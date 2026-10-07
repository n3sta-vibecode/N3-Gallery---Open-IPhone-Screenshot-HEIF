package com.n3vibecode.gallery.security

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Alle Schreib- und Löschvorgänge auf Dateien laufen ausschließlich hier durch.
 *
 * Die ungehärtete Fassung hatte mehrere kritische Stellen:
 *
 *  1. `File(GalleryApp.shareDir(), item.name)` (DetailActivity, InfoActivity,
 *     MediaSaver) – `item.name` stammte aus `Uri.getLastPathSegment()`, welches
 *     Prozent-Kodierung **dekodiert**. Aus `%2e%2e%2f%2e%2e%2fshared_prefs%2fpwn.xml`
 *     wurde `../../shared_prefs/pwn.xml` und die Galerie schrieb Angreifer-Inhalt in
 *     ihr eigenes privates Datenverzeichnis.
 *
 *  2. `File(uri.path).delete()` (DetailActivity.directDelete, GalleryFragments.deleteDirect)
 *     – für jedes Schema außer `content` wurde direkt gelöscht, ohne
 *     MediaStore-Systemdialog. Der Pfad kam vom Angreifer.
 *
 *  3. `File(uri.path).outputStream()` (MediaSaver.overwrite) – für `file://` wurde
 *     direkt überschrieben; der Pfad kam vom Angreifer.
 *
 * Gegenmaßnahmen:
 *  • [confinedChild] erzwingt, dass eine Zieldatei **innerhalb** ihres Verzeichnisses
 *    bleibt – geprüft am kanonischen Pfad, nicht am String.
 *  • [deleteMedia]/[deleteViaResolver] löschen nur noch über den ContentResolver bzw.
 *    `MediaStore.createDeleteRequest`. `File.delete()` auf fremdbestimmten Pfaden gibt
 *    es nicht mehr.
 *  • [overwriteViaResolver] schreibt nur noch über den ContentResolver.
 */
object SafeFiles {

    /**
     * Erzeugt eine Datei unterhalb von [dir] und garantiert, dass sie dort bleibt.
     *
     * @throws SecurityException wenn der bereinigte Name aus dem Verzeichnis ausbricht.
     */
    fun confinedChild(dir: File, rawName: String?): File {
        val base = dir.canonicalFile
        val name = UriGuard.safeFileName(rawName)

        val candidate = File(base, name)
        val canon = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            throw SecurityException("Zielpfad nicht auflösbar: ${e.message}")
        }

        // Entscheidender Check: Der kanonische Pfad muss mit dem kanonischen
        // Verzeichnis beginnen. Ein Separator wird angehängt, damit "/data/x-share"
        // nicht als Kind von "/data/x" durchgeht.
        val prefix = base.absolutePath + File.separator
        if (!canon.absolutePath.startsWith(prefix)) {
            throw SecurityException(
                "Path Traversal abgewiesen: Ziel läge außerhalb von ${base.name}/"
            )
        }
        return canon
    }

    /**
     * Schreibt [source] in eine Datei unterhalb von [dir] – atomar über eine
     * temporäre Datei und mit harter Größenobergrenze.
     *
     * @return die tatsächlich geschriebene Datei, oder `null` bei Verletzung.
     */
    fun writeConfinedCopy(
        ctx: Context,
        dir: File,
        rawName: String?,
        source: Uri,
        maxBytes: Long = DEFAULT_MAX_COPY_BYTES
    ): File? {
        val target = try {
            confinedChild(dir, rawName)
        } catch (se: SecurityException) {
            return null
        }

        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
        var bytesWritten = 0L
        try {
            ctx.contentResolver.openInputStream(source)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        bytesWritten += n
                        if (bytesWritten > maxBytes) {
                            throw SecurityException(
                                "Kopie abgebrochen: über ${maxBytes / (1024 * 1024)} MB"
                            )
                        }
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null

            if (tmp.canonicalFile.parentFile?.absolutePath != target.parentFile?.absolutePath) {
                throw SecurityException("Temporäre Datei liegt im falschen Verzeichnis")
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) return null
            }
            return target
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /**
     * Schreibt [bytes] in eine Datei unterhalb von [dir] – mit Traversal-Schutz.
     * Ersetzt `File(shareDir(), name)` an allen Stellen, die Bytes ablegen.
     *
     * @return die geschriebene Datei, oder `null` bei einer Pfadverletzung.
     */
    fun writeConfinedBytes(dir: File, rawName: String?, bytes: ByteArray): File? {
        val target = try {
            confinedChild(dir, rawName)
        } catch (se: SecurityException) {
            return null
        }
        return try {
            target.writeBytes(bytes)
            if (target.length() > 0) target else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Eindeutige, nicht vorhersagbare Datei in [dir].
     *
     * Ersetzt die alten festen Namen `n3_write_tmp.<ext>` (ExifWriter) und
     * `n3_edit_tmp.<ext>` (MediaSaver): ein konstanter Name im Cache ist ein
     * Race-Target und überschreibt sich bei zwei parallelen Vorgängen.
     */
    fun uniqueTemp(dir: File, ext: String): File {
        val safeExt = UriGuard.safeFileName(ext).take(8).ifBlank { "img" }
        return File(dir, "n3_tmp_${System.nanoTime()}_${(0..9999).random()}.$safeExt")
    }

    // ------------------------------------------------------------------ Überschreiben

    /**
     * Überschreibt ein Medium – ausschließlich über den ContentResolver.
     *
     * Ersetzt `MediaSaver.overwrite()`-Pfad `File(uri.path).outputStream()`: Für
     * `file://` entschied dort der Aufrufer, welche Datei überschrieben wird.
     * Nicht-`content`-URIs werden abgelehnt.
     *
     * @return true, wenn geschrieben wurde; wirft [SecurityException] bei unzulässiger URI.
     */
    fun overwriteViaResolver(ctx: Context, uri: Uri, mode: String, writer: (java.io.OutputStream) -> Unit): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, true)) {
            throw SecurityException(
                "Überschreiben nur für content://-Medien erlaubt (Schema war '${uri.scheme}')"
            )
        }
        if (uri.isOpaque) throw SecurityException("opaque URI nicht beschreibbar")
        ctx.contentResolver.openOutputStream(uri, mode)?.use { out ->
            writer(out)
        } ?: return false
        runCatching { ctx.contentResolver.notifyChange(uri, null) }
        return true
    }

    // ------------------------------------------------------------------ Löschen

    sealed class DeleteOutcome {
        data class NeedsConfirmation(val sender: android.content.IntentSender) : DeleteOutcome()
        object Deleted : DeleteOutcome()
        data class Refused(val reason: String) : DeleteOutcome()
        data class Failed(val reason: String) : DeleteOutcome()
    }

    /**
     * Löscht ein Medium **ausschließlich** über den ContentResolver.
     * `file://`-URIs und alles, was nicht `content://` ist, werden abgewiesen.
     */
    fun deleteViaResolver(ctx: Context, uri: Uri): DeleteOutcome {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
            return DeleteOutcome.Refused(
                "Löschen nur für content://-Medien erlaubt (Schema war '${uri.scheme}')"
            )
        }
        if (uri.isOpaque) return DeleteOutcome.Refused("opaque URI nicht löschbar")

        if (Build.VERSION.SDK_INT >= 29 && UriGuard.isMediaStore(uri)) {
            val sender = try {
                MediaStore.createDeleteRequest(ctx.contentResolver, listOf(uri)).intentSender
            } catch (_: Throwable) {
                null
            }
            if (sender != null) return DeleteOutcome.NeedsConfirmation(sender)
        }

        return try {
            val rows = ctx.contentResolver.delete(uri, null, null)
            if (rows > 0) DeleteOutcome.Deleted
            else DeleteOutcome.Failed("Provider hat nichts gelöscht (0 Zeilen)")
        } catch (rse: Throwable) {
            val sender = runCatching {
                (rse as? android.app.RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            }.getOrNull()
            if (sender != null) DeleteOutcome.NeedsConfirmation(sender)
            else DeleteOutcome.Failed(rse.message ?: "Löschen fehlgeschlagen")
        }
    }

    /** Liegt diese Datei nachweislich im privaten Bereich der eigenen App? */
    fun isOwnPrivateFile(ctx: Context, file: File): Boolean {
        val allowed = listOf(ctx.cacheDir, ctx.filesDir, ctx.externalCacheDir, ctx.codeCacheDir)
            .filterNotNull()
            .map { runCatching { it.canonicalFile.absolutePath + File.separator }.getOrNull() }
            .filterNotNull()
        val canon = runCatching { file.canonicalFile.absolutePath }.getOrNull() ?: return false
        return allowed.any { canon.startsWith(it) }
    }

    /** Löscht eine app-eigene Datei – nur nach dem Nachweis aus [isOwnPrivateFile]. */
    fun deleteOwnCacheFile(ctx: Context, file: File): Boolean {
        if (!isOwnPrivateFile(ctx, file)) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * Löscht ein Medium und wählt selbst den richtigen Weg.
     *
     *  • `content://` einer FileProvider der EIGENEN App → die Datei liegt im privaten
     *    Cache. `FileProvider.delete()` ist nicht implementiert, also wird über den
     *    zugehörigen Pfad gelöscht – aber nur mit dem Sandbox-Nachweis.
     *  • alles andere → [deleteViaResolver] (MediaStore-Systemdialog bzw. Resolver).
     *  • `file://` und unbekannte Schemata → abgelehnt.
     */
    fun deleteMedia(ctx: Context, uri: Uri): DeleteOutcome {
        val ownAuthority = ctx.packageName + ".fileprovider"
        if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, true) &&
            uri.authority == ownAuthority
        ) {
            val path = runCatching { fileProviderPath(ctx, ownAuthority, uri) }.getOrNull()
            if (path != null) {
                val f = File(path)
                return if (deleteOwnCacheFile(ctx, f)) {
                    DeleteOutcome.Deleted
                } else {
                    DeleteOutcome.Failed("App-eigene Datei konnte nicht gelöscht werden")
                }
            }
            return DeleteOutcome.Refused("Pfad der eigenen FileProvider-URI nicht bestimmbar")
        }
        return deleteViaResolver(ctx, uri)
    }

    /**
     * Macht die Pfad-Zuordnung der FileProvider rückgängig, indem die deklarierten
     * Wurzeln durchprobiert werden (cacheDir, externalCacheDir).
     */
    private fun fileProviderPath(ctx: Context, authority: String, uri: Uri): String? {
        val roots = listOf(ctx.cacheDir, ctx.externalCacheDir).filterNotNull()
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        val name = segments.first()
        val rest = segments.drop(1).joinToString("/")
        if (UriGuard.containsTraversal("/$rest")) return null

        val candidates = roots.map { File(File(it, name), rest) }
        return candidates
            .firstOrNull { isOwnPrivateFile(ctx, it) && it.exists() }
            ?.canonicalFile?.absolutePath
    }

    /**
     * Obergrenze für Kopien in den geteilten Cache. Dient auch als DoS-Bremse: Die
     * ungehärtete Fassung pufferte bis zu 256 MB vollständig im RAM.
     */
    const val DEFAULT_MAX_COPY_BYTES = 96L * 1024 * 1024
}
