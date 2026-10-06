package com.n3vibecode.gallery.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.n3vibecode.gallery.GalleryApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sicherung von **Notizen, Tags und Favoriten**.
 *
 * Diese drei Dinge liegen normalerweise nur in den App-Einstellungen – bei einer
 * Neuinstallation (nötig, wenn sich der Signaturschlüssel geändert hat) wären sie weg.
 * Deshalb schreibt die App sie zusätzlich als kleine JSON-Datei in
 * `Pictures/N3 Gallery/n3-sicherung.json` – dort überleben sie ein Deinstallieren.
 *
 * Beim Start holt die App die Sicherung automatisch zurück, wenn noch keine Notizen und
 * Favoriten vorhanden sind (frische Installation). Die Bilddateien selbst werden nie
 * verändert.
 */
object MetaBackup {

    private const val ALBUM = "N3 Gallery"
    private const val FILE_NAME = "n3-sicherung.json"
    private const val FOLDER = "n3backup"
    private const val MIME = "application/json"
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

    // ------------------------------------------------------------------ Sichern

    /** Nach einer Änderung an Notizen/Tags/Favoriten aufrufen – schreibt verzögert. */
    fun scheduleSave() {
        if (!savePending.compareAndSet(false, true)) return
        main.postDelayed({
            savePending.set(false)
            Thread({
                runCatching { save() }
            }, "n3-meta-backup").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
        }, 3000)
    }

    /** Beim Start aufrufen: schreibt die Sicherung, wenn es etwas zu sichern gibt. */
    fun saveIfAny(): Boolean {
        if (MetaStore.favorites().isEmpty() && MetaStore.noteCount() == 0 &&
            MetaStore.taggedUris().isEmpty()
        ) return false
        return save()
    }

    /** Sicherung sofort schreiben (z. B. vor dem Teilen/Beenden). */
    fun save(): Boolean {
        val ctx = GalleryApp.instance
        val json = buildJson(ctx) ?: return false
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        return if (Build.VERSION.SDK_INT >= 29) saveViaMediaStore(ctx, bytes)
        else saveViaFile(ctx, bytes)
    }

    private fun buildJson(ctx: Context): String? = try {
        val notes = JSONObject()
        // Alle Notizen einsammeln (URI -> Text)
        for (uri in allNoteUris()) {
            val text = MetaStore.note(uri)
            if (text.isNotBlank()) notes.put(uri, text)
        }
        val tags = JSONObject()
        for (uri in MetaStore.taggedUris()) {
            val list = MetaStore.tags(uri)
            if (list.isNotEmpty()) tags.put(uri, JSONArray(list))
        }
        val favs = JSONArray()
        MetaStore.favorites().forEach { favs.put(it) }

        JSONObject().apply {
            put("version", 1)
            put("app", ctx.packageName)
            put("time", System.currentTimeMillis())
            put("favorites", favs)
            put("notes", notes)
            put("tags", tags)
        }.toString()
    } catch (_: Throwable) {
        null
    }

    private fun allNoteUris(): Set<String> {
        // Die Notizen liegen in den Preferences; MetaStore gibt sie nicht als Liste heraus,
        // deshalb hierüber die bekannten URIs zusammenziehen.
        val out = HashSet<String>()
        MetaStore.favorites().forEach { out.add(it) }
        MetaStore.taggedUris().forEach { out.add(it) }
        // Alle Fotos der Bibliothek durchsehen: Notizen können auch ohne Favorit/Tag existieren
        DataHub.all.forEach { if (MetaStore.hasNote(it.uri)) out.add(it.uri) }
        return out
    }

    /**
     * Ablage über MediaStore – im **Downloads**-Bereich, nicht in der Bilder-Galerie:
     * eine JSON-Datei würde dort als kaputtes Bild erscheinen (und die Galerie-Sammlung
     * nimmt sie auf vielen Geräten gar nicht erst an).
     */
    private fun saveViaMediaStore(ctx: Context, bytes: ByteArray): Boolean = try {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Vorhandene Sicherung überschreiben, sonst sammelten sich Kopien an
        val existing = queryBackupUri(ctx)
        if (existing != null) {
            ctx.contentResolver.openOutputStream(existing, "wt")?.use { it.write(bytes); it.flush() }
            true
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(collection, values) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes); it.flush() }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun queryBackupUri(ctx: Context): Uri? = try {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        ctx.contentResolver.query(collection, projection, selection, arrayOf(FILE_NAME), null)?.use { c ->
            if (c.moveToFirst()) {
                Uri.withAppendedPath(collection, c.getLong(0).toString())
            } else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun saveViaFile(ctx: Context, bytes: ByteArray): Boolean = try {
        val dir = File(ctx.cacheDir, FOLDER).apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        file.writeBytes(bytes)
        // Zusätzlich versuchen, in den öffentlichen Bilder-Ordner zu schreiben
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val publicDir = File(pictures, ALBUM)
        if (publicDir.exists() || publicDir.mkdirs()) {
            runCatching { File(publicDir, FILE_NAME).writeBytes(bytes) }
        }
        file.exists()
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------ Zurückholen

    /** Beim App-Start: Sicherung einlesen, wenn die App noch keine eigenen Daten hat. */
    fun restoreIfEmpty() {
        try {
            if (MetaStore.favorites().isNotEmpty() || MetaStore.noteCount() > 0) return
            val raw = readBackup() ?: return
            applyJson(raw)
        } catch (_: Throwable) {
        }
    }

    private fun readBackup(): String? = try {
        val ctx = GalleryApp.instance
        val viaStore = if (Build.VERSION.SDK_INT >= 29) {
            queryBackupUri(ctx)?.let { uri ->
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } else null
        if (viaStore != null) viaStore
        else {
            val candidates = mutableListOf<File>()
            candidates += File(GalleryApp.instance.cacheDir, "$FOLDER/$FILE_NAME")
            @Suppress("DEPRECATION")
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            candidates += File(File(pictures, ALBUM), FILE_NAME)
            candidates.firstOrNull { it.exists() && it.length() > 0 }?.readText(Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    private fun applyJson(raw: String): Int {
        val root = JSONObject(raw)
        var count = 0
        root.optJSONArray("favorites")?.let { favs ->
            for (i in 0 until favs.length()) {
                val uri = favs.optString(i)
                if (uri.isNotBlank() && !MetaStore.isFavorite(uri)) {
                    MetaStore.toggleFavorite(uri)
                    count++
                }
            }
        }
        root.optJSONObject("notes")?.let { notes ->
            notes.keys().forEach { uri ->
                val text = notes.optString(uri)
                if (text.isNotBlank() && MetaStore.note(uri).isBlank()) {
                    MetaStore.setNote(uri, text)
                    count++
                }
            }
        }
        root.optJSONObject("tags")?.let { tags ->
            tags.keys().forEach { uri ->
                val arr = tags.optJSONArray(uri) ?: return@forEach
                val list = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                if (list.isNotEmpty() && MetaStore.tags(uri).isEmpty()) {
                    MetaStore.setTags(uri, list)
                    count++
                }
            }
        }
        return count
    }
}
