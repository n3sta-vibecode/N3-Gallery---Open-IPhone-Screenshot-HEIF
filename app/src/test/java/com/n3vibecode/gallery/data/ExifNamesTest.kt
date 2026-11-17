package com.n3vibecode.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft die beiden neuen Datenteile:
 *
 *  • [ExifNames]: die Feldliste muss zum Namensschema von `ExifInterface` passen
 *    (`Tag_*`, `HEIC_*` oder roher TIFF-Name), damit kein Feld still leer bleibt.
 *  • [AppleMakerNote]: Apple-Felder werden gelesen, wenn sie lesbar sind – und
 *    **nicht** erfunden, wenn nicht.
 */
class ExifNamesTest {

    @Test
    fun feldnamenFolgenDemExifInterfaceSchema() {
        val erlaubtePraefixe = listOf("Tag_", "HEIC_")
        val bekannteRoh = setOf(
            // TIFF/EXIF-Felder, die ExifInterface ohne Präfix kennt
            "ImageWidth", "ImageLength", "BitsPerSample", "Compression",
            "PhotometricInterpretation", "Orientation", "SamplesPerPixel",
            "PlanarConfiguration", "YCbCrPositioning", "XResolution", "YResolution",
            "ResolutionUnit", "TransferFunction", "WhitePoint", "PrimaryChromaticities",
            "YCbCrCoefficients", "ReferenceBlackWhite", "ExifVersion", "FlashpixVersion",
            "ColorSpace", "PixelXDimension", "PixelYDimension", "ComponentsConfiguration",
            "CompressedBitsPerPixel", "MakerNote", "UserComment", "RelatedSoundFile",
            "DateTimeOriginal", "DateTimeDigitized", "SubSecTime", "SubSecTimeOriginal",
            "SubSecTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            "ExposureTime", "FNumber", "ExposureProgram", "SpectralSensitivity",
            "PhotographicSensitivity", "ISOSpeedRatings", "SensitivityType",
            "StandardOutputSensitivity", "RecommendedExposureIndex", "ShutterSpeedValue",
            "ApertureValue", "BrightnessValue", "ExposureBiasValue", "MaxApertureValue",
            "SubjectDistance", "MeteringMode", "LightSource", "Flash", "FocalLength",
            "SubjectArea", "FlashEnergy", "FocalPlaneXResolution", "FocalPlaneYResolution",
            "FocalPlaneResolutionUnit", "SubjectLocation", "ExposureIndex", "SensingMethod",
            "FileSource", "SceneType", "CustomRendered", "ExposureMode", "WhiteBalance",
            "DigitalZoomRatio", "FocalLengthIn35mmFilm", "SceneCaptureType", "GainControl",
            "Contrast", "Saturation", "Sharpness", "DeviceSettingDescription",
            "SubjectDistanceRange", "ImageUniqueID", "CameraOwnerName", "BodySerialNumber",
            "LensSpecification", "LensMake", "LensModel", "LensSerialNumber", "Gamma",
            "Make", "Model", "Software", "Artist", "Copyright", "DateTime",
            "ImageDescription", "DocumentName", "HostComputer", "ProcessingSoftware",
            // GPS
            "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef",
            "GPSLongitude", "GPSAltitudeRef", "GPSAltitude", "GPSTimeStamp",
            "GPSSatellites", "GPSStatus", "GPSMeasureMode", "GPSDOP", "GPSSpeedRef",
            "GPSSpeed", "GPSTrackRef", "GPSTrack", "GPSImgDirectionRef",
            "GPSImgDirection", "GPSMapDatum", "GPSDestLatitudeRef", "GPSDestLatitude",
            "GPSDestLongitudeRef", "GPSDestLongitude", "GPSDestBearingRef",
            "GPSDestBearing", "GPSDestDistanceRef", "GPSDestDistance",
            "GPSProcessingMethod", "GPSAreaInformation", "GPSDateStamp",
            "GPSDifferential", "GPSHPositioningError"
        )
        val unbekannt = ExifNames.ALL.map { it.first }.filter { name ->
            erlaubtePraefixe.none { name.startsWith(it) } && name !in bekannteRoh
        }
        assertTrue("Unbekannte Feldnamen: $unbekannt", unbekannt.isEmpty())
    }

    @Test
    fun keineDublettenUndJedeZeileHatEinLabel() {
        val namen = ExifNames.ALL.map { it.first }
        assertEquals("Doppelte Feldnamen", namen.size, namen.toSet().size)
        ExifNames.ALL.forEach { (name, label) ->
            assertTrue("Label fehlt bei $name", label.isNotBlank())
        }
    }

    @Test
    fun gpsFelderSindVollstaendig() {
        // Diese vier müssen vorhanden sein, sonst gibt es keinen Ort
        listOf("GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef").forEach {
            assertNotNull("GPS-Feld fehlt: $it", ExifNames.label(it))
        }
    }

    @Test
    fun gruppenZuordnung() {
        assertEquals("Ort (GPS)", ExifNames.group("GPSLatitude"))
        assertEquals("Ort (GPS)", ExifNames.group("HEIC_GPSLatitude"))
        assertEquals("HEIF-Container", ExifNames.group("HEIC_ImageWidth"))
        assertEquals("Apple-Aufnahmedaten", ExifNames.group("Tag_Apple_RunTime"))
    }

    // ---------------------------------------------------------------- Apple MakerNote

    @Test
    fun appleBildstilWirdGelesen() {
        val text = "\u0000\u0001SemanticStyle\u0000\u0000Rich Contrast\u0000"
        assertEquals("Rich Contrast", AppleMakerNote.valueFor(text, "SemanticStyle"))
    }

    @Test
    fun appleZahlenfolgeWirdGelesen() {
        // So sieht ein Zahlenfeld im MakerNote aus: Schlüssel, Typ/Länge, dann die Werte
        val text = "AccelerationVector\u0000\u0005\u0000\u0000-0.0123 0.456 -1.002"
        val value = AppleMakerNote.valueFor(text, "AccelerationVector")
        assertNotNull("Zahlen erwartet, war: $value", value)
        assertTrue(value!!.any { it.isDigit() })
    }

    @Test
    fun textEndetAmNaechstenSchluessel() {
        // „LensType … Tele“ darf nicht „TeleQualityHighest“ ergeben
        val text = "LensType\u0000\u0000Tele\u0000Quality\u0000\u0000Highest\u0000"
        val value = AppleMakerNote.valueFor(text, "LensType")
        assertEquals("Tele", value)
    }

    @Test
    fun nichtVorhandeneFelderWerdenNichtErfunden() {
        val text = "irgendein anderer Inhalt ohne Apple-Felder"
        assertNull(AppleMakerNote.valueFor(text, "SemanticStyle"))
        assertTrue(AppleMakerNote.parse(text).isEmpty())
        assertEquals(0, AppleMakerNote.parse("").size)
    }

    @Test
    fun mehrereFelderWerdenGefunden() {
        val text = "SemanticStyle\u0000\u0000Rich Contrast\u0000LensType\u0000\u0000Tele\u0000" +
            "Quality\u0000\u0000Highest\u0000"
        val rows = AppleMakerNote.parse(text)
        assertTrue("mindestens zwei Felder erwartet, waren: $rows", rows.size >= 2)
        assertTrue(rows.any { it.second.contains("Rich Contrast") })
        assertTrue(rows.any { it.second.contains("Tele", true) })
    }
}
