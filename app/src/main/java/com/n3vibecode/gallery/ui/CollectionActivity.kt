package com.n3vibecode.gallery.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.util.Fmt

/** Zeigt eine Sammlung (Tag, Album, Ordner, Format) als Raster. */
class CollectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: "tag"
        val value = intent.getStringExtra(EXTRA_VALUE).orEmpty()

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val items: List<MediaItem> = when (mode) {
            "tag" -> {
                val uris = MetaStore.itemsForTag(value).toSet()
                DataHub.all.filter { uris.contains(it.uri) }
            }
            "notes" -> DataHub.all.filter { MetaStore.note(it.uri).isNotBlank() }
            "favorites" -> DataHub.all.filter { MetaStore.isFavorite(it.uri) }
            "bucket" -> DataHub.all.filter { it.bucket == value }
            "family" -> DataHub.all.filter { it.family == value }
            else -> DataHub.all
        }

        toolbar.title = when (mode) {
            "tag" -> "Tag: $value"
            "notes" -> "Mit Notiz"
            "favorites" -> "Favoriten"
            "bucket" -> value
            else -> value
        }
        toolbar.subtitle = buildString {
            append(getString(R.string.count_files, items.size))
            val size = items.sumOf { it.size }
            if (size > 0) append(" · ").append(Fmt.bytes(size))
            val fmts = items.map { it.format }.distinct().take(8)
            if (fmts.isNotEmpty()) append(" · ").append(fmts.joinToString(", "))
        }

        val host = findViewById<android.widget.FrameLayout>(R.id.content)
        val recycler = RecyclerView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(12), 0, dp(12), dp(24))
            clipToPadding = false
        }
        host.addView(recycler)
        recycler.layoutManager = GridLayoutManager(this, 3)
        val adapter = GridAdapter(3, false) { index ->
            startActivity(
                Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_POSITION, index)
            )
        }
        recycler.adapter = adapter
        adapter.submit(items)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_VALUE = "value"
    }
}
