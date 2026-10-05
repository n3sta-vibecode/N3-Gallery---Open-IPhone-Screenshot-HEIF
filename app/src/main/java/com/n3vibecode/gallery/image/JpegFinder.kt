package com.n3vibecode.gallery.image

import java.io.InputStream

/**
 * Sucht das größte eingebettete JPEG in einer beliebigen Container-Datei.
 *
 * Das ist der Schlüssel für RAW-Formate (DNG, CR2, CR3, NEF, ARW, ORF, RW2, RAF, PEF, SRW …):
 * praktisch jede Kamera legt eine vollwertige JPEG-Vorschau mit ab. Zusätzlich fängt die Suche
 * exotische Container ab, in denen ein JPEG direkt eingebettet ist (z. B. HEIF-Varianten mit
 * JPEG-kodiertem Bild – die „Zickzack“-Kodierung stammt ja ursprünglich aus JPEG).
 */
object JpegFinder {

    private const val SOI = 0xFFD8
    private const val EOI = 0xFFD9

    data class Range(val offset: Long, val length: Long)

    /** Durchsucht den Stream und liefert die Länge/Position des größten JPEG-Blocks. */
    fun largestRange(input: InputStream, scanLimit: Long = 192L * 1024 * 1024, maxCandidate: Long = 48L * 1024 * 1024): Range? {
        val buffer = ByteArray(1 shl 20) // 1 MB
        var base = 0L
        var start = -1L
        var best: Range? = null

        fun close(upTo: Long) {
            if (start >= 0) {
                val len = upTo - start + 2
                if (len in 512..maxCandidate) {
                    val b = best
                    if (b == null || len > b.length) best = Range(start, len)
                }
            }
            start = -1
        }

        while (base < scanLimit) {
            val n = try {
                input.read(buffer)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) break
            var i = 0
            while (i < n - 1) {
                val hi = buffer[i].toInt() and 0xFF
                if (hi == 0xFF) {
                    val lo = buffer[i + 1].toInt() and 0xFF
                    val marker = (hi shl 8) or lo
                    if (marker == SOI) start = base + i
                    else if (marker == EOI && start >= 0) close(base + i)
                    i += 2
                } else {
                    i++
                }
            }
            base += n
            if (n < buffer.size) break
        }
        if (start >= 0) close(base)
        return best
    }

    /** Liest den gefundenen Bereich aus dem Stream (Stream wird an den Anfang zurückgesetzt erwartet). */
    fun read(input: InputStream, range: Range): ByteArray? {
        return try {
            var skipped = 0L
            while (skipped < range.offset) {
                val s = input.skip(range.offset - skipped)
                if (s <= 0) {
                    if (input.read() < 0) return null
                    skipped++
                } else {
                    skipped += s
                }
            }
            val len = range.length.toInt()
            val data = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = input.read(data, off, len - off)
                if (r < 0) break
                off += r
            }
            if (off <= 0) null else if (off == len) data else data.copyOf(off)
        } catch (_: Exception) {
            null
        }
    }

    /** Prüft, ob der Puffer ein JPEG ist. */
    fun looksLikeJpeg(data: ByteArray?): Boolean =
        data != null && data.size > 4 &&
                (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
}
