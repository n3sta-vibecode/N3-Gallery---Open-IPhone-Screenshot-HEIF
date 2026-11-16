package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log

/**
 * **Gemma-Embedding auf dem Gerät** – die Suchmaschine, die Google in „Edge Gallery“
 * (Google Fotos auf dem Gerät / AI Edge) einsetzt.
 *
 * Ablauf:
 *  • Das Modell liegt im App-Paket (`assets/models/…task`) – kein Download, kein Internet.
 *  • Gerechnet wird über **MediaPipe Tasks** (`com.google.mediapipe:tasks-text`), das den
 *    LiteRT-Interpreter mit dem passenden Beschleuniger startet.
 *  • Reihenfolge der Beschleuniger: **NPU** (falls eine NPU-Bibliothek eingebunden ist und das
 *    Modell NPU-tauglich ist) → **GPU** → **CPU**. Es wird jeweils getestet, ob das Modell
 *    damit wirklich läuft; sonst geht es eine Stufe zurück. So funktioniert die Suche auf
 *    jedem Gerät, nutzt aber die NPU, wenn eine da ist.
 *
 * Die Suche selbst (Texte einbetten, Ähnlichkeit vergleichen) passiert in
 * [SearchIndex] – hier steckt nur das Modell.
 */
internal class GemmaEmbedder(
    /** Das MediaPipe-Objekt (TextEmbedder) – als Any, damit ohne Modell-Bibliothek kompiliert wird. */
    private val modelHandle: Any,
    override val accelerator: AiEngine.Accelerator,
    override val modelName: String,
    override val detail: String,
    /** Embedding-Pipeline aufrufen (per Reflection, damit die App ohne Modell-Bibliothek baut). */
    private val embedFn: (String) -> FloatArray?
) : AiEngine.EmbedderBackend {

    override fun embed(text: String): FloatArray? = try {
        embedFn(text)
    } catch (t: Throwable) {
        Log.w("N3Ai", "Einbetten fehlgeschlagen: ${t.message}")
        null
    }

    override fun close() {
        runCatching {
            modelHandle.javaClass.getMethod("close").invoke(modelHandle)
        }
    }

    /** Woher das Modell kommt: App-Speicher (Download) oder App-Paket (assets). */
    internal sealed class ModelSource {
        abstract val label: String
        abstract val name: String

        /** Datei im App-Speicher – z. B. `files/models/embedding_gemma.task`. */
        data class File(val file: java.io.File) : ModelSource() {
            override val label: String get() = file.absolutePath
            override val name: String get() = file.name
        }

        /** Datei im App-Paket – z. B. `models/embedding_gemma.task`. */
        data class Asset(val path: String) : ModelSource() {
            override val label: String get() = path
            override val name: String get() = path.substringAfterLast('/')
        }
    }

    companion object {

        private const val TAG = "N3Ai"

        /**
         * Erstellt die Engine oder gibt `null` zurück, wenn kein Gemma-Modell im App-Paket
         * liegt bzw. die MediaPipe-Bibliothek fehlt. Der Aufrufer nimmt dann das eingebaute
         * Wortmodell ([LocalTextModel]).
         */
        fun create(ctx: Context): GemmaEmbedder? {
            // 1) Modell im App-Speicher (vom Nutzer geholt, siehe ModelStore)
            // 2) Modell im App-Paket (assets/models, falls beim Bauen beigelegt)
            val source: ModelSource = ModelStore.best(ctx)?.let { ModelSource.File(it) }
                ?: findModelAsset(ctx)?.let { ModelSource.Asset(it) }
                ?: run {
                    Log.i(TAG, "Kein Gemma-Modell vorhanden – Suche nutzt das eingebaute Wortmodell.")
                    return null
                }
            val engineClass = runCatching {
                Class.forName("com.google.mediapipe.tasks.text.textembedder.TextEmbedder")
            }.getOrNull() ?: run {
                Log.i(TAG, "MediaPipe-Bibliothek nicht eingebunden – Suche nutzt das eingebaute Wortmodell.")
                return null
            }
            // Beschleuniger von schnell nach langsam. NPU ist ein eigener Wert von
            // MediaPipe (Delegate.NPU); fehlt er in der eingebundenen Version, wird die
            // Konstante einfach übersprungen.
            val order = listOf(
                AiEngine.Accelerator.NPU to "NPU",
                AiEngine.Accelerator.GPU to "GPU",
                AiEngine.Accelerator.CPU to "CPU"
            )
            for ((acc, delegateName) in order) {
                val built = runCatching { build(ctx, engineClass, source, delegateName, acc) }
                    .onFailure { Log.i(TAG, "${acc.label} nicht möglich: ${it.message}") }
                    .getOrNull()
                if (built != null) {
                    // Kurzer Testlauf (kleiner Satz) – erst dann gilt der Beschleuniger als nutzbar
                    val ok = runCatching { built.embedFn("Test") }.getOrNull()?.isNotEmpty() == true
                    if (ok) {
                        Log.i(TAG, "Gemma-Embedding läuft auf ${acc.label} (${source.label})")
                        return built
                    }
                    runCatching { built.close() }
                }
            }
            Log.w(TAG, "Modell ${source.label} ließ sich mit keinem Beschleuniger starten.")
            return null
        }

        /** Sucht das erste vorhandene Modell in `assets/models`. */
        private fun findModelAsset(ctx: Context): String? {
            val files = runCatching { ctx.assets.list(AiEngine.Prompt.ASSET_DIR)?.toList() }
                .getOrNull() ?: return null
            for (name in AiEngine.Prompt.CANDIDATES) {
                if (files.contains(name)) return "${AiEngine.Prompt.ASSET_DIR}/$name"
            }
            // Unbekannter Dateiname: trotzdem nehmen, wenn es ein passendes Format ist
            return files.firstOrNull { it.endsWith(".task") || it.endsWith(".tflite") }
                ?.let { "${AiEngine.Prompt.ASSET_DIR}/$it" }
        }

        /**
         * Baut die Engine über MediaPipe (alles per Reflection, damit die App auch ohne die
         * Bibliothek kompiliert und dann automatisch das Wortmodell nutzt).
         */
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

            // BaseOptions.builder() → BaseOptions.Builder
            val baseBuilder = baseOptionsClass.getMethod("builder").invoke(null) ?: return null
            when (source) {
                is ModelSource.Asset ->
                    baseBuilder.javaClass.getMethod("setModelAssetPath", String::class.java)
                        .invoke(baseBuilder, source.path)
                is ModelSource.File ->
                    // Datei im App-Speicher: MediaPipe nimmt den Pfad; fehlt die Methode in
                    // älteren Versionen, wird bei fehlender Schreibrechte-Kopie die
                    // Modell-Datei über den Deskriptor übergeben.
                    runCatching {
                        baseBuilder.javaClass.getMethod("setModelAssetPath", String::class.java)
                            .invoke(baseBuilder, source.file.absolutePath)
                    }.getOrElse {
                        context.contentResolver.openFileDescriptor(
                            android.net.Uri.fromFile(source.file), "r"
                        )?.use { pfd ->
                            baseBuilder.javaClass.getMethod(
                                "setModelAssetFileDescriptor",
                                android.os.ParcelFileDescriptor::class.java
                            ).invoke(baseBuilder, pfd)
                        } ?: return null
                    }
            }
            baseBuilder.javaClass.getMethod("setDelegate", delegateClass).invoke(baseBuilder, delegate)
            val baseOptions = baseBuilder.javaClass.getMethod("build").invoke(baseBuilder) ?: return null

            // TextEmbedder.TextEmbedderOptions.builder() → Builder
            val optionsClass = Class.forName(
                "com.google.mediapipe.tasks.text.textembedder.TextEmbedder\$TextEmbedderOptions"
            )
            val optBuilder = optionsClass.getMethod("builder").invoke(null) ?: return null
            optBuilder.javaClass.getMethod("setBaseOptions", baseOptionsClass)
                .invoke(optBuilder, baseOptions)
            // Vektoren normalisieren (kürzere Vergleichszeit, gleiche Qualität)
            runCatching {
                optBuilder.javaClass.getMethod("setL2Normalize", Boolean::class.javaPrimitiveType)
                    .invoke(optBuilder, true)
            }
            val options = optBuilder.javaClass.getMethod("build").invoke(optBuilder) ?: return null

            val engine = engineClass.getMethod(
                "createFromOptions", Context::class.java, optionsClass
            ).invoke(null, context, options) ?: return null

            val embedMethod = engineClass.getMethod("embed", String::class.java)
            // Ergebnis von MediaPipe auspacken: TextEmbedderResult → embeddingResult()
            //   → embeddings() → [0].floatEmbedding()
            val embedFn: (String) -> FloatArray? = { text ->
                runCatching {
                    val result = embedMethod.invoke(engine, text)
                    val embeddingResult = result.javaClass.getMethod("embeddingResult").invoke(result)
                    val embeddings = embeddingResult.javaClass.getMethod("embeddings").invoke(embeddingResult)
                    val first = (embeddings as? List<*>)?.firstOrNull()
                    first?.javaClass?.getMethod("floatEmbedding")?.invoke(first) as? FloatArray
                }.getOrNull()
            }

            val modelName = source.name
            val friendly = when {
                modelName.contains("gemma", ignoreCase = true) -> "Gemma Embedding"
                modelName.contains("universal", ignoreCase = true) -> "Universal Sentence Encoder"
                else -> modelName
            }
            val detail = if (accelerator == AiEngine.Accelerator.NPU) {
                "auf der NPU"
            } else "${accelerator.label}-Beschleunigung"

            return GemmaEmbedder(engine, accelerator, friendly, detail, embedFn)
        }
    }
}
