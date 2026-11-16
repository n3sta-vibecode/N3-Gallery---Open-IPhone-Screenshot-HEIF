package com.n3vibecode.gallery.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.ai.AiEngine
import com.n3vibecode.gallery.ai.ModelStore
import com.n3vibecode.gallery.ai.SearchIndex
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.GridPrefs
import com.n3vibecode.gallery.data.MediaItem

/**
 * Bildersuche in eigenen Worten – wie in „Google Edge Gallery“:
 *
 *  • Der Suchtext wird mit dem **Gemma-Embedding-Modell** in einen Vektor umgerechnet
 *    (auf der **NPU**, sonst GPU, sonst CPU – siehe [AiEngine]).
 *  • Jedes Foto hat ebenfalls einen Vektor (aus Namen, Notizen, Tags, Ort, Zeit, Format …).
 *  • Gezeigt werden die Fotos mit der größten Ähnlichkeit.
 *
 * Zusätzlich bleibt die klassische Suche erhalten (Dateiname, Ordner, Format) – die Treffer
 * beider Wege werden gemischt, damit auch „IMG_2043“ gefunden wird.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var queryEdit: TextInputEditText
    private lateinit var tvEngine: TextView
    private lateinit var suggestions: ChipGroup
    private lateinit var recycler: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySub: TextView
    private lateinit var progress: View

    private lateinit var modelBox: View
    private lateinit var tvModelTitle: TextView
    private lateinit var tvModelHint: TextView

    private lateinit var adapter: GridAdapter
    private val main = Handler(Looper.getMainLooper())
    private val progressTick: Runnable = object : Runnable {
        override fun run() {
            val (done, total) = SearchIndex.progress()
            if (total > 0 && SearchIndex.isBuilding() && !isFinishing) {
                tvEngine.text = getString(R.string.ai_indexing, done, total)
            }
            if (!isFinishing) main.postDelayed(this, 700)
        }
    }

    /** Zuletzt angezeigte Treffer (für das Öffnen der Großansicht). */
    private var results: List<MediaItem> = emptyList()

    private var searchToken = 0
    private val rerun = Runnable { search() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        queryEdit = findViewById(R.id.queryEdit)
        tvEngine = findViewById(R.id.tvEngine)
        suggestions = findViewById(R.id.suggestions)
        recycler = findViewById(R.id.recycler)
        emptyBox = findViewById(R.id.emptyBox)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptySub = findViewById(R.id.tvEmptySub)
        progress = findViewById(R.id.progress)

        modelBox = findViewById(R.id.modelBox)
        tvModelTitle = findViewById(R.id.tvModelTitle)
        tvModelHint = findViewById(R.id.tvModelHint)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Modell holen: der Browser lädt die offizielle Datei, die App liest sie danach ein.
        findViewById<View>(R.id.btnModelDownload).setOnClickListener { openModelDownload() }
        findViewById<View>(R.id.btnModelPick).setOnClickListener { pickModelFile() }

        val root = findViewById<View>(R.id.searchRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val span = GridPrefs.span(this).coerceAtMost(6)
        recycler.layoutManager = GridLayoutManager(this, span)
        adapter = GridAdapter(span, false, { item, index ->
            ViewState.viewList = results
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_POSITION, index)
                    .putExtra(DetailActivity.EXTRA_URI, item.uri)
            )
        }, { item -> PeekPreview.show(this, item, results.indexOfFirst { it.uri == item.uri }) })
        recycler.adapter = adapter

        queryEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }
        queryEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Kurz warten, damit beim Tippen nicht jede Buchstabe eine Suche startet
                main.removeCallbacks(rerun)
                main.postDelayed(rerun, 320)
            }
        })
        // Lupen-Symbol im Eingabefeld: antippen startet die Suche sofort
        findViewById<View>(R.id.queryBox)
            .findViewById<View>(com.google.android.material.R.id.text_input_end_icon)
            ?.setOnClickListener { search() }

        buildSuggestions()

        // Suchmaschine vorbereiten (Modell laden) und Index aufbauen
        progress.visibility = View.VISIBLE
        tvEngine.text = getString(R.string.ai_loading)
        Thread({
            AiEngine.prepare(applicationContext) { status ->
                SearchIndex.build(applicationContext, DataHub.all) {
                    runOnUiThread {
                        progress.visibility = View.GONE
                        showEngineStatus(status)
                        search()
                    }
                }
            }
        }, "n3-search-prepare").apply { isDaemon = true }.start()
        // Auch während der Index entsteht schon den Fortschritt zeigen
        watchProgress()
    }

    private fun watchProgress() {
        main.removeCallbacks(progressTick)
        main.postDelayed(progressTick, 700)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Modell-Leiste zeigen, solange kein Gemma-Modell vorhanden ist. */
    private fun refreshModelBox(status: AiEngine.Status) {
        if (status.modelInstalled) {
            modelBox.visibility = View.GONE
            return
        }
        modelBox.visibility = View.VISIBLE
        tvModelTitle.text = getString(R.string.ai_model_missing_title)
        tvModelHint.text = getString(R.string.ai_model_missing_hint)
    }

    /**
     * Modell besorgen. Die App hat keine Internet-Berechtigung – deshalb übergibt sie die
     * offizielle Adresse an den Browser/Download-Manager des Geräts. Danach „Datei wählen“.
     */
    private fun openModelDownload() {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ModelStore.DOWNLOAD_URL))
        val opened = runCatching { startActivity(intent) }.isSuccess
        if (!opened) {
            // Kein Browser: Adresse zum Kopieren anzeigen
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_model_download)
                .setMessage(ModelStore.DOWNLOAD_URL)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText("model", ModelStore.DOWNLOAD_URL)
                    )
                    Toast.makeText(this, R.string.ai_model_download_hint, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        Toast.makeText(this, R.string.ai_model_download_hint, Toast.LENGTH_LONG).show()
    }

    /** Datei-Auswahl (SAF) – die gewählte .task-Datei wird in den App-Speicher kopiert. */
    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_PICK_MODEL) }
            .onFailure { Toast.makeText(this, R.string.ai_model_import_failed, Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        progress.visibility = View.VISIBLE
        tvEngine.text = getString(R.string.ai_loading)
        Thread({
            val file = ModelStore.importFrom(applicationContext, uri)
            runOnUiThread {
                progress.visibility = View.GONE
                if (file == null) {
                    Toast.makeText(this, R.string.ai_model_import_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this,
                    getString(R.string.ai_model_imported, file.name),
                    Toast.LENGTH_SHORT
                ).show()
                reloadEngine()
            }
        }, "n3-model-import").apply { isDaemon = true }.start()
    }

    /** Suchmaschine mit dem neuen Modell neu starten und Index neu aufbauen. */
    private fun reloadEngine() {
        Thread({
            AiEngine.prepare(applicationContext, force = true) { status ->
                // Andere Vektoren (anderes Modell) → Index neu berechnen
                SearchIndex.invalidate(applicationContext)
                SearchIndex.build(applicationContext, DataHub.all) {
                    runOnUiThread {
                        showEngineStatus(status)
                        refreshModelBox(status)
                        search()
                    }
                }
            }
        }, "n3-engine-reload").apply { isDaemon = true }.start()
    }

    private fun showEngineStatus(status: AiEngine.Status) {
        val prefix = when (status.accelerator) {
            AiEngine.Accelerator.NPU -> "⚡"
            AiEngine.Accelerator.GPU -> "◆"
            AiEngine.Accelerator.CPU -> "●"
            AiEngine.Accelerator.LOCAL -> "◦"
        }
        tvEngine.text = getString(
            R.string.ai_engine_status,
            prefix,
            status.modelName,
            status.accelerator.label
        ) + if (status.detail.isNotBlank()) " · ${status.detail}" else ""
        refreshModelBox(status)
    }

    private fun buildSuggestions() {
        suggestions.removeAllViews()
        val ideas = listOf(
            R.string.search_idea_beach,
            R.string.search_idea_food,
            R.string.search_idea_screenshot,
            R.string.search_idea_person,
            R.string.search_idea_mountain,
            R.string.search_idea_pet
        )
        for (id in ideas) {
            val chip = Chip(this).apply {
                text = getString(id)
                isCheckable = false
                isClickable = true
                setOnClickListener {
                    queryEdit.setText(text)
                    queryEdit.setSelection(text.length)
                    runSearch(text.toString())
                }
            }
            suggestions.addView(chip)
        }
    }

    // ------------------------------------------------------------------ Suche

    /** Sucht mit dem aktuellen Inhalt des Eingabefelds. */
    private fun search() {
        runSearch(queryEdit.text?.toString().orEmpty())
    }

    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.length < 2) {
            results = emptyList()
            adapter.submitItems(emptyList())
            emptyBox.visibility = View.GONE
            return
        }
        val token = ++searchToken
        // Treffer sofort zeigen, solange die Suche rechnet (bei großen Sammlungen spürbar)
        progress.visibility = View.VISIBLE
        Thread({
            // Warten, bis die Suchmaschine bereit ist (Modell lädt beim ersten Mal)
            if (!AiEngine.ready()) AiEngine.prepare(applicationContext)
            val ai = runCatching { SearchIndex.search(query) }.getOrDefault(emptyList())
            // Zusätzlich: klassische Suche über Namen/Ordner/Format/Tags – mit gleichem
            // Wortlaut, damit z. B. „IMG_2043“ oder „Luzern“ sicher gefunden wird.
            val plain = classicSearch(query)
            val merged = LinkedHashSet<MediaItem>(ai.size + plain.size)
            merged.addAll(plain)
            merged.addAll(ai)
            val list = merged.toList()
            main.post {
                if (token != searchToken || isFinishing) return@post
                progress.visibility = View.GONE
                results = list
                adapter.submitItems(list)
                if (list.isEmpty()) {
                    emptyBox.visibility = View.VISIBLE
                    tvEmptyTitle.text = getString(R.string.search_empty_title)
                    tvEmptySub.text = getString(R.string.search_empty_hint)
                } else {
                    emptyBox.visibility = View.GONE
                }
            }
        }, "n3-search").apply { isDaemon = true }.start()
    }

    private companion object {
        const val REQ_PICK_MODEL = 4711
    }

    private fun classicSearch(query: String): List<MediaItem> {
        val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return DataHub.all.filter { item ->
            val text = SearchIndex.describe(applicationContext, item).lowercase()
            terms.all { text.contains(it) }
        }
    }
}
