package com.n3vibecode.gallery.ai

import android.content.Context
import kotlin.math.sqrt

/**
 * Eingebautes **Wort-Bedeutungsmodell** – die Suche funktioniert damit sofort, ohne dass ein
 * großes Modell im App-Paket liegen muss (das Gemma-Modell ist ~ 200 MB).
 *
 * Idee: Jedes Wort wird über eine kleine, in der App hinterlegte Bedeutungstabelle in einen
 * Vektor übersetzt („Strand“, „Meer“, „Urlaub“ liegen dicht beieinander). Diese Vektoren
 * werden mit den Buchstabenanteilen (Zeichen-3-Gramme) gemischt – dadurch funktionieren auch
 * Wörter, die nicht in der Tabelle stehen, sowie Tippfehler und Wortteile („Sonnenunterg“).
 *
 * Das ist bewusst **kein** Ersatz für ein echtes Embedding-Modell, sondern die Garantie, dass
 * die Suche in eigenen Worten auch ohne 200 MB Download sinnvolle Treffer liefert.
 */
internal class LocalTextModel(context: Context) : AiEngine.EmbedderBackend {

    override val accelerator = AiEngine.Accelerator.LOCAL
    override val modelName = "Wortmodell (eingebaut)"
    override val detail = "ohne Modell-Download"

    private val dimensions: Int
    private val vocabulary: Array<String>
    private val vectors: Array<FloatArray>

    /** Wort → Index (einmal aufgebaut; vorher wurde pro Suchwort die ganze Liste durchsucht). */
    private val exactIndex: HashMap<String, Int>

    init {
        val (dim, vocab, vecs) = loadModel(context)
        dimensions = dim
        vocabulary = vocab
        vectors = vecs
        exactIndex = HashMap(vocab.size * 2)
        vocab.forEachIndexed { i, w -> exactIndex.putIfAbsent(w, i) }
    }

    fun dimensionCount(): Int = dimensions

    /** Bedeutung + Schreibweise zu einem Vektor zusammenrechnen (L2-normalisiert). */
    override fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        val out = FloatArray(dimensions)
        var hits = 0
        for (token in tokenize(text)) {
            val idx = exactIndex[token] ?: lookupByStem(token)
            if (idx >= 0) {
                add(out, vectors[idx], 1f)
                hits++
            } else {
                // Unbekanntes Wort: über Zeichenanteile und Wortstamm annähern
                val approx = approximate(token)
                if (approx != null) {
                    add(out, approx, 0.7f)
                    hits++
                }
            }
        }
        if (hits == 0) return null
        normalize(out)
        return out
    }

    /** Wortstamm-Suche: „sonnenuntergaenge“ → „sonnenuntergang“. */
    private fun lookupByStem(token: String): Int {
        if (token.length < 4) return -1
        for (i in vocabulary.indices) {
            val v = vocabulary[i]
            if (v.length >= 4 && (token.startsWith(v) || v.startsWith(token))) return i
        }
        return -1
    }

    /**
     * Unbekanntes Wort annähern: Zeichen-3-Gramme als Vektor. Gleich geschriebene Wörter
     * (auch in anderer Form) landen dadurch dicht beieinander; zusätzlich werden Wörter mit
     * gleichem Wortstamm wie ein bekanntes Wort behandelt.
     */
    private fun approximate(token: String): FloatArray? {
        val grams = gramsOf(token)
        if (grams.isEmpty()) return null
        val out = FloatArray(dimensions)
        for (g in grams) {
            val h = stableHash(g) % dimensions
            out[h] += 1f
            // Nachbar-Dimension mischen, damit ähnliche Wörter nicht zufällig weit auseinander
            // liegen („Strand“ und „Strände“).
            out[(h + 1) % dimensions] += 0.5f
        }
        normalize(out)
        return out
    }

    private fun gramsOf(token: String): List<String> {
        val padded = "  $token "
        if (padded.length < 3) return emptyList()
        val out = ArrayList<String>(padded.length)
        for (i in 0..padded.length - 3) out.add(padded.substring(i, i + 3))
        return out
    }

    private fun stableHash(s: String): Int {
        var h = 0
        for (c in s) h = h * 31 + c.code
        return (h and 0x7FFFFFFF)
    }

    private fun add(target: FloatArray, src: FloatArray, weight: Float) {
        for (i in src.indices) target[i] += src[i] * weight
    }

    private fun normalize(v: FloatArray) {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum)
        if (n <= 1e-6f) return
        for (i in v.indices) v[i] /= n
    }

    /**
     * Umlaute ausschreiben („Grüße“ → „gruesse“). Muss **sowohl** beim Laden der Wortliste
     * als auch beim Zerlegen des Suchtexts passieren – sonst findet „selbstporträt“ das
     * eingetragene Wort „porträt“ nie, weil auf der einen Seite „ae“ und auf der anderen
     * „ä“ steht.
     */
    private fun fold(text: String): String =
        text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            // Doppelt kodierte Umlaute aus älteren Dateien („FrÃ¼hstÃ¼ck“) geradeziehen
            .replace("Ã¤", "ae").replace("Ã¶", "oe").replace("Ã¼", "ue")
            .replace("ÃŸ", "ss").replace("Ã©", "e")

    private fun tokenize(text: String): List<String> =
        fold(text)
            .split(Regex("[^a-z0-9aeiou]+"))
            .filter { it.length >= 2 }

    override fun close() {}

    // ------------------------------------------------------------------ Datenformat

    /**
     * Liest `assets/ai/wortmodell.txt`:
     *
     *   # Kommentar
     *   dim 128
     *   strand 1.0 0 0.2 ...        (Wort + Werte, fehlende Werte = 0)
     *
     * Die Datei ist klein (wenige kB) und liegt im App-Paket.
     */
    private fun loadModel(ctx: Context): Triple<Int, Array<String>, Array<FloatArray>> {
        val vocab = ArrayList<String>(600)
        val vecs = ArrayList<FloatArray>(600)
        var dim = 0
        val lines = runCatching {
            // Ausdrücklich UTF-8: Die Datei enthält Umlaute („Frühstück“). Ohne Angabe nimmt
            // Android die Latin-1-Codepage des Systems – dann steht dort „FrÃ¼hstÃ¼ck“ und
            // das Wort wird nie gefunden.
            ctx.assets.open("ai/wortmodell.txt")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
        }.getOrDefault(emptyList())

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("dim ")) {
                dim = line.substring(4).trim().toIntOrNull() ?: 0
                continue
            }
            val parts = line.split(' ')
            if (parts.size < 2) continue
            val word = fold(parts[0])
            val values = parts.drop(1).mapNotNull { it.toFloatOrNull() }
            if (values.isEmpty()) continue
            val v = FloatArray(if (dim > 0) dim else values.size)
            for (i in values.indices) if (i < v.size) v[i] = values[i]
            normalize(v)
            vocab.add(word)
            vecs.add(v)
        }
        if (dim <= 0) dim = vecs.firstOrNull()?.size ?: 128
        if (vocab.isEmpty()) {
            // Keine Datei vorhanden: nur mit Zeichenanteilen arbeiten (immer noch brauchbar)
            return Triple(dim, emptyArray(), emptyArray())
        }
        // Vektoren auf die endgültige Dimension bringen
        val finalVecs = Array(vecs.size) { i ->
            val src = vecs[i]
            if (src.size == dim) src else FloatArray(dim).also { dst ->
                for (k in 0 until minOf(dim, src.size)) dst[k] = src[k]
                normalize(dst)
            }
        }
        return Triple(dim, vocab.toTypedArray(), finalVecs)
    }
}
