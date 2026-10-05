package com.n3vibecode.gallery.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/**
 * Raster mit Zwei-Finger-Zoom – genau wie in einer normalen Galerie-App.
 *
 *  • Finger **zusammenziehen** (zur Mitte) → Kacheln werden kleiner, es erscheinen
 *    **mehr Fotos** auf einmal
 *  • Finger **auseinanderziehen** → Kacheln werden größer, es erscheinen **weniger Fotos**
 *
 * Technisch: Die Geste wird in [dispatchTouchEvent] abgefangen – also ganz früh, bevor
 * Kacheln, Scrollen oder die Tab-Wischgeste etwas davon merken. Solange zwei Finger
 * aufliegen, wird dem Elternteil (dem Wischen zwischen den Tabs) das Abfangen verboten.
 */
class ZoomGridRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Beginn einer Zwei-Finger-Geste (Ausgangswert merken). */
    var onPinchStart: (() -> Unit)? = null

    /** Maßstab-Änderung seit dem letzten Ereignis (kleiner 1 = zusammenziehen). */
    var onPinchScale: ((Float) -> Unit)? = null

    /** Ende der Geste (z. B. Einblendung ausblenden). */
    var onPinchEnd: (() -> Unit)? = null

    private var gestureActive = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                gestureActive = true
                disallowParent(true)
                onPinchStart?.invoke()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onPinchScale?.invoke(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                endGesture()
            }
        }
    )

    private fun disallowParent(disallow: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallow)
    }

    private fun endGesture() {
        if (!gestureActive) return
        gestureActive = false
        disallowParent(false)
        onPinchEnd?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Allererste Stelle im Ereignisweg: hier bekommt die Geste garantiert alles mit,
        // unabhängig davon, ob eine Kachel, das Scrollen oder ein Elternteil gerade "zuständig" ist.
        if (ev.pointerCount >= 2) {
            gestureActive = true
            disallowParent(true)
            scaleDetector.onTouchEvent(ev)
        } else if (gestureActive || scaleDetector.isInProgress) {
            scaleDetector.onTouchEvent(ev)
        }

        val handled = super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Bei zwei Fingern übernimmt das Raster selbst: keine Kachel wird "gedrückt",
        // kein Foto öffnet sich, das Scrollen pausiert.
        if (ev.pointerCount >= 2) {
            gestureActive = true
            disallowParent(true)
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Die Geste selbst wird schon in dispatchTouchEvent gefüttert – hier nur konsumieren.
        if (gestureActive || ev.pointerCount >= 2) return true
        return super.onTouchEvent(ev)
    }
}
