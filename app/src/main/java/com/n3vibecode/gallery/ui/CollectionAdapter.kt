package com.n3vibecode.gallery.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.image.ImageLoader

sealed class ListEntry {
    data class Section(val title: String) : ListEntry()
    data class Row(
        val title: String,
        val sub: String,
        val formats: String,
        val cover: MediaItem?,
        val treeUri: String? = null,
        val removable: Boolean = false,
        val isAdd: Boolean = false,
        val key: String = ""
    ) : ListEntry()
}

/** Listen für Tags/Alben, Ordner und Formate – Optik wie in Apple Fotos. */
class CollectionAdapter(private val onClick: (ListEntry.Row) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val differ = AsyncListDiffer(this, DIFF)

    /** Nur die Unterschiede übernehmen – die Liste wird nicht mehr komplett neu gebaut. */
    fun submit(list: List<ListEntry>) {
        differ.submitList(list)
    }

    private val entries: List<ListEntry> get() = differ.currentList

    override fun getItemCount(): Int = differ.currentList.size

    override fun getItemViewType(position: Int): Int = when (val e = entries[position]) {
        is ListEntry.Section -> T_SECTION
        is ListEntry.Row -> if (e.isAdd || e.removable) T_FOLDER else T_ROW
    }

    class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvTitle)
    }

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val sub: TextView = view.findViewById(R.id.tvSub)
        val formats: TextView = view.findViewById(R.id.tvFormats)
    }

    class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val sub: TextView = view.findViewById(R.id.tvSub)
        val remove: ImageButton = view.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_SECTION -> SectionHolder(inflater.inflate(R.layout.item_timeline_header, parent, false))
            T_FOLDER -> FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
            else -> RowHolder(inflater.inflate(R.layout.item_collection, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val e = entries[position]) {
            is ListEntry.Section -> (holder as SectionHolder).title.text = e.title
            is ListEntry.Row -> {
                if (holder is FolderHolder) {
                    holder.title.text = e.title
                    holder.sub.text = e.sub
                    holder.icon.setImageResource(if (e.isAdd) R.drawable.ic_folder else R.drawable.ic_folder)
                    holder.remove.visibility = if (e.removable) View.VISIBLE else View.GONE
                    holder.remove.setOnClickListener { onClick(e) }
                    holder.itemView.setOnClickListener { onClick(e) }
                } else if (holder is RowHolder) {
                    holder.title.text = e.title
                    holder.sub.text = e.sub
                    holder.formats.text = e.formats
                    holder.formats.visibility = if (e.formats.isBlank()) View.GONE else View.VISIBLE
                    holder.cover.setImageDrawable(null)
                    e.cover?.let { c -> ImageLoader.into(holder.itemView.context, c, 256, holder.cover) }
                    holder.itemView.setOnClickListener { onClick(e) }
                }
            }
        }
    }

    private companion object {
        const val T_SECTION = 0
        const val T_ROW = 1
        const val T_FOLDER = 2

        val DIFF = object : DiffUtil.ItemCallback<ListEntry>() {
            override fun areItemsTheSame(oldItem: ListEntry, newItem: ListEntry): Boolean = when {
                oldItem is ListEntry.Section && newItem is ListEntry.Section -> oldItem.title == newItem.title
                oldItem is ListEntry.Row && newItem is ListEntry.Row ->
                    oldItem.title == newItem.title && oldItem.treeUri == newItem.treeUri && oldItem.isAdd == newItem.isAdd
                else -> false
            }

            override fun areContentsTheSame(oldItem: ListEntry, newItem: ListEntry): Boolean = oldItem == newItem
        }
    }
}
