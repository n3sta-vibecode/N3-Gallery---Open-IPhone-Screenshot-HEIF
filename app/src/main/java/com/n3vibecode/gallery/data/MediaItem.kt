package com.n3vibecode.gallery.data

import android.net.Uri
import com.n3vibecode.gallery.util.Fmt

enum class MediaKind { PHOTO, VIDEO, OTHER }

/**
 * Ein Medienelement – aus dem Android-Medienindex (MediaStore), aus einem per SAF
 * hinzugefügten Ordner oder aus dem App-Export.
 */
data class MediaItem(
    val key: String,            // eindeutig, i. d. R. die Content-URI
    val uri: String,
    val name: String,
    val mime: String,
    val ext: String,
    val kind: MediaKind,
    val size: Long,
    val width: Int,
    val height: Int,
    val takenAt: Long,          // Aufnahmezeit (ms), 0 wenn unbekannt
    val modifiedAt: Long,
    val bucket: String,
    val path: String,
    val isSaf: Boolean = false,
    val durationMs: Long = 0L
) {
    val format: String get() = Formats.label(ext, mime)
    val isRaw: Boolean get() = Formats.isRaw(ext)
    val isHeif: Boolean get() = Formats.isHeif(ext)
    val isAvif: Boolean get() = Formats.isAvif(ext)
    val isVector: Boolean get() = Formats.isVector(ext)
    val isVideoFile: Boolean get() = kind == MediaKind.VIDEO
    val time: Long get() = if (takenAt > 0) takenAt else modifiedAt
    val family: String get() = Formats.family(ext, mime)
    val isWriteLocked: Boolean get() = isRaw
    fun uriObj(): Uri = Uri.parse(uri)

    /** Kurzbeschreibung für Listen: "4032 × 3024 · 2,1 MB · DNG" */
    fun shortInfo(): String {
        val parts = mutableListOf<String>()
        if (width > 0 && height > 0) parts += "$width × $height"
        if (size > 0) parts += Fmt.bytes(size)
        parts += format
        return parts.joinToString(" · ")
    }
}

/** Ergebnis einer Ordner-/Medienabfrage inkl. Hinweisen (z. B. „noch keine Medien“). */
data class Library(val items: List<MediaItem>, val hiddenByUserSelection: Boolean = false)
