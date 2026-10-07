package com.n3vibecode.gallery.data

import android.content.Context
import com.n3vibecode.gallery.GalleryApp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * App-interne Ablage für Notizen, Tags und Favoriten (SharedPreferences).
 * Notizen/Tags werden zusätzlich – wenn der Nutzer es möchte – als echte EXIF/XMP-Metadaten
 * in die Bilddatei geschrieben (siehe ExifWriter).
 *
 * **Performance:** Alles wird zusätzlich im Speicher gehalten (Favoriten-Set, Zuordnung
 * Foto → Notiz, Foto → Tags). Vorher hat die Galerie beim Bildaufbau für **jedes** Foto
 * erneut in die Preferences geschaut – beim Scrollen und beim Aktualisieren kostete das
 * bei großen Sammlungen spürbar Zeit. Die Caches werden bei Änderungen aktualisiert,
 * Aufrufer müssen also nichts tun.
 */
object MetaStore {

    private const val PREF = "n3_gallery_meta"
    private const val K_NOTE = "note:"
    private const val K_TAGS = "tags:"
    private const val K_FAVS = "favorites"
    private const val K_PENDING = "pending:"

    private val prefs by lazy {
        GalleryApp.instance.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    // ---------------------------------------------------------------- Speicher-Caches

    private val favCache = AtomicReference<Set<String>?>(null)
    private val noteCache = AtomicReference<Map<String, String>?>(null)
    private val tagCache = AtomicReference<Map<String, List<String>>?>(null)

    /** Caches vorab (im Hintergrund) aufbauen – z. B. direkt nach dem Einlesen der Mediathek. */
    fun warmUp() {
        favorites()
        notes()
        tagsMap()
    }

    private fun favoritesRaw(): Set<String> = prefs.getStringSet(K_FAVS, emptySet()) ?: emptySet()

    private fun loadNotes(): Map<String, String> {
        val out = ConcurrentHashMap<String, String>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(K_NOTE) && v is String && v.isNotBlank()) out[k.removePrefix(K_NOTE)] = v
        }
        return out
    }

    private fun loadTags(): Map<String, List<String>> {
        val out = ConcurrentHashMap<String, List<String>>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(K_TAGS) && v is String && v.isNotBlank()) {
                val list = parseTags(v)
                if (list.isNotEmpty()) out[k.removePrefix(K_TAGS)] = list
            }
        }
        return out
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun notes(): Map<String, String> {
        noteCache.get()?.let { return it }
        val loaded = loadNotes()
        noteCache.compareAndSet(null, loaded)
        return noteCache.get() ?: loaded
    }

    private fun tagsMap(): Map<String, List<String>> {
        tagCache.get()?.let { return it }
        val loaded = loadTags()
        tagCache.compareAndSet(null, loaded)
        return tagCache.get() ?: loaded
    }

    /** Favoriten-Index (einmal geladen, danach O(1)). */
    private fun favoritesIndex(): Set<String> {
        favCache.get()?.let { return it }
        val loaded = java.util.Collections.unmodifiableSet(HashSet(favoritesRaw()))
        favCache.compareAndSet(null, loaded)
        return favCache.get() ?: loaded
    }

    // ---------- Notizen ----------

    fun note(uri: String): String = notes()[uri].orEmpty()

    fun hasNote(uri: String): Boolean = notes().containsKey(uri)

    fun setNote(uri: String, text: String) {
        prefs.edit().putString(K_NOTE + uri, text).apply()
        @Suppress("UNCHECKED_CAST")
        val map = HashMap(notes() as Map<String, String>)
        if (text.isBlank()) map.remove(uri) else map[uri] = text
        noteCache.set(map)
        MetaBackup.scheduleSave()
    }

    // ---------- Tags ----------

    fun tags(uri: String): List<String> = tagsMap()[uri] ?: emptyList()

    fun setTags(uri: String, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString(K_TAGS + uri, clean.joinToString(",")).apply()
        val map = HashMap(tagsMap())
        if (clean.isEmpty()) map.remove(uri) else map[uri] = clean
        tagCache.set(map)
        MetaBackup.scheduleSave()
    }

    /** Alle Tag-Namen der Bibliothek (sortiert). */
    fun allTagNames(): List<String> {
        val names = sortedSetOf<String>()
        tagsMap().values.forEach { names += it }
        return names.toList()
    }

    /** Tag -> URIs (alphabetisch sortiert). */
    fun itemsForTag(tag: String): List<String> =
        tagsMap().entries
            .filter { (_, list) -> list.any { it.equals(tag, true) } }
            .map { it.key }
            .sorted()

    /** Alle Fotos, die überhaupt Tags haben (für Album-Ansichten). */
    fun taggedUris(): List<String> = tagsMap().keys.sorted()

    // ---------- Favoriten ----------

    fun isFavorite(uri: String): Boolean = favoritesIndex().contains(uri)

    fun favorites(): Set<String> = favoritesIndex()

    fun favoriteUris(): List<String> = favoritesIndex().toList()

    fun toggleFavorite(uri: String): Boolean {
        val cur = HashSet(favoritesRaw())
        val nowFav = if (cur.contains(uri)) {
            cur.remove(uri); false
        } else {
            cur.add(uri); true
        }
        prefs.edit().putStringSet(K_FAVS, cur).apply()
        favCache.set(java.util.Collections.unmodifiableSet(cur))
        MetaBackup.scheduleSave()
        return nowFav
    }

    /** Nach Änderungen von außen (z. B. Datei-Import) den Index neu aufbauen. */
    fun invalidate() {
        favCache.set(null)
        noteCache.set(null)
        tagCache.set(null)
    }

    // ---------- Offene Schreibvorgänge (falls Android eine Bestätigung verlangt) ----------

    fun rememberPending(uri: String, note: String, tags: List<String>) {
        val payload = note.replace('\u0001', ' ') + "\u0001" + tags.joinToString(",")
        prefs.edit().putString(K_PENDING + uri, payload).apply()
    }

    fun pendingFor(uri: String): Pair<String, List<String>>? {
        val raw = prefs.getString(K_PENDING + uri, null) ?: return null
        val parts = raw.split('\u0001')
        if (parts.size != 2) return null
        return parts[0] to parseTags(parts[1])
    }

    fun clearPending(uri: String) {
        prefs.edit().remove(K_PENDING + uri).apply()
    }

    fun pendingUris(): List<String> = prefs.all.keys.filter { it.startsWith(K_PENDING) }.map { it.removePrefix(K_PENDING) }

    // ---------- Statistik ----------

    fun noteCount(): Int = notes().size

    fun tagCount(): Int = allTagNames().size
}
