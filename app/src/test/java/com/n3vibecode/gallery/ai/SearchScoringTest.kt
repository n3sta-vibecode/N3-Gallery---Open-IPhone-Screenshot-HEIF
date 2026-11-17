package com.n3vibecode.gallery.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests für die Trefferbewertung der Bildersuche.
 *
 * Die Suche vergleicht Vektoren über die Kosinus-Ähnlichkeit und nimmt alles ab
 * `0,62 × bester Treffer` (mindestens 0,10). Diese Schwellen sind der Unterschied zwischen
 * „findet sinnvolle Bilder“ und „zeigt die halbe Bibliothek“ – deshalb hier festgenagelt.
 */
class SearchScoringTest {

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

    private fun threshold(maxScore: Float): Float = (maxScore * 0.62f).coerceAtLeast(0.10f)

    @Test
    fun identischeVektorenSindEins() {
        val v = floatArrayOf(0.3f, -0.5f, 0.8f)
        assertEquals(1f, cosine(v, v.copyOf()), 0.001f)
    }

    @Test
    fun gegenvektorenSindMinusEins() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        assertEquals(-1f, cosine(a, b), 0.001f)
    }

    @Test
    fun senkrechteVektorenSindNull() {
        assertEquals(0f, cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.001f)
    }

    @Test
    fun nullvektorBleibtNull() {
        assertEquals(0f, cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0.001f)
    }

    /** Vektoren unterschiedlicher Länge (anderes Modell im Index) dürfen nicht abstürzen. */
    @Test
    fun unterschiedlicheDimensionenStuerzenNichtAb() {
        val s = cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f))
        assertTrue(s in -1f..1f)
    }

    @Test
    fun schwelleFolgtDemBestenTreffer() {
        // Bei einem guten Treffer (0,8) liegt die Schwelle bei 0,496
        assertEquals(0.496f, threshold(0.8f), 0.001f)
        // Bei schwachen Treffern greift die Untergrenze 0,10
        assertEquals(0.10f, threshold(0.05f), 0.001f)
        assertEquals(0.10f, threshold(0f), 0.001f)
    }

    @Test
    fun schwelleIstNieNull() {
        // Früher wurde bei maxScore 0 alles zurückgegeben – jetzt nie
        assertTrue(threshold(0f) > 0f)
    }

    /** Was die Suche mit einem echten Treffer macht: nur ähnliche Vektoren kommen durch. */
    @Test
    fun nurAehnlicheTrefferKommenDurch() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = mapOf(
            "sehr ähnlich" to floatArrayOf(0.95f, 0.1f, 0f),
            "ähnlich" to floatArrayOf(0.8f, 0.2f, 0f),
            "unähnlich" to floatArrayOf(0f, 1f, 0f)
        )
        val scores = candidates.mapValues { cosine(query, it.value) }
        val best = scores.values.max()
        val hits = scores.filterValues { it >= threshold(best) }.keys
        assertTrue("sehr ähnlich muss dabei sein", "sehr ähnlich" in hits)
        assertTrue("ähnlich muss dabei sein", "ähnlich" in hits)
        assertTrue("unähnlich darf nicht dabei sein", "unähnlich" !in hits)
    }
}
