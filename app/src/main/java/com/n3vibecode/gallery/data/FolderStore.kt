package com.n3vibecode.gallery.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Verwaltet vom Nutzer per SAF hinzugefügte Ordner (praktisch für RAW/HEIC aus dem Download-Ordner,
 * von SD-Karten oder aus Cloud-Apps, die MediaStore nicht indexiert).
 */
class FolderStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("n3_gallery_folders", Context.MODE_PRIVATE)
    private val key = "tree_uris"

    fun treeUris(): List<String> = prefs.getStringSet(key, emptySet())?.toList().orEmpty()

    fun add(uri: Uri) {
        val set = treeUris().toMutableSet()
        set.add(uri.toString())
        prefs.edit().putStringSet(key, set).apply()
        // Lesezugriff dauerhaft sichern
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    fun remove(uriString: String) {
        val set = treeUris().toMutableSet()
        set.remove(uriString)
        prefs.edit().putStringSet(key, set).apply()
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    fun displayName(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.substringAfterLast(':').ifEmpty { docId }
        } catch (_: Exception) {
            Uri.decode(uriString).substringAfterLast('/')
        }
    }

    /** Alle Dateien in allen Ordnern, die zu unseren unterstützten Formaten gehören. */
    fun scan(onProgress: (String) -> Unit = {}): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (uriString in treeUris()) {
            try {
                val root = DocumentFile.fromTreeUri(ctx, Uri.parse(uriString)) ?: continue
                onProgress(root.name.orEmpty())
                walk(root, out)
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun walk(dir: DocumentFile, out: MutableList<MediaItem>) {
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            emptyArray()
        }
        for (f in children) {
            try {
                if (f.isDirectory) {
                    walk(f, out)
                } else {
                    val name = f.name ?: continue
                    val ext = Formats.extOf(name)
                    if (ext.isEmpty() || !Formats.ALL.contains(ext)) continue
                    val mime = f.type ?: Formats.guessMime(ext)
                    out += MediaItem(
                        key = f.uri.toString(),
                        uri = f.uri.toString(),
                        name = name,
                        mime = mime,
                        ext = ext,
                        kind = if (Formats.isVideo(ext)) MediaKind.VIDEO else MediaKind.PHOTO,
                        size = f.length(),
                        width = 0,
                        height = 0,
                        takenAt = f.lastModified(),
                        modifiedAt = f.lastModified(),
                        bucket = dir.name.orEmpty(),
                        path = Uri.decode(f.uri.toString()),
                        isSaf = true
                    )
                }
            } catch (_: Exception) {
            }
        }
    }
}
