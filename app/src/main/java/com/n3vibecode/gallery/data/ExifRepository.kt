package com.n3vibecode.gallery.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.n3vibecode.gallery.image.HeifInspector
import com.n3vibecode.gallery.util.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MetaRow(val label: String, val value: String)

data class MetaSection(val title: String, val rows: List<MetaRow>)

data class MediaMeta(
    val item: MediaItem,
    val sections: List<MetaSection>,
    val heif: HeifInspector.Info?,
    val exifTagDump: List<MetaRow>,
    val note: String,
    val tags: List<String>,
    val writeSupported: Boolean
) {
    /** Alle Metadaten als Text. [tr] erlaubt die Übersetzung der Beschriftungen. */
    fun asPlainText(tr: ((String) -> String)? = null): String {
        val t: (String) -> String = tr ?: { it }
        val sb = StringBuilder()
        sb.append(item.name).append('\n')
        sections.forEach { s ->
            sb.append("\n[").append(t(s.title)).append("]\n")
            s.rows.forEach { r -> sb.append(t(r.label)).append(": ").append(t(r.value)).append('\n') }
        }
        if (exifTagDump.isNotEmpty()) {
            sb.append("\n[").append(t("Alle EXIF-Felder (Rohwerte)")).append("]\n")
            exifTagDump.forEach { r -> sb.append(t(r.label)).append(": ").append(t(r.value)).append('\n') }
        }
        return sb.toString()
    }
}

/**
 * Liest alle Metadaten: Datei/Format, Kamera & Optik, Sensor/Aufnahmeparameter,
 * GPS, DNG-/RAW-Details, HEIF/AVIF-Containertechnik und XMP (Notizen, Tags).
 */
object ExifRepository {

    fun load(ctx: Context, item: MediaItem): MediaMeta {
        val sections = mutableListOf<MetaSection>()
        val shownTags = linkedSetOf<String>()
        var heif: HeifInspector.Info? = null
        var writeSupported = isWriteSupported(item)
        var xmpRaw: String? = null

        // ------------------------------ EXIF öffnen (Attribute werden sofort gelesen)
        var exif: ExifInterface? = null
        try {
            ctx.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                val e = ExifInterface(input)
                // Attribute in den Speicher laden, solange der Stream offen ist
                e.getAttribute(ExifInterface.TAG_MAKE)
                e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                exif = e
            }
        } catch (_: Throwable) {
            exif = null
        }
        val e = exif

        if (e != null) {
            xmpRaw = runCatching { e.getAttribute("XMP") }.getOrNull()
        }

        // ------------------------------ HEIF/AVIF-Container lesen
        if (item.isHeif || item.isAvif) {
            heif = try {
                val uri = Uri.parse(item.uri)
                HeifInspector.inspect(
                    open = { runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull() },
                    fileSize = item.size
                )
            } catch (_: Throwable) {
                null
            }
        }

        fun addRow(rows: MutableList<MetaRow>, label: String, value: String?) {
            val v = Fmt.cleanExif(value)
            if (v != null) rows += MetaRow(label, v)
        }

        fun tag(rows: MutableList<MetaRow>, tagName: String, label: String, transform: (String) -> String? = { it }) {
            if (e == null) return
            val raw = Fmt.cleanExif(runCatching { e.getAttribute(tagName) }.getOrNull()) ?: return
            shownTags += tagName
            val v = runCatching { transform(raw) }.getOrNull() ?: raw
            rows += MetaRow(label, v)
        }

        // === 1) Datei & Format =================================================
        run {
            val rows = mutableListOf<MetaRow>()
            rows += MetaRow("Dateiname", item.name)
            val desc = Formats.description(item.ext)
            rows += MetaRow("Format", if (desc.isEmpty()) item.format else "${item.format} · $desc")
            if (item.mime.isNotBlank()) rows += MetaRow("MIME-Typ", item.mime)
            if (item.size > 0) {
                rows += MetaRow("Dateigröße", "${Fmt.bytes(item.size)}  (${String.format(Locale.GERMAN, "%,d", item.size)} Byte)")
            }
            val w = if (item.width > 0) item.width else heif?.primaryWidth ?: 0
            val h = if (item.height > 0) item.height else heif?.primaryHeight ?: 0
            if (w > 0 && h > 0) {
                rows += MetaRow("Auflösung", "$w × $h Pixel")
                rows += MetaRow("Megapixel", Fmt.megapixel(w, h))
                rows += MetaRow("Seitenverhältnis", Fmt.ratio(w, h))
            }
            val dtOriginal = e?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            if (!dtOriginal.isNullOrBlank()) {
                rows += MetaRow("Aufgenommen (EXIF)", exifDate(dtOriginal))
            }
            rows += MetaRow("Aufgenommen", Fmt.dateTime(item.time) + if (dtOriginal.isNullOrBlank()) " (Dateisystem)" else "")
            rows += MetaRow("Geändert", Fmt.dateTime(item.modifiedAt))
            rows += MetaRow("Ordner", item.bucket.ifEmpty { "–" })
            if (item.path.isNotBlank()) rows += MetaRow("Pfad", item.path)
            rows += MetaRow("Quelle", if (item.isSaf) "Eigener Ordner (SAF)" else "Android-Medienindex")
            tag(rows, "Orientation", "Ausrichtung") { orientationName(it) }
            tag(rows, "BitsPerSample", "Bit-Tiefe") { "$it Bit pro Kanal" }
            tag(rows, "ColorSpace", "Farbraum") { colorSpaceName(it) }
            tag(rows, "Compression", "Kompression") { compressionName(it) }
            tag(rows, "PhotometricInterpretation", "Farbmodell") { photometricName(it) }
            tag(rows, "SamplesPerPixel", "Kanäle")
            if (heif?.bitDepth != null && e?.getAttribute("BitsPerSample") == null) {
                rows += MetaRow("Bit-Tiefe", "${heif.bitDepth} Bit pro Kanal (HEIF/pixi)")
            }
            sections += MetaSection("Datei & Format", rows)
        }

        // === 2) HEIF/AVIF-Technik ==============================================
        if (heif != null) {
            val rows = mutableListOf<MetaRow>()
            rows += MetaRow("Container-Marke (ftyp)", heif.majorBrand)
            if (heif.compatible.isNotEmpty()) rows += MetaRow("Kompatible Marken", heif.compatible.joinToString(", "))
            rows += MetaRow("Bilder im Container", heif.itemCount.toString())
            if (heif.codedTypes.isNotEmpty()) {
                rows += MetaRow("Codierte Bild-Typen", heif.codedTypes.joinToString(", ") { HeifInspector.codedTypeName(it) })
            }
            val tiles = heif.tiles
            if (tiles != null) {
                val tileSize = if (tiles.tileWidth > 0 && tiles.tileHeight > 0)
                    " à ${tiles.tileWidth} × ${tiles.tileHeight} px" else ""
                val out = if (tiles.outWidth > 0 && tiles.outHeight > 0)
                    " · Ausgabe ${tiles.outWidth} × ${tiles.outHeight} px" else ""
                rows += MetaRow(
                    "Kachel-Raster (Grid)",
                    "${tiles.cols} Spalten × ${tiles.rows} Zeilen = ${tiles.count} Kacheln$tileSize$out"
                )
            } else if (heif.isGrid) {
                rows += MetaRow("Kachel-Raster (Grid)", "Ja – Container aus mehreren Kacheln (typisch Apple-Screenshot & iPad)")
            }
            heif.primaryType?.let { t ->
                rows += MetaRow(
                    "Primäres Bild",
                    HeifInspector.codedTypeName(t) + (heif.primaryItemName?.let { " · „$it\u201c" } ?: "")
                )
            }
            if (heif.auxiliaryTypes.isNotEmpty()) {
                rows += MetaRow("Zusatzebenen (aux)", heif.auxiliaryTypes.joinToString(", "))
            }
            rows += MetaRow(
                "HDR-Gain-Map (Apple/Google)",
                if (heif.gainMap) "Ja" + (heif.gainMapType?.let { " – $it" } ?: "") else "Nein"
            )
            heif.primaries?.let { rows += MetaRow("Farbprimärvalenz", it) }
            heif.transfer?.let { rows += MetaRow("HDR-Transferfunktion", it) }
            if (heif.fullRange != null) {
                rows += MetaRow("Wertebereich", if (heif.fullRange == true) "Full Range (0–255)" else "Limited Range (16–235)")
            }
            if (heif.hevcProfile != null) rows += MetaRow("HEVC-Profil", HeifInspector.hevcProfileName(heif.hevcProfile) ?: "–")
            if (heif.hevcLevel != null) rows += MetaRow("HEVC-Level", HeifInspector.hevcLevelName(heif.hevcLevel) ?: "–")
            rows += MetaRow("Alpha-Kanal", if (heif.auxiliaryTypes.any { it.contains("alpha", true) }) "Ja" else "Nein")
            rows += MetaRow(
                "Integrierter HEIF-Decoder",
                if (com.n3vibecode.gallery.image.HeifSupport.available)
                    "Verfügbar (libheif) – dekodiert gekachelte Container, 10-Bit & HDR"
                else "Nicht verfügbar – es greift der System-Decoder"
            )
            val jpegItems = heif.jpegItems()
            if (jpegItems.isNotEmpty()) {
                rows += MetaRow("JPEG-kodiertes HEIF-Bild", "Ja (${jpegItems.size}×, größtes ${Fmt.bytes(jpegItems.maxOf { it.size })})")
            }
            if (heif.items.isNotEmpty()) {
                rows += MetaRow(
                    "Bild-Einträge",
                    heif.items.take(8).joinToString(" · ") { it ->
                        val size = if (it.size > 0) " (${Fmt.bytes(it.size)})" else ""
                        "${it.name} [${HeifInspector.codedTypeName(it.type)}]$size"
                    }
                )
            }
            sections += MetaSection("HEIF/AVIF-Technik", rows)
        }

        // === 3) Kamera & Optik =================================================
        if (e != null) {
            val rows = mutableListOf<MetaRow>()
            tag(rows, "Make", "Hersteller")
            tag(rows, "Model", "Modell")
            tag(rows, "LensModel", "Objektiv")
            tag(rows, "LensMake", "Objektiv-Hersteller")
            tag(rows, "LensSpecification", "Objektiv-Spezifikation") { lensSpec(it) }
            tag(rows, "LensSerialNumber", "Objektiv-Seriennummer")
            tag(rows, "BodySerialNumber", "Kamera-Seriennummer")
            tag(rows, "CameraOwnerName", "Besitzer")
            tag(rows, "Artist", "Fotograf")
            tag(rows, "Copyright", "Copyright")
            tag(rows, "Software", "Software")
            tag(rows, "ExifVersion", "EXIF-Version")
            tag(rows, "ImageUniqueID", "Bild-Kennung")
            if (rows.isNotEmpty()) sections += MetaSection("Kamera & Optik", rows)
        }

        // === 4) Sensor & Aufnahme ==============================================
        if (e != null) {
            val rows = mutableListOf<MetaRow>()
            addRow(rows, "Belichtungszeit", Fmt.rational(e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)))
            addRow(rows, "Blende", e.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()
                ?.let { String.format(Locale.GERMAN, "f/%.1f", it) })
            addRow(
                rows, "ISO",
                (e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?: e.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS))?.split(',')?.firstOrNull()?.trim()
            )
            addRow(rows, "Brennweite", e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                ?.let { f -> Fmt.rational(f)?.let { "$it mm" } ?: f })
            addRow(rows, "Brennweite (35 mm)", e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
                ?.let { "$it mm" })
            addRow(rows, "Belichtungsprogramm", exposureProgramName(safeInt(e, ExifInterface.TAG_EXPOSURE_PROGRAM)))
            addRow(rows, "Belichtungskorrektur", Fmt.rational(e.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE))?.let { "$it EV" })
            addRow(rows, "Messmethode", meteringName(safeInt(e, ExifInterface.TAG_METERING_MODE)))
            addRow(rows, "Weißabgleich", whiteBalanceName(safeInt(e, ExifInterface.TAG_WHITE_BALANCE)))
            addRow(rows, "Lichtquelle", lightSourceName(safeInt(e, ExifInterface.TAG_LIGHT_SOURCE)))
            addRow(rows, "Blitz", flashName(safeInt(e, ExifInterface.TAG_FLASH)))
            addRow(rows, "Motivprogramm", sceneName(safeInt(e, ExifInterface.TAG_SCENE_CAPTURE_TYPE)))
            addRow(rows, "Größte Blende", Fmt.rational(e.getAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE))?.let { "f/$it" })
            addRow(rows, "Motivabstand", Fmt.rational(e.getAttribute(ExifInterface.TAG_SUBJECT_DISTANCE))?.let { "$it m" })
            addRow(rows, "Digitalzoom", Fmt.rational(e.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO))?.let { "$it×" })
            addRow(rows, "Sensortyp", sensingMethodName(safeInt(e, ExifInterface.TAG_SENSING_METHOD)))
            addRow(rows, "Schärfe", senseName(safeInt(e, ExifInterface.TAG_SHARPNESS)))
            addRow(rows, "Kontrast", senseName(safeInt(e, ExifInterface.TAG_CONTRAST)))
            addRow(rows, "Sättigung", senseName(safeInt(e, ExifInterface.TAG_SATURATION)))
            addRow(rows, "Subsekunden", e.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
            addRow(rows, "Zeitzone Aufnahme", runCatching { e.getAttribute("OffsetTimeOriginal") }.getOrNull())
            addRow(rows, "Aufnahmezeitpunkt (EXIF)", e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { exifDate(it) })
            if (rows.isNotEmpty()) sections += MetaSection("Sensor & Aufnahme", rows)
        }

        // === Video =============================================================
        if (item.isVideoFile) {
            val vRows = videoMeta(ctx, item)
            if (vRows.isNotEmpty()) sections += MetaSection("Video", vRows)
        }

        // === 5) RAW / DNG ======================================================
        if (item.isRaw && e != null) {
            val rows = mutableListOf<MetaRow>()
            tag(rows, "DNGVersion", "DNG-Version") { dngVersion(it) }
            tag(rows, "DNGBackwardVersion", "DNG-Rückwärtsversion") { dngVersion(it) }
            tag(rows, "UniqueCameraModel", "Kamera-Kennung (DNG)")
            tag(rows, "LocalizedCameraModel", "Kamera-Modell (lokalisiert)")
            tag(rows, "CFARepeatPatternDim", "CFA-Raster")
            tag(rows, "CFAPattern", "CFA-Muster (Bayer)") { cfaPattern(it) }
            tag(rows, "CFAPlaneColor", "CFA-Farbebenen")
            tag(rows, "BlackLevel", "Schwarzpegel")
            tag(rows, "WhiteLevel", "Weißpegel")
            tag(rows, "AsShotNeutral", "AsShotNeutral (Weißabgleich)") { rationalList(it) }
            tag(rows, "AsShotWhiteXY", "Weißpunkt (xy)") { rationalList(it) }
            tag(rows, "ColorMatrix1", "Farbmatrix 1") { matrixShort(it) }
            tag(rows, "ColorMatrix2", "Farbmatrix 2") { matrixShort(it) }
            tag(rows, "CameraCalibration1", "Sensorkalibrierung 1") { matrixShort(it) }
            tag(rows, "CalibrationIlluminant1", "Kalibrier-Lichtart 1") { illuminantName(it) }
            tag(rows, "CalibrationIlluminant2", "Kalibrier-Lichtart 2") { illuminantName(it) }
            tag(rows, "BaselineExposure", "Basisbelichtung") { Fmt.rational(it) }
            tag(rows, "DefaultCropSize", "Standard-Zuschnitt")
            tag(rows, "ActiveArea", "Aktiver Sensorbereich")
            tag(rows, "MaskedAreas", "Maskierte Bereiche")
            tag(rows, "AnalogBalance", "Analog-Balance") { rationalList(it) }
            tag(rows, "LinearResponseLimit", "Linearitätsgrenze") { Fmt.rational(it) }
            tag(rows, "PreviewColorSpace", "Vorschau-Farbraum") { previewColorSpace(it) }
            tag(rows, "CameraSerialNumber", "Kamera-Seriennummer (DNG)")
            tag(rows, "OriginalRawFileName", "Originaler RAW-Dateiname")
            tag(rows, "ProfileName", "Kameraprofil")
            rows += MetaRow("Angezeigte Vorschau", "Eingebettetes Kamerabild (größter JPEG-Block der Datei)")
            sections += MetaSection("RAW / DNG-Details", rows)
        }

        // === 6) GPS ============================================================
        if (e != null) {
            val rows = mutableListOf<MetaRow>()
            val latLong: DoubleArray? = runCatching { e.latLong }.getOrNull()
            if (latLong != null && (latLong[0] != 0.0 || latLong[1] != 0.0)) {
                rows += MetaRow("Breitengrad", String.format(Locale.GERMAN, "%.6f° %s", Math.abs(latLong[0]), if (latLong[0] >= 0) "Nord" else "Süd"))
                rows += MetaRow("Längengrad", String.format(Locale.GERMAN, "%.6f° %s", Math.abs(latLong[1]), if (latLong[1] >= 0) "Ost" else "West"))
            }
            tag(rows, "GPSAltitude", "Höhe") { Fmt.rational(it)?.let { v -> "$v m" } ?: it }
            tag(rows, "GPSDateStamp", "GPS-Datum")
            tag(rows, "GPSTimeStamp", "GPS-Zeit (UTC)")
            tag(rows, "GPSProcessingMethod", "GPS-Methode")
            tag(rows, "GPSDOP", "Genauigkeit (DOP)") { Fmt.rational(it) }
            tag(rows, "GPSSpeed", "Geschwindigkeit")
            tag(rows, "GPSImgDirection", "Blickrichtung")
            if (rows.isNotEmpty()) sections += MetaSection("Ort (GPS)", rows)
        }

        // === 7) Notizen, Tags & XMP ============================================
        run {
            val rows = mutableListOf<MetaRow>()
            val noteInternal = MetaStore.note(item.uri)
            val noteInFile = e?.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()
            val note = noteInternal.ifBlank { noteInFile }
            rows += MetaRow("Notiz / Bildbeschreibung", note.ifBlank { "– noch keine Notiz –" })
            val tagsInternal = MetaStore.tags(item.uri)
            val allTags = tagsInternal.distinct()
            if (allTags.isNotEmpty()) rows += MetaRow("Tags / Keywords", allTags.joinToString(", "))
            if (MetaStore.isFavorite(item.uri)) rows += MetaRow("Favorit", "Ja ★")
            addRow(rows, "Benutzerkommentar (EXIF)", e?.getAttribute(ExifInterface.TAG_USER_COMMENT))
            addRow(rows, "XMP-Paket vorhanden", if (xmpRaw.isNullOrBlank()) "Nein" else "Ja (${xmpRaw.length} Zeichen)")
            rows += xmpFields(xmpRaw)
            if (e != null) {
                rows += MetaRow(
                    "Schreiben in die Datei",
                    if (writeSupported) "Ja – Notiz & Tags werden als EXIF/XMP in die Bilddatei geschrieben"
                    else "Nein – ${item.format} lässt sich von Android nicht beschreiben (Notiz bleibt sicher in der App, tagsüber XMP-Sidecar exportierbar)"
                )
            }
            sections += MetaSection("Notizen, Tags & XMP", rows)
        }

        // === Restliche EXIF-Felder =============================================
        val dump = mutableListOf<MetaRow>()
        if (e != null) {
            for ((tagName, label) in ALL_TAGS) {
                if (shownTags.contains(tagName)) continue
                val v = Fmt.cleanExif(runCatching { e.getAttribute(tagName) }.getOrNull()) ?: continue
                dump += MetaRow(label, v.replace('\n', ' ').take(400))
            }
        }
        if (heif != null) {
            if (heif.thumbRefs > 0) dump += MetaRow("Miniaturansichten im Container", heif.thumbRefs.toString())
            if (heif.dimgRefs > 0) dump += MetaRow("Bild-Referenzen (dimg)", heif.dimgRefs.toString())
            if (heif.channels != null) dump += MetaRow("Kanäle (pixi)", heif.channels.toString())
        }

        return MediaMeta(
            item = item,
            sections = sections,
            heif = heif,
            exifTagDump = dump,
            note = MetaStore.note(item.uri).ifBlank { e?.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty() },
            tags = MetaStore.tags(item.uri),
            writeSupported = writeSupported
        )
    }

    fun isWriteSupported(item: MediaItem): Boolean = when (item.ext) {
        "jpg", "jpeg", "jpe", "jfif", "png", "webp" -> true
        else -> false
    }

    private fun safeInt(e: ExifInterface, tag: String): Int =
        runCatching { e.getAttributeInt(tag, -1) }.getOrDefault(-1)

    // ------------------------------------------------------------------ Video

    private fun videoMeta(ctx: Context, item: MediaItem): List<MetaRow> {
        val rows = mutableListOf<MetaRow>()
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, Uri.parse(item.uri))
            fun m(key: Int): String? = runCatching { mmr.extractMetadata(key) }.getOrNull()
            m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { rows += MetaRow("Laufzeit", Fmt.duration(it)) }
            val w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) {
                rows += MetaRow("Bildgröße", "$w × $h Pixel")
                rows += MetaRow("Seitenverhältnis", Fmt.ratio(w, h))
            }
            m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != "0" }?.let { rows += MetaRow("Drehung", "$it°") }
            m(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.let {
                rows += MetaRow("Bildrate", String.format(Locale.GERMAN, "%.2f fps", it.toFloatOrNull() ?: 0f))
            }
            m(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { rows += MetaRow("MIME-Typ", it) }
            m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
                rows += MetaRow("Datenrate", String.format(Locale.GERMAN, "%.1f Mbit/s", it / 1_000_000.0))
            }
            m(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.let { rows += MetaRow("Tonspur", if (it == "yes") "Ja" else "Nein") }
        } catch (_: Throwable) {
        } finally {
            runCatching { mmr.release() }
        }
        return rows
    }

    // ------------------------------------------------------------------ XMP

    private fun xmpFields(xmp: String?): List<MetaRow> {
        if (xmp.isNullOrBlank() || !xmp.contains('<')) return emptyList()
        val out = mutableListOf<MetaRow>()
        fun first(pattern: String): String? = runCatching {
            Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(xmp)?.groupValues?.getOrNull(1)?.let { clean(it) }
        }.getOrNull()?.takeIf { it.isNotEmpty() && it.length < 500 }

        first("<dc:description>.*?<rdf:li[^>]*>(.*?)</rdf:li>")?.let { out += MetaRow("XMP-Beschreibung", it) }
        val subjects = runCatching {
            Regex("<dc:subject>.*?</dc:subject>", RegexOption.DOT_MATCHES_ALL).find(xmp)?.value
                ?.let { block ->
                    Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL).findAll(block)
                        .map { clean(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
                }
        }.getOrNull()
        if (!subjects.isNullOrEmpty()) out += MetaRow("XMP-Themen (Tags)", subjects.joinToString(", "))
        first("<xmp:Rating>(.*?)</xmp:Rating>")?.let { out += MetaRow("Bewertung (XMP)", "$it ★") }
        first("<dc:creator>.*?<rdf:li[^>]*>(.*?)</rdf:li>")?.let { out += MetaRow("XMP-Urheber", it) }
        first("<Iptc4xmpCore:Location>(.*?)</Iptc4xmpCore:Location>")?.let { out += MetaRow("XMP-Ort", it) }
        first("xmp:CreateDate=\"(.*?)\"")?.let { out += MetaRow("XMP erstellt", it) }
        first("xmp:ModifyDate=\"(.*?)\"")?.let { out += MetaRow("XMP geändert", it) }
        return out
    }

    private fun clean(s: String): String = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()

    // ------------------------------------------------------------------ Namens-Dekoder

    fun orientationName(v: String): String = when (v.toIntOrNull()) {
        1 -> "Normal (0°)"
        2 -> "Horizontal gespiegelt"
        3 -> "Um 180° gedreht"
        4 -> "Vertikal gespiegelt"
        5 -> "Transponiert (90° + Spiegelung)"
        6 -> "Um 90° gedreht"
        7 -> "Transversal (270° + Spiegelung)"
        8 -> "Um 270° gedreht"
        else -> "Wert $v"
    }

    fun colorSpaceName(v: String): String = when (v.toIntOrNull()) {
        1 -> "sRGB"
        2 -> "AdobeRGB"
        65535 -> "Nicht kalibriert"
        else -> "Wert $v"
    }

    fun compressionName(v: String): String = when (v.toIntOrNull()) {
        1 -> "Unkomprimiert"
        2 -> "CCITT Huffman"
        5 -> "LZW"
        6 -> "JPEG (alt)"
        7 -> "JPEG"
        8 -> "Deflate/ZIP"
        32773 -> "PackBits"
        34892 -> "Verlustbehaftetes JPEG (DNG)"
        else -> "Wert $v"
    }

    fun photometricName(v: String): String = when (v.toIntOrNull()) {
        0 -> "Weiß ist Null"
        1 -> "Schwarz ist Null"
        2 -> "RGB"
        3 -> "Palette"
        5 -> "CMYK"
        6 -> "YCbCr"
        8 -> "CIELab"
        32803 -> "CFA (Bayer-Sensor)"
        34892 -> "Linear RAW"
        else -> "Wert $v"
    }

    private fun lensSpec(v: String): String {
        val parts = v.split(',').map { it.trim() }
        if (parts.size >= 4) {
            val min = Fmt.rational(parts[0])
            val max = Fmt.rational(parts[1])
            val fMin = Fmt.rational(parts[2])
            val fMax = Fmt.rational(parts[3])
            return "$min–$max mm · f/$fMin–$fMax"
        }
        return v
    }

    fun dngVersion(v: String): String {
        val nums = v.split(' ', ',').mapNotNull { it.trim().toIntOrNull() }
        return if (nums.size >= 4) "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}" else v
    }

    private fun cfaPattern(v: String): String {
        val nums = v.split(' ', ',').mapNotNull { it.trim().toIntOrNull() }
        if (nums.size >= 4) {
            val names = listOf("R", "G", "B", "G")
            return nums.take(4).joinToString(" ") { n -> names.getOrElse(n) { "?" } } + "   (Bayer-Farbfilter)"
        }
        return v
    }

    private fun rationalList(v: String): String =
        v.split(' ').filter { it.isNotBlank() }.joinToString(", ") { Fmt.rational(it) ?: it }

    private fun matrixShort(v: String): String {
        val parts = v.split(' ').filter { it.isNotBlank() }
        if (parts.size >= 9) {
            return parts.chunked(3).joinToString("   |   ") { row -> row.joinToString(" ") { Fmt.rational(it) ?: it } }
        }
        return v
    }

    private fun previewColorSpace(v: String): String = when (v.toIntOrNull()) {
        1 -> "sRGB"
        2 -> "AdobeRGB"
        3 -> "ProPhoto RGB"
        else -> "Wert $v"
    }

    private fun illuminantName(v: String): String = when (v.toIntOrNull()) {
        0 -> "Unbekannt"
        1 -> "Tageslicht"
        2 -> "Fluoreszierend (F2)"
        3 -> "Leuchtstoff (F11)"
        4 -> "Blitz"
        9 -> "Feines Wetter"
        10 -> "Bewölkt"
        11 -> "Schatten"
        17 -> "Standardlicht A"
        18 -> "Standardlicht B"
        19 -> "Standardlicht C"
        20 -> "D55"
        21 -> "D65"
        22 -> "D75"
        23 -> "D50"
        24 -> "Studio-Blitz (ISO)"
        255 -> "Andere"
        else -> "Wert $v"
    }

    fun exposureProgramName(v: Int): String? = when (v) {
        0 -> "Nicht definiert"
        1 -> "Manuell"
        2 -> "Programmautomatik"
        3 -> "Blendenpriorität (Av)"
        4 -> "Zeitpriorität (Tv)"
        5 -> "Kreativprogramm"
        6 -> "Sportprogramm"
        7 -> "Porträtprogramm"
        8 -> "Landschaftsprogramm"
        else -> null
    }

    fun meteringName(v: Int): String? = when (v) {
        0 -> "Unbekannt"
        1 -> "Mittenbetont"
        2 -> "Mittenbetont (Mittelwert)"
        3 -> "Spot"
        4 -> "Mehrfeld / Matrix"
        5 -> "Mehr-Spot"
        6 -> "Partiell"
        255 -> "Andere"
        else -> null
    }

    fun whiteBalanceName(v: Int): String? = when (v) {
        0 -> "Automatisch"
        1 -> "Manuell"
        else -> null
    }

    fun lightSourceName(v: Int): String? = when (v) {
        0 -> "Unbekannt"
        1 -> "Tageslicht"
        2 -> "Leuchtstoff"
        3 -> "Glühlampenlicht"
        4 -> "Blitz"
        9 -> "Feines Wetter"
        10 -> "Bewölkt"
        11 -> "Schatten"
        255 -> "Andere"
        else -> null
    }

    fun sceneName(v: Int): String? = when (v) {
        0 -> "Standard"
        1 -> "Landschaft"
        2 -> "Porträt"
        3 -> "Nachtaufnahme"
        else -> null
    }

    fun sensingMethodName(v: Int): String? = when (v) {
        1 -> "Nicht definiert"
        2 -> "Ein-Chip-Farbsensor"
        3 -> "Zwei-Chip-Farbsensor"
        4 -> "Drei-Chip-Farbsensor"
        5 -> "Farbfolge-Sensor"
        else -> null
    }

    private fun senseName(v: Int): String? = when (v) {
        0 -> "Normal"
        1 -> "Weich"
        2 -> "Hart"
        else -> null
    }

    fun flashName(v: Int): String? {
        if (v < 0) return null
        val fired = (v and 0x1) != 0
        val mode = (v shr 3) and 0x3
        val redEye = (v and 0x40) != 0
        val modeName = when (mode) {
            1 -> "erzwungen"
            2 -> "unterdrückt"
            3 -> "Automatik"
            else -> ""
        }
        return buildString {
            append(if (fired) "Ausgelöst" else "Nicht ausgelöst")
            if (modeName.isNotEmpty()) append(" · $modeName")
            if (redEye) append(" · Rote-Augen-Korrektur")
        }
    }

    fun exifDate(v: String): String = runCatching {
        val inFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.GERMAN)
        val out = SimpleDateFormat("dd.MM.yyyy, HH:mm:ss", Locale.GERMAN)
        out.format(inFmt.parse(v) ?: Date(0))
    }.getOrDefault(v)

    private val ALL_TAGS: List<Pair<String, String>> = listOf(
        "ImageWidth" to "Bildbreite", "ImageLength" to "Bildhöhe", "SamplesPerPixel" to "Abtastwerte",
        "PlanarConfiguration" to "Ebenen-Konfiguration", "YCbCrSubSampling" to "YCbCr-Unterabtastung",
        "XResolution" to "X-Auflösung", "YResolution" to "Y-Auflösung", "ResolutionUnit" to "Auflösungseinheit",
        "DateTime" to "Datum/Uhrzeit (Datei)", "DateTimeDigitized" to "Digitalisiert",
        "OffsetTime" to "Zeitzone (Datei)", "OffsetTimeDigitized" to "Zeitzone (digitalisiert)",
        "SubSecTime" to "Subsekunden (Datei)", "SubSecTimeDigitized" to "Subsekunden (digitalisiert)",
        "UserComment" to "Benutzerkommentar", "MakerNote" to "Hersteller-Notizen (MakerNote)",
        "InteroperabilityIndex" to "Interoperabilität", "FlashpixVersion" to "FlashPix-Version",
        "ComponentsConfiguration" to "Komponenten-Konfiguration", "PixelXDimension" to "Pixelbreite (EXIF)",
        "PixelYDimension" to "Pixelhöhe (EXIF)", "RelatedSoundFile" to "Zugehörige Tondatei",
        "ShutterSpeedValue" to "Verschlusszeit (APEX)", "ApertureValue" to "Blende (APEX)",
        "BrightnessValue" to "Helligkeit (APEX)", "ExposureMode" to "Belichtungsmodus",
        "SensitivityType" to "Empfindlichkeitstyp", "StandardOutputSensitivity" to "Standard-Empfindlichkeit",
        "RecommendedExposureIndex" to "Empfohlener Belichtungsindex", "GainControl" to "Verstärkungsregelung",
        "SubjectDistanceRange" to "Motivabstandsbereich", "SubjectArea" to "Motivbereich",
        "ExposureIndex" to "Belichtungsindex", "SpectralSensitivity" to "Spektrale Empfindlichkeit",
        "GPSVersionID" to "GPS-Version", "GPSStatus" to "GPS-Status", "GPSMeasureMode" to "GPS-Messmodus",
        "GPSSatellites" to "GPS-Satelliten", "GPSSpeedRef" to "Geschwindigkeitseinheit",
        "GPSTrack" to "Fahrtrichtung", "GPSImgDirectionRef" to "Blickrichtung (Referenz)",
        "GPSMapDatum" to "Kartendatum", "GPSAreaInformation" to "GPS-Bereichsinfo",
        "DNGPrivateData" to "DNG-Privatdaten", "OpcodeList1" to "DNG-Opcode-Liste 1",
        "OpcodeList2" to "DNG-Opcode-Liste 2", "OpcodeList3" to "DNG-Opcode-Liste 3",
        "NoiseProfile" to "Rauschprofil", "DefaultCropOrigin" to "Standard-Zuschnitt-Ursprung",
        "DefaultScale" to "Standard-Skalierung", "BayerGreenSplit" to "Bayer-Grün-Split",
        "AntiAliasStrength" to "Anti-Alias-Stärke", "ShadowScale" to "Schatten-Skalierung",
        "BaselineNoise" to "Basis-Rauschen", "BaselineSharpness" to "Basis-Schärfe",
        "ProfileToneCurve" to "Profil-Tonkurve", "ProfileEmbedPolicy" to "Profil-Einbettung",
        "ProfileCopyright" to "Profil-Copyright", "PreviewDateTime" to "Vorschau erstellt",
        "RawDataUniqueID" to "RAW-Kennung", "ThumbnailOffset" to "Miniaturbild-Offset"
    )
}
