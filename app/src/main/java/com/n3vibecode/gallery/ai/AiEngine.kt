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

    /**
     * Zustand der Suche – für die Anzeige in der Suchleiste.
     *
     * [modelInstalled] sagt, ob ein **Gemma-Modell** vorhanden ist; ist es `false`, rechnet
     * die Suche mit dem eingebauten Wortmodell. Die Suchseite bietet in diesem Fall an, das
     * Modell zu holen.
     */
    data class Status(
        val ready: Boolean,
        val accelerator: Accelerator,
        val modelName: String,
        val detail: String = "",
        val modelInstalled: Boolean = false
    )

    /** Gemeinsame Schnittstelle aller Such-Engines (Gemma und das eingebaute Wortmodell). */
    internal interface EmbedderBackend : AutoCloseable {
        val accelerator: Accelerator
        val modelName: String
        val detail: String
        fun embed(text: String): FloatArray?
    }

    @Volatile private var backend: EmbedderBackend? = null
    @Volatile private var status: Status = Status(false, Accelerator.LOCAL, "", "", false)

    fun currentStatus(): Status = status

    /**
     * Suchmaschine vorbereiten. Wird beim ersten Öffnen der Suche aufgerufen und läuft im
     * Hintergrund (Modell laden dauert je nach Gerät 1–3 Sekunden, auf der NPU kürzer).
     *
     * [onReady] kommt auf einem Hintergrund-Thread.
     */
    fun prepare(ctx: Context, force: Boolean = false, onReady: ((Status) -> Unit)? = null) {
        if (!force) {
            backend?.let {
                onReady?.invoke(status)
                return
            }
        }
        synchronized(this) {
            if (backend != null && !force) {
                onReady?.invoke(status)
                return
            }
            if (force) {
                // Neu geladenes Modell soll sofort greifen
                runCatching { backend?.close() }
                backend = null
            }
            val app = ctx.applicationContext
            var chosen: EmbedderBackend? = null
            var st: Status? = null

            // 1) Gemma-Embedding (NPU → GPU → CPU).
            //    Modell zuerst aus dem App-Speicher (heruntergeladen), dann aus dem
            //    App-Paket (assets/models, falls beim Bauen eins beigelegt wurde).
            runCatching { GemmaEmbedder.create(app) }.getOrNull()?.let { gemma ->
                chosen = gemma
                st = Status(true, gemma.accelerator, gemma.modelName, gemma.detail, true)
            }

            // 2) Ersatz: eingebautes Wortmodell (immer verfügbar)
            if (chosen == null) {
                val local = LocalTextModel(app)
                chosen = local
                st = Status(true, Accelerator.LOCAL, local.modelName, local.detail, false)
            }

            backend = chosen
            status = st ?: Status(false, Accelerator.LOCAL, "", "", false)
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

        /** Reihenfolge = Vorliebe: großes Modell (Text+Bild) zuerst, dann kleiner. */
        val CANDIDATES = listOf(
            "embeddinggemma-2-740m.litertlm",
            "embeddinggemma-2-text-vision-440m.litertlm",
            "embeddinggemma-2-text-270m.litertlm",
            "embedding_gemma.task"
        )
    }
}
