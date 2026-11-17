package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log
import java.util.concurrent.locks.ReentrantLock

/**
 * Zugriff auf das Embedding-Modell für die **Suche in eigenen Worten**.
 *
 * Standard ist [GemmaEmbedder] (**EmbeddingGemma 2** über MediaPipe Tasks Retrieval /
 * LiteRT-LM, GPU oder CPU). Fehlt das Modell, springt [LocalTextModel] ein: ein kleines
 * Wort-Bedeutungsmodell, das die App selbst mitbringt und das **ohne** Download sofort
 * funktioniert.
 *
 * **Zwei Regeln, die hier zentral gelten** (1.35):
 *
 *  1. **Es rechnet immer nur einer.** Die LiteRT-LM-Laufzeit ist nicht thread-sicher; vorher
 *     konnten der Index-Aufbau und eine Suche gleichzeitig `embedText` aufrufen – das Gerät
 *     blieb stehen. Jetzt serialisiert [embedLock] jeden Zugriff auf das Modell.
 *  2. **Fehler werden nicht verschluckt.** Wenn ein Modell da ist, aber nicht startet (z. B.
 *     weil die GPU-Stufe fehlt), steht der Grund in [Status.failure] und wird in der
 *     Bildersuche angezeigt – statt still auf das Wortmodell zu fallen.
 */
object AiEngine {

    /** Beschleunigung, mit der die Suche gerade rechnet. */
    enum class Accelerator(val label: String) {
        // NPU nur, wenn LiteRT-LM sie wirklich nutzt (Android stellt sie nicht einheitlich bereit)
        NPU("NPU"),
        GPU("GPU"),
        CPU("CPU"),
        LOCAL("Wortmodell")

        /** Rechnet dieses Modell auf der NPU? (ab 1.41 die einzige erlaubte Stufe) */
    }

    /**
     * Zustand der Suche – für die Anzeige in der Suchleiste.
     *
     * [modelInstalled] sagt, ob eine **Modelldatei** auf dem Gerät liegt. [failure] ist leer,
     * wenn alles läuft; sonst steht dort der Grund, warum das Modell nicht benutzt wird.
     */
    data class Status(
        val ready: Boolean,
        val accelerator: Accelerator,
        val modelName: String,
        val detail: String = "",
        val modelInstalled: Boolean = false,
        val failure: String = ""
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

    /**
     * Ein Schloss für **alle** Modellaufrufe. Der Index-Aufbau rechnet damit Foto für Foto;
     * eine Suche dazwischen bekommt das Schloss kurz, macht ihr Embedding und gibt es wieder
     * ab. Dadurch bleibt die Bedienung flüssig, ohne dass zwei Aufrufe die Laufzeit stören.
     */
    private val embedLock = ReentrantLock()

    fun currentStatus(): Status = status

    /**
     * **Selbsttest des Modells** – für die Fehlersuche auf dem Gerät.
     *
     * Lädt die Engine neu, bettet einen Satz ein und gibt zurück, was passiert ist. Damit
     * bekommt man eine Meldung wie „OK · UniversalEmbedder · GPU · 768 Dimensionen“ oder
     * „FEHLGESCHLAGEN · Klasse fehlt: …“ – statt nur „läuft nicht“.
     *
     * Aufruf aus einem Hintergrund-Thread.
     */
    fun selfTest(ctx: Context): Pair<Boolean, String> {
        val app = ctx.applicationContext
        if (!ModelStore.hasModel(app)) {
            return false to "Keine vollständige Modelldatei gefunden."
        }
        prepare(app, force = true)
        val st = currentStatus()
        val vec = embed("Ein Foto vom Strand bei Sonnenuntergang")
        return if (vec != null && vec.isNotEmpty()) {
            true to "${st.modelName} · ${st.detail} · ${st.accelerator.label} · ${vec.size} Dimensionen"
        } else {
            false to st.failure.ifBlank {
                "Das Modell hat keinen Vektor geliefert (unbekannter Grund)."
            }
        }
    }

    /** Grund, warum ein vorhandenes Modell nicht läuft (leer = kein Problem). */
    fun failureReason(): String = status.failure

    /**
     * Suchmaschine vorbereiten. Läuft im Hintergrund (Modell laden dauert je nach Gerät
     * 1–3 Sekunden, das 1,3-GB-Modell länger).
     *
     * [onReady] kommt auf dem Thread des Aufrufers.
     */
    fun prepare(ctx: Context, force: Boolean = false, onReady: ((Status) -> Unit)? = null) {
        if (!force && backend != null) {
            onReady?.invoke(status)
            return
        }
        synchronized(this) {
            if (backend != null && !force) {
                onReady?.invoke(status)
                return
            }
            val app = ctx.applicationContext

            if (force) {
                // Neu geladenes Modell soll sofort greifen – ohne dass gerade gerechnet wird.
                // (1.42: auch die Personen-Engine – sie könnte das neue Modell sein.)
                embedLock.lock()
                try {
                    runCatching { backend?.close() }
                    backend = null
                    runCatching { personsBackend?.close() }
                    personsBackend = null
                } finally {
                    embedLock.unlock()
                }
            }

            var chosen: EmbedderBackend? = null
            var st: Status? = null

            // 1) EmbeddingGemma 2 (GPU → CPU). Modell zuerst aus dem App-Speicher
            //    (heruntergeladen), dann aus dem App-Paket (assets/models).
            val gemma = runCatching { GemmaEmbedder.create(app) }
                .onFailure { Log.w("N3Ai", "Gemma-Start: ${it.message}") }
                .getOrNull()
            if (gemma != null) {
                chosen = gemma
                st = Status(true, gemma.accelerator, gemma.modelName, gemma.detail, true)
                Log.i("N3Ai", "Modellweg: ${GemmaEmbedder.lastRoute}")
            }

            // 2) Ersatz: eingebautes Wortmodell (immer verfügbar)
            if (chosen == null) {
                val local = LocalTextModel(app)
                chosen = local
                // Lag eine Modelldatei da, ist das ein Fehlerfall – Grund mitgeben.
                val installed = runCatching { ModelStore.hasModel(app) }.getOrDefault(false)
                st = Status(
                    ready = true,
                    accelerator = Accelerator.LOCAL,
                    modelName = local.modelName,
                    // 1.41: Es soll nur die NPU rechnen. Läuft sie nicht, sagt die App das
                    // deutlich – statt „Suche funktioniert“ mit dem Wortmodell vorzutäuschen.
                    detail = if (GemmaEmbedder.NPU_ONLY) {
                        "NPU nicht aktiv – es wird nur die Wortsuche benutzt"
                    } else {
                        local.detail
                    },
                    modelInstalled = installed,
                    failure = if (installed) GemmaEmbedder.lastFailure else ""
                )
            }

            backend = chosen
            status = st ?: Status(false, Accelerator.LOCAL, "", "", false)
            Log.i("N3Ai", "Suche bereit: ${status.modelName} auf ${status.accelerator.label}" +
                if (status.failure.isNotBlank()) " (Grund: ${status.failure})" else "")
            onReady?.invoke(status)
        }
    }

    /**
     * Vektor für einen Text – **serialisiert**. Wird das Modell gerade für ein anderes Foto
     * benutzt, wartet der Aufrufer hier (und nicht die Oberfläche).
     */
    fun embed(text: String): FloatArray? {
        val b = backend ?: return null
        if (text.isBlank()) return null
        embedLock.lock()
        return try {
            b.embed(text)
        } catch (t: Throwable) {
            Log.w("N3Ai", "Einbetten fehlgeschlagen: ${t.message}")
            null
        } finally {
            embedLock.unlock()
        }
    }

    /**
     * Vektor für einen **Index-Eintrag** (Fotosuche) – 1.42.
     *
     * EmbeddingGemma 2 ist auf Aufgabenbeschreibungen trainiert: Vor den eigentlichen Text
     * gehört der Hinweis, **was** eingebettet wird (Doku „LiteRT-LM embedding models“):
     *
     * ```
     * task: search result | text: <Inhalt>   ← beim Aufbau des Index
     * task: search query   | text: <Anfrage> ← bei der Suche
     * ```
     *
     * Ohne den Vorspann findet das Modell deutlich weniger passende Treffer. Das eingebaute
     * Wortmodell (Fallback) bekommt den Zusatz **nicht** – es bettet nackte Wörter ein.
     */
    fun embedIndex(text: String): FloatArray? =
        embedWithPrefix(text, "task: search result | text: ")

    /** Vektor für eine **Suchanfrage** – gleiche Regel wie bei [embedIndex]. */
    fun embedQuery(text: String): FloatArray? =
        embedWithPrefix(text, "task: search query | text: ")

    private fun embedWithPrefix(text: String, prefix: String): FloatArray? {
        val t = text.trim()
        if (t.isBlank()) return null
        return if (status.accelerator != Accelerator.LOCAL) embed(prefix + t) else embed(t)
    }

    /** Ist die Suche bereit? */
    fun ready(): Boolean = backend != null

    /** Rechnet gerade das eingebaute Wortmodell (kein EmbeddingGemma)? */
    fun usingFallback(): Boolean = status.accelerator == Accelerator.LOCAL

    // ------------------------------------------------------------------ 2. Modell: Personen (1.42)

    /**
     * **Zweites Modell – Personen-Klassifizierung.**
     *
     * Das ist eine **eigene Engine** für das multimodale Modell (EmbeddingGemma 2
     * Text+Bild 440M oder 740M): Sie bettet **Fotos** in Vektoren ein (InputData.Image),
     * während das Such-Modell Text einbettet. Beide rechnen auf der **NPU** – die
     * NPU-Fassung des jeweiligen Chipsatzes wird von [ModelStore] bevorzugt.
     *
     * Fehlt das Modell (noch nicht heruntergeladen), ist `personsBackend == null` und
     * [personsFailure] sagt, warum. Die Suche läuft unabhängig davon weiter.
     */
    @Volatile private var personsBackend: GemmaEmbedder? = null
    @Volatile private var personsFailure: String = ""
    @Volatile private var personsPreparing = false

    /** Grund, warum das Personen-Modell nicht läuft (leer = läuft oder noch nie versucht). */
    fun personsFailureReason(): String = personsFailure

    /** Ist ein Modell mit Bild-Einbettung auf dem Gerät (auch wenn die Engine noch nicht lief)? */
    fun hasPersonsModel(ctx: Context): Boolean =
        runCatching { ModelStore.hasVisionModel(ctx.applicationContext) }.getOrDefault(false)

    /**
     * Personen-Modell laden (Hintergrund-Thread). Mehrfache Aufrufe während des Ladens
     * werden zusammengefasst. [onReady] bekommt (läuft, Grund) auf dem Thread des Aufrufers.
     */
    fun preparePersons(ctx: Context, onReady: ((Boolean, String) -> Unit)? = null) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (personsBackend != null) {
                onReady?.invoke(true, "")
                return
            }
            if (personsPreparing) {
                onReady?.invoke(false, "wird geladen")
                return
            }
            personsPreparing = true
        }
        Thread({
            try {
                val built = runCatching { GemmaEmbedder.createForPersons(app) }.getOrNull()
                if (built != null) {
                    // Nicht zwei Engines gleichzeitig starten lassen: NPU-Initialisierungen
                    // müssen nacheinander passieren – embedLock hält das auseinander.
                    embedLock.lock()
                    try {
                        runCatching { personsBackend?.close() }
                        personsBackend = built
                        personsFailure = ""
                    } finally {
                        embedLock.unlock()
                    }
                    Log.i("N3Ai", "Personen-Modell bereit: ${GemmaEmbedder.lastRoute}")
                    onReady?.invoke(true, "")
                } else {
                    personsFailure = GemmaEmbedder.lastFailure.ifBlank { "Modell fehlt" }
                    Log.i("N3Ai", "Personen-Modell nicht verfügbar: $personsFailure")
                    onReady?.invoke(false, personsFailure)
                }
            } finally {
                personsPreparing = false
            }
        }, "n3-persons-model").start()
    }

    /**
     * Bild einbetten (JPEG/PNG-Bytes) – für die Personen-Klassifizierung.
     * Serialisiert über dasselbe Schloss wie die Textsuche: Die NPU rechnet nacheinander.
     */
    fun embedImage(bytes: ByteArray): FloatArray? {
        val b = personsBackend ?: return null
        embedLock.lock()
        return try {
            b.embedImage(bytes)
        } catch (t: Throwable) {
            Log.w("N3Ai", "Bild-Einbetten fehlgeschlagen: ${t.message}")
            null
        } finally {
            embedLock.unlock()
        }
    }

    /** Läuft das Personen-Modell (Engine gestartet)? */
    fun personsReady(): Boolean = personsBackend != null

    /** Personen-Engine verwerfen (z. B. nach dem Löschen der Modelldateien). */
    fun closePersons() {
        embedLock.lock()
        try {
            runCatching { personsBackend?.close() }
            personsBackend = null
        } finally {
            embedLock.unlock()
        }
    }

    fun close() {
        embedLock.lock()
        try {
            runCatching { backend?.close() }
            backend = null
        } finally {
            embedLock.unlock()
        }
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
