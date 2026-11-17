package com.n3vibecode.gallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
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
import com.n3vibecode.gallery.data.GeoStore
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.MediaMeta
import com.n3vibecode.gallery.data.MetaStore
import com.n3vibecode.gallery.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vollbild-Ansicht mit Wischen – aufgebaut wie die Foto-Apps von Samsung / Google / Apple:
 *
 *  • **Knöpfe unten** (Details, Bearbeiten, Teilen, Favorit, Löschen) – nicht mehr oben,
 *    weil man sie mit dem Daumen erreicht, während man das Foto hält.
 *  • **Hochwischen irgendwo im Foto** öffnet unten das Blatt „Wichtigste Angaben“:
 *    kleine Karte, Datum, Ort, Kamera, Auflösung, Format – und das Notizfeld.
 *    „Alle Metadaten“ führt zur Vollbild-Seite mit allen über 150 Feldern.
 *
 * Notizen/Tags werden hier als echte EXIF/XMP-Daten in die Datei geschrieben.
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var behavior: BottomSheetBehavior<LinearLayout>
    private lateinit var metaContainer: LinearLayout
    private lateinit var tvCounter: TextView
    private lateinit var tvSheetTitle: TextView
    private lateinit var tvSheetSub: TextView
    private lateinit var btnFav: ImageButton
    private lateinit var tvNote: TextView

    private var current: MediaItem? = null

    /** Angelegte Seiten (Position → Seite), damit die sichtbare Seite ihr Vollbild laden kann. */
    private val pages = HashMap<Int, MediaPageFragment>()
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

        // Bildansicht wirklich bildschirmfüllend – auch die obere Systemleiste aus.
        // Solange das Metadaten-Blatt offen ist, gehören die Leisten wieder dazu (applySheetState).
        runCatching { SystemBars.hideAll(this) }

        pager = findViewById(R.id.pager)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        sheet = findViewById(R.id.sheet)
        metaContainer = findViewById(R.id.metaContainer)
        tvCounter = findViewById(R.id.tvCounter)
        tvSheetTitle = findViewById(R.id.tvSheetTitle)
        tvSheetSub = findViewById(R.id.tvSheetSub)
        btnFav = findViewById(R.id.btnFav)
        tvNote = findViewById(R.id.tvNote)

        // Unteren Systemabstand (Gesten-/Navigationsleiste) ergänzen, damit der letzte
        // Eintrag des Blattes niemals hinter der Navigationsleiste verschwindet.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }

        // Es gibt bewusst NUR zwei Zustände: ganz zu oder ganz offen (Inhalt-hoch).
        // Ein „halb“ angepeektes Blatt war früher die Ursache für verschluckte Wischgesten.
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

        // Kopfzeile antippen = zu
        findViewById<View>(R.id.sheetHeader).setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        // ✕ schließt das Blatt
        findViewById<ImageButton>(R.id.btnSheetClose).setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        // Griff nach unten wischen = schließen
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
            // primary = die gerade sichtbare Seite. Nur sie lädt das Vollbild, die
            // Nachbarseiten halten die schnelle Vorschau fürs Wischen bereit.
            override fun createFragment(position: Int) =
                MediaPageFragment.create(position, position == pager.currentItem)
                    .also { pages[position] = it }
        }
        // Nachbarfotos schon im Voraus laden – Wischen zeigt sofort das nächste Bild.
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pager.isUserInputEnabled = true
                onItemShown(position)
                updatePrimary(position)
                // Nachbarn gleich mit der Zielgröße dekodieren, solange man noch schaut
                warmNeighbours(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                // Während des Wischens keine neuen Dekodiervorgänge anstoßen – das ist der
                // Unterschied zwischen „flüssig“ und „hakt beim Ziehen“.
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    warmNeighbours(pager.currentItem)
                }
            }
        })

        val start = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(0, list.size - 1)
        // **Hochwischen irgendwo im Bild öffnet die wichtigsten Metadaten (unten).**
        //
        // Eine senkrechte Wischbewegung (mehr als 40 dp nach oben, deutlich senkrechter als
        // waagerecht) öffnet das Blatt. Waagerechtes Wischen bleibt beim Bildwechsel, damit
        // „durch die Fotos blättern“ unverändert funktioniert.
        installSwipeUpForMeta()

        pager.setCurrentItem(start, false)
        onItemShown(start)
        // Nach dem Aufbau (die Seite entsteht erst mit dem Layout) das Vollbild starten
        pager.post { updatePrimary(start) }

        // Aus dem ⋮-Menü heraus „Wichtigste Angaben“ sofort zeigen (Metadaten laden, dann Dialog)
        if (intent.getBooleanExtra(EXTRA_KEY_FACTS, false)) {
            pager.postDelayed({ if (!isFinishing) showKeyFacts() }, 220)
        }

        // ---------------------------------------------------------------- Knöpfe
        // Oben: nur zurück, Name und Zähler.
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Unten (wie Samsung Gallery / Apple Fotos): Details, Bearbeiten, Teilen, Favorit, Löschen
        findViewById<View>(R.id.btnSheetInfo).setOnClickListener { openSheet() }
        findViewById<ImageButton>(R.id.btnEdit).setOnClickListener { openEditor() }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener { shareCurrent() }
        btnFav.setOnClickListener { toggleFavorite() }
        findViewById<ImageButton>(R.id.btnTrash).setOnClickListener { confirmDelete() }

        // Im Blatt: Notizfeld (antippen = Notiz & Tags bearbeiten), alle Metadaten,
        // Karte und EXIF kopieren.
        findViewById<View>(R.id.noteCard).setOnClickListener { openNoteDialog() }
        findViewById<View>(R.id.btnTags).setOnClickListener { openNoteDialog() }
        // Vollbild-Metadaten-Seite (kein Bottom-Sheet) – dort ist garantiert alles scrollbar
        findViewById<MaterialButton>(R.id.btnMetaPage).setOnClickListener {
            startActivity(InfoActivity.intentFor(this, pager.currentItem, current?.uri))
        }
        // Karte direkt: aktuelles Foto groß auf der Karte (ohne Umweg über „Details“)
        findViewById<MaterialButton>(R.id.btnMap).setOnClickListener { openMapForCurrent() }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener { copyMetadata() }
        // 1.42 – 2. Modell (Personen): Fotos mit denselben Personen finden. Die Suche-Seite
        // öffnet im Personen-Modus und bettet die Fotos mit dem Bild-Modell ein (NPU).
        findViewById<MaterialButton>(R.id.btnPersons).setOnClickListener {
            val item = current ?: return@setOnClickListener
            startActivity(
                Intent(this, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_SIMILAR_TO, item.uri)
            )
        }
    }

    // ------------------------------------------------------------------ Anzeige

    /** Nur die sichtbare Seite lädt das Vollbild – die Nachbarn bleiben bei der Vorschau. */
    private fun updatePrimary(current: Int) {
        for ((position, page) in HashMap(pages)) {
            page.setPrimary(position == current)
        }
    }

    private fun onItemShown(position: Int) {
        val item = ViewState.viewList.getOrNull(position) ?: return
        current = item
        currentMeta = null
        tvCounter.text = buildString {
            append(item.name)
            append("   ")
            append(position + 1).append(" / ").append(ViewState.viewList.size)
        }
        // 1.42: Untertitel ohne Dateiformat – beim Durchschauen steht das Format nicht
        // mehr im Weg (es steht bei Bedarf in den Metadaten unten).
        tvSheetTitle.text = item.name
        tvSheetSub.text = Fmt.dateTime(item.time)
        btnFav.setImageResource(
            if (MetaStore.isFavorite(item.uri)) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )
        updateNoteField(item)
        MetaRenderer.render(this, metaContainer)
        loadMeta(item)
    }

    /** Notizfeld im Blatt: eigener Text oder Hinweis „Notiz hinzufügen“. */
    private fun updateNoteField(item: MediaItem) {
        val note = MetaStore.note(item.uri)
        if (note.isBlank()) {
            tvNote.text = getString(R.string.note_add_hint)
            tvNote.setTextColor(androidx.core.content.ContextCompat
                .getColor(this, R.color.n3_on_surface_variant))
        } else {
            tvNote.text = note
            tvNote.setTextColor(androidx.core.content.ContextCompat
                .getColor(this, R.color.n3_on_bg))
        }
    }

    /**
     * Karte für das aktuelle Foto öffnen – mit Position direkt an der Stelle, sonst mit
     * allen Fotos (die Karte liest die Position dort im Hintergrund nach).
     */
    private fun openMapForCurrent() {
        val item = current ?: return
        val point = GeoStore.pointFor(item)
        val intent = if (point != null) MapActivity.intentFor(this, point.first, point.second)
        else android.content.Intent(this, MapActivity::class.java)
        runCatching { startActivity(intent) }
    }

    private fun loadMeta(item: MediaItem) {
        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { ExifRepository.load(applicationContext, item) }
            if (current?.uri == item.uri) {
                currentMeta = meta
                renderSheetMeta(meta)
            }
        }
    }

    /**
     * Das Blatt unten füllen: kleine Karte, dann die wichtigsten Angaben.
     * (Das große Metadaten-Blatt von früher ist bewusst weg – die Kern-Angaben
     * gehören nach unten, alles Weitere steht auf der Metadaten-Seite.)
     */
    private fun renderSheetMeta(meta: MediaMeta) {
        metaContainer.removeAllViews()
        // 1) Karte mit dem Ort des Fotos (antippen = groß)
        MetaRenderer.addMapCard(this, metaContainer, meta)
        // 2) Die wichtigsten Angaben wie in der Samsung-Galerie:
        //    Auflösung, Megapixel, Format, Datum & Uhrzeit, Sensor, Ort
        val rows = meta.keyFacts
        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val rowView = inflater.inflate(R.layout.item_meta_row, metaContainer, false)
            rowView.findViewById<TextView>(R.id.tvLabel).text = Labels.tr(this, row.label)
            rowView.findViewById<TextView>(R.id.tvValue).text = Labels.tr(this, row.value)
            metaContainer.addView(rowView)
        }
    }

    /**
     * Die **wichtigsten Angaben** zu einem Foto – erreichbar über das ⋮-Menü.
     * Im Foto selbst öffnet das Hochwischen das Blatt unten (dasselbe, schöner).
     */
    private fun showKeyFacts() {
        val item = current ?: return
        val rows = currentMeta?.keyFacts.orEmpty().toMutableList()
        if (rows.isEmpty()) {
            if (item.width > 0 && item.height > 0) {
                rows.add(com.n3vibecode.gallery.data.MetaRow(
                    getString(R.string.fact_resolution_px), "${item.width} × ${item.height}"
                ))
                rows.add(com.n3vibecode.gallery.data.MetaRow(
                    getString(R.string.fact_resolution_mp),
                    com.n3vibecode.gallery.data.ExifRepository.megapixelPrecise(item.width, item.height)
                ))
            }
            rows.add(com.n3vibecode.gallery.data.MetaRow(getString(R.string.fact_format), item.format))
            rows.add(com.n3vibecode.gallery.data.MetaRow(
                getString(R.string.fact_datetime),
                if (item.time > 0) Fmt.dateTime(item.time) else getString(R.string.date_unknown)
            ))
        }
        val note = MetaStore.note(item.uri)
        if (note.isNotBlank()) {
            rows.add(com.n3vibecode.gallery.data.MetaRow(getString(R.string.meta_note), note))
        }
        val text = rows.joinToString("\n\n") { (k, v) -> "$k\n$v" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.key_facts)
            .setMessage(text)
            .setPositiveButton(R.string.ok) { _, _ -> }
            .setNeutralButton(R.string.key_facts_copy) { _, _ ->
                val clip = getSystemService(ClipboardManager::class.java)
                clip?.setPrimaryClip(ClipData.newPlainText(getString(R.string.key_facts), text))
                Toast.makeText(this, R.string.key_facts_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.sheet_open) { _, _ -> openSheet() }
            .show()
    }

    /**
     * Blatt offen: Knopfleiste unten weicht dem Blatt, Uhr und Akku kommen zurück
     * (man liest Text). Blatt zu: alles versteckt, Foto bildschirmfüllend.
     */
    private fun applySheetState(newState: Int) {
        // Wichtig: Ein geschlossenes BottomSheet fängt trotz Höhe 0 in manchen Zuständen
        // Berührungen ab. Deshalb ist das Blatt nur sichtbar, wenn es offen ist.
        val open = newState == BottomSheetBehavior.STATE_EXPANDED ||
            newState == BottomSheetBehavior.STATE_COLLAPSED ||
            newState == BottomSheetBehavior.STATE_HALF_EXPANDED

        findViewById<View>(R.id.sheet)?.visibility = if (open) View.VISIBLE else View.GONE

        if (open) {
            bottomBar.visibility = View.GONE
            runCatching { SystemBars.showAll(this) }
        } else {
            if (barsVisible) bottomBar.visibility = View.VISIBLE
            runCatching { SystemBars.hideAll(this) }
        }
    }

    /**
     * Blatt öffnen. Wichtig: Die Sichtbarkeit **vor** dem Zustand setzen – ein
     * `GONE`-Blatt hat keine Maße und würde sonst leer oder gar nicht aufklappen.
     */
    private fun openSheet() {
        sheet.visibility = View.VISIBLE
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /** Für die Foto-Seite: Hochwischen öffnet das Blatt (nicht doppelt, falls es schon offen ist). */
    fun openSheetFromPage() {
        if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) openSheet()
    }

    /** Sind die Leisten (oben/unten) gerade sichtbar? Antippen des Fotos dreht das um. */
    private var barsVisible = true

    /** Zoom im Bild: Wischen zwischen Fotos abschalten, solange gezoomt wird. */
    fun setPagerInputEnabled(enabled: Boolean) {
        pager.isUserInputEnabled = enabled
    }

    fun toggleBars() {
        if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
            return
        }
        barsVisible = !barsVisible
        topBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
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
            updateNoteField(item)
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
                    updateNoteField(item)
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

    /**
     * Wischgeste nach oben irgendwo auf dem Foto → Metadaten.
     *
     * Der Zuhörer sitzt am **Wurzel-Layout**, also über allem (Bild, Kopfzeile, Knopfleiste).
     * Er gibt jede Berührung sofort weiter (`false`), damit Wischen zwischen Fotos, Zoomen
     * und Antippen unverändert bleiben; erst wenn die Bewegung klar senkrecht nach oben geht,
     * gilt sie als „hochscrollen“.
     */
    private fun installSwipeUpForMeta() {
        val root: View = findViewById(R.id.root) ?: return
        val slop = (resources.displayMetrics.density * 40).coerceAtLeast(1f)
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var claimed = false

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAt = System.currentTimeMillis()
                    claimed = false
                    false
                }

                android.view.MotionEvent.ACTION_UP -> {
                    if (claimed) {
                        claimed = false
                        return@setOnTouchListener true
                    }
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val quick = System.currentTimeMillis() - downAt < 900
                    val upward = dy < -slop
                    val mostlyVertical = kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.4f
                    if (upward && mostlyVertical && quick && behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                        openSheet()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    /**
     * Lädt die Nachbarfotos **in Bildschirmgröße vor**, sobald der Pager steht.
     * Ein HEIC in dieser Größe zu dekodieren dauert; passiert das erst beim Wischen, sieht
     * man das Nachladen. Im Leerlauf fällt es nicht auf.
     */
    private fun warmNeighbours(position: Int) {
        val list = ViewState.viewList
        if (list.isEmpty()) return
        val targets = listOf(position - 1, position + 1)
            .filter { it in list.indices }
            .map { list[it] }
        if (targets.isEmpty()) return
        Thread({
            val px = com.n3vibecode.gallery.image.ImageLoader.detailPx(this)
            for (item in targets) {
                // Nur wenn es nicht ohnehin schon im Speicher liegt
                if (com.n3vibecode.gallery.image.ImageLoader.bestCached(item) != null) continue
                runCatching {
                    com.n3vibecode.gallery.image.ImageLoader.loadFullDetailed(this, item, px) { _, _ -> }
                }
            }
        }, "n3-warm-neighbours").apply { isDaemon = true }.start()
    }

    private fun recreatePager() {
        val position = pager.currentItem
        pages.clear()
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = ViewState.viewList.size
            override fun createFragment(position: Int) =
                MediaPageFragment.create(position, position == pager.currentItem)
                    .also { pages[position] = it }
        }
        pager.setCurrentItem(position, false)
        pager.post { updatePrimary(position) }
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

        /** Optionaler Titel (z. B. Ortsname, wenn die Liste von der Karte kommt). */
        const val EXTRA_TITLE = "title"

        /** Direkt die Karte „Wichtigste Angaben“ öffnen (Aufruf aus dem ⋮-Menü). */
        const val EXTRA_KEY_FACTS = "key_facts"
    }
}
