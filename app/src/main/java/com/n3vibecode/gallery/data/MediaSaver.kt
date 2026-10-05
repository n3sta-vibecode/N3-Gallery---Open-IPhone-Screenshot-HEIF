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
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime(fmt))
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.Failed("Kein Zugriff auf die Galerie")
                ctx.contentResolver.openOutputStream(uri)?.use { write(it, bitmap, fmt) }
                    ?: return Result.Failed("Datei konnte nicht geschrieben werden")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                ctx.contentResolver.notifyChange(uri, null)
                Result.Success(uri, name)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    ALBUM
                ).apply { if (!exists()) mkdirs() }
                val file = File(dir, name)
                file.outputStream().use { out -> if (!write(out, bitmap, fmt)) throw IllegalStateException("Speichern fehlgeschlagen") }
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(file.absolutePath), arrayOf(mime(fmt)), null
                )
                Result.Success(Uri.fromFile(file), name)
            }
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "Unbekannter Fehler")
        }
    }

    /** Originaldatei ersetzen. */
    fun overwrite(ctx: Context, item: MediaItem, bitmap: Bitmap): Result {
        val uri = Uri.parse(item.uri)
        if (!canOverwrite(item) || uri.scheme != "content") {
            return Result.Failed("unsupported")
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
