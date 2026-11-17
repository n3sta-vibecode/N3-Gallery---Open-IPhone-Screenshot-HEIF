package com.n3vibecode.gallery.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Größen- und Chipsatz-Prüfung der Modelldateien.
 *
 * Hintergrund (auf dem Gerät sichtbar): `embeddinggemma-2-740m.litertlm` lag mit 462 MB im
 * App-Speicher – die Datei ist 484 622 336 Bytes (462 MiB) groß, der Download war also mitten
 * drin abgebrochen und die halbe Datei wurde als fertig übernommen. Ergebnis: Das Modell
 * startet nicht, und es sieht aus wie ein Fehler des Modells.
 *
 * 1.42 kommt dazu: Es gibt jetzt **NPU-Fassungen pro Chipsatz** (z. B.
 * `…_Qualcomm_SM8750.litertlm` für das Galaxy S25 Ultra) – die Tests prüfen, dass auf einem
 * SM8750-Gerät automatisch die NPU-Datei gewählt wird und die Größen der drei Familien
 * stimmen.
 */
class ModelSizeTest {

    private val full = ModelStore.fileFor(ModelStore.Kind.FULL, "")
    private val text = ModelStore.fileFor(ModelStore.Kind.TEXT, "")
    private val vision = ModelStore.fileFor(ModelStore.Kind.VISION, "")

    @Test
    fun sollgroessenSindHinterlegt() {
        // Werte aus dem HTTP-Header der Hugging-Face-Antwort (in CI gemessen)
        assertEquals(164_626_432L, text.bytes)
        assertEquals(387_710_976L, vision.bytes)
        assertEquals(484_622_336L, full.bytes)
    }

    @Test
    fun npuFassungenSindGroesser() {
        // Die NPU-Fassungen enthalten zusätzlich die Vorkompilierung – sie sind nie kleiner.
        for (kind in ModelStore.Kind.entries) {
            val generic = ModelStore.fileFor(kind, "")
            val npu = ModelStore.fileFor(kind, "Qualcomm_SM8750")
            assertTrue("$kind: NPU-Fassung muss existieren", npu.soc == "Qualcomm_SM8750")
            assertTrue("$kind: NPU-Fassung ist größer", npu.bytes > generic.bytes)
        }
    }

    @Test
    fun sm8750BekommtDieNpuFassung() {
        // Galaxy S25 Ultra = Snapdragon 8 Elite = SM8750
        assertEquals("Qualcomm_SM8750", ModelStore.socTagFrom("QUALCOMM SM8750 qcom"))
        val v = ModelStore.fileFor(ModelStore.Kind.TEXT, "Qualcomm_SM8750")
        assertEquals("embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm", v.file)
        assertTrue(v.isNpu)
    }

    @Test
    fun unbekannterChipsatzBekommtDieNormaleDatei() {
        assertEquals("", ModelStore.socTagFrom("UNISOC T618 ums512"))
        val v = ModelStore.fileFor(ModelStore.Kind.TEXT, "")
        assertFalse(v.isNpu)
        assertEquals("embeddinggemma-2-text-270m.litertlm", v.file)
    }

    @Test
    fun angezeigteGroessePasstZuDenBytes() {
        // 484 622 336 Bytes = 462 MiB (nicht 1,3 GB)
        assertEquals(462, full.expectedMb)
        assertEquals(157, text.expectedMb)
        assertEquals(370, vision.expectedMb)
    }

    /** Die Prüfung selbst – dieselbe Rechnung wie `ModelStore.isComplete`. */
    private fun complete(name: String, length: Long): Boolean {
        val known = ModelStore.knownFile(name)
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

    /** Die Varianten sind aufsteigend sortiert (270M → 440M → 740M). */
    @Test
    fun reihenfolgeIstAufsteigend() {
        val sizes = ModelStore.variants().map { it.bytes }
        assertEquals(sizes.sorted(), sizes)
    }

    /** Nur die multimodalen Familien können Bilder einbetten (Personen-Klassifizierung). */
    @Test
    fun nurVisionFamilienKennenBilder() {
        assertFalse(ModelStore.Kind.TEXT.supportsImages)
        assertTrue(ModelStore.Kind.VISION.supportsImages)
        assertTrue(ModelStore.Kind.FULL.supportsImages)
    }

    /** Die URL zeigt auf die Datei des jeweiligen Chipsatzes. */
    @Test
    fun urlZeigtAufDieRichtigeDatei() {
        val v = ModelStore.Variant(ModelStore.Kind.TEXT, ModelStore.fileFor(ModelStore.Kind.TEXT, "Qualcomm_SM8750"))
        assertTrue(v.url.endsWith("embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm"))
        assertTrue(v.url.startsWith("https://huggingface.co/litert-community/"))
    }
}
