package com.n3vibecode.gallery.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * Bildansicht mit Pinch-Zoom, Doppeltipp-Zoom und Verschieben.
 *
 * – Zwei Finger = zoomen (1× bis 6×, Brennpunkt unter den Fingern)
 * – Doppeltipp = zwischen 1× und 2,5× wechseln
 * – Ein Finger, wenn gezoomt = verschieben (Bild wird an den Rändern gehalten)
 * – Einfacher Tipp = [onSingleTap] (z. B. Leisten ein-/ausblenden)
 * – [onZoomChanged] meldet, ob gerade gezoomt ist (damit der ViewPager nicht
 *   gleichzeitig umblättert, während man im Bild wischt)
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onSingleTap: (() -> Unit)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null
    /** Zieht man mit zwei Fingern über die Normalgröße hinaus zusammen → Übersicht. */
    var onZoomOutToGrid: (() -> Unit)? = null
    /** Wischt man (ohne Zoom) nach unten → Übersicht. */
    var onSwipeDownBack: (() -> Unit)? = null

    private val drawMatrix = Matrix()
    private val baseMatrix = Matrix()

    private var scaleFactor = 1f
    private var minScale = 1f
    private val maxScale = 6f
    private var zoomed = false

    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var animator: ValueAnimator? = null

    /** Einmal-Auslösung pro Geste verhindern. */
    private var zoomOutFired = false
    private var downSwipeFired = false
    private var downStartY = 0f
    private var downStartX = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Über die Normalgröße hinaus zusammenziehen = "rauszoomen" auf die Übersicht
                val raw = scaleFactor * detector.scaleFactor
                if (raw < minScale * 0.78f) {
                    if (!zoomOutFired) {
                        zoomOutFired = true
                        onZoomOutToGrid?.invoke()
                    }
                    return true
                }
                val target = raw.coerceIn(minScale, maxScale)
                val applied = target / scaleFactor
                if (abs(applied - 1f) < 0.0005f) return true
                scaleFactor = target
                drawMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                clampBounds()
                apply()
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                animator?.cancel()
                zoomOutFired = false
                requestParentDisallow(true)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (scaleFactor > minScale * 1.15f) minScale else MIN(2.5f, maxScale)
                animateTo(target, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }
        }
    )

    private fun MIN(a: Float, b: Float) = if (a < b) a else b

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
    }

    // ------------------------------------------------------------------ Matrix

    /** Basis: Bild vollständig sichtbar (fit center) und zentriert. */
    private fun computeBaseMatrix(): Boolean {
        val d = drawable ?: return false
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        if (dw <= 0 || dh <= 0 || width == 0 || height == 0) return false
        val fit = minOf(width.toFloat() / dw, height.toFloat() / dh)
        baseMatrix.reset()
        baseMatrix.setScale(fit, fit)
        baseMatrix.postTranslate(
            (width - dw * fit) / 2f,
            (height - dh * fit) / 2f
        )
        return true
    }

    /** Zoom zurücksetzen (z. B. nach dem Laden eines neuen Bildes). */
    fun resetZoom() {
        animator?.cancel()
        scaleFactor = 1f
        if (computeBaseMatrix()) {
            drawMatrix.set(baseMatrix)
            imageMatrix = drawMatrix
        }
        setZoomed(false)
    }

    private fun apply() {
        imageMatrix = drawMatrix
        setZoomed(scaleFactor > minScale * 1.01f)
    }

    private fun setZoomed(z: Boolean) {
        if (z == zoomed) return
        zoomed = z
        onZoomChanged?.invoke(z)
    }

    /** Verhindert, dass das Bild aus dem sichtbaren Bereich geschoben wird. */
    private fun clampBounds() {
        val d = drawable ?: return
        val v = FloatArray(9)
        drawMatrix.getValues(v)
        val s = v[Matrix.MSCALE_X]
        if (s <= 0f) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth * s
        val dh = d.intrinsicHeight * s
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]
        val ntx = if (dw <= vw) (vw - dw) / 2f else tx.coerceIn(vw - dw, 0f)
        val nty = if (dh <= vh) (vh - dh) / 2f else ty.coerceIn(vh - dh, 0f)
        if (abs(ntx - tx) < 0.01f && abs(nty - ty) < 0.01f) return
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate(ntx, nty)
    }

    private fun animateTo(target: Float, focusX: Float, focusY: Float) {
        val start = scaleFactor
        if (abs(target - start) < 0.01f) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val targetScale = a.animatedValue as Float
                val factor = targetScale / scaleFactor
                scaleFactor = targetScale
                drawMatrix.postScale(factor, factor, focusX, focusY)
                clampBounds()
                apply()
            }
            start()
        }
    }

    // ------------------------------------------------------------------ Touch

    private fun requestParentDisallow(disallow: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                downStartX = event.x
                downStartY = event.y
                downSwipeFired = false
                if (zoomed) requestParentDisallow(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Zweiter Finger: ab jetzt gehört die Geste dem Bild, nicht dem Pager
                requestParentDisallow(true)
                if (event.pointerCount >= 2) {
                    activePointerId = event.getPointerId(event.pointerCount - 1)
                    lastX = event.getX(event.pointerCount - 1)
                    lastY = event.getY(event.pointerCount - 1)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress && scaleFactor > minScale * 1.01f) {
                    val index = event.findPointerIndex(activePointerId)
                    if (index >= 0) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        drawMatrix.postTranslate(x - lastX, y - lastY)
                        clampBounds()
                        apply()
                        lastX = x
                        lastY = y
                    }
                } else if (event.pointerCount >= 2) {
                    requestParentDisallow(true)
                } else if (!zoomed && !downSwipeFired) {
                    // Ohne Zoom: deutliches Wischen nach unten führt zurück zur Übersicht
                    val dy = event.y - downStartY
                    val dx = event.x - downStartX
                    if (dy > height * 0.18f && dy > kotlin.math.abs(dx) * 1.4f) {
                        downSwipeFired = true
                        onSwipeDownBack?.invoke()
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                val upId = event.getPointerId(upIndex)
                if (upId == activePointerId) {
                    val next = if (upIndex == 0) 1 else 0
                    if (next < event.pointerCount) {
                        activePointerId = event.getPointerId(next)
                        lastX = event.getX(next)
                        lastY = event.getY(next)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                requestParentDisallow(false)
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

    // ------------------------------------------------------------------ Laden

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Bei Drehung/Größenänderung: Zoom behalten, Basis neu berechnen
        val keepScale = scaleFactor
        if (computeBaseMatrix()) {
            drawMatrix.set(baseMatrix)
            if (keepScale > minScale * 1.01f) {
                drawMatrix.postScale(keepScale, keepScale, w / 2f, h / 2f)
                clampBounds()
            }
            scaleFactor = keepScale
            apply()
        }
    }
}
