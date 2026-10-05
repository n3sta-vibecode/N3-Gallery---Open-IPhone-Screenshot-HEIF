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
 * Bedienung wie in den Apple-Fotos-Apps:
 *  • **Zuschneiden:** Der Rahmen steht fest, das **Foto wird darunter verschoben**
 *    (ein Finger) bzw. mit zwei Fingern gezoomt. Die Rahmengröße ändert man an den
 *    acht Griffen (4 Ecken + 4 Kanten); Seitenverhältnisse gibt es als Auswahl.
 *    Dadurch muss man nicht genau auf einer Ecke „treffen“, nur um den Bildausschnitt
 *    zu wählen – das war vorher die mühsame Stelle.
 *  • **Zeichnen:** ein Finger zeichnet, zwei Finger zoomen.
 *  • **Text:** tippen setzt Text, ziehen verschiebt, Doppeltipp ändert.
 *
 * Alle Bearbeitungen liegen in *Bildkoordinaten* – das Ergebnis ist unabhängig vom Zoom
 * und [renderResult] liefert immer die volle Auflösung.
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
            userScale = 1f
            panX = 0f
            panY = 0f
            annos.clear()
            undoStack.clear()
            redoStack.clear()
            cropRect = null
            cropFrame = null
            cropAspect = null
            selected = null
            updateMatrix()
        }

    var mode: EditorMode = EditorMode.VIEW
        set(value) {
            if (field == value) return
            field = value
            drawing = null
            selected = null
            // Beim Zuschneiden mehr Rand lassen, damit die Griffe gut erreichbar sind
            updateMatrix()
            if (value == EditorMode.CROP) startCrop()
            invalidate()
        }

    var drawColor: Int = Color.parseColor("#FF3B30")
        set(value) { field = value; invalidate() }

    var textColor: Int = Color.WHITE
        set(value) {
            field = value
            selected?.let { sel -> replaceText(sel, sel.copy(color = value)) }
        }

    /** Strichstärke in Bildpunkten. */
    var strokeWidth: Float = 14f

    /** Textgröße in Bildpunkten. */
    var textSize: Float = 110f

    /** Wird gerufen, wenn im Text-Modus in eine freie Fläche getippt wird (Bildkoordinaten). */
    var onTextRequest: ((Float, Float) -> Unit)? = null

    /** Wird gerufen, wenn ein vorhandenes Textfeld angetippt wird. */
    var onTextTapped: ((TextAnno) -> Unit)? = null

    /** Wird gerufen, wenn ein Textfeld zum Ändern doppelt angetippt wurde. */
    var onTextEditRequest: ((TextAnno) -> Unit)? = null

    /** Meldet Änderungen (für Rückgängig/Wiederholen-Schaltflächen). */
    var onChanged: (() -> Unit)? = null

    private val annos = mutableListOf<Any>()

    /** Zuschnitt in **Bildkoordinaten** (für Speichern und „gibt es Änderungen?“). */
    private var cropRect: RectF? = null

    /** Zuschnitt-Rahmen in **Ansichtskoordinaten** (das, was der Nutzer sieht/zieht). */
    private var cropFrame: RectF? = null

    /** Seitenverhältnis des Rahmens (null = frei). */
    private var cropAspect: Float? = null

    private var selected: TextAnno? = null

    private var fitScale = 1f
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f

    private val toView = Matrix()
    private val toBitmap = Matrix()

    private var drawing: Path? = null

    // Gesten
    private var activeHandle = HANDLE_NONE
    private var gestureStartFrame = RectF()
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var panStartX = 0f
    private var panStartY = 0f
    private var selectedStart: TextAnno? = null
    private var gestureHistoryPushed = false

    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDistance = 0f

    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private data class Snapshot(val annos: List<Any>, val crop: RectF?, val frame: RectF?, val aspect: Float?)

    // Paints
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
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3000000")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#55FFFFFF")
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val edgeKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#CCFFFFFF")
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#FF3B30")
    }

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    // ------------------------------------------------------------------ Öffentliche API

    /** true, wenn wirklich etwas weggeschnitten wird (nicht nur der Rahmen aufgezogen wurde). */
    fun hasCrop(): Boolean {
        val src = source ?: return false
        val c = cropRect ?: return false
        // Kleine Toleranz, damit Rundungsfehler beim Rahmen-Ziehen nicht als „Zuschnitt“ gelten
        val tol = maxOf(2f, min(src.width, src.height) * 0.004f)
        return c.left > tol || c.top > tol ||
            src.width - c.right > tol || src.height - c.bottom > tol
    }

    fun hasChanges(): Boolean = annos.isNotEmpty() || hasCrop()

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
        cropFrame = null
        cropAspect = null
        selected = null
        if (mode == EditorMode.CROP) startCrop()
        invalidate()
        onChanged?.invoke()
    }

    /** Textfeld anlegen (Bildkoordinaten; ohne Angabe mittig). */
    fun addText(text: String, x: Float? = null, y: Float? = null) {
        val bmp = source ?: return
        if (text.isBlank()) return
        pushHistory()
        val anno = TextAnno(
            text.trim(),
            x ?: (bmp.width / 2f),
            y ?: (bmp.height / 2f),
            textSize,
            textColor
        )
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

    /**
     * Seitenverhältnis setzen (null = frei). Der Rahmen wird auf die größtmögliche
     * Fläche dieses Verhältnisses gesetzt – das Foto wird darunter automatisch
     * passend verschoben/gezoomt.
     */
    fun applyAspect(ratio: Float?) {
        val bmp = source ?: return
        cropAspect = ratio
        pushHistory()
        val bounds = frameBounds()
        val target: RectF
        if (ratio == null) {
            // Frei: aktuellen Rahmen behalten (oder das ganze Bild)
            target = cropFrame?.let { RectF(it) } ?: imageBoundsInView()
        } else {
            val area = frameBounds()
            var w = area.width()
            var h = w / ratio
            if (h > area.height()) {
                h = area.height()
                w = h * ratio
            }
            val cx = area.centerX()
            val cy = area.centerY()
            target = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }
        cropFrame = clampFrame(target, bounds)
        coverFrameWithPhoto()
        syncCropRect()
        invalidate()
        onChanged?.invoke()
    }

    /** Zuschnitt komplett zurücksetzen (ganzes Bild). */
    fun resetCrop() {
        if (cropRect == null && cropFrame == null) return
        pushHistory()
        cropAspect = null
        cropFrame = imageBoundsInView()
        userScale = 1f
        panX = 0f
        panY = 0f
        updateMatrix()
        syncCropRect()
        invalidate()
        onChanged?.invoke()
    }

    /** Rahmen anzeigen – beim Wechsel in den Zuschnitt-Modus. */
    fun startCrop() {
        val src = source ?: return
        if (width == 0 || height == 0) return
        val existing = cropRect
        val full = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        cropFrame = if (existing != null && (existing.left > 1f || existing.top > 1f ||
                src.width - existing.right > 1f || src.height - existing.bottom > 1f)
        ) {
            // Bestehenden Ausschnitt wieder anzeigen
            val r = RectF()
            toView.mapRect(r, existing)
            clampFrame(r, frameBounds())
        } else {
            imageBoundsInView()
        }
        coverFrameWithPhoto()
        syncCropRect()
        invalidate()
    }

    /**
     * Ergebnis in voller Auflösung: Zuschnitt + Zeichnungen + Text.
     * [maxDim] begrenzt die Kantenlänge (z. B. 4096 px) und schützt vor Speicherproblemen.
     */
    fun renderResult(maxDim: Int = 4096): Bitmap? {
        val src = source ?: return null
        val crop = cropRect?.let { clampCrop(RectF(it)) }
            ?: RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
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
        canvas.drawColor(Color.BLACK)
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

    // ------------------------------------------------------------------ Geometrie

    private fun paddingPx(): Float = if (mode == EditorMode.CROP) dp(34f) else dp(8f)

    /** Bereich, in dem der Rahmen liegen darf. */
    private fun frameBounds(): RectF {
        val p = dp(6f)
        return RectF(p, p, (width - p).coerceAtLeast(p + 1f), (height - p).coerceAtLeast(p + 1f))
    }

    /** Das Foto in Ansichtskoordinaten (so wie es gerade dargestellt wird). */
    private fun imageBoundsInView(): RectF {
        val bmp = source ?: return RectF()
        val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val out = RectF()
        toView.mapRect(out, r)
        return out
    }

    private fun clampFrame(rect: RectF, bounds: RectF): RectF {
        val minSize = dp(56f)
        var w = rect.width().coerceAtLeast(minSize)
        var h = rect.height().coerceAtLeast(minSize)
        if (w > bounds.width()) w = bounds.width()
        if (h > bounds.height()) h = bounds.height()
        var left = rect.left.coerceIn(bounds.left, (bounds.right - w).coerceAtLeast(bounds.left))
        var top = rect.top.coerceIn(bounds.top, (bounds.bottom - h).coerceAtLeast(bounds.top))
        if (cropAspect != null) {
            // Seitenverhältnis beibehalten, in den erlaubten Bereich einpassen
            val ratio = cropAspect!!
            if (w / h > ratio) w = h * ratio else h = w / ratio
            w = w.coerceAtMost(bounds.width())
            h = h.coerceAtMost(bounds.height())
            if (w / h > ratio) w = h * ratio else h = w / ratio
            left = left.coerceIn(bounds.left, (bounds.right - w).coerceAtLeast(bounds.left))
            top = top.coerceIn(bounds.top, (bounds.bottom - h).coerceAtLeast(bounds.top))
        }
        return RectF(left, top, left + w, top + h)
    }

    /**
     * Sorgt dafür, dass das Foto den Rahmen **komplett ausfüllt**: Bei Bedarf wird
     * hineingezoomt und so verschoben, dass keine Lücke entsteht. Das ist das Verhalten
     * der Apple-Fotos-App – der Ausschnitt ist immer mit Bild gefüllt.
     */
    private fun coverFrameWithPhoto() {
        val bmp = source ?: return
        val f = cropFrame ?: return
        if (width == 0 || height == 0 || bmp.width == 0 || bmp.height == 0) return

        val base = fitScale.coerceAtLeast(0.0001f)
        // Nötiger Maßstab, damit das Foto den Rahmen bedeckt
        val needUser = maxOf(f.width() / (bmp.width * base), f.height() / (bmp.height * base))
        val minUser = needUser.coerceAtLeast(0.2f)
        if (userScale < minUser) userScale = minUser.coerceAtMost(MAX_ZOOM)
        updateMatrix()

        // Verschieben begrenzen: keine Lücke an den Rahmenkanten
        val scale = base * userScale
        val imgW = bmp.width * scale
        val imgH = bmp.height * scale
        var left = (width - imgW) / 2f + panX
        var top = (height - imgH) / 2f + panY
        if (left > f.left) left = f.left
        if (top > f.top) top = f.top
        if (left + imgW < f.right) left = f.right - imgW
        if (top + imgH < f.bottom) top = f.bottom - imgH
        panX = left - (width - imgW) / 2f
        panY = top - (height - imgH) / 2f
        updateMatrix()

        // Falls das Foto (auch mit maximalem Zoom) nicht reicht: Rahmen verkleinern
        val img = imageBoundsInView()
        if (img.width() < f.width() - 1f || img.height() < f.height() - 1f) {
            val shrunk = RectF(f)
            shrunk.left = f.left.coerceAtLeast(img.left)
            shrunk.top = f.top.coerceAtLeast(img.top)
            shrunk.right = f.right.coerceAtMost(img.right)
            shrunk.bottom = f.bottom.coerceAtMost(img.bottom)
            if (shrunk.width() > dp(40f) && shrunk.height() > dp(40f)) cropFrame = shrunk
        }
    }

    /** Übernimmt den sichtbaren Rahmen in Bildkoordinaten (für Speichern/Änderungen). */
    private fun syncCropRect() {
        val frame = cropFrame
        if (frame == null || mode != EditorMode.CROP) return
        val r = RectF()
        val inverse = Matrix()
        if (!toView.invert(inverse)) return
        inverse.mapRect(r, frame)
        cropRect = clampCrop(r)
    }

    private fun clampCrop(rect: RectF): RectF {
        val bmp = source ?: return rect
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val minSize = min(w, h) * 0.04f
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

    private fun updateMatrix() {
        val bmp = source ?: return
        if (width == 0 || height == 0) return
        val pad = paddingPx()
        val base = min(
            (width - pad * 2) / bmp.width.toFloat(),
            (height - pad * 2) / bmp.height.toFloat()
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

    // ------------------------------------------------------------------ Zeichnen

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
        if (mode == EditorMode.CROP && cropFrame == null) startCrop()
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
        val frame = cropFrame ?: return
        // Außenbereich abdunkeln – der Ausschnitt bleibt hell
        canvas.save()
        canvas.clipOutRect(frame)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.restore()

        // Drittel-Raster
        val thirdW = frame.width() / 3f
        val thirdH = frame.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(frame.left + thirdW * i, frame.top, frame.left + thirdW * i, frame.bottom, gridPaint)
            canvas.drawLine(frame.left, frame.top + thirdH * i, frame.right, frame.top + thirdH * i, gridPaint)
        }
        canvas.drawRect(frame, framePaint)

        // Ecken-Winkel (wie in iOS) und Kanten-Griffe
        val arm = min(dp(26f), min(frame.width(), frame.height()) / 4f)
        // oben links
        canvas.drawLine(frame.left, frame.top, frame.left + arm, frame.top, handlePaint)
        canvas.drawLine(frame.left, frame.top, frame.left, frame.top + arm, handlePaint)
        // oben rechts
        canvas.drawLine(frame.right - arm, frame.top, frame.right, frame.top, handlePaint)
        canvas.drawLine(frame.right, frame.top, frame.right, frame.top + arm, handlePaint)
        // unten links
        canvas.drawLine(frame.left, frame.bottom - arm, frame.left, frame.bottom, handlePaint)
        canvas.drawLine(frame.left, frame.bottom, frame.left + arm, frame.bottom, handlePaint)
        // unten rechts
        canvas.drawLine(frame.right, frame.bottom - arm, frame.right, frame.bottom, handlePaint)
        canvas.drawLine(frame.right - arm, frame.bottom, frame.right, frame.bottom, handlePaint)

        // Kantenmitten
        val knob = dp(14f)
        val midX = frame.centerX()
        val midY = frame.centerY()
        canvas.drawLine(midX - knob, frame.top, midX + knob, frame.top, edgeKnobPaint)
        canvas.drawLine(midX - knob, frame.bottom, midX + knob, frame.bottom, edgeKnobPaint)
        canvas.drawLine(frame.left, midY - knob, frame.left, midY + knob, edgeKnobPaint)
        canvas.drawLine(frame.right, midY - knob, frame.right, midY + knob, edgeKnobPaint)
    }

    private fun drawSelection(canvas: Canvas, anno: TextAnno) {
        val rect = RectF()
        toView.mapRect(rect, textBounds(anno))
        canvas.drawRect(rect, selectedPaint)
    }

    // ------------------------------------------------------------------ Eingaben

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureHistoryPushed = false
                lastMidX = event.x
                lastMidY = event.y
                lastDistance = 0f
                onSingleDown(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Ab zwei Fingern wird gezoomt/verschoben, nicht gezeichnet
                drawing = null
                activeHandle = HANDLE_NONE
                lastMidX = midX(event)
                lastMidY = midY(event)
                lastDistance = distance(event)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) handleZoom(event) else handleSingleMove(event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
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
                activeHandle = HANDLE_NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Zwei Finger: zoomen (Brennpunkt zwischen den Fingern) und verschieben. */
    private fun handleZoom(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val midXNow = midX(event)
        val midYNow = midY(event)
        val distNow = distance(event)
        if (lastDistance > 0f && distNow > 0f) {
            val factor = distNow / lastDistance
            var newUser = (userScale * factor).coerceIn(minUserScale(), MAX_ZOOM)
            val applied = newUser / userScale
            userScale = newUser
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
        if (mode == EditorMode.CROP) {
            pushHistoryOnce()
            coverFrameWithPhoto()
            syncCropRect()
        } else {
            clampPan()
        }
    }

    private fun minUserScale(): Float = if (mode == EditorMode.CROP) {
        // Im Zuschnitt muss das Foto den Rahmen immer ausfüllen
        val bmp = source ?: return 0.2f
        val f = cropFrame ?: return 0.2f
        val base = fitScale.coerceAtLeast(0.0001f)
        maxOf(0.2f, maxOf(f.width() / (bmp.width * base), f.height() / (bmp.height * base)))
    } else 0.5f

    private fun onSingleDown(x: Float, y: Float) {
        val point = toBitmapPoint(x, y)
        when (mode) {
            EditorMode.DRAW -> {
                drawing = Path().apply { moveTo(point.x, point.y) }
                invalidate()
            }
            EditorMode.CROP -> {
                gestureStartX = x
                gestureStartY = y
                panStartX = panX
                panStartY = panY
                cropFrame?.let { gestureStartFrame = RectF(it) }
                activeHandle = handleAt(x, y)
            }
            EditorMode.TEXT -> {
                val hit = textAt(point.x, point.y)
                if (hit != null) {
                    selected = hit
                    selectedStart = hit
                    gestureStartX = x
                    gestureStartY = y
                    onTextTapped?.invoke(hit)
                } else {
                    selected = null
                }
                invalidate()
            }
            EditorMode.VIEW -> Unit
        }
    }

    private fun handleSingleMove(event: MotionEvent) {
        val point = toBitmapPoint(event.x, event.y)
        when (mode) {
            EditorMode.DRAW -> {
                drawing?.lineTo(point.x, point.y)
                invalidate()
            }
            EditorMode.CROP -> {
                if (activeHandle == HANDLE_NONE) return
                pushHistoryOnce()
                if (activeHandle == HANDLE_MOVE) {
                    // Foto unter dem festen Rahmen verschieben – wie bei Apple
                    panX = panStartX + (event.x - gestureStartX)
                    panY = panStartY + (event.y - gestureStartY)
                    coverFrameWithPhoto()
                } else {
                    cropFrame = resizeFrame(activeHandle, event.x - gestureStartX, event.y - gestureStartY)
                    coverFrameWithPhoto()
                }
                syncCropRect()
                invalidate()
                onChanged?.invoke()
            }
            EditorMode.TEXT -> {
                val start = selectedStart ?: return
                val index = annos.indexOf(start)
                if (index < 0) return
                if (abs(event.x - gestureStartX) < dp(1f) && abs(event.y - gestureStartY) < dp(1f)) return
                pushHistoryOnce()
                val moved = start.copy(x = point.x, y = point.y)
                annos[index] = moved
                selected = moved
                selectedStart = moved
                gestureStartX = event.x
                gestureStartY = event.y
                invalidate()
            }
            EditorMode.VIEW -> Unit
        }
    }

    /** Rahmen an einem der acht Griffe verändern (Seitenverhältnis bleibt erhalten). */
    private fun resizeFrame(handle: Int, dx: Float, dy: Float): RectF {
        val s = gestureStartFrame
        var l = s.left
        var t = s.top
        var r = s.right
        var b = s.bottom
        when (handle) {
            HANDLE_TL -> { l += dx; t += dy }
            HANDLE_TR -> { r += dx; t += dy }
            HANDLE_BL -> { l += dx; b += dy }
            HANDLE_BR -> { r += dx; b += dy }
            HANDLE_L -> l += dx
            HANDLE_R -> r += dx
            HANDLE_T -> t += dy
            HANDLE_B -> b += dy
        }
        val minSize = dp(56f)
        if (r - l < minSize) { if (handle == HANDLE_L || handle == HANDLE_TL || handle == HANDLE_BL) l = r - minSize else r = l + minSize }
        if (b - t < minSize) { if (handle == HANDLE_T || handle == HANDLE_TL || handle == HANDLE_TR) t = b - minSize else b = t + minSize }

        val ratio = cropAspect
        if (ratio != null) {
            val horizontal = handle == HANDLE_L || handle == HANDLE_R ||
                handle == HANDLE_TL || handle == HANDLE_TR || handle == HANDLE_BL || handle == HANDLE_BR
            if (horizontal) {
                val w = r - l
                val h = (w / ratio).coerceAtLeast(minSize)
                when (handle) {
                    HANDLE_TL, HANDLE_TR -> t = b - h
                    HANDLE_BL, HANDLE_BR -> b = t + h
                    else -> { // Seiten-Griff: vertikal zentriert wachsen
                        val cy = s.centerY()
                        t = cy - h / 2f
                        b = cy + h / 2f
                    }
                }
            } else {
                val h = b - t
                val w = (h * ratio).coerceAtLeast(minSize)
                val cx = s.centerX()
                l = cx - w / 2f
                r = cx + w / 2f
            }
        }
        return RectF(l, t, r, b)
    }

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
                if (activeHandle != HANDLE_NONE) {
                    cropFrame = cropFrame?.let { clampFrame(RectF(it), frameBounds()) }
                    coverFrameWithPhoto()
                    syncCropRect()
                    onChanged?.invoke()
                }
                activeHandle = HANDLE_NONE
                invalidate()
            }
            EditorMode.TEXT -> {
                val now = System.currentTimeMillis()
                val isDoubleTap = now - lastTapAt < 320 &&
                    hypot(x - lastTapX, y - lastTapY) < dp(40f)
                lastTapAt = now
                lastTapX = x
                lastTapY = y
                val point = toBitmapPoint(x, y)
                val hit = textAt(point.x, point.y)
                if (isDoubleTap && hit != null) {
                    onTextEditRequest?.invoke(hit)
                } else if (hit == null && !isDoubleTap) {
                    onTextRequest?.invoke(point.x, point.y)
                }
            }
            EditorMode.VIEW -> Unit
        }
    }

    /** Pan nur so weit, dass das Foto die Ansicht nicht verlässt (nicht im Zuschnitt-Modus). */
    private fun clampPan() {
        val bmp = source ?: return
        val scale = fitScale * userScale
        val imgW = bmp.width * scale
        val imgH = bmp.height * scale
        val maxX = ((imgW - width) / 2f).coerceAtLeast(0f)
        val maxY = ((imgH - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        updateMatrix()
    }

    // ------------------------------------------------------------------ Hilfen

    private fun pushHistory() {
        undoStack.addLast(snapshot())
        while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Pro Geste nur einmal in die Historie schreiben (Ziehen erzeugt viele Ereignisse). */
    private fun pushHistoryOnce() {
        if (gestureHistoryPushed) return
        gestureHistoryPushed = true
        pushHistory()
    }

    private fun snapshot(): Snapshot =
        Snapshot(annos.toList(), cropRect?.let { RectF(it) }, cropFrame?.let { RectF(it) }, cropAspect)

    private fun restore(state: Snapshot) {
        annos.clear()
        annos.addAll(state.annos)
        cropRect = state.crop?.let { RectF(it) }
        cropFrame = state.frame?.let { RectF(it) }
        cropAspect = state.aspect
        selected = null
        invalidate()
        onChanged?.invoke()
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

    /** Welcher Griff liegt unter dem Finger? (Ecken und Kanten, großzügiger Radius) */
    private fun handleAt(x: Float, y: Float): Int {
        val f = cropFrame ?: return HANDLE_NONE
        val r = dp(30f)
        val midX = f.centerX()
        val midY = f.centerY()
        val spots = listOf(
            Triple(HANDLE_TL, f.left, f.top),
            Triple(HANDLE_TR, f.right, f.top),
            Triple(HANDLE_BL, f.left, f.bottom),
            Triple(HANDLE_BR, f.right, f.bottom),
            Triple(HANDLE_T, midX, f.top),
            Triple(HANDLE_B, midX, f.bottom),
            Triple(HANDLE_L, f.left, midY),
            Triple(HANDLE_R, f.right, midY)
        )
        for ((handle, hx, hy) in spots) {
            if (hypot(x - hx, y - hy) < r) return handle
        }
        return if (f.contains(x, y)) HANDLE_MOVE else HANDLE_NONE
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
        private const val MAX_ZOOM = 8f

        private const val HANDLE_NONE = 0
        private const val HANDLE_MOVE = 1
        private const val HANDLE_TL = 2
        private const val HANDLE_TR = 3
        private const val HANDLE_BR = 4
        private const val HANDLE_BL = 5
        private const val HANDLE_T = 6
        private const val HANDLE_B = 7
        private const val HANDLE_L = 8
        private const val HANDLE_R = 9
    }
}
