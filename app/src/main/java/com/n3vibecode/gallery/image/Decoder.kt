package com.n3vibecode.gallery.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.n3vibecode.gallery.data.Formats
import com.n3vibecode.gallery.data.MediaItem
import java.io.InputStream

/** Über welchen Weg das Bild entstanden ist (für den Hinweis in der Detailansicht). */
enum class DecodePath {
    VIDEO, NATIVE, HEIF_LIB, RAW_PREVIEW, EMBEDDED_JPEG, NONE
}

data class DecodeResult(val bitmap: Bitmap?, val path: DecodePath)

/**
 * Die Anzeige-Engine. Reihenfolge:
 *   1. Video            → Einzelbild über MediaMetadataRetriever
 *   2. Systemdekoder    → ImageDecoder / BitmapFactory (JPEG, PNG, WebP, GIF, BMP, TIFF, einfache HEICs, AVIF ab Android 12)
 *   3. libheif          → integrierter HEIF/AVIF-Decoder: gekachelte Apple-Container (Screenshots!),
 *                          10-Bit-/HDR-HEIF, AVIF auch unter Android 12
 *   4. RAW              → eingebettete Kamera-Vorschau (größter JPEG-Block) – Standard bei DNG/CR2/NEF/ARW …
 *   5. Fallback         → irgendwo eingebettetes JPEG suchen (fängt exotische Container ab,
 *                          z. B. HEIF-Varianten mit JPEG-kodiertem Bild – dieselbe Huffman-/Zickzack-Kodierung)
 */
object Decoder {

    fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        if (w <= 0 || h <= 0 || maxPx <= 0) return 1
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxPx * 0.75) sample *= 2
        return sample
    }

    fun decode(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? = decodeDetailed(ctx, item, maxPx).bitmap

    fun decodeDetailed(ctx: Context, item: MediaItem, maxPx: Int): DecodeResult {
        try {
            // 1) Video
            if (item.isVideoFile) {
                val frame = videoFrame(ctx, item, maxPx)
                if (frame != null) return DecodeResult(frame, DecodePath.VIDEO)
                // Standbild-Fallback für Videos: eingebettetes Vorschaubild
                val p = rawPreview(ctx, item, maxPx)
                if (p != null) return DecodeResult(p, DecodePath.RAW_PREVIEW)
                return DecodeResult(null, DecodePath.NONE)
            }

            val isHeifFamily = item.isHeif || item.isAvif || Formats.isHeif(item.ext) || Formats.isAvif(item.ext)

            // 2) RAW: zuerst die eingebettete Kamera-Vorschau (volle Qualität), dann System, dann libheif
            if (item.isRaw) {
                rawPreview(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.RAW_PREVIEW) }
                platformDecode(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.NATIVE) }
                heifDecode(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.HEIF_LIB) }
                embeddedJpeg(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.EMBEDDED_JPEG) }
                return DecodeResult(null, DecodePath.NONE)
            }

            // 3) HEIF/AVIF: System zuerst (schnell), sonst der eingebaute libheif-Decoder
            if (isHeifFamily) {
                platformDecode(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.NATIVE) }
                heifDecode(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.HEIF_LIB) }
                embeddedJpeg(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.EMBEDDED_JPEG) }
                return DecodeResult(null, DecodePath.NONE)
            }

            // 4) Alles andere (JPEG, PNG, WebP, GIF, BMP, TIFF …)
            platformDecode(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.NATIVE) }
            heifDecode(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.HEIF_LIB) }
            embeddedJpeg(ctx, item, maxPx)?.let { return DecodeResult(it, DecodePath.EMBEDDED_JPEG) }
            return DecodeResult(null, DecodePath.NONE)
        } catch (t: Throwable) {
            return DecodeResult(null, DecodePath.NONE)
        }
    }

    // ------------------------------------------------------------------ Video

    private fun videoFrame(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, Uri.parse(item.uri))
            val raw = mmr.frameAtTime ?: return null
            val w = raw.width
            val h = raw.height
            val sample = sampleSize(w, h, maxPx)
            return if (sample > 1) {
                Bitmap.createScaledBitmap(raw, (w / sample).coerceAtLeast(1), (h / sample).coerceAtLeast(1), true)
            } else raw
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { mmr.release() }
        }
    }

    // ------------------------------------------------------------------ libheif (HEIF/HEIC/AVIF)

    private fun heifDecode(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? {
        if (!HeifSupport.available) return null
        return HeifSupport.decode(ctx, Uri.parse(item.uri), maxPx)
    }

    // ------------------------------------------------------------------ Systemdekoder

    fun platformDecode(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? {
        val uri = Uri.parse(item.uri)
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val source = ImageDecoder.createSource(ctx.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val sample = sampleSize(info.size.width, info.size.height, maxPx)
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                    if (Build.VERSION.SDK_INT >= 29) decoder.isUnpremultipliedRequired = false
                }
                if (bmp != null) return bmp
            } catch (_: Throwable) {
                // weiter mit BitmapFactory
            } catch (_: OutOfMemoryError) {
            }
        }
        return bitmapFactoryDecode(ctx, uri, maxPx)
    }

    private fun bitmapFactoryDecode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            applyExifRotation(ctx, uri, bmp)
        } catch (_: Throwable) {
            null
        }
    }

    fun applyExifRotation(ctx: Context, uri: Uri, bmp: Bitmap): Bitmap {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                val m = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                }
                if (m.isIdentity) bmp
                else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } ?: bmp
        } catch (_: Throwable) {
            bmp
        }
    }

    // ------------------------------------------------------------------ RAW

    /** Liest die größte eingebettete JPEG-Vorschau (Standard bei praktisch allen Kamera-RAWs). */
    private fun rawPreview(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? {
        val size = item.size
        val scanLimit = if (size in 1..(400L * 1024 * 1024)) size else 192L * 1024 * 1024
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                val range = JpegFinder.largestRange(buffered(input), scanLimit) ?: return@use null
                ctx.contentResolver.openInputStream(Uri.parse(item.uri))?.use { second ->
                    val data = JpegFinder.read(second, range) ?: return@use null
                    decodeBytes(data, maxPx)
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Fallback: irgendwo eingebettetes JPEG finden (auch bei HEIF-/TIFF-/Exoten-Containern). */
    private fun embeddedJpeg(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? {
        val size = item.size
        val scanLimit = if (size in 1..(200L * 1024 * 1024)) size else 128L * 1024 * 1024
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                val range = JpegFinder.largestRange(buffered(input), scanLimit) ?: return@use null
                if (range.length < 1024) return@use null
                ctx.contentResolver.openInputStream(Uri.parse(item.uri))?.use { second ->
                    val data = JpegFinder.read(second, range) ?: return@use null
                    decodeBytes(data, maxPx)
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun decodeBytes(data: ByteArray, maxPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0) return null
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        } catch (t: Throwable) {
            null
        }
    }

    /** Extrahiert ein JPEG-kodiertes HEIF-Bild anhand der iloc-Box. */
    fun decodeHeifJpegItem(ctx: Context, item: MediaItem, info: HeifInspector.Info, maxPx: Int): Bitmap? {
        val jpegItems = info.jpegItems().sortedByDescending { it.size }
        val uri = Uri.parse(item.uri)
        for (ji in jpegItems) {
            for (ext in ji.extents) {
                if (ext.length < 512 || ext.length > 64L * 1024 * 1024) continue
                val bytes = readRange(ctx, uri, ext.offset, ext.length.toInt()) ?: continue
                if (!JpegFinder.looksLikeJpeg(bytes)) continue
                decodeBytes(bytes, maxPx)?.let { return it }
            }
        }
        return null
    }

    fun readRange(ctx: Context, uri: Uri, offset: Long, length: Int): ByteArray? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val s = input.skip(offset - skipped)
                    if (s <= 0) {
                        if (input.read() < 0) return null
                        skipped++
                    } else skipped += s
                }
                val out = ByteArray(length)
                var off = 0
                while (off < length) {
                    val r = input.read(out, off, length - off)
                    if (r < 0) break
                    off += r
                }
                if (off == length) out else out.copyOf(off)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buffered(input: InputStream) = java.io.BufferedInputStream(input, 1 shl 20)

    // ------------------------------------------------------------------ Hinweise

    fun hintFor(item: MediaItem): String? = when {
        item.isRaw -> "RAW – Anzeige über die eingebettete Kamera-Vorschau"
        item.isAvif && HeifSupport.available -> null
        item.isAvif && Build.VERSION.SDK_INT < 31 -> "AVIF-Vorschau ab Android 12 – Metadaten unten trotzdem vollständig"
        item.isHeif -> "HEIF/HEIC – inkl. Apple-Screenshot-Container"
        else -> null
    }

    fun hintForPath(path: DecodePath): String? = when (path) {
        DecodePath.HEIF_LIB -> "Angezeigt mit dem integrierten HEIF-Decoder (libheif) – gekachelte & 10-Bit-Apple-Container"
        DecodePath.RAW_PREVIEW -> "Angezeigt aus der eingebetteten Kamera-Vorschau"
        DecodePath.EMBEDDED_JPEG -> "Angezeigt aus dem eingebetteten JPEG im Container"
        else -> null
    }
}
