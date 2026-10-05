package com.n3vibecode.gallery.data

import androidx.annotation.MainThread

/**
 * Gemeinsamer, speicherinterner Zustand der Bibliothek (keine Datenbank, alles zur Laufzeit).
 */
object DataHub {

    @Volatile
    var all: List<MediaItem> = emptyList()
        private set

    @Volatile
    var query: String = ""

    @Volatile
    var lastScanMs: Long = 0L

    @Volatile
    var hiddenByUserSelection: Boolean = false

    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Wird von Ansichten gesetzt, die einen Neuscan anstoßen können (Hauptbildschirm).
     * So kann z. B. der Editor nach dem Speichern sagen: „Liste bitte neu einlesen“.
     */
    @Volatile
    var rescanHandler: (() -> Unit)? = null

    /** Merkt sich, dass neu eingelesen werden muss – auch wenn gerade kein Bildschirm aktiv ist. */
    @Volatile
    var rescanPending: Boolean = false
        private set

    fun requestRescan() {
        rescanPending = true
        mainHandler.post {
            val handler = rescanHandler
            if (handler != null) {
                rescanPending = false
                handler.invoke()
            }
        }
    }

    /** Vom Hauptbildschirm aufgerufen, wenn er wieder sichtbar ist und etwas offen war. */
    fun consumePendingRescan(): Boolean {
        if (!rescanPending) return false
        rescanPending = false
        return true
    }

    fun setItems(items: List<MediaItem>, limited: Boolean) {
        all = items
        hiddenByUserSelection = limited
        lastScanMs = System.currentTimeMillis()
        notifyChanged()
    }

    fun find(uri: String): MediaItem? = all.firstOrNull { it.uri == uri }

    fun visible(base: List<MediaItem>): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return base
        val terms = q.lowercase().split(' ').filter { it.isNotBlank() }
        return base.filter { item ->
            val hay = buildString {
                append(item.name.lowercase()).append(' ')
                append(item.format.lowercase()).append(' ')
                append(item.family.lowercase()).append(' ')
                append(item.bucket.lowercase()).append(' ')
                append(item.path.lowercase()).append(' ')
                append(MetaStore.tags(item.uri).joinToString(" ").lowercase()).append(' ')
                append(MetaStore.note(item.uri).lowercase())
            }
            terms.all { hay.contains(it) }
        }
    }

    @MainThread
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    @MainThread
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Nach Änderung von Suchtext/Filter die Ansichten neu aufbauen. */
    fun refreshListeners() {
        notifyChanged()
    }

    private fun notifyChanged() {
        // Benachrichtigungen laufen über den Haupt-Thread (Handler der Fragmente).
        mainHandler.post { listeners.toList().forEach { it.invoke() } }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
}
