package com.n3vibecode.gallery.ui

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
import com.n3vibecode.gallery.util.Fmt

private const val SPAN = GridPrefs.DEFAULT

/** Basis: Raster-RecyclerView mit Leerzustand und automatischer Aktualisierung. */
abstract class BaseGridFragment : Fragment() {

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

    protected abstract fun buildList(): List<MediaItem>

    /** Einmal pro Aktualisierung aufrufen – hier Gruppendaten vorberechnen (Performance). */
    protected open fun prepare(list: List<MediaItem>) {}

    protected open fun groupOf(item: MediaItem): Pair<String, String>? = null
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
        adapter = GridAdapter(span, withHeaders(), ::openDetail)
        val lm = GridLayoutManager(requireContext(), span)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            // Überschriften/Banner über die ganze Zeile, Fotos je eine Spalte
            override fun getSpanSize(position: Int): Int =
                if (adapter.isFullSpan(position)) lm.spanCount else 1
        }
        recycler.layoutManager = lm
        recycler.adapter = adapter
        // Viele kleine Kacheln: großzügiger Kachel-Pool und Zwischenspeicher, damit beim
        // Scrollen und Zoomen kaum neue Views gebaut werden müssen
        recycler.recycledViewPool.setMaxRecycledViews(1, 400)
        recycler.setItemViewCacheSize(40)
        recycler.itemAnimator = null
        setupPinchZoom()
        maybeShowPinchHint()
        syncControls()
        DataHub.addListener(onHubChange)
        refresh()
    }

    override fun onDestroyView() {
        DataHub.removeListener(onHubChange)
        super.onDestroyView()
    }

    protected open fun openDetail(index: Int) {
        startActivity(
            Intent(requireContext(), DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_POSITION, index)
        )
    }

    protected fun refresh() {
        if (!isAdded) return
        // Rastergröße kann in einer anderen Ansicht oder über das Menü geändert worden sein
        val pref = GridPrefs.span(requireContext())
        if (pref != span) {
            span = pref
            applySpan()
        }
        val list = DataHub.visible(buildList())
        prepare(list)
        adapter.submit(list, ::groupOf, withBanner())
        DataHubViewHelper.updateEmpty(emptyView, list.isEmpty(), emptyText())
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
        syncControls()
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
    private var mode: String = GridPrefs.MODE_DAY

    private val info = HashMap<Long, Pair<Int, Long>>()

    override fun withHeaders(): Boolean = mode != GridPrefs.MODE_NONE

    override fun buildList(): List<MediaItem> = DataHub.all

    override fun prepare(list: List<MediaItem>) {
        info.clear()
        val counts = HashMap<Long, Int>()
        val sizes = HashMap<Long, Long>()
        list.forEach { item ->
            val key = keyOf(item)
            counts[key] = (counts[key] ?: 0) + 1
            sizes[key] = (sizes[key] ?: 0L) + item.size
        }
        counts.keys.forEach { k -> info[k] = (counts[k] ?: 0) to (sizes[k] ?: 0L) }
    }

    private fun keyOf(item: MediaItem): Long = when (mode) {
        GridPrefs.MODE_MONTH -> Fmt.monthKey(item.time)
        GridPrefs.MODE_YEAR -> Fmt.yearKey(item.time)
        else -> Fmt.dayKey(item.time)
    }

    override fun groupOf(item: MediaItem): Pair<String, String>? {
        if (mode == GridPrefs.MODE_NONE) return null
        val k = keyOf(item)
        val i = info[k] ?: (1 to item.size)
        val sub = buildString {
            append(getString(R.string.count_files, i.first))
            if (i.second > 0) append(" · ").append(Fmt.bytes(i.second))
            when (mode) {
                GridPrefs.MODE_MONTH -> if (item.time > 0) append(" · ").append(Fmt.monthRange(item.time))
                GridPrefs.MODE_YEAR -> Unit
                else -> if (item.time > 0) append(" · ").append(Fmt.weekday(item.time))
            }
        }
        val title = when (mode) {
            GridPrefs.MODE_MONTH -> Fmt.monthTitle(item.time)
            GridPrefs.MODE_YEAR -> Fmt.yearTitle(item.time)
            else -> Fmt.dayTitle(item.time)
        }
        return title to sub
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
        familyInfo.clear()
        list.groupBy { it.family }.forEach { (family, items) ->
            val formats = items.map { it.format }.distinct().sorted()
            val shown = if (formats.size > 14) formats.take(14).joinToString(", ") + " …" else formats.joinToString(", ")
            familyInfo[family] = items.size to shown
        }
    }

    override fun groupOf(item: MediaItem): Pair<String, String>? {
        val info = familyInfo[item.family] ?: return item.family to item.format
        return item.family to "${getString(R.string.count_files, info.first)} · ${info.second}"
    }
}

// ============================================================ Tags / Alben

class TagsFragment : Fragment() {

    private var adapter: CollectionAdapter? = null
    private val onHubChange: () -> Unit = { refresh() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val empty = view.findViewById<View>(R.id.emptyView)
        adapter = CollectionAdapter { row -> onRow(row) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        DataHub.addListener(onHubChange)
        refresh()
        empty.visibility = View.GONE
    }

    override fun onDestroyView() {
        DataHub.removeListener(onHubChange)
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
        val ad = adapter ?: return
        val entries = mutableListOf<ListEntry>()
        val favs = DataHub.all.filter { MetaStore.isFavorite(it.uri) }
        entries += ListEntry.Section("Alben")
        entries += ListEntry.Row(
            title = "Favoriten",
            sub = getString(R.string.count_files, favs.size),
            formats = favs.map { it.format }.distinct().take(6).joinToString(", "),
            cover = favs.firstOrNull(),
            key = "favorites"
        )
        val noteItems = DataHub.all.filter { MetaStore.note(it.uri).isNotBlank() }
        if (noteItems.isNotEmpty()) {
            entries += ListEntry.Row(
                title = "Mit Notiz",
                sub = getString(R.string.count_files, noteItems.size),
                formats = "Notizen & Beschriftungen",
                cover = noteItems.firstOrNull(),
                key = "notes"
            )
        }

        val tagNames = MetaStore.allTagNames()
        if (tagNames.isNotEmpty()) {
            entries += ListEntry.Section("Tags")
            tagNames.forEach { tag ->
                val uris = MetaStore.itemsForTag(tag)
                val items = DataHub.all.filter { uris.contains(it.uri) }
                entries += ListEntry.Row(
                    title = tag,
                    sub = getString(R.string.count_files, items.size),
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
        ad.submit(entries)
    }
}

// ============================================================ Ordner

class FoldersFragment : Fragment() {

    private var adapter: CollectionAdapter? = null
    private val onHubChange: () -> Unit = { refresh() }

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
        adapter = null
        super.onDestroyView()
    }

    private fun refresh() {
        val ad = adapter ?: return
        val store = FolderStore(requireContext())
        val entries = mutableListOf<ListEntry>()

        entries += ListEntry.Section("Eigene Ordner (RAW, HEIC, AVIF …)")
        entries += ListEntry.Row(
            title = getString(R.string.add_folder),
            sub = getString(R.string.folder_pick),
            formats = "",
            cover = null,
            isAdd = true
        )
        val treeUris = store.treeUris()
        if (treeUris.isEmpty()) {
            entries += ListEntry.Row(
                title = getString(R.string.folders_empty),
                sub = "Nützlich für Ordner außerhalb des Medienindex (SD-Karte, NAS-Sync, Download-Ordner).",
                formats = "",
                cover = null
            )
        } else {
            treeUris.forEach { uriString ->
                val items = DataHub.all.filter { it.uri.startsWith(uriString) || it.isSaf }
                entries += ListEntry.Row(
                    title = store.displayName(uriString),
                    sub = getString(R.string.count_files, items.size),
                    formats = "SAF-Ordner · dauerhafter Lesezugriff",
                    cover = items.firstOrNull(),
                    treeUri = uriString,
                    removable = true
                )
            }
        }

        val buckets = DataHub.all.groupBy { it.bucket }.toList().sortedByDescending { it.second.size }
        if (buckets.isNotEmpty()) {
            entries += ListEntry.Section("Geräteordner")
            buckets.forEach { (name, items) ->
                entries += ListEntry.Row(
                    title = name.ifEmpty { "Unbekannt" },
                    sub = getString(R.string.count_files, items.size) + " · " + Fmt.bytes(items.sumOf { it.size }),
                    formats = items.map { it.format }.distinct().take(8).joinToString(", "),
                    cover = items.firstOrNull(),
                    key = "bucket:$name"
                )
            }
        }
        ad.submit(entries)
    }
}
