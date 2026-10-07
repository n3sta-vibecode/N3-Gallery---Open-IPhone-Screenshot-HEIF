package com.n3vibecode.gallery.image

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Liest die ISO-BMFF-/HEIF-Boxstruktur (ftyp, meta, iinf, iloc, iprp/ipco, iref) direkt aus der Datei.
 *
 * Damit lässt sich bei HEIC/HEIF/AVIF auch dann alles anzeigen, wenn Android die Datei nicht dekodieren
 * kann – und zwar:
 *   • Marke/Container (Apple „heic“, „mif1“, „hevc“, „avif“ …)
 *   • Anzahl & Typen der enthaltenen Bilder (z. B. Apple-Screenshot = „grid“ + Kacheln)
 *   • Primärbild-Größe (ispe), Bit-Tiefe (pixi), Farbraum (colr)
 *   • HDR-Gain-Map (Apple/Google, auxC „hdrgainmap“), Alpha
 *   • JPEG-kodierte HEIF-Bilder → werden für die Anzeige als JPEG extrahiert
 */
object HeifInspector {

    data class Extent(val offset: Long, val length: Long)

    data class Item(
        val id: Int,
        val name: String,
        val type: String,
        val protectedItem: Boolean,
        val contentType: String?,
        val extents: List<Extent>
    ) {
        val size: Long get() = extents.sumOf { it.length }
    }

    /** Kachel-Raster (Grid-Container) – typisch für Apple-Screenshots und iPhone-Fotos. */
    data class Tiles(val rows: Int, val cols: Int, val outWidth: Int, val outHeight: Int, val tileWidth: Int, val tileHeight: Int) {
        val count: Int get() = rows * cols
    }

    data class Info(
        val majorBrand: String,
        val compatible: List<String>,
        val items: List<Item>,
        val codedTypes: List<String>,
        val primaryWidth: Int,
        val primaryHeight: Int,
        val bitDepth: Int?,
        val channels: Int?,
        val primaries: String?,
        val transfer: String?,
        val fullRange: Boolean?,
        val gainMap: Boolean,
        val gainMapType: String?,
        val auxiliaryTypes: List<String>,
        val hevcProfile: Int?,
        val hevcLevel: Int?,
        val dimgRefs: Int,
        val thumbRefs: Int,
        val isAvif: Boolean,
        val isHeic: Boolean,
        val tiles: Tiles? = null,
        val gridTileWidth: Int = 0,
        val gridTileHeight: Int = 0,
        val primaryItemId: Int = 0,
        val primaryType: String? = null,
        val primaryItemName: String? = null
    ) {
        val itemCount: Int get() = items.size
        val isGrid: Boolean get() = items.any { it.name.contains("grid", true) || it.name.contains("tile", true) } || dimgRefs > 1
        val isAppleStyle: Boolean
            get() = compatible.any { it == "heic" || it == "hevc" || it == "mif1" } ||
                    items.any { it.name.startsWith("IMG_") || it.name.contains("Screenshot", true) }
        fun jpegItems(): List<Item> = items.filter {
            it.type == "jpeg" || it.contentType?.contains("jpeg") == true
        }
    }

    // ---------------------------------------------------------------- Box-Lesen

    private class Cursor(val data: ByteArray, var pos: Int, val end: Int) {
        fun u8(): Int = if (pos < end) (data[pos++].toInt() and 0xFF) else 0
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long {
            var v = 0L
            repeat(4) { v = (v shl 8) or u8().toLong() }
            return v
        }
        fun u64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }
        fun bytes(n: Int): ByteArray {
            val len = minOf(n, end - pos).coerceAtLeast(0)
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
        fun fourcc(): String = String(bytes(4), StandardCharsets.US_ASCII)
        fun cString(max: Int): String {
            val sb = StringBuilder()
            var n = 0
            while (pos < end && n < max) {
                val b = u8()
                if (b == 0) break
                sb.append(b.toChar())
                n++
            }
            return sb.toString()
        }
        fun skip(n: Int) {
            pos = (pos + n).coerceAtMost(end)
            if (pos > end) pos = end
        }
        val remaining: Int get() = end - pos
    }

    fun inspect(stream: InputStream, fileSize: Long): Info? = inspect({ stream }, fileSize)

    /**
     * Wie oben, arbeitet aber mit einem Öffner, damit die grid-Box (Kachel-Raster)
     * nachgelesen werden kann – sie liegt im mdat, nicht im meta-Bereich.
     */
    fun inspect(open: () -> InputStream?, fileSize: Long): Info? {
        val stream: InputStream = open() ?: return null
        var major = ""
        var compatible = listOf<String>()
        var metaBytes: ByteArray? = null
        var metaStart = 0L
        var pos = 0L

        try {
            val header = ByteArray(16)
            while (pos + 8 <= fileSize) {
                val read = readFully(stream, header, 8)
                if (read < 8) break
                var size = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                        ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
                val type = String(header, 4, 4, StandardCharsets.US_ASCII)
                var headerSize = 8L
                if (size == 1L) {
                    val ext = ByteArray(8)
                    if (readFully(stream, ext, 8) < 8) break
                    size = 0L
                    for (i in 0 until 8) size = (size shl 8) or (ext[i].toLong() and 0xFF)
                    headerSize = 16L
                } else if (size == 0L) {
                    size = fileSize - pos
                }
                if (size < headerSize) break

                when (type) {
                    "ftyp" -> {
                        val payload = ByteArray(minOf(size - headerSize, 64L).toInt())
                        readFully(stream, payload, payload.size)
                        if (payload.size >= 8) {
                            major = String(payload, 0, 4, StandardCharsets.US_ASCII)
                            val brands = mutableListOf<String>()
                            var i = 8
                            while (i + 4 <= payload.size) {
                                brands += String(payload, i, 4, StandardCharsets.US_ASCII)
                                i += 4
                            }
                            compatible = brands
                        }
                    }
                    "meta" -> {
                        val bodyLen = minOf(size - headerSize, 4L * 1024 * 1024).toInt()
                        metaBytes = ByteArray(bodyLen)
                        readFully(stream, metaBytes, bodyLen)
                        metaStart = pos + headerSize
                    }
                    else -> skipFully(stream, size - headerSize)
                }
                pos += size
                if (metaBytes != null && major.isNotEmpty()) break
            }
        } catch (_: Exception) {
        }

        val meta = metaBytes ?: return if (major.isEmpty()) null else Info(
            majorBrand = major, compatible = compatible, items = emptyList(), codedTypes = emptyList(),
            primaryWidth = 0, primaryHeight = 0, bitDepth = null, channels = null, primaries = null,
            transfer = null, fullRange = null, gainMap = false, gainMapType = null, auxiliaryTypes = emptyList(),
            hevcProfile = null, hevcLevel = null, dimgRefs = 0, thumbRefs = 0,
            isAvif = major.contains("avif", true), isHeic = major.contains("heic", true) || major.contains("heix", true)
        )

        val parsed = runCatching { parseMeta(meta, metaStart, major, compatible) }.getOrNull() ?: return null

        // Kachel-Raster auslesen (die grid-Box liegt im mdat, iloc kennt den Offset)
        val gridItem = parsed.items.firstOrNull { it.type == "grid" || it.name.contains("grid", true) }
        var tiles = gridItem?.extents?.firstOrNull()?.let { readTiles(open, it) }
        // Kachelgröße aus dem Container ergänzen (kleinste ispe-Box entspricht der Kachelgröße)
        if (tiles != null && (tiles.tileWidth == 0 || tiles.tileHeight == 0)) {
            tiles = tiles.copy(tileWidth = parsed.gridTileWidth, tileHeight = parsed.gridTileHeight)
        }

        val primary = parsed.items.firstOrNull { it.id == parsed.primaryItemId }
        return parsed.copy(
            tiles = tiles,
            primaryType = primary?.type,
            primaryItemName = primary?.name
        )
    }

    /** Liest die kleine grid-Box (Zeilen, Spalten, Ausgabegröße). */
    private fun readTiles(open: () -> InputStream?, extent: Extent): Tiles? {
        if (extent.length <= 0 || extent.length > 4096) return null
        return try {
            open()?.use { input ->
                var skipped = 0L
                while (skipped < extent.offset) {
                    val s = input.skip(extent.offset - skipped)
                    if (s <= 0) {
                        if (input.read() < 0) return null
                        skipped++
                    } else skipped += s
                }
                val len = minOf(extent.length, 64L).toInt().coerceAtLeast(12)
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = input.read(buf, off, len - off)
                    if (r < 0) break
                    off += r
                }
                if (off < 12) return null
                var p = 1 // version
                val flags = buf[p++].toInt() and 0xFF
                val rows = (buf[p++].toInt() and 0xFF) + 1
                val cols = (buf[p++].toInt() and 0xFF) + 1
                var outW = 0
                var outH = 0
                if ((flags and 1) != 0) {
                    if (off >= p + 8) {
                        outW = ((buf[p].toInt() and 0xFF) shl 24) or ((buf[p + 1].toInt() and 0xFF) shl 16) or
                                ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
                        outH = ((buf[p + 4].toInt() and 0xFF) shl 24) or ((buf[p + 5].toInt() and 0xFF) shl 16) or
                                ((buf[p + 6].toInt() and 0xFF) shl 8) or (buf[p + 7].toInt() and 0xFF)
                    }
                } else {
                    if (off >= p + 4) {
                        outW = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
                        outH = ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
                    }
                }
                if (outW > 100_000 || outH > 100_000) { outW = 0; outH = 0 }
                Tiles(rows, cols, outW, outH, 0, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- meta-Box

    private fun parseMeta(meta: ByteArray, metaStart: Long, major: String, compatible: List<String>): Info {
        val c = Cursor(meta, 0, meta.size)
        c.skip(4) // version + flags

        var items = listOf<Item>()
        var primaryItemId = 0
        var idatRel = -1L          // Nutzlast-Offset der idat-Box (relativ zum meta-Beginn)
        var ilocAt = -1
        var ilocEnd = -1
        var dimgRefs = 0
        var thumbRefs = 0
        val auxTypes = mutableListOf<String>()
        var gainMap = false
        var gainMapType: String? = null
        var primaryW = 0
        var primaryH = 0
        var bitDepth: Int? = null
        var channels: Int? = null
        var primaries: String? = null
        var transfer: String? = null
        var fullRange: Boolean? = null
        var hevcProfile: Int? = null
        var hevcLevel: Int? = null
        var tileW = 0
        var tileH = 0
        var ispeList: List<Pair<Int, Int>> = emptyList()

        while (c.remaining >= 8) {
            val startPos = c.pos
            val size = c.u32()
            val type = c.fourcc()
            if (size < 8 || size > c.end - startPos) {
                c.pos = startPos + 1
                continue
            }
            val boxEnd = (startPos + size).coerceAtMost(c.end.toLong()).toInt()
            when (type) {
                "pitm" -> {
                    val v = c.u8()
                    c.skip(3)
                    primaryItemId = if (v == 0) c.u16() else c.u32().toInt()
                }
                "iinf" -> items = parseIinf(c, boxEnd)
                "idat" -> idatRel = (startPos + 8).toLong()
                // iloc erst nach der Schleife auswerten – Extents können auf die idat-Box zeigen
                "iloc" -> {
                    ilocAt = c.pos
                    ilocEnd = boxEnd
                }
                "iref" -> {
                    val r = parseIref(c, boxEnd)
                    dimgRefs = r.first
                    thumbRefs = r.second
                }
                "iprp" -> {
                    val r = parseIprp(c, boxEnd)
                    ispeList = r.ispe
                    bitDepth = r.bitDepth
                    channels = r.channels
                    primaries = r.primaries
                    transfer = r.transfer
                    fullRange = r.fullRange
                    hevcProfile = r.hevcProfile
                    hevcLevel = r.hevcLevel
                    auxTypes += r.aux
                }
            }
            c.pos = boxEnd
        }

        if (ilocAt >= 0) {
            c.pos = ilocAt
            val extents = parseIloc(c, ilocEnd, metaStart, idatRel)
            if (items.isNotEmpty() && extents.isNotEmpty()) {
                items = items.map { item -> item.copy(extents = extents[item.id] ?: emptyList()) }
            }
        }

        // Größtes ispe = Primärbild (bei Apple-HEIF liegen Kacheln + Grid vor)
        val biggest = ispeList.maxByOrNull { it.first.toLong() * it.second } ?: (0 to 0)
        primaryW = biggest.first
        primaryH = biggest.second
        val smallest = ispeList.filter { it != biggest }.minByOrNull { it.first.toLong() * it.second }
        tileW = smallest?.first ?: 0
        tileH = smallest?.second ?: 0

        val jpegCoded = items.any { it.type == "jpeg" || it.contentType?.contains("jpeg") == true }
        if (items.any { it.type == "av01" } || major.contains("avif", true)) {
            // AVIF
        }
        if (jpegCoded) {
            // „Zickzack-kodiertes“ HEIF: JPEG-Bild im HEIF-Container → Anzeige per Extraktion
        }
        auxTypes.forEach {
            if (it.contains("hdrgainmap", true) || it.contains("gainmap", true) || it.contains("21496")) {
                gainMap = true
                gainMapType = it
            }
        }
        // Hinweis: Apple nennt die Gain-Map „urn:com:apple:photo:2020:aux:hdrgainmap“

        return Info(
            majorBrand = major,
            compatible = compatible,
            items = items,
            codedTypes = items.map { it.type }.distinct(),
            primaryWidth = primaryW,
            primaryHeight = primaryH,
            bitDepth = bitDepth,
            channels = channels,
            primaries = primaries,
            transfer = transfer,
            fullRange = fullRange,
            gainMap = gainMap,
            gainMapType = gainMapType,
            auxiliaryTypes = auxTypes.distinct(),
            hevcProfile = hevcProfile,
            hevcLevel = hevcLevel,
            dimgRefs = dimgRefs,
            thumbRefs = thumbRefs,
            isAvif = compatible.any { it.contains("avif", true) } || major.contains("avif", true),
            isHeic = compatible.any { it == "heic" || it == "hevc" || it == "heix" } || major.contains("hei", true),
            gridTileWidth = tileW,
            gridTileHeight = tileH,
            primaryItemId = primaryItemId
        )
    }

    private fun parseIinf(c: Cursor, boxEnd: Int): List<Item> {
        c.skip(4) // version/flags
        val version = c.data[c.pos - 4].toInt()
        val count = if (version == 0) c.u16() else c.u32().toInt()
        val out = mutableListOf<Item>()
        var guard = 0
        while (c.pos + 8 <= boxEnd && guard < count + 8) {
            guard++
            val start = c.pos
            val size = c.u32()
            val type = c.fourcc()
            if (size < 8 || size > boxEnd - start) {
                c.pos = start + 1
                continue
            }
            val end = (start + size).coerceAtMost(boxEnd.toLong()).toInt()
            if (type == "infe") {
                val v = c.u8()
                c.skip(3) // flags
                var id = 0
                if (v >= 3) id = c.u32().toInt() else id = c.u16()
                val protection = c.u16()
                val itemType = c.fourcc().trim('\u0000')
                var name = ""
                var contentType: String? = null
                if (v >= 2) {
                    name = c.cString(256)
                    if (itemType == "mime") {
                        contentType = c.cString(128)
                    }
                }
                if (name.isEmpty()) name = "Item $id"
                out += Item(id, name, itemType, protection != 0, contentType, emptyList())
            }
            c.pos = end
        }
        return out
    }

    private fun parseIloc(c: Cursor, boxEnd: Int, metaStart: Long, idatRel: Long): Map<Int, List<Extent>> {
        val result = mutableMapOf<Int, List<Extent>>()
        val version = c.u8()
        c.skip(3)
        val sizes = c.u8()
        val offsetSize = (sizes shr 4) and 0x0F
        val lengthSize = sizes and 0x0F
        val sizes2 = c.u8()
        val baseOffsetSize = (sizes2 shr 4) and 0x0F
        val indexSize = if (version == 1 || version == 2) sizes2 and 0x0F else 0
        val count = if (version < 2) c.u16() else c.u32().toInt()

        fun readN(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or c.u8().toLong() }
            return v
        }

        var guard = 0
        while (guard < count && c.pos < boxEnd) {
            guard++
            val id = if (version < 2) c.u16() else c.u32().toInt()
            // 12 Bit reserviert + 4 Bit construction_method (0 = Datei-Offset, 1 = idat-Box)
            var constructionMethod = 0
            if (version == 1 || version == 2) constructionMethod = c.u16() and 0x0F
            c.u16() // data_reference_index
            val base = readN(baseOffsetSize)
            val extentCount = c.u16()
            val list = mutableListOf<Extent>()
            for (i in 0 until extentCount) {
                if (version == 1 || version == 2) {
                    if (indexSize > 0) readN(indexSize)
                }
                val off = readN(offsetSize)
                val len = readN(lengthSize)
                if (len > 0) {
                    val absolute = when {
                        constructionMethod == 0 -> base + off                       // Datei-Offset
                        constructionMethod == 1 && idatRel >= 0 -> metaStart + idatRel + base + off  // in der idat-Box
                        else -> -1L
                    }
                    if (absolute >= 0) list += Extent(absolute, len)
                }
            }
            if (list.isNotEmpty()) result[id] = list
        }
        return result
    }

    private fun parseIref(c: Cursor, boxEnd: Int): Pair<Int, Int> {
        c.skip(4)
        var dimg = 0
        var thumb = 0
        while (c.pos + 8 <= boxEnd) {
            val start = c.pos
            val size = c.u32()
            val type = c.fourcc()
            if (size < 8 || size > boxEnd - start) break
            val end = (start + size).coerceAtMost(boxEnd.toLong()).toInt()
            c.skip(2) // from_item_ID
            val refCount = c.u16()
            when (type) {
                "dimg" -> dimg += refCount
                "thmb" -> thumb += refCount
            }
            c.pos = end
        }
        return dimg to thumb
    }

    private data class IprpResult(
        val ispe: List<Pair<Int, Int>>,
        val bitDepth: Int?,
        val channels: Int?,
        val primaries: String?,
        val transfer: String?,
        val fullRange: Boolean?,
        val hevcProfile: Int?,
        val hevcLevel: Int?,
        val aux: List<String>
    )

    private fun parseIprp(c: Cursor, boxEnd: Int): IprpResult {
        val ispe = mutableListOf<Pair<Int, Int>>()
        var bitDepth: Int? = null
        var channels: Int? = null
        var primaries: String? = null
        var transfer: String? = null
        var fullRange: Boolean? = null
        var hevcProfile: Int? = null
        var hevcLevel: Int? = null
        val aux = mutableListOf<String>()

        var guardPos = 0
        while (c.pos + 8 <= boxEnd && guardPos < boxEnd) {
            guardPos++
            val start = c.pos
            val size = c.u32()
            val type = c.fourcc()
            if (size < 8 || size > boxEnd - start) break
            val end = (start + size).coerceAtMost(boxEnd.toLong()).toInt()
            if (type == "ipco") {
                while (c.pos + 8 <= end) {
                    val s2 = c.pos
                    val size2 = c.u32()
                    val t2 = c.fourcc()
                    if (size2 < 8 || size2 > end - s2) break
                    val e2 = (s2 + size2).coerceAtMost(end.toLong()).toInt()
                    when (t2) {
                        "ispe" -> {
                            c.skip(4)
                            val w = c.u32().toInt()
                            val h = c.u32().toInt()
                            if (w > 0 && h > 0) ispe += w to h
                        }
                        "pixi" -> {
                            c.skip(4)
                            val ch = c.u8()
                            channels = ch
                            if (ch > 0) bitDepth = c.u8()
                        }
                        "colr" -> {
                            val colrType = c.fourcc()
                            if (colrType == "nclx") {
                                val p = c.u16()
                                val t = c.u16()
                                c.u16() // matrix
                                val range = c.u8()
                                primaries = primariesName(p)
                                transfer = transferName(t)
                                fullRange = (range and 0x80) != 0
                            } else if (colrType == "prof" || colrType == "rICC") {
                                primaries = "ICC-Profil eingebettet"
                            }
                        }
                        "hvcC" -> {
                            c.u8() // configurationVersion
                            val profileByte = c.u8()
                            hevcProfile = profileByte and 0x1F
                            c.skip(11) // Kompatibilität + Constraints
                            hevcLevel = c.u8()
                        }
                        "auxC" -> {
                            c.skip(4)
                            val s = c.cString(64)
                            if (s.isNotEmpty()) aux += s
                        }
                    }
                    c.pos = e2
                }
            }
            c.pos = end
        }
        return IprpResult(ispe, bitDepth, channels, primaries, transfer, fullRange, hevcProfile, hevcLevel, aux)
    }

    // ---------------------------------------------------------------- Namen

    fun primariesName(v: Int): String? = when (v) {
        1 -> "BT.709 (sRGB)"
        5 -> "BT.601 (PAL)"
        6 -> "BT.601 (NTSC)"
        9 -> "BT.2020 (Weit)"
        12 -> "Display P3 (DCI-P3)"
        22 -> "EBU / P3-D65"
        else -> if (v > 0) "Codepoint $v" else null
    }

    fun transferName(v: Int): String? = when (v) {
        1 -> "BT.709"
        4 -> "Gamma 2.2"
        13 -> "sRGB"
        14 -> "BT.2020 (10 Bit)"
        15 -> "BT.2020 (12 Bit)"
        16 -> "PQ / HDR10"
        18 -> "HLG (HDR)"
        else -> if (v > 0) "Codepoint $v" else null
    }

    fun hevcProfileName(p: Int?): String? = when (p) {
        1 -> "Main"
        2 -> "Main 10"
        3 -> "Main Still Picture"
        4 -> "Rext (Range Extensions)"
        5 -> "High Throughput"
        9 -> "Main 10 Still"
        else -> p?.let { "Profil $it" }
    }

    fun hevcLevelName(l: Int?): String? {
        if (l == null || l == 0) return null
        val major = l / 30
        val minor = (l % 30) / 3
        return "Level $major.$minor"
    }

    fun codedTypeName(t: String): String = when (t) {
        "hvc1" -> "HEVC/H.265 (Einzelbild)"
        "hev1" -> "HEVC/H.265 (Stream)"
        "av01" -> "AV1"
        "jpeg" -> "JPEG (im HEIF-Container)"
        "grid" -> "Kachel-Raster"
        "iovl" -> "Überlagerungsebene"
        "Exif" -> "EXIF-Block"
        "mime" -> "Zusatzdaten"
        "idat" -> "Datenspeicher"
        else -> t
    }

    // ---------------------------------------------------------------- I/O-Hilfen

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        var guard = 0
        while (left > 0 && guard < 1_000_000) {
            guard++
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return
                left--
            } else left -= s
        }
    }
}
