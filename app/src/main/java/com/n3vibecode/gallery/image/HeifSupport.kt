package com.n3vibecode.gallery.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.radzivon.bartoshyk.avif.coder.HeifCoder

/**
 * Eingebauter HEIF/AVIF-Decoder (libheif + libde265 für HEVC, libdav1d für AV1).
 *
 * Warum nötig: Androids System-Decoder öffnet nur einfache HEICs. Apple-Container arbeiten aber
 * mit **Kachel-Rastern** (Grid: z. B. 8 × 6 Kacheln à 512 px, wie in jedem iPhone-Foto/Screenshot),
 * teils 10-Bit/HDR. Genau diese Dateien scheitern sonst – hier werden sie korrekt zusammengesetzt.
 *
 * Die Bibliothek bringt ihre nativen Bibliotheken selbst mit; schlägt das Laden fehl
 * (seltene CPU-Architektur), meldet [available] einfach „false“ und die App nutzt die anderen Wege.
 */
object HeifSupport {

    private val coder: HeifCoder? by lazy {
        runCatching { HeifCoder() }.getOrElse { null }
    }

    /** Kann der integrierte Decoder genutzt werden? */
    val available: Boolean get() = coder != null

    fun name(): String = if (available) "libheif (integriert)" else "nicht verfügbar"

    private const val MAX_BYTES = 256L * 1024 * 1024

    data class Dim(val width: Int, val height: Int)

    /** Bildgröße direkt aus dem Container lesen (ohne zu dekodieren). */
    fun sizeOf(bytes: ByteArray): Dim? = runCatching {
        coder?.getSize(bytes)?.let { Dim(it.width, it.height) }
    }.getOrNull()

    /** Ist es eine HEIF/AVIF-Datei, die der integrierte Decoder versteht? */
    fun isSupported(bytes: ByteArray): Boolean =
        runCatching { coder?.isSupportedImage(bytes) == true }.getOrDefault(false)

    /**
     * Dekodiert HEIF/HEIC/AVIF. Bei großen Bildern wird direkt in der Zielgröße skaliert
     * (ScaleMode.FIT), damit der Speicher klein bleibt.
     */
    fun decodeBytes(bytes: ByteArray, maxPx: Int): Bitmap? {
        val c = coder ?: return null
        return try {
            val size = runCatching { c.getSize(bytes) }.getOrNull()
            val longest = if (size != null) maxOf(size.width, size.height) else 0
            if (size != null && maxPx > 0 && longest > maxPx * 1.15) {
                val scale = maxPx.toFloat() / longest
                val w = (size.width * scale).toInt().coerceAtLeast(1)
                val h = (size.height * scale).toInt().coerceAtLeast(1)
                c.decodeSampled(bytes, w, h)
            } else {
                c.decode(bytes)
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (t: Throwable) {
            null
        }
    }

    fun decode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
        if (!available) return null
        val bytes = readAll(ctx, uri) ?: return null
        return decodeBytes(bytes, maxPx)
    }

    fun readAll(ctx: Context, uri: Uri, limit: Long = MAX_BYTES): ByteArray? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > limit) return null
                    out.write(buf, 0, r)
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
