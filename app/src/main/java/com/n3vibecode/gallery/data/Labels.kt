package com.n3vibecode.gallery.data

import android.content.Context
import java.util.Locale

/**
 * Übersetzung der Metadaten-Beschriftungen.
 *
 * Die Datenaufbereitung liefert deutsche Beschriftungen (kurz und platzsparend).
 * Ist das Gerät bzw. die App auf Englisch gestellt, werden sie hier ins Englische
 * übertragen – exakte Treffer über eine Tabelle, dazu ein paar Regeln für Werte,
 * die aus Bausteinen zusammengesetzt sind (z. B. „8 × 6 Kacheln“).
 */
object Labels {

    private val map: Map<String, String> = mapOf(
        // ---- Abschnitte -------------------------------------------------------
        "Datei & Format" to "File & format",
        "HEIF/AVIF-Technik" to "HEIF/AVIF details",
        "Kamera & Optik" to "Camera & lens",
        "Sensor & Aufnahme" to "Sensor & capture",
        "RAW / DNG-Details" to "RAW / DNG details",
        "Ort (GPS)" to "Location (GPS)",
        "Notizen, Tags & XMP" to "Notes, tags & XMP",
        "Video" to "Video",

        // ---- Datei / Format --------------------------------------------------
        "Dateiname" to "File name",
        "Format" to "Format",
        "MIME-Typ" to "MIME type",
        "Dateigröße" to "File size",
        "Auflösung" to "Resolution",
        "Megapixel" to "Megapixels",
        "Seitenverhältnis" to "Aspect ratio",
        "Aufgenommen" to "Taken",
        "Aufgenommen (EXIF)" to "Taken (EXIF)",
        "Geändert" to "Modified",
        "Ordner" to "Folder",
        "Pfad" to "Path",
        "Quelle" to "Source",
        "Bit-Tiefe" to "Bit depth",
        "Drehung" to "Rotation",
        "Bildgröße" to "Image size",
        "Größe" to "Size",
        "Breite" to "Width",
        "Höhe" to "Height",
        "Alpha-Kanal" to "Alpha channel",
        "Farbe" to "Color",
        "Angezeigte Vorschau" to "Displayed preview",
        "Bild-Einträge" to "Image items",
        "Primäres Bild" to "Primary image",
        "Miniaturansichten im Container" to "Thumbnails in container",

        // ---- HEIF / AVIF -----------------------------------------------------
        "Container-Marke (ftyp)" to "Container brand (ftyp)",
        "Kompatible Marken" to "Compatible brands",
        "Bilder im Container" to "Images in container",
        "Codierte Bild-Typen" to "Coded image types",
        "Kachel-Raster (Grid)" to "Tile grid",
        "Kacheln" to "Tiles",
        "HEVC-Profil" to "HEVC profile",
        "HEVC-Level" to "HEVC level",
        "Kanäle (pixi)" to "Channels (pixi)",
        "Wertebereich" to "Value range",
        "HDR-Gain-Map (Apple/Google)" to "HDR gain map (Apple/Google)",
        "HDR-Transferfunktion" to "HDR transfer function",
        "Farbprimärvalenz" to "Color primaries",
        "Integrierter HEIF-Decoder" to "Built-in HEIF decoder",
        "JPEG-kodiertes HEIF-Bild" to "JPEG-coded HEIF image",
        "Bild-Referenzen (dimg)" to "Image references (dimg)",
        "Zusatzebenen (aux)" to "Auxiliary layers (aux)",

        // ---- Kamera / Optik --------------------------------------------------
        "Hersteller" to "Make",
        "Modell" to "Model",
        "Objektiv" to "Lens",
        "Software" to "Software",
        "Seriennummer" to "Serial number",
        "Blitz" to "Flash",
        "Brennweite" to "Focal length",
        "Blende" to "Aperture",
        "Belichtungszeit" to "Exposure time",
        "ISO" to "ISO",
        "Aufnahmeprogramm" to "Exposure program",
        "Belichtungskorrektur" to "Exposure bias",
        "Messmethode" to "Metering mode",
        "Weißabgleich" to "White balance",
        "AsShotNeutral (Weißabgleich)" to "AsShotNeutral (white balance)",
        "Größte Blende" to "Largest aperture",
        "Belichtungsindex" to "Exposure index",

        // ---- Sensor / Aufnahme -----------------------------------------------
        "Sensortyp" to "Sensor type",
        "Schwarzpegel" to "Black level",
        "Weißpegel" to "White level",
        "Linearitätsgrenze" to "Linearization limit",
        "Basis-Schärfe" to "Baseline sharpness",
        "Schärfe" to "Sharpness",
        "Sättigung" to "Saturation",
        "Bayer-Grün-Split" to "Bayer green split",
        "Verstärkungsregelung" to "Gain control",
        "Weiß ist Null" to "White is zero",
        "Weißpunkt (xy)" to "White point (xy)",
        "AsShotNeutral" to "AsShotNeutral",
        "Bildrate" to "Frame rate",
        "Datenrate" to "Bitrate",
        "Laufzeit" to "Duration",
        "Tonspur" to "Audio track",
        "Codec" to "Codec",

        // ---- RAW / DNG -------------------------------------------------------
        "DNG-Version" to "DNG version",
        "DNG-Rückwärtsversion" to "DNG backward version",
        "Kamera-Kennung (DNG)" to "Camera ID (DNG)",
        "Farbmatrix" to "Color matrix",
        "Kalibrier-Lichtart" to "Calibration illuminant",
        "Eingebettete Vorschau" to "Embedded preview",
        "Weißpegel" to "White level",
        "CFA-Muster (Bayer)" to "CFA pattern (Bayer)",
        "Aktiver Sensorbereich" to "Active sensor area",
        "Bildfelder" to "Image fields",

        // ---- GPS -------------------------------------------------------------
        "Breitengrad" to "Latitude",
        "Längengrad" to "Longitude",
        "Höhe" to "Altitude",
        "Genauigkeit" to "Accuracy",
        "GPS-Zeit" to "GPS time",
        "Richtung" to "Direction",

        // ---- XMP / Notizen ---------------------------------------------------
        "Notiz / Bildbeschreibung" to "Note / image description",
        "Tags / Keywords" to "Tags / keywords",
        "XMP-Beschreibung" to "XMP description",
        "XMP-Themen (Tags)" to "XMP subjects (tags)",
        "XMP-Urheber" to "XMP creator",
        "XMP erstellt" to "XMP created",
        "XMP geändert" to "XMP modified",
        "XMP-Ort" to "XMP location",
        "Bewertung (XMP)" to "Rating (XMP)",
        "Favorit" to "Favorite",
        "Schreiben in die Datei" to "Writing into the file",

        // ---- Werte (exakt) ---------------------------------------------------
        "Nicht ausgelöst" to "Not fired",
        "Ausgelöst" to "Fired",
        "unterdrückt" to "suppressed",
        "Bewölkt" to "Cloudy",
        "Glühlampenlicht" to "Tungsten light",
        "Tageslicht" to "Daylight",
        "Porträt" to "Portrait",
        "Porträtprogramm" to "Portrait mode",
        "Blendenpriorität (Av)" to "Aperture priority (Av)",
        "Zeitpriorität (Tv)" to "Shutter priority (Tv)",
        "Automatik" to "Auto",
        "Süd" to "South",
        "Nord" to "North",
        "Ost" to "East",
        "West" to "West",
        "Eingebettetes Kamerabild (größter JPEG-Block der Datei)" to
            "Embedded camera image (largest JPEG block in the file)",
        "Android-Medienindex" to "Android media index",
        "Eigener Ordner (SAF)" to "Custom folder (SAF)",
        "Verfügbar (libheif) – dekodiert gekachelte Container, 10-Bit & HDR" to
            "Available (libheif) – decodes tiled containers, 10-bit & HDR",
        "Nicht verfügbar – es greift der System-Decoder" to
            "Not available – the system decoder is used instead",
        "Ja – Container aus mehreren Kacheln (typisch Apple-Screenshot & iPad)" to
            "Yes – container made of multiple tiles (typical Apple screenshot & iPad)",
        "Angezeigt aus dem JPEG-Bild im HEIF-Container" to
            "Shown from the JPEG image inside the HEIF container"
    )

    /** Werte, die aus Bausteinen zusammengesetzt werden – einfache Ersetzungen. */
    private val replacements: List<Pair<String, String>> = listOf(
        " Pixel" to " px",
        " Spalten" to " columns",
        " Zeilen" to " rows",
        " Kacheln" to " tiles",
        " Kachel" to " tile",
        " Dateien" to " files",
        "Dateien" to "files",
        " Bit pro Kanal" to " bits per channel",
        "Kachel-Raster" to "Tile grid",
        "Nein – " to "No – ",
        "Ja – " to "Yes – ",
        "Nicht verfügbar" to "Not available",
        "Verfügbar (libheif)" to "Available (libheif)",
        "lässt sich von Android nicht beschreiben" to "cannot be written by Android",
        "Notiz bleibt sicher in der App" to "the note stays safe in the app",
        "tagsüber XMP-Sidecar exportierbar" to "an XMP sidecar can be exported at any time",
        "Ausgabe" to "output",
        "typisch Apple-Screenshot" to "typical Apple screenshot",
        "Woche" to "week",
        "Tage" to "days",
        "Stunden" to "hours",
        "Minuten" to "minutes",
        "Sekunden" to "seconds"
    )

    /** Kleine Liste bekannter deutscher Format-/Technikwörter in Werten. */
    private val valueWords: List<Pair<String, String>> = listOf(
        "Unbekanntes Datum" to "Unknown date",
        "Unbekanntes Jahr" to "Unknown year",
        "Unbekannt" to "Unknown",
        "Dateisystem" to "file system",
        "Kodierung" to "Encoding",
        "Kacheln" to "tiles",
        "Breite" to "width",
        "Höhe" to "height",
        "Zeilenverlauf" to "scan order",
        "progressiv" to "progressive",
        "Interoperabilität" to "Interoperability"
    )

    /** true, wenn die App gerade auf Deutsch läuft. */
    fun isGerman(context: Context): Boolean {
        val tag = context.resources.configuration.locales.get(0)?.language ?: Locale.getDefault().language
        return tag.startsWith("de", ignoreCase = true)
    }

    /** Übersetzt eine deutsche Beschriftung in die aktuelle Sprache (Deutsch bleibt unverändert). */
    fun tr(context: Context, german: String): String {
        if (german.isBlank() || isGerman(context)) return german
        map[german]?.let { return it }
        var out = german
        // zusammengesetzte Werte („8 × 6 Kacheln à 512 px“ o. Ä.)
        if (out.length < 200) {
            replacements.forEach { (de, en) -> out = out.replace(de, en) }
            if (out == german) {
                valueWords.forEach { (de, en) -> out = out.replace(de, en) }
            }
        }
        return out
    }
}
