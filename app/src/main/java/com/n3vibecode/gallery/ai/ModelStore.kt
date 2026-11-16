package com.n3vibecode.gallery.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Verwaltet das **Gemma-Embedding-Modell** auf dem Gerät.
 *
 * Warum eine eigene Ablage? Das Modell ist ~ 200 MB groß. Es liegt deshalb **nicht** im
 * App-Paket (das würde jede Installation aufblähen), sondern wird einmal heruntergeladen und
 * im App-Speicher abgelegt:
 *
 * ```
 * /data/data/com.n3vibecode.gallery/files/models/embedding_gemma.task
 * ```
 *
 * **Der Download selbst läuft nicht durch die App.** Die App hat bewusst keine
 * Internet-Berechtigung (das ist eine Zusage dieser App, prüfbar in der Manifest-Datei).
 * Der Knopf in der Suche übergibt die offizielle Adresse an den Browser bzw. den
 * Download-Manager des Geräts – das Herunterladen macht also der Browser, nicht die App.
 * Danach wird die Datei einmal ausgewählt ([importFrom]) und liegt im App-Speicher; ab dann
 * arbeitet die Suche vollständig offline.
 */
object ModelStore {

    private const val TAG = "N3Ai"

    /** Ordner im App-Speicher (kein Zugriff von außen nötig → keine Berechtigung). */
    private const val DIR = "models"

    /**
     * Offizielle Bezugsadresse des Gemma-Embedding-Modells (Google AI Edge / MediaPipe).
     * EmbeddingGemma 300M, int4/int8-quantisiert – läuft per LiteRT auch auf der NPU.
     */
    const val DOWNLOAD_URL =
        "https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task"

    /** Kleiner Ersatz (6 MB) – falls jemand kein 200-MB-Modell laden möchte. */
    const val DOWNLOAD_URL_SMALL =
        "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"

    const val FILE_NAME = "embedding_gemma.task"

    /** Erwartete Dateiendungen eines Modells. */
    private val EXTENSIONS = listOf(".task", ".tflite")

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Alle vorhandenen Modelldateien, größte zuerst (das große Modell ist das gute). */
    fun models(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { f -> f.isFile && EXTENSIONS.any { f.name.endsWith(it, true) } && f.length() > 1024 }
            ?.sortedByDescending { it.length() }
            ?: emptyList()

    /** Das beste vorhandene Modell (oder `null`). */
    fun best(ctx: Context): File? = models(ctx).firstOrNull()

    fun hasModel(ctx: Context): Boolean = best(ctx) != null

    /**
     * Kopiert eine vom Nutzer ausgewählte Datei in den App-Speicher.
     * Gibt die Zieldatei zurück oder `null`, wenn das Lesen scheitert.
     */
    fun importFrom(ctx: Context, uri: Uri): File? {
        val name = queryName(ctx, uri) ?: FILE_NAME
        // Sicherer Dateiname: keine Pfadanteile, nur Buchstaben/Ziffern/Punkt/Bindestrich
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { FILE_NAME }
        val target = File(dir(ctx), if (EXTENSIONS.any { safe.endsWith(it, true) }) safe else "$safe.task")
        val tmp = File(dir(ctx), "${target.name}.part")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out -> input.copyTo(out, 1 shl 16) }
            } ?: return null
            if (tmp.length() < 1024) {
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            Log.i(TAG, "Modell übernommen: ${target.name} (${target.length() / 1048576} MB)")
            target
        } catch (t: Throwable) {
            Log.w(TAG, "Modell konnte nicht übernommen werden: ${t.message}")
            tmp.delete()
            null
        }
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** Alle Modelle löschen (z. B. um Platz zu schaffen). */
    fun clear(ctx: Context) {
        models(ctx).forEach { delete(it) }
    }

    /** Dateiname aus einer Content-URI lesen (Anzeigename des Dokuments). */
    private fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}
