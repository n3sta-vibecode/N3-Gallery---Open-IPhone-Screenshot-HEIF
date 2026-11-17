package com.n3vibecode.gallery.ui

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.Labels
import com.n3vibecode.gallery.data.MediaMeta
import com.n3vibecode.gallery.data.MetaSection
import com.n3vibecode.gallery.map.MapView

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

        // Ganz oben die Karte: der Ort des Fotos ist so sofort sichtbar (antippen = groß).
        addMapCard(context, container, meta)

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

    /**
     * Karte direkt in den Metadaten – oben, vor allen Zeilen.
     *
     * Vorher musste man erst eine Zeile „Auf Karte zeigen“ suchen (und dafür nach unten
     * scrollen). Jetzt steht die Karte mit allen Fotos, der eigene Punkt ist rot markiert;
     * antippen öffnet sie groß. Ohne Position im Foto entfällt der Block.
     *
     * 1.42: auch für das neue Blatt „Wichtigste Angaben“ in der Detailansicht benutzt.
     */
    internal fun addMapCard(context: Context, container: LinearLayout, meta: MediaMeta) {
        val point = meta.mapPoint
        if (point == null) {
            // „Karte bei jedem Foto“: Ist im Foto kein Ort gespeichert, steht hier trotzdem ein
            // Karten-Block – mit der Wahrheit („kein Ort gespeichert“) und dem Weg zur Karte.
            // Vorher fehlte der Block komplett, was wie ein Fehler aussah.
            addNoMapCard(context, container)
            return
        }
        val height = dp(context, 190)
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            ).apply { bottomMargin = dp(context, 10) }
        }
        val view = MapView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setMarkers(
                listOf(
                    MapView.Marker(
                        id = "self",
                        lat = point.first,
                        lon = point.second,
                        count = 1,
                        label = meta.mapLabel.orEmpty()
                    )
                )
            )
            isClickable = true
            setOnClickListener {
                runCatching { context.startActivity(MapActivity.intentFor(context, point.first, point.second)) }
            }
            // Gleiche Wahl wie in der großen Karte (OpenStreetMap ist Standard, 1.42)
            useOsm = runCatching {
                context.getSharedPreferences("n3_map", Context.MODE_PRIVATE)
                    .getBoolean("osm", true)
            }.getOrDefault(true)
            post { fitToMarkers(minDegrees = 6f) }
        }
        frame.addView(view)
        // Runder Rahmen + Hinweis „antippen für groß“
        frame.setBackgroundResource(R.drawable.bg_map_card)
        container.addView(frame)

        val hint = TextView(context).apply {
            text = context.getString(R.string.map_tap_to_open)
            setTextColor(context.getColor(R.color.n3_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(context, 4), 0, 0, dp(context, 10))
            setOnClickListener {
                runCatching { context.startActivity(MapActivity.intentFor(context, point.first, point.second)) }
            }
        }
        container.addView(hint)
    }

    /** Karten-Platzhalter für Fotos ohne Ortsangabe – gleiche Höhe, klare Aussage. */
    private fun addNoMapCard(context: Context, container: LinearLayout) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
            setBackgroundResource(R.drawable.bg_map_card)
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            isClickable = true
            setOnClickListener {
                runCatching { context.startActivity(android.content.Intent(context, MapActivity::class.java)) }
            }
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.map_no_place_title)
            setTextColor(context.getColor(R.color.n3_on_bg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPaintFlags(paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG)
        }
        val sub = TextView(context).apply {
            text = context.getString(R.string.map_no_place_hint)
            setTextColor(context.getColor(R.color.n3_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(context, 4), 0, 0)
        }
        box.addView(title)
        box.addView(sub)
        container.addView(box)
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
