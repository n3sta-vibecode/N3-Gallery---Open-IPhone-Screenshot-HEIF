package com.n3vibecode.gallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.ExifRepository
import com.n3vibecode.gallery.data.ExifWriter
import com.n3vibecode.gallery.data.Labels
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MediaMeta
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.image.ImageLoader
import com.n3vibecode.gallery.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vollbild-Seite mit allen Metadaten – wie in Google Fotos.
 *
 * Sie ist bewusst **kein** Bottom-Sheet: Die Liste scrollt ganz normal, respektiert die
 * System-/Gestenleiste und ist damit garantiert vollständig nach unten zu scrollen.
 * Erreichbar über das Info-Blatt („Metadaten-Seite“) oder einen Doppeltipp auf ℹ️.
 */
class InfoActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private var item: MediaItem? = null
    private var meta: MediaMeta? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val current = item ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val pending = MetaStore.pendingFor(current.uri)
            write(current, pending?.first ?: MetaStore.note(current.uri), pending?.second ?: MetaStore.tags(current.uri), true)
        } else {
            Toast.makeText(this, R.string.write_request_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val sidecarLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/rdf+xml")
    ) { uri ->
        val current = item ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            val xmp = ExifWriter.buildSidecar(current, MetaStore.note(current.uri), MetaStore.tags(current.uri))
            contentResolver.openOutputStream(uri)?.use { it.write(xmp.toByteArray()) }
            Toast.makeText(this, R.string.sidecar_saved, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.sidecar_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meta)

        container = findViewById(R.id.metaContainer)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        val scroll = findViewById<NestedScrollView>(R.id.scroll)

        toolbar.setNavigationOnClickListener { finish() }

        // Rand: oben Platz für die Statusleiste, unten für Navigations-/Gestenleiste.
        // Dadurch ist der letzte Eintrag immer sichtbar – die Liste scrollt ganz normal.
        val root = findViewById<android.view.View>(R.id.metaRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        // Scrollbereich: freies, flüssiges Scrollen (kein Ziehen, kein Einrasten)
        scroll.isNestedScrollingEnabled = true
        scroll.isFillViewport = true

        val position = intent.getIntExtra(EXTRA_POSITION, -1)
        val fromList = if (position >= 0) ViewState.viewList.getOrNull(position) else null
        item = fromList ?: intent.getStringExtra(EXTRA_URI)?.let { DataHub.find(it) } ?: ViewState.viewList.firstOrNull()

        val current = item
        if (current == null) {
            finish()
            return
        }

        toolbar.title = current.name
        toolbar.subtitle = current.shortInfo() + " · " + Fmt.dateTime(current.time)
        if (current.isHeif) {
            toolbar.subtitle = getString(R.string.heic_badge) + " · " + toolbar.subtitle
        } else if (current.isAvif) {
            toolbar.subtitle = getString(R.string.heic_badge_avif) + " · " + toolbar.subtitle
        }

        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_fav -> {
                    val now = MetaStore.toggleFavorite(current.uri)
                    menuItem.setIcon(if (now) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
                    Toast.makeText(this, if (now) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_share_file -> {
                    shareFile(current)
                    true
                }
                else -> false
            }
        }

        findViewById<MaterialButton>(R.id.btnNote).setOnClickListener { openNoteDialog(current) }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener { copyAll() }
        findViewById<MaterialButton>(R.id.btnShareText).setOnClickListener { shareText() }

        load(current)
    }

    private fun load(current: MediaItem) {
        MetaRenderer.render(this, container)
        progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { ExifRepository.load(applicationContext, current) }
            meta = loaded
            progress.visibility = android.view.View.GONE
            MetaRenderer.render(this@InfoActivity, container, loaded, true)
        }
    }

    // ------------------------------------------------------------------ Notizen

    private fun openNoteDialog(current: MediaItem) {
        NoteDialog.show(
            activity = this,
            item = current,
            note = MetaStore.note(current.uri),
            tags = MetaStore.tags(current.uri),
            onSave = { note, tags, writeToFile ->
                if (!writeToFile) {
                    MetaStore.setNote(current.uri, note)
                    MetaStore.setTags(current.uri, tags)
                    Toast.makeText(this, R.string.saved_internal, Toast.LENGTH_SHORT).show()
                    load(current)
                } else {
                    write(current, note, tags, false)
                }
            },
            onSidecar = { sidecarLauncher.launch(current.name.substringBeforeLast('.') + ".xmp") }
        )
    }

    private fun write(current: MediaItem, note: String, tags: List<String>, retried: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ExifWriter.save(applicationContext, current, note, tags) }
            when (result) {
                is ExifWriter.Result.Success -> {
                    Toast.makeText(this@InfoActivity, R.string.saved_to_file, Toast.LENGTH_SHORT).show()
                    ImageLoader.clear()
                    load(current)
                }
                is ExifWriter.Result.NeedsPermission -> {
                    if (retried) {
                        Toast.makeText(this@InfoActivity, R.string.write_request_denied, Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            permissionLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                        } catch (t: Throwable) {
                            Toast.makeText(this@InfoActivity, t.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                is ExifWriter.Result.Unsupported -> {
                    MaterialAlertDialogBuilder(this@InfoActivity)
                        .setTitle(R.string.format_not_writable)
                        .setMessage(result.reason + "\n\n" + getString(R.string.not_writable))
                        .setPositiveButton(R.string.save_sidecar) { _, _ ->
                            sidecarLauncher.launch(current.name.substringBeforeLast('.') + ".xmp")
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
                is ExifWriter.Result.Failed -> {
                    Toast.makeText(this@InfoActivity, getString(R.string.save_failed, result.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ Teilen / Kopieren

    private fun copyAll() {
        val loaded = meta
        if (loaded == null) {
            Toast.makeText(this, R.string.meta_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(
            ClipData.newPlainText(
                "EXIF " + loaded.item.name,
                loaded.asPlainText { Labels.tr(this@InfoActivity, it) }
            )
        )
        Toast.makeText(this, R.string.exif_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText() {
        val loaded = meta ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, loaded.item.name)
            putExtra(Intent.EXTRA_TEXT, loaded.asPlainText { Labels.tr(this@InfoActivity, it) })
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_text)))
    }

    private fun shareFile(current: MediaItem) {
        try {
            val uri = Uri.parse(current.uri)
            val shareUri: Uri = if (uri.scheme == "content") {
                uri
            } else {
                val target = File(com.n3vibecode.gallery.GalleryApp.shareDir(), current.name)
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                FileProvider.getUriForFile(this, packageName + ".fileprovider", target)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (current.mime.isNotBlank()) current.mime else "image/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.share_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_POSITION = "position"
        const val EXTRA_URI = "uri"

        fun intentFor(context: Context, position: Int, uri: String? = null): Intent =
            Intent(context, InfoActivity::class.java).apply {
                putExtra(EXTRA_POSITION, position)
                if (uri != null) putExtra(EXTRA_URI, uri)
            }
    }
}
