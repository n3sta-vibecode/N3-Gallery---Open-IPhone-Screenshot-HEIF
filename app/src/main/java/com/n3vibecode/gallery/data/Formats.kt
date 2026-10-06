package com.n3vibecode.gallery.data

import java.util.Locale

/**
 * Formaterkennung. Deckt alle gängigen Foto-/Video-Container ab:
 * JPEG, PNG, WebP, GIF, BMP, TIFF, HEIC/HEIF (Apple & Android), AVIF, DNG und praktisch alle
 * Kamera-RAWs (CR2/CR3, NEF, ARW, ORF, RW2, RAF, PEF, SRW, RWL, 3FR, IIQ, X3F …) sowie HEVC/H.265-Video.
 */
object Formats {

    val RAW = setOf(
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "ori",
        "rw2", "raw", "rwl", "raf", "pef", "ptx", "srw", "3fr", "fff", "iiq", "cap",
        "x3f", "mrw", "erf", "kdc", "dcr", "mos", "mef", "x3i", "braw", "r3d", "gpr", "cine", "ari"
    )

    val HEIF = setOf("heic", "heif", "hif", "heics", "heifs", "avci", "heicm")

    val AVIF = setOf("avif", "avifs", "aves")

    val SIMPLE = setOf("jpg", "jpeg", "jpe", "jfif", "png", "apng", "webp", "gif", "bmp", "tif", "tiff", "ico", "dib")

    val VIDEO = setOf("mp4", "m4v", "mov", "qt", "hevc", "h265", "265", "h264", "mkv", "webm", "avi", "wmv", "3gp", "3g2", "mts", "m2ts", "mpg", "mpeg", "ts")

    /** Vektorgrafik: wird nicht „dekodiert“, sondern in der gewünschten Größe gezeichnet. */
    val VECTOR = setOf("svg", "svgz")

    val ALL: Set<String> = RAW + HEIF + AVIF + SIMPLE + VIDEO + VECTOR

    fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.length - 1) return ""
        return name.substring(i + 1).lowercase(Locale.ROOT)
    }

    fun isRaw(ext: String) = RAW.contains(ext)
    fun isHeif(ext: String) = HEIF.contains(ext)
    fun isAvif(ext: String) = AVIF.contains(ext)
    fun isVideo(ext: String) = VIDEO.contains(ext)
    fun isSimple(ext: String) = SIMPLE.contains(ext)
    fun isVector(ext: String) = VECTOR.contains(ext)

    /** Apple-Screenshots & Co. liegen als HEIF/HEIC mit Kachel-Container vor. */
    fun looksApple(name: String, ext: String): Boolean {
        val n = name.uppercase(Locale.ROOT)
        return isHeif(ext) && (n.startsWith("IMG_") || n.startsWith("IMG-") || n.startsWith("RPReplay") || n.startsWith("BILD") || n.contains("SCREEN") || n.contains("HEIC"))
    }

    /** Anzeigename für die Ecke des Vorschaubildes, z. B. "JPG", "HEIC", "DNG", "CR3", "AVIF". */
    fun label(ext: String, mime: String): String {
        if (ext.isNotEmpty()) {
            return when (ext) {
                "jpeg", "jpe", "jfif" -> "JPG"
                "heif", "hif" -> "HEIF"
                "tiff" -> "TIF"
                "apng" -> "PNG"
                "svg", "svgz" -> "SVG"
                "h265", "265" -> "H.265"
                "h264" -> "H.264"
                else -> ext.uppercase(Locale.ROOT)
            }
        }
        val m = mime.lowercase(Locale.ROOT)
        return when {
            m.contains("heic") || m.contains("heif") -> "HEIF"
            m.contains("avif") -> "AVIF"
            m.contains("dng") -> "DNG"
            m.contains("jpeg") -> "JPG"
            m.contains("png") -> "PNG"
            m.contains("webp") -> "WEBP"
            m.contains("gif") -> "GIF"
            m.startsWith("image/") -> "IMG"
            m.startsWith("video/") -> "VIDEO"
            else -> "DATEI"
        }
    }

    /** Gruppen für den Tab „Formate“ – Apple-/Google-ähnliche Sortierung. */
    fun family(ext: String, mime: String): String = when {
        isRaw(ext) -> "RAW (Kamera-Rohdaten)"
        isHeif(ext) -> "HEIC / HEIF (Apple & Android)"
        isAvif(ext) -> "AVIF"
        isVector(ext) -> "SVG (Vektorgrafik)"
        ext == "jpg" || ext == "jpeg" || ext == "jpe" || ext == "jfif" -> "JPEG"
        ext == "png" -> "PNG"
        ext == "webp" -> "WebP"
        ext == "gif" -> "GIF"
        ext == "bmp" -> "BMP"
        ext == "tif" || ext == "tiff" -> "TIFF"
        isVideo(ext) || mime.startsWith("video/") -> "Videos (HEVC/H.264 …)"
        ext.isNotEmpty() -> ext.uppercase(Locale.ROOT)
        mime.startsWith("image/") -> "Bilder (sonstige)"
        else -> "Sonstige Dateien"
    }

    fun guessMime(ext: String): String = when (ext) {
        "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
        "png", "apng" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp", "dib" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        "heic", "heics" -> "image/heic"
        "heif", "hif", "heifs" -> "image/heif"
        "avif", "avifs" -> "image/avif"
        "svg", "svgz" -> "image/svg+xml"
        "dng" -> "image/x-adobe-dng"
        "cr2", "cr3" -> "image/x-canon-cr$ext"
        "nef", "nrw" -> "image/x-nikon-nef"
        "arw", "sr2", "srf" -> "image/x-sony-arw"
        "orf", "ori" -> "image/x-olympus-orf"
        "rw2", "raw", "rwl" -> "image/x-panasonic-rw2"
        "raf" -> "image/x-fuji-raf"
        "pef", "ptx" -> "image/x-pentax-pef"
        "srw" -> "image/x-samsung-srw"
        "3fr", "fff" -> "image/x-hasselblad-3fr"
        "x3f" -> "image/x-sigma-x3f"
        "mp4", "m4v" -> "video/mp4"
        "mov", "qt" -> "video/quicktime"
        "hevc", "h265", "265" -> "video/hevc"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "*/*"
    }

    /** Ist dieses Format auf dieser Android-Version dekodierbar? (Metadaten gehen immer.) */
    fun decodableNatively(ext: String, sdk: Int): Boolean = when {
        isVector(ext) -> true              // wird von der App selbst gezeichnet
        isSimple(ext) -> true
        isHeif(ext) -> true                 // Android 8+ (API 26) kann HEIC
        isRaw(ext) -> false                 // wird über eingebettete Vorschau gelöst
        isAvif(ext) -> sdk >= 31            // AVIF ab Android 12
        else -> isVideo(ext)
    }

    fun description(ext: String): String = when {
        isRaw(ext) -> "Kamera-Rohdaten (RAW)"
        isHeif(ext) -> "HEIF-Container, meist HEVC-codiert"
        isAvif(ext) -> "AVIF (AV1-codiert)"
        ext == "jpg" || ext == "jpeg" -> "JPEG, verlustbehaftet"
        ext == "png" -> "PNG, verlustfrei"
        isVector(ext) -> "SVG-Vektorgrafik – beliebig scharf skalierbar"
        else -> ""
    }
}
