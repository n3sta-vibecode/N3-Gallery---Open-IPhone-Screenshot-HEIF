package com.n3vibecode.gallery.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ScaleGestureDetector
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.FolderStore
import com.n3vibecode.gallery.data.GridPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.image.ImageLoader
import com.n3vibecode.gallery.util.Fmt
import java.util.concurrent.Executors

private const val SPAN = GridPrefs.DEFAULT

/**
 * Hintergrund-Arbeiten für das Raster: Zeilenaufbau, Gruppierung, Filterung.
 * Ein einzelner Thread genügt – so kann sich nie eine alte und eine neue
 * Berechnung derselben Ansicht überschneiden, und die Oberfläche bleibt frei.
 */
object GridWork {
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "n3-grid").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    fun run(block: () -> Unit) {
        pool.execute {
            try {
                block()
            } catch (_: Throwable) {
                // Ein Fehler in einer Berechnung darf die Ansicht nie leer lassen.
            }
        }
    }
}

/** Tabs im Wisch-Navigator: informiert, ob der Tab gerade sichtbar ist. */
interface PageAware {
    fun setPageActive(active: Boolean)
}

/** Basis: Raster-RecyclerView mit Leerzustand und automatischer Aktualisierung. */
abstract class BaseGridFragment : Fragment(), PageAware {

    protected lateinit var recycler: RecyclerView
    protected lateinit var emptyView: View
    protected lateinit var emptyTitle: TextView
    private lateinit var adapter: GridAdapter

    /** Spalten pro Zeile – mit zwei Fingern zoombar (mehr Fotos auf einmal). */
    private var span = SPAN
    /** Maßstab seit Beginn der Zwei-Finger-Geste (1 = unverändert). */
    private var pinchScale = 1f
    private var pinchBaseSpan = GridPrefs.DEFAULT
    private var consumingPinch = false
    private var overviewOpened = false
    private lateinit var pinchDetector: ScaleGestureDetector
    private var pinchLabel: TextView? = null

    private val main = Handler(Looper.getMainLooper())

    /** Laufende Nummer der Aktualisierung – ältere Berechnungen werden verworfen. */
    @Volatile
    private var refreshToken = 0

    /** Es gab Änderungen, während der Tab nicht sichtbar war. */
    @Volatile
    private var dirty = false

    /** Nur sichtbare Tabs rechnen mit – spart beim Start viel Arbeit. */
    @Volatile
    private var pageActive = true

    private var lastPrefetchAt = 0L
    private var lastKnownSpan = 0

    protected abstract fun buildList(): List<MediaItem>

    /** Einmal pro Aktualisierung aufrufen – hier Gruppendaten vorberechnen (Performance). */
    protected open fun prepare(list: List<MediaItem>) {}

    /**
     * Gruppierung für Überschriften (Tag/Monat/Jahr). Wird im Hintergrund benutzt,
     * darf also keine Views anfassen.
     */
    protected open fun grouper(): Grouper? = null

    protected open fun withHeaders(): Boolean = true

    /** Werbe-Banner oben einblenden (nur in der Zeitleiste). */
    protected open fun withBanner(): Boolean = false
    protected open fun emptyText(): Pair<String, String> = getString(R.string.empty) to getString(R.string.empty_hint)

    /** Steuerleiste (Gruppierung Tag/Monat/Jahr + Spalten) – nur in der Zeitleiste. */
    protected open fun showControlBar(): Boolean = false
    protected open fun setupControlBar(root: View) {}
    /** Anzeige der Steuerleiste an den aktuellen Zustand anpassen. */
    protected open fun syncControls() {}

    private val onHubChange: () -> Unit = { refresh() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler)
        emptyView = view.findViewById(R.id.emptyView)
        emptyTitle = view.findViewById(R.id.emptyTitle)
        pinchLabel = view.findViewById(R.id.tvPinchLabel)

        // Steuerleiste zuerst aufbauen – sie legt z. B. die Gruppierung fest,
        // bevor der Adapter erzeugt wird.
        val bar = view.findViewById<View>(R.id.controlBar)
        if (showControlBar()) {
            bar.visibility = View.VISIBLE
            setupControlBar(view)
        } else {
            bar.visibility = View.GONE
        }

        span = GridPrefs.span(requireContext())
        adapter = GridAdapter(span, withHeaders(), ::openDetailFromItem, ::onItemLongPress)
        val lm = GridLayoutManager(requireContext(), span)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            // Überschriften/Banner über die ganze Zeile, Fotos je eine Spalte
            override fun getSpanSize(position: Int): Int =
                if (adapter.isFullSpan(position)) lm.spanCount else 1
        }
        // Damit beim Wischen schon die nächste Zeile vorbereitet wird
        lm.initialPrefetchItemCount = span * 2
        recycler.layoutManager = lm
        recycler.adapter = adapter
        // Viele kleine Kacheln: großzügiger Kachel-Pool und Zwischenspeicher, damit beim
        // Scrollen und Zoomen kaum neue Views gebaut werden müssen
        recycler.recycledViewPool.setMaxRecycledViews(1, 400)
        recycler.setItemViewCacheSize(48)
        recycler.itemAnimator = null
        // Alle Kacheln sind gleich groß: Damit muss die RecyclerView bei Änderungen nicht
        // jedes Mal die komplette Liste neu vermessen (spürbar beim Scrollen).
        recycler.setHasFixedSize(true)
        setupPrefetch()
        setupPinchZoom()
        maybeShowPinchHint()
        syncControls()
        DataHub.addListener(onHubChange)
        refresh()
    }

    override fun onDestroyView() {
        DataHub.removeListener(onHubChange)
        // Laufende Berechnungen dieser Ansicht verwerfen
        refreshToken++
        // Nicht in den „es wird gescrollt“-Zustand hängen bleiben
        ImageLoader.setScrolling(false)
        super.onDestroyView()
    }

    /**
     * Wird vom Hauptbildschirm beim Tab-Wechsel aufgerufen. Unsichtbare Tabs bauen
     * ihre Listen nicht neu auf – das war beim Start und beim Aktualisieren der
     * Hauptgrund für hängende Bilder.
     */
    override fun setPageActive(active: Boolean) {
        val becameActive = active && !pageActive
        pageActive = active
        if (!active) ImageLoader.setScrolling(false)
        if (becameActive && dirty) {
            dirty = false
            if (isAdded) refresh()
        }
    }

    protected open fun openDetail(index: Int) {
        openDetail(index, null)
    }

    /**
     * Öffnet die Großansicht. Wichtig: Die Liste, zu der die Position gehört, wird hier
     * **mitgegeben** und als aktuelle Liste gesetzt. Vorher lag sie nur irgendwo global
     * im Speicher und konnte von einer anderen Ansicht (Album, Ordner, Sammlung)
     * überschrieben werden – dann öffnete ein Tipp das falsche Foto oder gar keins.
     */
    protected fun openDetail(index: Int, uri: String?) {
        lastVisible?.let { ViewState.viewList = it }
        startActivity(
            Intent(requireContext(), DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_POSITION, index)
                .putExtra(DetailActivity.EXTRA_URI, uri)
        )
    }

    private fun openDetailFromItem(item: MediaItem, index: Int) {
        openDetail(index, item.uri)
    }

    /** Zuletzt angezeigte Liste (gehört zu den Positionen der Kacheln). */
    @Volatile
    private var lastVisible: List<MediaItem>? = null

    /** Langes Drücken: Bearbeiten, Teilen, Details, Löschen. */
    private fun onItemLongPress(item: MediaItem) {
        val labels = arrayOf(
            getString(R.string.editor_title),
            getString(R.string.share),
            getString(R.string.info),
            getString(R.string.delete)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> EditorActivity.start(requireContext(), item.uri)
                    1 -> share(item)
                    2 -> {
                        val index = lastVisible?.indexOfFirst { it.uri == item.uri } ?: -1
                        if (index >= 0) openDetail(index, item.uri)
                    }
                    else -> confirmDelete(item)
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ Löschen

    /** Löschen mit Rückfrage; ab Android 10 mit der System-Bestätigung. */
    private fun confirmDelete(item: MediaItem) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, item.name))
            .setPositiveButton(R.string.delete) { _, _ -> deleteItem(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteItem(item: MediaItem) {
        val uri = android.net.Uri.parse(item.uri)
        if (android.os.Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
            // Android 10+ verlangt eine Bestätigung des Systems – dann über den Launcher
            pendingDelete = item
            GridWork.run {
                val sender = try {
                    android.provider.MediaStore.createDeleteRequest(
                        requireContext().contentResolver, listOf(uri)
                    ).intentSender
                } catch (t: Throwable) {
                    null
                }
                main.post {
                    if (!isAdded) return@post
                    if (sender != null) {
                        try {
                            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        } catch (t: Throwable) {
                            toast(getString(R.string.delete_failed, t.message ?: ""))
                        }
                    } else {
                        deleteDirect(uri)
                    }
                }
            }
        } else {
            deleteDirect(uri)
        }
    }

    private fun deleteDirect(uri: android.net.Uri) {
        try {
            if (uri.scheme == "content") {
                requireContext().contentResolver.delete(uri, null, null)
            } else {
                java.io.File(uri.path ?: return).delete()
            }
            toast(getString(R.string.deleted))
            DataHub.requestRescan()
            refresh()
        } catch (t: Throwable) {
            toast(getString(R.string.delete_failed, t.message ?: ""))
        }
    }

    /** Foto, das gerade über die System-Bestätigung gelöscht wird. */
    private var pendingDelete: MediaItem? = null

    /** System-Bestätigung des Löschens (Android 10+). */
    private val deleteLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val item = pendingDelete
        pendingDelete = null
        if (result.resultCode == android.app.Activity.RESULT_OK && item != null) {
            toast(getString(R.string.deleted))
            DataHub.requestRescan()
            refresh()
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT).show()
    }


    private fun share(item: MediaItem) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mime.ifBlank { "image/*" }
                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(item.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (t: Throwable) {
            Toast.makeText(
                requireContext(),
                getString(R.string.share_failed, t.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Raster aktualisieren. Der schwere Teil (Filtern, Gruppieren, Überschriften
     * formatieren) läuft im Hintergrund; die Ansicht bekommt anschließend nur die
     * Unterschiede – dadurch bleibt das Scrollen jederzeit flüssig.
     */
    protected fun refresh() {
        if (!isAdded) return
        if (!pageActive) {
            dirty = true
            return
        }
        // Rastergröße kann in einer anderen Ansicht oder über das Menü geändert worden sein
        val pref = GridPrefs.span(requireContext())
        if (pref != span) {
            span = pref
            applySpan()
        }
        val token = ++refreshToken
        dirty = false
        val base = buildList()
        val showBanner = withBanner()
        val headers = withHeaders()
        val grouper = grouper()
        GridWork.run {
            if (token != refreshToken) return@run
            val list = DataHub.visible(base)
            if (token != refreshToken) return@run
            prepare(list)
            val built = GridAdapter.buildRows(list, headers, grouper, showBanner)
            if (token != refreshToken) return@run
            main.post {
                if (token != refreshToken || !isAdded) return@post
                lastVisible = list
                adapter.submitRows(built)
                DataHubViewHelper.updateEmpty(emptyView, list.isEmpty(), emptyText())
                startPrefetch()
                startWarmUp(list)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::recycler.isInitialized) {
            val pref = GridPrefs.span(requireContext())
            if (pref != span) {
                span = pref
                applySpan()
            }
        }
    }

    // ---------------------------------------------------------------- Vorladen

    /**
     * Die ersten Bildschirme im Voraus berechnen, damit beim ersten Wischen
     * schon Bilder da sind statt grauer Kacheln.
     */
    private fun startPrefetch() {
        if (!isAdded) return
        val ctx = requireContext().applicationContext
        val px = adapter.tilePx()
        val first = adapter.itemsBetween(0, span * 6)
        lastPrefetchAt = SystemClock.uptimeMillis()
        GridWork.run { ImageLoader.prefetch(ctx, first, px, 60) }
    }

    /**
     * Nach dem Aufbau die nächsten Kacheln in Ruhe fertigstellen (in Reihenfolge der Liste,
     * also genau die, die beim Weiterscrollen drankommen). Die Anfragen landen **hinter**
     * den sichtbaren Bildern in der Warteschlange – Wischen und Antippen bleiben dadurch
     * jederzeit reaktionsschnell, und beim Scrollen sind die Bilder schon da.
     */
    private fun startWarmUp(list: List<MediaItem>) {
        if (list.isEmpty() || !isAdded) return
        val ctx = requireContext().applicationContext
        val px = adapter.tilePx()
        // Die ganze Liste der Reihe nach vorbereiten – der Daemon überspringt, was schon
        // fertig ist, und macht beim nächsten Start dort weiter.
        ImageLoader.startWarmUp(ctx, list, px)
    }

    /**
     * Beim Wischen die nächsten Kacheln vorladen – die Anfragen landen hinter den
     * sichtbaren Bildern in der Warteschlange und kosten daher keine Reaktionszeit.
     */
    private fun setupPrefetch() {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // Beim Wischen pausiert der Hintergrund-Aufbau: die sichtbaren Kacheln
                // bekommen die CPU allein (wie bei Apple Fotos).
                ImageLoader.setScrolling(newState != RecyclerView.SCROLL_STATE_IDLE)
                // Sobald der Finger ruht, in Ruhe die nächsten Kacheln fertigstellen.
                if (newState == RecyclerView.SCROLL_STATE_IDLE) prefetchAround(rv)
            }
        })
    }

    /**
     * Die nächsten Kacheln rund um die aktuelle Position vorbereiten.
     *
     * Das läuft **nur**, wenn gerade nicht gewischt wird. Vorher wurden die Vorladeaufträge
     * mitten im Scrollen abgeschickt (alle 220 ms bis zu 60 Stück) und nahmen den
     * sichtbaren Kacheln die Rechenzeit weg – das war der Grund, warum das Wischen mit
     * schon geladenen Bildern nicht flüssig war.
     */
    private fun prefetchAround(rv: RecyclerView) {
        if (!isAdded) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPrefetchAt < 150) return
        lastPrefetchAt = now
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return
        val spread = currentSpanForPrefetch()
        val forward = adapter.itemsBetween(last + 1, last + spread * 4)
        val backward = if (first > spread) adapter.itemsBetween(first - spread * 2, first - 1) else emptyList()
        val ctx = requireContext().applicationContext
        val px = adapter.tilePx()
        GridWork.run { ImageLoader.prefetch(ctx, forward + backward, px, 90) }
    }

    private fun currentSpanForPrefetch(): Int = maxOf(1, currentSpan())

    // ---------------------------------------------------------------- Zoomen im Raster

    /**
     * Zwei Finger zusammenziehen → **mehr Fotos auf einmal** (Kacheln kleiner),
     * auseinanderziehen → größer und weniger. Läuft im Ereignisweg der Raster-Klasse
     * (siehe [com.n3vibecode.gallery.widget.ZoomGridRecyclerView]) und ist damit
     * unabhängig von Kacheln, Scrollen oder der Tab-Wischgeste.
     */
    private fun setupPinchZoom() {
        val grid = recycler as? com.n3vibecode.gallery.widget.ZoomGridRecyclerView ?: return
        grid.onPinchStart = {
            pinchScale = 1f
            pinchBaseSpan = span
            overviewOpened = false
            recycler.stopScroll()
        }
        grid.onPinchScale = { factor ->
            pinchScale *= factor
            if (pinchScale <= 0.01f) pinchScale = 0.01f
            if (pinchScale >= 100f) pinchScale = 100f
            // Zusammenziehen (Maßstab < 1) => mehr Spalten, auseinanderziehen => weniger
            setColumnsLive(Math.round(pinchBaseSpan / pinchScale))
        }
        grid.onPinchEnd = { hidePinchLabelSoon() }
    }

    /** Spaltenzahl live während des Zoomens setzen (ohne Toast, dafür mit Einblendung). */
    private fun setColumnsLive(target: Int) {
        // Weiter als bis zur Rastergrenze zoomen? Dann öffnet sich die Gesamtübersicht,
        // in der wirklich alle Fotos auf einen Blick zu sehen sind.
        if (target > GridPrefs.MAX && !overviewOpened) {
            overviewOpened = true
            openOverview()
            return
        }
        val t = target.coerceIn(GridPrefs.MIN, GridPrefs.MAX)
        if (t == span) return
        span = t
        GridPrefs.setSpan(requireContext(), t)
        applySpan()
        showPinchLabel()
        recycler.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun showPinchLabel() {
        val label = pinchLabel ?: return
        label.animate().cancel()
        label.text = getString(R.string.grid_cols, span)
        label.alpha = 1f
        label.visibility = View.VISIBLE
    }

    private fun hidePinchLabelSoon() {
        val label = pinchLabel ?: return
        label.postDelayed({
            label.animate().alpha(0f).setDuration(250)
                .withEndAction { label.visibility = View.GONE }.start()
        }, 900)
    }

    /** Einmaliger Hinweis: "Zwei Finger zusammenziehen = mehr Fotos". */
    private fun maybeShowPinchHint() {
        val prefs = requireContext().getSharedPreferences("n3_gallery_meta", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("pinch_hint_shown", false)) return
        prefs.edit().putBoolean("pinch_hint_shown", true).apply()
        val hint = view?.findViewById<TextView>(R.id.tvPinchHint) ?: return
        hint.visibility = View.VISIBLE
        hint.postDelayed({
            hint.animate().alpha(0f).setDuration(400)
                .withEndAction { hint.visibility = View.GONE }.start()
        }, 6500)
    }

    /** Aktuelle Spaltenzahl (für die Anzeige in der Steuerleiste). */
    protected fun currentSpan(): Int = span

    /** Überschriften passend zur Einstellung an-/abschalten und neu aufbauen. */
    protected fun applyHeadersAndRefresh() {
        adapter.setHeaders(withHeaders())
        refresh()
    }

    /** Gesamtübersicht: alle Fotos als ein Mosaik auf einem Bildschirm. */
    protected fun openOverview() {
        startActivity(Intent(requireContext(), OverviewActivity::class.java))
    }

    protected fun changeSpan(delta: Int) {
        // Am Maximum: „+“ öffnet die Gesamtübersicht (statt einer Grenzmeldung)
        if (delta > 0 && span >= GridPrefs.MAX) {
            overviewOpened = true
            openOverview()
            return
        }
        // Sinnvolle Schrittweite: bis 8 Spalten einzeln, danach größere Sprünge
        val step = when {
            span < 8 -> 1
            span < 16 -> 2
            else -> 4
        }
        val direction = if (delta >= 0) 1 else -1
        val target = (span + direction * step).coerceIn(GridPrefs.MIN, GridPrefs.MAX)
        if (target == span) return
        span = target
        GridPrefs.setSpan(requireContext(), target)
        applySpan()
        Toast.makeText(
            requireContext(),
            getString(if (delta > 0) R.string.grid_zoom_more else R.string.grid_zoom_less, target),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applySpan() {
        (recycler.layoutManager as? GridLayoutManager)?.spanCount = span
        adapter.setSpan(span)
        recycler.requestLayout()
        syncControls()
        // Andere Kachelgröße = andere Vorschaugröße: Hintergrund-Aufbau neu ausrichten
        val list = lastVisible
        if (isAdded && !list.isNullOrEmpty()) {
            ImageLoader.startWarmUp(requireContext().applicationContext, list, adapter.tilePx())
        }
    }
}

object DataHubViewHelper {
    fun updateEmpty(view: View, isEmpty: Boolean, texts: Pair<String, String>) {
        view.visibility = if (isEmpty) View.VISIBLE else View.GONE
        (view.findViewById<TextView>(R.id.emptyTitle) as? TextView)?.text = texts.first
        view.findViewById<TextView>(R.id.emptySub)?.text = texts.second
    }
}

// ============================================================ Zeitleiste (Tag/Monat/Jahr)

class TimelineFragment : BaseGridFragment() {

    override fun withBanner(): Boolean = true

    override fun showControlBar(): Boolean = true

    /** Gruppierung: nach Tag (Standard), Monat, Jahr oder eine durchgehende Liste. */
    @Volatile
    private var mode: String = GridPrefs.MODE_DAY

    /** Anzahl und Gesamtgröße je Gruppe – im Hintergrund berechnet. */
    private val info = HashMap<Long, Pair<Int, Long>>()

    override fun withHeaders(): Boolean = mode != GridPrefs.MODE_NONE

    override fun buildList(): List<MediaItem> = DataHub.all

    override fun prepare(list: List<MediaItem>) {
        val counts = HashMap<Long, Int>()
        val sizes = HashMap<Long, Long>()
        list.forEach { item ->
            val key = bucketKey(item)
            counts[key] = (counts[key] ?: 0) + 1
            sizes[key] = (sizes[key] ?: 0L) + item.size
        }
        val fresh = HashMap<Long, Pair<Int, Long>>(counts.size)
        counts.keys.forEach { k -> fresh[k] = (counts[k] ?: 0) to (sizes[k] ?: 0L) }
        info.clear()
        info.putAll(fresh)
    }

    /** Gruppenschlüssel (Ausnahme: nicht „keyOf“ heißen, sonst verdeckt das Grouper-Objekt ihn). */
    private fun bucketKey(item: MediaItem): Long = when (mode) {
        GridPrefs.MODE_MONTH -> Fmt.monthKey(item.time)
        GridPrefs.MODE_YEAR -> Fmt.yearKey(item.time)
        else -> Fmt.dayKey(item.time)
    }

    override fun grouper(): Grouper? {
        if (mode == GridPrefs.MODE_NONE) return null
        val currentMode = mode
        return object : Grouper {
            override fun keyOf(item: MediaItem): String? = bucketKey(item).toString()

            /** Wird nur einmal pro Gruppe aufgerufen (nicht pro Foto!). */
            override fun headerOf(item: MediaItem): Pair<String, String> {
                val k = bucketKey(item)
                val i = info[k] ?: (1 to item.size)
                val sub = buildString {
                    append(getString(R.string.count_files, i.first))
                    if (i.second > 0) append(" · ").append(Fmt.bytes(i.second))
                    when (currentMode) {
                        GridPrefs.MODE_MONTH -> if (item.time > 0) append(" · ").append(Fmt.monthRange(item.time))
                        GridPrefs.MODE_YEAR -> Unit
                        else -> if (item.time > 0) append(" · ").append(Fmt.weekday(item.time))
                    }
                }
                val title = when (currentMode) {
                    GridPrefs.MODE_MONTH -> Fmt.monthTitle(item.time)
                    GridPrefs.MODE_YEAR -> Fmt.yearTitle(item.time)
                    else -> Fmt.dayTitle(item.time)
                }
                return title to sub
            }
        }
    }

    override fun setupControlBar(root: View) {
        mode = GridPrefs.timelineMode(requireContext())
        root.findViewById<Chip>(R.id.chipDay).setOnClickListener { setMode(GridPrefs.MODE_DAY) }
        root.findViewById<Chip>(R.id.chipMonth).setOnClickListener { setMode(GridPrefs.MODE_MONTH) }
        root.findViewById<Chip>(R.id.chipYear).setOnClickListener { setMode(GridPrefs.MODE_YEAR) }
        root.findViewById<Chip>(R.id.chipAll).setOnClickListener { setMode(GridPrefs.MODE_NONE) }
        root.findViewById<MaterialButton>(R.id.btnColsMinus).setOnClickListener { changeSpan(-1) }
        root.findViewById<MaterialButton>(R.id.btnColsPlus).setOnClickListener { changeSpan(+1) }
    }

    override fun syncControls() {
        val root = view ?: return
        root.findViewById<Chip>(R.id.chipDay)?.isChecked = mode == GridPrefs.MODE_DAY
        root.findViewById<Chip>(R.id.chipMonth)?.isChecked = mode == GridPrefs.MODE_MONTH
        root.findViewById<Chip>(R.id.chipYear)?.isChecked = mode == GridPrefs.MODE_YEAR
        root.findViewById<Chip>(R.id.chipAll)?.isChecked = mode == GridPrefs.MODE_NONE
        root.findViewById<TextView>(R.id.tvCols)?.text = getString(R.string.grid_cols, currentSpan())
    }

    private fun setMode(newMode: String) {
        if (newMode == mode) return
        mode = newMode
        GridPrefs.setTimelineMode(requireContext(), newMode)
        applyHeadersAndRefresh()
        syncControls()
    }
}

// ============================================================ Formate

class FormatsFragment : BaseGridFragment() {

    private val familyInfo = HashMap<String, Pair<Int, String>>()

    override fun buildList(): List<MediaItem> = DataHub.all

    // Auch hier: Spalten mit −/+ einstellbar (ohne die Gruppierungs-Chips)
    override fun showControlBar(): Boolean = true

    override fun setupControlBar(root: View) {
        root.findViewById<View>(R.id.groupChips)?.visibility = View.GONE
        root.findViewById<View>(R.id.colsDivider)?.visibility = View.GONE
        root.findViewById<MaterialButton>(R.id.btnColsMinus).setOnClickListener { changeSpan(-1) }
        root.findViewById<MaterialButton>(R.id.btnColsPlus).setOnClickListener { changeSpan(+1) }
    }

    override fun syncControls() {
        view?.findViewById<TextView>(R.id.tvCols)?.text = getString(R.string.grid_cols, currentSpan())
    }

    override fun prepare(list: List<MediaItem>) {
        val fresh = HashMap<String, Pair<Int, String>>()
        list.groupBy { it.family }.forEach { (family, items) ->
            val formats = items.map { it.format }.distinct().sorted()
            val shown = if (formats.size > 14) formats.take(14).joinToString(", ") + " …" else formats.joinToString(", ")
            val count = items.size
            fresh[family] = count to shown
        }
        familyInfo.clear()
        familyInfo.putAll(fresh)
    }

    override fun grouper(): Grouper = object : Grouper {
        override fun keyOf(item: MediaItem): String? = item.family

        override fun headerOf(item: MediaItem): Pair<String, String> {
            val info = familyInfo[item.family] ?: return item.family to item.format
            return item.family to "${getString(R.string.count_files, info.first)} · ${info.second}"
        }
    }
}

// ============================================================ Tags / Alben

class TagsFragment : Fragment() {

    private var adapter: CollectionAdapter? = null
    private val onHubChange: () -> Unit = { refresh() }
    @Volatile
    private var token = 0
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val empty = view.findViewById<View>(R.id.emptyView)
        recycler.itemAnimator = null
        adapter = CollectionAdapter { row -> onRow(row) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        DataHub.addListener(onHubChange)
        refresh()
        empty.visibility = View.GONE
    }

    override fun onDestroyView() {
        DataHub.removeListener(onHubChange)
        token++
        adapter = null
        super.onDestroyView()
    }

    private fun onRow(row: ListEntry.Row) {
        when {
            row.key == "favorites" -> openCollection("favorites", row.title)
            row.key == "notes" -> openCollection("notes", row.title)
            row.key.startsWith("tag:") -> openCollection("tag", row.key.removePrefix("tag:"))
            row.key.isNotBlank() -> openCollection("tag", row.title)
        }
    }

    private fun openCollection(mode: String, value: String) {
        startActivity(
            Intent(requireContext(), CollectionActivity::class.java)
                .putExtra(CollectionActivity.EXTRA_MODE, mode)
                .putExtra(CollectionActivity.EXTRA_VALUE, value)
        )
    }

    private fun refresh() {
        if (adapter == null || !isAdded) return
        val myToken = ++token
        val all = DataHub.all
        // WICHTIG: Die Vorlage darf NICHT vorab mit 0 formatiert werden – dabei wird der
        // Platzhalter ersetzt und jede Zeile zeigt danach für immer „0 Dateien“.
        val ctx = requireContext().applicationContext
        GridWork.run {
            val entries = mutableListOf<ListEntry>()
            val favs = all.filter { MetaStore.isFavorite(it.uri) }
            entries += ListEntry.Section("Alben")
            entries += ListEntry.Row(
                title = "Favoriten",
                sub = ctx.getString(R.string.count_files, favs.size),
                formats = favs.map { it.format }.distinct().take(6).joinToString(", "),
                cover = favs.firstOrNull(),
                key = "favorites"
            )
            val noteItems = all.filter { MetaStore.hasNote(it.uri) }
            if (noteItems.isNotEmpty()) {
                entries += ListEntry.Row(
                    title = "Mit Notiz",
                    sub = ctx.getString(R.string.count_files, noteItems.size),
                    formats = "Notizen & Beschriftungen",
                    cover = noteItems.firstOrNull(),
                    key = "notes"
                )
            }

            val tagNames = MetaStore.allTagNames()
            if (tagNames.isNotEmpty()) {
                entries += ListEntry.Section("Tags")
                val byUri = all.associateBy { it.uri }
                tagNames.forEach { tag ->
                    val items = MetaStore.itemsForTag(tag).mapNotNull { byUri[it] }
                    entries += ListEntry.Row(
                        title = tag,
                        sub = ctx.getString(R.string.count_files, items.size),
                        formats = items.map { it.format }.distinct().take(6).joinToString(", "),
                        cover = items.firstOrNull(),
                        key = "tag:$tag"
                    )
                }
            } else {
                entries += ListEntry.Section("Tags")
                entries += ListEntry.Row(
                    title = "Noch keine Tags",
                    sub = "Vergib Tags über „Bearbeiten“ in der Detailansicht – sie erscheinen hier als Alben.",
                    formats = "",
                    cover = null,
                    key = ""
                )
            }
            main.post {
                if (myToken != token) return@post
                adapter?.submit(entries)
            }
        }
    }
}

// ============================================================ Ordner

class FoldersFragment : Fragment() {

    private var adapter: CollectionAdapter? = null
    private val onHubChange: () -> Unit = { refresh() }
    @Volatile
    private var token = 0
    private val main = Handler(Looper.getMainLooper())

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            FolderStore(requireContext()).add(uri)
            (activity as? MainActivity)?.reload()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val empty = view.findViewById<View>(R.id.emptyView)
        recycler.itemAnimator = null
        adapter = CollectionAdapter { row ->
            when {
                row.isAdd -> pickFolder.launch(null)
                row.removable -> {
                    FolderStore(requireContext()).remove(row.treeUri.orEmpty())
                    (activity as? MainActivity)?.reload()
                    refresh()
                }
                row.key.startsWith("bucket:") -> startActivity(
                    Intent(requireContext(), CollectionActivity::class.java)
                        .putExtra(CollectionActivity.EXTRA_MODE, "bucket")
                        .putExtra(CollectionActivity.EXTRA_VALUE, row.key.removePrefix("bucket:"))
                )
            }
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        DataHub.addListener(onHubChange)
        refresh()
        empty.visibility = View.GONE
    }

    override fun onDestroyView() {
        DataHub.removeListener(onHubChange)
        token++
        adapter = null
        super.onDestroyView()
    }

    private fun refresh() {
        if (adapter == null || !isAdded) return
        val myToken = ++token
        val store = FolderStore(requireContext())
        val all = DataHub.all
        val treeUris = store.treeUris()
        val names: Map<String, String> =
            treeUris.associateWith { tree -> runCatching { store.displayName(tree) }.getOrElse { tree } }
        // Siehe oben: Vorlage erst mit der echten Zahl formatieren.
        val ctx = requireContext().applicationContext
        val addTitle = getString(R.string.add_folder)
        val pickHint = getString(R.string.folder_pick)
        val emptyHint = getString(R.string.folders_empty)

        GridWork.run {
            val entries = mutableListOf<ListEntry>()

            entries += ListEntry.Section("Eigene Ordner (RAW, HEIC, AVIF …)")
            entries += ListEntry.Row(
                title = addTitle,
                sub = pickHint,
                formats = "",
                cover = null,
                isAdd = true
            )
            if (treeUris.isEmpty()) {
                entries += ListEntry.Row(
                    title = emptyHint,
                    sub = "Nützlich für Ordner außerhalb des Medienindex (SD-Karte, NAS-Sync, Download-Ordner).",
                    formats = "",
                    cover = null
                )
            } else {
                // Ein Durchlauf statt pro Ordner über alle Fotos zu gehen
                val safByTree = HashMap<String, MutableList<MediaItem>>()
                treeUris.forEach { safByTree[it] = mutableListOf() }
                val safAll = mutableListOf<MediaItem>()
                all.forEach { item ->
                    if (!item.isSaf) return@forEach
                    safAll += item
                    val key = treeUris.firstOrNull { item.uri.startsWith(it) }
                    if (key != null) safByTree[key]?.add(item)
                }
                treeUris.forEach { uriString ->
                    val items = safByTree[uriString].orEmpty().ifEmpty { safAll }
                    entries += ListEntry.Row(
                        title = names[uriString] ?: uriString,
                        sub = ctx.getString(R.string.count_files, items.size),
                        formats = "SAF-Ordner · dauerhafter Lesezugriff",
                        cover = items.firstOrNull(),
                        treeUri = uriString,
                        removable = true
                    )
                }
            }

            val buckets = all.groupBy { it.bucket }.toList().sortedByDescending { it.second.size }
            if (buckets.isNotEmpty()) {
                entries += ListEntry.Section("Geräteordner")
                buckets.forEach { (name, items) ->
                    entries += ListEntry.Row(
                        title = name.ifEmpty { "Unbekannt" },
                        sub = ctx.getString(R.string.count_files, items.size) + " · " + Fmt.bytes(items.sumOf { it.size }),
                        formats = items.map { it.format }.distinct().take(8).joinToString(", "),
                        cover = items.firstOrNull(),
                        key = "bucket:$name"
                    )
                }
            }
            main.post {
                if (myToken != token) return@post
                adapter?.submit(entries)
            }
        }
    }
}
