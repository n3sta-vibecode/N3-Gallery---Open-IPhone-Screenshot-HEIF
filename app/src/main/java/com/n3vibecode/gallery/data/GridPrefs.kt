package com.n3vibecode.gallery.data

import android.content.Context

/**
 * Rastergröße (Spalten pro Zeile) – mit zwei Fingern im Raster zoombar
 * und über das Menü einstellbar. Gilt für alle Ansichten und wird gemerkt.
 */
object GridPrefs {

    const val MIN = 2
    const val MAX = 32
    const val DEFAULT = 4

    /** Gruppierung der Zeitleiste. */
    const val MODE_DAY = "day"
    const val MODE_MONTH = "month"
    const val MODE_YEAR = "year"
    const val MODE_NONE = "none"

    private const val FILE = "n3_gallery_meta"
    private const val KEY = "grid_span"

    fun span(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY, DEFAULT)
            .coerceIn(MIN, MAX)

    fun setSpan(ctx: Context, value: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY, value.coerceIn(MIN, MAX))
            .apply()
    }

    /** "day" (Standard), "month", "year" oder "none" (eine durchgehende Liste). */
    fun timelineMode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString("timeline_mode", MODE_DAY)
            ?: MODE_DAY

    fun setTimelineMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("timeline_mode", mode)
            .apply()
    }
}
