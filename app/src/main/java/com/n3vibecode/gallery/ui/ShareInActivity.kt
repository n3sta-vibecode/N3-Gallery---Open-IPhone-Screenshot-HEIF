package com.n3vibecode.gallery.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.n3vibecode.gallery.GalleryApp
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import java.io.File

/**
 * **Fotos aus anderen Apps annehmen** – „Teilen“ in der Galerie, Kamera oder einem Messenger.
 *
 * Bisher konnte die App nur geöffnet werden, wenn ein anderes Programm sie aufrief; geteilte
 * Fotos landeten nirgends. Jetzt gilt:
 *
 *  • [Intent.ACTION_SEND] (ein Bild) und [Intent.ACTION_SEND_MULTIPLE] (mehrere) werden
 *    angenommen – siehe `AndroidManifest.xml`.
 *  • Angezeigt wird eine kleine Vorschau-Liste; „In Galerie speichern“ übernimmt die Dateien
 *    in den App-Ordner, „In der App öffnen“ öffnet das erste Bild groß.
 *  • Ohne Berechtigung für die Galerie funktioniert das trotzdem: Der Inhalt kommt über den
 *    `contentResolver` der anderen App, nicht aus dem Medienindex.
 */
class ShareInActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnOpen: MaterialButton

    /** Was hereinkam – für Anzeige und Übernahme. */
    private val incoming = ArrayList<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_in)

        recycler = findViewById(R.id.recycler)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvHint = findViewById(R.id.tvHint)
        btnSave = findViewById(R.id.btnSave)
        btnOpen = findViewById(R.id.btnOpen)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        readIntent(intent)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = PreviewAdapter()

        if (incoming.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            btnSave.isEnabled = false
            btnOpen.isEnabled = false
            tvHint.text = getString(R.string.share_in_failed, "kein Bild erhalten")
        }

        btnSave.setOnClickListener { save() }
        btnOpen.setOnClickListener { openFirst() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.clear()
        readIntent(intent)
        recycler.adapter?.notifyDataSetChanged()
        if (incoming.isNotEmpty()) {
            tvEmpty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            btnSave.isEnabled = true
            btnOpen.isEnabled = true
            tvHint.text = getString(R.string.share_received_hint)
        }
    }

    /** Holt die URIs aus SEND bzw. SEND_MULTIPLE (auch `ClipData` von „Teilen“-Dialogen). */
    private fun readIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) incoming.add(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                list?.forEach { if (it != null) incoming.add(it) }
            }

            Intent.ACTION_VIEW -> intent.data?.let { incoming.add(it) }
        }
        // Fallback: manche Apps setzen nur ClipData
        if (incoming.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { incoming.add(it) }
            }
        }
        // Nur Bilder, keine Dubletten
        incoming.retainAll { it.scheme != null }
        val unique = LinkedHashSet(incoming)
        incoming.clear()
        incoming.addAll(unique)
    }

    /** Dateiname aus dem Content-Resolver (oder letztes Pfadstück). */
    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "foto"

    private fun sizeOf(uri: Uri): Long =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
            }
        }.getOrNull() ?: 0L

    /** Zielordner: App-eigener Ordner „Übernommen“ (kein Zugriff auf fremde Ordner nötig). */
    private fun targetDir(): File =
        File(getExternalFilesDir(null) ?: filesDir, "Uebernommen").apply { mkdirs() }

    private fun save() {
        btnSave.isEnabled = false
        Thread({
            val dir = targetDir()
            val saved = ArrayList<File>()
            val errors = ArrayList<String>()
            for (uri in incoming) {
                val name = sanitize(displayName(uri))
                val target = uniqueFile(dir, name)
                val ok = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().buffered().use { out -> input.copyTo(out, 1 shl 16) }
                    } ?: false
                    target.length() > 0
                }.getOrDefault(false)
                if (ok) {
                    saved.add(target)
                } else {
                    target.delete()
                    errors.add(name)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnSave.isEnabled = true
                if (saved.isEmpty()) {
                    Toast.makeText(
                        this,
                        getString(R.string.share_in_failed, errors.firstOrNull() ?: "unbekannt"),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this,
                    if (saved.size == 1) getString(R.string.share_saved, saved[0].name)
                    else getString(R.string.share_saved, "${saved.size} Fotos"),
                    Toast.LENGTH_SHORT
                ).show()
                // Galerie neu einlesen lassen und das erste Foto groß zeigen
                DataHub.requestRescan()
                openSaved(saved.first())
            }
        }, "n3-share-save").apply { isDaemon = true }.start()
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (clean.contains('.')) clean else "$clean.jpg"
    }

    /** Kein Überschreiben: bei gleichem Namen wird durchnummeriert. */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "jpg")
        var i = 2
        while (f.exists() && i < 10_000) {
            f = File(dir, "${base}_$i.$ext")
            i++
        }
        return f
    }

    private fun openFirst() {
        val uri = incoming.firstOrNull() ?: return
        startActivity(
            Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_URI, uri.toString())
        )
    }

    private fun openSaved(file: File) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_URI, Uri.fromFile(file).toString())
        )
    }

    // ------------------------------------------------------------------ Vorschau

    private inner class PreviewAdapter : RecyclerView.Adapter<PreviewAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.image)
            val name: TextView = view.findViewById(R.id.badge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_media_grid, parent, false)
            )

        override fun getItemCount(): Int = incoming.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val uri = incoming[position]
            holder.name.text = displayName(uri)
            // Vorschau direkt aus dem Content-Resolver (kein Umweg über die Galerie)
            holder.image.setImageDrawable(null)
            Thread({
                val drawable: Drawable? = runCatching {
                    val input = contentResolver.openInputStream(uri) ?: return@runCatching null
                    android.graphics.drawable.Drawable.createFromStream(input, uri.toString())
                }.getOrNull()
                runOnUiThread {
                    if (holder.adapterPosition == position) holder.image.setImageDrawable(drawable)
                }
            }, "n3-share-preview").apply { isDaemon = true }.start()
        }
    }
}
