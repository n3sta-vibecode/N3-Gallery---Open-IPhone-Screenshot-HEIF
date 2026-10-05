package com.n3vibecode.gallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.ExifRepository
import com.n3vibecode.gallery.data.ExifWriter
import com.n3vibecode.gallery.data.Labels
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MediaMeta
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vollbild-Ansicht mit Wischen, Zoom-Info-Blatt und allen Metadaten.
 * Notizen/Tags werden hier als echte EXIF/XMP-Daten in die Datei geschrieben.
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var topBar: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var behavior: BottomSheetBehavior<LinearLayout>
    private lateinit var metaContainer: LinearLayout
    private lateinit var tvCounter: TextView
    private lateinit var tvSheetTitle: TextView
    private lateinit var tvSheetSub: TextView
    private lateinit var btnFav: ImageButton
    private lateinit var btnNote: MaterialButton
    private lateinit var scroll: androidx.core.widget.NestedScrollView
    private lateinit var sheetHint: TextView

    private var current: MediaItem? = null
    private var currentMeta: MediaMeta? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val item = current ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val pending = MetaStore.pendingFor(item.uri)
            val note = pending?.first ?: MetaStore.note(item.uri)
            val tags = pending?.second ?: MetaStore.tags(item.uri)
            performWrite(item, note, tags, retried = true)
        } else {
            Toast.makeText(this, R.string.write_request_denied, Toast.LENGTH_LONG).show()
        }
    }

    /** Foto-Editor (Zuschneiden, Zeichnen, Text) – nach dem Speichern neu zeichnen. */
    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            com.n3vibecode.gallery.image.ImageLoader.clearAll(applicationContext)
            currentMeta = null
            onItemShown(pager.currentItem)
        }
    }

    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/rdf+xml")
    ) { uri ->
        val item = current ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            val xmp = ExifWriter.buildSidecar(item, MetaStore.note(item.uri), MetaStore.tags(item.uri))
            contentResolver.openOutputStream(uri)?.use { it.write(xmp.toByteArray()) }
            Toast.makeText(this, R.string.sidecar_saved, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.sidecar_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        pager = findViewById(R.id.pager)
        topBar = findViewById(R.id.topBar)
        sheet = findViewById(R.id.sheet)
        metaContainer = findViewById(R.id.metaContainer)
        tvCounter = findViewById(R.id.tvCounter)
        tvSheetTitle = findViewById(R.id.tvSheetTitle)
        tvSheetSub = findViewById(R.id.tvSheetSub)
        btnFav = findViewById(R.id.btnFav)
        btnNote = findViewById(R.id.btnNote)

        scroll = findViewById(R.id.scroll)
        sheetHint = findViewById(R.id.tvSheetHint)

        // Unteren Systemabstand (Gesten-/Navigationsleiste) ergänzen, damit der letzte
        // EXIF-Eintrag niemals hinter der Navigationsleiste verschwindet.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom + MetaRenderer.dp(v.context, 28))
            insets
        }

        // Es gibt bewusst NUR zwei Zustände: ganz zu oder ganz offen.
        // Ein „halb“ angepeektes Blatt war die Ursache dafür, dass die Detail-Liste
        // Wischgesten verschluckt hat (Scrollbereich größer als der sichtbare Ausschnitt).
        behavior = BottomSheetBehavior.from(sheet)
        behavior.isHideable = true
        behavior.isDraggable = true
        behavior.skipCollapsed = true
        behavior.setPeekHeight(0, false)
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                applySheetState(newState)
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })
        applySheetState(BottomSheetBehavior.STATE_HIDDEN)

        // Kopfzeile antippen = auf-/zuklappen
        findViewById<View>(R.id.sheetHeader).setOnClickListener {
            behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_HIDDEN
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }
        // ✕ schließt das Blatt
        findViewById<ImageButton>(R.id.btnSheetClose).setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        // Griff nach unten wischen = schließen (die Liste selbst scrollt dabei frei)
        val handle = findViewById<View>(R.id.sheetHandle)
        if (handle != null) {
            val density = resources.displayMetrics.density
            var downY = 0f
            handle.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (e.rawY - downY > 40 * density) behavior.state = BottomSheetBehavior.STATE_HIDDEN
                        v.performClick()
                        true
                    }
                    else -> true
                }
            }
        }

        // Sicherstellen, dass Position und Liste zusammenpassen: Wenn wir die Bild-URI
        // kennen, bestimmen wir die Position daraus. So kann nie ein anderes Foto
        // geöffnet werden, nur weil eine andere Ansicht die Liste überschrieben hat.
        val wantedUri = intent.getStringExtra(EXTRA_URI)
        if (!wantedUri.isNullOrBlank()) {
            val current = ViewState.viewList
            val idx = current.indexOfFirst { it.uri == wantedUri }
            if (idx >= 0) {
                intent.putExtra(EXTRA_POSITION, idx)
            } else {
                // Aus einer Sammlung geöffnet, deren Liste nicht mehr gesetzt ist
                val fromLibrary = DataHub.all.indexOfFirst { it.uri == wantedUri }
                if (fromLibrary >= 0) {
                    ViewState.viewList = DataHub.all
                    intent.putExtra(EXTRA_POSITION, fromLibrary)
                } else {
                    DataHub.find(wantedUri)?.let { ViewState.viewList = listOf(it) }
                    intent.putExtra(EXTRA_POSITION, 0)
                }
            }
        }

        val list = ViewState.viewList
        if (list.isEmpty()) {
            finish()
            return
        }

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = ViewState.viewList.size
            override fun createFragment(position: Int) = MediaPageFragment.create(position)
        }
        // Nachbarfotos schon im Voraus laden – Wischen zeigt sofort das nächste Bild
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pager.isUserInputEnabled = true
                onItemShown(position)
            }
        })

        val start = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(0, list.size - 1)
        pager.setCurrentItem(start, false)
        onItemShown(start)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        // ℹ️  = Vollbild-Metadaten-Seite (immer vollständig scrollbar, kein Bottom-Sheet)
        // Langdruck = klassisches Info-Blatt
        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener {
            startActivity(InfoActivity.intentFor(this, pager.currentItem, current?.uri))
        }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener { shareCurrent() }
        btnFav.setOnClickListener { toggleFavorite() }
        btnNote.setOnClickListener { openNoteDialog() }
        // Foto bearbeiten: zuschneiden, zeichnen, Textfelder einfügen
        findViewById<ImageButton>(R.id.btnEdit).setOnClickListener { openEditor() }
        findViewById<MaterialButton>(R.id.btnPhotoEdit).setOnClickListener { openEditor() }
        // Vollbild-Metadaten-Seite (kein Bottom-Sheet) – dort ist garantiert alles scrollbar
        findViewById<MaterialButton>(R.id.btnMetaPage).setOnClickListener {
            startActivity(InfoActivity.intentFor(this, pager.currentItem, current?.uri))
        }
        // Langdruck auf ℹ️ = klassisches Info-Blatt (nach oben/unten ziehbar)
        findViewById<View>(R.id.btnInfo).setOnLongClickListener {
            toggleSheet()
            true
        }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener { copyMetadata() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }
    }

    // ------------------------------------------------------------------ Anzeige

    private fun onItemShown(position: Int) {
        val item = ViewState.viewList.getOrNull(position) ?: return
        current = item
        currentMeta = null
        tvCounter.text = buildString {
            append(item.name)
            append("   ")
            append(position + 1).append(" / ").append(ViewState.viewList.size)
        }
        tvSheetTitle.text = item.name
        tvSheetSub.text = item.shortInfo() + " · " + Fmt.dateTime(item.time)
        btnFav.setImageResource(
            if (MetaStore.isFavorite(item.uri)) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )
        MetaRenderer.render(this, metaContainer)
        loadMeta(item)
    }

    private fun loadMeta(item: MediaItem) {
        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { ExifRepository.load(applicationContext, item) }
            if (current?.uri == item.uri) {
                currentMeta = meta
                MetaRenderer.render(this@DetailActivity, metaContainer, meta)
            }
        }
    }

    /**
     * Wichtig für die Bedienung: Nur im voll aufgezogenen Zustand darf die Detail-Liste
     * selbst scrollen. Sonst ist der Scrollbereich höher als der sichtbare Ausschnitt und
     * Wischgesten „verschlucken“ sich – das Blatt lässt sich dann nicht mehr sauber ziehen.
     * In allen anderen Zuständen wandern Gesten direkt an das Blatt (auf-/zuklappen/schließen).
     */
    private fun applySheetState(newState: Int) {
        when (newState) {
            BottomSheetBehavior.STATE_EXPANDED -> {
                // Offen: Liste darf frei scrollen. Das Blatt selbst ist dann NICHT mehr ziehbar,
                // sonst kämpfen Scroll- und Ziehgeste gegeneinander (=> ruckelt / hängt).
                // Geschlossen wird über ✕, Zurück, den Griff nach unten oder Tippen auf die Kopfzeile.
                scroll.setOnTouchListener(null)
                scroll.isNestedScrollingEnabled = true
                behavior.isDraggable = false
                sheetHint.visibility = View.VISIBLE
                topBar.visibility = View.GONE
            }
            else -> {
                // Zu (HIDDEN oder Übergang): Liste gesperrt, Blatt lässt sich hochziehen.
                lockSheetScroll()
                behavior.isDraggable = true
                sheetHint.visibility = View.GONE
                topBar.visibility = View.VISIBLE
            }
        }
    }

    private fun lockSheetScroll() {
        scroll.isNestedScrollingEnabled = false
        scroll.scrollTo(0, 0)
        // Gesten nicht von der Liste schlucken lassen – das Blatt soll sie bekommen
        scroll.setOnTouchListener { _, _ -> true }
    }

    /** Zoom im Bild: Wischen zwischen Fotos abschalten, solange gezoomt wird. */
    fun setPagerInputEnabled(enabled: Boolean) {
        pager.isUserInputEnabled = enabled
    }

    fun toggleBars() {
        if (behavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        } else {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    private fun toggleSheet() {
        behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            BottomSheetBehavior.STATE_HIDDEN
        } else {
            BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onBackPressed() {
        if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun toggleFavorite() {
        val item = current ?: return
        val now = MetaStore.toggleFavorite(item.uri)
        btnFav.setImageResource(if (now) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
        Toast.makeText(this, if (now) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ Foto-Editor

    private fun openEditor() {
        val item = current ?: return
        if (item.isVideoFile) {
            Toast.makeText(this, R.string.editor_video, Toast.LENGTH_SHORT).show()
            return
        }
        if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        editorLauncher.launch(
            Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_URI, item.uri)
        )
    }

    // ------------------------------------------------------------------ Notizen

    private fun openNoteDialog() {
        val item = current ?: return
        NoteDialog.show(
            activity = this,
            item = item,
            note = MetaStore.note(item.uri),
            tags = MetaStore.tags(item.uri),
            onSave = { note, tags, writeToFile -> saveNote(item, note, tags, writeToFile) },
            onSidecar = { createDocLauncher.launch(item.name.substringBeforeLast('.') + ".xmp") }
        )
    }

    private fun saveNote(item: MediaItem, note: String, tags: List<String>, writeToFile: Boolean) {
        if (!writeToFile) {
            MetaStore.setNote(item.uri, note)
            MetaStore.setTags(item.uri, tags)
            Toast.makeText(this, R.string.saved_internal, Toast.LENGTH_SHORT).show()
            loadMeta(item)
            return
        }
        performWrite(item, note, tags, retried = false)
    }

    private fun performWrite(item: MediaItem, note: String, tags: List<String>, retried: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ExifWriter.save(applicationContext, item, note, tags)
            }
            when (result) {
                is ExifWriter.Result.Success -> {
                    Toast.makeText(this@DetailActivity, R.string.saved_to_file, Toast.LENGTH_SHORT).show()
                    // Vorschau neu zeichnen (Metadaten kommen aus der Datei)
                    com.n3vibecode.gallery.image.ImageLoader.clear()
                    loadMeta(item)
                    (this@DetailActivity).recreatePager()
                }
                is ExifWriter.Result.NeedsPermission -> {
                    if (retried) {
                        Toast.makeText(this@DetailActivity, R.string.write_request_denied, Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            permissionLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                        } catch (t: Throwable) {
                            Toast.makeText(this@DetailActivity, t.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                is ExifWriter.Result.Unsupported -> {
                    MaterialAlertDialogBuilder(this@DetailActivity)
                        .setTitle(R.string.format_not_writable)
                        .setMessage(result.reason + "\n\n" + getString(R.string.not_writable))
                        .setPositiveButton(R.string.save_sidecar) { _, _ ->
                            createDocLauncher.launch(item.name.substringBeforeLast('.') + ".xmp")
                        }
                        .setNegativeButton(R.string.close) { _, _ -> loadMeta(item) }
                        .show()
                }
                is ExifWriter.Result.Failed -> {
                    Toast.makeText(this@DetailActivity, getString(R.string.save_failed, result.message), Toast.LENGTH_LONG).show()
                    loadMeta(item)
                }
            }
        }
    }

    private fun recreatePager() {
        val position = pager.currentItem
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = ViewState.viewList.size
            override fun createFragment(position: Int) = MediaPageFragment.create(position)
        }
        pager.setCurrentItem(position, false)
    }

    // ------------------------------------------------------------------ Teilen / Kopieren / Löschen

    private fun copyMetadata() {
        val meta = currentMeta
        if (meta == null) {
            Toast.makeText(this, R.string.meta_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(
            ClipData.newPlainText(
                "EXIF " + meta.item.name,
                meta.asPlainText { Labels.tr(this@DetailActivity, it) }
            )
        )
        Toast.makeText(this, R.string.exif_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrent() {
        val item = current ?: return
        try {
            val uri = Uri.parse(item.uri)
            val shareUri: Uri = if (uri.scheme == "content") {
                uri
            } else {
                val target = File(com.n3vibecode.gallery.GalleryApp.shareDir(), item.name)
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                FileProvider.getUriForFile(this, packageName + ".fileprovider", target)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (item.mime.isNotBlank()) item.mime else "image/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_TEXT, MetaStore.note(item.uri).ifBlank { null })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.share_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val item = current ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, item.name))
            .setPositiveButton(R.string.delete) { _, _ -> deleteCurrent() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteCurrent() {
        val item = current ?: return
        val uri = Uri.parse(item.uri)
        val resolved = DataHub.find(item.uri) ?: item
        val target = if (resolved.uri.startsWith("content://")) Uri.parse(resolved.uri) else uri
        try {
            if (Build.VERSION.SDK_INT >= 29 && target.scheme == "content") {
                // Systembestätigung für das Löschen anfordern
                lifecycleScope.launch {
                    val sender: IntentSender? = withContext(Dispatchers.IO) {
                        try {
                            android.provider.MediaStore.createDeleteRequest(contentResolver, listOf(target)).intentSender
                        } catch (t: Throwable) {
                            null
                        }
                    }
                    if (sender != null) {
                        try {
                            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        } catch (t: Throwable) {
                            Toast.makeText(this@DetailActivity, getString(R.string.delete_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        directDelete(target)
                    }
                }
                return
            }
            directDelete(target)
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.delete_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, R.string.cancel, Toast.LENGTH_SHORT).show()
        }
    }

    private fun directDelete(uri: Uri) {
        try {
            if (uri.scheme == "content") {
                contentResolver.delete(uri, null, null)
            } else {
                File(uri.path ?: return).delete()
            }
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
            finish()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.delete_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_POSITION = "position"

        /** Eindeutige Kennung des Fotos – verhindert, dass ein anderes Bild geöffnet wird. */
        const val EXTRA_URI = "uri"
    }
}
