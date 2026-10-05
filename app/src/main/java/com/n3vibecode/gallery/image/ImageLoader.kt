package com.n3vibecode.gallery.image

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.n3vibecode.gallery.data.MediaItem
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bildlader für das Raster – gebaut für „Echtzeit“-Gefühl wie beim iPhone:
 *
 *  • **Neueste Anfrage zuerst (LIFO):** Was gerade sichtbar wird, wird zuerst geladen –
 *    egal wie viele Anfragen für längst weggescrollte Kacheln noch warten.
 *  • **Abbrechen:** Wird eine Kachel wiederverwendet oder neu belegt, wird ihre alte
 *    Anfrage verworfen (siehe [cancel]).
 *  • **Mehrere Threads** (4–8, je nach CPU) statt 4, getrennt vom Laden der Vollbilder.
 *  • **Schneller Weg:** Android-Systemvorschau (`loadThumbnail`, ab Android 10) –
 *    bei alten Fotos und Videos oft 10–50× schneller als selbst dekodieren.
 *  • **Festplatten-Cache:** Jede Vorschau wird einmal erzeugt und danach in Millisekunden
 *    gelesen – auch nach App-Neustart (wichtig für RAW/HEIC, die sonst jedes Mal neu
 *    dekodiert werden müssten).
 *  • **Größenstufen** (128/256/512/1024): Beim Zoomen bleibt der Cache gültig, und eine
 *    schon vorhandene Vorschau wird sofort gezeigt, während die schärfere nachlädt.
 */
object ImageLoader {

    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ Größenstufen

    private val BUCKETS = intArrayOf(128, 256, 512, 1024)

    private fun bucketFor(px: Int): Int {
        for (b in BUCKETS) if (px <= b) return b
        return BUCKETS.last()
    }

    // ------------------------------------------------------------------ Speicher-Cache

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun cacheSizeBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 4).coerceIn(32L * 1024 * 1024, 160L * 1024 * 1024).toInt()
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bmp: Bitmap) {
        cache.put(key, bmp)
    }

    /** Leert den Speicher-Cache (Vorschauen auf der Festplatte bleiben gültig). */
    fun clear() {
        cache.evictAll()
    }

    /** Zusätzlich alles vergessen (nach Bearbeiten/Überschreiben einer Datei). */
    fun clearAll(ctx: Context) {
        cache.evictAll()
        prefetched.clear()
        failed.clear()
    }

    fun keyFor(item: MediaItem, sizePx: Int): String = item.uri + "#" + sizePx

    // ------------------------------------------------------------------ Thread-Pools

    /** Warteschlange für Vorschauen: neueste Anfrage zuerst. */
    private val queue = LinkedBlockingDeque<Task>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureWorkers() {
        if (!started.compareAndSet(false, true)) return
        val n = AtomicInteger(1)
        repeat(WORKERS) {
            Thread({
                // Dekodieren läuft bewusst mit Hintergrund-Priorität: Wischen und Scrollen
                // fühlen sich dadurch sofort an, auch wenn im Hintergrund Vorschaubilder
                // entstehen. Vorher haben sich Dekoder und Oberfläche die CPU geteilt.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                while (true) {
                    val t = try {
                        queue.takeFirst()
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    try {
                        // Großansicht hat Vorrang: Raster-Kacheln warten kurz, solange ein
                        // angetipptes Foto lädt (sonst teilen sich beide CPU und Video-Decoder)
                        var waited = 0
                        while (interactiveBusy.get() > 0 && waited < 12) {
                            Thread.sleep(20)
                            waited++
                        }
                        t.run()
                    } catch (_: Throwable) {
                        // einzelne Fehler dürfen den Worker nie beenden
                    }
                }
            }, "n3-thumb-${n.getAndIncrement()}").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
                start()
            }
        }
    }

    /** Anzahl gerade laufender Großansicht-Aufgaben (Vorschau/Vollbild). */
    private val interactiveBusy = AtomicInteger(0)

    /**
     * Eigener Pool für die Großansicht (Vorschau + Vollbild), damit ein angetipptes Foto
     * nie hinter den Raster-Kacheln wartet – auch bei 20 000 Fotos nicht.
     */
    private val fullPool = Executors.newFixedThreadPool(3, object : ThreadFactory {
        private val n = AtomicInteger(1)
        override fun newThread(r: Runnable): Thread =
            Thread(r, "n3-full-${n.getAndIncrement()}").apply { priority = Thread.NORM_PRIORITY }
    })

    /**
     * Anzahl Dekodier-Threads: etwa die Hälfte der CPU-Kerne plus einer (3–6).
     * Die Threads laufen mit Hintergrund-Priorität, kosten die Oberfläche also kaum
     * Reaktionszeit – mehr Threads bedeuten hier spürbar schneller gefüllte Kacheln.
     */
    val WORKERS: Int = (Runtime.getRuntime().availableProcessors() / 2 + 1).coerceIn(3, 6)

    // ------------------------------------------------------------------ Aufgaben

    private class Task(
        val appCtx: Context,
        val item: MediaItem,
        val bucket: Int,
        val key: String,
        target: ImageView?,
        val onDone: ((Bitmap?) -> Unit)?
    ) {
        val targetRef: WeakReference<ImageView>? = target?.let { WeakReference(it) }
        @Volatile var cancelled = false
        @Volatile var finished = false
        val signal = CancellationSignal()

        fun cancel() {
            cancelled = true
            runCatching { signal.cancel() }
        }

        fun run() {
            if (cancelled) { finished = true; return }
            val bmp = obtainThumb(appCtx, item, bucket, signal)
            if (cancelled) {
                // Ergebnis liegt im Cache – wird beim nächsten Binden sofort gezeigt
                finished = true
                return
            }
            main.post {
                finished = true
                if (cancelled) return@post
                val view = targetRef?.get()
                if (view != null) {
                    if (view.tag == key && bmp != null) {
                        val wasEmpty = view.drawable == null
                        view.setImageBitmap(bmp)
                        // Nur beim allerersten Bild sanft einblenden – beim Scrollen wäre
                        // eine Animation je Kachel nur zusätzliche Arbeit.
                        if (wasEmpty) {
                            view.alpha = 0f
                            view.animate().alpha(1f).setDuration(90).start()
                        } else {
                            view.alpha = 1f
                        }
                    }
                    pending.remove(view)
                }
                onDone?.invoke(bmp)
            }
        }
    }

    /** Offene Anfragen pro Kachel (nur Haupt-Thread). */
    private val pending = WeakHashMap<ImageView, Task>()

    /** Dateien, die sich nachweislich nicht dekodieren lassen – nicht ständig neu versuchen. */
    private val failed = ConcurrentHashMap<String, Boolean>()

    // ------------------------------------------------------------------ Öffentliche API

    /**
     * Lädt ein Vorschaubild in eine ImageView. Liegt schon irgendeine Größenstufe im
     * Speicher, erscheint sie sofort; fehlt die passende, wird sie nachgeladen.
     */
    fun into(ctx: Context, item: MediaItem, sizePx: Int, target: ImageView, placeholder: Bitmap? = null) {
        val bucket = bucketFor(sizePx)
        val key = keyFor(item, bucket)
        target.tag = key

        // Läuft für diese Kachel schon genau diese Anfrage? Dann nichts doppelt tun.
        val old = pending[target]
        if (old != null && !old.finished) {
            if (old.key == key && !old.cancelled) return
            old.cancel()
        }
        pending.remove(target)

        target.animate().cancel()
        target.alpha = 1f

        // 1) passende oder größere Stufe im Speicher → fertig
        for (b in BUCKETS) {
            if (b < bucket) continue
            val hit = cache.get(keyFor(item, b))
            if (hit != null) {
                target.setImageBitmap(hit)
                return
            }
        }

        // 2) kleinere Stufe als sofortiger Platzhalter (unscharf → scharf)
        var shown = false
        for (b in BUCKETS.reversed()) {
            if (b >= bucket) continue
            val low = cache.get(keyFor(item, b))
            if (low != null) {
                target.setImageBitmap(low)
                shown = true
                break
            }
        }
        if (!shown) target.setImageBitmap(placeholder)

        if (failed.containsKey(item.uri)) return

        val task = Task(ctx.applicationContext, item, bucket, key, target, null)
        pending[target] = task
        ensureWorkers()
        queue.addFirst(task)
    }

    /** Anfrage dieser Kachel verwerfen (beim Wiederverwenden der Kachel aufrufen). */
    fun cancel(target: ImageView) {
        pending.remove(target)?.cancel()
        target.animate().cancel()
        target.alpha = 1f
    }

    // ------------------------------------------------------------------ Vorladen (Prefetch)

    /** Schon vorgeladene Kombinationen – verhindert doppelte Arbeit. */
    private val prefetched = ConcurrentHashMap.newKeySet<String>()

    /**
     * Vorschaubilder im Voraus erzeugen (unterste Priorität, ohne Anzeige).
     *
     * Damit liegen die Bilder der nächsten Bildschirme schon im Speicher- bzw.
     * Festplatten-Cache, wenn sie beim Scrollen sichtbar werden – statt erst dann
     * dekodiert zu werden. Das ist der Unterschied zwischen „nicht alles sofort da“
     * und einem Raster, das beim Wischen durchgehend gefüllt ist.
     */
    fun prefetch(ctx: Context, items: List<MediaItem>, sizePx: Int, limit: Int = 48) {
        if (items.isEmpty()) return
        val app = ctx.applicationContext
        val bucket = bucketFor(sizePx)
        var queued = 0
        for (item in items) {
            if (queued >= limit) break
            val key = keyFor(item, bucket)
            if (cache.get(key) != null) continue
            if (failed.containsKey(item.uri)) continue
            if (!prefetched.add(key)) continue
            if (diskFile(app, item, bucket).exists()) continue
            ensureWorkers()
            queue.addLast(Task(app, item, bucket, key, null, null))
            queued++
        }
    }

    /** Nur zum Testen/Diagnose: wie viele Kacheln schon vorgeladen wurden. */
    fun prefetchedCount(): Int = prefetched.size

    /**
     * Synchrone Vorschau für Hintergrund-Threads (z. B. Gesamtübersicht).
     * Nutzt Speicher-Cache, Festplatten-Cache und Systemvorschau.
     */
    fun thumbnailBlocking(ctx: Context, item: MediaItem, sizePx: Int): Bitmap? {
        val bucket = bucketFor(sizePx)
        for (b in BUCKETS) {
            if (b < bucket) continue
            cache.get(keyFor(item, b))?.let { return it }
        }
        if (failed.containsKey(item.uri)) return null
        return obtainThumb(ctx.applicationContext, item, bucket, null)
    }

    /** Beste schon vorhandene Vorschau (größte Stufe zuerst) – sofort, ohne Warten. */
    fun bestCached(item: MediaItem): Bitmap? {
        for (b in BUCKETS.reversed()) cache.get(keyFor(item, b))?.let { return it }
        return null
    }

    /**
     * Schnelle Vorschau (1024 px) für die Großansicht: Antippen zeigt sofort etwas Scharfes,
     * während das Vollbild noch dekodiert. Läuft vor allen wartenden Raster-Kacheln.
     */
    fun loadPreview(ctx: Context, item: MediaItem, onDone: (Bitmap?) -> Unit) {
        cache.get(keyFor(item, BUCKETS.last()))?.let { onDone(it); return }
        val appCtx = ctx.applicationContext
        interactiveBusy.incrementAndGet()
        fullPool.execute {
            val bmp = try {
                obtainThumb(appCtx, item, BUCKETS.last(), null)
            } finally {
                interactiveBusy.decrementAndGet()
            }
            main.post { onDone(bmp) }
        }
    }

    /** Zielgröße für die Großansicht: etwa Bildschirmgröße (Zoom schärft nicht endlos nach, dafür sofort da). */
    fun detailPx(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return (maxOf(dm.widthPixels, dm.heightPixels) * 1.25f).toInt()
    }

    /** Volle Auflösung asynchron (für die Detailansicht). */
    fun loadFull(ctx: Context, item: MediaItem, maxPx: Int, onDone: (Bitmap?) -> Unit) {
        loadFullDetailed(ctx, item, maxPx) { bmp, _ -> onDone(bmp) }
    }

    /** Wie [loadFull], meldet aber zusätzlich, über welchen Weg dekodiert wurde. */
    fun loadFullDetailed(ctx: Context, item: MediaItem, maxPx: Int, onDone: (Bitmap?, DecodePath) -> Unit) {
        val key = keyFor(item, maxPx)
        val hit = cache.get(key)
        if (hit != null) {
            onDone(hit, DecodePath.NATIVE)
            return
        }
        interactiveBusy.incrementAndGet()
        fullPool.execute {
            val result = try {
                Decoder.decodeDetailed(ctx.applicationContext, item, maxPx)
            } finally {
                interactiveBusy.decrementAndGet()
            }
            result.bitmap?.let { cache.put(key, it) }
            main.post { onDone(result.bitmap, result.path) }
        }
    }

    fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ------------------------------------------------------------------ Vorschau erzeugen

    /** Speicher → Festplatte → Systemvorschau → eigener Dekoder. */
    private fun obtainThumb(ctx: Context, item: MediaItem, bucket: Int, signal: CancellationSignal?): Bitmap? {
        val key = keyFor(item, bucket)
        cache.get(key)?.let { return it }

        // Festplatten-Cache (genau diese Größenstufe)
        val file = diskFile(ctx, item, bucket)
        if (file.exists()) {
            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (bmp != null) {
                cache.put(key, bmp)
                return bmp
            }
            runCatching { file.delete() }
        }
        if (signal?.isCanceled == true) return null

        // Schon eine andere Größenstufe auf der Festplatte? Dann passend skalieren statt
        // neu zu dekodieren. Das ist der Unterschied zwischen „Bild ist sofort da“ und
        // mehreren Sekunden Wartezeit – besonders bei HEIC/RAW, wo Dekodieren teuer ist.
        cachedThumbAnySize(ctx, item, bucket)?.let { reuse ->
            cache.put(key, reuse)
            writeDisk(ctx, file, reuse)
            return reuse
        }
        if (signal?.isCanceled == true) return null

        // Systemvorschau (Android 10+) – schnell, auch für Videos und alte Fotos
        var bmp: Bitmap? = systemThumbnail(ctx, item, bucket, signal)

        // Eigener Dekoder
        if (bmp == null && signal?.isCanceled != true) {
            bmp = try {
                Decoder.decode(ctx, item, bucket)
            } catch (_: OutOfMemoryError) {
                cache.evictAll()
                null
            }
            if (bmp == null) failed[item.uri] = true
        }

        if (bmp != null) {
            cache.put(key, bmp)
            writeDisk(ctx, file, bmp)
        }
        return bmp
    }

    /**
     * Sucht die nächstbeste schon vorhandene Vorschau (andere Größenstufe) und skaliert sie
     * auf die gewünschte Kantenlänge. Nur brauchbare Größen werden verwendet – bei zu kleinen
     * Vorschauen wird lieber richtig dekodiert (Qualität geht vor).
     */
    private fun cachedThumbAnySize(ctx: Context, item: MediaItem, bucket: Int): Bitmap? {
        val candidates = BUCKETS.filter { it != bucket }.sortedBy { kotlin.math.abs(it - bucket) }
        for (b in candidates) {
            val f = diskFile(ctx, item, b)
            if (!f.exists()) continue
            val src = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: continue
            val longest = maxOf(src.width, src.height)
            if (longest <= 0) { continue }
            // Zu klein für diese Kachel? Dann lieber weiter suchen / neu dekodieren.
            if (longest < bucket * 0.75f) { src.recycle(); continue }
            val scale = bucket.toFloat() / longest
            val scaled = if (scale < 0.99f) {
                val w = (src.width * scale).toInt().coerceAtLeast(1)
                val h = (src.height * scale).toInt().coerceAtLeast(1)
                runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrNull()?.also {
                    if (it !== src) src.recycle()
                }
            } else src
            if (scaled != null) return scaled
        }
        return null
    }

    private fun systemThumbnail(ctx: Context, item: MediaItem, bucket: Int, signal: CancellationSignal?): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        // RAW und HEIF-Exoten lieber über die eigene Engine (gleiche Darstellung wie in der Großansicht)
        if (item.isRaw || item.isAvif) return null
        val uri = Uri.parse(item.uri)
        if (uri.scheme != "content" || uri.authority != "media") return null
        return try {
            val id = ContentUris.parseId(uri)
            val typed = if (item.isVideoFile) {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            } else {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            val bmp = ctx.contentResolver.loadThumbnail(typed, Size(bucket, bucket), signal)
            // zu kleine Systemvorschau (z. B. 96 px) nicht für große Kacheln verwenden
            if (bmp != null && maxOf(bmp.width, bmp.height) * 2 < bucket && bucket > 128) null else bmp
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ Festplatten-Cache

    private fun cacheDir(ctx: Context): File =
        File(ctx.cacheDir, "thumbs").also { if (!it.exists()) it.mkdirs() }

    private fun diskFile(ctx: Context, item: MediaItem, bucket: Int): File {
        val raw = "${item.uri}|${item.size}|${item.modifiedAt}|$bucket"
        val md = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        val name = md.joinToString("") { "%02x".format(it) }
        return File(cacheDir(ctx), "$name.img")
    }

    private val writes = AtomicInteger(0)

    private fun writeDisk(ctx: Context, file: File, bmp: Bitmap) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                val fmt = if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bmp.compress(fmt, 85, out)
            }
            if (!tmp.renameTo(file)) tmp.delete()
            if (writes.incrementAndGet() % 300 == 0) trimDisk(ctx)
        } catch (_: Throwable) {
        }
    }

    /** Hält den Vorschau-Ordner unter ca. 400 MB (älteste Dateien zuerst weg). */
    private fun trimDisk(ctx: Context) {
        try {
            val files = cacheDir(ctx).listFiles() ?: return
            var total = files.sumOf { it.length() }
            val limit = 400L * 1024 * 1024
            if (total <= limit) return
            for (f in files.sortedBy { it.lastModified() }) {
                total -= f.length()
                f.delete()
                if (total <= limit * 0.8) break
            }
        } catch (_: Throwable) {
        }
    }
}
