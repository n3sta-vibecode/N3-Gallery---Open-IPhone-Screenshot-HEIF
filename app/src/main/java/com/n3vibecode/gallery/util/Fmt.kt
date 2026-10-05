package com.n3vibecode.gallery.util

import android.content.Context
import com.n3vibecode.gallery.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Formatierungshilfen (deutsche Schreibweise, z. B. 5,6 MB und 1/125 s). */
object Fmt {

    /** Von GalleryApp gesetzt – für „Heute“/„Gestern“ in der Sprache des Geräts. */
    var appContext: Context? = null

    private val loc: java.util.Locale get() =
        appContext?.resources?.configuration?.locales?.get(0) ?: java.util.Locale.getDefault()

    fun bytes(b: Long): String {
        if (b < 0) return "–"
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.GERMAN, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.GERMAN, "%.1f MB", mb)
        return String.format(Locale.GERMAN, "%.2f GB", mb / 1024.0)
    }

    fun dateTime(ms: Long): String {
        if (ms <= 0) return "–"
        return SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMAN).format(Date(ms))
    }

    fun showDate(ms: Long): String {
        if (ms <= 0) return "–"
        return SimpleDateFormat("d. MMM yyyy", Locale.GERMAN).format(Date(ms))
    }

    fun timeOnly(ms: Long): String = SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date(ms))

    fun dayKey(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = if (ms > 0) ms else 0
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** "Heute", "Gestern" oder ausgeschriebenes Datum – wie in der Apple-Fotos-App. */
    fun dayTitle(ms: Long): String {
        if (ms <= 0) return ctx(R.string.date_unknown, "Unknown date")
        val today = dayKey(System.currentTimeMillis())
        val d = dayKey(ms)
        val oneDay = TimeUnit.DAYS.toMillis(1)
        return when (d) {
            today -> ctx(R.string.day_today, "Today")
            today - oneDay -> ctx(R.string.day_yesterday, "Yesterday")
            today - 2 * oneDay -> ctx(R.string.day_before_yesterday, "Day before yesterday")
            else -> {
                val cal = Calendar.getInstance()
                cal.timeInMillis = ms
                val fmt = SimpleDateFormat(
                    if (cal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) "EEEE, d. MMMM" else "d. MMMM yyyy",
                    Locale.GERMAN
                )
                fmt.format(Date(ms)).replaceFirstChar { it.uppercase() }
            }
        }
    }

    /** Schlüssel für die Gruppierung nach Monat (Jahr*12 + Monat) bzw. Jahr. */
    fun monthKey(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = if (ms > 0) ms else 0
        return c.get(Calendar.YEAR) * 12L + c.get(Calendar.MONTH)
    }

    fun yearKey(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = if (ms > 0) ms else 0
        return c.get(Calendar.YEAR).toLong()
    }

    /** "Oktober 2026" · "Unbekanntes Datum" */
    fun monthTitle(ms: Long): String {
        if (ms <= 0) return ctx(R.string.date_unknown, "Unknown date")
        return SimpleDateFormat("LLLL yyyy", Locale.GERMAN)
            .format(Date(ms)).replaceFirstChar { it.uppercase() }
    }

    /** "2026" */
    fun yearTitle(ms: Long): String {
        if (ms <= 0) return ctx(R.string.year_unknown, "Unknown year")
        return SimpleDateFormat("yyyy", Locale.GERMAN).format(Date(ms))
    }

    /** "1.–31. Okt 2026" – Zeitraum eines Monats für die Unterzeile. */
    fun monthRange(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.DAY_OF_MONTH, 1)
        val start = Date(c.timeInMillis)
        c.add(Calendar.MONTH, 1)
        c.add(Calendar.DAY_OF_MONTH, -1)
        val end = Date(c.timeInMillis)
        val f = SimpleDateFormat("d.", Locale.GERMAN)
        val f2 = SimpleDateFormat("d. MMM yyyy", Locale.GERMAN)
        return f.format(start) + "–" + f2.format(end)
    }

    fun weekday(ms: Long): String =
        SimpleDateFormat("EEEE", Locale.GERMAN).format(Date(ms)).replaceFirstChar { it.uppercase() }

    fun duration(ms: Long): String {
        if (ms <= 0) return "–"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.GERMAN, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.GERMAN, "%d:%02d", m, s)
    }

    fun ratio(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "–"
        val g = gcd(w, h)
        val a = w / g
        val b = h / g
        return if (a > 40 || b > 40) String.format(Locale.GERMAN, "%.2f:1", w.toDouble() / h)
        else "$a:$b"
    }

    /** Text aus den Ressourcen, sonst der englische Rückfallwert. */
    private fun ctx(id: Int, fallback: String): String =
        appContext?.getString(id) ?: fallback

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    fun megapixel(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "–"
        return String.format(Locale.GERMAN, "%.1f MP", (w.toLong() * h) / 1_000_000.0)
    }

    /** "55/10" -> "5,5" · "1/125" bleibt "1/125" · "0/10" -> null */
    fun rational(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (!s.contains("/")) {
            return s.toDoubleOrNull()?.let { nz(it) } ?: s
        }
        val parts = s.split("/")
        if (parts.size != 2) return s
        val num = parts[0].trim().toDoubleOrNull() ?: return s
        val den = parts[1].trim().toDoubleOrNull() ?: return s
        if (den == 0.0) return null
        if (num == 0.0) return null
        val v = num / den
        return if (abs(v) < 1.0 && abs(v) > 0.0) {
            // Belichtungszeit als Bruch darstellen
            val inv = (1.0 / v).roundToInt()
            if (inv in 1..8000) "1/$inv s" else nz(v)
        } else nz(v)
    }

    fun number(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return rational(raw)
    }

    private fun nz(v: Double): String {
        val t = v.toLong()
        return if (abs(v - t) < 0.0005) t.toString() else String.format(Locale.GERMAN, "%.1f", v)
    }

    /** EXIF-Tags wie "ExposureTime"/"FNumber" kommen je nach Gerät als Text; nicht-interpretierbare Werte ausblenden. */
    fun cleanExif(v: String?): String? {
        if (v.isNullOrBlank()) return null
        val s = v.trim()
        if (s.equals("null", true) || s == "0/0" || s == "[]") return null
        return s
    }
}
