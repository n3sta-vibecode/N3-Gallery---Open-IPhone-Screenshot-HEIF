package com.n3vibecode.gallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.image.ImageLoader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * **Personen-Klassifizierung (1.42) – das zweite Modell.**
 *
 * Während [SearchIndex] **Text**-Einbettungen für die Bildersuche baut, bettet dieser Index
 * die **Fotos selbst** ein (Bild-Einbettung mit dem multimodalen EmbeddingGemma-2-Modell,
 * Text+Bild 440M oder 740M – auf der NPU, siehe [AiEngine.preparePersons]).
 *
 * Daraus ergibt sich „ähnliche Fotos“: Fotos mit denselben Personen (oder demselben Motiv)
 * liegen im Vektorraum dicht beieinander. Das Foto, von dem aus gesucht wird, wird mit
 * jedem anderen Foto verglichen (Kosinus-Ähnlichkeit) – die treffsichersten zuerst.
 *
 * ## Aufbau
 *
 *  * Fotos werden **einmal** eingebettet und in `files/persons.idx` gespeichert – auch über
 *    Neustarts hinweg. Neue Fotos werden ergänzt, nicht alles neu gerechnet.
 *  * Das Speicherformat trägt den Namen der Modelldatei im Kopf: Ein anderes Modell rechnet
 *    in einem anderen Vektorraum – dann wird der Index neu aufgebaut statt Äpfel mit Birnen
 *    zu vergleichen.
 *  * Wie bei der Suche gilt: **ein Einbetten nach dem anderen** (dasselbe Schloss in
 *    [AiEngine]), damit die NPU nie zwei Dinge gleichzeitig rechnet.
 *  * Abbrechbar: [stop] verwirft den weiteren Aufbau; schon gespeicherte Vektoren bleiben.
 *  * [ensureBuilt] meldet sich **immer im Haupt-Thread** zurück – auch im Fehlerfall.
 */
object PersonIndex {

    private const val TAG = "N3Ai"
    private const val FILE = "persons.idx"
    private const val MAGIC = 0x4E335049   // "N3PI"
    private const val VERSION = 1

    /** Größe der eingebetteten Vorschau (längste Kante) – reicht für Personen, bleibt schnell. */
    private const val EMBED_PX = 512

    /** Alle 40 Fotos zwischenspeichern – bei einem Abbruch ist nicht alles weg. */
    private const val SAVE_EVERY = 40

    private val lock = Object()
    private val vectors = HashMap<String, FloatArray>()   // uri → Bild-Vektor
    private val waiters = ArrayList<(Boolean, String) -> Unit>()

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var doneCount = 0
    @Volatile private var totalCount = 0
    @Volatile private var lastError = ""
    @Volatile private var modelFile = ""
    private var loaded = false

    fun progress(): Pair<Int, Int> = doneCount to totalCount
    fun isRunning(): Boolean = running
    fun lastError(): String = lastError

    fun size(): Int = synchronized(lock) { vectors.size }

    /** Laufenden Aufbau anhalten (Ergebnisse bis dahin bleiben gespeichert). */
    fun stop() {
        stopRequested = true
    }

    // ------------------------------------------------------------------ Datei

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    private fun load(ctx: Context, expectedModel: String) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching {
                DataInputStream(file(ctx).inputStream().buffered()).use { input ->
                    if (input.readInt() != MAGIC) return
                    if (input.readInt() != VERSION) return
                    val model = input.readUTF()
                    if (model != expectedModel) {
                        Log.i(TAG, "Personen-Index stammt von anderem Modell – wird neu aufgebaut")
                        return
                    }
                    modelFile = model
                    val n = input.readInt()
                    repeat(n) {
                        val uri = input.readUTF()
                        val dims = input.readInt()
                        val v = FloatArray(dims)
                        repeat(dims) { i -> v[i] = input.readFloat() }
                        vectors[uri] = v
                    }
                    Log.i(TAG, "Personen-Index geladen: ${vectors.size} Fotos")
                }
            }
        }
    }

    private fun save(ctx: Context) {
        synchronized(lock) {
            runCatching {
                val tmp = File(ctx.filesDir, "$FILE.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    out.writeUTF(modelFile)
                    out.writeInt(vectors.size)
                    for ((uri, v) in vectors) {
                        out.writeUTF(uri)
                        out.writeInt(v.size)
                        for (x in v) out.writeFloat(x)
                    }
                }
                if (!tmp.renameTo(file(ctx))) tmp.delete()
            }.onFailure { Log.w(TAG, "Personen-Index speichern: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ Aufbau

    /**
     * Index aufbauen (falls nötig) und dann aufrufen. Läuft im Hintergrund;
     * [onDone] kommt **im Haupt-Thread**: (ok, Grund falls nicht ok).
     *
     * Läuft schon ein Aufbau, wird der Aufruf einfach zusätzlich angemeldet – der laufende
     * Aufbau meldet sich bei allen Wartenden zurück.
     */
    fun ensureBuilt(ctx: Context, onDone: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        if (!AiEngine.hasPersonsModel(app)) {
            postMain { onDone(false, app.getString(com.n3vibecode.gallery.R.string.persons_no_model)) }
            return
        }
        AiEngine.preparePersons(app) { ok, reason ->
            if (!ok) {
                postMain { onDone(false, reason) }
                return@preparePersons
            }
            val model = ModelStore.bestVision(app)?.name ?: ""
            load(app, model)
            synchronized(lock) {
                modelFile = model
                if (running) {
                    waiters.add(onDone)
                    return@preparePersons
                }
                running = true
                waiters.add(onDone)
            }
            stopRequested = false
            Thread({
                val items = DataHub.all.filter { !it.isVideoFile }
                totalCount = items.size
                doneCount = 0
                lastError = ""
                // Fotofür-Foto: Vorschau dekodieren (ImageLoader-ThreadPool), dann einbetten
                fun step(i: Int) {
                    if (stopRequested || i >= items.size) {
                        finish(app)
                        return
                    }
                    val item = items[i]
                    val known = synchronized(lock) { vectors.containsKey(item.uri) }
                    if (known) {
                        doneCount = i + 1
                        step(i + 1)
                        return
                    }
                    ImageLoader.loadPreview(app, item, EMBED_PX) { bmp ->
                        if (stopRequested) {
                            finish(app)
                            return@loadPreview
                        }
                        Thread({
                            if (bmp != null) {
                                val v = embed(bmp)
                                if (v != null) synchronized(lock) { vectors[item.uri] = v }
                            }
                            doneCount = i + 1
                            if (doneCount % SAVE_EVERY == 0) save(app)
                            step(i + 1)
                        }, "n3-persons-embed").apply { isDaemon = true }.start()
                    }
                }
                step(0)
            }, "n3-persons-build").apply { isDaemon = true }.start()
        }
    }

    /** Aufräumen und alle Wartenden (im Haupt-Thread) informieren. */
    private fun finish(ctx: Context) {
        val toCall: List<(Boolean, String) -> Unit>
        synchronized(lock) {
            if (running) save(ctx)
            running = false
            toCall = ArrayList(waiters)
            waiters.clear()
        }
        val stopped = stopRequested
        val err = lastError
        postMain {
            for (w in toCall) runCatching {
                val ok = !stopped && err.isBlank()
                val reason = when {
                    stopped -> ctx.getString(com.n3vibecode.gallery.R.string.persons_stopped)
                    err.isNotBlank() -> err
                    else -> ""
                }
                w(ok, reason)
            }
        }
    }

    private fun postMain(block: () -> Unit) = ImageLoader.postMain(block)

    /** Bild → Vektor (JPEG-Zwischenschritt, wie es die LiteRT-LM-Schnittstelle verlangt). */
    private fun embed(bmp: Bitmap): FloatArray? = runCatching {
        // Auf die Einbettungsgröße herunterrechnen – kleinere Bytes = schnelleres Modell
        var b = bmp
        val longEdge = maxOf(b.width, b.height)
        if (longEdge > EMBED_PX) {
            val scale = EMBED_PX.toFloat() / longEdge
            b = Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1),
                (b.height * scale).toInt().coerceAtLeast(1), true)
        }
        val out = java.io.ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 88, out)
        AiEngine.embedImage(out.toByteArray())
    }.getOrNull()

    // ------------------------------------------------------------------ Suche

    /**
     * Fotos, die diesem am ähnlichsten sind (denselben Personen / demselben Motiv) –
     * beste Treffer zuerst, das Ausgangsfoto selbst ist nicht dabei.
     */
    fun similar(ctx: Context, uri: String, limit: Int = 60): List<MediaItem> {
        val app = ctx.applicationContext
        val model = ModelStore.bestVision(app)?.name ?: ""
        load(app, model)
        val snapshot: Map<String, FloatArray>
        val base: FloatArray?
        synchronized(lock) {
            base = vectors[uri]
            snapshot = HashMap(vectors)
        }
        if (base == null || base.isEmpty()) return emptyList()
        val byUri = HashMap<String, MediaItem>(DataHub.all.size * 2)
        for (m in DataHub.all) byUri[m.uri] = m
        val scored = ArrayList<Pair<MediaItem, Float>>(snapshot.size)
        for ((other, v) in snapshot) {
            if (other == uri) continue
            val item = byUri[other] ?: continue
            val s = cosine(base, v)
            if (s > 0.55f) scored.add(item to s)   // Schwellwert: nur wirklich Ähnliche
        }
        scored.sortByDescending { it.second }
        return scored.take(limit).map { it.first }
    }

    /** Kosinus-Ähnlichkeit zweier Vektoren (1 = gleich, 0 = unabhängig). */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0f || nb <= 0f) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1f, 1f)
    }

    /** Index komplett verwerfen (z. B. nach dem Löschen der Modelldateien). */
    fun clear(ctx: Context) {
        synchronized(lock) {
            vectors.clear()
            loaded = true
            modelFile = ""
        }
        runCatching { file(ctx).delete() }
    }
}
