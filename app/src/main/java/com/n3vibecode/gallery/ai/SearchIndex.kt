package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.GeoStore
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.map.GeoIndex
import com.n3vibecode.gallery.util.AppVisibility
import com.n3vibecode.gallery.util.Fmt
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Bildersuche mit Embeddings – wie in „Google Edge Gallery“/Google Fotos auf dem Gerät:
 * Der Suchbegriff wird in einen Vektor umgerechnet, jedes Foto hat ebenfalls einen Vektor
 * (aus **allen** Angaben, die es zu dem Foto gibt), gesucht wird nach der größten
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
 * **Was in 1.35 repariert wurde:** Der Aufbau lief nur, wenn man die Suchseite öffnete und
 * blieb dort bei „0 / n“ stehen, weil drei Fehler zusammenkamen (siehe [build]).
 */
object SearchIndex {

    private const val FILE = "n3_search_index.v2"
    private const val TAG = "N3Ai"

    private val entries = ConcurrentHashMap<String, FloatArray>()
    private val building = AtomicBoolean(false)

    /**
     * Wie viele Aufrufer gerade auf den Index warten. Zähler statt Ja/Nein, weil der Aufbau
     * beim Start, nach einem Neuscan und nach einem Modellwechsel angestoßen werden kann –
     * vorher setzte der zweite Aufruf den ersten auf „läuft nicht mehr“.
     */
    private val runningCount = AtomicInteger(0)

    @Volatile private var builtForScanMs: Long = -1
    @Volatile private var doneCount: Int = 0
    @Volatile private var totalCount: Int = 0

    /** Ergebnis des letzten Aufbaus: leer = alles gut, sonst der Grund. */
    @Volatile private var lastProblem: String = ""

    /** Ein Suchbegriff, mit dem geprüft wird, ob das Modell überhaupt nutzbar ist. */
    private const val PROBE = "foto"

    fun progress(): Pair<Int, Int> = doneCount to totalCount
    fun indexedCount(): Int = entries.size
    fun isBuilding(): Boolean = building.get()
    fun isRunning(): Boolean = runningCount.get() > 0
    fun lastError(): String = lastProblem

    /**
     * **Im Hintergrund vorbereiten** – beim Start der App und nach einem Neuscan.
     *
     * Läuft nur, wenn wirklich etwas fehlt (neue Fotos, anderes Modell, anderer Beschleuniger)
     * und nie doppelt. Das Modell wird dabei zuerst geprüft: Läuft EmbeddingGemma 2 nicht,
     * erscheint der Grund in der Suchseite.
     */
    fun warmUp(ctx: Context, delayMs: Long = 0L) {
        if (building.get()) return
        val app = ctx.applicationContext
        if (delayMs > 0) {
            // Erst den Bildschirm ankommen lassen (Wischen nach dem Start bleibt flüssig)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Thread({ warmNow(app) }, "n3-search-warmup").apply { isDaemon = true }.start()
            }, delayMs)
            return
        }
        Thread({ warmNow(app) }, "n3-search-warmup").apply { isDaemon = true }.start()
    }

    /** Arbeit von [warmUp] auf einem Hintergrund-Thread. */
    private fun warmNow(app: Context) {
        if (building.get()) return
        runningCount.incrementAndGet()
        try {
            // Ortsliste abwarten: Der Foto-Text enthält den Ortsnamen („Luzern“). Läuft der
            // Aufbau los, bevor die Ortsliste geladen ist, fehlt er im Index – dann findet
            // „Luzern“ nur noch über die klassische Suche. Nach dem ersten Start ist das
            // sofort da (das Warten endet, sobald die Liste bereit ist).
            runCatching { GeoIndex.awaitReady(4_000) }
            AiEngine.prepare(app)
            val items = DataHub.all
            if (!needsBuild(app, items)) {
                Log.i(TAG, "Suchindex ist aktuell (${entries.size} Fotos)")
                return
            }
            buildWork(app, items)
        } finally {
            runningCount.decrementAndGet()
        }
    }

    /** Muss der Index neu gebaut werden? */
    private fun needsBuild(ctx: Context, items: List<MediaItem>): Boolean {
        if (items.isEmpty()) return false
        if (entries.isEmpty()) return true
        if (builtForScanMs != DataHub.lastScanMs) return true
        // Beschleuniger gewechselt (z. B. GPU → CPU) oder Modell getauscht? → andere Vektoren
        val stamp = runCatching { File(ctx.filesDir, "$FILE.stamp").readText().trim() }.getOrNull()
        return stamp != entryStamp()
    }

    /** Kennung der aktuell rechnenden Engine (Modell + Beschleuniger). */
    private fun entryStamp(): String {
        val s = AiEngine.currentStatus()
        return "${s.modelName}|${s.accelerator.name}|${s.modelInstalled}"
    }

    // ------------------------------------------------------------------ Aufbau

    /**
     * Index aufbauen. Wird vom Hauptbildschirm ([warmUp]) und nach einem Modellwechsel
     * aufgerufen; ein zweiter Aufruf während des Aufbaus bewirkt nichts.
     *
     * [onDone] kommt auf dem Haupt-Thread.
     */
    fun build(ctx: Context, items: List<MediaItem>, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread({
            runningCount.incrementAndGet()
            try {
                AiEngine.prepare(app)
                buildWork(app, items)
            } finally {
                runningCount.decrementAndGet()
                if (onDone != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onDone() }
                }
            }
        }, "n3-search-index").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /**
     * Eigentliche Arbeit – **läuft immer, auch wenn der Aufrufer schon weg ist**.
     *
     * Drei Fehler aus 1.34 steckten hier:
     *  1. `if (!building.compareAndSet(false, true)) return` verwarf den Aufruf still – ohne
     *     `onDone`. Die Suchseite wartete bei „Modell wird vorbereitet“ und ihr Fortschritt
     *     blieb bei 0 / n stehen, weil der Aufbau nie lief.
     *  2. `if (entries.isEmpty()) loadFromDisk(...)` lud den Stand des **vorigen** Modells: Die
     *     alten Vektoren (andere Dimension, anderes Modell) blockierten jede Neuberechnung.
     *  3. Ein `catch (_: Throwable) {}` verschluckte jeden Fehler und meldete trotzdem fertig.
     */
    private fun buildWork(app: Context, items: List<MediaItem>) {
        if (!building.compareAndSet(false, true)) {
            Log.i(TAG, "Index-Aufbau läuft bereits")
            return
        }
        try {
            lastProblem = ""
            // Gespeicherte Vektoren nur übernehmen, wenn sie zum aktuellen Modell gehören.
            val stamp = runCatching { File(app.filesDir, "$FILE.stamp").readText().trim() }.getOrNull()
            if (stamp == entryStamp()) {
                loadFromDisk(app, items)
            } else if (stamp != null || entries.isNotEmpty()) {
                Log.i(TAG, "Modell/Beschleuniger geändert – Suchindex wird neu berechnet")
                entries.clear()
            }

            val total = items.size
            totalCount = total
            doneCount = 0
            if (total == 0) {
                saveToDisk(app)
                return
            }

            // Ist das Modell überhaupt benutzbar? Sonst bleibt der Index leer und der Grund
            // steht in der Suchseite – statt still mit falschen Treffern zu arbeiten.
            if (AiEngine.embed(PROBE).isNullOrEmpty()) {
                lastProblem = AiEngine.failureReason().ifBlank {
                    "Das Suchmodell liefert keine Vektoren."
                }
                Log.w(TAG, "Index nicht möglich: $lastProblem")
                return
            }

            var done = 0
            var sinceSave = 0
            var fails = 0
            // Höflich zur Bedienung: nach jedem Foto kurz Luft lassen. Das Modell rechnet
            // ohnehin seriell (AiEngine.embedLock); die Pause sorgt dafür, dass Scrollen,
            // Tastatur und Bildaufbau dazwischenkommen können. Ist die App im Hintergrund,
            // wird länger gewartet (Akku) – beim Zurückkommen geht es sofort weiter.
            for (item in items) {
                if (!entries.containsKey(item.uri)) {
                    val v = embedItem(app, item)
                    if (v != null) {
                        entries[item.uri] = v
                        sinceSave++
                    } else {
                        fails++
                    }
                }
                done++
                doneCount = done
                runCatching { Thread.sleep(if (AppVisibility.isVisible()) PACE_MS else PACE_BG_MS) }
                if (done % 40 == 0) {
                    if (sinceSave >= 300) {
                        saveToDisk(app)
                        sinceSave = 0
                    }
                    Thread.yield()
                }
            }
            saveToDisk(app)
            builtForScanMs = DataHub.lastScanMs
            doneCount = total
            if (fails > 0 && entries.isEmpty()) {
                lastProblem = AiEngine.failureReason().ifBlank {
                    "Kein Foto konnte eingebettet werden."
                }
            }
            Log.i(TAG, "Suchindex fertig: ${entries.size} Vektoren (${aiName()}), $fails ohne Vektor")
        } catch (t: Throwable) {
            lastProblem = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Suchindex abgebrochen: $lastProblem", t)
        } finally {
            building.set(false)
        }
    }

    private fun aiName(): String = AiEngine.currentStatus().modelName

    /** Index verwerfen (Modellwechsel, Fotos gelöscht). */
    fun invalidate(ctx: Context) {
        entries.clear()
        builtForScanMs = -1
        doneCount = 0
        totalCount = 0
        lastProblem = ""
        descriptionCache.clear()
        runCatching { File(ctx.filesDir, FILE).delete() }
        runCatching { File(ctx.filesDir, "$FILE.stamp").delete() }
    }

    private fun embedItem(ctx: Context, item: MediaItem): FloatArray? = AiEngine.embed(describeCached(ctx, item))

    /**
     * Beschreibungstexte im Speicher halten.
     *
     * Sie werden zweimal gebraucht (Index-Aufbau und klassische Suche). Vorher entstand der
     * Text bei **jeder** Suche für **jedes** Foto neu – inklusive Datenbankzugriff für den Ort.
     * Bei einigen tausend Fotos blockierte das die Suche und die Tastatur hakte.
     */
    private val descriptionCache = ConcurrentHashMap<String, String>()

    fun describeCached(ctx: Context, item: MediaItem): String {
        val key = "${item.uri}|${MetaStore.note(item.uri).hashCode()}|" +
            "${MetaStore.tags(item.uri).hashCode()}|${MetaStore.isFavorite(item.uri)}"
        descriptionCache[key]?.let { return it }
        val text = describe(ctx, item)
        if (descriptionCache.size > 60_000) descriptionCache.clear()
        descriptionCache[key] = text
        return text
    }

    /** Nur für Tests/Diagnose: gibt es einen Vektor zu diesem Foto? */
    fun hasVector(uri: String): Boolean = entries.containsKey(uri)

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
        if (item.isVideoFile) sb.append("video film ")
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
                hour < 9 -> sb.append(" morgen frueh fruehstueck ")
                hour < 12 -> sb.append(" vormittag ")
                hour < 17 -> sb.append(" mittag nachmittag ")
                hour < 21 -> sb.append(" abend sonnenuntergang ")
                else -> sb.append(" nacht dunkel ")
            }
            sb.append(Fmt.dateTime(item.time)).append(' ')
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
        if (entries.isEmpty()) return emptyList()
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
        // Ähnlichkeit alle sinnvollen Bilder, aber kein Rauschen.
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
            // Zu welchem Modell gehören diese Vektoren?
            runCatching { File(ctx.filesDir, "$FILE.stamp").writeText(entryStamp()) }
            builtForScanMs = DataHub.lastScanMs
        } catch (t: Throwable) {
            Log.w(TAG, "Suchindex speichern: ${t.message}")
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
                    Log.i(TAG, "Suchindex geladen: ${map.size} Vektoren")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Suchindex lesen: ${t.message}")
        }
    }

    /** Pause zwischen zwei Fotos (ms) – sichtbar bzw. im Hintergrund. */
    private const val PACE_MS = 12L
    private const val PACE_BG_MS = 60L

    private const val MAGIC = 0x4E335332 // 'N3S2'
}
