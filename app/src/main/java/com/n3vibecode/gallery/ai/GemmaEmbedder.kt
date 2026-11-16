package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log

/**
 * **EmbeddingGemma 2** auf dem Gerät – das Modell, das Google in „Edge Gallery“ für
 * „Instant Media Search“ einsetzt (740 M Parameter, multimodal, Apache 2.0).
 *
 * Aufbau (an die echte MediaPipe-API angelehnt):
 *
 * ```java
 * BaseOptions.builder().setModelAssetPath(pfad).setDelegate(Delegate.CPU).build()
 * UniversalEmbedderOptions.builder().setBaseOptions(bo)
 *     .setTextDelegate(Delegate.CPU).setL2Normalize(true).build()
 * UniversalEmbedder.createFromOptions(context, options)
 * embedText("…").embeddings().get(0).floatEmbedding()   // 768 floats
 * ```
 *
 * **Was in 1.35 repariert wurde:** Das Modell wurde zwar geladen, aber nie benutzt. Ursache
 * war die Reflection: `BaseOptions.Builder` und `UniversalEmbedderOptions.Builder` sind
 * `abstract` – `getMethod("build")` findet die Methode dort **nicht**, weil sie in
 * `AutoValue_…Builder` steckt. Deshalb scheiterte jede Stufe und die Suche fiel still auf das
 * Wortmodell zurück. Jetzt werden die Methoden in der Klassenhierarchie gesucht, jeder
 * Fehlversuch wird in [lastFailure] festgehalten und in der App angezeigt.
 *
 * Reflection (statt direktem Import) bleibt: So baut die App auch dann, wenn die Bibliothek
 * fehlt, und die Suche funktioniert notfalls mit dem eingebauten Wortmodell.
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

    override fun embed(text: String): FloatArray? = embedFn(text)

    override fun close() {
        runCatching {
            findMethod(modelHandle.javaClass, "close", arrayOf<Any?>())?.invoke(modelHandle)
        }
    }

    companion object {

        private const val TAG = "N3Ai"

        private const val CLASS_EMBEDDER =
            "com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder"
        private const val CLASS_OPTIONS =
            "com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions"
        private const val CLASS_BASE_OPTIONS = "com.google.mediapipe.tasks.core.BaseOptions"
        private const val CLASS_DELEGATE = "com.google.mediapipe.tasks.core.Delegate"

        /** Warum das Modell nicht läuft – für die Anzeige in der Bildersuche. */
        @Volatile
        var lastFailure: String = ""
            private set

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
            lastFailure = ""
            val source: ModelSource = ModelStore.best(ctx)?.let { ModelSource.File(it) }
                ?: findBundledModel(ctx)?.let { ModelSource.Asset(it) }
                ?: run {
                    Log.i(TAG, "Kein EmbeddingGemma-2-Modell vorhanden – Suche nutzt das eingebaute Wortmodell.")
                    lastFailure = "kein Modell im Gerät"
                    return null
                }

            val engineClass = runCatching { Class.forName(CLASS_EMBEDDER) }.getOrNull()
            if (engineClass == null) {
                Log.i(TAG, "tasks-retrieval nicht eingebunden – Suche nutzt das eingebaute Wortmodell.")
                lastFailure = "tasks-retrieval fehlt im Build"
                return null
            }

            // GPU zuerst (schnell), dann CPU (immer vorhanden).
            val order = listOf(
                AiEngine.Accelerator.GPU to "GPU",
                AiEngine.Accelerator.CPU to "CPU"
            )
            val reasons = ArrayList<String>(2)
            for ((acc, delegateName) in order) {
                val built = runCatching {
                    build(ctx, engineClass, source, delegateName, acc)
                }.onFailure {
                    val msg = it.cause?.message ?: it.message ?: it.javaClass.simpleName
                    reasons.add("${acc.label}: $msg")
                    Log.w(TAG, "${acc.label} nicht möglich: $msg", it)
                }.getOrNull()

                if (built == null) {
                    if (reasons.isEmpty()) reasons.add("${acc.label}: Aufbau fehlgeschlagen")
                    continue
                }
                // Kurzer Testlauf – erst dann gilt der Beschleuniger als nutzbar
                val probe = runCatching { built.embedFn("Test") }
                val vector = probe.getOrNull()
                if (vector != null && vector.isNotEmpty()) {
                    lastFailure = ""
                    Log.i(TAG, "EmbeddingGemma 2 läuft auf ${acc.label} (${source.name}, " +
                        "${vector.size} Dimensionen)")
                    return built
                }
                val why = probe.exceptionOrNull()?.let {
                    it.cause?.message ?: it.message ?: it.javaClass.simpleName
                } ?: "leerer Vektor"
                reasons.add("${acc.label}: Testlauf → $why")
                Log.w(TAG, "${acc.label}: Testlauf fehlgeschlagen – $why")
                runCatching { built.close() }
            }
            lastFailure = reasons.joinToString(" · ").ifBlank { "Modell konnte nicht gestartet werden" }
            Log.w(TAG, "Modell ${source.name} ließ sich mit keinem Beschleuniger starten: $lastFailure")
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

        /**
         * Methode in der ganzen Klassenhierarchie suchen – **mit passender Signatur**.
         *
         * Zwei Dinge, die hier schiefgehen können und in 1.36 beide behoben sind:
         *
         *  1. `getMethod`/`getDeclaredMethod` vergleichen die **deklarierten** Parametertypen.
         *     Unser Argument ist aber ein `AutoValue_BaseOptions`, während der Parameter
         *     `BaseOptions` heißt – exakt gleich sind die Typen nie. Deshalb werden alle
         *     Methoden mit dem Namen genommen und die **zugewiesenen** Parameter geprüft
         *     (`isInstance`).
         *  2. `build()` steckt nicht im abstrakten Builder, sondern in der erzeugten
         *     `AutoValue_…Builder` – daher die Suche über die ganze Oberklassen-Kette.
         */
        private fun findMethod(target: Class<*>, name: String, args: Array<out Any?>): java.lang.reflect.Method? {
            val wanted = args.map { it?.javaClass }
            var c: Class<*>? = target
            while (c != null) {
                for (m in c.declaredMethods) {
                    if (m.name != name) continue
                    if (m.parameterTypes.size != wanted.size) continue
                    val fits = m.parameterTypes.indices.all { i ->
                        val declared = m.parameterTypes[i].let { if (it.isPrimitive) boxed(it) else it }
                        val value = wanted[i]
                        if (value == null) !declared.isPrimitive else declared.isAssignableFrom(value)
                    }
                    if (!fits) continue
                    runCatching { m.isAccessible = true }
                    return m
                }
                c = c.superclass
            }
            return null
        }

        /** `int` → `Integer` usw. – Reflection liefert für primitive Parameter eigene Klassen. */
        private fun boxed(c: Class<*>): Class<*> = when (c) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> c
        }

        /**
         * Ruft eine Methode auf: erst mit Argumenten, dann ohne (z. B. `build()` über
         * `builder()`, das ja selbst keine Parameter hat).
         */
        private fun Any.call(name: String, vararg args: Any?): Any? {
            val m = findMethod(javaClass, name, args)
                ?: throw NoSuchMethodException(
                    "$name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }}) an ${javaClass.simpleName}"
                )
            runCatching { m.isAccessible = true }
            return try {
                m.invoke(this, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause ?: e
            }
        }

        /** Statischer Aufruf, z. B. `BaseOptions.builder()`. */
        private fun callStatic(target: Class<*>, name: String, vararg args: Any?): Any? {
            val m = findMethod(target, name, args)
                ?: throw NoSuchMethodException("$name an ${target.simpleName}")
            runCatching { m.isAccessible = true }
            return try {
                m.invoke(null, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause ?: e
            }
        }

        /** Baut die Engine über MediaPipe (alles per Reflection, aber robust). */
        private fun build(
            context: Context,
            engineClass: Class<*>,
            source: ModelSource,
            delegateName: String,
            accelerator: AiEngine.Accelerator
        ): GemmaEmbedder? {
            val baseOptionsClass = Class.forName(CLASS_BASE_OPTIONS)
            val delegateClass = Class.forName(CLASS_DELEGATE)
            @Suppress("UNCHECKED_CAST")
            val delegate = java.lang.Enum.valueOf(
                delegateClass as Class<out Enum<*>>, delegateName
            )

            // BaseOptions.builder() → Modellpfad → Delegate → build()
            val baseBuilder = callStatic(baseOptionsClass, "builder")
                ?: throw IllegalStateException("BaseOptions.builder() fehlt")
            val path = when (source) {
                is ModelSource.Asset -> source.path
                is ModelSource.File -> source.file.absolutePath
            }
            baseBuilder.call("setModelAssetPath", path)
            baseBuilder.call("setDelegate", delegate)
            val baseOptions = baseBuilder.call("build") ?: throw IllegalStateException("BaseOptions build")

            val optionsClass = Class.forName(CLASS_OPTIONS)
            val optBuilder = callStatic(optionsClass, "builder")
                ?: throw IllegalStateException("UniversalEmbedderOptions.builder() fehlt")
            optBuilder.call("setBaseOptions", baseOptions)
            // Nur Text wird eingebettet: Text-Beschleuniger setzen (Vision/Audio bleiben Default).
            runCatching { optBuilder.call("setTextDelegate", delegate) }
            // Vektoren normalisieren (kürzere Vergleichszeit, gleiche Qualität)
            runCatching { optBuilder.call("setL2Normalize", true) }
            val options = optBuilder.call("build") ?: throw IllegalStateException("Options build")

            val engine = callStatic(engineClass, "createFromOptions", context, options)
                ?: throw IllegalStateException("Embedder nicht erzeugt")

            val embedMethod = findMethod(engineClass, "embedText", arrayOf<Any?>("x"))
                ?: throw IllegalStateException("embedText fehlt")
            val embedFn: (String) -> FloatArray? = { text ->
                val result = embedMethod.invoke(engine, text) ?: error("kein Ergebnis")
                val embeddings = result.call("embeddings") as? List<*>
                    ?: error("embeddings() fehlt")
                val first = embeddings.firstOrNull() ?: error("leeres Ergebnis")
                first.call("floatEmbedding") as? FloatArray
            }

            val detail = when (accelerator) {
                AiEngine.Accelerator.GPU -> "GPU-Beschleunigung"
                AiEngine.Accelerator.CPU -> "CPU (keine GPU)"
                else -> accelerator.label
            }
            return GemmaEmbedder(engine, accelerator, "EmbeddingGemma 2", detail, embedFn)
        }
    }
}
