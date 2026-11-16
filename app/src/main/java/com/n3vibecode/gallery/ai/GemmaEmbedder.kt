package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log

/**
 * **EmbeddingGemma 2** auf dem Gerät – das Modell, das Google in „Edge Gallery“ für
 * „Instant Media Search“ einsetzt (740 M Parameter, multimodal, Apache 2.0).
 *
 * Ablauf:
 *  • Das Modell liegt als `.litertlm`-Paket im App-Speicher (siehe [ModelStore]) oder im
 *    App-Paket (`assets/models/…`).
 *  • Gerechnet wird über **MediaPipe Tasks Retrieval** (`com.google.mediapipe:tasks-retrieval`,
 *    Klasse `UniversalEmbedder`), das intern die **LiteRT-LM**-Laufzeit startet.
 *  • Beschleuniger: **GPU**, sonst **CPU**. (LiteRT-LM adressiert die NPU derzeit nicht
 *    einheitlich über MediaPipe; wenn keine GPU da ist, übernimmt die CPU – das Modell ist
 *    klein genug, dass die Suche auch dort flott bleibt.)
 *  • Jede Stufe wird mit einem Testlauf geprüft; erst dann gilt sie als aktiv.
 *
 * Alles läuft per Reflection: So baut die App auch dann, wenn die Bibliothek fehlt, und
 * nutzt automatisch das eingebaute Wortmodell ([LocalTextModel]).
 */
internal class GemmaEmbedder(
    /** Das MediaPipe-Objekt (UniversalEmbedder) – als Any, damit ohne Bibliothek kompiliert wird. */
    private val modelHandle: Any,
    override val accelerator: AiEngine.Accelerator,
    override val modelName: String,
    override val detail: String,
    /** Embedding-Pipeline aufrufen (per Reflection). */
    private val embedFn: (String) -> FloatArray?
) : AiEngine.EmbedderBackend {

    override fun embed(text: String): FloatArray? = try {
        embedFn(text)
    } catch (t: Throwable) {
        Log.w("N3Ai", "Einbetten fehlgeschlagen: ${t.message}")
        null
    }

    override fun close() {
        runCatching { modelHandle.javaClass.getMethod("close").invoke(modelHandle) }
    }

    companion object {

        private const val TAG = "N3Ai"

        /** Woher das Modell kommt: App-Speicher (Download) oder App-Paket (assets). */
        internal sealed class ModelSource {
            abstract val label: String
            abstract val name: String

            /** Datei im App-Speicher – z. B. `files/models/embeddinggemma-2-…litertlm`. */
            data class File(val file: java.io.File) : ModelSource() {
                override val label: String get() = file.absolutePath
                override val name: String get() = file.name
            }

            /** Datei im App-Paket – z. B. `models/embeddinggemma-2-…litertlm`. */
            data class Asset(val path: String) : ModelSource() {
                override val label: String get() = path
                override val name: String get() = path.substringAfterLast('/')
            }
        }

        /**
         * Erstellt die Engine oder gibt `null` zurück, wenn kein Modell vorliegt bzw. die
         * Bibliothek fehlt. Der Aufrufer nimmt dann das eingebaute Wortmodell.
         */
        fun create(ctx: Context): GemmaEmbedder? {
            val source: ModelSource = ModelStore.best(ctx)?.let { ModelSource.File(it) }
                ?: findBundledModel(ctx)?.let { ModelSource.Asset(it) }
                ?: run {
                    Log.i(TAG, "Kein EmbeddingGemma-2-Modell vorhanden – Suche nutzt das eingebaute Wortmodell.")
                    return null
                }
            val engineClass = runCatching {
                Class.forName("com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder")
            }.getOrNull() ?: run {
                Log.i(TAG, "tasks-retrieval nicht eingebunden – Suche nutzt das eingebaute Wortmodell.")
                return null
            }
            // GPU zuerst (schnell), dann CPU (immer vorhanden).
            val order = listOf(
                AiEngine.Accelerator.GPU to "GPU",
                AiEngine.Accelerator.CPU to "CPU"
            )
            for ((acc, delegateName) in order) {
                val built = runCatching { build(ctx, engineClass, source, delegateName, acc) }
                    .onFailure { Log.i(TAG, "${acc.label} nicht möglich: ${it.message}") }
                    .getOrNull() ?: continue
                // Kurzer Testlauf – erst dann gilt der Beschleuniger als nutzbar
                val ok = runCatching { built.embedFn("Test") }.getOrNull()?.isNotEmpty() == true
                if (ok) {
                    Log.i(TAG, "EmbeddingGemma 2 läuft auf ${acc.label} (${source.name})")
                    return built
                }
                runCatching { built.close() }
            }
            Log.w(TAG, "Modell ${source.name} ließ sich mit keinem Beschleuniger starten.")
            return null
        }

        /** Sucht ein beigelegtes Modell in `assets/models` (.litertlm bevorzugt). */
        private fun findBundledModel(ctx: Context): String? {
            val files = runCatching { ctx.assets.list(AiEngine.Prompt.ASSET_DIR)?.toList() }
                .getOrNull() ?: return null
            for (name in AiEngine.Prompt.CANDIDATES) {
                if (files.contains(name)) return "${AiEngine.Prompt.ASSET_DIR}/$name"
            }
            return files.firstOrNull { it.endsWith(".litertlm", true) || it.endsWith(".task", true) }
                ?.let { "${AiEngine.Prompt.ASSET_DIR}/$it" }
        }

        /** Baut die Engine über MediaPipe (alles per Reflection). */
        private fun build(
            context: Context,
            engineClass: Class<*>,
            source: ModelSource,
            delegateName: String,
            accelerator: AiEngine.Accelerator
        ): GemmaEmbedder? {
            val baseOptionsClass = Class.forName("com.google.mediapipe.tasks.core.BaseOptions")
            val delegateClass = Class.forName("com.google.mediapipe.tasks.core.Delegate")
            val delegate = runCatching {
                java.lang.Enum.valueOf(delegateClass as Class<out Enum<*>>, delegateName)
            }.getOrNull() ?: return null

            // BaseOptions.builder() → Modell setzen → Delegate setzen → build()
            val baseBuilder = baseOptionsClass.getMethod("builder").invoke(null) ?: return null
            when (source) {
                is ModelSource.Asset ->
                    baseBuilder.javaClass.getMethod("setModelAssetPath", String::class.java)
                        .invoke(baseBuilder, source.path)

                is ModelSource.File ->
                    baseBuilder.javaClass.getMethod("setModelAssetPath", String::class.java)
                        .invoke(baseBuilder, source.file.absolutePath)
            }
            baseBuilder.javaClass.getMethod("setDelegate", delegateClass).invoke(baseBuilder, delegate)
            val baseOptions = baseBuilder.javaClass.getMethod("build").invoke(baseBuilder) ?: return null

            val optionsClass = Class.forName(
                "com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions"
            )
            val optBuilder = optionsClass.getMethod("builder").invoke(null) ?: return null
            optBuilder.javaClass.getMethod("setBaseOptions", baseOptionsClass)
                .invoke(optBuilder, baseOptions)
            // Text-Beschleuniger (das Vision-/Audio-Delegate bleibt auf CPU – wir nutzen nur Text)
            runCatching {
                optBuilder.javaClass.getMethod("setTextDelegate", delegateClass)
                    .invoke(optBuilder, delegate)
            }
            // Vektoren normalisieren (kürzere Vergleichszeit, gleiche Qualität)
            runCatching {
                optBuilder.javaClass.getMethod("setL2Normalize", Boolean::class.javaPrimitiveType)
                    .invoke(optBuilder, true)
            }
            val options = optBuilder.javaClass.getMethod("build").invoke(optBuilder) ?: return null

            val engine = engineClass.getMethod(
                "createFromOptions", Context::class.java, optionsClass
            ).invoke(null, context, options) ?: return null

            // embedText(String) → EmbeddingResult.embeddings() → [0].floatEmbedding()
            val embedMethod = engineClass.getMethod("embedText", String::class.java)
            val embedFn: (String) -> FloatArray? = { text ->
                runCatching {
                    val result = embedMethod.invoke(engine, text)
                    val embeddings = result.javaClass.getMethod("embeddings").invoke(result)
                    val first = (embeddings as? List<*>)?.firstOrNull()
                    first?.javaClass?.getMethod("floatEmbedding")?.invoke(first) as? FloatArray
                }.getOrNull()
            }

            val friendly = "EmbeddingGemma 2"
            val detail = when (accelerator) {
                AiEngine.Accelerator.GPU -> "GPU-Beschleunigung"
                AiEngine.Accelerator.CPU -> "CPU (kein GPU-Beschleuniger)"
                else -> accelerator.label
            }
            return GemmaEmbedder(engine, accelerator, friendly, detail, embedFn)
        }
    }
}
