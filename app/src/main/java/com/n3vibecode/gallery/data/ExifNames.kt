package com.n3vibecode.gallery.data

/**
 * **EXIF-Feldnamen zum Nachschlagen** (1.41).
 *
 * Ziel war „alle Metadaten, auf dem Niveau von Metapho/Metadata2Go“. Diese Datei deckt die
 * Felder ab, die dort üblicherweise stehen – auch die, die `androidx.exifinterface` nicht
 * als Konstante kennt (z. B. Drohnen- und Apple-spezifische Felder).
 *
 * Die Namen müssen **exakt** den Schlüsseln entsprechen, die `ExifInterface.getAttribute()`
 * verwendet (also `Tag_HEIC_*`, `Tag_*`, rohe TIFF-Namen). Deshalb steht hier auch je
 * Eintrag eine deutsche Bezeichnung daneben.
 *
 * Geprüft von `ExifNamesTest`: jeder Name muss im Namensschema von ExifInterface liegen
 * (kein Tippfehler wie „TAG_ISO“ oder ein fehlendes `Tag_`), und die Liste darf keine
 * Dubletten enthalten.
 */
object ExifNames {

    /** Alle Felder mit deutscher Bezeichnung, in der Reihenfolge der Anzeige. */
    val ALL: List<Pair<String, String>> = listOf(
        // ---------------- Bild / Grunddaten
        "ImageWidth" to "Bildbreite",
        "ImageLength" to "Bildhöhe",
        "BitsPerSample" to "Bit-Tiefe",
        "Compression" to "Kompression",
        "PhotometricInterpretation" to "Farbmodell",
        "Orientation" to "Ausrichtung",
        "SamplesPerPixel" to "Kanäle",
        "PlanarConfiguration" to "Ebenen-Konfiguration",
        "YCbCrPositioning" to "YCbCr-Position",
        "XResolution" to "X-Auflösung",
        "YResolution" to "Y-Auflösung",
        "ResolutionUnit" to "Auflösungseinheit",
        "TransferFunction" to "Übertragungsfunktion",
        "WhitePoint" to "Weißpunkt",
        "PrimaryChromaticities" to "Primärfarben",
        "YCbCrCoefficients" to "YCbCr-Koeffizienten",
        "ReferenceBlackWhite" to "Schwarz/Weiß-Referenz",
        "ExifVersion" to "EXIF-Version",
        "FlashpixVersion" to "Flashpix-Version",
        "ColorSpace" to "Farbraum",
        "PixelXDimension" to "Pixelbreite",
        "PixelYDimension" to "Pixelhöhe",
        "ComponentsConfiguration" to "Komponenten-Konfiguration",
        "CompressedBitsPerPixel" to "Komprimierte Bit pro Pixel",
        "MakerNote" to "Hersteller-Notiz (MakerNote)",
        "UserComment" to "Benutzerkommentar",
        "RelatedSoundFile" to "Zugehörige Tondatei",
        "DateTimeOriginal" to "Aufnahmezeitpunkt (Original)",
        "DateTimeDigitized" to "Digitalisiert am",
        "SubSecTime" to "Subsekunden",
        "SubSecTimeOriginal" to "Subsekunden (Original)",
        "SubSecTimeDigitized" to "Subsekunden (digitalisiert)",
        "OffsetTime" to "Zeitzone",
        "OffsetTimeOriginal" to "Zeitzone (Original)",
        "OffsetTimeDigitized" to "Zeitzone (digitalisiert)",
        "ExposureTime" to "Belichtungszeit",
        "FNumber" to "Blende (F-Nummer)",
        "ExposureProgram" to "Belichtungsprogramm",
        "SpectralSensitivity" to "Spektrale Empfindlichkeit",
        "PhotographicSensitivity" to "ISO-Empfindlichkeit",
        "ISOSpeedRatings" to "ISO (alt)",
        "SensitivityType" to "Empfindlichkeitstyp",
        "StandardOutputSensitivity" to "Standard-Ausgabeempfindlichkeit",
        "RecommendedExposureIndex" to "Empfohlener Belichtungsindex",
        "ShutterSpeedValue" to "Verschlusszeit (APEX)",
        "ApertureValue" to "Blendenwert (APEX)",
        "BrightnessValue" to "Helligkeit",
        "ExposureBiasValue" to "Belichtungskorrektur",
        "MaxApertureValue" to "Größte Blende",
        "SubjectDistance" to "Motivabstand",
        "MeteringMode" to "Messmethode",
        "LightSource" to "Lichtquelle",
        "Flash" to "Blitz",
        "FocalLength" to "Brennweite",
        "SubjectArea" to "Motivbereich",
        "FlashEnergy" to "Blitzenergie",
        "FocalPlaneXResolution" to "Sensorauflösung X",
        "FocalPlaneYResolution" to "Sensorauflösung Y",
        "FocalPlaneResolutionUnit" to "Sensorauflösungseinheit",
        "SubjectLocation" to "Motivposition",
        "ExposureIndex" to "Belichtungsindex",
        "SensingMethod" to "Sensortyp",
        "FileSource" to "Dateiquelle",
        "SceneType" to "Szenentyp",
        "CustomRendered" to "Eigene Verarbeitung",
        "ExposureMode" to "Belichtungsmodus",
        "WhiteBalance" to "Weißabgleich",
        "DigitalZoomRatio" to "Digitalzoom",
        "FocalLengthIn35mmFilm" to "Brennweite (Kleinbild)",
        "SceneCaptureType" to "Motivprogramm",
        "GainControl" to "Verstärkung",
        "Contrast" to "Kontrast",
        "Saturation" to "Sättigung",
        "Sharpness" to "Schärfe",
        "DeviceSettingDescription" to "Geräte-Einstellungen",
        "SubjectDistanceRange" to "Motivabstand-Bereich",
        "ImageUniqueID" to "Bild-Kennung",
        "CameraOwnerName" to "Kamera-Besitzer",
        "BodySerialNumber" to "Kamera-Seriennummer",
        "LensSpecification" to "Objektiv-Spezifikation",
        "LensMake" to "Objektiv-Hersteller",
        "LensModel" to "Objektiv",
        "LensSerialNumber" to "Objektiv-Seriennummer",
        "Gamma" to "Gamma",

        // ---------------- Kamera / Aufnahme (TIFF)
        "Make" to "Hersteller",
        "Model" to "Modell",
        "Software" to "Software",
        "Artist" to "Fotograf",
        "Copyright" to "Copyright",
        "DateTime" to "Datum/Uhrzeit (Datei)",
        "ImageDescription" to "Bildbeschreibung",
        "DocumentName" to "Dokumentname",
        "HostComputer" to "Computer",
        "ProcessingSoftware" to "Verarbeitungssoftware",

        // ---------------- Apple / HEIF (ExifInterface-Konstanten)
        "HEIC_ImageWidth" to "HEIF-Breite",
        "HEIC_ImageLength" to "HEIF-Höhe",
        "HEIC_ExifVersion" to "HEIF-EXIF-Version",
        "HEIC_Rotation" to "HEIF-Drehung",
        "HEIC_Make" to "HEIF-Hersteller",
        "HEIC_Model" to "HEIF-Modell",
        "HEIC_Software" to "HEIF-Software",
        "HEIC_DateTime" to "HEIF-Datum",
        "HEIC_MaxApertureValue" to "HEIF-größte Blende",
        "HEIC_FNumber" to "HEIF-Blende",
        "HEIC_FocalLength" to "HEIF-Brennweite",
        "HEIC_ISO" to "HEIF-ISO",
        "HEIC_ExposureTime" to "HEIF-Belichtungszeit",
        "HEIC_ShutterSpeedValue" to "HEIF-Verschlusszeit",
        "HEIC_ApertureValue" to "HEIF-Blendenwert",
        "HEIC_Flash" to "HEIF-Blitz",
        "HEIC_WhiteBalance" to "HEIF-Weißabgleich",
        "HEIC_FocalLengthIn35mmFilm" to "HEIF-Kleinbildbrennweite",
        "HEIC_ColorSpace" to "HEIF-Farbraum",
        "HEIC_LensModel" to "HEIF-Objektiv",
        "HEIC_LensMake" to "HEIF-Objektiv-Hersteller",
        "HEIC_LensSpecification" to "HEIF-Objektiv-Spezifikation",
        "HEIC_GPSLatitude" to "HEIF-Breitengrad",
        "HEIC_GPSLongitude" to "HEIF-Längengrad",
        "HEIC_GPSTimeStamp" to "HEIF-GPS-Zeit",
        "HEIC_GPSDateStamp" to "HEIF-GPS-Datum",
        "HEIC_GPSSpeed" to "HEIF-GPS-Geschwindigkeit",
        "HEIC_GPSImgDirection" to "HEIF-Blickrichtung",
        "HEIC_GPSTrack" to "HEIF-Fahrtrichtung",
        "HEIC_GPSProcessingMethod" to "HEIF-GPS-Methode",
        "HEIC_GPSAreaInformation" to "HEIF-GPS-Bereich",
        "HEIC_GPSAltitude" to "HEIF-Höhe",
        "HEIC_GPSAltitudeRef" to "HEIF-Höhenbezug",
        "HEIC_GPSHPositioningError" to "HEIF-GPS-Genauigkeit",

        // ---------------- GPS (vollständig)
        "GPSVersionID" to "GPS-Version",
        "GPSLatitudeRef" to "Breitengrad-Referenz",
        "GPSLatitude" to "Breitengrad",
        "GPSLongitudeRef" to "Längengrad-Referenz",
        "GPSLongitude" to "Längengrad",
        "GPSAltitudeRef" to "Höhen-Referenz",
        "GPSAltitude" to "Höhe über Normalnull",
        "GPSTimeStamp" to "GPS-Zeit (UTC)",
        "GPSSatellites" to "GPS-Satelliten",
        "GPSStatus" to "GPS-Status",
        "GPSMeasureMode" to "GPS-Messmodus",
        "GPSDOP" to "GPS-Genauigkeit (DOP)",
        "GPSSpeedRef" to "Geschwindigkeitseinheit",
        "GPSSpeed" to "GPS-Geschwindigkeit",
        "GPSTrackRef" to "Fahrtrichtungs-Referenz",
        "GPSTrack" to "Fahrtrichtung",
        "GPSImgDirectionRef" to "Blickrichtungs-Referenz",
        "GPSImgDirection" to "Blickrichtung",
        "GPSMapDatum" to "Kartendatum",
        "GPSDestLatitudeRef" to "Ziel-Breitengrad-Referenz",
        "GPSDestLatitude" to "Ziel-Breitengrad",
        "GPSDestLongitudeRef" to "Ziel-Längengrad-Referenz",
        "GPSDestLongitude" to "Ziel-Längengrad",
        "GPSDestBearingRef" to "Ziel-Peilungs-Referenz",
        "GPSDestBearing" to "Ziel-Peilung",
        "GPSDestDistanceRef" to "Ziel-Entfernungseinheit",
        "GPSDestDistance" to "Ziel-Entfernung",
        "GPSProcessingMethod" to "GPS-Methode",
        "GPSAreaInformation" to "GPS-Bereichsinfo",
        "GPSDateStamp" to "GPS-Datum",
        "GPSDifferential" to "GPS-Differenzialkorrektur",
        "GPSHPositioningError" to "GPS-Genauigkeit (horizontal)",

        // ---------------- Apple MakerNote, in Klartext extrahiert
        "Tag_Apple_RunTime" to "Apple: Laufzeit (RunTime)",
        "Tag_Apple_AccelerationVector" to "Apple: Beschleunigungssensor",
        "Tag_Apple_HDRHeadroom" to "Apple: HDR-Spielraum",
        "Tag_Apple_ImageCaptureType" to "Apple: Aufnahmeart",
        "Tag_Apple_ImageUniqueID" to "Apple: Bild-Kennung",
        "Tag_Apple_LensType" to "Apple: Objektiv-Typ",
        "Tag_Apple_Quality" to "Apple: Qualität",
        "Tag_Apple_SemanticStyle" to "Apple: Bildstil",
        "Tag_Apple_SignalToNoiseRatio" to "Apple: Signal-Rausch-Abstand",
        "Tag_Apple_PhotoIdentifier" to "Apple: Foto-Kennung"
    )

    /** Nachschlagen: roher Tag-Name → deutsche Bezeichnung (null, wenn unbekannt). */
    fun label(tag: String): String? = ALL.firstOrNull { it.first == tag }?.second

    /** Gruppierung für die Anzeige: macht aus dem Roh-Namen ein lesbares Kapitel. */
    fun group(tag: String): String = when {
        tag.startsWith("GPS") || tag.startsWith("HEIC_GPS") -> "Ort (GPS)"
        tag.startsWith("HEIC_") -> "HEIF-Container"
        tag.startsWith("Tag_Apple_") -> "Apple-Aufnahmedaten"
        else -> "Alle EXIF-Felder"
    }
}
