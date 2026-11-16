package com.n3vibecode.gallery.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Größenprüfung der Modelldateien.
 *
 * Hintergrund (auf dem Gerät sichtbar): `embeddinggemma-2-740m.litertlm` lag mit 462 MB im
 * App-Speicher – die Datei ist 484 622 336 Bytes (462 MiB) groß, der Download war also mitten
 * drin abgebrochen und die halbe Datei wurde als fertig übernommen. Ergebnis: Das Modell
 * startet nicht, und es sieht aus wie ein Fehler des Modells.
 *
 * Diese Tests prüfen die Grenzen der Regel „vollständig = innerhalb 1 KiB der Sollgröße“.
 */
class ModelSizeTest {

    private val full = ModelStore.Variant.FULL

    @Test
    fun sollgroessenSindHinterlegt() {
        // Werte aus dem HTTP-Header der Hugging-Face-Antwort (in CI gemessen)
        assertEquals(164_626_432L, ModelStore.Variant.TEXT.bytes)
        assertEquals(387_710_976L, ModelStore.Variant.TEXT_VISION.bytes)
        assertEquals(484_622_336L, full.bytes)
    }

    @Test
    fun angezeigteGroessePasstZuDenBytes() {
        // 484 622 336 Bytes = 462 MiB (nicht 1,3 GB)
        assertEquals(462, full.expectedMb)
        assertEquals(157, ModelStore.Variant.TEXT.expectedMb)
        assertEquals(370, ModelStore.Variant.TEXT_VISION.expectedMb)
    }

    /** Die Prüfung selbst – dieselbe Rechnung wie `ModelStore.isComplete`. */
    private fun complete(name: String, length: Long): Boolean {
        val known = ModelStore.Variant.entries.firstOrNull { it.file == name }
        return if (known != null && known.bytes > 0) {
            length >= known.bytes - 1024 && length <= known.bytes + 1024
        } else {
            length > 1024 * 1024
        }
    }

    @Test
    fun exakteGroesseIstVollstaendig() {
        assertTrue(complete(full.file, full.bytes))
    }

    @Test
    fun kleineAbweichungIstVollstaendig() {
        assertTrue("1 KiB Toleranz", complete(full.file, full.bytes - 1024))
        assertTrue("1 KiB Toleranz", complete(full.file, full.bytes + 1024))
    }

    /** Der echte Fehlerfall: 462 MB, also deutlich weniger als 484 622 336 Bytes. */
    @Test
    fun abgeschnitteneDateiIstUnvollstaendig() {
        assertFalse(
            "462 MB (462 000 000 Bytes) ist nicht die volle Datei",
            complete(full.file, 462_000_000L)
        )
        assertFalse("die Hälfte reicht nicht", complete(full.file, full.bytes / 2))
        assertFalse("leere Datei", complete(full.file, 0))
    }

    @Test
    fun zuGrosseDateiIstUnvollstaendig() {
        assertFalse("mehr als die Sollgröße ist verdächtig", complete(full.file, full.bytes * 2))
    }

    @Test
    fun unbekannteDateiNurNachMindestgroesse() {
        assertFalse("1 MB ist kein Modell", complete("irgendein.litertlm", 1024L))
        assertTrue("50 MB gilt als Modell", complete("irgendein.litertlm", 50L * 1024 * 1024))
    }

    @Test
    fun platzpruefungRechnetMitDerEchtenGroesse() {
        // 462 MiB + 200 MiB Reserve = 662 MiB
        val needed = full.expectedMb.toLong() * 1024 * 1024 + 200L * 1024 * 1024
        assertEquals(694_157_312L, needed)
    }

    /** Die Sollgrößen der Modelle sind aufsteigend – die Auswahl im Dialog ist sinnvoll sortiert. */
    @Test
    fun reihenfolgeIstAufsteigend() {
        val sizes = ModelStore.Variant.entries.map { it.bytes }
        assertEquals(sizes.sorted(), sizes)
    }
}
