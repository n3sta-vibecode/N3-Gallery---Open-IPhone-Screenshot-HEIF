package com.n3vibecode.gallery.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Liest die Mediathek: Hauptquelle ist der Android-Medienindex (MediaStore, Bilder + Videos),
 * dazu alle vom Nutzer freigegebenen Ordner (SAF, u. a. für RAW-Formate) sowie Dateien,
 * die über „Öffnen mit → N3 Photos“ hereinkommen.
 *
 * Wichtig: Unbekannte/exotische Formate werden NICHT weggefiltert – die Erkennung läuft über die
 * Dateiendung, damit DNG, CR3, HEIC, AVIF, HEVC & Co. zuverlässig auftauchen.
 */
class Repository(private val ctx: Context) {

    private val folderStore = FolderStore(ctx)

    /**
     * Schneller erster Durchgang: nur der Android-Medienindex.
     * Damit ist die Galerie sofort gefüllt, während eigene Ordner (SAF) noch gelesen werden.
     */
    suspend fun loadMediaIndex(): Library = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, MediaItem>()
        runCatching { mediaStoreItems() }.getOrDefault(emptyList()).forEach { out[it.key] = it }
        runCatching { mediaStoreFilesLikeImages() }.getOrDefault(emptyList()).forEach { out.putIfAbsent(it.key, it) }
        Library(sort(out), false)
    }

    suspend fun loadAll(): Library = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, MediaItem>()
        val limited = false

        // 1) MediaStore – Bilder und Videos
        runCatching { mediaStoreItems() }.getOrDefault(emptyList()).forEach { out[it.key] = it }

        // 2) Zusätzlich: Dateien-Tabelle mit Bild-MIME-Typen (fängt RAW/HEIC ab, die als "file" indexiert sind)
        runCatching { mediaStoreFilesLikeImages() }.getOrDefault(emptyList()).forEach { out.putIfAbsent(it.key, it) }

        // 3) SAF-Ordner
        runCatching { folderStore.scan() }.getOrDefault(emptyList()).forEach { out[it.key] = it }

        // 4) App-eigener Export-Ordner
        runCatching { appExportItems() }.getOrDefault(emptyList()).forEach { out[it.key] = it }

        Library(sort(out), limited)
    }

    private fun sort(out: LinkedHashMap<String, MediaItem>): List<MediaItem> =
        out.values.sortedWith(
            compareByDescending<MediaItem> { it.time }.thenByDescending { it.size }
        )

    // ------------------------------------------------------------------ MediaStore

    private fun mediaStoreItems(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DURATION
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        ctx.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                readRow(c, collection)?.let { out += it }
            }
        }
        return out
    }

    /** Zusätzliche Suche über MIME-Präfixe – fängt Dateien auf, die nicht als „image“ indexiert sind. */
    private fun mediaStoreFilesLikeImages(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DURATION
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=0 OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} IS NULL) AND " +
                "(${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?)"
        val args = arrayOf("image/%", "video/%", "application/x-adobe-dng")
        runCatching {
            ctx.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    readRow(c, collection)?.let { out += it }
                }
            }
        }
        return out
    }

    private fun readRow(c: Cursor, collection: Uri): MediaItem? {
        val id = c.getLong(0)
        val name = c.getString(1) ?: return null
        val mime = c.getString(2) ?: ""
        val size = c.getLong(3)
        val w = c.getInt(4)
        val h = c.getInt(5)
        val dateAdded = c.getLong(6) * 1000L
        val dateModified = c.getLong(7) * 1000L
        val dateTaken = if (c.isNull(8)) 0L else c.getLong(8)
        val bucketName = c.getString(10) ?: "Unbekannt"
        val relPath = if (c.isNull(11)) "" else c.getString(11) ?: ""
        val mediaType = c.getInt(12)
        val uri = ContentUris.withAppendedId(collection, id)
        val ext = Formats.extOf(name)
        // Dateien ohne Bild-/Video-Kennzeichnung und ohne passende Endung überspringen
        if (mediaType == 0 && !Formats.ALL.contains(ext)) return null
        return MediaItem(
            key = uri.toString(),
            uri = uri.toString(),
            name = name,
            mime = mime,
            ext = ext,
            kind = when {
                mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || Formats.isVideo(ext) -> MediaKind.VIDEO
                mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || mime.startsWith("image/") -> MediaKind.PHOTO
                else -> MediaKind.PHOTO
            },
            size = size,
            width = w,
            height = h,
            takenAt = dateTaken,
            modifiedAt = if (dateModified > 0) dateModified else dateAdded,
            bucket = bucketName,
            path = relPath,
            isSaf = false,
            durationMs = if (c.isNull(13)) 0L else c.getLong(13)
        )
    }

    // ------------------------------------------------------------------ App-Export

    private fun appExportItems(): List<MediaItem> {
        val dir = com.n3vibecode.gallery.GalleryApp.exportDir()
        val out = mutableListOf<MediaItem>()
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val ext = Formats.extOf(f.name)
            if (!Formats.ALL.contains(ext)) return@forEach
            val uri = Uri.fromFile(f)
            out += MediaItem(
                key = uri.toString(),
                uri = uri.toString(),
                name = f.name,
                mime = Formats.guessMime(ext),
                ext = ext,
                kind = if (Formats.isVideo(ext)) MediaKind.VIDEO else MediaKind.PHOTO,
                size = f.length(),
                width = 0,
                height = 0,
                takenAt = f.lastModified(),
                modifiedAt = f.lastModified(),
                bucket = "N3 Gallery Export",
                path = f.absolutePath,
                isSaf = false
            )
        }
        return out
    }

    // ------------------------------------------------------------------ Hilfen

    /** Alle Ordner (Buckets) mit Anzahl – für den Ordner-Tab. */
    fun bucketsOf(items: List<MediaItem>): List<Pair<String, List<MediaItem>>> =
        items.groupBy { it.bucket }.toList().sortedByDescending { it.second.size }

    fun appDownloadGuess(): File? = File("/storage/emulated/0/Download")

    companion object {
        fun sdk(): Int = Build.VERSION.SDK_INT
    }
}
