package com.n3vibecode.gallery.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.roundToInt

/**
 * Bildansicht, die ihr **Seitenverhältnis vom Bild übernimmt** (statt es zu beschneiden
 * oder zu verzerren).
 *
 * Genau das ist bei der Vorschau nach langem Drücken wichtig: Ein iPhone-Screenshot ist
 * hochkant (9:19,5), ein Panorama sehr breit, ein RAW 3:2. Im quadratischen Raster sind alle
 * Kacheln gleich groß (das ist gewollt und sieht wie bei Apple Fotos aus) – die Vorschau
 * zeigt das Foto aber **vollständig und im richtigen Verhältnis**.
 *
 * Bei `adjustViewBounds` allein funktioniert das nur mit festen Grenzen; hier wird die Höhe
 * zusätzlich auf einen Höchstwert begrenzt, damit ein sehr hochkantes Bild nicht über den
 * Bildschirm hinausläuft.
 */
class AspectImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Höchstanteil der Bildschirmhöhe, den das Bild einnehmen darf (0.1 … 1.0). */
    var maxHeightFraction: Float = 0.62f

    /** Kleinste Höhe in dp – damit ein Panorama nicht zu einem Strich wird. */
    var minHeightDp: Float = 90f

    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        requestLayout()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d: Drawable? = drawable
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0 || wSize <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val density = resources.displayMetrics.density
        val maxH = (resources.displayMetrics.heightPixels * maxHeightFraction)
            .coerceAtLeast(minHeightDp * density)
        val aspect = d.intrinsicHeight.toFloat() / d.intrinsicWidth.toFloat()
        var h = (wSize * aspect).roundToInt().coerceAtLeast((minHeightDp * density).roundToInt())
        var w = wSize
        if (h > maxH) {
            // Sehr hochkant (z. B. Handy-Screenshot): Höhe begrenzen, Breite folgt daraus
            h = maxH.roundToInt()
            w = (h / aspect).roundToInt().coerceAtMost(wSize).coerceAtLeast(1)
        }
        setMeasuredDimension(w, h)
    }
}
