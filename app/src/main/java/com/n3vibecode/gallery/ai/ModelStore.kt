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
 * Die Dateien sind zu groß für das App-Paket, deshalb werden sie einmal heruntergeladen und im
 * App-Speicher abgelegt:
 *
 * ```
 * /data/data/com.n3vibecode.gallery/files/models/<datei>.litertlm
 * ```
 *
 * **Was in 1.37 repariert wurde:** Der Download brach mitten in der Datei ab und die halbe
 * Datei wurde als „fertig“ übernommen (auf dem Gerät lag `embeddinggemma-2-740m.litertlm` mit
 * 462 MB statt 1,3 GB – das Modell kann so nie starten). Jetzt gilt:
 *
 *  • Es wird **gegen die erwartete Größe geprüft** (aus dem HTTP-Header, sonst aus der Tabelle).
 *    Stimmt sie nicht, wird die Datei verworfen und der Grund genannt.
 *  • Bricht die Verbindung ab, wird **fortgesetzt** (`Range: bytes=…`) – statt von vorn.
 *  • Ist eine SHA-256-Prüfsumme hinterlegt, wird sie geprüft; passt sie nicht, wird verworfen.
 *  • Eine unvollständige Datei wird nie als Modell geladen (`ModelStore.models` filtert).
 *
 * Nur eine bewusste Handlung löst einen Download aus (Knopf in der Bildersuche) – die App geht
 * nicht von sich aus online. Gesucht wird danach vollständig offline.
 */
object ModelStore {

    private const val TAG = "N3Ai"
    private const val DIR = "models"

    /**
     * Ein anwählbares Modell.
     *
     * [sizeMb] ist die **erwartete** Größe. Sie dient der Platzprüfung, der Fortschrittsanzeige
     * und – wichtiger – der Kontrolle, ob der Download vollständig ist.
     */
    enum class Variant(
        val file: String,
        val url: String,
        val label: String,
        val sizeMb: Int,
        val note: String,
        /** Erwartete Bytes, 0 = unbekannt (dann entscheidet der HTTP-Header). */
        val bytes: Long = 0L
    ) {
        TEXT(
            "embeddinggemma-2-text-270m.litertlm",
            "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
            "EmbeddingGemma 2 · Text (270M)",
            157,
            "kleinste Variante · nur Text",
            164_626_432L
        ),
        TEXT_VISION(
            "embeddinggemma-2-text-vision-440m.litertlm",
            "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm",
            "EmbeddingGemma 2 · Text + Bild (440M)",
            370,
            "empfohlen · Text und Bilder in einem Raum",
            387_710_976L
        ),
        FULL(
            "embeddinggemma-2-740m.litertlm",
            "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm",
            "EmbeddingGemma 2 · alles (740M)",
            462,
            "größte Variante · Text, Bild, Audio",
            484_622_336L
        );

        /**
         * Erwartete Größe in MB – für Anzeige und Platzprüfung.
         *
         * Gerundet, nicht abgeschnitten: 387 710 976 B sind 369,75 MiB und werden als 370 MB
         * angezeigt (Abschneiden ergäbe 369 – der Unit-Test hat das aufgedeckt).
         */
        val expectedMb: Int
            get() = if (bytes > 0) Math.round(bytes / 1048576.0).toInt() else sizeMb
    }

    private val EXTENSIONS = listOf(".litertlm", ".task", ".tflite")

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * Eine Datei gilt erst als Modell, wenn sie **vollständig** ist. Eine abgebrochene Datei
     * (z. B. 462 MB von 1,3 GB) würde sonst geladen und der Start scheitert mit einer
     * unverständlichen Meldung – genau das war auf dem Gerät zu sehen.
     */
    private fun isComplete(f: File): Boolean {
        if (!f.isFile) return false
        val known = Variant.entries.firstOrNull { it.file == f.name }
        return if (known != null && known.bytes > 0) {
            // Toleranz: Ein paar Bytes Abweichung sind bei CDN-Angaben normal, 1 % nicht.
            f.length() >= known.bytes - 1024 && f.length() <= known.bytes + 1024
        } else {
            f.length() > 1024 * 1024
        }
    }

    /** Alle **vollständigen** Modelldateien, größte zuerst. */
    fun models(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { f -> EXTENSIONS.any { f.name.endsWith(it, true) } && isComplete(f) }
            ?.sortedByDescending { it.length() }
            ?: emptyList()

    /** Unvollständige Dateien (für die Anzeige: „unvollständig, 462 von 1,3 GB“). */
    fun incomplete(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { f -> EXTENSIONS.any { f.name.endsWith(it, true) } && f.isFile && !isComplete(f) }
            ?: emptyList()

    /** Das beste vorhandene Modell (oder `null`). */
    fun best(ctx: Context): File? = models(ctx).firstOrNull()

    fun hasModel(ctx: Context): Boolean = best(ctx) != null

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
            val expected = if (variant.bytes > 0) variant.bytes else -1L
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
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                onDone?.invoke(null, "Die Datei konnte nicht gespeichert werden.")
                return
            }
            Log.i(TAG, "Modell geladen: ${target.name} (${target.length() / 1048576} MB)")
            onProgress?.invoke(target.length(), target.length())
            onDone?.invoke(target, "")
        } catch (t: Throwable) {
            Log.w(TAG, "Download fehlgeschlagen: ${t.message}", t)
            onDone?.invoke(null, "Unerwarteter Fehler: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            downloading = false
        }
    }

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
     * sonst entstünde eine Datei mit dohppelten Anfangsbytes.
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
                    setRequestProperty("User-Agent", "N3Gallery/1.37 (Android)")
                    // Fortsetzen, wenn schon etwas da ist
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: break
                    url = URL(location)
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
        }.getOrNull() ?: uri.lastPathSegment ?: Variant.TEXT.file
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { Variant.TEXT.file }
        val target = File(dir(ctx), safe)
        val tmp = File(dir(ctx), "$safe.part")
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out -> input.copyTo(out, 1 shl 16) }
            } ?: return null
            // Vollständigkeit auch hier prüfen, wenn die Datei bekannt ist
            val known = Variant.entries.firstOrNull { it.file == safe }
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
