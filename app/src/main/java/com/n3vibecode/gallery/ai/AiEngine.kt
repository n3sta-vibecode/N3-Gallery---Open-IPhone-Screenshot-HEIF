package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log

/**
 * Zugriff auf das Sprachmodell für die **Suche in eigenen Worten** – wie „bunte Bilder vom
 * Strand“ statt nur Dateinamen.
 *
 * Standard ist [GemmaEmbedder] (Gemma-Embedding-Modell über MediaPipe, läuft auf NPU, GPU
 * oder CPU). Fehlt das Modell auf dem Gerät, springt [LocalTextModel] ein: ein kleines
 * Wort-Bedeutungsmodell, das die App selbst mitbringt und das **ohne** zusätzlichen Download
 * sofort funktioniert. Damit ist die Suche nie „kaputt“, sondern höchstens weniger klug –
 * und wer das volle Modell hat, bekommt die beste Qualität.
 *
 * **Woher kommt das Modell?** Die App hat bewusst keine Internet-Berechtigung, kann also
 * nichts nachladen. Das Gemma-Modell wird deshalb vor dem Bauen in den Ordner
 * `app/src/main/assets/models/` gelegt (siehe `README-KI.md`) – dann ist es im App-Paket
 * enthalten und läuft vollständig offline.
 */
object AiEngine {

    /** Beschleunigung, mit der die Suche gerade rechnet. */
    enum class Accelerator(val label: String) {
        NPU("NPU"),
        GPU("GPU"),
        CPU("CPU"),
        LOCAL("Wortmodell")
    }

    /** Zustand der Suche – für die Anzeige in der Suchleiste. */
    data class Status(
        val ready: Boolean,
        val accelerator: Accelerator,
        val modelName: String,
        val detail: String = ""
    )

    /** Gemeinsame Schnittstelle aller Such-Engines (Gemma und das eingebaute Wortmodell). */
    internal interface EmbedderBackend : AutoCloseable {
        val accelerator: Accelerator
        val modelName: String
        val detail: String
        fun embed(text: String): FloatArray?
    }

    @Volatile private var backend: EmbedderBackend? = null
    @Volatile private var status: Status = Status(false, Accelerator.LOCAL, "", "")

    fun currentStatus(): Status = status

    /**
     * Suchmaschine vorbereiten. Wird beim ersten Öffnen der Suche aufgerufen und läuft im
     * Hintergrund (Modell laden dauert je nach Gerät 1–3 Sekunden, auf der NPU kürzer).
     *
     * [onReady] kommt auf einem Hintergrund-Thread.
     */
    fun prepare(ctx: Context, onReady: ((Status) -> Unit)? = null) {
        backend?.let {
            onReady?.invoke(status)
            return
        }
        synchronized(this) {
            if (backend != null) {
                onReady?.invoke(status)
                return
            }
            val app = ctx.applicationContext
            var chosen: EmbedderBackend? = null
            var st: Status? = null

            // 1) Gemma-Embedding (NPU → GPU → CPU)
            runCatching { GemmaEmbedder.create(app) }.getOrNull()?.let { gemma ->
                chosen = gemma
                st = Status(true, gemma.accelerator, gemma.modelName, gemma.detail)
            }

            // 2) Ersatz: eingebautes Wortmodell (immer verfügbar)
            if (chosen == null) {
                val local = LocalTextModel(app)
                chosen = local
                st = Status(true, Accelerator.LOCAL, local.modelName, local.detail)
            }

            backend = chosen
            status = st ?: Status(false, Accelerator.LOCAL, "", "")
            Log.i("N3Ai", "Suche bereit: ${status.modelName} auf ${status.accelerator.label}")
            onReady?.invoke(status)
        }
    }

    /** Vektor für einen Text (Semantik). Null, wenn nichts bereit ist. */
    fun embed(text: String): FloatArray? = backend?.embed(text)

    /** Ist die Suche bereit? */
    fun ready(): Boolean = backend != null

    fun close() {
        runCatching { backend?.close() }
        backend = null
    }

    // ------------------------------------------------------------------ Modell-Suche

    /**
     * Suchbegriffe, die zum Modell gehören. Das Gemma-Modell erwartet von sich aus eine
     * Aufgabenbeschreibung; die Formate sind die aus der MediaPipe-Dokumentation.
     */
    object Prompt {
        const val ASSET_DIR = "models"
        /** Reihenfolge = Vorliebe (NPU-taugliche Variante zuerst). */
        val CANDIDATES = listOf(
            "gemma_embedding_npu.task",
            "embedding_gemma.task",
            "gemma_embedding.task",
            "embedding_gemma_300m.task",
            "universal_sentence_encoder.tflite"
        )
    }
}
