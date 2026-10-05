package com.n3vibecode.gallery.data

import android.content.Context
import com.n3vibecode.gallery.GalleryApp

/**
 * App-interne Ablage für Notizen, Tags und Favoriten (SharedPreferences).
 * Notizen/Tags werden zusätzlich – wenn der Nutzer es möchte – als echte EXIF/XMP-Metadaten
 * in die Bilddatei geschrieben (siehe ExifWriter).
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

    // ---------- Notizen ----------

    fun note(uri: String): String = prefs.getString(K_NOTE + uri, "").orEmpty()

    fun setNote(uri: String, text: String) {
        prefs.edit().putString(K_NOTE + uri, text).apply()
    }

    // ---------- Tags ----------

    fun tags(uri: String): List<String> =
        prefs.getString(K_TAGS + uri, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun setTags(uri: String, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString(K_TAGS + uri, clean.joinToString(",")).apply()
    }

    /** Alle Tag-Namen der Bibliothek (sortiert). */
    fun allTagNames(): List<String> {
        val names = sortedSetOf<String>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(K_TAGS) && v is String) {
                v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { names += it }
            }
        }
        return names.toList()
    }

    /** Tag -> URIs (alphabetisch sortiert). */
    fun itemsForTag(tag: String): List<String> {
        val out = mutableListOf<String>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(K_TAGS) && v is String) {
                val has = v.split(',').any { it.trim().equals(tag, true) }
                if (has) out += k.removePrefix(K_TAGS)
            }
        }
        return out.sorted()
    }

    // ---------- Favoriten ----------

    fun isFavorite(uri: String): Boolean = favorites().contains(uri)

    fun favorites(): Set<String> = prefs.getStringSet(K_FAVS, emptySet()) ?: emptySet()

    fun toggleFavorite(uri: String): Boolean {
        val cur = favorites().toMutableSet()
        val nowFav = if (cur.contains(uri)) {
            cur.remove(uri); false
        } else {
            cur.add(uri); true
        }
        prefs.edit().putStringSet(K_FAVS, cur).apply()
        return nowFav
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
        return parts[0] to parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun clearPending(uri: String) {
        prefs.edit().remove(K_PENDING + uri).apply()
    }

    fun pendingUris(): List<String> = prefs.all.keys.filter { it.startsWith(K_PENDING) }.map { it.removePrefix(K_PENDING) }

    // ---------- Statistik ----------

    fun noteCount(): Int = prefs.all.keys.count { it.startsWith(K_NOTE) && !(prefs.all[it] as? String).isNullOrBlank() }

    fun tagCount(): Int = allTagNames().size
}
