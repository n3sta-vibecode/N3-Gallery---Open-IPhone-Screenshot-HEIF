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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.ai.AiEngine
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

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

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

    private fun classicSearch(query: String): List<MediaItem> {
        val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return DataHub.all.filter { item ->
            val text = SearchIndex.describe(applicationContext, item).lowercase()
            terms.all { text.contains(it) }
        }
    }
}
