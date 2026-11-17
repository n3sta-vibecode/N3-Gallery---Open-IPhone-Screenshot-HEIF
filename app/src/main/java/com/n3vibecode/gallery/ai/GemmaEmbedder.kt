package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * **EmbeddingGemma 2** auf dem Gerät – das Modell, das Google in „Edge Gallery“ für
 * „Instant Media Search“ einsetzt (multimodal, Apache 2.0), als `.litertlm`-Paket.
 *
 * Zwei Wege, in dieser Reihenfolge:
 *
 *  1. **UniversalEmbedder** (MediaPipe Tasks Retrieval) – der offizielle, dokumentierte Weg:
 *     `BaseOptions` → `UniversalEmbedderOptions` (Text-Delegate, L2-Normalisierung) →
 *     `createFromOptions` → `embedText(...).embeddings()[0].floatEmbedding()`.
 *  2. **EmbeddingEngine** direkt (LiteRT-LM) – derselbe Motor ohne MediaPipe-Drumherum.
 *     Nützlich, wenn MediaPipe und LiteRT-LM nicht zusammenpassen; läuft auf CPU.
 *
 * Beide Wege werden **ausprobiert und mit einem Testlauf geprüft**; erst dann gilt das Modell
 * als benutzbar. Scheitert alles, steht der Grund in [lastFailure] und wird in der App
 * angezeigt – kein stiller Rückfall mehr.
 *
 * Alles läuft über Reflection, damit die App auch ohne die Bibliotheken baut.
 *
 * **Fehler, die hier bis 1.36 steckten (und jetzt behoben sind):**
 *  • Methoden wurden über exakte Parametertypen gesucht – `AutoValue_BaseOptions` passt nie
 *    auf `BaseOptions`. Jetzt entscheidet die Zuweisbarkeit.
 *  • Die LiteRT-LM-Version war zu alt (`0.17.0-alpha1`): `ActivationDataType` fehlte, der
 *    UniversalEmbedder konnte gar nicht starten (siehe `app/build.gradle.kts`).
 *  • `NoClassDefFoundError` ist ein `Error`, kein `Exception` – er wurde nicht gefangen und
 *    kam als nackter Klassenname in der Oberfläche an. Jetzt wird `Throwable` gefangen und
 *    lesbar übersetzt.
 */
internal class GemmaEmbedder(
    /** Das Engine-Objekt (UniversalEmbedder oder EmbeddingEngine). */
    private val handle: Any,
    override val accelerator: AiEngine.Accelerator,
    override val modelName: String,
    override val detail: String,
    private val embedFn: (String) -> FloatArray?,
    /** 1.42: Bild-Einbettung (nur die multimodalen Modelle 440M/740M). */
    private val imageFn: ((ByteArray) -> FloatArray?)? = null
) : AiEngine.EmbedderBackend {

    override fun embed(text: String): FloatArray? = embedFn(text)

    /** Kann dieses Modell Bilder einbetten (Voraussetzung für die Personen-Klassifizierung)? */
    val canEmbedImages: Boolean get() = imageFn != null

    /** Bild (JPEG/PNG-Bytes) einbetten – für die Personen-Klassifizierung. */
    fun embedImage(bytes: ByteArray): FloatArray? = imageFn?.invoke(bytes)

    override fun close() {
        runCatching {
            findMethod(handle.javaClass, "close", emptyArray())?.invoke(handle)
        }
    }

    companion object {

        private const val TAG = "N3Ai"

        private const val CLASS_UNIVERSAL = "com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder"
        private const val CLASS_UNIVERSAL_OPTIONS = "com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions"
        private const val CLASS_BASE_OPTIONS = "com.google.mediapipe.tasks.core.BaseOptions"
        private const val CLASS_DELEGATE = "com.google.mediapipe.tasks.core.Delegate"
        private const val CLASS_EMBEDDING_ENGINE = "com.google.ai.edge.litertlm.EmbeddingEngine"
        private const val CLASS_EMBEDDING_CONFIG = "com.google.ai.edge.litertlm.EmbeddingEngineConfig"
        private const val CLASS_BACKEND = "com.google.ai.edge.litertlm.Backend"
        private const val CLASS_INPUT_DATA = "com.google.ai.edge.litertlm.InputData"

        /**
         * Nur NPU zulassen (Vorgabe des Nutzers). Auf `false` setzen, wenn wieder GPU/CPU
         * erlaubt sein soll – der Code für beide Wege bleibt erhalten.
         */
        const val NPU_ONLY = true

        /** Warum das Modell nicht läuft – für die Anzeige in der Bildersuche. */
        @Volatile
        var lastFailure: String = ""
            private set

        /** Welcher Weg gerade rechnet (für die Statuszeile). */
        @Volatile
        var lastRoute: String = ""
            private set

        /** Woher das Modell kommt: App-Speicher (Download) oder App-Paket (assets). */
        internal sealed class ModelSource {
            abstract val label: String
            abstract val name: String

            data class File(val file: java.io.File) : ModelSource() {
                override val label: String get() = file.absolutePath
                override val name: String get() = file.name
            }

            data class Asset(val path: String) : ModelSource() {
                override val label: String get() = path
                override val name: String get() = path.substringAfterLast('/')
            }
        }

        fun create(ctx: Context): GemmaEmbedder? {
            lastFailure = ""
            lastRoute = ""
            // 1.42 – früher wurde hier das erste beliebige Modell genommen (auch ein reines
            // Text-Modell, obwohl die Dateien der Suche alle Text-Einbettung brauchen).
            // Gesucht ist jetzt die NPU-Fassung dieses Geräts; die fällt automatisch auf die
            // normale Datei zurück, wenn es keine gibt.
            val source: ModelSource = ModelStore.best(ctx)?.let { ModelSource.File(it) }
                ?: findBundledModel(ctx)?.let { ModelSource.Asset(it) }
                ?: run {
                    Log.i(TAG, "Kein EmbeddingGemma-2-Modell vorhanden – Suche nutzt das eingebaute Wortmodell.")
                    lastFailure = "kein Modell im Gerät"
                    return null
                }

            val reasons = ArrayList<String>(4)

            // ---- Weg 0: NPU (LiteRT-LM direkt). Der UniversalEmbedder von MediaPipe kennt nur
            //      CPU/GPU; LiteRT-LM selbst hat ein NPU-Backend. Deshalb zuerst dieser Weg –
            //      er wird mit einem Testlauf geprüft und fällt still auf GPU/CPU zurück.
            if (classOrNull(CLASS_EMBEDDING_ENGINE) != null && (NPU_ONLY || hasNpu())) {
                val result = runCatching { buildEngineDirect(ctx, source, "NPU") }
                val built = result.getOrNull()
                if (built != null) {
                    val (problem, dims) = probe(built)
                    if (problem == null) {
                        lastRoute = "LiteRT-LM NPU"
                        Log.i(TAG, "EmbeddingGemma 2 läuft auf der NPU ($dims Dimensionen)")
                        return built
                    }
                    reasons.add("NPU: $problem")
                    runCatching { built.close() }
                } else {
                    reasons.add("NPU: ${human(result.exceptionOrNull())}")
                }
            }

            /**
             * **Ab 1.41 gilt: nur NPU, kein stiller Rückfall.**
             *
             * Auf Wunsch des Nutzers soll das Modell ausschließlich auf der NPU rechnen.
             * Ein Rückfall auf GPU/CPU würde die Suche zwar „irgendwie“ laufen lassen, aber
             * genau das verschleiert, dass die NPU nicht genutzt wird. Deshalb wird hier
             * abgebrochen und der Grund gemeldet – sichtbar in der Statuszeile und im
             * Selbsttest.
             */
            if (NPU_ONLY) {
                lastFailure = reasons.joinToString(" · ").ifBlank {
                    "NPU nicht verfügbar (Backend.NPU fehlt oder Gerät ohne NPU-Treiber)"
                }
                Log.w(TAG, "NPU nicht nutzbar: $lastFailure")
                return null
            }

            // ---- Weg 1: UniversalEmbedder (dokumentierte MediaPipe-API), GPU → CPU
            if (classOrNull(CLASS_UNIVERSAL) != null) {
                for ((acc, delegateName) in listOf(
                    AiEngine.Accelerator.GPU to "GPU",
                    AiEngine.Accelerator.CPU to "CPU"
                )) {
                    val result = runCatching { buildUniversal(ctx, source, delegateName, acc) }
                    val built = result.getOrNull()
                    if (built != null) {
                        val (problem, dims) = probe(built)
                        if (problem == null) {
                            lastRoute = "UniversalEmbedder ${acc.label}"
                            Log.i(TAG, "EmbeddingGemma 2 läuft ($lastRoute, $dims Dimensionen)")
                            return built
                        }
                        reasons.add("${acc.label}: $problem")
                        runCatching { built.close() }
                    } else {
                        reasons.add("${acc.label}: ${human(result.exceptionOrNull())}")
                    }
                }
            } else {
                reasons.add("UniversalEmbedder fehlt im Build")
            }

            // ---- Weg 2: EmbeddingEngine direkt (LiteRT-LM), CPU
            if (classOrNull(CLASS_EMBEDDING_ENGINE) != null) {
                val result = runCatching { buildEngineDirect(ctx, source, "CPU") }
                val built = result.getOrNull()
                if (built != null) {
                    val (problem, dims) = probe(built)
                    if (problem == null) {
                        lastRoute = "LiteRT-LM direkt (CPU)"
                        Log.i(TAG, "EmbeddingGemma 2 läuft ($lastRoute, $dims Dimensionen)")
                        return built
                    }
                    reasons.add("LiteRT-LM: $problem")
                    runCatching { built.close() }
                } else {
                    reasons.add("LiteRT-LM: ${human(result.exceptionOrNull())}")
                }
            }

            lastFailure = reasons.joinToString(" · ").ifBlank { "Modell konnte nicht gestartet werden" }
            Log.w(TAG, "Modell ${source.name} läuft nicht: $lastFailure")
            return null
        }

        /**
         * **2. Modell – Personen-Klassifizierung (1.42).**
         *
         * Baut eine eigene Engine für das multimodale Modell (Text+Bild 440M oder 740M) –
         * bevorzugt die **NPU-Fassung dieses Chipsatzes** (z. B. `_Qualcomm_SM8750` für das
         * Galaxy S25 Ultra). Geprüft wird mit einem echten **Testbild**, denn hier zählt die
         * Bild-Einbettung: Ein Text-Modell (270M) wäre für Personen unbrauchbar.
         *
         * Nur NPU (kein stiller Rückfall) – dieselbe Regel wie bei der Suche.
         */
        fun createForPersons(ctx: Context): GemmaEmbedder? {
            lastFailure = ""
            lastRoute = ""
            val file = ModelStore.bestVision(ctx) ?: run {
                lastFailure = "kein Modell mit Bild-Einbettung im Gerät (Text+Bild 440M oder 740M laden)"
                Log.i(TAG, "Personen-Klassifizierung: kein Bild-Modell vorhanden")
                return null
            }
            val source = ModelSource.File(file)

            val reasons = ArrayList<String>(4)
            if (classOrNull(CLASS_EMBEDDING_ENGINE) != null) {
                val result = runCatching { buildEngineDirect(ctx, source, "NPU") }
                val built = result.getOrNull()
                if (built != null) {
                    val (problem, dims) = probeVision(built)
                    if (problem == null) {
                        lastRoute = "LiteRT-LM NPU · Bild"
                        Log.i(TAG, "Personen-Modell läuft auf der NPU ($dims Dimensionen): ${file.name}")
                        return built
                    }
                    reasons.add("NPU: $problem")
                    runCatching { built.close() }
                } else {
                    reasons.add("NPU: ${human(result.exceptionOrNull())}")
                }
            } else {
                reasons.add("LiteRT-LM fehlt im Build")
            }
            lastFailure = reasons.joinToString(" · ").ifBlank { "NPU nicht verfügbar" }
            Log.w(TAG, "Personen-Modell läuft nicht: $lastFailure")
            return null
        }

        /** Testlauf für die Bild-Einbettung: (Grund oder `null`, Dimensionen). */
        private fun probeVision(built: GemmaEmbedder): Pair<String?, Int> = try {
            val v = built.embedImage(tinyTestImage())
            if (v != null && v.isNotEmpty()) null to v.size else "Testbild lieferte keinen Vektor" to 0
        } catch (t: Throwable) {
            human(t) to 0
        }

        /** Kleines Testbild (PNG-Bytes), um die Bild-Einbettung ohne Foto zu prüfen. */
        private fun tinyTestImage(): ByteArray {
            val bmp = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.rgb(90, 130, 200))
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return out.toByteArray()
        }

        /**
         * Kurzer Testlauf: (Grund oder `null`, Dimensionen). Erst wenn hier ein echter Vektor
         * herauskommt, gilt das Modell als benutzbar.
         */
        private fun probe(built: GemmaEmbedder): Pair<String?, Int> = try {
            val v = built.embedFn("Test")
            if (v != null && v.isNotEmpty()) null to v.size else "Testlauf lieferte keinen Vektor" to 0
        } catch (t: Throwable) {
            human(t) to 0
        }

        /** Sucht ein beigelegtes Modell in `assets/models`. */
        private fun findBundledModel(ctx: Context): String? {
            val files = runCatching { ctx.assets.list(AiEngine.Prompt.ASSET_DIR)?.toList() }
                .getOrNull() ?: return null
            for (name in AiEngine.Prompt.CANDIDATES) {
                if (files.contains(name)) return "${AiEngine.Prompt.ASSET_DIR}/$name"
            }
            return files.firstOrNull { it.endsWith(".litertlm", true) || it.endsWith(".task", true) }
                ?.let { "${AiEngine.Prompt.ASSET_DIR}/$it" }
        }

        // ------------------------------------------------------------------ Weg 1

        private fun buildUniversal(
            ctx: Context,
            source: ModelSource,
            delegateName: String,
            accelerator: AiEngine.Accelerator
        ): GemmaEmbedder {
            val engineClass = Class.forName(CLASS_UNIVERSAL)
            val baseOptionsClass = Class.forName(CLASS_BASE_OPTIONS)
            val delegateClass = Class.forName(CLASS_DELEGATE)
            @Suppress("UNCHECKED_CAST")
            val delegate = java.lang.Enum.valueOf(delegateClass as Class<out Enum<*>>, delegateName)

            val path = when (source) {
                is ModelSource.Asset -> source.path
                is ModelSource.File -> source.file.absolutePath
            }

            // BaseOptions: Modellpfad + Beschleuniger
            val baseBuilder = invokeStatic(baseOptionsClass, "builder")
                ?: error("BaseOptions.builder() fehlt")
            invoke(baseBuilder, "setModelAssetPath", path)
            invoke(baseBuilder, "setDelegate", delegate)
            val baseOptions = invoke(baseBuilder, "build") ?: error("BaseOptions.build() fehlt")

            // UniversalEmbedderOptions: Basis + Text-Delegate + Normalisierung
            val optionsClass = Class.forName(CLASS_UNIVERSAL_OPTIONS)
            val optBuilder = invokeStatic(optionsClass, "builder")
                ?: error("UniversalEmbedderOptions.builder() fehlt")
            invoke(optBuilder, "setBaseOptions", baseOptions)
            runCatching { invoke(optBuilder, "setTextDelegate", delegate) }
            runCatching { invoke(optBuilder, "setL2Normalize", true) }
            val options = invoke(optBuilder, "build") ?: error("Options.build() fehlt")

            val engine = invokeStatic(engineClass, "createFromOptions", ctx, options)
                ?: error("createFromOptions lieferte null")

            val embedText = findMethod(engineClass, "embedText", arrayOf<Any?>("x"))
                ?: error("embedText fehlt")
            val fn: (String) -> FloatArray? = { text ->
                val result = embedText.invoke(engine, text) ?: error("kein Ergebnis")
                // EmbeddingResult.embeddings() → List<Embedding>, [0].floatEmbedding() → float[]
                val embeddings = invoke(result, "embeddings") as? List<*>
                    ?: error("embeddings() fehlt")
                val first = embeddings.firstOrNull() ?: error("Embedding-Liste ist leer")
                val vec = invoke(first, "floatEmbedding") as? FloatArray
                if (vec == null || vec.isEmpty()) error("floatEmbedding ist leer") else vec
            }
            val detail = when (accelerator) {
                AiEngine.Accelerator.GPU -> "UniversalEmbedder · GPU"
                AiEngine.Accelerator.CPU -> "UniversalEmbedder · CPU"
                else -> "UniversalEmbedder"
            }
            return GemmaEmbedder(engine, accelerator, "EmbeddingGemma 2", detail, fn)
        }

        // ------------------------------------------------------------------ Weg 2

        /**
         * LiteRT-LM direkt: `EmbeddingEngineConfig(modelPath, backend, …)` →
         * `EmbeddingEngine(config)` → `initialize()` → `computeEmbedding(listOf(InputData.Text(x)))`
         * → `response.embedding` (float[]).
         */
        private fun buildEngineDirect(
            ctx: Context,
            source: ModelSource,
            backendName: String
        ): GemmaEmbedder {
            val engineClass = Class.forName(CLASS_EMBEDDING_ENGINE)
            val configClass = Class.forName(CLASS_EMBEDDING_CONFIG)

            val backend = buildBackend(ctx, backendName)

            val path = when (source) {
                is ModelSource.Asset -> source.path
                is ModelSource.File -> source.file.absolutePath
            }

            // Konstruktor mit (String, Backend, Backend, Backend, String cacheDir, Integer, Integer, Integer)
            val ctor = configClass.declaredConstructors
                .filter { it.parameterTypes.size >= 2 }
                .maxByOrNull { it.parameterTypes.size }
                ?: error("EmbeddingEngineConfig-Konstruktor fehlt")
            ctor.isAccessible = true
            val args = ctor.parameterTypes.mapIndexed { i, type ->
                when {
                    i == 0 -> path
                    type.isAssignableFrom(backend.javaClass) || type.name == CLASS_BACKEND -> backend
                    type == String::class.java -> ctx.cacheDir.absolutePath   // cacheDir
                    else -> null                                             // übrige: Standard
                }
            }.toTypedArray()
            val config = ctor.newInstance(*args)
            val engine = engineClass.getDeclaredConstructor(configClass).also { it.isAccessible = true }
                .newInstance(config)
            invoke(engine, "initialize")

            val compute = engineClass.methods.firstOrNull {
                it.name == "computeEmbedding" && it.parameterTypes.size >= 1
            } ?: error("computeEmbedding fehlt")

            val fn: (String) -> FloatArray? = { text ->
                val input = runCatching {
                    Class.forName("$CLASS_INPUT_DATA\$Text")
                        .getDeclaredConstructor(String::class.java)
                        .also { it.isAccessible = true }
                        .newInstance(text)
                }.getOrElse { error("InputData.Text fehlt") }
                val response = compute.invoke(engine, listOf(input)) ?: error("kein Ergebnis")
                val vec = fieldOf(response, "embedding") ?: invoke(response, "getEmbedding")
                (vec as? FloatArray)?.takeIf { it.isNotEmpty() } ?: error("Embedding ist leer")
            }

            // 1.42 – Bild-Einbettung für die multimodalen Modelle (Text+Bild 440M / 740M):
            // InputData.Image(bytes) geht durch dieselbe computeEmbedding-Methode. Das ist
            // die Grundlage der Personen-Klassifizierung (2. Modell).
            val imageFn: ((ByteArray) -> FloatArray?)? = { bytes ->
                val input = runCatching {
                    Class.forName("$CLASS_INPUT_DATA\$Image")
                        .getDeclaredConstructor(ByteArray::class.java)
                        .also { it.isAccessible = true }
                        .newInstance(bytes)
                }.getOrElse { error("InputData.Image fehlt") }
                val response = compute.invoke(engine, listOf(input)) ?: error("kein Ergebnis")
                val vec = fieldOf(response, "embedding") ?: invoke(response, "getEmbedding")
                (vec as? FloatArray)?.takeIf { it.isNotEmpty() } ?: error("Bild-Embedding ist leer")
            }

            val acc = if (backendName == "NPU") AiEngine.Accelerator.NPU else AiEngine.Accelerator.CPU
            return GemmaEmbedder(engine, acc, "EmbeddingGemma 2", "LiteRT-LM · $backendName", fn, imageFn)
        }

        /**
         * `Backend.CPU()`, `Backend.GPU()` oder `Backend.NPU(nativeLibraryDir)` bauen.
         *
         * **Der Fehler bis 1.41:** `Backend.NPU` wurde über den Konstruktor mit den MEISTEN
         * Parametern gebaut und alle Werte auf `null` belassen. Der parameterlose Weg (alle
         * Standardwerte) wurde gar nicht erst versucht, und der gewählte Konstruktor verlangt
         * ein `int` (Default-Maske), das kein `null` sein darf. Folge: `Backend.NPU` ließ
         * sich **nie** bauen – die NPU konnte also gar nicht erst starten, egal ob
         * Bibliotheken und Modell gepasst hätten.
         *
         * Jetzt gilt (entsprechend der LiteRT-LM-Dokumentation):
         *  • **NPU** wird mit dem Bibliotheks-Ordner der App gebaut
         *    (`Context.applicationInfo.nativeLibraryDir`) – dort liegen die mitgelieferten
         *    NPU-Bibliotheken (Qualcomm-Dispatch + QNN-Laufzeit, siehe build.gradle.kts).
         *  • **CPU/GPU** nehmen den parameterlosen Konstruktor (alles auf Standardwerten).
         */
        private fun buildBackend(ctx: Context, backendName: String): Any {
            val cls = Class.forName("$CLASS_BACKEND\$$backendName")
            if (backendName == "NPU") {
                val nativeDir = ctx.applicationInfo.nativeLibraryDir
                runCatching {
                    val ctor = cls.getDeclaredConstructor(String::class.java)
                    ctor.isAccessible = true
                    return ctor.newInstance(nativeDir)
                }
                // Fallback: parameterlos (Standardwert "") – die Laufzeit sucht dann selbst.
            }
            runCatching {
                val ctor = cls.getDeclaredConstructor()
                ctor.isAccessible = true
                return ctor.newInstance()
            }
            error("Backend.$backendName nicht verfügbar")
        }

        // ------------------------------------------------------------------ Reflection

        private fun classOrNull(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

        /**
         * Ist auf diesem Gerät überhaupt eine NPU denkbar?
         *
         * Geprüft wird die LiteRT-LM-Klasse `Backend.NPU` **und** ob das Gerät einen der
         * bekannten NPU-Treiber mitbringt (Qualcomm QNN, Google Tensor/EdgeTPU, MediaTek APU).
         * Ohne diese Vorprüfung würde jedes Gerät einen fehlschlagenden NPU-Versuch bezahlen.
         * Der eigentliche Beweis ist danach der Testlauf in [create].
         */
        fun hasNpu(): Boolean {
            if (classOrNull("$CLASS_BACKEND\$NPU") == null) return false
            // SOC_MANUFACTURER/SOC_MODEL gibt es erst ab Android 12 (API 31) – die Felder
            // sind keine Konstanten, ihr Fehlen würde auf älteren Geräten brechen.
            val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.os.Build.SOC_MANUFACTURER.orEmpty().lowercase() + " " +
                    android.os.Build.SOC_MODEL.orEmpty().lowercase() + " "
            } else ""
            val device = soc +
                android.os.Build.HARDWARE.orEmpty().lowercase() + " " +
                android.os.Build.MANUFACTURER.orEmpty().lowercase() + " " +
                android.os.Build.MODEL.orEmpty().lowercase()
            val known = listOf(
                "qcom", "qualcomm", "sm8", "sm7", "tensor", "google", "mediatek", "mt6", "mt8",
                "exynos", "s5e", "kirin", "unisoc"
            )
            return known.any { device.contains(it) }
        }

        // Die eigentliche Suche/Aufruf-Logik liegt in Reflect (ohne Android-Abhängigkeit,
        // damit sie per Unit-Test geprüft werden kann – siehe app/src/test/.../ReflectTest.kt).
        private fun findMethod(target: Class<*>, name: String, args: Array<out Any?>): Method? =
            Reflect.findMethod(target, name, args.map { it?.javaClass })

        private fun invoke(target: Any, name: String, vararg args: Any?): Any? =
            Reflect.call(target, name, *args)

        private fun invokeStatic(target: Class<*>, name: String, vararg args: Any?): Any? =
            Reflect.callStatic(target, name, *args)

        private fun fieldOf(target: Any, name: String): Any? = Reflect.field(target, name)

        private fun human(t: Throwable?): String = Reflect.human(t)
    }
}
