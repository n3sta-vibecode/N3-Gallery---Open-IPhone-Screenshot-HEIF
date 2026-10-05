package com.n3vibecode.gallery.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.image.ImageLoader
import com.n3vibecode.gallery.util.Fmt

/** Von der Detailansicht genutzte, aktuell sichtbare Liste. */
object ViewState {
    var viewList: List<MediaItem> = emptyList()
}

/**
 * Raster-Adapter im Stil von Apple Fotos / Google Fotos: quadratische Kacheln,
 * Formatecke unten links (RAW/HEIC/AVIF), Videolänge, Favoriten-Herz und optional
 * Überschriften (Tag/Fomat), die über alle Spalten spannen.
 */
class GridAdapter(
    spanCount: Int,
    private var withHeaders: Boolean,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Spalten pro Zeile – ändert sich beim Zoomen mit zwei Fingern. */
    var spanCount: Int = spanCount
        private set

    fun setSpan(newSpan: Int) {
        if (newSpan == spanCount) return
        spanCount = newSpan.coerceAtLeast(1)
        notifyDataSetChanged()
    }

    /** Überschriften (Tag/Monat/Jahr) an- oder abschalten. */
    fun setHeaders(show: Boolean) {
        withHeaders = show
    }

    private sealed class Row {
        data class Header(val title: String, val sub: String) : Row()
        object Banner : Row()
        data class Entry(val item: MediaItem, val index: Int) : Row()
    }

    fun submit(list: List<MediaItem>, groupOf: ((MediaItem) -> Pair<String, String>?)? = null, banner: Boolean = false) {
        val newRows = mutableListOf<Row>()
        if (banner) newRows += Row.Banner
        var currentHeader: String? = null
        list.forEachIndexed { index, item ->
            if (withHeaders && groupOf != null) {
                val g = groupOf(item)
                if (g != null && g.first != currentHeader) {
                    currentHeader = g.first
                    newRows += Row.Header(g.first, g.second)
                }
            }
            newRows += Row.Entry(item, index)
        }
        rows = newRows
        ViewState.viewList = list
        notifyDataSetChanged()
    }

    private var rows: List<Row> = emptyList()
    private var thumbPx: Int = 512


    override fun getItemCount(): Int = rows.size

    /** Überschriften und Banner füllen die ganze Zeile (alle Spalten). */
    fun isFullSpan(position: Int): Boolean =
        position in rows.indices && rows[position] !is Row.Entry

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // Anfrage der wiederverwendeten Kachel verwerfen – der Platz geht an sichtbare Fotos
        if (holder is ItemHolder) ImageLoader.cancel(holder.image)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Banner -> TYPE_BANNER
        else -> TYPE_ITEM
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvTitle)
        val sub: TextView = view.findViewById(R.id.tvSub)
    }

    class BannerHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.bannerImage)
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val badge: TextView = view.findViewById(R.id.badge)
        val fav: ImageView = view.findViewById(R.id.fav)
        val iconOverlay: ImageView = view.findViewById(R.id.iconOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_timeline_header, parent, false))
        } else if (viewType == TYPE_BANNER) {
            BannerHolder(inflater.inflate(R.layout.item_banner, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_media_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Banner -> {
                val h = holder as BannerHolder
                h.itemView.setOnClickListener {
                    (h.itemView.context as? MainActivity)?.showAboutDialog()
                }
            }
            is Row.Header -> {
                val h = holder as HeaderHolder
                h.title.text = row.title
                h.sub.text = row.sub
            }
            is Row.Entry -> {
                val h = holder as ItemHolder
                val item = row.item
                // Vorschaugröße passend zur aktuellen Spaltenzahl (wird beim Zoomen neu berechnet)
                val dm = holder.itemView.resources.displayMetrics
                val w = (dm.widthPixels - 24 * dm.density) / spanCount.coerceAtLeast(1)
                thumbPx = (w * 1.3f).toInt().coerceIn(64, 1024)
                // Bei sehr vielen Spalten sind Kacheln winzig – Beschriftungen weg, sonst unlesbar
                val tiny = spanCount >= 12
                // Abstand zwischen den Kacheln: bei winzigen Kacheln nur 1 px, damit nichts verschenkt wird
                val pad = if (tiny) 1 else (dm.density).toInt().coerceAtLeast(1)
                h.itemView.setPadding(pad, pad, pad, pad)
                // Keine Kachel ohne Zustand: ImageLoader zeigt sofort eine vorhandene Stufe
                // oder den Platzhalter und lädt dann nach – das Bild wird NICHT vorher geleert.
                ImageLoader.into(holder.itemView.context, item, thumbPx, h.image)

                val badgeText = when {
                    item.isVideoFile && item.durationMs > 0 -> Fmt.duration(item.durationMs)
                    item.isVideoFile -> "VIDEO"
                    item.isRaw || item.isHeif || item.isAvif -> item.format
                    else -> null
                }
                if (badgeText != null && !tiny) {
                    h.badge.text = badgeText
                    h.badge.visibility = View.VISIBLE
                } else {
                    h.badge.visibility = View.GONE
                }

                h.fav.visibility =
                    if (!tiny && MetaStore.isFavorite(item.uri)) View.VISIBLE else View.GONE
                h.iconOverlay.visibility =
                    if (!tiny && item.isVideoFile) View.VISIBLE else View.GONE
                h.iconOverlay.setImageResource(R.drawable.ic_play)

                holder.itemView.setOnClickListener { onItemClick(row.index) }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        const val TYPE_BANNER = 2
    }
}
