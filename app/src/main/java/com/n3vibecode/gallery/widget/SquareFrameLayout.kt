package com.n3vibecode.gallery.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Immer quadratisch (Höhe = Breite). Damit werden die Kacheln beim Rauszoomen wirklich
 * kleiner und es passen mehr Fotos auf den Bildschirm – bei fester Höhe wurden sie nur schmaler.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val w = measuredWidth
        setMeasuredDimension(w, w)
    }
}
