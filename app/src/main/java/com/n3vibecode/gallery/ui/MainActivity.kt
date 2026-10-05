package com.n3vibecode.gallery.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.n3vibecode.gallery.BuildConfig
import com.n3vibecode.gallery.R
import com.n3vibecode.gallery.data.DataHub
import com.n3vibecode.gallery.data.GridPrefs
import com.n3vibecode.gallery.data.MediaItem
import com.n3vibecode.gallery.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * N3 Photos – Startbildschirm mit den Tabs
 * Zeitleiste · Tags · Formate · Ordner (Vorbild: Apple Fotos / Google Fotos).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var pager: androidx.viewpager2.widget.ViewPager2
    private lateinit var progress: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var searchBar: com.google.android.material.search.SearchBar
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar

    private var loading = false

    /** Gemappte Tabs: Position -> Fragment (für die „ist gerade sichtbar“-Info). */
    private val pages = HashMap<Int, androidx.fragment.app.Fragment>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        // Voller App-Name in der Kopfzeile (zwei Zeilen, damit nichts abgeschnitten wird)
        runCatching {
            for (i in 0 until toolbar.childCount) {
                val v = toolbar.getChildAt(i)
                if (v is android.widget.TextView) {
                    v.isSingleLine = false
                    v.maxLines = 2
                    v.textSize = 14f
                }
            }
        }
        searchBar = findViewById(R.id.searchBar)
        tabLayout = findViewById(R.id.tabLayout)
        pager = findViewById(R.id.viewPager)
        progress = findViewById(R.id.scanProgress)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> { openSearch(); true }
                R.id.action_refresh -> { reload(); true }
                R.id.action_grid -> { openGridSizeDialog(); true }
                R.id.action_language -> { openLanguageDialog(); true }
                R.id.action_lang_toggle -> {
                    // Direkt umschalten: Deutsch ⇄ English
                    val isDe = resources.configuration.locales.get(0)?.language?.startsWith("de") == true
                    val next = if (isDe) "en" else "de"
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(next)
                    )
                    recreate()
                    true
                }
                R.id.action_overview -> {
                    startActivity(android.content.Intent(this, OverviewActivity::class.java)); true
                }
                R.id.action_about -> { showAbout(); true }
                else -> false
            }
        }

        searchBar.setOnClickListener { openSearch() }

        // Dauerhafte Werbe-Leiste: iPhone-Screenshots / HEIC – tippen öffnet die Info
        findViewById<android.view.View>(R.id.promoBar).setOnClickListener { showAboutDialog() }

        val titles = listOf(
            getString(R.string.tab_timeline),
            getString(R.string.tab_tags),
            getString(R.string.tab_formats),
            getString(R.string.tab_folders)
        )
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 4
            override fun createFragment(position: Int): androidx.fragment.app.Fragment {
                val fragment = when (position) {
                    0 -> TimelineFragment()
                    1 -> TagsFragment()
                    2 -> FormatsFragment()
                    else -> FoldersFragment()
                }
                pages[position] = fragment
                return fragment
            }
        }
        // Nur den aktuellen Tab und seinen Nachbarn vorhalten: Vorher waren alle vier
        // Raster gleichzeitig aufgebaut, was beim Start und bei jedem Aktualisieren
        // unnötig Rechenzeit gekostet hat.
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                applyPageVisibility(position)
            }
        })
        TabLayoutMediator(tabLayout, pager) { tab, position -> tab.text = titles[position] }.attach()
        applyPageVisibility(pager.currentItem)

        ensurePermissionsThenLoad()

        if (intent?.action == Intent.ACTION_VIEW) handleViewIntent(intent)

        maybeShowWelcome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun ensurePermissionsThenLoad() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) reload() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Nur der sichtbare Tab darf rechnen – das hält das Wischen und Scrollen flüssig. */
    private fun applyPageVisibility(current: Int) {
        pages.forEach { (position, fragment) ->
            (fragment as? PageAware)?.setPageActive(position == current)
        }
    }

    override fun onStart() {
        super.onStart()
        // Nach dem Speichern im Editor (oder anderen Änderungen) Liste neu einlesen
        DataHub.rescanHandler = { reload() }
    }

    override fun onStop() {
        if (DataHub.rescanHandler != null) DataHub.rescanHandler = null
        super.onStop()
    }

    /** Alle Medien neu einlesen (MediaStore + eigene Ordner). */
    fun reload() {
        if (loading) return
        loading = true
        progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val repo = Repository(applicationContext)
            // 1) Schneller Durchgang: Android-Medienindex → die Galerie ist sofort gefüllt.
            val quick = withContext(Dispatchers.IO) {
                repo.loadMediaIndex().also {
                    // Favoriten/Notizen/Tags einmal im Hintergrund einlesen – danach ist
                    // der Zugriff beim Bildaufbau rein speicherintern.
                    com.n3vibecode.gallery.data.MetaStore.warmUp()
                }
            }
            DataHub.setItems(quick.items, quick.hiddenByUserSelection)
            progress.visibility = android.view.View.GONE
            loading = false
            if (quick.items.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.permission_needed),
                    Toast.LENGTH_LONG
                ).show()
            }

            // 2) Vollständiger Durchgang inkl. eigener Ordner (SAF) und App-Export.
            val full = withContext(Dispatchers.IO) { repo.loadAll() }
            if (full.items.size != quick.items.size) {
                DataHub.setItems(full.items, full.hiddenByUserSelection)
            }
        }
    }

    private fun openSearch() {
        val edit = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setText(DataHub.query)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                DataHub.query = edit.text.toString()
                searchBar.setText(DataHub.query)
                DataHub.refreshListeners()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset) { _, _ ->
                DataHub.query = ""
                searchBar.setText("")
                DataHub.refreshListeners()
            }
            .show()
    }

    /** Rastergröße: mehr Fotos pro Zeile (auch mit zwei Fingern im Raster zoombar). */
    private fun openGridSizeDialog() {
        val labels = arrayOf(
            getString(R.string.grid_size_2),
            getString(R.string.grid_size_3),
            getString(R.string.grid_size_4),
            getString(R.string.grid_size_5),
            getString(R.string.grid_size_6),
            getString(R.string.grid_size_8),
            getString(R.string.grid_size_10),
            getString(R.string.grid_size_12),
            getString(R.string.grid_size_16),
            getString(R.string.grid_size_20),
            getString(R.string.grid_size_24),
            getString(R.string.grid_size_32)
        )
        val values = intArrayOf(2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32)
        val current = GridPrefs.span(this)
        val checked = values.indexOfFirst { it == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grid_size_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                GridPrefs.setSpan(this, values[which])
                DataHub.refreshListeners()
                dialog.dismiss()
                // Zeitleiste zeigt zusätzlich die Chips für Tag/Monat/Jahr

                Toast.makeText(
                    this,
                    getString(R.string.grid_zoom_more, values[which]),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Sprache umschalten: Systemsprache, Deutsch oder English. */
    private fun openLanguageDialog() {
        val options = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_german),
            getString(R.string.lang_english)
        )
        val tags = arrayOf("", "de", "en")
        val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val checked = if (current.isEmpty) 0 else if (current.toLanguageTags().startsWith("de")) 1 else 2
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    if (tags[which].isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    else androidx.core.os.LocaleListCompat.forLanguageTags(tags[which])
                )
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Auch vom Banner im Raster aus erreichbar. */
    fun showAboutDialog() {
        showAbout()
    }

    /** Begrüßung mit Logo & iPhone-Screenshot-Hinweis – nur beim allerersten Start. */
    private fun maybeShowWelcome() {
        val prefs = getSharedPreferences("n3_gallery_meta", MODE_PRIVATE)
        if (prefs.getBoolean("welcome_shown", false)) return
        prefs.edit().putBoolean("welcome_shown", true).apply()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_welcome, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(R.string.welcome_ok, null)
            .setNeutralButton(R.string.about) { _, _ -> showAbout() }
            .create()

        // Sprache direkt beim ersten Start wählbar – Deutsch oder English
        fun setLang(tag: String) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(tag)
            )
            dialog.dismiss()
            recreate()
        }
        view.findViewById<android.view.View>(R.id.btnLangDe)?.setOnClickListener { setLang("de") }
        view.findViewById<android.view.View>(R.id.btnLangEn)?.setOnClickListener { setLang("en") }
        dialog.show()
    }

    /** Titel des Sprachknopfes an die aktuelle Sprache anpassen. */
    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val isDe = resources.configuration.locales.get(0)?.language?.startsWith("de") == true
        menu.findItem(R.id.action_lang_toggle)?.setTitle(if (isDe) "EN" else "DE")
        return super.onPrepareOptionsMenu(menu)
    }

    private fun showAbout() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)
        view.findViewById<android.widget.TextView>(R.id.tvBody).text =
            getString(R.string.about_text, BuildConfig.VERSION_NAME)
        // Rechtliches & Kontakt (E-Mail ist antippbar)
        view.findViewById<android.widget.TextView>(R.id.tvLegal).text =
            getString(R.string.legal_no_liability)
        view.findViewById<android.widget.TextView>(R.id.tvContact).text =
            getString(R.string.contact_email)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        val existing = DataHub.find(uri.toString())
        val item = existing ?: MediaItem(
            key = uri.toString(),
            uri = uri.toString(),
            name = uri.lastPathSegment ?: "Bild",
            mime = intent.type ?: "image/*",
            ext = com.n3vibecode.gallery.data.Formats.extOf(uri.lastPathSegment ?: ""),
            kind = com.n3vibecode.gallery.data.MediaKind.PHOTO,
            size = 0, width = 0, height = 0, takenAt = 0, modifiedAt = 0,
            bucket = "Freigegeben", path = uri.toString()
        )
        ViewState.viewList = listOf(item)
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_POSITION, 0)
        )
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, MainActivity::class.java))
        }
    }
}
