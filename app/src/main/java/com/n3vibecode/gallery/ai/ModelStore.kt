package com.n3vibecode.gallery.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verwaltet das **EmbeddingGemma-2-Modell** auf dem Gerät – inklusive **Download in der App**.
 *
 * Die Dateien sind zu groß für das App-Paket (163 MB / 530 MB / 1,3 GB), deshalb werden sie
 * einmal heruntergeladen und im App-Speicher abgelegt:
 *
 * ```
 * /data/data/com.n3vibecode.gallery/files/models/<datei>.litertlm
 * ```
 *
 * Der Download läuft in einem eigenen Thread, meldet Fortschritt und lässt sich abbrechen.
 * Er wird nur durch eine bewusste Handlung ausgelöst (Knopf in der Bildersuche) – die App
 * geht nicht von sich aus online. Gesucht wird danach vollständig offline.
 */
object ModelStore {

    private const val TAG = "N3Ai"
    private const val DIR = "models"

    /** Ein anwählbares Modell. */
    enum class Variant(
        val file: String,
        val url: String,
        val label: String,
        val sizeMb: Int,
        val note: String
    ) {
        TEXT(
            "embeddinggemma-2-text-270m.litertlm",
            "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
            "EmbeddingGemma 2 · Text (270M)",
            163,
            "kleinste Variante · nur Text"
        ),
        TEXT_VISION(
            "embeddinggemma-2-text-vision-440m.litertlm",
            "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm",
            "EmbeddingGemma 2 · Text + Bild (440M)",
            530,
            "empfohlen · Text und Bilder in einem Raum"
        ),
        FULL(
            "embeddinggemma-2-740m.litertlm",
            "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm",
            "EmbeddingGemma 2 · alles (740M)",
            1300,
            "größte Variante · Text, Bild, Audio"
        )
    }

    private val EXTENSIONS = listOf(".litertlm", ".task", ".tflite")

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Alle vorhandenen Modelldateien, größte zuerst. */
    fun models(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { f -> f.isFile && EXTENSIONS.any { f.name.endsWith(it, true) } && f.length() > 1024 * 1024 }
            ?.sortedByDescending { it.length() }
            ?: emptyList()

    /** Das beste vorhandene Modell (oder `null`). */
    fun best(ctx: Context): File? = models(ctx).firstOrNull()

    fun hasModel(ctx: Context): Boolean = best(ctx) != null

    // ------------------------------------------------------------------ Download

    @Volatile private var downloading = false
    @Volatile private var cancelRequested = false
    private val cancelled = AtomicBoolean(false)

    fun isDownloading(): Boolean = downloading

    fun cancel() {
        cancelRequested = true
        cancelled.set(true)
    }

    /**
     * Lädt ein Modell herunter (im Hintergrund-Thread, den der Aufrufer stellt).
     *
     * [onProgress] bekommt (geladene Bytes, Gesamt-Bytes oder -1). [onDone] bekommt die
     * fertige Datei oder `null` (abgebrochen/fehlgeschlagen).
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
        cancelRequested = false
        cancelled.set(false)
        val app = ctx.applicationContext
        val target = File(dir(app), variant.file)
        val tmp = File(dir(app), "${variant.file}.part")
        var connection: HttpURLConnection? = null
        try {
            var url = URL(variant.url)
            // Hugging Face leitet auf den CDN um – Redirects manuell folgen (max. 5)
            var hops = 0
            while (hops++ < 5) {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "N3Gallery/1.34 (Android)")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: break
                    url = URL(location)
                    connection.disconnect()
                    continue
                }
                if (code != 200) {
                    Log.w(TAG, "Download HTTP $code für ${variant.file}")
                    onDone?.invoke(
                        null,
                        if (code == 404) {
                            "Die Datei gibt es unter dieser Adresse nicht (HTTP 404)."
                        } else {
                            "Der Server antwortet mit HTTP $code."
                        }
                    )
                    return
                }
                break
            }
            val conn = connection ?: run {
                onDone?.invoke(null, "Keine Verbindung zum Server.")
                return
            }
            val total = conn.contentLengthLong
            tmp.delete()
            conn.inputStream.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled.get()) {
                            tmp.delete()
                            onDone?.invoke(null, "Abgebrochen.")
                            return
                        }
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        done += n
                        // höchstens alle 2 MB melden (spart UI-Arbeit)
                        if (done - lastReport > 2L * 1024 * 1024 || done == total) {
                            lastReport = done
                            onProgress?.invoke(done, total)
                        }
                    }
                }
            }
            if (tmp.length() < 1024 * 1024) {
                val got = tmp.length()
                tmp.delete()
                onDone?.invoke(
                    null,
                    "Die Datei ist zu klein ($got Bytes) – die Adresse liefert keine Modelldatei."
                )
                return
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                onDone?.invoke(null, "Die Datei konnte nicht gespeichert werden.")
                return
            }
            Log.i(TAG, "Modell geladen: ${target.name} (${target.length() / 1048576} MB)")
            onProgress?.invoke(target.length(), target.length())
            onDone?.invoke(target, "")
        } catch (t: Throwable) {
            Log.w(TAG, "Download fehlgeschlagen: ${t.message}", t)
            tmp.delete()
            onDone?.invoke(null, describe(t))
        } finally {
            runCatching { connection?.disconnect() }
            downloading = false
            cancelRequested = false
        }
    }

    /** Fehler in einem Satz, den man versteht – statt „null“. */
    private fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "Kein Internet oder der Name wurde nicht gefunden."
        is java.net.SocketTimeoutException -> "Zeitüberschreitung – Verbindung zu langsam."
        is java.net.ConnectException -> "Verbindung abgelehnt."
        is javax.net.ssl.SSLException -> "Verschlüsselte Verbindung fehlgeschlagen."
        is java.io.IOException -> "Netzwerkfehler: ${t.message ?: t.javaClass.simpleName}"
        else -> t.message ?: t.javaClass.simpleName
    }

    /**
     * Übernimmt eine vom Nutzer gewählte Datei (z. B. schon vorhandenes `.litertlm`)
     * in den App-Speicher.
     */
    fun importTo(ctx: Context, uri: android.net.Uri): File? {
        val name = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: Variant.TEXT.file
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { Variant.TEXT.file }
        val target = File(dir(ctx), safe)
        val tmp = File(dir(ctx), "$safe.part")
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out -> input.copyTo(out, 1 shl 16) }
            } ?: return null
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
    }

    /** Freier Speicher im App-Datenordner (Bytes). */
    fun freeBytes(ctx: Context): Long = runCatching {
        val stat = android.os.StatFs(ctx.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /** Reicht der Platz für ein Modell? (mit 150 MB Reserve) */
    fun hasRoomFor(ctx: Context, variant: Variant): Boolean =
        freeBytes(ctx) > variant.sizeMb.toLong() * 1024 * 1024 + 150L * 1024 * 1024
}
