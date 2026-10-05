package com.n3vibecode.gallery.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MediaKind
import com.n3vibecode.gallery.data.MediaSaver
import com.n3vibecode.gallery.data.Formats
import com.n3vibecode.gallery.image.Decoder
import com.n3vibecode.gallery.widget.EditorMode
import com.n3vibecode.gallery.widget.PhotoEditorView
import com.n3vibecode.gallery.widget.TextAnno
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Foto-Editor: **zuschneiden, zeichnen, Textfelder** – plus Speichern (neue Kopie oder
 * Original ersetzen). Erreichbar über das Stift-Symbol in der Großansicht und über
 * langes Drücken auf eine Kachel.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var editor: PhotoEditorView
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var hint: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var cropOptions: View
    private lateinit var drawOptions: View
    private lateinit var textOptions: View
    private lateinit var selectionActions: View
    private lateinit var btnModeCrop: MaterialButton
    private lateinit var btnModeDraw: MaterialButton
    private lateinit var btnModeText: MaterialButton

    private var item: MediaItem? = null
    private var currentMode = EditorMode.VIEW
    private var drawColor = Color.parseColor("#FF3B30")
    private var textColor = Color.WHITE
    private var pendingOverwrite = false

    /** Farbpalette: kräftig, aber auch Schwarz/Weiß für Dokumente. */
    private val palette = intArrayOf(
        Color.parseColor("#FFFFFFFF"),
        Color.parseColor("#FF111111"),
        Color.parseColor("#FFFF3B30"),
        Color.parseColor("#FFFF9500"),
        Color.parseColor("#FFFFCC00"),
        Color.parseColor("#FF34C759"),
        Color.parseColor("#FF007AFF"),
        Color.parseColor("#FFAF52DE"),
        Color.parseColor("#FFFF2D55"),
        Color.parseColor("#FF8E8E93")
    )

    /** Schreibberechtigung (nur Android 8/9 nötig) – danach wird direkt gespeichert. */
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val media = item ?: return@registerForActivityResult
        if (granted) {
            if (pendingOverwrite) doOverwrite(media) else doSaveCopy(media)
        } else {
            toast(getString(R.string.editor_no_write_permission))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val media = item
        if (result.resultCode == RESULT_OK && pendingOverwrite && media != null) {
            doOverwrite(media)
        } else {
            pendingOverwrite = false
            toast(getString(R.string.editor_save_failed, getString(R.string.write_request_denied)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        editor = findViewById(R.id.editor)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.tvEditorHint)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        cropOptions = findViewById(R.id.cropOptions)
        drawOptions = findViewById(R.id.drawOptions)
        textOptions = findViewById(R.id.textOptions)
        selectionActions = findViewById(R.id.textSelectionActions)
        btnModeCrop = findViewById(R.id.btnModeCrop)
        btnModeDraw = findViewById(R.id.btnModeDraw)
        btnModeText = findViewById(R.id.btnModeText)

        val uri = intent.getStringExtra(EXTRA_URI)
        val media = uri?.let { DataHub.find(it) ?: fallbackItem(it) }
        item = media
        if (media == null) {
            toast(getString(R.string.editor_load_failed))
            finish()
            return
        }
        if (media.isVideoFile) {
            toast(getString(R.string.editor_video))
            finish()
            return
        }
        findViewById<TextView>(R.id.tvEditorTitle).text = media.name

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { confirmDiscard() }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { askSave() }
        btnUndo.setOnClickListener { editor.undo(); syncButtons() }
        btnRedo.setOnClickListener { editor.redo(); syncButtons() }
        btnModeCrop.setOnClickListener { setMode(EditorMode.CROP) }
        btnModeDraw.setOnClickListener { setMode(EditorMode.DRAW) }
        btnModeText.setOnClickListener { setMode(EditorMode.TEXT) }
        findViewById<MaterialButton>(R.id.btnModeReset).setOnClickListener { confirmReset() }

        editor.onChanged = { syncButtons() }
        editor.onTextRequest = { x, y -> askText("") { text -> editor.addText(text, x, y) } }
        editor.onTextTapped = { syncSelection() }
        editor.onTextEditRequest = { anno -> askText(anno.text) { text -> editor.replaceText(anno, anno.copy(text = text)) } }

        setupCropOptions()
        setupDrawOptions()
        setupTextOptions()
        syncButtons()
        loadBitmap(media)
    }

    override fun onBackPressed() {
        confirmDiscard()
    }

    // ------------------------------------------------------------------ Laden

    private fun loadBitmap(media: MediaItem) {
        progress.visibility = View.VISIBLE
        hint.text = getString(R.string.editor_loading)
        hint.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                // Möglichst hoch auflösen: ein 1:1-Zuschnitt ist sonst viel weicher als das Original
                runCatching { Decoder.decodeDetailed(applicationContext, media, EDIT_PX).bitmap }.getOrNull()
            }
            progress.visibility = View.GONE
            if (bmp == null) {
                hint.text = getString(R.string.editor_load_failed)
                return@launch
            }
            editor.source = bmp
            if (currentMode == EditorMode.VIEW) setMode(EditorMode.DRAW) else showHint()
            syncButtons()
        }
    }

    // ------------------------------------------------------------------ Werkzeuge

    private fun setMode(mode: EditorMode) {
        currentMode = mode
        editor.mode = mode
        if (mode == EditorMode.CROP) editor.startCrop()
        cropOptions.visibility = if (mode == EditorMode.CROP) View.VISIBLE else View.GONE
        drawOptions.visibility = if (mode == EditorMode.DRAW) View.VISIBLE else View.GONE
        textOptions.visibility = if (mode == EditorMode.TEXT) View.VISIBLE else View.GONE
        markActive(btnModeCrop, mode == EditorMode.CROP)
        markActive(btnModeDraw, mode == EditorMode.DRAW)
        markActive(btnModeText, mode == EditorMode.TEXT)
        syncSelection()
        showHint()
    }

    private fun markActive(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (active) Color.parseColor("#33FFFFFF") else Color.TRANSPARENT
        )
    }

    private fun showHint() {
        val text = when (currentMode) {
            EditorMode.CROP -> getString(R.string.editor_hint_crop)
            EditorMode.DRAW -> getString(R.string.editor_hint_draw)
            EditorMode.TEXT -> getString(R.string.editor_hint_text)
            else -> null
        }
        if (text.isNullOrBlank()) {
            hint.visibility = View.GONE
            return
        }
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.animate().cancel()
        hint.alpha = 1f
        hint.postDelayed({
            if (isFinishing) return@postDelayed
            hint.animate().alpha(0f).setDuration(400).start()
        }, 4000)
    }

    private fun setupCropOptions() {
        val free = findViewById<Chip>(R.id.chipAspectFree)
        val a1 = findViewById<Chip>(R.id.chipAspect1x1)
        val a43 = findViewById<Chip>(R.id.chipAspect4x3)
        val a34 = findViewById<Chip>(R.id.chipAspect3x4)
        val a169 = findViewById<Chip>(R.id.chipAspect16x9)
        val a916 = findViewById<Chip>(R.id.chipAspect9x16)
        free.isChecked = true
        free.setOnClickListener { selectAspect(free, null) }
        a1.setOnClickListener { selectAspect(a1, 1f) }
        a43.setOnClickListener { selectAspect(a43, 4f / 3f) }
        a34.setOnClickListener { selectAspect(a34, 3f / 4f) }
        a169.setOnClickListener { selectAspect(a169, 16f / 9f) }
        a916.setOnClickListener { selectAspect(a916, 9f / 16f) }
        findViewById<MaterialButton>(R.id.btnCropReset).setOnClickListener {
            editor.resetCrop()
            syncButtons()
        }
    }

    private fun selectAspect(chip: Chip, ratio: Float?) {
        val group = listOf(
            R.id.chipAspectFree, R.id.chipAspect1x1, R.id.chipAspect4x3,
            R.id.chipAspect3x4, R.id.chipAspect16x9, R.id.chipAspect9x16
        )
        group.forEach { id -> findViewById<Chip>(id).isChecked = id == chip.id }
        editor.applyAspect(ratio)
        syncButtons()
    }

    private fun setupDrawOptions() {
        findViewById<LinearLayout>(R.id.drawColors).let { row ->
            addSwatches(row, drawColor) { color ->
                drawColor = color
                editor.drawColor = color
            }
        }
        findViewById<SeekBar>(R.id.seekStroke).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    updateStroke(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            updateStroke(progress)
        }
    }

    private fun updateStroke(value: Int) {
        val bmp = editor.source ?: return
        val minDim = min(bmp.width, bmp.height).toFloat()
        editor.strokeWidth = (minDim * (value + 2) / 320f).coerceIn(1.5f, minDim / 8f)
    }

    private fun setupTextOptions() {
        findViewById<LinearLayout>(R.id.textColors).let { row ->
            addSwatches(row, textColor) { color ->
                textColor = color
                editor.textColor = color
                syncSelection()
            }
        }
        findViewById<SeekBar>(R.id.seekText).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    updateTextSize(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            updateTextSize(progress)
        }
        findViewById<MaterialButton>(R.id.btnAddText).setOnClickListener {
            askText("") { text -> editor.addText(text) }
        }
        findViewById<MaterialButton>(R.id.btnEditText).setOnClickListener {
            val sel = editor.selectedText() ?: return@setOnClickListener
            askText(sel.text) { text -> editor.replaceText(sel, sel.copy(text = text)) }
        }
        findViewById<MaterialButton>(R.id.btnDuplicateText).setOnClickListener {
            editor.selectedText()?.let { editor.duplicateText(it) }
            syncSelection()
        }
        findViewById<MaterialButton>(R.id.btnDeleteText).setOnClickListener {
            editor.selectedText()?.let { editor.deleteText(it) }
            syncSelection()
        }
    }

    private fun updateTextSize(value: Int) {
        val bmp = editor.source ?: return
        val minDim = min(bmp.width, bmp.height).toFloat()
        editor.textSize = (minDim * (value + 2) / 150f).coerceIn(minDim / 30f, minDim / 2.2f)
    }

    private fun addSwatches(row: LinearLayout, initial: Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        val size = (30 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        val views = mutableListOf<View>()
        palette.forEach { color ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = margin
                    marginEnd = margin
                    topMargin = margin
                    bottomMargin = margin
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#66FFFFFF"))
                }
                setOnClickListener {
                    onPick(color)
                    views.forEach { v -> v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f }
                    scaleX = 1.25f
                    scaleY = 1.25f
                }
            }
            views += dot
            row.addView(dot)
        }
        // Startfarbe markieren
        val index = palette.indexOf(initial)
        if (index >= 0) {
            views[index].scaleX = 1.25f
            views[index].scaleY = 1.25f
        }
    }

    private fun syncSelection() {
        selectionActions.visibility =
            if (currentMode == EditorMode.TEXT && editor.selectedText() != null) View.VISIBLE else View.GONE
    }

    private fun syncButtons() {
        btnUndo.isEnabled = editor.canUndo()
        btnUndo.alpha = if (btnUndo.isEnabled) 1f else 0.35f
        btnRedo.isEnabled = editor.canRedo()
        btnRedo.alpha = if (btnRedo.isEnabled) 1f else 0.35f
        syncSelection()
    }

    // ------------------------------------------------------------------ Text eingeben

    private fun askText(initial: String, onDone: (String) -> Unit) {
        val edit = EditText(this).apply {
            hint = getString(R.string.editor_text_hint)
            setText(initial)
            setSelection(text.length)
            setSingleLine(false)
            maxLines = 4
            setTextColor(Color.BLACK)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_add_text)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotEmpty()) onDone(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ Speichern

    private fun askSave() {
        val media = item ?: return
        val changed = editor.hasChanges()
        val overwritePossible = changed && MediaSaver.canOverwrite(media)
        val labels = if (overwritePossible) {
            arrayOf(getString(R.string.editor_save_copy), getString(R.string.editor_save_overwrite))
        } else {
            arrayOf(getString(R.string.editor_save_copy))
        }
        val message = buildString {
            if (!changed) {
                append(getString(R.string.editor_nothing_to_save))
                append("\n\n")
            }
            append(getString(R.string.editor_save_copy_hint))
            if (overwritePossible) {
                append("\n\n")
                append(getString(R.string.editor_save_overwrite_hint))
            } else if (changed && !media.isVideoFile && media.ext.lowercase() !in
                setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp")
            ) {
                append("\n\n")
                append(getString(R.string.editor_not_writable))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_save_title)
            .setMessage(message)
            .setItems(labels) { _, which ->
                if (which == 0) doSaveCopy(media) else doOverwrite(media)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doSaveCopy(media: MediaItem) {
        // Android 8/9 braucht zum Schreiben in den öffentlichen Bilder-Ordner eine Freigabe.
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            writePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = editor.renderResult()
                if (bmp == null) {
                    SaveOutcome.RenderFailed
                } else {
                    // Fotos als JPEG (klein), PNG nur bei Original-PNG
                    val jpeg = media.ext.lowercase() != "png"
                    val res = MediaSaver.saveCopy(applicationContext, bmp, media.name, jpeg)
                    bmp.recycle()
                    SaveOutcome.Done(res)
                }
            }
            progress.visibility = View.GONE
            if (result is SaveOutcome.RenderFailed) {
                toast(getString(R.string.editor_render_failed))
                return@launch
            }
            when ((result as SaveOutcome.Done).result) {
                is MediaSaver.Result.Success -> {
                    toast(getString(R.string.editor_saved_copy, result.name))
                    DataHub.requestRescan()
                    reportChanged()
                    finish()
                }
                is MediaSaver.Result.Failed -> toast(getString(R.string.editor_save_failed, result.message))
                else -> toast(getString(R.string.editor_save_failed, ""))
            }
        }
    }

    private fun doOverwrite(media: MediaItem) {
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingOverwrite = true
            writePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = editor.renderResult()
                if (bmp == null) {
                    SaveOutcome.RenderFailed
                } else {
                    val res = MediaSaver.overwrite(applicationContext, media, bmp)
                    bmp.recycle()
                    SaveOutcome.Done(res)
                }
            }
            progress.visibility = View.GONE
            if (result is SaveOutcome.RenderFailed) {
                toast(getString(R.string.editor_render_failed))
                return@launch
            }
            when ((result as SaveOutcome.Done).result) {
                is MediaSaver.Result.Success -> {
                    pendingOverwrite = false
                    com.n3vibecode.gallery.image.ImageLoader.clearAll(applicationContext)
                    toast(getString(R.string.editor_saved_original))
                    DataHub.requestRescan()
                    reportChanged()
                    finish()
                }
                is MediaSaver.Result.NeedsPermission -> {
                    pendingOverwrite = true
                    try {
                        permissionLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                    } catch (t: Throwable) {
                        pendingOverwrite = false
                        toast(getString(R.string.editor_save_failed, t.message ?: ""))
                    }
                }
                is MediaSaver.Result.Failed -> {
                    pendingOverwrite = false
                    toast(getString(R.string.editor_save_failed, result.message))
                }
                else -> {
                    pendingOverwrite = false
                    toast(getString(R.string.editor_not_writable))
                }
            }
        }
    }

    private fun confirmReset() {
        if (!editor.hasChanges()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_reset_all)
            .setMessage(R.string.editor_discard_msg)
            .setPositiveButton(R.string.editor_discard) { _, _ -> editor.resetEdits() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDiscard() {
        if (!editor.hasChanges()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_discard_msg)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Ergebnis eines Speicherversuchs (inkl. „Bild nicht aufbereitbar“). */
    private sealed class SaveOutcome {
        class Done(val result: MediaSaver.Result) : SaveOutcome()
        object RenderFailed : SaveOutcome()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** Meldet der Großansicht, dass ein Bild neu gezeichnet werden muss. */
    private fun reportChanged() {
        setResult(RESULT_OK)
    }

    /** Notnagel, falls das Foto nicht in der Liste steht (z. B. „Öffnen mit“). */
    private fun fallbackItem(uri: String): MediaItem? = try {
        val ext = Formats.extOf(uri)
        MediaItem(
            key = uri,
            uri = uri,
            name = uri.substringAfterLast('/'),
            mime = if (ext == "png") "image/png" else "image/jpeg",
            ext = ext,
            kind = MediaKind.PHOTO,
            size = 0,
            width = 0,
            height = 0,
            takenAt = 0,
            modifiedAt = 0,
            bucket = "",
            path = uri
        )
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val EXTRA_URI = "edit_uri"

        /** Auflösung der Bearbeitungsvorlage (lang genug für 1:1-Zuschnitte, klein genug für den Speicher). */
        private const val EDIT_PX = 3600

        fun start(context: Context, uri: String) {
            context.startActivity(
                Intent(context, EditorActivity::class.java).putExtra(EXTRA_URI, uri)
            )
        }
    }
}
