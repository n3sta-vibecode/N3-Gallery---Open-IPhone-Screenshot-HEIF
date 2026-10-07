package com.n3vibecode.gallery.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.Labels
import com.n3vibecode.gallery.data.MediaMeta
import com.n3vibecode.gallery.data.MetaSection

/**
 * Baut die Metadaten-Ansicht (Abschnitte + Zeilen) für Detailansicht und Info-Tab.
 */
object MetaRenderer {

    fun render(context: Context, container: LinearLayout) {
        container.removeAllViews()
        val tv = TextView(context).apply {
            text = context.getString(R.string.scanning)
            setTextColor(context.getColor(R.color.n3_on_surface_variant))
            setPadding(0, dp(context, 18), 0, 0)
        }
        container.addView(tv)
    }

    fun render(context: Context, container: LinearLayout, meta: MediaMeta, showAll: Boolean = true) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)

        meta.sections.forEach { section ->
            addSection(context, container, inflater, section)
        }

        if (showAll && meta.exifTagDump.isNotEmpty()) {
            addSection(
                context, container, inflater,
                MetaSection(context.getString(R.string.raw_fields), meta.exifTagDump)
            )
        }

        // Fußzeile
        val footer = TextView(context).apply {
            text = context.getString(R.string.meta_footer)
            setTextColor(context.getColor(R.color.n3_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(context, 20), 0, dp(context, 8))
        }
        container.addView(footer)
    }

    private fun addSection(context: Context, container: LinearLayout, inflater: LayoutInflater, section: MetaSection) {
        if (section.rows.isEmpty()) return
        val title = TextView(context)
        title.setTextAppearance(R.style.N3SectionTitle)
        title.text = Labels.tr(context, section.title)
        container.addView(title)

        section.rows.forEach { row ->
            val rowView = inflater.inflate(R.layout.item_meta_row, container, false)
            rowView.findViewById<TextView>(R.id.tvLabel).text = Labels.tr(context, row.label)
            rowView.findViewById<TextView>(R.id.tvValue).text = Labels.tr(context, row.value)
            container.addView(rowView)
        }
    }

    /** Karte in Google Maps öffnen – funktioniert nur, wenn die Notiz-Koordinaten vorhanden sind. */
    /**
     * Öffnet Koordinaten in einer Karten-App.
     *
     * GEHÄRTET (war toter Code, enthielt aber riskante Muster): Koordinaten stammen aus
     * EXIF-Daten dritten Ursprungs. Ungeprüfte Interpolation in eine URI plus fehlende
     * Absicherung gegen eine fehlende Ziel-App wurden ersetzt durch Wertebereichsprüfung,
     * Formatierung mit Locale.US und try/catch um startActivity. Bewusst kein
     * resolveActivity(): das ist ab API 30 durch Package Visibility eingeschränkt.
     *
     * @return true, wenn eine Karten-App gestartet wurde.
     */
    fun openMap(context: Context, lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        val coord = "${fmtCoord(lat)},${fmtCoord(lon)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coord?q=$coord")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }

    private fun fmtCoord(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
