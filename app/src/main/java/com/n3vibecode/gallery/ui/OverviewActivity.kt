package com.n3vibecode.gallery.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.image.ImageLoader
import java.util.concurrent.atomic.AtomicInteger
import com.n3vibecode.gallery.widget.MosaicView
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * „Alle Fotos auf einen Blick“: alle Bilder der aktuellen Liste als **ein** Mosaik,
 * das möglichst komplett auf den Bildschirm passt – 500, 5 000 oder 50 000 Fotos
 * in einer einzigen Ansicht. Kein Raster-Limit, kein Scrollen.
 *
 * Die Kacheln werden nach und nach gefüllt (die Ansicht ist sofort da), jede Kachel
 * ist antippbar und öffnet das jeweilige Foto in der Großansicht.
 */
class OverviewActivity : AppCompatActivity() {

    private lateinit var mosaic: MosaicView
    private lateinit var progress: TextView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overview)

        val list = ViewState.viewList
        if (list.isEmpty()) {
            finish()
            return
        }

        mosaic = findViewById(R.id.mosaic)
        progress = findViewById(R.id.tvProgress)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        tvTitle.text = getString(R.string.overview_title) + " · " + getString(R.string.count_files, list.size)

        mosaic.onCellTap = { index ->
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_POSITION, index)
            )
        }

        buildMosaic(list)
    }

    /**
     * Mosaik aufbauen: Spalten und Zeilen so wählen, dass die Fläche dem Bildschirm
     * entspricht – dadurch ist die gesamte Sammlung in einer Ansicht sichtbar.
     */
    private fun buildMosaic(list: List<MediaItem>) {
        if (running) return
        running = true

        val dm = resources.displayMetrics
        val w = min(dm.widthPixels, 1600).coerceAtLeast(480)
        val h = min(dm.heightPixels, 3200).coerceAtLeast(480)
        val n = list.size

        val cols = ceil(sqrt(n.toDouble() * w / h)).toInt().coerceIn(1, maxOf(1, n))
        val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
        val cellW = w.toFloat() / cols
        val cellH = h.toFloat() / rows

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#16161C"))
        mosaic.setMosaic(bmp, cols, rows, n)
        mosaic.post { mosaic.refit() }
        progress.text = "0 / $n"

        // Kachelgröße für die Vorschau – etwas größer als die Zelle für klare Kanten
        val thumbPx = (maxOf(cellW, cellH) * 2f).toInt().coerceIn(24, 384)

        // Alle Fotos parallel laden (je nach CPU 4–8 Threads), von neu nach alt, ohne Zeitlimit.
        // Früher lief das einzeln und brach nach 30 s ab – die ältesten Fotos blieben dunkel.
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val live = AtomicInteger(WORKERS)
        val paintLock = Any()
        val appCtx = applicationContext

        repeat(WORKERS) {
            Thread {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                val src = Rect()
                val dst = RectF()
                while (!cancelled) {
                    val i = next.getAndIncrement()
                    if (i >= n) break
                    val b = runCatching { ImageLoader.thumbnailBlocking(appCtx, list[i], thumbPx) }.getOrNull()
                    val finished = done.incrementAndGet()
                    if (b != null) {
                        val c = i % cols
                        val r = i / cols
                        dst.set(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH)

                        // Quellbereich mittig zuschneiden, damit die Zelle gefüllt ist
                        val dstAspect = cellW / cellH
                        val srcAspect = b.width.toFloat() / b.height
                        if (srcAspect > dstAspect) {
                            val newW = (b.height * dstAspect).toInt().coerceAtLeast(1)
                            val x = (b.width - newW) / 2
                            src.set(x, 0, x + newW, b.height)
                        } else {
                            val newH = (b.width / dstAspect).toInt().coerceAtLeast(1)
                            val y = (b.height - newH) / 2
                            src.set(0, y, b.width, y + newH)
                        }
                        synchronized(paintLock) {
                            runCatching { canvas.drawBitmap(b, src, dst, paint) }
                        }
                    }
                    if (finished % 25 == 0 || finished == n) {
                        mosaic.post {
                            if (!cancelled) {
                                progress.text = "$finished / $n"
                                mosaic.invalidate()
                            }
                        }
                    }
                }
                if (live.decrementAndGet() == 0) {
                    mosaic.post {
                        if (cancelled) return@post
                        progress.text = getString(R.string.overview_done, done.get(), n)
                        progress.postDelayed({ progress.animate().alpha(0f).setDuration(400).start() }, 1200)
                        mosaic.invalidate()
                        running = false
                    }
                }
            }.apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
                start()
            }
        }
    }

    @Volatile
    private var cancelled = false

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            cancelled = true
            running = false
        }
    }

    private val WORKERS: Int = ImageLoader.WORKERS
}
