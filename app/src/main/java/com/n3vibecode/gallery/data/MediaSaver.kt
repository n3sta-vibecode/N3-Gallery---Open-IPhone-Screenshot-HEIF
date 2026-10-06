package com.n3vibecode.gallery.data

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speichert bearbeitete Fotos.
 *
 *  • [saveCopy]    – legt eine neue Datei an (Bilder/N3 Gallery) und trägt sie in die Galerie ein.
 *  • [overwrite]   – schreibt in die Originaldatei; verlangt Android eine Bestätigung, kommt
 *                    [Result.NeedsPermission] mit dem passenden IntentSender zurück.
 *
 * Es wird nie etwas gelöscht: Ohne Bestätigung bleibt das Original unverändert.
 */
object MediaSaver {

    const val ALBUM = "N3 Gallery"

    sealed class Result {
        data class Success(val uri: Uri, val name: String) : Result()

        /**
         * Die Galerie hat die Datei nicht angenommen. Das Bild ist deshalb **nicht verloren**:
         * es liegt im Teilen-Ordner und kann direkt weitergegeben werden (Teilen-Knopf).
         */
        data class ShareOnly(val file: File, val reason: String) : Result()

        data class NeedsPermission(val intentSender: IntentSender) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Formate, in denen ein bearbeitetes Bild sinnvoll gespeichert werden kann. */
    fun canOverwrite(item: MediaItem): Boolean {
        if (item.isVideoFile) return false
        val ext = item.ext.lowercase(Locale.ROOT)
        return ext in setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp")
    }

    private fun fileName(base: String, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val stem = base.substringBeforeLast('.').take(52).ifBlank { "Foto" }
        return "${stem}_bearbeitet_$stamp.$ext"
    }

    private fun format(bitmap: Bitmap, forceJpeg: Boolean, ext: String): Bitmap.CompressFormat = when {
        forceJpeg -> Bitmap.CompressFormat.JPEG
        ext == "png" -> Bitmap.CompressFormat.PNG
        // WEBP gibt es schon lange; die neuen Varianten (LOSSY/LOSSLESS) erst ab Android 11
        ext == "webp" -> Bitmap.CompressFormat.WEBP
        bitmap.hasAlpha() -> Bitmap.CompressFormat.PNG
        else -> Bitmap.CompressFormat.JPEG
    }

    private fun extension(fmt: Bitmap.CompressFormat): String = when (fmt) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.WEBP -> "webp"
        else -> "jpg"
    }

    private fun mime(fmt: Bitmap.CompressFormat): String = when (fmt) {
        Bitmap.CompressFormat.PNG -> "image/png"
        Bitmap.CompressFormat.WEBP -> "image/webp"
        else -> "image/jpeg"
    }

    private fun write(out: OutputStream, bitmap: Bitmap, fmt: Bitmap.CompressFormat): Boolean =
        bitmap.compress(fmt, 96, out)

    /**
     * Bearbeitetes Bild als neue Datei speichern (Original bleibt unangetastet).
     * [jpeg] = true schreibt ein JPEG (klein, Standard für Fotos); bei Bildern mit
     * Transparenz wird PNG verwendet.
     */
    fun saveCopy(ctx: Context, bitmap: Bitmap, baseName: String, jpeg: Boolean = true): Result {
        val fmt = format(bitmap, jpeg, "")
        val name = fileName(baseName, extension(fmt))
        val problems = StringBuilder()

        // 1) Galerie über MediaStore (ab Android 10): Pictures/N3 Gallery
        if (Build.VERSION.SDK_INT >= 29) {
            val album = saveViaMediaStore(ctx, bitmap, fmt, name, ALBUM)
            if (album is Result.Success) return album
            if (album is Result.Failed) problems.append(album.message)

            // 2) Manche Galerien mögen keinen Unterordner: dann direkt in Pictures.
            //    Und für den Fall, dass IS_PENDING abgelehnt wird, ohne PENDING-Flag.
            val top = saveViaMediaStore(ctx, bitmap, fmt, name, null)
            if (top is Result.Success) return top
            if (top is Result.Failed) problems.append(" / ").append(top.message)
        }

        // 3) Dateiweg: öffentlicher Bilder-Ordner (Android 8/9 mit Freigabe)
        val viaFile = saveViaFile(ctx, bitmap, fmt, name)
        if (viaFile is Result.Success) return viaFile
        if (viaFile is Result.Failed) problems.append(" / ").append(viaFile.message)

        // 4) Letzter Ausweg: ablegen und zum Teilen anbieten – nichts geht verloren.
        //    Früher landete das Bild in einem app-internen Ordner, den keine Galerie zeigt
        //    („gespeichert“, aber nirgends zu sehen) – das ist jetzt nicht mehr so.
        val share = saveForSharing(ctx, bitmap, fmt, name)
        if (share != null) {
            return Result.ShareOnly(share, problems.toString().trim(' ', '/'))
        }
        return Result.Failed(problems.toString().ifBlank { "Unbekannter Fehler beim Speichern" })
    }

    /** Ablage im Teilen-Ordner (FileProvider) für den letzten Ausweg. */
    private fun saveForSharing(ctx: Context, bitmap: Bitmap, fmt: Bitmap.CompressFormat, name: String): File? = try {
        val file = File(com.n3vibecode.gallery.GalleryApp.shareDir(), name)
        file.outputStream().use { out ->
            if (!write(out, bitmap, fmt)) throw IllegalStateException("Schreiben fehlgeschlagen")
        }
        if (file.length() > 0) file else null
    } catch (_: Throwable) {
        null
    }

    private fun saveViaMediaStore(
        ctx: Context,
        bitmap: Bitmap,
        fmt: Bitmap.CompressFormat,
        name: String,
        album: String?
    ): Result {
        if (Build.VERSION.SDK_INT < 29) {
            return Result.Failed("Android 8/9: Galerie-Eintrag nur über den Dateiweg")
        }
        var inserted: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime(fmt))
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    if (album.isNullOrBlank()) Environment.DIRECTORY_PICTURES
                    else "${Environment.DIRECTORY_PICTURES}/$album"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result.Failed("die Galerie nahm keinen Eintrag an")
            inserted = uri
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                if (!write(out, bitmap, fmt)) throw IllegalStateException("Schreiben fehlgeschlagen")
            } ?: throw IllegalStateException("Datei ließ sich nicht öffnen")

            // Nachsehen, dass wirklich Daten angekommen sind. Sonst bliebe ein leeres Bild
            // in der Galerie stehen – genau das sah aus wie „Speichern funktioniert nicht“.
            if (contentOk(ctx, uri) == false) throw IllegalStateException("die Datei blieb leer")

            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
            ctx.contentResolver.notifyChange(uri, null)
            Result.Success(uri, name)
        } catch (se: SecurityException) {
            // Kaputten Eintrag entfernen (falls schon angelegt)
            inserted?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            Result.Failed("keine Schreibberechtigung für die Galerie")
        } catch (t: Throwable) {
            inserted?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Prüft, ob die geschriebene Datei wirklich Inhalt hat.
     * true = Daten da, false = leer, null = nicht prüfbar (dann nichts löschen!).
     */
    private fun contentOk(ctx: Context, uri: Uri): Boolean? = try {
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        var read = 0
        input.use { ins ->
            val buf = ByteArray(4096)
            while (read <= 8) {
                val n = ins.read(buf)
                if (n <= 0) break
                read += n
            }
        }
        read > 0
    } catch (_: Throwable) {
        null
    }

    /**
     * Dateiweg: öffentlicher Bilder-Ordner (Android 8/9) und – als letzter Ausweg, aber
     * immer möglich – der app-eigene Bilder-Ordner. Die Datei wird anschließend in den
     * Medienindex aufgenommen, sodass sie in der Galerie erscheint.
     */
    private fun saveViaFile(
        ctx: Context,
        bitmap: Bitmap,
        fmt: Bitmap.CompressFormat,
        name: String
    ): Result {
        val dirs = mutableListOf<File>()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 29) {
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            dirs += File(pictures, ALBUM)
            dirs += pictures
        }
        // Wichtig: KEIN app-interner Ordner mehr. Dort landete das Bild früher, war aber in
        // keiner Galerie zu sehen – der Nutzer sah „gespeichert“ und fand nichts.
        var lastError: String? = null
        for (dir in dirs) {
            try {
                if (!dir.exists() && !dir.mkdirs()) continue
                val file = File(dir, name)
                file.outputStream().use { out ->
                    if (!write(out, bitmap, fmt)) throw IllegalStateException("Schreiben fehlgeschlagen")
                }
                // Leere Datei wäre in der Galerie ein kaputtes Bild
                if (file.length() <= 0) throw IllegalStateException("die Datei blieb leer")
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(file.absolutePath), arrayOf(mime(fmt)), null
                )
                return Result.Success(Uri.fromFile(file), file.absolutePath)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
        return Result.Failed(lastError ?: "Kein beschreibbarer Ordner gefunden")
    }

    /** Originaldatei ersetzen. */
    fun overwrite(ctx: Context, item: MediaItem, bitmap: Bitmap): Result {
        val uri = Uri.parse(item.uri)
        if (item.isVideoFile) return Result.Failed("Videos können nicht überschrieben werden")
        if (!canOverwrite(item)) {
            return Result.Failed("Das Format ${item.format} lässt sich nicht überschreiben – bitte als Kopie speichern")
        }
        if (uri.scheme != "content") {
            return try {
                val target = File(uri.path ?: return Result.Failed("Datei nicht gefunden"))
                val fmt = format(bitmap, forceJpeg = true, ext = item.ext.lowercase(Locale.ROOT))
                target.outputStream().use { out ->
                    if (!write(out, bitmap, fmt)) throw IllegalStateException("Schreiben fehlgeschlagen")
                }
                Result.Success(Uri.fromFile(target), target.name)
            } catch (t: Throwable) {
                Result.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
        val fmt = format(bitmap, forceJpeg = item.ext.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "jpe", "jfif"), ext = item.ext.lowercase(Locale.ROOT))
        return try {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                if (!write(out, bitmap, fmt)) throw IllegalStateException("Speichern fehlgeschlagen")
            } ?: return Result.Failed("Datei konnte nicht geöffnet werden")
            ctx.contentResolver.notifyChange(uri, null)
            Result.Success(uri, item.name)
        } catch (se: SecurityException) {
            val sender = writeRequest(ctx, uri)
            if (sender != null) Result.NeedsPermission(sender)
            else Result.Failed(se.message ?: "Kein Schreibzugriff")
        } catch (t: Throwable) {
            // Manche Provider können nicht direkt überschreiben – dann über eine Kopie schreiben
            try {
                val tmp = File(ctx.cacheDir, "n3_edit_tmp." + extension(fmt))
                tmp.outputStream().use { out -> if (!write(out, bitmap, fmt)) throw IllegalStateException("Speichern fehlgeschlagen") }
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    ?: return Result.Failed("Datei konnte nicht geöffnet werden")
                tmp.delete()
                ctx.contentResolver.notifyChange(uri, null)
                Result.Success(uri, item.name)
            } catch (t2: SecurityException) {
                val sender = writeRequest(ctx, uri)
                if (sender != null) Result.NeedsPermission(sender) else Result.Failed(t2.message ?: "Kein Schreibzugriff")
            } catch (t2: Throwable) {
                Result.Failed(t2.message ?: t.message ?: "Unbekannter Fehler")
            }
        }
    }

    /** Systembestätigung für das Schreiben (Android 11+). */
    private fun writeRequest(ctx: Context, uri: Uri): IntentSender? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.createWriteRequest(ctx.contentResolver, listOf(uri)).intentSender
        } else null
    } catch (_: Throwable) {
        null
    }
}
