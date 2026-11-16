package com.n3vibecode.gallery.ai

import android.content.Context
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.GeoStore
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.map.GeoIndex
import com.n3vibecode.gallery.util.Fmt
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Bildersuche mit Embeddings – wie in „Google Edge Gallery“/Google Fotos auf dem Gerät:
 * Der Suchbegriff wird in einen Vektor umgerechnet, jedes Foto hat ebenfalls einen Vektor
 * (aus **allen** Angaben, die es zu dem Foto gibt), und gesucht wird nach der größten
 * Ähnlichkeit (Kosinus).
 *
 * Woher der Foto-Vektor kommt – bewusst nur aus **vorhandenen** Angaben, also ohne das Bild
 * selbst zu analysieren (das würde Minuten pro Bibliothek kosten und viel Akku brauchen):
 *   • Dateiname und Ordnername
 *   • eigene Notizen und Tags (XMP)
 *   • Ort („Luzern“, „Schweiz“ – offline bestimmt), Jahr, Monat, Wochentag, Uhrzeit („Nacht“)
 *   • Format (HEIC, RAW, Screenshot …), Kamera/Objektiv, Video
 *   • Bildmaße (Panorama, Hochformat/Screenshot, Quadrat)
 *
 * Diese Vektoren werden einmal berechnet und im App-Speicher gehalten; die Berechnung läuft
 * im Hintergrund und meldet ihren Fortschritt.
 */
object SearchIndex {

    private const val FILE = "n3_search_index.v1"

    /** Ein Eintrag je Foto. */
    private class Entry(
        val uri: String,
        val vector: FloatArray
    )

    private val entries = ConcurrentHashMap<String, FloatArray>()
    private val building = AtomicBoolean(false)

    @Volatile private var builtForScanMs: Long = -1
    @Volatile private var progress: Pair<Int, Int> = 0 to 0

    fun progress(): Pair<Int, Int> = progress
    fun indexedCount(): Int = entries.size
    fun isBuilding(): Boolean = building.get()

    // ------------------------------------------------------------------ Aufbau

    /**
     * Index für die aktuelle Bibliothek aufbauen (im Hintergrund). Wird nach einem Neuscan
     * oder beim ersten Öffnen der Suche aufgerufen; ein zweiter Aufruf während des Aufbaus
     * bewirkt nichts.
     */
    fun build(ctx: Context, items: List<MediaItem>, onDone: (() -> Unit)? = null) {
        if (!building.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            try {
                AiEngine.prepare(app)
                // Gemerkte Vektoren laden (App-Neustart: kein Neuberechnen)
                if (entries.isEmpty()) loadFromDisk(app, items)

                var done = 0
                val total = items.size
                progress = 0 to total
                var sinceSave = 0
                for (item in items) {
                    done++
                    if (!entries.containsKey(item.uri)) {
                        embedItem(app, item)?.let { v ->
                            entries[item.uri] = v
                            sinceSave++
                        }
                    }
                    if (done % 50 == 0 || done == total) {
                        progress = done to total
                        if (sinceSave >= 400) {
                            saveToDisk(app)
                            sinceSave = 0
                        }
                    }
                }
                saveToDisk(app)
                builtForScanMs = DataHub.lastScanMs
            } catch (_: Throwable) {
            } finally {
                building.set(false)
                main.post { onDone?.invoke() }
            }
        }, "n3-search-index").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Index verwerfen (nach Löschen/Bearbeiten von Fotos sinnvoll). */
    fun invalidate(ctx: Context) {
        entries.clear()
        builtForScanMs = -1
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    private fun embedItem(ctx: Context, item: MediaItem): FloatArray? {
        val text = describe(ctx, item)
        return AiEngine.embed(text)
    }

    /**
     * Alles, was das Foto beschreibt, als Text. Je aussagekräftiger dieser Text, desto besser
     * die Treffer – hier fließen alle vorhandenen Angaben ein.
     */
    fun describe(ctx: Context, item: MediaItem): String {
        val sb = StringBuilder(160)
        // Dateiname ohne Endung, mit Worttrennung (IMG_1234 → IMG 1234)
        sb.append(item.name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')).append(' ')
        sb.append(item.bucket.replace('_', ' ')).append(' ')
        sb.append(item.format).append(' ')
        sb.append(item.family).append(' ')
        if (item.isVideoFile) sb.append(" video film ")
        if (item.isHeif) sb.append(" heic heif ")
        if (item.isRaw) sb.append(" raw rohdaten ")
        if (item.ext.equals("png", true)) sb.append(" png grafik ")
        if (item.isVector) sb.append(" svg vektor grafik ")
        if (item.name.contains("screenshot", true) || item.name.contains("bildschirm", true)) {
            sb.append(" screenshot bildschirmfoto ")
        }
        // Hochformat/Querformat/Panorama – hilft bei „hochkant“, „panorama“
        if (item.width > 0 && item.height > 0) {
            val ratio = item.width.toFloat() / item.height
            when {
                ratio > 2.0f -> sb.append(" panorama breit ")
                ratio < 0.62f -> sb.append(" hochformat screenshot hochkant ")
                ratio in 0.95f..1.05f -> sb.append(" quadrat ")
            }
        }
        // Zeit
        if (item.time > 0) {
            sb.append(Fmt.yearTitle(item.time)).append(' ')
            sb.append(Fmt.monthTitle(item.time)).append(' ')
            sb.append(Fmt.weekday(item.time)).append(' ')
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = item.time }
                .get(java.util.Calendar.HOUR_OF_DAY)
            when {
                hour < 5 -> sb.append(" nacht dunkel ")
                hour < 9 -> sb.append(" morgen früh fruehstueck frühstück ")
                hour < 12 -> sb.append(" vormittag ")
                hour < 17 -> sb.append(" mittag nachmittag ")
                hour < 21 -> sb.append(" abend sonnenuntergang ")
                else -> sb.append(" nacht dunkel ")
            }
            if (item.time > 0) sb.append(Fmt.dateTime(item.time)).append(' ')
        }
        // Notizen und Tags (die stärksten Hinweise – der Nutzer hat sie selbst vergeben)
        val note = MetaStore.note(item.uri)
        if (note.isNotBlank()) sb.append(note).append(' ').append(note).append(' ')
        val tags = MetaStore.tags(item.uri)
        if (tags.isNotEmpty()) sb.append(tags.joinToString(" ")).append(' ')
        if (MetaStore.isFavorite(item.uri)) sb.append(" favorit lieblingsbild ")
        // Ort
        val pos = GeoStore.pointFor(item)
        if (pos != null) {
            GeoIndex.nearest(pos.first, pos.second)?.let { loc ->
                sb.append(loc.place.name).append(' ')
                sb.append(loc.place.country).append(' ')
                loc.region?.let { sb.append(it.name).append(' ') }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Suche

    /**
     * Fotos nach Ähnlichkeit zum Suchtext, sortiert (beste zuerst).
     * [limit] begrenzt die Anzahl; bei 0 wird alles zurückgegeben, was über der Schwelle liegt.
     */
    fun search(query: String, limit: Int = 0): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val q = AiEngine.embed(query) ?: return emptyList()
        val scores = ArrayList<Triple<MediaItem, Float, String>>(entries.size)
        val byUri = DataHub.byUri()
        var maxScore = 0f
        for ((uri, vec) in entries) {
            val s = cosine(q, vec)
            if (s > maxScore) maxScore = s
            val item = byUri[uri] ?: continue
            scores.add(Triple(item, s, uri))
        }
        if (scores.isEmpty() || maxScore <= 0f) return emptyList()
        // Mindestähnlichkeit relativ zum besten Treffer: so findet man auch bei schwacher
        // Ähnlichkeit (kleines Modell) alle sinnvollen Bilder, aber kein Rauschen.
        val threshold = (maxScore * 0.62f).coerceAtLeast(0.10f)
        val hits = scores.filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .map { it.first }
        return if (limit > 0) hits.take(limit) else hits
    }

    /** Ähnlichkeit zum Suchtext (0…1) – für die Sortierung „ähnlichste zuerst“. */
    fun score(queryVector: FloatArray?, uri: String): Float {
        val v = entries[uri] ?: return 0f
        return cosine(queryVector ?: return 0f, v)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 1e-9f || nb <= 1e-9f) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1f, 1f)
    }

    // ------------------------------------------------------------------ Speicher

    private fun saveToDisk(ctx: Context) {
        try {
            val file = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.outputStream().buffered().use { out ->
                val data = java.io.DataOutputStream(out)
                data.writeInt(MAGIC)
                data.writeInt(entries.size)
                for ((uri, vec) in entries) {
                    val ub = uri.toByteArray()
                    data.writeInt(ub.size)
                    data.write(ub)
                    data.writeInt(vec.size)
                    for (f in vec) data.writeFloat(f)
                }
                data.flush()
            }
            if (!tmp.renameTo(file)) tmp.delete()
            builtForScanMs = DataHub.lastScanMs
        } catch (_: Throwable) {
        }
    }

    private fun loadFromDisk(ctx: Context, items: List<MediaItem>) {
        try {
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) return
            // Nur übernehmen, wenn die Bibliothek seit dem letzten Mal gleich groß ist –
            // sonst ist der Index veraltet und wird neu berechnet.
            val known = items.count { it.uri.isNotEmpty() }
            file.inputStream().buffered().use { input ->
                val data = java.io.DataInputStream(input)
                if (data.readInt() != MAGIC) return
                val n = data.readInt()
                if (n <= 0 || n > 2_000_000) return
                val map = HashMap<String, FloatArray>(n * 2)
                repeat(n) {
                    val uriLen = data.readInt()
                    if (uriLen <= 0 || uriLen > 4096) return
                    val ub = ByteArray(uriLen)
                    data.readFully(ub)
                    val dim = data.readInt()
                    if (dim <= 0 || dim > 4096) return
                    val vec = FloatArray(dim) { data.readFloat() }
                    map[String(ub, Charsets.UTF_8)] = vec
                }
                if (map.size >= (known * 0.6f).toInt()) {
                    map.forEach { (k, v) -> entries[k] = v }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private const val MAGIC = 0x4E335331 // 'N3S1'
}
