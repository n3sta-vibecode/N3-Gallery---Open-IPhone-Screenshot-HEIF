package com.n3vibecode.gallery.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/**
 * Offline-Ortsbestimmung für Fotos – **ohne Internet**.
 *
 * Die App hat bewusst keine Internet-Berechtigung. Damit ein Foto trotzdem einen Ortsnamen
 * bekommt („Luzern, Schweiz“ statt „47.0502, 8.3093“) liegen zwei kleine Datendateien im
 * App-Paket (zusammen ~ 1,2 MB):
 *
 *  • `assets/map/places.dat.gz` – 46 000 Orte ab 5 000 Einwohnern (GeoNames, CC BY 4.0).
 *  • `assets/map/world.dat.gz`  – Landflächen (Natural Earth 1:50m, gemeinfrei) für die Karte.
 *
 * Geladen wird mit zwei Gittern (0,5° und 1°), damit die Suche nach dem nächsten Ort in
 * wenigen Millisekunden fertig ist – auch wenn die Karte 46 000 Orte enthält.
 */
object GeoIndex {

    /** Ein Ort aus der Ortsliste. */
    data class Place(
        val name: String,
        val country: String,
        val lat: Double,
        val lon: Double,
        val grade: Int
    )

    /** Ergebnis der Ortssuche zu einer Position. */
    data class Location(
        val place: Place,
        val distanceKm: Double,
        /** Größerer Ort in der Nähe – ergibt „Adligenswil · Luzern, Schweiz“. */
        val region: Place?,
        val regionKm: Double
    ) {
        fun label(): String {
            val regionUsable = region != null && region.name != place.name &&
                    region.name.isNotEmpty() && regionKm <= distanceKm * 12 + 10
            val base = if (regionUsable) "${place.name} · ${region!!.name}" else place.name
            return if (place.country.isNotEmpty() && place.country != place.name) {
                "$base, ${place.country}"
            } else base
        }

        fun coords(): String = String.format(java.util.Locale.US, "%.5f, %.5f", place.lat, place.lon)
    }

    // ------------------------------------------------------------------ Daten

    private class Data(
        val count: Int,
        val lon: DoubleArray,
        val lat: DoubleArray,
        val grade: ByteArray,
        val country: Array<String>,
        val name: Array<String>,
        val coarse: Array<IntArray?>,
        val fine: Array<IntArray?>
    )

    @Volatile private var data: Data? = null
    @Volatile private var rings: List<FloatArray>? = null

    /** Sind die Landflächen schon im Speicher? (Sonst zeichnet die Karte ihr Bild) */
    fun ringsLoaded(): Boolean = rings != null
    private val lock = Any()

    fun ready(): Boolean = data != null

    // ------------------------------------------------------------------ Ortsliste laden

    /**
     * Ortsliste schon im Hintergrund einlesen – z. B. beim Start der App, damit die Karte und
     * die Suchbeschreibungen später sofort einen Ortsnamen haben.
     */
    fun warmUp(ctx: Context) {
        if (data != null) return
        Thread({
            load(ctx.applicationContext)
        }, "n3-geo-warm").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /**
     * Wartet (höchstens [maxMs]) bis die Ortsliste im Speicher ist.
     *
     * Wozu: Der Suchindex schreibt Ortsnamen in den Foto-Text. Läuft er los, bevor die
     * Ortsliste da ist, fehlen sie – und „Luzern“ findet dann nur noch die klassische Suche.
     * Aufruf nur aus einem Hintergrund-Thread.
     */
    fun awaitReady(maxMs: Long): Boolean {
        if (data != null) return true
        val until = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < until) {
            if (data != null) return true
            runCatching { Thread.sleep(50) }
        }
        return data != null
    }

    /** Lädt die Ortsliste, falls noch nicht geschehen (im Hintergrund aufrufen). */
    fun load(ctx: Context): Boolean {
        if (data != null) return true
        synchronized(lock) {
            if (data != null) return true
            val loaded = try {
                readPlaces(ctx)
            } catch (_: Throwable) {
                null
            }
            data = loaded
            return loaded != null
        }
    }

    private fun readPlaces(ctx: Context): Data {
        DataInputStream(GZIPInputStream(ctx.assets.open("map/places.dat.gz"), 1 shl 16).buffered(1 shl 16)).use { input ->
            require(readIntLe(input) == MAGIC_PLACES) { "places.dat.gz: falsche Kennung" }

            // Länder: "CC\nDeutsch\nEnglish\nFrançais\nItaliano"
            val countryCount = readIntLe(input)
            val countryNames = Array(countryCount) { "" }
            repeat(countryCount) {
                val parts = readString(input).split('\n')
                countryNames[it] = parts.getOrElse(1) { parts[0] }
            }

            val n = readIntLe(input)
            val lon = DoubleArray(n)
            val lat = DoubleArray(n)
            val grade = ByteArray(n)
            val country = arrayOfNulls<String>(n)
            val nameIdx = IntArray(n)
            for (i in 0 until n) {
                lon[i] = readIntLe(input) / 1e5
                lat[i] = readIntLe(input) / 1e5
                grade[i] = input.readByte()
                val ci = input.readUnsignedByte()
                country[i] = countryNames.getOrElse(ci) { "" }
                // little endian (DataInputStream liest big endian)
                nameIdx[i] = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            }

            val nameCount = readIntLe(input)
            val names = Array(nameCount) { "" }
            for (i in 0 until nameCount) names[i] = readString(input)

            // Gitter aufbauen: grob (1°) für „nächster größerer Ort“, fein (0,5°) für die Nähe.
            val coarse = arrayOfNulls<IntArray>(COARSE_W * COARSE_H)
            val fine = arrayOfNulls<IntArray>(FINE_W * FINE_H)
            val coarseLists = HashMap<Int, MutableList<Int>>()
            val fineLists = HashMap<Int, MutableList<Int>>()
            for (i in 0 until n) {
                coarseLists.getOrPut(coarseIndex(lon[i], lat[i])) { ArrayList(8) }.add(i)
                fineLists.getOrPut(fineIndex(lon[i], lat[i])) { ArrayList(4) }.add(i)
            }
            val byGrade = Comparator<Int> { a, b -> grade[b].compareTo(grade[a]) }
            coarseLists.forEach { (k, list) -> coarse[k] = list.sortedWith(byGrade).toIntArray() }
            fineLists.forEach { (k, list) -> fine[k] = list.sortedWith(byGrade).toIntArray() }

            return Data(
                count = n, lon = lon, lat = lat, grade = grade,
                country = Array(n) { country[it] ?: "" },
                name = Array(n) { names.getOrElse(nameIdx[it]) { "" } },
                coarse = coarse, fine = fine
            )
        }
    }

    // DataInputStream liest big endian – die Datei ist little endian (wie auf Android üblich).
    private fun readIntLe(input: DataInputStream): Int =
        (input.readUnsignedByte()) or
                (input.readUnsignedByte() shl 8) or
                (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 24)

    private fun readString(input: DataInputStream): String {
        // varint-Länge (7 Bit je Byte)
        var len = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) break
        }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ Suche

    private const val COARSE_W = 360
    private const val COARSE_H = 180
    private const val FINE_W = 720
    private const val FINE_H = 360
    private const val FINE_STEP = 0.5

    private fun coarseIndex(lon: Double, lat: Double): Int {
        val x = ((lon + 180.0)).toInt().coerceIn(0, COARSE_W - 1)
        val y = ((90.0 - lat)).toInt().coerceIn(0, COARSE_H - 1)
        return y * COARSE_W + x
    }

    private fun fineIndex(lon: Double, lat: Double): Int {
        val x = ((lon + 180.0) / FINE_STEP).toInt().coerceIn(0, FINE_W - 1)
        val y = ((90.0 - lat) / FINE_STEP).toInt().coerceIn(0, FINE_H - 1)
        return y * FINE_W + x
    }

    /** Entfernung in km (Haversine). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * 6371.0 * Math.asin(minOf(1.0, Math.sqrt(a)))
    }

    /**
     * Nächster Ort zu (lat, lon).
     *
     * [maxKm] begrenzt die Suche: Fotos mitten im Ozean oder mit kaputten GPS-Daten sollen
     * lieber keine Ortsangabe bekommen als eine falsche.
     */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 150.0): Location? {
        val d = data ?: return null
        if (d.count == 0 || lat.isNaN() || lon.isNaN()) return null

        var bestIdx = -1
        var bestKm = Double.MAX_VALUE

        fun consider(i: Int) {
            val km = distanceKm(lat, lon, d.lat[i], d.lon[i])
            if (km < bestKm - 0.05 ||
                (km < bestKm + 0.05 && bestIdx >= 0 && d.grade[i] > d.grade[bestIdx])
            ) {
                bestKm = km
                bestIdx = i
            }
        }

        // 1) Feines Gitter (0,5° ≈ 55 km): Nachbarzellen mit wachsendem Radius
        for (ring in 0..3) {
            for (cell in cellsAround(lon, lat, FINE_W, FINE_H, ring)) {
                d.fine.getOrNull(cell)?.forEach { consider(it) }
            }
            // Gefunden und der Treffer liegt sicher innerhalb der durchsuchten Fläche? Fertig.
            if (bestIdx >= 0 && bestKm <= ring * FINE_STEP * 111.0) break
        }

        // 2) Grobes Gitter (1°): nächster *größerer* Ort (ab 20 000 Einwohnern)
        var regionIdx = -1
        var regionKm = Double.MAX_VALUE
        for (ring in 0..4) {
            for (cell in cellsAround(lon, lat, COARSE_W, COARSE_H, ring)) {
                val arr = d.coarse.getOrNull(cell) ?: continue
                for (i in arr) {
                    if (d.grade[i] < 2) continue
                    val km = distanceKm(lat, lon, d.lat[i], d.lon[i])
                    if (km < regionKm) {
                        regionKm = km
                        regionIdx = i
                    }
                }
            }
            if (regionIdx >= 0 && regionKm < ring * 111.0) break
        }

        if (bestIdx < 0) {
            return if (regionIdx >= 0) Location(place(d, regionIdx), regionKm, null, Double.MAX_VALUE) else null
        }
        if (bestKm > maxKm) return null

        val region = if (regionIdx >= 0 && regionIdx != bestIdx && regionKm <= maxKm) place(d, regionIdx) else null
        return Location(place(d, bestIdx), bestKm, region, regionKm)
    }

    private fun place(d: Data, i: Int) = Place(
        name = d.name.getOrElse(i) { "" },
        country = d.country.getOrElse(i) { "" },
        lat = d.lat[i],
        lon = d.lon[i],
        grade = d.grade[i].toInt()
    )

    /** Zellnummern im Ring [ring] um die Position (Ring 0 = nur die eigene Zelle). */
    private fun cellsAround(lon: Double, lat: Double, w: Int, h: Int, ring: Int): IntArray {
        val step = 360.0 / w
        val cx = ((lon + 180.0) / step).toInt()
        val cy = ((90.0 - lat) / step).toInt()
        if (ring == 0) {
            val x = ((cx % w) + w) % w
            val y = cy.coerceIn(0, h - 1)
            return intArrayOf(y * w + x)
        }
        val out = ArrayList<Int>((2 * ring + 1) * (2 * ring + 1))
        for (y in cy - ring..cy + ring) {
            if (y < 0 || y >= h) continue
            for (x in cx - ring..cx + ring) {
                // nur der Rand des Rings – die inneren Zellen waren schon dran
                if (kotlin.math.abs(x - cx) != ring && kotlin.math.abs(y - cy) != ring) continue
                val xw = ((x % w) + w) % w
                out.add(y * w + xw)
            }
        }
        return out.toIntArray()
    }

    // ------------------------------------------------------------------ Weltkarte

    /** Landflächen als Ringe (lon/lat in Grad). Wird einmal gelesen und bleibt im Speicher. */
    fun rings(ctx: Context): List<FloatArray> {
        rings?.let { return it }
        synchronized(lock) {
            rings?.let { return it }
            val list = try {
                val out = ArrayList<FloatArray>(1024)
                DataInputStream(GZIPInputStream(ctx.assets.open("map/world.dat.gz"), 1 shl 16).buffered(1 shl 16)).use { input ->
                    require(readIntLe(input) == MAGIC_WORLD) { "world.dat.gz: falsche Kennung" }
                    val count = readIntLe(input)
                    repeat(count) {
                        val points = readIntLe(input)
                        val arr = FloatArray(points * 2)
                        for (i in 0 until points) {
                            arr[i * 2] = readIntLe(input) / 1e5f
                            arr[i * 2 + 1] = readIntLe(input) / 1e5f
                        }
                        out.add(arr)
                    }
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
            rings = list
            return list
        }
    }

    /**
     * Weltkarte als Bitmap im Verhältnis 2:1 (lon/lat linear – „Plate Carrée“). Dadurch ist
     * die Umrechnung Koordinate → Bildpunkt eine Multiplikation, und die Marker sitzen exakt.
     * Das Ergebnis wird als PNG zwischengespeichert.
     */
    fun mapBitmap(ctx: Context): Bitmap? {
        val dir = java.io.File(ctx.cacheDir, "mapcache")
        val file = java.io.File(dir, "world_v2.png")
        if (file.exists()) {
            runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
                .getOrNull()?.let { return it }
            runCatching { file.delete() }
        }
        val bmp = runCatching { renderWorld(ctx) }.getOrNull() ?: return null
        runCatching {
            dir.mkdirs()
            val tmp = java.io.File(dir, "world_v2.tmp")
            java.io.FileOutputStream(tmp).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (!tmp.renameTo(file)) tmp.delete()
        }
        return bmp
    }

    /**
     * Ersatz für das Zwischenbild: die Karte einfarbig umgefärbt (dunkles Design, Vorschau).
     * Wird nicht gespeichert, sondern nur gezeichnet.
     */
    fun tinted(ctx: Context, landColor: Int, seaColor: Int): Bitmap? {
        val src = mapBitmap(ctx) ?: return null
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(seaColor)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(landColor, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Karte in einer bestimmten Größe (z. B. kleine Vorschau in der Liste). */
    fun scaled(ctx: Context, width: Int, height: Int): Bitmap? {
        val src = mapBitmap(ctx) ?: return null
        if (src.width == width && src.height == height) return src
        val m = Matrix().apply { setScale(width.toFloat() / src.width, height.toFloat() / src.height) }
        return runCatching { Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) }.getOrNull()
    }

    private fun renderWorld(ctx: Context): Bitmap {
        val w = 2048
        val h = 1024
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(SEA)
        val path = android.graphics.Path()
        path.fillType = android.graphics.Path.FillType.EVEN_ODD
        val list = rings(ctx)
        for (r in list) {
            if (r.size < 8) continue
            path.rewind()
            path.moveTo(xOf(r[0], w), yOf(r[1], h))
            var i = 2
            while (i < r.size) {
                path.lineTo(xOf(r[i], w), yOf(r[i + 1], h))
                i += 2
            }
            path.close()
        }
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = LAND
        }
        canvas.drawPath(path, fill)
        // Küstenlinie mitziehen, damit Inseln und Seen sichtbar bleiben
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.3f
            color = COAST
        }
        canvas.drawPath(path, stroke)
        return bmp
    }

    fun xOf(lon: Float, w: Int): Float = (lon + 180f) / 360f * w
    fun yOf(lat: Float, h: Int): Float = (90f - lat) / 180f * h

    // ------------------------------------------------------------------ Farben

    val SEA: Int = Color.rgb(226, 237, 246)
    val LAND: Int = Color.rgb(197, 212, 226)
    val COAST: Int = Color.rgb(170, 191, 210)

    private const val MAGIC_PLACES = 0x3150334e // 'N3P1'
    private const val MAGIC_WORLD = 0x3157334e  // 'N3W1'
}
