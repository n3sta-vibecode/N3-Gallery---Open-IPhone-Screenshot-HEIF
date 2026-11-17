package com.n3vibecode.gallery.image

import java.io.InputStream

/**
 * **EXIF direkt aus einem HEIF/AVIF-Container lesen.**
 *
 * Warum das nötig war: Bei iPhone-Fotos stand „kein Ort gespeichert“ und kein
 * Aufnahme-Sensor. Ursache ist nicht die Datei – Apple legt die EXIF-Daten als eigenes
 * Item (`type = "Exif"`) in den Container, und `androidx.exifinterface` findet sie nur,
 * wenn genau die Item-Variante verbaut ist, die es erwartet (u. a. Offset-Feld vor dem
 * TIFF-Block). Fehlt das, liefert `ExifInterface` **nichts**, ohne einen Fehler zu melden –
 * also bleibt still, was eigentlich da ist.
 *
 * Diese Klasse liest die Boxen selbst (`iinf` → Item-IDs und Typen, `iloc` → Position,
 * dann der TIFF-Block mit IFD0, Exif-IFD und GPS-IFD). Damit kommen Kamera, Objektiv,
 * ISO, Blende, Kleinbild-Brennweite, Aufnahmezeit **und die GPS-Koordinaten** an, egal in
 * welcher Variante Apple sie abgelegt hat.
 *
 * Geprüft mit `tools/heif/heif_geo_probe.py --self-test` (baut echte HEIF-Strukturen im
 * Speicher und vergleicht die gelesenen Werte) und mit Unit-Tests in [HeifGeoTest].
 */
object HeifGeo {

    /** Ergebnis der Auswertung. Alle Felder sind optional – fehlt etwas, bleibt es `null`. */
    data class Geo(
        val make: String? = null,
        val model: String? = null,
        val lens: String? = null,
        val taken: String? = null,
        val offsetTime: String? = null,
        val iso: Int? = null,
        val fNumber: Double? = null,
        val exposureTime: String? = null,
        val focalLength: Double? = null,
        val focal35: Int? = null,
        val pixelWidth: Int? = null,
        val pixelHeight: Int? = null,
        val orientation: Int? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitude: Double? = null,
        val rotationDegrees: Int? = null,
        val mirrored: Boolean = false
    ) {
        val hasGps: Boolean get() = latitude != null && longitude != null
    }

    // ---------------------------------------------------------------- Öffentlich

    /**
     * Öffnet die Datei einmal und liest alles, was geht. `open` liefert einen **frischen**
     * Stream (der Container wird mehrfach an verschiedenen Stellen gelesen).
     */
    fun read(opener: () -> InputStream?, fileSize: Long): Geo? {
        val tiff = exifTiff(opener) ?: return null
        val tags = TiffTags.parse(tiff)
        val gps = tags.gps
        val rotation = orientation(opener)

        val lat = dms(gps["GPSLatitude"], gps["GPSLatitudeRef"] as? String)
        val lon = dms(gps["GPSLongitude"], gps["GPSLongitudeRef"] as? String)
        val altRationals = gps["GPSAltitude"] as? List<*>
        val altRef = (gps["GPSAltitudeRef"] as? List<*>)?.firstOrNull() as? Int

        return Geo(
            make = tags.ifd0["Make"] as? String,
            model = tags.ifd0["Model"] as? String,
            lens = tags.exif["LensModel"] as? String,
            taken = tags.exif["DateTimeOriginal"] as? String,
            offsetTime = (tags.exif["OffsetTimeOriginal"] ?: tags.exif["OffsetTime"]) as? String,
            iso = tags.exif["PhotographicSensitivity"] as? Int
                ?: tags.exif["ISOSpeedRatings"] as? Int,
            fNumber = (tags.exif["FNumber"] as? List<*>)?.firstOrNull() as? Double,
            exposureTime = formatExposure((tags.exif["ExposureTime"] as? List<*>)?.firstOrNull() as? Double),
            focalLength = (tags.exif["FocalLength"] as? List<*>)?.firstOrNull() as? Double,
            focal35 = tags.exif["FocalLengthIn35mmFilm"] as? Int,
            pixelWidth = tags.exif["PixelXDimension"] as? Int,
            pixelHeight = tags.exif["PixelYDimension"] as? Int,
            orientation = tags.ifd0["Orientation"] as? Int,
            latitude = lat,
            longitude = lon,
            altitude = altRationals?.firstOrNull()?.let { a ->
                val v = (a as? Double) ?: return@let null
                if (altRef == 1) -v else v
            },
            rotationDegrees = rotation.first,
            mirrored = rotation.second
        ).takeIf { it.hasGps || it.make != null || it.model != null || it.taken != null }
    }

    /** Nur die Drehung aus dem Container (`irot`/`imir`) – hilft bei falsch gedrehten Bildern. */
    fun orientation(opener: () -> InputStream?): Pair<Int?, Boolean> = readOrientation(opener)

    // ---------------------------------------------------------------- Boxen

    /**
     * Sucht das `Exif`-Item: `iinf` liefert Typ und ID, `iloc` die Position, gelesen wird
     * der TIFF-Block samt Offsets (`data` → `offsets` → `positions`, so heißt das in iOS).
     */
    private fun exifTiff(opener: () -> InputStream?): ByteArray? {
        val head = readAt(opener, 0, 64 * 1024) ?: return null
        // meta-Box finden (liegt direkt hinter ftyp)
        var pos = 0
        var metaStart = -1L
        var metaLen = 0
        while (pos + 8 <= head.size) {
            val size = u32(head, pos)
            val type = fourcc(head, pos + 4)
            var header = 8L
            var boxSize = size
            if (size == 1L) {
                if (pos + 16 > head.size) break
                boxSize = u64(head, pos + 8)
                header = 16
            } else if (size == 0L) {
                boxSize = Long.MAX_VALUE
            }
            if (type == "meta") {
                metaStart = pos + header
                metaLen = (boxSize - header).toInt()
                break
            }
            if (boxSize <= 0 || boxSize > Int.MAX_VALUE) break
            pos += boxSize.toInt()
        }
        if (metaStart < 0) return null

        val meta = readAt(opener, metaStart, metaLen) ?: return null
        val itemTypes = parseIinf(meta) ?: return null
        val positions = parseIloc(meta) ?: return null

        val exifItemId = itemTypes.entries.firstOrNull { it.value.equals("Exif", true) }?.key
            ?: itemTypes.entries.firstOrNull { it.value.equals("exif", true) }?.key
            ?: return null
        val extents = positions[exifItemId] ?: return null
        val first = extents.firstOrNull() ?: return null
        if (first.second <= 0 || first.second > 4L * 1024 * 1024) return null
        val blob = readAt(opener, first.first, first.second.toInt()) ?: return null
        if (blob.size < 8) return null

        // Übliche Form: 4 Byte Offset, dann „Exif\0\0“, dann TIFF. Es gibt aber auch
        // Dateien ohne Offset-Feld. Deshalb: TIFF-Kennung suchen statt raten.
        val candidates = listOf(
            4 + u32(blob, 0).toInt(),   // Offset-Feld
            4,                          // ohne Offset-Feld
            6,                          // „Exif\0\0“ zuerst
            0                           // direkt TIFF
        )
        for (start in candidates) {
            if (start in 0..blob.size - 8) {
                val a = blob[start]
                val b = blob[start + 1]
                if ((a == 'I'.code.toByte() && b == 'I'.code.toByte()) ||
                    (a == 'M'.code.toByte() && b == 'M'.code.toByte())
                ) {
                    return blob.copyOfRange(start, blob.size)
                }
            }
        }
        return null
    }

    private fun parseIinf(meta: ByteArray): Map<Int, String>? {
        val box = findBox(meta, "iinf", 4) ?: return null
        val out = LinkedHashMap<Int, String>()
        var p = box.first + 4
        val version = meta[box.first].toInt() and 0xFF
        val count = if (version == 0) u16(meta, p) else u32(meta, p).toInt()
        p += if (version == 0) 2 else 4
        repeat(count.coerceAtMost(4096)) {
            if (p + 8 > box.second) return out
            val size = u32(meta, p).toInt()
            if (size < 8 || p + size > box.second) return out
            if (fourcc(meta, p + 4) == "infe") {
                val v = meta[p + 8].toInt() and 0xFF
                // item_ID: 16 Bit bis Version 1, 32 Bit ab Version 2 – ABER Apple schreibt
                // auch bei Version 2 16-Bit-IDs. Beides prüfen: passt die ID zum iloc-Bereich?
                val id16 = u16(meta, p + 12)
                val id32 = u32(meta, p + 12).toInt()
                val typeAt = if (v >= 2) p + 16 else p + 14
                val type = if (typeAt + 4 <= p + size) fourcc(meta, typeAt) else ""
                if (type.isNotEmpty()) {
                    out[id16] = type
                    if (id32 != id16) out.putIfAbsent(id32, type)
                }
            }
            p += size
        }
        return out
    }

    private fun parseIloc(meta: ByteArray): Map<Int, List<Pair<Long, Long>>>? {
        val box = findBox(meta, "iloc", 4) ?: return null
        val start = box.first
        val end = box.second
        val version = meta[start].toInt() and 0xFF
        var p = start + 4
        if (p + 2 > end) return null
        val sizes = meta[p].toInt() and 0xFF
        val offsetSize = (sizes shr 4) and 0x0F
        val lengthSize = sizes and 0x0F
        val sizes2 = meta[p + 1].toInt() and 0xFF
        val baseSize = (sizes2 shr 4) and 0x0F
        val indexSize = if (version == 1 || version == 2) sizes2 and 0x0F else 0
        p += 2
        val count = if (version < 2) u16(meta, p) else u32(meta, p).toInt()
        p += if (version < 2) 2 else 4
        if (count < 0 || count > 65_535) return null

        val out = LinkedHashMap<Int, List<Pair<Long, Long>>>()
        var guard = 0
        repeat(count) {
            if (guard++ > 65_535 || p >= end) return out
            val id = if (version < 2) u16(meta, p) else u32(meta, p).toInt()
            p += if (version < 2) 2 else 4
            if (version == 1 || version == 2) p += 2   // construction_method + reserved
            p += 2                                     // data_reference_index
            val base = readN(meta, p, baseSize)
            p += baseSize
            if (p + 2 > end) return out
            val extents = u16(meta, p)
            p += 2
            val list = ArrayList<Pair<Long, Long>>(extents)
            repeat(extents) {
                if (indexSize > 0) p += indexSize
                val off = readN(meta, p, offsetSize)
                p += offsetSize
                val len = readN(meta, p, lengthSize)
                p += lengthSize
                if (len > 0) list.add((base + off) to len)
            }
            if (list.isNotEmpty()) out[id] = list
        }
        return out
    }

    private fun readOrientation(opener: () -> InputStream?): Pair<Int?, Boolean> {
        val head = readAt(opener, 0, 64 * 1024) ?: return null to false
        var pos = 0
        while (pos + 8 <= head.size) {
            val size = u32(head, pos)
            val type = fourcc(head, pos + 4)
            if (type == "meta") {
                val metaLen = (size - 8).toInt().coerceIn(0, head.size - pos - 8)
                val meta = head.copyOfRange(pos + 8, pos + 8 + metaLen)
                // iprp enthält ipco, ipco enthält die Eigenschaften (irot/imir/ispe …).
                // Vorher wurde ipco direkt in meta gesucht – dort liegt es nicht, deshalb
                // kam nie eine Drehung an (vom Unit-Test gefunden).
                val iprp = findBox(meta, "iprp", 4) ?: return null to false
                val ipco = findBoxInRange(meta, "ipco", iprp.first, iprp.second)
                    ?: return null to false
                var p = ipco.first
                var degrees: Int? = null
                var mirrored = false
                while (p + 8 <= ipco.second) {
                    val s = u32(meta, p).toInt()
                    if (s < 8) break
                    when (fourcc(meta, p + 4)) {
                        // irot: 2 Bit Winkel in 90°-Schritten (gegen den Uhrzeigersinn)
                        "irot" -> if (p + 8 < ipco.second) degrees = (meta[p + 8].toInt() and 0x03) * 90
                        "imir" -> mirrored = true
                    }
                    p += s
                }
                return degrees to mirrored
            }
            if (size <= 0 || size > Int.MAX_VALUE) break
            pos += size.toInt()
        }
        return null to false
    }

    // ---------------------------------------------------------------- TIFF

    /** Kleiner TIFF-Leser: IFD0, Exif-IFD und GPS-IFD, ohne Fremdbibliothek. */
    object TiffTags {

        class Result(
            val ifd0: Map<String, Any?>,
            val exif: Map<String, Any?>,
            val gps: Map<String, Any?>
        )

        private val IFD0 = mapOf(
            0x010F to "Make", 0x0110 to "Model", 0x0112 to "Orientation",
            0x0131 to "Software", 0x0132 to "DateTime", 0x8769 to "ExifIFD",
            0x8825 to "GPSIFD"
        )

        private val EXIF = mapOf(
            0x829A to "ExposureTime", 0x829D to "FNumber", 0x8827 to "ISOSpeedRatings",
            0x8900 to "ISOSpeedRatings", 0x9003 to "DateTimeOriginal",
            0x9010 to "OffsetTime", 0x9011 to "OffsetTimeOriginal",
            0x920A to "FocalLength", 0xA405 to "FocalLengthIn35mmFilm",
            0xA002 to "PixelXDimension", 0xA003 to "PixelYDimension",
            0xA434 to "LensModel", 0xA433 to "LensMake",
            0x8833 to "PhotographicSensitivity", 0x8832 to "PhotographicSensitivity"
        )

        private val GPS = mapOf(
            0x0000 to "GPSVersionID", 0x0001 to "GPSLatitudeRef", 0x0002 to "GPSLatitude",
            0x0003 to "GPSLongitudeRef", 0x0004 to "GPSLongitude",
            0x0005 to "GPSAltitudeRef", 0x0006 to "GPSAltitude",
            0x0007 to "GPSTimeStamp", 0x0010 to "GPSImgDirectionRef",
            0x0011 to "GPSImgDirection", 0x001D to "GPSDateStamp", 0x001F to "GPSHPositioningError"
        )

        fun parse(tiff: ByteArray): Result {
            val little = tiff[0] == 'I'.code.toByte()
            if (!little && tiff[0] != 'M'.code.toByte()) return Result(emptyMap(), emptyMap(), emptyMap())
            val ifd0Off = u32(tiff, 4, little).toInt()
            val ifd0Raw = entries(tiff, ifd0Off, little)
            val ifd0 = LinkedHashMap<String, Any?>()
            ifd0Raw.forEach { (tag, e) ->
                IFD0[tag]?.let { ifd0[it] = e.value() }
            }
            val exif = LinkedHashMap<String, Any?>()
            (ifd0Raw[0x8769]?.let { it.number() })?.let { ptr ->
                entries(tiff, ptr, little).forEach { (tag, e) ->
                    EXIF[tag]?.let { key -> if (exif[key] == null) exif[key] = e.value() }
                }
            }
            val gps = LinkedHashMap<String, Any?>()
            (ifd0Raw[0x8825]?.let { it.number() })?.let { ptr ->
                entries(tiff, ptr, little).forEach { (tag, e) ->
                    GPS[tag]?.let { gps[it] = e.value() }
                }
            }
            return Result(ifd0, exif, gps)
        }

        private class Entry(val type: Int, val count: Int, val raw: ByteArray, val little: Boolean) {
            fun value(): Any? = when (type) {
                2 -> raw.takeWhile { it != 0.toByte() }
                    .toByteArray().toString(Charsets.ISO_8859_1).trim()
                1, 7 -> raw.toList().map { it.toInt() and 0xFF }
                3 -> if (raw.size >= 2) u16v(raw, 0, little) else null
                4, 9 -> if (raw.size >= 4) u32(raw, 0, little).toInt() else null
                5 -> rationals(raw, little)
                else -> null
            }

            fun number(): Int? = when (type) {
                3 -> if (raw.size >= 2) u16v(raw, 0, little) else null
                4, 9 -> if (raw.size >= 4) u32(raw, 0, little).toInt() else null
                else -> null
            }
        }

        private fun entries(tiff: ByteArray, offset: Int, little: Boolean): Map<Int, Entry> {
            if (offset <= 0 || offset + 2 > tiff.size) return emptyMap()
            val count = u16v(tiff, offset, little)
            val out = LinkedHashMap<Int, Entry>(count)
            val typeSize = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 7 to 1, 9 to 4, 10 to 8)
            for (i in 0 until count) {
                val p = offset + 2 + i * 12
                if (p + 12 > tiff.size) break
                val tag = u16v(tiff, p, little)
                val type = u16v(tiff, p + 2, little)
                val n = u32(tiff, p + 4, little).toInt()
                val size = (typeSize[type] ?: 1) * n
                if (size < 0) continue
                val raw: ByteArray = if (size <= 4) {
                    tiff.copyOfRange(p + 8, minOf(p + 8 + size, tiff.size))
                } else {
                    val at = u32(tiff, p + 8, little).toInt()
                    if (at + size > tiff.size) continue
                    tiff.copyOfRange(at, at + size)
                }
                out[tag] = Entry(type, n, raw, little)
            }
            return out
        }

        private fun rationals(raw: ByteArray, little: Boolean): List<Double> {
            val out = ArrayList<Double>(raw.size / 8)
            var i = 0
            while (i + 8 <= raw.size) {
                val num = u32(raw, i, little).toDouble()
                val den = u32(raw, i + 4, little).toDouble()
                out.add(if (den == 0.0) 0.0 else num / den)
                i += 8
            }
            return out
        }
    }

    // ---------------------------------------------------------------- Rechnen

    /** Grad/Minuten/Sekunden + N/S/W/O → Dezimalgrad (S und W negativ). */
    fun dms(values: Any?, ref: String?): Double? {
        val list = (values as? List<*>) ?: return null
        if (list.size < 3) return null
        val d = (list[0] as? Double) ?: return null
        val m = (list[1] as? Double) ?: 0.0
        val s = (list[2] as? Double) ?: 0.0
        var deg = d + m / 60.0 + s / 3600.0
        val r = ref?.trim()?.uppercase()?.firstOrNull()
        if (r == 'S' || r == 'W') deg = -deg
        return if (kotlin.math.abs(deg) <= 180.0 && deg != 0.0) deg else null
    }

    /** 0,0083333 s → „1/120 s“; alles ab 1 s bleibt Dezimal. */
    fun formatExposure(seconds: Double?): String? {
        if (seconds == null || seconds <= 0.0) return null
        if (seconds >= 1.0) return String.format(java.util.Locale.GERMAN, "%.1f s", seconds)
        val denom = Math.round(1.0 / seconds)
        return if (denom <= 0 || denom > 100_000) null else "1/$denom s"
    }

    // ---------------------------------------------------------------- Bytes

    internal fun u16(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun u16v(b: ByteArray, p: Int, little: Boolean): Int = if (little) {
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    } else u16(b, p)

    internal fun u32(b: ByteArray, p: Int): Long {
        if (p + 4 > b.size) return 0
        return ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
    }

    private fun u32(b: ByteArray, p: Int, little: Boolean): Long {
        if (p + 4 > b.size) return 0
        return if (little) {
            (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
                ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
        } else u32(b, p)
    }

    internal fun u64(b: ByteArray, p: Int): Long {
        if (p + 8 > b.size) return 0
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        return v
    }

    internal fun fourcc(b: ByteArray, p: Int): String =
        if (p + 4 <= b.size) String(b, p, 4, Charsets.ISO_8859_1) else ""

    private fun readN(b: ByteArray, p: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) {
            if (p + i >= b.size) return v
            v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        }
        return v
    }

    /** Sucht eine Box in einem Bereich (für verschachtelte Boxen wie iprp → ipco). */
    private fun findBoxInRange(data: ByteArray, type: String, from: Int, to: Int): Pair<Int, Int>? {
        var p = from
        while (p + 8 <= to && p + 8 <= data.size) {
            val size = u32(data, p).toInt()
            if (size < 8) return null
            val end = (p + size).coerceAtMost(minOf(to, data.size))
            if (fourcc(data, p + 4) == type) return (p + 8) to end
            p = end
        }
        return null
    }

    /** Sucht eine Box innerhalb eines Puffers (ab `from`, meist 4 = hinter version/flags). */
    private fun findBox(data: ByteArray, type: String, from: Int): Pair<Int, Int>? {
        var p = from
        while (p + 8 <= data.size) {
            val size = u32(data, p).toInt()
            if (size < 8) return null
            val end = (p + size).coerceAtMost(data.size)
            if (fourcc(data, p + 4) == type) return (p + 8) to end
            p = end
        }
        return null
    }

    private fun readAt(opener: () -> InputStream?, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) return null
        val stream: InputStream = runCatching { opener() }.getOrNull() ?: return null
        val buf = ByteArray(length)
        var filled = 0
        try {
            stream.use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val s = input.skip(offset - skipped)
                    if (s <= 0) {
                        if (input.read() < 0) return@use
                        skipped++
                    } else {
                        skipped += s
                    }
                }
                while (filled < length) {
                    val r = input.read(buf, filled, length - filled)
                    if (r < 0) break
                    filled += r
                }
            }
        } catch (_: Throwable) {
            return null
        }
        return when {
            filled == 0 -> null
            filled == length -> buf
            else -> buf.copyOfRange(0, filled)
        }
    }
}
