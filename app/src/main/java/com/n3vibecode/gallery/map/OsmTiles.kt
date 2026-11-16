package com.n3vibecode.gallery.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * **OpenStreetMap-Kacheln** für die Karte – optional zuschaltbar.
 *
 * Warum optional? Diese App ist bewusst offline: Landflächen und Ortsnamen liegen im
 * App-Paket. OpenStreetMap liefert aber echte Straßen und Ortsbezeichnungen, und das geht
 * nur über das Netz. Deshalb gilt:
 *
 *  • **Standard: aus.** Die Karte zeichnet wie bisher die Landflächen aus dem App-Paket.
 *  • **Auf Wunsch: an.** Ein Knopf auf der Karte schaltet OSM zu; die App lädt dann Kacheln
 *    im Hintergrund, merkt sie auf dem Gerät (Ordner `cache/osm`) und zeigt sie beim nächsten
 *    Mal sofort – ohne erneutes Laden.
 *
 * Regeln des OSM-Projekts (Tile Usage Policy) werden eingehalten: Kennung im User-Agent,
 * höchstens 4 gleichzeitige Anfragen, Kacheln werden zwischengespeichert statt neu geladen.
 * Die Nennung „© OpenStreetMap-Mitwirkende“ steht dauerhaft auf der Karte.
 */
object OsmTiles {

    private const val TAG = "N3Map"

    /** Bei OpenStreetMap zugelassene Zoomstufen; 18 ist die Grenze der Standardkacheln. */
    const val MIN_Z = 2
    const val MAX_Z = 18

    /** Kachelgröße in Pixeln (OSM-Standard). */
    const val TILE = 256

    private const val UA = "N3Gallery/1.34 (Android; Offline-Galerie) – Kartenansicht auf Wunsch"

    private val memory = ConcurrentHashMap<String, Bitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Höchstens 4 parallele Anfragen – so verlangt es die OSM-Nutzungsregel. */
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "n3-osm").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private val pending = AtomicInteger(0)

    fun cacheDir(ctx: Context): File =
        File(ctx.cacheDir, "osm").apply { if (!exists()) mkdirs() }

    /** Längengrad → Kachelspalte (Web-Mercator). */
    fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Breitengrad → Kachelzeile (Web-Mercator). */
    fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(Math.tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    /** Pixelposition (Web-Mercator-Weltkoordinate) eines Punktes auf Zoomstufe z. */
    fun worldPx(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val n = (1 shl z) * TILE.toDouble()
        val x = (lon + 180.0) / 360.0 * n
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(Math.tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n
        return x to y
    }

    /** Zoomstufe, die zu einer Auflösung von px pro Grad passt. */
    fun zoomFor(pxPerDeg: Float): Int {
        // Weltbreite = 360° → Pixel = pxPerDeg * 360; Kachel-Welt = 256 * 2^z
        if (pxPerDeg <= 0f) return MIN_Z
        val world = pxPerDeg.toDouble() * 360.0
        val z = ln(world / TILE) / ln(2.0)
        return z.roundToInt().coerceIn(MIN_Z, MAX_Z)
    }

    /** Kachel aus dem Speicher (oder `null`). */
    fun cached(ctx: Context, z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory[key]?.let { return it }
        val f = File(cacheDir(ctx), "$z-$x-$y.png")
        if (!f.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: run {
            f.delete()
            return null
        }
        memory[key] = bmp
        return bmp
    }

    /**
     * Kachel laden, falls nicht vorhanden. [onLoaded] kommt im Hintergrund-Thread.
     * Doppelanfragen für dieselbe Kachel werden zusammengefasst.
     */
    fun request(ctx: Context, z: Int, x: Int, y: Int, onLoaded: (() -> Unit)? = null) {
        if (z < MIN_Z || z > MAX_Z) return
        val key = "$z/$x/$y"
        if (memory.containsKey(key) || missing.contains(key)) return
        if (File(cacheDir(ctx), "$z-$x-$y.png").exists()) return
        val app = ctx.applicationContext
        pending.incrementAndGet()
        pool.execute {
            try {
                val file = File(cacheDir(app), "$z-$x-$y.png")
                if (file.exists()) return@execute
                val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "image/png")
                }
                try {
                    val code = conn.responseCode
                    if (code != 200) {
                        // 404 = außerhalb der Welt; nicht immer wieder fragen
                        if (code == 404) missing.add(key)
                        return@execute
                    }
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size < 100) return@execute
                    val tmp = File(cacheDir(app), "$z-$x-$y.png.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(file)) tmp.delete()
                    onLoaded?.invoke()
                } finally {
                    runCatching { conn.disconnect() }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "OSM-Kachel $key: ${t.message}")
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun isLoading(): Boolean = pending.get() > 0

    /** Wie viele Kacheln liegen schon auf dem Gerät (für „offline verfügbar“)? */
    fun cachedCount(ctx: Context): Int =
        cacheDir(ctx).listFiles()?.count { it.isFile && it.name.endsWith(".png") } ?: 0

    /** Zwischenspeicher leeren. */
    fun clear(ctx: Context) {
        memory.clear()
        cacheDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Adresse für „in OpenStreetMap öffnen“ (Browser/andere App) – ohne Netzzugriff der App. */
    fun webUrl(lat: Double, lon: Double, z: Int = 16): String {
        val n = 1 shl z
        val x = tileX(lon, z)
        val y = tileY(lat, z)
        return "https://www.openstreetmap.org/#map=$z/$lat/$lon".also {
            if (abs(x) > n || abs(y) > n) return@also
        }
    }
}
