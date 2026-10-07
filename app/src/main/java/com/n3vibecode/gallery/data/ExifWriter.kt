package com.n3vibecode.gallery.data

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.n3vibecode.gallery.security.SafeFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt Notizen und Tags als echte Metadaten in die Bilddatei.
 *
 * Wege:
 *  1. Direkt in die Datei über den (seekable) Dateideskriptor – schnell, kein Kopieren.
 *  2. Falls der Provider das nicht erlaubt: Kopie bearbeiten und zurückschreiben.
 *  3. Falls Android eine Bestätigung verlangt (API 29+): NeedsPermission mit IntentSender.
 *
 * Gesetzt werden:
 *  • EXIF ImageDescription        → Notiz (Apple Fotos, Google Fotos, Lightroom lesen das als „Beschriftung“)
 *  • EXIF UserComment             → Notiz + Tags
 *  • EXIF Software                → „N3 Photos“
 *  • XMP dc:description / dc:subject → Notiz & Tags (wird in ein vorhandenes XMP-Paket gemerged)
 */
object ExifWriter {

    sealed class Result {
        object Success : Result()
        data class NeedsPermission(val intentSender: IntentSender) : Result()
        data class Unsupported(val reason: String) : Result()
        data class Failed(val message: String) : Result()
    }

    fun save(ctx: Context, item: MediaItem, note: String, tags: List<String>): Result {
        // Immer zuerst app-intern sichern – nichts geht verloren.
        MetaStore.setNote(item.uri, note)
        MetaStore.setTags(item.uri, tags)

        if (!ExifRepository.isWriteSupported(item)) {
            return Result.Unsupported(
                "${item.format} lässt sich von Android nicht beschreiben – die Notiz ist in der App gespeichert."
            )
        }

        val uri = Uri.parse(item.uri)

        // Schreibzugriff prüfen / anfordern
        try {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { /* Test */ }
        } catch (rse: RecoverableSecurityException) {
            MetaStore.rememberPending(item.uri, note, tags)
            return Result.NeedsPermission(rse.userAction.actionIntent.intentSender)
        } catch (se: SecurityException) {
            val sender = writeRequestSender(ctx, uri)
            if (sender != null) {
                MetaStore.rememberPending(item.uri, note, tags)
                return Result.NeedsPermission(sender)
            }
            return Result.Failed("Kein Schreibzugriff auf diese Datei.")
        } catch (_: Throwable) {
            // file:// oder Provider ohne FD – der Kopie-Weg unten übernimmt das
        }

        // 1) direkt schreiben
        try {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                applyAttributes(exif, note, tags)
                exif.saveAttributes()
            }
            ctx.contentResolver.notifyChange(uri, null)
            MetaStore.clearPending(item.uri)
            return Result.Success
        } catch (t: Throwable) {
            // 2) über Kopie
            return try {
                writeViaTempFile(ctx, uri, item, note, tags)
                ctx.contentResolver.notifyChange(uri, null)
                MetaStore.clearPending(item.uri)
                Result.Success
            } catch (t2: Throwable) {
                Result.Failed(t2.message ?: t.message ?: "Unbekannter Fehler beim Schreiben")
            }
        }
    }

    // ------------------------------------------------------------------ Wege

    private fun writeViaTempFile(ctx: Context, uri: Uri, item: MediaItem, note: String, tags: List<String>) {
        // GEHÄRTET: fester Name war ein Race-Target und item.ext floss unbereinigt in
        // einen Dateinamen. SafeFiles.uniqueTemp erzeugt einen eindeutigen, sicheren Namen.
        val tmp = SafeFiles.uniqueTemp(ctx.cacheDir, item.ext.ifEmpty { "img" })
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Datei konnte nicht gelesen werden")

            val exif = ExifInterface(tmp.absolutePath)
            applyAttributes(exif, note, tags)
            exif.saveAttributes()

            var lastError: Throwable? = null
            for (mode in listOf("rwt", "wt", "w")) {
                try {
                    ctx.contentResolver.openOutputStream(uri, mode)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                    return
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw lastError ?: IllegalStateException("Schreiben fehlgeschlagen")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun applyAttributes(exif: ExifInterface, note: String, tags: List<String>) {
        runCatching { exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, note.ifBlank { null }) }
        runCatching { exif.setAttribute(ExifInterface.TAG_USER_COMMENT, buildComment(note, tags)) }
        runCatching { exif.setAttribute(ExifInterface.TAG_SOFTWARE, "N3 Photos") }
        val merged = runCatching { mergeXmp(exif.getAttribute(ExifInterface.TAG_XMP), note, tags) }.getOrNull()
        if (merged != null) {
            runCatching { exif.setAttribute(ExifInterface.TAG_XMP, merged) }
        }
    }

    private fun buildComment(note: String, tags: List<String>): String? {
        val parts = mutableListOf<String>()
        if (note.isNotBlank()) parts += note
        if (tags.isNotEmpty()) parts += "Tags: " + tags.joinToString(", ")
        return parts.joinToString("  |  ").ifBlank { null }
    }

    // ------------------------------------------------------------------ XMP

    fun mergeXmp(existing: String?, note: String, tags: List<String>): String? {
        if (note.isBlank() && tags.isEmpty()) return null
        val subject = if (tags.isEmpty()) "" else
            "<dc:subject><rdf:Bag>" + tags.joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" } + "</rdf:Bag></dc:subject>"
        val description = if (note.isBlank()) "" else
            "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(note)}</rdf:li></rdf:Alt></dc:description>"
        val modify = "<xmp:ModifyDate>${isoNow()}</xmp:ModifyDate>"

        if (existing.isNullOrBlank() || !existing.contains("rdf:Description")) {
            return """<?xpacket begin="&#xFEFF;" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:xmp="http://ns.adobe.com/xap/1.0/">
   <xmp:CreatorTool>N3 Photos</xmp:CreatorTool>
   $modify
   $subject
   $description
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
        }

        var x = existing
        x = x.replace(Regex("<dc:subject>.*?</dc:subject>", RegexOption.DOT_MATCHES_ALL), "")
        x = x.replace(Regex("<dc:description>.*?</dc:description>", RegexOption.DOT_MATCHES_ALL), "")
        x = x.replace(Regex("<xmp:ModifyDate>.*?</xmp:ModifyDate>", RegexOption.DOT_MATCHES_ALL), "")
        if (!x.contains("xmlns:dc")) {
            x = x.replaceFirst(
                Regex("<rdf:Description([^>]*?)>"),
                "<rdf:Description$1 xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
            )
        }
        if (!x.contains("xmlns:xmp")) {
            x = x.replaceFirst(
                Regex("<rdf:Description([^>]*?)>"),
                "<rdf:Description$1 xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">"
            )
        }
        val start = Regex("<rdf:Description[^>]*>").find(x)
        val body = subject + description + modify
        return if (start != null) {
            val idx = start.range.last + 1
            x.substring(0, idx) + body + x.substring(idx)
        } else {
            x + body
        }
    }

    /** Eigenständiges XMP-Paket für HEIC/RAW (Sidecar-Datei, wie Lightroom es macht). */
    fun buildSidecar(item: MediaItem, note: String, tags: List<String>): String {
        val subject = if (tags.isEmpty()) "" else
            "<dc:subject><rdf:Bag>" + tags.joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" } + "</rdf:Bag></dc:subject>"
        return """<?xpacket begin="&#xFEFF;" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/">
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">${esc(item.name)}</rdf:li></rdf:Alt></dc:title>
   <dc:description><rdf:Alt><rdf:li xml:lang="x-default">${esc(note)}</rdf:li></rdf:Alt></dc:description>
   $subject
   <xmp:CreatorTool>N3 Photos</xmp:CreatorTool>
   <xmp:ModifyDate>${isoNow()}</xmp:ModifyDate>
   <photoshop:CaptionWriter>N3 Vibecode</photoshop:CaptionWriter>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.GERMAN).format(Date())

    // ------------------------------------------------------------------ Rechte

    fun writeRequestSender(ctx: Context, uri: Uri): IntentSender? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val pi: PendingIntent = MediaStore.createWriteRequest(ctx.contentResolver, listOf(uri))
            pi.intentSender
        } catch (_: Throwable) {
            null
        }
    }
}
