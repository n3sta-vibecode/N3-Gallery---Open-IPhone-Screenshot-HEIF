package com.n3vibecode.gallery.ui

import android.content.Context
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
            val valueView = rowView.findViewById<TextView>(R.id.tvValue)
            // Sonderfall „Auf Karte zeigen“: antippbar und öffnet die eingebaute Karte
            // (ohne Internet, mit den Kartendaten aus dem App-Paket).
            if (row.value.startsWith("map:")) {
                val parts = row.value.removePrefix("map:").split(',')
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    valueView.text = context.getString(R.string.map_show) + "  ›"
                    valueView.setTextColor(context.getColor(R.color.n3_accent))
                    valueView.setOnClickListener { v ->
                        runCatching {
                            v.context.startActivity(
                                MapActivity.intentFor(v.context, lat, lon)
                            )
                        }
                    }
                    container.addView(rowView)
                    return@forEach
                }
                valueView.text = "–"
            } else {
                valueView.text = Labels.tr(context, row.value)
            }
            container.addView(rowView)
        }
    }

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
