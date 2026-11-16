package com.n3vibecode.gallery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
 *  • Der Suchtext wird mit **EmbeddingGemma 2** in einen Vektor umgerechnet (GPU oder CPU).
 *  • Jedes Foto hat ebenfalls einen Vektor (aus Namen, Notizen, Tags, Ort, Zeit, Format …).
 *  • Gezeigt werden die Fotos mit der größten Ähnlichkeit.
 *
 * Zusätzlich bleibt die klassische Suche erhalten (Dateiname, Ordner, Format) – die Treffer
 * beider Wege werden gemischt, damit auch „IMG_2043“ gefunden wird.
 *
 * **Zwei Dinge, die das Gerät in 1.34 einfrieren ließen und hier behoben sind:**
 *  1. Jede Suche startete einen neuen Thread, der wieder `AiEngine.prepare()` aufrief und
 *     dabei das Modell **neu lud** – mehrere Ladevorgänge gleichzeitig, während der Haupt-
 *     Thread auf Sperren wartete. Jetzt wird nie mehrfach geladen.
 *  2. Der Index-Aufbau meldete sich nie zurück (`onDone` wurde verworfen), deshalb blieb die
 *     Fortschrittsanzeige bei „0 / n“ stehen. Jetzt zeigt sie Foto für Foto den Fortschritt.
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

    /** Zuletzt angezeigte Treffer (für das Öffnen der Großansicht). */
    private var results: List<MediaItem> = emptyList()

    private var searchToken = 0
    private var lastStatus: AiEngine.Status? = null

    private val rerun = Runnable { search() }

    /** Fortschritt des Index-Aufbaus in der Statuszeile (nur solange wirklich gebaut wird). */
    private val progressTick: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val (done, total) = SearchIndex.progress()
            val building = SearchIndex.isRunning()
            progress.visibility = if (building) View.VISIBLE else View.GONE
            if (building) {
                tvEngine.text = if (total > 0) {
                    getString(R.string.ai_indexing, done, total)
                } else {
                    getString(R.string.ai_loading)
                }
            } else {
                lastStatus?.let { showEngineStatus(it) }
            }
            main.postDelayed(this, 700)
        }
    }

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

        findViewById<View>(R.id.btnModelDownload).setOnClickListener { chooseModelAndDownload() }
        findViewById<View>(R.id.btnModelCancel).setOnClickListener { ModelStore.cancel() }
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
                hideKeyboard()
                search()
                true
            } else false
        }
        queryEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Kurz warten, damit beim Tippen nicht jeder Buchstabe eine Suche startet
                main.removeCallbacks(rerun)
                main.postDelayed(rerun, 320)
            }
        })
        // Lupen-Symbol im Eingabefeld: antippen startet die Suche sofort
        findViewById<View>(R.id.queryBox)
            .findViewById<View>(com.google.android.material.R.id.text_input_end_icon)
            ?.setOnClickListener {
                hideKeyboard()
                search()
            }

        // Tippen auf die Treffer (oder irgendwo daneben) schließt die Tastatur – zusätzlich
        // zum Zurück-Wischen. Vorher blieb sie stehen, weil der Fokus im Feld blieb.
        //
        // Wichtig: ein OnItemTouchListener läuft **vor** den Kacheln. Ein OnTouchListener am
        // RecyclerView käme bei einem Tippen auf ein Bild gar nicht an (das Kind behandelt es).
        recycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) hideKeyboard()
                return false   // nie konsumieren – Antippen/Langesdrücken bleibt wie es war
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })
        root.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performClick()
                hideKeyboard()
            }
            false
        }

        buildSuggestions()

        // Suchmaschine vorbereiten und – falls nötig – den Index bauen. Alles im Hintergrund;
        // die Seite ist sofort bedienbar und zeigt den Fortschritt in der Statuszeile.
        progress.visibility = View.VISIBLE
        tvEngine.text = getString(R.string.ai_loading)
        Thread({
            AiEngine.prepare(applicationContext)
            SearchIndex.warmUp(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val st = AiEngine.currentStatus()
                lastStatus = st
                showEngineStatus(st)
                refreshModelBox(st)
                // Läuft der Index noch, bleibt der Fortschritt stehen; sonst ausblenden
                if (!SearchIndex.isRunning()) progress.visibility = View.GONE
                search()
            }
        }, "n3-search-prepare").apply { isDaemon = true }.start()
        watchProgress()
    }

    private fun watchProgress() {
        main.removeCallbacks(progressTick)
        main.postDelayed(progressTick, 400)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Tastatur

    /**
     * Tastatur schließen und den Fokus vom Eingabefeld nehmen. Wichtig: Der Fokus muss
     * **irgendwohin** wandern (hier auf die Wurzel), sonst holt sich das Feld ihn beim
     * nächsten Layout-Durchlauf zurück und die Tastatur geht wieder auf.
     */
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(queryEdit.windowToken, 0)
        queryEdit.clearFocus()
        val root = findViewById<View>(R.id.searchRoot)
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    // ------------------------------------------------------------------ Modell-Leiste

    /**
     * Modell-Leiste: zeigt den Download an, solange kein EmbeddingGemma-2-Modell da ist –
     * und den **Grund**, wenn eine Datei da ist, das Modell aber nicht startet.
     */
    private fun refreshModelBox(status: AiEngine.Status) {
        if (status.modelInstalled && status.failure.isBlank()) {
            modelBox.visibility = View.GONE
            return
        }
        modelBox.visibility = View.VISIBLE
        if (status.modelInstalled) {
            tvModelTitle.text = getString(R.string.ai_model_broken_title)
            tvModelHint.text = getString(R.string.ai_model_broken_hint, status.failure)
        } else {
            tvModelTitle.text = getString(R.string.ai_model_missing_title)
            tvModelHint.text = getString(R.string.ai_model_missing_hint)
        }
    }

    /** Auswahl: welches EmbeddingGemma-2-Modell geladen werden soll. */
    private fun chooseModelAndDownload() {
        val variants = ModelStore.Variant.entries
        val labels = variants.map { v -> "${v.label}\n${v.note} · ca. ${v.sizeMb} MB" }
            .toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_model_choose)
            .setItems(labels) { _, which -> startDownload(variants[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Download in der App – mit Fortschritt, Abbrechen und Platzprüfung. */
    private fun startDownload(variant: ModelStore.Variant) {
        if (!ModelStore.hasRoomFor(this, variant)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_model_no_space)
                .setMessage(getString(R.string.ai_model_no_space_msg, variant.sizeMb))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        findViewById<View>(R.id.modelButtons).visibility = View.GONE
        findViewById<View>(R.id.modelProgressBox).visibility = View.VISIBLE
        val bar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(
            R.id.modelProgress
        )
        val tv = findViewById<TextView>(R.id.tvModelProgress)
        bar.isIndeterminate = true
        tv.text = getString(R.string.ai_model_starting, variant.sizeMb)

        Thread({
            ModelStore.download(applicationContext, variant,
                onProgress = { done, total ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = ((done * 100) / total).toInt()
                            tv.text = getString(
                                R.string.ai_model_progress, done / 1048576, total / 1048576
                            )
                        } else {
                            tv.text = getString(R.string.ai_model_progress_unknown, done / 1048576)
                        }
                    }
                },
                onDone = { file ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        findViewById<View>(R.id.modelButtons).visibility = View.VISIBLE
                        findViewById<View>(R.id.modelProgressBox).visibility = View.GONE
                        bar.isIndeterminate = false
                        bar.progress = 0
                        if (file == null) {
                            Toast.makeText(
                                this, R.string.ai_model_import_failed, Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this, getString(R.string.ai_model_imported, file.name),
                                Toast.LENGTH_SHORT
                            ).show()
                            reloadEngine()
                        }
                    }
                })
        }, "n3-model-download").apply { isDaemon = true }.start()
    }

    /** Datei-Auswahl (SAF) – falls das Modell schon auf dem Gerät liegt. */
    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_PICK_MODEL) }
            .onFailure {
                Toast.makeText(this, R.string.ai_model_import_failed, Toast.LENGTH_LONG).show()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        progress.visibility = View.VISIBLE
        tvEngine.text = getString(R.string.ai_loading)
        Thread({
            val file = ModelStore.importTo(applicationContext, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                if (file == null) {
                    Toast.makeText(
                        this, R.string.ai_model_import_failed, Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this, getString(R.string.ai_model_imported, file.name), Toast.LENGTH_SHORT
                ).show()
                reloadEngine()
            }
        }, "n3-model-import").apply { isDaemon = true }.start()
    }

    /**
     * Suchmaschine mit dem neuen Modell neu starten und Index neu aufbauen.
     * Läuft komplett im Hintergrund, die Seite bleibt bedienbar.
     */
    private fun reloadEngine() {
        progress.visibility = View.VISIBLE
        tvEngine.text = getString(R.string.ai_loading)
        Thread({
            AiEngine.prepare(applicationContext, force = true)
            SearchIndex.invalidate(applicationContext)
            SearchIndex.warmUp(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val st = AiEngine.currentStatus()
                lastStatus = st
                showEngineStatus(st)
                refreshModelBox(st)
                if (!SearchIndex.isRunning()) progress.visibility = View.GONE
                search()
            }
        }, "n3-engine-reload").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ Statuszeile

    private fun showEngineStatus(status: AiEngine.Status) {
        lastStatus = status
        val (prefix, label) = when (status.accelerator) {
            AiEngine.Accelerator.NPU -> "⚡" to status.accelerator.label
            AiEngine.Accelerator.GPU -> "◆" to status.accelerator.label
            AiEngine.Accelerator.CPU -> "●" to status.accelerator.label
            AiEngine.Accelerator.LOCAL -> "◦" to getString(R.string.ai_engine_local)
        }
        val text = getString(R.string.ai_engine_status, prefix, status.modelName, label) +
            if (status.detail.isNotBlank()) " · ${status.detail}" else ""
        // Ist ein Modell da, startet aber nicht: Grund direkt daneben zeigen.
        tvEngine.text = if (status.modelInstalled && status.failure.isNotBlank()) {
            getString(R.string.ai_engine_failed, text, status.failure)
        } else {
            text
        }
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

    /**
     * Suche ausführen – **vollständig im Hintergrund**.
     *
     * In 1.34 lief die klassische Suche (Dateiname, Ordner, Notizen …) noch auf dem
     * Haupt-Thread: Bei jedem Tastendruck wurde die Beschreibung **jedes** Fotos neu gebaut
     * (inklusive Datenbankzugriff für den Ort). Bei einigen tausend Fotos stand die App dabei
     * – genau das war das „Einfrieren nach der Suche“. Jetzt rechnet alles im Hintergrund,
     * und die Treffer kommen als fertige Liste zurück.
     */
    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        val token = ++searchToken
        if (query.length < 2) {
            results = emptyList()
            adapter.submitItems(emptyList())
            emptyBox.visibility = View.GONE
            return
        }
        Thread({
            // Modell nur vorbereiten, wenn noch nichts läuft (prepare ist sonst billig)
            if (!AiEngine.ready()) AiEngine.prepare(applicationContext)
            // Klassische Suche: gleicher Wortlaut in Name/Ordner/Notizen/Tags/Format
            val plain = runCatching { classicSearch(query) }.getOrDefault(emptyList())
            val ai = runCatching { SearchIndex.search(query) }.getOrDefault(emptyList())
            val merged = LinkedHashSet<MediaItem>(ai.size + plain.size)
            merged.addAll(plain)
            merged.addAll(ai)
            val list = merged.toList()
            main.post {
                if (token != searchToken || isFinishing || isDestroyed) return@post
                results = list
                adapter.submitItems(list)
                if (list.isEmpty()) {
                    emptyBox.visibility = View.VISIBLE
                    tvEmptyTitle.text = getString(R.string.search_empty_title)
                    tvEmptySub.text = emptyHint()
                } else {
                    emptyBox.visibility = View.GONE
                }
                // Statuszeile auffrischen: zeigt jetzt auch, wie weit der Index ist
                lastStatus?.let { showEngineStatus(it) }
            }
        }, "n3-search").apply { isDaemon = true }.start()
    }

    /** Hinweis im leeren Zustand – nennt den echten Grund, nicht nur „nichts gefunden“. */
    private fun emptyHint(): String {
        val st = lastStatus ?: AiEngine.currentStatus()
        val problem = SearchIndex.lastError()
        return when {
            problem.isNotBlank() -> getString(R.string.search_empty_error, problem)
            SearchIndex.isRunning() -> getString(R.string.search_empty_indexing)
            st.modelInstalled && st.failure.isNotBlank() ->
                getString(R.string.search_empty_error, st.failure)

            !st.modelInstalled -> getString(R.string.search_empty_no_model)
            else -> getString(R.string.search_empty_hint)
        }
    }

    private fun classicSearch(query: String): List<MediaItem> {
        val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return DataHub.all.filter { item ->
            val text = SearchIndex.describeCached(this, item).lowercase()
            terms.all { text.contains(it) }
        }
    }

    private companion object {
        const val REQ_PICK_MODEL = 4711
    }
}
