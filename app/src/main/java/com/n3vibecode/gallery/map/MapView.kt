package com.n3vibecode.gallery.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Selbst gezeichnete Karte – **ohne Internet, ohne Kartenanbieter, ohne Schlüssel**.
 *
 * Die Landflächen kommen aus dem App-Paket (Natural Earth), die Ortsnamen aus der
 * Ortsliste (siehe [GeoIndex]). Zoom mit zwei Fingern, Verschieben mit einem Finger,
 * Doppeltipp = hineinzoomen.
 *
 * **Zwei Zeichenwege:** Beim Wischen/Zoomen wird das vorbereitete Kartenbild (Bitmap)
 * verschoben – das ist praktisch kostenlos. Sobald der Finger ruht, werden die Landflächen
 * als Pfade **scharf** gezeichnet. Dadurch ist die Karte beim Zoomen flüssig *und* in jeder
 * Zoomstufe scharf.
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Ein Punkt auf der Karte – ein Foto, ein Ort oder eine Zusammenfassung mehrerer Fotos. */
    data class Marker(
        val id: String,
        val lat: Double,
        val lon: Double,
        val count: Int = 1,
        /** Kurztext für die Blase (Ortsname, Datum …). */
        val label: String = "",
        /** Beliebiges Zusatzobjekt (z. B. die Fotos dieses Punktes). */
        val payload: Any? = null
    )

    var onMarkerClick: ((Marker) -> Unit)? = null
    var onZoomChanged: ((Int) -> Unit)? = null

    // ------------------------------------------------------------------ Zustand

    /** Bildpunkte je Grad. 360° Welt ≈ 5,7 px/Grad bei Maßstab 1. */
    private var pxPerDeg = 5.7f
    private var centerLat = 20.0
    private var centerLon = 8.0

    private val markers = ArrayList<Marker>()
    private val clusters = ArrayList<Cluster>()
    private val density = resources.displayMetrics.density

    /** Während einer Geste wird das Bild verschoben, danach scharf gezeichnet. */
    private var gesturing = false
    private var idleRunnable: Runnable? = null

    private var baseBitmap: android.graphics.Bitmap? = null
    private var baseLoading = false

    private val vectorPath = Path()
    private var vectorScale = -1f
    private var vectorCenterLat = 0.0
    private var vectorCenterLon = 0.0
    private var vectorW = 0
    private var vectorH = 0

    private var selectedId: String? = null

    // ------------------------------------------------------------------ Stifte

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GeoIndex.LAND
    }
    private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = GeoIndex.COAST
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(28, 0, 0, 0)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6C4CF1") }
    private val markerSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF3B30") }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#14141A")
        textSize = 11 * density
        isFakeBoldText = true
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 255)
    }
    private val bubbleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 0, 0, 0) }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        textSize = 9 * density
    }

    private val clusterRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        // Karte vorbereiten, sobald die Ansicht einmal gezeichnet wird – im Hintergrund,
        // damit das Öffnen der Karte nie auf das Kartenbild warten muss.
        post { ensureBitmap() }
    }

    // ------------------------------------------------------------------ Öffentliche API

    fun setMarkers(list: List<Marker>) {
        markers.clear()
        markers.addAll(list)
        invalidate()
    }

    val markerCount: Int get() = markers.size

    /** Karte auf eine Position ausrichten. [degreesAcross] = sichtbare Breite in Grad. */
    fun showAt(lat: Double, lon: Double, degreesAcross: Float = 60f, animate: Boolean = false) {
        val targetPx = max(1f, width.toFloat().takeIf { it > 0 } ?: 1080f) / degreesAcross
        if (!animate || width == 0) {
            centerLat = lat.coerceIn(-85.0, 85.0)
            centerLon = lon
            pxPerDeg = targetPx.coerceIn(MIN_PX_PER_DEG, MAX_PX_PER_DEG)
            clampToView()
            invalidate()
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480
            interpolator = DecelerateInterpolator()
            val fromLat = centerLat
            val fromLon = centerLon
            val fromPx = pxPerDeg
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                centerLat = fromLat + (lat - fromLat) * t
                centerLon = fromLon + shortestLonDelta(fromLon, lon) * t
                pxPerDeg = fromPx + (targetPx - fromPx) * t
                clampToView()
                invalidate()
            }
            start()
        }
    }

    /** Alle Punkte einpassen (mit etwas Rand). */
    fun fitToMarkers(minDegrees: Float = 4f) {
        if (markers.isEmpty() || width == 0) return
        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0
        markers.forEach {
            minLat = min(minLat, it.lat); maxLat = max(maxLat, it.lat)
            minLon = min(minLon, it.lon); maxLon = max(maxLon, it.lon)
        }
        // Datumsgrenze: liegt ein Punkt „gegenüber“, ist der einfache Bereich falsch
        if (maxLon - minLon > 180) {
            val alt = markers.map { if (it.lon < 0) it.lon + 360 else it.lon }
            minLon = alt.min(); maxLon = alt.max()
        }
        val latSpan = max(minDegrees * 0.7f, (maxLat - minLat).toFloat())
        val lonSpan = max(minDegrees, (maxLon - minLon).toFloat())
        val wSpan = max(lonSpan, latSpan * width / height)
        centerLat = ((minLat + maxLat) / 2).coerceIn(-85.0, 85.0)
        centerLon = ((minLon + maxLon) / 2).let { if (it > 180) it - 360 else it }
        pxPerDeg = (width / wSpan).coerceIn(MIN_PX_PER_DEG, MAX_PX_PER_DEG)
        clampToView()
        invalidate()
    }

    fun center(): Pair<Double, Double> = centerLat to centerLon

    /** Über die Knöpfe zoomen (Mitte bleibt stehen). */
    fun zoomBy(factor: Float) {
        val old = pxPerDeg
        val next = (old * factor).coerceIn(MIN_PX_PER_DEG, MAX_PX_PER_DEG)
        if (next == old) return
        pxPerDeg = next
        clampToView()
        onZoomChanged?.invoke(zoomLevel())
        invalidate()
    }

    /** Sichtbare Breite in Grad (für Beschriftungen wie „1:5 Mio.“). */
    fun visibleDegrees(): Float = if (pxPerDeg <= 0f) 360f else width / pxPerDeg

    /**
     * Markierungen, die auf dem Bildschirm dicht beieinanderliegen, werden zu **einer**
     * Blase zusammengefasst (mit Anzahl) – wie bei Google Fotos, damit man nicht in
     * Punktwolken zoomen muss.
     */
    private class Cluster(
        val lat: Double,
        val lon: Double,
        val members: List<Marker>
    ) {
        var screenX = 0f
        var screenY = 0f
        val count: Int get() = members.sumOf { it.count }
        /** Bei einem Ort der Ortsname, bei mehreren die Anzahl („4 Fotos“). */
        val label: String get() = if (members.size == 1) members[0].label else "$count"
    }

    // ------------------------------------------------------------------ Zeichnen

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val sharp = !gesturing
        if (sharp) drawVector(canvas) else drawBitmap(canvas)

        drawGraticule(canvas)
        buildClusters()
        drawClusters(canvas)
        drawAttribution(canvas)
    }

    private fun ensureBitmap() {
        if (baseBitmap != null || baseLoading) return
        baseLoading = true
        Thread({
            val bmp = runCatching { GeoIndex.mapBitmap(context.applicationContext) }.getOrNull()
            post {
                baseBitmap = bmp
                baseLoading = false
                invalidate()
            }
        }, "n3-map-bitmap").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /**
     * Schneller Weg während der Geste: das vorbereitete Kartenbild verschieben.
     * Maßstab = px je Grad geteilt durch die Bildpunkte je Grad der Bitmap.
     */
    private fun drawBitmap(canvas: Canvas) {
        val bmp = baseBitmap ?: run { canvas.drawColor(GeoIndex.SEA); return }
        val scale = pxPerDeg * 360f / bmp.width
        val cxBits = ((centerLon + 180.0) / 360.0 * bmp.width).toFloat()
        val cyBits = ((90.0 - centerLat) / 180.0 * bmp.height).toFloat()
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, -cxBits, -cyBits, bitmapPaint)
        // Naht an der Datumsgrenze: die Karte einmal um 360° versetzt nachzeichnen
        val worldW = bmp.width.toFloat()
        canvas.drawBitmap(bmp, -cxBits - worldW, -cyBits, bitmapPaint)
        canvas.drawBitmap(bmp, -cxBits + worldW, -cyBits, bitmapPaint)
        canvas.restore()
    }

    /** Landflächen als Vektorpfad – in jeder Zoomstufe scharf. */
    private fun drawVector(canvas: Canvas) {
        // Landflächen schon im Speicher? Wenn nicht, reicht das vorbereitete Kartenbild –
        // das Einlesen läuft im Hintergrund und zeichnet danach von selbst neu.
        val list = if (GeoIndex.ringsLoaded() || vectorPathDirty) {
            GeoIndex.rings(context)
        } else {
            emptyList()
        }
        if (list.isEmpty()) {
            drawBitmap(canvas)
            if (!GeoIndex.ringsLoaded() && !ringsWarmUpStarted) {
                ringsWarmUpStarted = true
                Thread({
                    GeoIndex.rings(context.applicationContext)
                    post { invalidate() }
                }, "n3-map-rings").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
            }
            return
        }
        vectorPathDirty = false
        if (vectorScale != pxPerDeg || vectorCenterLat != centerLat ||
            vectorCenterLon != centerLon || vectorW != width || vectorH != height
        ) {
            buildVectorPath(list)
        }
        canvas.drawColor(GeoIndex.SEA)
        canvas.drawPath(vectorPath, landPaint)
        coastPaint.strokeWidth = (0.5f + 0.35f * density).coerceAtMost(2f)
        canvas.drawPath(vectorPath, coastPaint)
    }

    /** Pfad muss neu aufgebaut werden (z. B. nachdem die Landflächen geladen wurden). */
    private var vectorPathDirty = false
    private var ringsWarmUpStarted = false

    private fun buildVectorPath(list: List<FloatArray>) {
        vectorScale = pxPerDeg
        vectorCenterLat = centerLat
        vectorCenterLon = centerLon
        vectorW = width
        vectorH = height
        vectorPath.rewind()
        // Gerade/ungerade Füllung: dadurch werden Seen und Binnenmeere (Kaspisches Meer,
        // Zürichsee …) korrekt ausgespart – die Ringe in den Daten enthalten diese Löcher.
        vectorPath.fillType = Path.FillType.EVEN_ODD

        val halfLat = height / 2f / pxPerDeg
        val halfLon = width / 2f / pxPerDeg

        for (r in list) {
            if (r.size < 8) continue
            // Vorprüfung mit dem Rahmen des Rings (spart das Zeichnen der meisten Ringe)
            var ringMinLat = 90f
            var ringMaxLat = -90f
            var ringMinLon = 180f
            var ringMaxLon = -180f
            var i = 0
            while (i < r.size) {
                val la = r[i + 1]
                val lo = r[i]
                if (la < ringMinLat) ringMinLat = la
                if (la > ringMaxLat) ringMaxLat = la
                if (lo < ringMinLon) ringMinLon = lo
                if (lo > ringMaxLon) ringMaxLon = lo
                i += 2
            }
            if (ringMaxLat < centerLat - halfLat) continue
            if (ringMinLat > centerLat + halfLat) continue
            var visible = false
            for (shift in intArrayOf(-360, 0, 360)) {
                if (ringMaxLon + shift >= centerLon - halfLon && ringMinLon + shift <= centerLon + halfLon) {
                    visible = true
                    break
                }
            }
            if (!visible) continue

            // Ring um 360° versetzt zeichnen, damit an der Datumsgrenze keine Lücke entsteht.
            for (shift in intArrayOf(-360, 0, 360)) {
                var started = false
                i = 0
                while (i < r.size) {
                    val x = width / 2f + (r[i] + shift - centerLon).toFloat() * pxPerDeg
                    val y = height / 2f + (centerLat - r[i + 1]).toFloat() * pxPerDeg
                    if (!started) {
                        vectorPath.moveTo(x, y)
                        started = true
                    } else {
                        vectorPath.lineTo(x, y)
                    }
                    i += 2
                }
                if (started) vectorPath.close()
            }
        }
    }

    private fun drawGraticule(canvas: Canvas) {
        // Dezentes Gradnetz: hilft bei der Orientierung, wenn kein Ort in der Nähe ist
        val step = when {
            pxPerDeg > 220 -> 1f
            pxPerDeg > 90 -> 5f
            pxPerDeg > 30 -> 10f
            pxPerDeg > 12 -> 30f
            else -> 60f
        }
        val w = width.toFloat()
        val h = height.toFloat()
        var lon = Math.floor((centerLon - w / 2 / pxPerDeg) / step) * step
        while (lon <= centerLon + w / 2 / pxPerDeg) {
            val x = w / 2f + ((lon - centerLon) * pxPerDeg).toFloat()
            canvas.drawLine(x, 0f, x, h, gridPaint)
            lon += step
        }
        var lat = Math.floor((centerLat - h / 2 / pxPerDeg) / step) * step
        while (lat <= centerLat + h / 2 / pxPerDeg) {
            val y = h / 2f + ((centerLat - lat) * pxPerDeg).toFloat()
            canvas.drawLine(0f, y, w, y, gridPaint)
            lat += step
        }
    }

    private fun buildClusters() {
        clusters.clear()
        if (markers.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val minDist = 46 * density
        val mergeKm = (minDist / pxPerDeg) * 111.0
        val used = BooleanArray(markers.size)
        for (i in markers.indices) {
            if (used[i]) continue
            val a = markers[i]
            val group = ArrayList<Marker>(4)
            group.add(a)
            used[i] = true
            for (j in i + 1 until markers.size) {
                if (used[j]) continue
                val b = markers[j]
                if (GeoIndex.distanceKm(a.lat, a.lon, b.lat, b.lon) <= mergeKm) {
                    group.add(b)
                    used[j] = true
                }
            }
            val cx = w / 2f + ((a.lon - centerLon) * pxPerDeg).toFloat()
            val cy = h / 2f + ((centerLat - a.lat) * pxPerDeg).toFloat()
            // Nur zeichnen, was (knapp) sichtbar ist
            if (cx < -60 * density || cx > w + 60 * density) continue
            if (cy < -60 * density || cy > h + 60 * density) continue
            clusters.add(Cluster(a.lat, a.lon, group).also {
                it.screenX = cx
                it.screenY = cy
            })
        }
    }

    private fun drawClusters(canvas: Canvas) {
        val base = 9 * density
        for (c in clusters) {
            val selected = c.members.size == 1 && c.members[0].id == selectedId
            val big = c.count > 1
            val r = if (big) base + 4 * density else base
            markerPaint.alpha = 235
            markerRingPaint.strokeWidth = 2f * density
            markerRingPaint.color = Color.WHITE

            // Kleiner „Stiel“ nach unten – wie bei Karten-Apps
            canvas.drawCircle(c.screenX, c.screenY, r + 2 * density, bubbleShadow)
            canvas.drawCircle(
                c.screenX, c.screenY, r,
                if (selected) markerSelectedPaint else markerPaint
            )
            canvas.drawCircle(c.screenX, c.screenY, r, markerRingPaint)

            labelPaint.textSize = (if (big) 11f else 10f) * density
            val text = when {
                big -> c.count.toString()
                else -> "1"
            }
            val ty = c.screenY - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(text, c.screenX, ty, labelPaint)

            // Ortsname als kleine Blase über dem Punkt (sonst wird es bei vielen Fotos unruhig)
            val manyMarkers = clusters.size > MAX_LABELS
            val label = if (!manyMarkers || selected) c.label else ""
            if (label.isNotEmpty()) {
                bubbleTextPaint.textSize = 11 * density
                val tw = bubbleTextPaint.measureText(label)
                val padH = 6 * density
                val bh = 18 * density
                val bx = c.screenX
                val by = c.screenY - r - bh - 5 * density
                clusterRect.set(bx - tw / 2 - padH, by, bx + tw / 2 + padH, by + bh)
                canvas.drawRoundRect(clusterRect, bh / 2, bh / 2, bubbleShadow.also { it.alpha = 45 })
                canvas.drawRoundRect(clusterRect, bh / 2, bh / 2, bubblePaint)
                canvas.drawText(label, bx, by + bh / 2 - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2, bubbleTextPaint)
            }
        }
    }

    private fun drawAttribution(canvas: Canvas) {
        val text = "Karte: Natural Earth · Orte: GeoNames (CC BY 4.0)"
        attributionPaint.textSize = 9 * density
        canvas.drawText(text, 8 * density, height - 8 * density, attributionPaint)
    }

    // ------------------------------------------------------------------ Gesten

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            beginGesture()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            if (factor.isNaN() || factor <= 0f) return true
            val oldPx = pxPerDeg
            val newPx = (oldPx * factor).coerceIn(MIN_PX_PER_DEG, MAX_PX_PER_DEG)
            if (newPx == oldPx) return true
            // Um den Punkt zwischen den Fingern zoomen (nicht um die Mitte)
            val fx = detector.focusX
            val fy = detector.focusY
            val lonAtFocus = centerLon + (fx - width / 2f) / oldPx
            val latAtFocus = centerLat - (fy - height / 2f) / oldPx
            pxPerDeg = newPx
            centerLon = lonAtFocus - (fx - width / 2f) / newPx
            centerLat = latAtFocus + (fy - height / 2f) / newPx
            clampToView()
            onZoomChanged?.invoke(zoomLevel())
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            endGesture()
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            beginGesture()
            moveBy(distanceX, distanceY)
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            beginGesture()
            // Nachlauf: Geschwindigkeit klingt weich ab (wie bei einer echten Karte)
            val maxV = 6000f
            val vx = velocityX.coerceIn(-maxV, maxV)
            val vy = velocityY.coerceIn(-maxV, maxV)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700
                val t0 = System.currentTimeMillis()
                addUpdateListener {
                    val t = (System.currentTimeMillis() - t0) / 700f
                    if (t >= 1f) {
                        endGesture()
                        cancel()
                        return@addUpdateListener
                    }
                    val decay = Math.pow(0.12, t.toDouble()).toFloat()
                    // Weg = v * dt * decay (grob in zwei Schritten, reicht für das Gefühl)
                    moveBy(-vx * 0.012f * decay, -vy * 0.012f * decay)
                }
                start()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val oldPx = pxPerDeg
            val newPx = (oldPx * 2.2f).coerceIn(MIN_PX_PER_DEG, MAX_PX_PER_DEG)
            if (newPx == oldPx) {
                // schon am Anschlag → zurück auf die Übersicht
                pxPerDeg = MIN_PX_PER_DEG
            } else {
                val lonAt = centerLon + (e.x - width / 2f) / oldPx
                val latAt = centerLat - (e.y - height / 2f) / oldPx
                pxPerDeg = newPx
                centerLon = lonAt - (e.x - width / 2f) / newPx
                centerLat = latAt + (e.y - height / 2f) / newPx
            }
            clampToView()
            onZoomChanged?.invoke(zoomLevel())
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = findClusterAt(e.x, e.y)
            if (hit == null) {
                selectedId = null
                invalidate()
                return true
            }
            if (hit.members.size == 1) {
                selectedId = hit.members[0].id
                invalidate()
                performClick()
                onMarkerClick?.invoke(hit.members[0])
            } else {
                // Mehrere Fotos an dieser Stelle: hineinzoomen und dabei zentrieren
                if (pxPerDeg < MAX_PX_PER_DEG * 0.9f) {
                    showAt(hit.lat, hit.lon, max(2f, width / pxPerDeg * 0.45f), animate = true)
                } else {
                    // Schon am tiefsten Zoom: die Fotos des Ortes direkt anzeigen
                    onMarkerClick?.invoke(hit.members.first())
                }
            }
            return true
        }
    })

    private fun findClusterAt(x: Float, y: Float): Cluster? {
        val r = 34 * density
        var best: Cluster? = null
        var bestD = r
        for (c in clusters) {
            val dx = c.screenX - x
            val dy = c.screenY - y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            endGesture()
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginGesture() {
        if (!gesturing) {
            gesturing = true
            idleRunnable?.let { removeCallbacks(it) }
            invalidate()
        }
    }

    /** Nach dem Loslassen kurz warten, dann scharf nachzeichnen. */
    private fun endGesture() {
        idleRunnable?.let { removeCallbacks(it) }
        val r = Runnable {
            if (gesturing) {
                gesturing = false
                startVectorWarmUp()
                invalidate()
            }
        }
        idleRunnable = r
        postDelayed(r, 220)
    }

    /** Landflächen im Hintergrund einlesen, damit das scharfe Zeichnen nicht ruckelt. */
    private fun startVectorWarmUp() {
        if (ringsWarmUpStarted || GeoIndex.ringsLoaded()) return
        ringsWarmUpStarted = true
        Thread({
            GeoIndex.rings(context.applicationContext)
            post {
                vectorPathDirty = true
                invalidate()
            }
        }, "n3-map-rings").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private fun moveBy(dx: Float, dy: Float) {
        centerLon -= dx / pxPerDeg
        centerLat += dy / pxPerDeg
        clampToView()
        invalidate()
    }

    private fun clampToView() {
        centerLat = centerLat.coerceIn(-85.0, 85.0)
        // Ost/West umbrechen (Karte ist rund)
        while (centerLon > 180.0) centerLon -= 360.0
        while (centerLon < -180.0) centerLon += 360.0
    }

    private fun shortestLonDelta(from: Double, to: Double): Double {
        var d = to - from
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    /** Zoomstufe 0…100 für die Anzeige („Wo sind meine Fotos?“). */
    fun zoomLevel(): Int {
        val minPx = MIN_PX_PER_DEG
        val maxPx = MAX_PX_PER_DEG
        val t = (Math.log((pxPerDeg / minPx).toDouble()) / Math.log((maxPx / minPx).toDouble()))
        return (t * 100).toInt().coerceIn(0, 100)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0 && markers.isNotEmpty()) fitToMarkers()
        ensureBitmap()
        // Südhalbkugel soll nicht „unter“ dem Bildschirm liegen
        clampToView()
    }

    companion object {
        /**
         * Ganz herausgezoomt: 360° (die ganze Welt) passen auf etwa 2 Bildschirmbreiten –
         * auf einem Handy also rund 1150 px je 360°, damit man alles sieht.
         */
        const val MIN_PX_PER_DEG = 1.6f

        /**
         * Ganz hineingezoomt: rund 0,6° (≈ 60 km) über die Bildschirmbreite. Weiter hinein
         * wäre sinnlos – die Kartendaten sind bewusst klein gehalten (Details fehlen) und die
         * Marker lägen alle auf einem Haufen.
         */
        const val MAX_PX_PER_DEG = 1800f

        /** Ab so vielen Punkten werden Ortsnamen nur noch beim ausgewählten Punkt gezeigt. */
        private const val MAX_LABELS = 28
    }
}
