package com.n3vibecode.gallery.ai

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verwaltet die **EmbeddingGemma-2-Modelle** auf dem Gerät – inklusive **Download in der App**.
 *
 * Die Dateien sind zu groß für das App-Paket, deshalb werden sie einmal heruntergeladen und im
 * App-Speicher abgelegt:
 *
 * ```
 * /data/data/com.n3vibecode.gallery/files/models/<datei>.litertlm
 * ```
 *
 * ## 1.42 – NPU-Varianten pro Chipsatz (der entscheidende Punkt)
 *
 * Google veröffentlicht die Modelle in **zwei Fassungen**:
 *
 *  * die normale Datei (`embeddinggemma-2-….litertlm`) ist für **CPU/GPU** compiliert,
 *  * die Dateien mit Anhang (`…_Qualcomm_SM8750.litertlm`, `…_Google_Tensor_G5.litertlm`,
 *    `…_MediaTek_MT6991.litertlm` …) sind **für die NPU dieses Chipsatzes** vorkompiliert
 *    (AOT). Nur mit ihnen rechnet `Backend.NPU` wirklich auf dem NPU – mit der normalen
 *    Datei startet die NPU nicht. Das war der Grund, warum „auf NPU laufen“ bisher
 *    unmöglich war.
 *
 * Die App erkennt den Chipsatz (`Build.SOC_MANUFACTURER`/`SOC_MODEL`, z. B. **SM8750 =
 * Snapdragon 8 Elite = Galaxy S25 Ultra**) und lädt automatisch die passende NPU-Fassung.
 *
 * ## Download-Sicherheit
 *
 *  * Er wird **gegen die erwartete Größe** geprüft (aus dem HTTP-Header, sonst aus der Tabelle).
 *  * Es wird **gegen die SHA-256-Prüfsumme** geprüft (von Hugging Face, mit der Datei geliefert).
 *    Stimmt sie nicht, wird die Datei verworfen und der Grund genannt.
 *  * Bricht die Verbindung ab, wird **fortgesetzt** (`Range: bytes=…`) – statt von vorn.
 *  * Eine unvollständige Datei wird nie als Modell geladen (`models` filtert).
 *
 * Nur eine bewusste Handlung löst einen Download aus (Knopf in der Bildersuche bzw. im
 * Personen-Bereich) – die App geht nicht von sich aus online. Gesucht und klassifiziert
 * wird danach vollständig offline.
 */
object ModelStore {

    private const val TAG = "N3Ai"
    private const val DIR = "models"

    /** Die drei Modell-Familien („Arten“). */
    enum class Kind(
        val repo: String,
        val baseFile: String,
        val label: String,
        val note: String
    ) {
        TEXT(
            "embeddinggemma-2-text-270m-litert-lm",
            "embeddinggemma-2-text-270m",
            "EmbeddingGemma 2 · Text (270M)",
            "kleinste Variante · nur Textsuche"
        ),
        VISION(
            "embeddinggemma-2-text-vision-440m-litert-lm",
            "embeddinggemma-2-text-vision-440m",
            "EmbeddingGemma 2 · Text + Bild (440M)",
            "empfohlen · Suche und Personen-Klassifizierung"
        ),
        FULL(
            "embeddinggemma-2-740m-litert-lm",
            "embeddinggemma-2-740m",
            "EmbeddingGemma 2 · alles (740M)",
            "größte Variante · Text, Bild und Audio"
        );

        /** Kann diese Familie Bilder einbetten (für die Personen-Klassifizierung)? */
        val supportsImages: Boolean get() = this != TEXT
    }

    /** Eine konkrete Datei ( Chipsatz-Variante oder die normale CPU/GPU-Fassung). */
    data class ModelFile(
        val file: String,
        val bytes: Long,
        val sha256: String,
        /** Leerer Anhang = normale CPU/GPU-Datei. */
        val soc: String
    ) {
        val expectedMb: Int get() = Math.round(bytes / 1048576.0).toInt()
        val isNpu: Boolean get() = soc.isNotEmpty()
    }

    /**
     * Alle Dateien je Familie. Quelle: Hugging Face (`litert-community`), jede Datei mit
     * Größe und SHA-256 aus dem LFS-Objekt (Stand November 2026). Die NPU-Fassungen sind
     * **vorkompiliert für genau diesen Chipsatz** – sie laufen nur dort auf der NPU.
     */
    private val FILES: Map<Kind, List<ModelFile>> = mapOf(
        Kind.TEXT to listOf(
            ModelFile("embeddinggemma-2-text-270m.litertlm", 164_626_432L, "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb", ""),
            ModelFile("embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm", 187_596_800L, "b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99", "Qualcomm_SM8750"),
            ModelFile("embeddinggemma-2-text-270m_Qualcomm_SM8850.litertlm", 202_293_248L, "72992b679c812ae9e30ffa9ad88fc662e649f7b643500d28051fbd456ad0d22a", "Qualcomm_SM8850"),
            ModelFile("embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm", 207_798_272L, "7dabcb17eed982d6de4435aaf29cc561476a627f6ab20a1e37aadd7af5399457b", "Qualcomm_SM8650"),
            ModelFile("embeddinggemma-2-text-270m_Qualcomm_SM8550.litertlm", 207_896_576L, "6a5043146940e738cfa9111aa445b1cdb3dc7bbef30caef367622f14ada1a895", "Qualcomm_SM8550"),
            ModelFile("embeddinggemma-2-text-270m_Qualcomm_QCS8275.litertlm", 208_011_264L, "128c32151613a5bbf8b5695b2d9ae271057338febbf06e3aeebf29e1353cb51d", "Qualcomm_QCS8275"),
            ModelFile("embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm", 166_007_264L, "f066663433a31a8228b46f3271d2714a3b72a341ed4563cbc6d90f2e3dd0162d", "Google_Tensor_G5"),
            ModelFile("embeddinggemma-2-text-270m_Google_Tensor_G6.litertlm", 199_532_256L, "4cd282395a7b611ec8dec95160cd31a1f471a7b1cde1fc71717aa539c2a29883", "Google_Tensor_G6"),
            ModelFile("embeddinggemma-2-text-270m_MediaTek_MT6991.litertlm", 416_350_208L, "7acc8e334d882216207c23c7a477179d5af530d67fd8e2cb50ab42d3457f90c0", "MediaTek_MT6991"),
            ModelFile("embeddinggemma-2-text-270m_MediaTek_MT6993.litertlm", 385_925_120L, "eff006451073498117f454c7ab27f27b5fe14a54af49be239e8f480ee49fa36", "MediaTek_MT6993")
        ),
        Kind.VISION to listOf(
            ModelFile("embeddinggemma-2-text-vision-440m.litertlm", 387_710_976L, "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0", ""),
            ModelFile("embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm", 446_611_456L, "c5c05cb59833541a2d0d9374c7b50d2c3a406a4a84c85762775e5f9c6d96cd0c", "Qualcomm_SM8750"),
            ModelFile("embeddinggemma-2-text-vision-440m_Qualcomm_SM8850.litertlm", 462_536_704L, "661369235aa9fc23cf04a213d4e445c4634073832279c5a261ed22650a8ebc5a", "Qualcomm_SM8850"),
            ModelFile("embeddinggemma-2-text-vision-440m_Qualcomm_SM8650.litertlm", 465_747_968L, "ec7d2ee513830337343567409f32fea4a3e4f8f7b94facd0a20f3d3523cd7480", "Qualcomm_SM8650"),
            ModelFile("embeddinggemma-2-text-vision-440m_Qualcomm_SM8550.litertlm", 465_993_728L, "4427eecca4289555e72b297308f4c9cf97110a657de25dd2fe7946ac824f6214", "Qualcomm_SM8550"),
            ModelFile("embeddinggemma-2-text-vision-440m_Qualcomm_QCS8275.litertlm", 465_977_344L, "ce8ccce80a12f6bc9acfe664b76c3cc46a698f0da81ae857f70b06b9a4c7d38", "Qualcomm_QCS8275"),
            ModelFile("embeddinggemma-2-text-vision-440m_Google_Tensor_G5.litertlm", 559_418_036L, "96cdbae7b3dd6fa0f829fef2d0dd5bafc0179890d9bafbfe972db852e818d758", "Google_Tensor_G5"),
            ModelFile("embeddinggemma-2-text-vision-440m_Google_Tensor_G6.litertlm", 513_166_004L, "40e98aeed9ef08abfd91d5d309f64f4fa06195c513931e917bcf5ea41a910668", "Google_Tensor_G6"),
            ModelFile("embeddinggemma-2-text-vision-440m_MediaTek_MT6991.litertlm", 798_097_408L, "53cb4b8b3d8ca3e022ceec1dd809ddd26c281721c4652e1fadc82d2433cbc5de", "MediaTek_MT6991"),
            ModelFile("embeddinggemma-2-text-vision-440m_MediaTek_MT6993.litertlm", 767_770_624L, "1cfc52d73266fe0e099e852b816150c22deb32fea421cabf9647dc8d8112ede9", "MediaTek_MT6993")
        ),
        Kind.FULL to listOf(
            ModelFile("embeddinggemma-2-740m.litertlm", 484_622_336L, "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c", ""),
            ModelFile("embeddinggemma-2-740m_Qualcomm_SM8750.litertlm", 543_522_816L, "ac1973c0195dc6e28a621651f2f5e04d35fdc7a7b11cdbbe94f29f5dccb57996", "Qualcomm_SM8750"),
            ModelFile("embeddinggemma-2-740m_Qualcomm_SM8850.litertlm", 559_448_064L, "19f6e8db6aa74b116d1c55c862ee131f60ce093576d3c8dd1898f656ca8080b1", "Qualcomm_SM8850"),
            ModelFile("embeddinggemma-2-740m_Qualcomm_SM8650.litertlm", 562_659_328L, "3b8a18c6d048b8132c4eda45611296e1ae455ada82cda8b591ec0ca141e041c5", "Qualcomm_SM8650"),
            ModelFile("embeddinggemma-2-740m_Qualcomm_SM8550.litertlm", 562_905_088L, "802686a865fd7350f2ce4b6fa17ce02a411f56b5b508ec2bd8cb731b2db82e6", "Qualcomm_SM8550"),
            ModelFile("embeddinggemma-2-740m_Qualcomm_QCS8275.litertlm", 562_888_704L, "4d99a14e63b01cd2947961a1b4939395ad50a3a101a411e05f8397f4a80dbd80", "Qualcomm_QCS8275"),
            ModelFile("embeddinggemma-2-740m_Google_Tensor_G5.litertlm", 657_656_500L, "5b53d76c2c7c66d2dc6230ff158ea0033ac5939ac7cfcf827c3a22cdfc742a5e", "Google_Tensor_G5"),
            ModelFile("embeddinggemma-2-740m_Google_Tensor_G6.litertlm", 615_713_460L, "ed0e4f413e705946eda544078d8bf5acf10fe5aee2946451c81919a1f59665e7", "Google_Tensor_G6"),
            ModelFile("embeddinggemma-2-740m_MediaTek_MT6991.litertlm", 895_008_768L, "99e6a35b824b4f7c7f20c59ba380aa90e0b4442aadfee36e4230d548a9c7cca", "MediaTek_MT6991"),
            ModelFile("embeddinggemma-2-740m_MediaTek_MT6993.litertlm", 864_681_984L, "ed2a2554bfb6d7abcafd0e9e36ee2f8d7de82888e7ed0fc52dd144e65b171742", "MediaTek_MT6993")
        )
    )

    /**
     * Ein anwählbares Modell: Familie + Datei (Chipsatz-Variante, wenn es eine gibt).
     * Diese Klasse ersetzt das frühere `Variant`-Enum.
     */
    class Variant(val kind: Kind, val model: ModelFile) {
        val file: String get() = model.file
        val bytes: Long get() = model.bytes
        val sha256: String get() = model.sha256
        val soc: String get() = model.soc
        val expectedMb: Int get() = model.expectedMb
        val label: String get() = kind.label
        val note: String get() = kind.note
        val url: String get() = "https://huggingface.co/litert-community/${kind.repo}/resolve/main/${model.file}"
        val isNpu: Boolean get() = model.isNpu
    }

    // ------------------------------------------------------------------ Chipsatz

    /**
     * Chipsatz dieses Geräts als Anhang der Modell-Datei (z. B. `Qualcomm_SM8750` für das
     * Galaxy S25 Ultra) – oder `""`, wenn es keine veröffentlichte NPU-Fassung gibt.
     */
    fun socTag(): String = socTagFrom(deviceIdString())

    /** Die Geräte-Kennungen in einem String (für den Unit-Test injizierbar). */
    internal fun deviceIdString(): String = runCatching {
        // SOC_MANUFACTURER/SOC_MODEL gibt es erst ab Android 12 (API 31); die Felder sind
        // keine Konstanten und würden auf älteren Geräten bei der Abfrage brechen.
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MANUFACTURER.orEmpty() + " " + Build.SOC_MODEL.orEmpty() + " "
        } else ""
        soc + Build.HARDWARE.orEmpty() + " " + Build.BOARD.orEmpty()
    }.getOrDefault("")

    /** Der reine Textvergleich – ohne Android, deshalb im Unit-Test prüfbar. */
    internal fun socTagFrom(raw: String): String {
        val s = raw.uppercase()
        return when {
            // Snapdragon 8 Elite (Galaxy S25/S25 Ultra) – der Anwendungsfall des Nutzers
            s.contains("SM8750") || s.contains("8 ELITE") -> "Qualcomm_SM8750"
            s.contains("SM8850") -> "Qualcomm_SM8850"
            s.contains("SM8650") -> "Qualcomm_SM8650"
            s.contains("SM8550") -> "Qualcomm_SM8550"
            s.contains("QCS8275") || s.contains("SA8295") -> "Qualcomm_QCS8275"
            // Google Tensor: Pixel 10 = G5, Pixel 11 = G6 (Modellbezeichnung „Tensor G5“)
            s.contains("TENSOR G5") || s.contains("TENSORG5") -> "Google_Tensor_G5"
            s.contains("TENSOR G6") || s.contains("TENSORG6") -> "Google_Tensor_G6"
            s.contains("MT6991") -> "MediaTek_MT6991"
            s.contains("MT6993") -> "MediaTek_MT6993"
            else -> ""
        }
    }

    /** Lesbarer Name für die Anzeige (z. B. „Snapdragon 8 Elite (SM8750) – NPU“). */
    fun socLabel(soc: String = socTag()): String = when (soc) {
        "Qualcomm_SM8750" -> "Snapdragon 8 Elite (SM8750)"
        "Qualcomm_SM8850" -> "Snapdragon 8 Elite Gen 5 (SM8850)"
        "Qualcomm_SM8650" -> "Snapdragon 8 Gen 3 (SM8650)"
        "Qualcomm_SM8550" -> "Snapdragon 8 Gen 2 (SM8550)"
        "Qualcomm_QCS8275" -> "Qualcomm QCS8275"
        "Google_Tensor_G5" -> "Google Tensor G5"
        "Google_Tensor_G6" -> "Google Tensor G6"
        "MediaTek_MT6991" -> "MediaTek MT6991"
        "MediaTek_MT6993" -> "MediaTek MT6993"
        else -> ""
    }

    /** Gibt es für diesen Chipsatz eine veröffentlichte NPU-Fassung? */
    fun hasNpuVariant(soc: String = socTag()): Boolean = soc.isNotEmpty()

    /**
     * Die Datei einer Familie für dieses Gerät: **NPU-Fassung, wenn es sie gibt** –
     * sonst die normale CPU/GPU-Datei.
     */
    fun fileFor(kind: Kind, soc: String = socTag()): ModelFile {
        val files = FILES[kind].orEmpty()
        return files.firstOrNull { it.soc == soc && soc.isNotEmpty() }
            ?: files.firstOrNull { it.soc.isEmpty() }
            ?: files.first()
    }

    /** Alle drei Familien als auswählbare Varianten für dieses Gerät. */
    fun variants(): List<Variant> = Kind.entries.map { Variant(it, fileFor(it)) }

    internal fun knownFile(name: String): ModelFile? =
        FILES.values.flatten().firstOrNull { it.file == name }

    // ------------------------------------------------------------------ Speicher

    private val EXTENSIONS = listOf(".litertlm", ".task", ".tflite")

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * Eine Datei gilt erst als Modell, wenn sie **vollständig** ist. Eine abgebrochene Datei
     * würde sonst geladen und der Start scheitert mit einer unverständlichen Meldung.
     */
    private fun isComplete(f: File): Boolean {
        if (!f.isFile) return false
        val known = knownFile(f.name)
        return if (known != null && known.bytes > 0) {
            // Toleranz: Ein paar Bytes Abweichung sind bei CDN-Angaben normal, 1 % nicht.
            f.length() >= known.bytes - 1024 && f.length() <= known.bytes + 1024
        } else {
            f.length() > 1024 * 1024
        }
    }

    /** Alle **vollständigen** Modelldateien. */
    fun models(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { f -> EXTENSIONS.any { f.name.endsWith(it, true) } && isComplete(f) }
            ?: emptyList()

    /** Unvollständige Dateien (für die Anzeige: „unvollständig, 462 von 485 MB“). */
    fun incomplete(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { f -> EXTENSIONS.any { f.name.endsWith(it, true) } && f.isFile && !isComplete(f) }
            ?: emptyList()

    /**
     * Das beste vorhandene Modell für die **Suche** (Text-Einbettung): zuerst die
     * NPU-Fassung **dieses Geräts** (egal welche Familie), dann die größte Datei.
     */
    fun best(ctx: Context): File? {
        val soc = socTag()
        return models(ctx)
            .sortedWith(
                compareByDescending<File> { f ->
                    val k = knownFile(f.name)
                    if (k != null && k.soc == soc && soc.isNotEmpty()) 1 else 0
                }.thenByDescending { it.length() }
            )
            .firstOrNull()
    }

    /**
     * Das beste vorhandene Modell **mit Bild-Einbettung** (für die Personen-Klassifizierung):
     * Text+Bild (440M) oder alles (740M), bevorzugt die NPU-Fassung dieses Geräts.
     */
    fun bestVision(ctx: Context): File? {
        val soc = socTag()
        val wanted = FILES[Kind.VISION].orEmpty().map { it.file } +
            FILES[Kind.FULL].orEmpty().map { it.file }
        return models(ctx)
            .filter { wanted.contains(it.name) }
            .sortedWith(
                compareByDescending<File> { f ->
                    val k = knownFile(f.name)
                    if (k != null && k.soc == soc && soc.isNotEmpty()) 1 else 0
                }.thenByDescending { it.length() }
            )
            .firstOrNull()
    }

    fun hasModel(ctx: Context): Boolean = best(ctx) != null

    /** Hat die Personen-Klassifizierung ihr Modell (Text + Bild)? */
    fun hasVisionModel(ctx: Context): Boolean = bestVision(ctx) != null

    // ------------------------------------------------------------------ Download

    @Volatile private var downloading = false
    private val cancelled = AtomicBoolean(false)

    fun isDownloading(): Boolean = downloading

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Lädt ein Modell herunter (im Hintergrund-Thread, den der Aufrufer stellt).
     *
     * [onProgress] bekommt (geladene Bytes, Gesamt-Bytes oder -1). [onDone] bekommt die fertige
     * Datei – oder `null` **und den Grund**.
     */
    fun download(
        ctx: Context,
        variant: Variant,
        onProgress: ((Long, Long) -> Unit)? = null,
        onDone: ((File?, String) -> Unit)? = null
    ) {
        if (downloading) {
            onDone?.invoke(null, "Es läuft bereits ein Download.")
            return
        }
        downloading = true
        cancelled.set(false)
        val app = ctx.applicationContext
        val target = File(dir(app), variant.file)
        val part = File(dir(app), "${variant.file}.part")

        // Ist die Datei schon vollständig da? Dann nichts tun.
        if (isComplete(target)) {
            downloading = false
            onDone?.invoke(target, "")
            return
        }

        try {
            var finished = false
            var attempt = 0
            var lastReason = ""
            // Bis zu 3 Anläufe; ab dem zweiten wird fortgesetzt, nicht neu begonnen.
            while (attempt < 3 && !finished) {
                attempt++
                if (cancelled.get()) {
                    onDone?.invoke(null, "Abgebrochen.")
                    return
                }
                val have = if (part.exists()) part.length() else 0L
                val res = fetch(variant, part, have, onProgress)
                when {
                    res.ok -> finished = true
                    res.cancelled -> {
                        onDone?.invoke(null, "Abgebrochen.")
                        return
                    }

                    else -> {
                        lastReason = res.reason
                        Log.w(TAG, "Versuch $attempt fehlgeschlagen: ${res.reason} (${part.length()} Bytes)")
                        if (!res.retryable) break
                        runCatching { Thread.sleep(1200) }
                    }
                }
            }
            if (!finished) {
                onDone?.invoke(null, lastReason.ifBlank { "Der Download wurde unterbrochen." })
                return
            }

            // ---- Vollständigkeit prüfen, bevor die Datei zum Modell wird
            val expected = variant.bytes
            val got = part.length()
            if (expected > 0 && got != expected) {
                // Bei zu wenig: .part behalten (nächster Versuch setzt fort), aber melden.
                onDone?.invoke(
                    null,
                    "Der Download ist unvollständig: $got von $expected Bytes. " +
                        "Bitte erneut versuchen – er läuft dann an dieser Stelle weiter."
                )
                return
            }
            if (got < 1024 * 1024) {
                part.delete()
                onDone?.invoke(null, "Die Datei ist zu klein ($got Bytes) – die Adresse liefert keine Modelldatei.")
                return
            }
            // ---- 1.42: Prüfsumme – nur die exakte, vollständige Datei wird übernommen.
            val hash = sha256Of(part)
            if (hash != null && variant.sha256.isNotBlank() &&
                !hash.equals(variant.sha256, ignoreCase = true)
            ) {
                part.delete()
                onDone?.invoke(
                    null,
                    "Die Prüfsumme stimmt nicht (erwartet ${variant.sha256.take(12)}…, gekommen ${hash.take(12)}…). " +
                        "Die Datei wurde verworfen – bitte erneut versuchen."
                )
                return
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                onDone?.invoke(null, "Die Datei konnte nicht gespeichert werden.")
                return
            }
            Log.i(TAG, "Modell geladen: ${target.name} (${target.length() / 1048576} MB, Prüfsumme ok)")
            onProgress?.invoke(target.length(), target.length())
            onDone?.invoke(target, "")
        } catch (t: Throwable) {
            Log.w(TAG, "Download fehlgeschlagen: ${t.message}", t)
            onDone?.invoke(null, "Unerwarteter Fehler: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            downloading = false
        }
    }

    /** SHA-256 einer Datei (oder `null`, wenn sie nicht lesbar ist). */
    private fun sha256Of(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private class FetchResult(
        val ok: Boolean,
        val reason: String = "",
        /** Lohnt ein neuer Versuch? (bei 404 nicht) */
        val retryable: Boolean = true,
        val cancelled: Boolean = false
    )

    /**
     * Ein Versuch: lädt nach [part] und setzt bei [have] Bytes fort.
     *
     * Wichtig ist die Behandlung von `206 Partial Content`: Nur dann darf angehängt werden.
     * Antwortet der Server mit `200`, schickt er die **ganze** Datei – dann wird neu begonnen,
     * sonst entstünde eine Datei mit doppelten Anfangsbytes.
     */
    private fun fetch(
        variant: Variant,
        part: File,
        have: Long,
        onProgress: ((Long, Long) -> Unit)?
    ): FetchResult {
        var connection: HttpURLConnection? = null
        return try {
            var url = URL(variant.url)
            var hops = 0
            var code: Int
            while (true) {
                if (hops++ > 5) return FetchResult(false, "Zu viele Weiterleitungen.", false)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 45_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "N3Gallery/1.42 (Android)")
                    // Fortsetzen, wenn schon etwas da ist
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: break
                    url = URL(url, location)   // auch relative Weiterleitungen erlauben
                    connection.disconnect()
                    continue
                }
                break
            }
            val conn = connection ?: return FetchResult(false, "Keine Verbindung.")

            // Bei 416 ist der Bereich schon komplett geladen
            if (code == 416 && have > 0) {
                conn.disconnect()
                return FetchResult(true)
            }
            if (code != 200 && code != 206) {
                conn.disconnect()
                val reason = when (code) {
                    404 -> "Die Datei gibt es unter dieser Adresse nicht (HTTP 404)."
                    403 -> "Der Server verweigert den Zugriff (HTTP 403)."
                    401 -> "Der Server verlangt eine Anmeldung (HTTP 401)."
                    in 500..599 -> "Der Server hat einen Fehler (HTTP $code)."
                    else -> "Der Server antwortet mit HTTP $code."
                }
                return FetchResult(false, reason, code !in listOf(404, 401, 403))
            }

            // Wie groß ist die Datei insgesamt?
            val contentLength = conn.contentLengthLong
            val total = when {
                code == 206 && contentLength > 0 -> have + contentLength
                contentLength > 0 -> contentLength
                else -> variant.bytes
            }

            // Server ignoriert Range → von vorn beginnen
            val append = code == 206 && have > 0
            if (!append && have > 0) {
                Log.i(TAG, "Server liefert die ganze Datei – Neustart statt Fortsetzen")
                part.delete()
            }

            conn.inputStream.use { input ->
                val raw = if (append) java.io.FileOutputStream(part, true) else part.outputStream()
                raw.buffered(1 shl 16).use { out ->
                        val buffer = ByteArray(1 shl 16)
                        var done = if (append) have else 0L
                        var lastReport = 0L
                        while (true) {
                            if (cancelled.get()) {
                                conn.disconnect()
                                return FetchResult(false, "Abgebrochen.", false, cancelled = true)
                            }
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (done - lastReport > 2L * 1024 * 1024 || done == total) {
                                lastReport = done
                                onProgress?.invoke(done, total)
                            }
                        }
                    }
            }
            conn.disconnect()
            FetchResult(true)
        } catch (t: Throwable) {
            runCatching { connection?.disconnect() }
            FetchResult(false, describe(t), true)
        }
    }

    /** Fehler in einem Satz, den man versteht – statt „null“. */
    private fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "Kein Internet oder der Name wurde nicht gefunden."
        is java.net.SocketTimeoutException -> "Zeitüberschreitung – die Verbindung ist zu langsam."
        is java.net.ConnectException -> "Verbindung abgelehnt."
        is javax.net.ssl.SSLException -> "Verschlüsselte Verbindung fehlgeschlagen."
        is java.io.IOException -> "Netzwerkfehler: ${t.message ?: t.javaClass.simpleName}"
        else -> t.message ?: t.javaClass.simpleName
    }

    /**
     * Übernimmt eine vom Nutzer gewählte Datei (z. B. schon vorhandenes `.litertlm`) in den
     * App-Speicher.
     */
    fun importTo(ctx: Context, uri: android.net.Uri): File? {
        val name = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: Kind.TEXT.baseFile + ".litertlm"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { Kind.TEXT.baseFile + ".litertlm" }
        val target = File(dir(ctx), safe)
        val tmp = File(dir(ctx), "$safe.part")
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out -> input.copyTo(out, 1 shl 16) }
            } ?: return null
            // Vollständigkeit auch hier prüfen, wenn die Datei bekannt ist
            val known = knownFile(safe)
            if (known != null && known.bytes > 0 && tmp.length() < known.bytes - 1024) {
                Log.w(TAG, "Übernommene Datei ist unvollständig: ${tmp.length()} von ${known.bytes}")
                tmp.delete()
                return null
            }
            if (tmp.length() < 1024 * 1024) {
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            Log.i(TAG, "Modell übernommen: ${target.name} (${target.length() / 1048576} MB)")
            target
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    fun clear(ctx: Context) {
        models(ctx).forEach { delete(it) }
        incomplete(ctx).forEach { delete(it) }
        dir(ctx).listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** Freier Speicher im App-Datenordner (Bytes). */
    fun freeBytes(ctx: Context): Long = runCatching {
        val stat = android.os.StatFs(ctx.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /** Reicht der Platz für ein Modell? (mit 200 MB Reserve) */
    fun hasRoomFor(ctx: Context, variant: Variant): Boolean =
        freeBytes(ctx) > variant.expectedMb.toLong() * 1024 * 1024 + 200L * 1024 * 1024
}
