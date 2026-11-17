package com.n3vibecode.gallery.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.GeoStore
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.map.GeoIndex
import com.n3vibecode.gallery.map.MapView
import com.n3vibecode.gallery.util.Fmt

/**
 * „Karte“ – zeigt, **wo** die Fotos entstanden sind.
 *
 * Bewusst ohne Kartendienst und ohne Internet (die App hat keine Internet-Berechtigung):
 * Landflächen und Ortsnamen stecken im App-Paket (siehe [GeoIndex]).
 *
 * Die Positionen kommen aus dem Zwischenspeicher ([GeoStore]) – dadurch ist die Karte beim
 * Öffnen sofort da. Für Fotos ohne gespeicherte Position werden sie im Hintergrund aus
 * Medienindex/EXIF nachgelesen und anschließend ergänzt.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var scanBox: View
    private lateinit var tvScan: TextView
    private lateinit var placeCard: View
    private lateinit var tvPlaceTitle: TextView
    private lateinit var tvPlaceSub: TextView
    private lateinit var btnOpenPlace: View
    private lateinit var emptyBox: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySub: TextView

    /** Alle Fotos der aktuellen Liste (Position wird nachgelesen). */
    private var scope: List<MediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        scanBox = findViewById(R.id.scanBox)
        tvScan = findViewById(R.id.tvScan)
        placeCard = findViewById(R.id.placeCard)
        tvPlaceTitle = findViewById(R.id.tvPlaceTitle)
        tvPlaceSub = findViewById(R.id.tvPlaceSub)
        btnOpenPlace = findViewById(R.id.btnOpenPlace)
        emptyBox = findViewById(R.id.emptyBox)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptySub = findViewById(R.id.tvEmptySub)

        osmNote = findViewById(R.id.tvOsmNote)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Rand: oben Platz für die Statusleiste, unten für die Navigationsgeste
        val root = findViewById<View>(R.id.mapRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Kartenstil: gezeichnete Karte (offline) oder OpenStreetMap (lädt Kacheln)
        val btnProvider = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProvider)
        val prefs = getSharedPreferences("n3_map", MODE_PRIVATE)
        mapView.useOsm = prefs.getBoolean("osm", false)
        mapView.onProviderChanged = { osm ->
            btnProvider.text = getString(if (osm) R.string.map_osm_off else R.string.map_osm_on)
        }
        btnProvider.text = getString(
            if (mapView.useOsm) R.string.map_osm_off else R.string.map_osm_on
        )
        btnProvider.setOnClickListener {
            val next = !mapView.useOsm
            mapView.useOsm = next
            prefs.edit().putBoolean("osm", next).apply()
            if (next) {
                Toast.makeText(this, R.string.map_osm_hint, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<View>(R.id.btnZoomIn).setOnClickListener { mapView.zoomBy(1.7f) }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { mapView.zoomBy(1f / 1.7f) }
        findViewById<View>(R.id.btnFit).setOnClickListener {
            hidePlaceCard()
            mapView.fitToMarkers()
        }

        mapView.onMarkerClick = { marker -> showPlace(marker) }

        scope = ViewState.viewList.ifEmpty { DataHub.all }
        // Von der Metadaten-Seite: Karte direkt auf diese Position stellen
        focusLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        focusLon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)

        // Sofort mit dem zeichnen, was schon bekannt ist (Positionen aus dem Zwischenspeicher),
        // parallel Ortsliste und Kartenbild laden – beides zusammen ~ 1 MB, also schnell.
        rebuildMarkers()
        Thread({
            val ok = runCatching { GeoIndex.load(applicationContext) }.getOrDefault(false)
            runOnUiThread {
                // Mit Ortsnamen neu beschriften
                if (ok) rebuildMarkers()
                startScan()
            }
        }, "n3-map-load").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }.start()

        toolbar.subtitle = getString(R.string.map_subtitle)
        watchOsm()
    }

    /**
     * Prüft regelmäßig, ob die OSM-Kacheln ankommen, und sagt es, wenn nicht.
     *
     * Der Nutzer hat „Karte lädt nicht“ gemeldet – ohne diese Anzeige blieb nur eine leere
     * helle Fläche, ohne Grund und ohne Handlungsmöglichkeit. Jetzt steht dort entweder
     * „lädt …“, ein konkreter Fehler (HTTP-Code, keine Verbindung) oder der Hinweis, dass
     * die App offline zeichnet.
     */
    private fun watchOsm() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (!mapView.useOsm) {
                    osmNote.visibility = View.GONE
                } else {
                    val err = com.n3vibecode.gallery.map.OsmTiles.lastError
                    val loading = com.n3vibecode.gallery.map.OsmTiles.isLoading()
                    when {
                        err.isNotBlank() -> {
                            osmNote.visibility = View.VISIBLE
                            osmNote.text = err
                        }
                        loading && !com.n3vibecode.gallery.map.OsmTiles.hasAnyLoaded() -> {
                            osmNote.visibility = View.VISIBLE
                            osmNote.text = getString(R.string.map_osm_loading)
                        }
                        !com.n3vibecode.gallery.map.OsmTiles.hasAnyLoaded() -> {
                            osmNote.visibility = View.VISIBLE
                            osmNote.text = getString(R.string.map_osm_offline_hint)
                        }
                        else -> osmNote.visibility = View.GONE
                    }
                }
                handler.postDelayed(this, 1200)
            }
        }
        handler.postDelayed(tick, 800)
    }

    /** Zielposition, wenn die Karte aus den Metadaten eines Fotos geöffnet wurde. */
    private lateinit var osmNote: android.widget.TextView

    private var focusLat = Double.NaN
    private var focusLon = Double.NaN

    /** Liest fehlende Positionen im Hintergrund nach (Medienindex + EXIF). */
    private fun startScan() {
        val missing = scope.count { GeoStore.pointFor(it) == null }
        if (missing == 0) {
            scanBox.visibility = View.GONE
            return
        }
        scanBox.visibility = View.VISIBLE
        tvScan.text = getString(R.string.map_scanning_count, 0, missing)
        GeoStore.scanMissing(
            this,
            scope,
            onProgress = { done, total ->
                scanBox.visibility = View.VISIBLE
                tvScan.text = getString(R.string.map_scanning_count, done, total)
                // Zwischendurch schon neue Punkte zeigen
                if (done % 100 == 0) rebuildMarkers()
            },
            onDone = {
                scanBox.visibility = View.GONE
                rebuildMarkers()
            }
        )
    }

    /**
     * Marker aus den bekannten Positionen bauen. Bei sehr vielen Fotos werden Fotos am
     * selben Ort zu einem Punkt zusammengefasst (sonst wären es zehntausende Marker).
     */
    private fun rebuildMarkers() {
        val points = GeoStore.cached()
        if (points.isEmpty()) {
            mapView.setMarkers(emptyList())
            emptyBox.visibility = View.VISIBLE
            tvEmptyTitle.text = getString(R.string.map_empty_title)
            tvEmptySub.text = getString(R.string.map_empty_hint)
            return
        }
        emptyBox.visibility = View.GONE

        // Position → Fotos (auf 0,002° genau ≈ 200 m zusammengefasst)
        val groups = HashMap<Long, MutableList<MediaItem>>()
        val firstPoint = HashMap<Long, GeoStore.Point>()
        val byUri = DataHub.byUri()
        // Nur Fotos aus der aktuellen Auswahl zeigen (z. B. nur ein Album), sonst alle
        val limitToScope = scope !== DataHub.all
        val scopeUris = if (limitToScope) HashSet<String>(scope.size * 2).apply {
            scope.forEach { add(it.uri) }
        } else null
        points.forEach { p ->
            if (scopeUris != null && !scopeUris.contains(p.uri)) return@forEach
            val item = byUri[p.uri] ?: return@forEach
            val key = ((Math.round(p.lat * 500) shl 32) or (Math.round(p.lon * 500) and 0xFFFFFFFFL))
            groups.getOrPut(key) { ArrayList(2) }.add(item)
            firstPoint.putIfAbsent(key, p)
        }

        val markers = ArrayList<MapView.Marker>(groups.size)
        groups.forEach { (key, items) ->
            // Neuestes Foto zuerst (die Liste ist nach Datum sortiert)
            val first = items.maxByOrNull { it.time } ?: items.first()
            val pos = firstPoint[key] ?: return@forEach
            val loc = runCatching { GeoIndex.nearest(pos.lat, pos.lon) }.getOrNull()
            markers.add(
                MapView.Marker(
                    id = first.uri,
                    lat = pos.lat,
                    lon = pos.lon,
                    count = items.size,
                    label = loc?.place?.name ?: "",
                    payload = items
                )
            )
        }
        mapView.setMarkers(markers)
        if (markers.isEmpty()) {
            emptyBox.visibility = View.VISIBLE
            tvEmptyTitle.text = getString(R.string.map_empty_title)
            tvEmptySub.text = getString(R.string.map_empty_hint)
            return
        }
        if (!fitted) {
            fitted = true
            if (!focusLat.isNaN() && !focusLon.isNaN()) {
                mapView.post { mapView.showAt(focusLat, focusLon, 12f) }
            } else {
                mapView.post { mapView.fitToMarkers() }
            }
        }
    }

    private var fitted = false

    private fun showPlace(marker: MapView.Marker) {
        // Die Fotos dieses Ortes liegen im Marker (payload) – bei einer Zusammenfassung alle.
        @Suppress("UNCHECKED_CAST")
        val items = (marker.payload as? List<MediaItem>) ?: emptyList()
        if (items.isEmpty()) return
        val loc = runCatching { GeoIndex.nearest(marker.lat, marker.lon) }.getOrNull()
        tvPlaceTitle.text = loc?.label() ?: String.format(java.util.Locale.US, "%.4f, %.4f", marker.lat, marker.lon)
        val whenText = items.maxOfOrNull { it.time }?.let { t ->
            getString(R.string.map_photos_from, Fmt.dateTime(t))
        }.orEmpty()
        val coords = String.format(java.util.Locale.US, "%.5f, %.5f", marker.lat, marker.lon)
        tvPlaceSub.text = buildString {
            append(getString(R.string.count_files, items.size))
            if (whenText.isNotEmpty()) append(" · ").append(whenText)
            append(" · ").append(coords)
        }
        placeCard.visibility = View.VISIBLE
        // Neueste zuerst
        val sorted = items.sortedByDescending { it.time }
        btnOpenPlace.setOnClickListener { openPhotos(sorted, loc?.label()) }
        // Karte etwas zentrieren, damit die Karte nicht hinter der Auswahl verschwindet
        mapView.showAt(marker.lat, marker.lon, mapView.visibleDegrees(), animate = true)
    }

    private fun hidePlaceCard() {
        placeCard.visibility = View.GONE
    }

    /**
     * Fotos dieses Ortes in der Großansicht öffnen – die Liste wird mitgegeben, damit man
     * dort durch alle Fotos dieses Ortes wischen kann.
     */
    private fun openPhotos(items: List<MediaItem>, title: String?) {
        if (items.isEmpty()) return
        ViewState.viewList = items
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_POSITION, 0)
                .putExtra(DetailActivity.EXTRA_URI, items.first().uri)
                .putExtra(DetailActivity.EXTRA_TITLE, title)
        )
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"

        /** Karte auf eine bestimmte Position öffnen (z. B. aus den Metadaten eines Fotos). */
        fun intentFor(context: android.content.Context, lat: Double, lon: Double): Intent =
            Intent(context, MapActivity::class.java)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LON, lon)
    }
}
