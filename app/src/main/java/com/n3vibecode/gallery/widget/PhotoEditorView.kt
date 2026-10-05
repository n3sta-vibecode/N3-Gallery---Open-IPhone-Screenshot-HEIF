package com.n3vibecode.gallery.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Was gerade bearbeitet wird. */
enum class EditorMode { VIEW, CROP, DRAW, TEXT }

/** Eine Zeichnung (Freihandlinie) in Bildkoordinaten. */
class StrokeAnno(val path: Path, val color: Int, val width: Float)

/** Ein Textfeld in Bildkoordinaten – [y] ist die Grundlinie. */
data class TextAnno(val text: String, val x: Float, val y: Float, val size: Float, val color: Int)

/**
 * Bearbeitungsfläche für Fotos: **zuschneiden, zeichnen, Textfelder**.
 *
 * Alle Bearbeitungen liegen in *Bildkoordinaten* – dadurch ist das Ergebnis unabhängig
 * davon, wie weit hineingezoomt wurde, und [renderResult] liefert immer die volle Auflösung.
 *
 * Bedienung
 *  • Ein Finger: zeichnen, Text verschieben, Zuschnitt ziehen
 *  • Zwei Finger: in das Bild zoomen/verschieben (in jedem Modus)
 *  • [undo]/[redo]: jede Änderung rückgängig machen
 */
class PhotoEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ------------------------------------------------------------------ Zustand

    var source: Bitmap? = null
        set(value) {
            field = value
            fitScale = 1f
            userScale = 1f
            panX = 0f
            panY = 0f
            annos.clear()
            undoStack.clear()
            redoStack.clear()
            cropRect = null
            selected = null
            invalidate()
        }

    var mode: EditorMode = EditorMode.VIEW
        set(value) {
            if (field == value) return
            field = value
            drawing = null
            selected = null
            invalidate()
        }

    var drawColor: Int = Color.parseColor("#FF3B30")
        set(value) { field = value; invalidate() }

    var textColor: Int = Color.WHITE
        set(value) {
            field = value
            selected?.let { sel ->
                replaceText(sel, sel.copy(color = value))
            }
        }

    /** Strichstärke in Bildpunkten. */
    var strokeWidth: Float = 14f

    /** Textgröße in Bildpunkten. */
    var textSize: Float = 110f

    /** Wird gerufen, wenn im Text-Modus in eine freie Fläche getippt wird (Bildkoordinaten). */
    var onTextRequest: ((Float, Float) -> Unit)? = null

    /** Wird gerufen, wenn ein vorhandenes Textfeld angetippt wird (Doppeltipp = ändern). */
    var onTextTapped: ((TextAnno) -> Unit)? = null

    /** Wird gerufen, wenn ein Textfeld zum Ändern doppelt angetippt wurde. */
    var onTextEditRequest: ((TextAnno) -> Unit)? = null

    /** Meldet Änderungen (für Rückgängig/Wiederholen-Schaltflächen). */
    var onChanged: (() -> Unit)? = null

    private val annos = mutableListOf<Any>()
    private var cropRect: RectF? = null
    private var selected: TextAnno? = null

    private var fitScale = 1f
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f

    private val toView = Matrix()
    private val toBitmap = Matrix()

    private var drawing: Path? = null
    private var drawingPointerId = MotionEvent.INVALID_POINTER_ID

    // Zuschnitt-Geste
    private var cropHandle = HANDLE_NONE
    private var cropStart = RectF()

    // Zwei-Finger-Geste
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDistance = 0f

    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private data class Snapshot(val annos: List<Any>, val crop: RectF?)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#66FFFFFF")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#FF3B30")
    }

    // ------------------------------------------------------------------ Öffentliche API

    fun hasCrop(): Boolean = cropRect != null

    fun hasChanges(): Boolean = annos.isNotEmpty() || cropRect != null

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun selectedText(): TextAnno? = selected

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next)
    }

    /** Alle Bearbeitungen verwerfen. */
    fun resetEdits() {
        if (!hasChanges()) return
        pushHistory()
        annos.clear()
        cropRect = null
        selected = null
        invalidate()
        onChanged?.invoke()
    }

    /** Textfeld anlegen (Bildkoordinaten; ohne Angabe mittig). */
    fun addText(text: String, x: Float? = null, y: Float? = null) {
        val bmp = source ?: return
        if (text.isBlank()) return
        pushHistory()
        val size = textSize
        val cx = x ?: (bmp.width / 2f)
        val cy = y ?: (bmp.height / 2f)
        val anno = TextAnno(text.trim(), cx, cy, size, textColor)
        annos += anno
        selected = anno
        invalidate()
        onChanged?.invoke()
    }

    fun replaceText(target: TextAnno, newAnno: TextAnno) {
        val index = annos.indexOf(target)
        if (index < 0) return
        pushHistory()
        annos[index] = newAnno
        selected = newAnno
        invalidate()
        onChanged?.invoke()
    }

    fun deleteText(target: TextAnno) {
        if (!annos.contains(target)) return
        pushHistory()
        annos.remove(target)
        if (selected == target) selected = null
        invalidate()
        onChanged?.invoke()
    }

    fun duplicateText(target: TextAnno) {
        pushHistory()
        val copy = target.copy(x = target.x + target.size * 0.6f, y = target.y + target.size * 0.9f)
        annos += copy
        selected = copy
        invalidate()
        onChanged?.invoke()
    }

    /** Seitenverhältnis für den Zuschnitt setzen (null = frei). */
    fun applyAspect(ratio: Float?) {
        val bmp = source ?: return
        val full = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        if (ratio == null) {
            cropRect = null
            cropHandle = HANDLE_MOVE
            invalidate()
            onChanged?.invoke()
            return
        }
        pushHistory()
        val base = cropRect ?: full
        val w: Float
        val h: Float
        if (ratio >= 1f) {
            w = base.width()
            h = w / ratio
        } else {
            h = base.height()
            w = h * ratio
        }
        val cx = base.centerX()
        val cy = base.centerY()
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        cropRect = clampCrop(rect)
        invalidate()
        onChanged?.invoke()
    }

    fun resetCrop() {
        if (cropRect == null) return
        pushHistory()
        cropRect = null
        invalidate()
        onChanged?.invoke()
    }

    /** Zuschnitt auf das ganze Bild – falls der Nutzer den Modus wählt, ohne zu ziehen. */
    fun startCrop() {
        if (cropRect == null) {
            val bmp = source ?: return
            cropRect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        }
        invalidate()
    }

    /**
     * Ergebnis in voller Auflösung: Zuschnitt + Zeichnungen + Text.
     * [maxDim] begrenzt die Kantenlänge (z. B. 4096 px) und schützt vor Speicherproblemen.
     */
    fun renderResult(maxDim: Int = 4096): Bitmap? {
        val src = source ?: return null
        val crop = cropRect?.let { clampCrop(RectF(it)) } ?: RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        if (crop.width() < 8f || crop.height() < 8f) return null

        val longest = maxOf(crop.width(), crop.height())
        val scale = if (longest > maxDim) maxDim / longest else 1f
        val outW = (crop.width() * scale).roundToInt().coerceAtLeast(1)
        val outH = (crop.height() * scale).roundToInt().coerceAtLeast(1)

        val out = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val canvas = Canvas(out)
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate(-crop.left * scale, -crop.top * scale)
        canvas.drawBitmap(src, m, bitmapPaint)
        canvas.save()
        canvas.concat(m)
        drawAnnos(canvas)
        canvas.restore()
        return out
    }

    // ------------------------------------------------------------------ Zeichnen

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        val bmp = source ?: return
        if (width == 0 || height == 0) return
        val padding = 8f * resources.displayMetrics.density
        val base = min(
            (width - padding * 2) / bmp.width.toFloat(),
            (height - padding * 2) / bmp.height.toFloat()
        ).coerceAtLeast(0.01f)
        fitScale = base
        val total = base * userScale
        val dx = (width - bmp.width * total) / 2f + panX
        val dy = (height - bmp.height * total) / 2f + panY
        toView.reset()
        toView.postScale(total, total)
        toView.postTranslate(dx, dy)
        toView.invert(toBitmap)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = source ?: return
        canvas.drawBitmap(bmp, toView, bitmapPaint)

        canvas.save()
        canvas.concat(toView)
        drawAnnos(canvas)
        drawing?.let { path ->
            strokePaint.color = drawColor
            strokePaint.strokeWidth = strokeWidth
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()

        if (mode == EditorMode.CROP) drawCropOverlay(canvas)
        selected?.let { drawSelection(canvas, it) }
    }

    private fun drawAnnos(canvas: Canvas) {
        for (a in annos) {
            when (a) {
                is StrokeAnno -> {
                    strokePaint.color = a.color
                    strokePaint.strokeWidth = a.width
                    canvas.drawPath(a.path, strokePaint)
                }
                is TextAnno -> {
                    textPaint.color = a.color
                    textPaint.textSize = a.size
                    canvas.drawText(a.text, a.x, a.y, textPaint)
                }
            }
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val rect = cropRect ?: return
        val view = RectF()
        toView.mapRect(view, rect)
        // Außenbereich abdunkeln
        cropPaint.color = Color.parseColor("#B3000000")
        cropPaint.style = Paint.Style.FILL
        val outer = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.save()
        canvas.clipOutRect(view)
        canvas.drawRect(outer, cropPaint)
        canvas.restore()
        // Raster (Drittel-Regel) + Rahmen + Griffe
        val thirdW = view.width() / 3f
        val thirdH = view.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(view.left + thirdW * i, view.top, view.left + thirdW * i, view.bottom, gridPaint)
            canvas.drawLine(view.left, view.top + thirdH * i, view.right, view.top + thirdH * i, gridPaint)
        }
        canvas.drawRect(view, handlePaint)
        val r = 12f * resources.displayMetrics.density
        for ((x, y) in corners(view)) {
            canvas.drawCircle(x, y, r, handlePaint)
        }
    }

    private fun drawSelection(canvas: Canvas, anno: TextAnno) {
        val rect = RectF()
        toView.mapRect(rect, textBounds(anno))
        canvas.drawRect(rect, selectedPaint)
    }

    private fun corners(view: RectF): List<Pair<Float, Float>> = listOf(
        view.left to view.top,
        view.right to view.top,
        view.right to view.bottom,
        view.left to view.bottom
    )

    // ------------------------------------------------------------------ Eingaben

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastMidX = event.x
                lastMidY = event.y
                lastDistance = 0f
                onSingleDown(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Ab zwei Fingern wird nicht mehr gezeichnet/gezogen, sondern gezoomt
                drawing = null
                cropHandle = HANDLE_NONE
                lastMidX = midX(event)
                lastMidY = midY(event)
                lastDistance = distance(event)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    handleZoom(event)
                } else {
                    handleSingleMove(event)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Übrig gebliebener Finger übernimmt die Position neu
                lastMidX = midX(event)
                lastMidY = midY(event)
                lastDistance = 0f
                return true
            }
            MotionEvent.ACTION_UP -> {
                onSingleUp(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = null
                cropHandle = HANDLE_NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleZoom(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val midXNow = midX(event)
        val midYNow = midY(event)
        val distNow = distance(event)
        if (lastDistance > 0f && distNow > 0f) {
            val factor = distNow / lastDistance
            val newUser = (userScale * factor).coerceIn(0.5f, 8f)
            val applied = newUser / userScale
            userScale = newUser
            // um den Mittelpunkt zoomen: Verschiebung des Bildmittelpunkts anpassen
            val cx = width / 2f
            val cy = height / 2f
            panX = (panX + (midXNow - cx)) * applied - (midXNow - cx) + (midXNow - lastMidX)
            panY = (panY + (midYNow - cy)) * applied - (midYNow - cy) + (midYNow - lastMidY)
        } else {
            panX += midXNow - lastMidX
            panY += midYNow - lastMidY
        }
        lastMidX = midXNow
        lastMidY = midYNow
        lastDistance = distNow
        updateMatrix()
    }

    private fun onSingleDown(x: Float, y: Float) {
        val point = toBitmapPoint(x, y)
        when (mode) {
            EditorMode.DRAW -> {
                drawing = Path().apply { moveTo(point.x, point.y) }
                drawingPointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
            EditorMode.CROP -> {
                startCrop()
                val rect = cropRect ?: return
                cropHandle = handleAt(x, y)
                cropStart = RectF(rect)
            }
            EditorMode.TEXT -> {
                val hit = textAt(point.x, point.y)
                if (hit != null) {
                    selected = hit
                    selectedStart = hit
                    onTextTapped?.invoke(hit)
                } else {
                    selected = null
                }
                invalidate()
            }
            EditorMode.VIEW -> Unit
        }
    }

    private var selectedStart: TextAnno? = null

    private fun handleSingleMove(event: MotionEvent) {
        val point = toBitmapPoint(event.x, event.y)
        when (mode) {
            EditorMode.DRAW -> {
                val path = drawing ?: return
                path.lineTo(point.x, point.y)
                invalidate()
            }
            EditorMode.CROP -> {
                val rect = cropRect ?: return
                val bit = toBitmapPoint(event.x, event.y)
                val dx = bit.x - cropStart.centerX()
                val dy = bit.y - cropStart.centerY()
                val moved = when (cropHandle) {
                    HANDLE_MOVE -> {
                        val r = RectF(cropStart)
                        r.offsetTo(cropStart.left + dx, cropStart.top + dy)
                        r
                    }
                    HANDLE_TL -> RectF(min(cropStart.left + dx, cropStart.right - 40), min(cropStart.top + dy, cropStart.bottom - 40), cropStart.right, cropStart.bottom)
                    HANDLE_TR -> RectF(cropStart.left, min(cropStart.top + dy, cropStart.bottom - 40), maxOf(cropStart.right + dx, cropStart.left + 40), cropStart.bottom)
                    HANDLE_BL -> RectF(min(cropStart.left + dx, cropStart.right - 40), cropStart.top, cropStart.right, maxOf(cropStart.bottom + dy, cropStart.top + 40))
                    HANDLE_BR -> RectF(cropStart.left, cropStart.top, maxOf(cropStart.right + dx, cropStart.left + 40), maxOf(cropStart.bottom + dy, cropStart.top + 40))
                    else -> null
                }
                if (moved != null) cropRect = clampCrop(moved)
                invalidate()
            }
            EditorMode.TEXT -> {
                val start = selectedStart ?: return
                val bit = toBitmapPoint(event.x, event.y)
                val dx = bit.x - start.x
                val dy = bit.y - start.y
                if (abs(dx) < 1f && abs(dy) < 1f) return
                val index = annos.indexOf(start)
                if (index < 0) return
                if (moveRecorded != start) {
                    pushHistory()
                    moveRecorded = start
                }
                val moved = start.copy(x = bit.x, y = bit.y)
                annos[index] = moved
                selected = moved
                selectedStart = moved
                invalidate()
            }
            EditorMode.VIEW -> Unit
        }
    }

    private var moveRecorded: TextAnno? = null

    private fun onSingleUp(x: Float, y: Float) {
        when (mode) {
            EditorMode.DRAW -> {
                val path = drawing
                drawing = null
                if (path != null) {
                    pushHistory()
                    annos += StrokeAnno(path, drawColor, strokeWidth)
                    onChanged?.invoke()
                }
                invalidate()
            }
            EditorMode.CROP -> {
                if (cropHandle != HANDLE_NONE) onChanged?.invoke()
                cropHandle = HANDLE_NONE
            }
            EditorMode.TEXT -> {
                moveRecorded = null
                val now = System.currentTimeMillis()
                val isDoubleTap = now - lastTapAt < 320 &&
                    hypot(x - lastTapX, y - lastTapY) < 40 * resources.displayMetrics.density
                lastTapAt = now
                lastTapX = x
                lastTapY = y
                val point = toBitmapPoint(x, y)
                val hit = textAt(point.x, point.y)
                if (isDoubleTap && hit != null) {
                    onTextEditRequest?.invoke(hit)
                } else if (hit == null && !isDoubleTap) {
                    // Doppeltipp zum Anlegen vermeiden: nur bei echtem Einzeltipp
                    onTextRequest?.invoke(point.x, point.y)
                }
            }
            EditorMode.VIEW -> Unit
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private fun pushHistory() {
        undoStack.addLast(snapshot())
        while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun snapshot(): Snapshot = Snapshot(annos.toList(), cropRect?.let { RectF(it) })

    private fun restore(state: Snapshot) {
        annos.clear()
        annos.addAll(state.annos)
        cropRect = state.crop?.let { RectF(it) }
        selected = null
        invalidate()
        onChanged?.invoke()
    }

    private fun clampCrop(rect: RectF): RectF {
        val bmp = source ?: return rect
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val minSize = min(w, h) * 0.08f
        var rw = rect.width().coerceIn(minSize, w)
        var rh = rect.height().coerceIn(minSize, h)
        var left = rect.left.coerceIn(0f, w - rw)
        var top = rect.top.coerceIn(0f, h - rh)
        rw = rw.coerceAtMost(w - left).coerceAtLeast(1f)
        rh = rh.coerceAtMost(h - top).coerceAtLeast(1f)
        left = left.coerceAtMost(w - rw)
        top = top.coerceAtMost(h - rh)
        return RectF(left, top, left + rw, top + rh)
    }

    private fun textBounds(anno: TextAnno): RectF {
        textPaint.textSize = anno.size
        val rect = android.graphics.Rect()
        textPaint.getTextBounds(anno.text, 0, anno.text.length, rect)
        val pad = anno.size * 0.25f
        return RectF(
            anno.x + rect.left - pad,
            anno.y + rect.top - pad,
            anno.x + rect.right + pad,
            anno.y + rect.bottom + pad
        )
    }

    private fun textAt(x: Float, y: Float): TextAnno? {
        for (i in annos.indices.reversed()) {
            val a = annos[i]
            if (a is TextAnno && textBounds(a).contains(x, y)) return a
        }
        return null
    }

    private fun handleAt(x: Float, y: Float): Int {
        val rect = cropRect ?: return HANDLE_NONE
        val view = RectF()
        toView.mapRect(view, rect)
        val r = 28f * resources.displayMetrics.density
        val pts = corners(view)
        if (hypot(x - pts[0].first, y - pts[0].second) < r) return HANDLE_TL
        if (hypot(x - pts[1].first, y - pts[1].second) < r) return HANDLE_TR
        if (hypot(x - pts[2].first, y - pts[2].second) < r) return HANDLE_BR
        if (hypot(x - pts[3].first, y - pts[3].second) < r) return HANDLE_BL
        return if (view.contains(x, y)) HANDLE_MOVE else HANDLE_NONE
    }

    private fun toBitmapPoint(x: Float, y: Float): android.graphics.PointF {
        val pts = floatArrayOf(x, y)
        toBitmap.mapPoints(pts)
        return android.graphics.PointF(pts[0], pts[1])
    }

    private fun midX(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getX(i)
        return sum / e.pointerCount
    }

    private fun midY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    private fun distance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    companion object {
        private const val HANDLE_NONE = 0
        private const val HANDLE_MOVE = 1
        private const val HANDLE_TL = 2
        private const val HANDLE_TR = 3
        private const val HANDLE_BR = 4
        private const val HANDLE_BL = 5
    }
}
