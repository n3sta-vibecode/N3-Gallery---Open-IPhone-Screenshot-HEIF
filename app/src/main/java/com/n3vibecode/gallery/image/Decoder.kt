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

    /** Läuft gerade erfolgreich ein Hardware-/System-Dekodierweg für HEIF? */
    private val platformWins = java.util.concurrent.atomic.AtomicInteger(0)
    private val platformFails = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Dateien, bei denen der System-Dekoder nichts Brauchbares liefert. **Pro Datei**,
     * nicht global: Vorher schaltete sich der System-Weg nach drei Fehlversuchen für die
     * ganze Sitzung ab – dadurch liefen danach auch **normale** HEICs über den langsamen
     * Software-Decoder (mehrere Sekunden pro Bild). Jetzt wird nur die betroffene Datei
     * gemerkt.
     */
    private val platformFailed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Diagnose: nutzt das Gerät den System-Decoder für HEIFs? */
    fun usesSystemDecoderForHeif(): Boolean = platformWins.get() > 0 || platformFails.get() < 3

    // Welcher Weg hat für diese Datei funktioniert? (spart Wiederholungen)
    private const val PATH_HARDWARE = 1
    private const val PATH_SYSTEM = 2
    private const val PATH_LIBHEIF = 3
    private const val PATH_DARK_OK = 4

    private val goodPath = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Dateien, bei denen ein dunkles Vorschaubild normal ist (z. B. Nachtaufnahmen). */
    private val darkNormal = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isDarkNormal(uri: String): Boolean = darkNormal.containsKey(uri)

    fun markDarkNormal(uri: String) {
        darkNormal[uri] = true
    }

    /**
     * Erkennt (fast) einfarbig schwarze Bilder. Solche Ergebnisse entstehen bei manchen
     * Geräten, wenn 10-Bit-/HDR-HEIFs über den falschen Weg dekodiert werden – dann wird
     * der nächste Weg probiert, statt schwarze Kacheln zu zeigen.
     */
    fun looksUniformlyDark(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2) return false
        var dark = true
        var samples = 0
        for (gy in 0 until 6) {
            for (gx in 0 until 6) {
                val x = (w - 1) * gx / 5
                val y = (h - 1) * gy / 5
                val c = runCatching { bmp.getPixel(x, y) }.getOrElse { return false }
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                samples++
                if (r + g + b > 48) {   // mehr als „fast schwarz“
                    dark = false
                    break
                }
            }
            if (!dark) break
        }
        return dark && samples > 0
    }

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

            // 3) HEIF/AVIF: System zuerst (auf vielen Geräten Hardware-Decoder = sehr schnell),
            //    sonst der eingebaute libheif-Decoder.
            //
            //    Wichtig für die Geschwindigkeit: Kann das Gerät Apple-HEIFs (Kachel-Raster,
            //    10-Bit) nicht über den System-Decoder öffnen, wird das gemerkt – sonst kostet
            //    jeder einzelne Fehlversuch bei jedem Foto wieder Zeit.
            if (isHeifFamily) {
                // Schon bekannt, welcher Weg bei dieser Datei funktioniert? Dann direkt
                // dorthin – kein zweiter Fehlversuch bei jedem Anzeigen.
                when (goodPath[item.uri]) {
                    PATH_HARDWARE -> hardwareDecode(ctx, Uri.parse(item.uri), maxPx)
                        ?.let { return DecodeResult(it, DecodePath.NATIVE) }
                    PATH_SYSTEM, PATH_DARK_OK -> platformDecodeDetailed(ctx, item, maxPx)
                        ?.let { return DecodeResult(it.bitmap, DecodePath.NATIVE) }
                    PATH_LIBHEIF -> heifDecode(ctx, item, maxPx)
                        ?.let { return DecodeResult(it, DecodePath.HEIF_LIB) }
                }
                if (!platformFailed.containsKey(item.uri)) {
                    val viaSystem = platformDecodeDetailed(ctx, item, maxPx)
                    if (viaSystem != null) {
                        platformWins.incrementAndGet()
                        goodPath[item.uri] = when {
                            viaSystem.hardware && looksUniformlyDark(viaSystem.bitmap) -> PATH_DARK_OK
                            viaSystem.hardware -> PATH_HARDWARE
                            else -> PATH_SYSTEM
                        }
                        return DecodeResult(viaSystem.bitmap, DecodePath.NATIVE)
                    }
                    platformFails.incrementAndGet()
                    platformFailed[item.uri] = true
                }
                heifDecode(ctx, item, maxPx)?.let {
                    goodPath[item.uri] = PATH_LIBHEIF
                    return DecodeResult(it, DecodePath.HEIF_LIB)
                }
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

    /** Ergebnis des System-Dekoders samt Angabe, ob der Hardware-Weg beteiligt war. */
    private class PlatformResult(val bitmap: Bitmap, val hardware: Boolean)

    fun platformDecode(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? =
        platformDecodeDetailed(ctx, item, maxPx)?.bitmap

    /**
     * System-Dekoder, Hardware zuerst (wie iOS).
     *
     * Wichtig: Liefert der Hardware-Weg ein (fast) schwarzes Bild – das passiert bei
     * 10-Bit-/HDR-HEICs auf manchen Geräten –, wird dieses Ergebnis **aufbewahrt** und nur
     * dann ersetzt, wenn ein anderer Weg ein sichtbares Bild liefert. Früher wurde es
     * verworfen; dadurch musste die App für solche Fotos jedes Mal zusätzlich den langsamen
     * Software-Weg gehen (und am Ende teils libheif) – das war die Wartezeit bei HEIC.
     */
    private fun platformDecodeDetailed(ctx: Context, item: MediaItem, maxPx: Int): PlatformResult? {
        val uri = Uri.parse(item.uri)
        var darkHardware: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 29 && (item.isHeif || item.isAvif)) {
            hardwareDecode(ctx, uri, maxPx)?.let { hw ->
                if (!looksUniformlyDark(hw)) return PlatformResult(hw, true)
                darkHardware = hw
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            imageDecoderSoftware(ctx, uri, maxPx)?.let { return PlatformResult(it, false) }
        }
        bitmapFactoryDecode(ctx, uri, maxPx)?.let { return PlatformResult(it, false) }
        // Lieber ein dunkles Bild als gar keins – und vor allem ohne den Umweg über libheif.
        darkHardware?.let { return PlatformResult(it, true) }
        return null
    }

    private fun imageDecoderSoftware(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            configureSize(decoder, info.size.width, info.size.height, maxPx)
            if (Build.VERSION.SDK_INT >= 29) decoder.isUnpremultipliedRequired = false
        }
    } catch (_: Throwable) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /**
     * Zielgröße möglichst genau vorgeben – „in Anzeigegröße dekodieren“ wie iOS.
     * Die Zweier-Stufe ist immer möglich; die exakte Zielgröße wird zusätzlich versucht
     * und still verworfen, wenn ein Gerät sie nicht unterstützt.
     */
    private fun configureSize(decoder: ImageDecoder, w: Int, h: Int, maxPx: Int) {
        if (w <= 0 || h <= 0 || maxPx <= 0) return
        val sample = sampleSize(w, h, maxPx)
        if (sample > 1) decoder.setTargetSampleSize(sample)
        val sw = (w / sample).coerceAtLeast(1)
        val sh = (h / sample).coerceAtLeast(1)
        val longest = maxOf(sw, sh)
        if (longest <= maxPx) return
        val scale = maxPx.toFloat() / longest
        if (scale > 0.9f) return
        val tw = (sw * scale).toInt().coerceAtLeast(1)
        val th = (sh * scale).toInt().coerceAtLeast(1)
        // Nicht jede Bildart erlaubt eine freie Zielgröße – dann bleibt es bei der Zweier-Stufe.
        runCatching { decoder.setTargetSize(tw, th) }
    }

    /**
     * Hardware-Dekodierung (Android 10+). Der HEVC-Decoder der CPU/GPU ist um ein
     * Vielfaches schneller als libheif in Software – deshalb zuerst probieren.
     * Das Ergebnis wird in ein normales (Software-)Bitmap kopiert, damit es sich
     * speichern und weiterverarbeiten lässt.
     */
    private fun hardwareDecode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            val hw = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                configureSize(decoder, info.size.width, info.size.height, maxPx)
            }
            if (hw == null) return null
            val software = Bitmap.createBitmap(hw.width, hw.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(software)
            canvas.drawBitmap(hw, 0f, 0f, null)
            hw.recycle()
            software
        } catch (_: Throwable) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
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
