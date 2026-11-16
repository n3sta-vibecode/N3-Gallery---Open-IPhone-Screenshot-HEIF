package com.n3vibecode.gallery.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.n3vibecode.gallery.GalleryApp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speichert die GPS-Position jedes Fotos – damit die Karte **sofort** aufgeht und nicht
 * erst alle Dateien gelesen werden müssen.
 *
 * Woher kommen die Daten?
 *  1. Android-Medienindex: viele Geräte speichern Breiten-/Längengrad direkt mit
 *     (`MediaStore.Images.Media.LATITUDE/LONGITUDE`) – das ist ein einziger Abfragedurchlauf
 *     ohne eine einzige Datei zu öffnen.
 *  2. EXIF der Datei (für alle, die dort nichts eingetragen hat – RAW, SAF-Ordner, HEIC …).
 *
 * Das Ergebnis landet in einer kleinen SQLite-Datei im App-Speicher. Beim nächsten Öffnen
 * ist die Karte damit ohne Wartezeit da; neu hinzugekommene Fotos werden nachgetragen.
 */
object GeoStore {

    /** Ein Foto mit Position. */
    data class Point(val uri: String, val lat: Double, val lon: Double, val time: Long)

    private const val DB = "n3_geo.db"
    private const val TABLE = "geo"

    @Volatile private var helper: Helper? = null
    @Volatile private var scanning = false

    /** Zwischenspeicher im RAM (uri → Position), damit wiederholte Kartenaufrufe nichts kosten. */
    private val memory = ConcurrentHashMap<String, Pair<Double, Double>>()

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (uri TEXT PRIMARY KEY, lat REAL, lon REAL, taken INTEGER, ver INTEGER)"
            )
            db.execSQL("CREATE INDEX idx_geo_taken ON $TABLE (taken)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Format geändert? Einfach neu aufbauen – die Daten kommen aus den Dateien wieder.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private fun db(): SQLiteDatabase? = try {
        val h = helper ?: synchronized(this) {
            helper ?: Helper(GalleryApp.instance).also { helper = it }
        }
        h.writableDb
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ Lesen

    /** Alle bekannten Positionen (uri → lat/lon). Sofort verfügbar, ohne Datei-Zugriff. */
    fun cached(): List<Point> {
        val out = ArrayList<Point>()
        val seen = HashSet<String>()
        val byUri = DataHub.byUri()
        // 1) RAM
        memory.forEach { (uri, latLon) ->
            val item = byUri[uri]
            out.add(Point(uri, latLon.first, latLon.second, item?.time ?: 0L))
            seen.add(uri)
        }
        // 2) Datenbank
        val d = db() ?: return out
        try {
            d.rawQuery("SELECT uri, lat, lon, taken FROM $TABLE", null).use { c ->
                while (c.moveToNext()) {
                    val uri = c.getString(0) ?: continue
                    if (seen.contains(uri)) continue
                    val item = byUri[uri]
                    out.add(
                        Point(
                            uri,
                            c.getDouble(1),
                            c.getDouble(2),
                            item?.time ?: c.getLong(3)
                        )
                    )
                    seen.add(uri)
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** Position eines einzelnen Fotos – erst Zwischenspeicher, sonst Datei lesen. */
    fun pointFor(item: MediaItem): Pair<Double, Double>? {
        memory[item.uri]?.let { return it }
        val d = db()
        if (d != null) {
            try {
                d.rawQuery("SELECT lat, lon FROM $TABLE WHERE uri = ?", arrayOf(item.uri)).use { c ->
                    if (c.moveToFirst()) {
                        val p = c.getDouble(0) to c.getDouble(1)
                        memory[item.uri] = p
                        return p
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** Position direkt aus den Metadaten lesen (ohne Datenbank). */
    fun readFromMetadata(ctx: Context, item: MediaItem): Pair<Double, Double>? {
        // 1) Medienindex (schnell, kein Datei-Zugriff)
        if (item.uri.startsWith("content://media/")) {
            runCatching { mediaStorePosition(ctx, item) }.getOrNull()?.let { return it }
        }
        // 2) EXIF der Datei
        return runCatching {
            ctx.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                val exif = ExifInterface(input)
                val latLong = exif.latLong
                if (latLong != null && (latLong[0] != 0.0 || latLong[1] != 0.0)) {
                    latLong[0] to latLong[1]
                } else null
            }
        }.getOrNull()
    }

    private fun mediaStorePosition(ctx: Context, item: MediaItem): Pair<Double, Double>? {
        val uri = Uri.parse(item.uri)
        val id = item.uri.substringAfterLast('/').toLongOrNull() ?: return null
        val base = if (item.isVideoFile) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.LATITUDE, MediaStore.Images.Media.LONGITUDE)
        val target = Uri.parse(base.toString() + "/" + id)
        ctx.contentResolver.query(target, projection, null, null, null)?.use { c: Cursor ->
            if (c.moveToFirst()) {
                if (!c.isNull(0) && !c.isNull(1)) {
                    val lat = c.getDouble(0)
                    val lon = c.getDouble(1)
                    if (lat != 0.0 || lon != 0.0) return lat to lon
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Schreiben

    fun put(uri: String, lat: Double, lon: Double, taken: Long) {
        memory[uri] = lat to lon
        val d = db() ?: return
        try {
            d.execSQL(
                "INSERT OR REPLACE INTO $TABLE (uri, lat, lon, taken, ver) VALUES (?,?,?,?,1)",
                arrayOf(uri, lat, lon, taken)
            )
        } catch (_: Throwable) {
        }
    }

    /** Position vergessen (z. B. wenn ein Foto gelöscht oder bearbeitet wurde). */
    fun forget(uri: String) {
        memory.remove(uri)
        try {
            db()?.execSQL("DELETE FROM $TABLE WHERE uri = ?", arrayOf(uri))
        } catch (_: Throwable) {
        }
    }

    /** Alles vergessen (nach einem Neuscan, wenn Dateien ersetzt wurden). */
    fun clear() {
        memory.clear()
        try {
            db()?.execSQL("DELETE FROM $TABLE")
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ Nachlesen

    /**
     * Positionen für alle Fotos ermitteln, die noch keine haben – im Hintergrund, mit
     * mehreren Threads. [onProgress] meldet (fertig, gesamt); [onDone] kommt am Ende.
     *
     * Läuft nie doppelt: während gesucht wird, gibt ein zweiter Aufruf sofort zurück.
     */
    fun scanMissing(
        ctx: Context,
        items: List<MediaItem>,
        threads: Int = 3,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (scanning) return
        scanning = true
        val app = ctx.applicationContext
        val todo = items.filter { !memory.containsKey(it.uri) }
        if (todo.isEmpty()) {
            scanning = false
            onDone?.invoke()
            return
        }
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val live = AtomicInteger(threads)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val pool = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "n3-geo").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
        }
        repeat(threads) {
            pool.execute {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= todo.size) break
                    val item = todo[i]
                    runCatching {
                        // Schon in der Datenbank? Dann nur in den RAM übernehmen.
                        val known = pointFor(item)
                        if (known == null) {
                            readFromMetadata(app, item)?.let { (lat, lon) ->
                                put(item.uri, lat, lon, item.time)
                            }
                        }
                    }
                    val finished = done.incrementAndGet()
                    if (finished % 25 == 0 || finished == todo.size) {
                        main.post { onProgress?.invoke(finished, todo.size) }
                    }
                }
                if (live.decrementAndGet() == 0) {
                    pool.shutdown()
                    scanning = false
                    main.post { onDone?.invoke() }
                }
            }
        }
    }

    fun isScanning(): Boolean = scanning
}
