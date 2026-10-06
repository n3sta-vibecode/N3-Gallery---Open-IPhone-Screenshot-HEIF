package com.n3vibecode.gallery.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Übersicht aller Fotos als **ein** Bild (Mosaik) – wie die „Alle Fotos“-Ansicht,
 * in der möglichst alles auf einen Bildschirm passt.
 *
 *  • Zwei Finger = in die Übersicht hineinzoomen (bis 8×)
 *  • Ein Finger (gezoomt) = verschieben
 *  • Tippen auf eine Kachel = dieses Foto groß öffnen
 */
class MosaicView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Tippen auf die Kachel mit diesem Index. */
    var onCellTap: ((Int) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var cols = 1
    private var rows = 1
    private var count = 0
    private var cellW = 1f
    private var cellH = 1f

    private val matrix = Matrix()
    private var minScale = 1f
    private var maxScale = 1f
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val applied = target / scale
                scale = target
                matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                clamp()
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                openTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (scale > minScale * 1.2f) minScale else min(minScale * 3f, maxScale)
                val applied = target / scale
                scale = target
                matrix.postScale(applied, applied, e.x, e.y)
                clamp()
                invalidate()
                return true
            }
        }
    )

    /** Mosaik setzen – [w] Kacheln breit, [h] Kacheln hoch, [total] gültige Fotos. */
    fun setMosaic(bmp: Bitmap, w: Int, h: Int, total: Int) {
        bitmap = bmp
        cols = w.coerceAtLeast(1)
        rows = h.coerceAtLeast(1)
        count = total
        cellW = bmp.width.toFloat() / cols
        cellH = bmp.height.toFloat() / rows
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        minScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        maxScale = minScale * 8f
        scale = minScale
        matrix.reset()
        matrix.setScale(minScale, minScale)
        matrix.postTranslate(
            (width - b.width * minScale) / 2f,
            (height - b.height * minScale) / 2f
        )
        invalidate()
    }

    /** Mosaik an die Fenstergröße anpassen (z. B. nach Neuaufbau). */
    fun refit() = fit()

    private fun clamp() {
        val b = bitmap ?: return
        val v = FloatArray(9)
        matrix.getValues(v)
        val s = v[Matrix.MSCALE_X]
        if (s <= 0f) return
        val bw = b.width * s
        val bh = b.height * s
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        val ntx = if (bw <= width) (width - bw) / 2f else tx.coerceIn(width - bw, 0f)
        val nty = if (bh <= height) (height - bh) / 2f else ty.coerceIn(height - bh, 0f)
        if (abs(ntx - tx) < 0.01f && abs(nty - ty) < 0.01f) return
        matrix.setScale(s, s)
        matrix.postTranslate(ntx, nty)
    }

    private fun openTap(x: Float, y: Float) {
        if (count <= 0) return
        val inv = Matrix()
        if (!matrix.invert(inv)) return
        val pts = floatArrayOf(x, y)
        inv.mapPoints(pts)
        val col = (pts[0] / cellW).toInt()
        val row = (pts[1] / cellH).toInt()
        if (col < 0 || row < 0 || col >= cols) return
        val index = row * cols + col
        if (index in 0 until count) onCellTap?.invoke(index)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        canvas.drawBitmap(b, matrix, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                if (scale > minScale * 1.01f) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress && scale > minScale * 1.01f) {
                    matrix.postTranslate(event.x - lastX, event.y - lastY)
                    clamp()
                    invalidate()
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
