package com.n3vibecode.gallery.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.image.ImageLoader
import com.n3vibecode.gallery.util.Fmt

/** Von der Detailansicht genutzte, aktuell sichtbare Liste. */
object ViewState {
    @Volatile
    var viewList: List<MediaItem> = emptyList()
}

/**
 * Gruppierung für die Überschriften im Raster.
 *
 * Wichtig für die Geschwindigkeit: [keyOf] läuft pro Foto (billig), [headerOf] nur **einmal
 * pro Gruppe** – dort steckt die teure Formatierung von Datum und Summen.
 */
interface Grouper {
    /** Schlüssel, der eine Gruppe beschreibt (z. B. der Tag). Null = dieses Foto ohne Gruppe. */
    fun keyOf(item: MediaItem): String?

    /** Fertige Überschrift (Titel + Unterzeile) für das erste Foto einer Gruppe. */
    fun headerOf(item: MediaItem): Pair<String, String>
}

/**
 * Raster-Adapter im Stil von Apple Fotos / Google Fotos: quadratische Kacheln,
 * Formatecke unten links (RAW/HEIC/AVIF), Videolänge, Favoriten-Herz und optional
 * Überschriften (Tag/Format), die über alle Spalten spannen.
 *
 * **Scroll-Verhalten:** Die Zeilenliste wird im Hintergrund aufgebaut und über
 * [AsyncListDiffer] übergeben. Dadurch muss beim Aktualisieren nur das neu gezeichnet
 * werden, was sich wirklich geändert hat – vorher wurde bei jedem Aktualisieren oder
 * Zoomen das komplette Raster neu aufgebaut und alle sichtbaren Kacheln neu gebunden.
 */
class GridAdapter(
    spanCount: Int,
    private var withHeaders: Boolean,
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: ((MediaItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Spalten pro Zeile – ändert sich beim Zoomen mit zwei Fingern. */
    var spanCount: Int = spanCount.coerceAtLeast(1)
        private set

    /** Kachelkantenlänge in px (wird nur bei Spaltenwechsel/Neuaufbau berechnet). */
    private var thumbPx: Int = 512

    /** Abstand zwischen den Kacheln in px. */
    private var padPx: Int = 2
    private var tiny: Boolean = false

    fun setSpan(newSpan: Int) {
        val s = newSpan.coerceAtLeast(1)
        if (s == spanCount) return
        spanCount = s
        measureTiles(null)
        // Nur die sichtbaren Kacheln neu binden – kein kompletter Neuaufbau der Liste.
        notifyItemRangeChanged(0, itemCount)
    }

    private fun measureTiles(anyView: View?) {
        val res = anyView?.resources ?: appResources ?: return
        val dm = res.displayMetrics
        val w = (dm.widthPixels - 24 * dm.density) / spanCount
        thumbPx = (w * 1.3f).toInt().coerceIn(64, 1024)
        tiny = spanCount >= 12
        padPx = if (tiny) 1 else dm.density.toInt().coerceAtLeast(1)
    }

    private var appResources: android.content.res.Resources? = null

    /** Überschriften (Tag/Monat/Jahr) an- oder abschalten. */
    fun setHeaders(show: Boolean) {
        withHeaders = show
    }

    /** Aktuelle Zeilen (nur über den Haupt-Thread lesen). */
    val rows: List<Row> get() = differ.currentList

    /** Kantenlänge einer Kachel in px (für das Vorladen passend zur Spaltenzahl). */
    fun tilePx(): Int = thumbPx

    /** Anzahl der Fotos in einem Positionsbereich (für das Vorladen). */
    fun itemsBetween(from: Int, to: Int): List<MediaItem> {
        val list = differ.currentList
        if (list.isEmpty()) return emptyList()
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(list.size - 1)
        if (start > end) return emptyList()
        val out = ArrayList<MediaItem>(end - start + 1)
        for (i in start..end) {
            val row = list[i]
            if (row is Row.Entry) out += row.item
        }
        return out
    }

    sealed class Row(val id: String) {
        class Header(val title: String, val sub: String) : Row("h:" + title)
        object Banner : Row("banner")
        class Entry(val item: MediaItem) : Row("i:" + item.uri)

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            other !is Row -> false
            id != other.id -> false
            this is Header && other is Header -> title == other.title && sub == other.sub
            this is Entry && other is Entry -> item == other.item
            else -> true
        }

        override fun hashCode(): Int = id.hashCode()
    }

    private val differ = AsyncListDiffer(this, DIFF)

    /** Zeilenliste im Hintergrund aufbauen (siehe [buildRows]) und hier übergeben. */
    fun submitRows(newRows: List<Row>) {
        differ.submitList(newRows)
    }

    /** Einfache Liste ohne Gruppierung setzen (Alben, Sammlungen). */
    fun submitItems(list: List<MediaItem>) {
        ViewState.viewList = list
        differ.submitList(list.map { Row.Entry(it) })
    }

    override fun getItemCount(): Int = differ.currentList.size

    /** Überschriften und Banner füllen die ganze Zeile (alle Spalten). */
    fun isFullSpan(position: Int): Boolean {
        val list = differ.currentList
        return position in list.indices && list[position] !is Row.Entry
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // Anfrage der wiederverwendeten Kachel verwerfen – der Platz geht an sichtbare Fotos
        if (holder is ItemHolder) ImageLoader.cancel(holder.image)
    }

    override fun getItemViewType(position: Int): Int = when (differ.currentList[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Banner -> TYPE_BANNER
        else -> TYPE_ITEM
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvTitle)
        val sub: TextView = view.findViewById(R.id.tvSub)
    }

    class BannerHolder(view: View, onBannerClick: () -> Unit) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.bannerImage)

        init {
            itemView.setOnClickListener { onBannerClick() }
        }
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val badge: TextView = view.findViewById(R.id.badge)
        val fav: ImageView = view.findViewById(R.id.fav)
        val iconOverlay: ImageView = view.findViewById(R.id.iconOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        appResources = parent.resources
        measureTiles(parent)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_timeline_header, parent, false))
            TYPE_BANNER -> BannerHolder(inflater.inflate(R.layout.item_banner, parent, false)) {
                (parent.context as? MainActivity)?.showAboutDialog()
            }
            else -> ItemHolder(inflater.inflate(R.layout.item_media_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = differ.currentList.getOrNull(position) ?: return
        when (row) {
            is Row.Header -> {
                val h = holder as HeaderHolder
                h.title.text = row.title
                h.sub.text = row.sub
            }
            is Row.Banner -> Unit
            is Row.Entry -> {
                val h = holder as ItemHolder
                val item = row.item
                if (appResources == null) {
                    appResources = holder.itemView.resources
                    measureTiles(holder.itemView)
                }

                h.itemView.setPadding(padPx, padPx, padPx, padPx)
                // ImageLoader zeigt sofort eine vorhandene Stufe oder den Platzhalter und
                // lädt dann nach – das Bild wird NICHT vorher geleert.
                ImageLoader.into(holder.itemView.context, item, thumbPx, h.image)

                val badgeText = when {
                    item.isVideoFile && item.durationMs > 0 -> Fmt.duration(item.durationMs)
                    item.isVideoFile -> "VIDEO"
                    item.isRaw || item.isHeif || item.isAvif -> item.format
                    else -> null
                }
                if (badgeText != null && !tiny) {
                    if (h.badge.text != badgeText) h.badge.text = badgeText
                    h.badge.visibility = View.VISIBLE
                } else {
                    h.badge.visibility = View.GONE
                }

                h.fav.visibility =
                    if (!tiny && MetaStore.isFavorite(item.uri)) View.VISIBLE else View.GONE
                h.iconOverlay.visibility =
                    if (!tiny && item.isVideoFile) View.VISIBLE else View.GONE
                if (h.iconOverlay.visibility == View.VISIBLE) {
                    h.iconOverlay.setImageResource(R.drawable.ic_play)
                }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_BANNER = 2

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
        }

        /**
         * Baut die Zeilenliste (Überschriften + Fotos). Läuft bewusst im Hintergrund:
         * bei 20 000+ Fotos kostet das Aufbauen und Formatieren sonst sichtbar Zeit
         * im Haupt-Thread – genau das war die Ursache für träges Scrollen.
         */
        fun buildRows(
            list: List<MediaItem>,
            withHeaders: Boolean,
            grouper: Grouper?,
            banner: Boolean
        ): List<Row> {
            val rows = ArrayList<Row>(list.size + 8)
            if (banner) rows += Row.Banner
            var currentKey: String? = null
            for (item in list) {
                if (withHeaders && grouper != null) {
                    val key = grouper.keyOf(item)
                    if (key != null && key != currentKey) {
                        currentKey = key
                        val (title, sub) = grouper.headerOf(item)
                        rows += Row.Header(title, sub)
                    }
                }
                rows += Row.Entry(item)
            }
            ViewState.viewList = list
            return rows
        }
    }
}
