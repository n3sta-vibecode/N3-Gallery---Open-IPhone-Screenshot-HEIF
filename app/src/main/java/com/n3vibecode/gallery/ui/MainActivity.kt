package com.n3vibecode.gallery.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
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
    private lateinit var bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView
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

        // 1.39: obere Systemleisten (Uhr, Akku) aus – die Galerie beginnt oben mit dem Titel.
        runCatching { SystemBars.hideTop(this) }
        // Der Inhalt braucht trotzdem Abstand zur Statusleiste, sonst klebt der Titel
        // unter der Kamera-Aussparung.
        runCatching {
            val appBar = findViewById<android.view.View>(R.id.appBar)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                v.setPadding(v.paddingLeft, top + dp(4), v.paddingRight, v.paddingBottom)
                // Die untere Leiste bekommt den Systemabstand unten, sonst liegt sie darunter
                findViewById<android.view.View>(R.id.bottomNav)?.let { nav ->
                    nav.setPadding(nav.paddingLeft, nav.paddingTop, nav.paddingRight, bottom)
                }
                insets
            }
        }

        toolbar = findViewById(R.id.toolbar)
        // 1.38: Kopfzeile wie in einer normalen Foto-App – großer Titel „Bilder“, darunter
        // die eine Suchleiste, rechts Karte und ⋮. Kein Quetschen mehr auf 14 sp. 
        searchBar = findViewById(R.id.searchBar)
        tabLayout = findViewById(R.id.tabLayout)
        pager = findViewById(R.id.viewPager)
        progress = findViewById(R.id.scanProgress)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_map -> {
                    // Gesamte Bibliothek auf der Karte zeigen
                    ViewState.viewList = DataHub.all
                    startActivity(android.content.Intent(this, MapActivity::class.java))
                    true
                }
                R.id.action_key_facts -> {
                    // Die sechs wichtigsten Angaben des ersten Fotos direkt aus dem Menü
                    // (im Foto selbst gibt es zusätzlich den Knopf in der Leiste unten).
                    val first = DataHub.all.firstOrNull()
                    if (first == null) {
                        android.widget.Toast.makeText(
                            this, R.string.empty, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        ViewState.viewList = DataHub.all
                        startActivity(
                            android.content.Intent(this, DetailActivity::class.java)
                                .putExtra(DetailActivity.EXTRA_POSITION, 0)
                                .putExtra(DetailActivity.EXTRA_KEY_FACTS, true)
                        )
                    }
                    true
                }
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

        // Eine einzige Suchleiste: tippen öffnet die Bildersuche, in der zugleich die
        // klassische Suche (Dateiname, Ordner, Tags) mitläuft – keine zweite Lupe daneben.
        searchBar.setOnClickListener { openSearchAi() }
        // Ein einziges Ziel: die Bildersuche. Kein zweiter Suchweg, keine zweite Lupe.
        searchBar.setOnLongClickListener { openSearchAi(); true }


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

        // 1.38: gewechselt wird unten (Bilder / Alben / Formate / Sammlungen / Menü).
        // „Menü“ ist kein Tab, sondern öffnet die ⋮-Liste – wie in Google Photos.
        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_photos -> { pager.setCurrentItem(0, false); true }
                R.id.nav_albums -> { pager.setCurrentItem(3, false); true }
                R.id.nav_formats -> { pager.setCurrentItem(2, false); true }
                R.id.nav_tags -> { pager.setCurrentItem(1, false); true }
                R.id.nav_menu -> { toolbar.showOverflowMenu(); true }
                else -> false
            }
        }
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val id = when (position) {
                    1 -> R.id.nav_tags
                    2 -> R.id.nav_formats
                    3 -> R.id.nav_albums
                    else -> R.id.nav_photos
                }
                if (bottomNav.selectedItemId != id) bottomNav.selectedItemId = id
            }
        })

        ensurePermissionsThenLoad()

        forwardSharedIntent(intent)

        maybeShowWelcome()
    }


    /**
     * Fragt die Ort-Berechtigung nach, wenn sie fehlt – aber **nur einmal** und nur, wenn
     * noch nicht alle Fotos geladen sind. So bleibt die App bedienbar und der Nutzer weiß,
     * warum bei manchen Fotos kein Ort steht.
     */
    private fun askForLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) return
        val perm = Manifest.permission.ACCESS_MEDIA_LOCATION
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("n3_perms", MODE_PRIVATE)
        if (prefs.getBoolean("asked_location", false)) return
        prefs.edit().putBoolean("asked_location", true).apply()
        runCatching { locationLauncher.launch(perm) }
    }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            DataHub.requestRescan()
            reload()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ensurePermissionsThenLoad() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            reload()
            askForLocationIfNeeded()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * Seit Android 10 liefert der Medienindex Fotos **ohne** GPS-Position. Die echten
     * Koordinaten gibt es nur mit `ACCESS_MEDIA_LOCATION` (`setRequireOriginal`).
     * Ohne diese Berechtigung stand bei vielen Fotos „kein Ort gespeichert“ –
     * obwohl einer in der Datei ist.
     */
    private fun requiredPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= 29 -> listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        else -> listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            // Android 8/9: wird gebraucht, um bearbeitete Fotos in „Bilder“ zu speichern
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    /** Nur der sichtbare Tab darf rechnen – das hält das Wischen und Scrollen flüssig. */
    private fun applyPageVisibility(current: Int) {
        pages.forEach { (position, fragment) ->
            (fragment as? PageAware)?.setPageActive(position == current)
        }
    }

    /**
     * Fotos aus anderen Apps: „Teilen“ und „Öffnen mit“ landen hier (Intent-Filter im
     * Manifest) und werden an die Übernahme-Ansicht weitergereicht. Wichtig ist
     * `onNewIntent`, weil die App meist schon läuft – sonst ginge ein zweites geteiltes
     * Foto verloren.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardSharedIntent(intent)
    }

    private fun forwardSharedIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action == Intent.ACTION_VIEW && intent.data != null) {
            // „Öffnen mit“: das Bild direkt groß zeigen (bisheriges Verhalten)
            handleViewIntent(intent)
            return
        }
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            val next = Intent(this, ShareInActivity::class.java).apply {
                this.action = action
                intent.data?.let { data = it }
                intent.clipData?.let { clipData = it }
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let {
                    putExtra(Intent.EXTRA_STREAM, it)
                }
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, it)
                }
            }
            startActivity(next)
        }
    }

    override fun onStart() {
        super.onStart()
        // Nach dem Speichern im Editor (oder anderen Änderungen) Liste neu einlesen
        DataHub.rescanHandler = { reload() }
        // Suchindex im Hintergrund aufbauen/prüfen – die App „analysiert“ die Fotos damit
        // schon beim Start, nicht erst beim Öffnen der Suche. Kurz warten, damit das Wischen
        // durch die Galerie Vorrang hat.
        com.n3vibecode.gallery.ai.SearchIndex.warmUp(applicationContext, delayMs = 1200)
        // Wurde gespeichert, während die Liste nicht sichtbar war? Jetzt nachziehen.
        if (DataHub.consumePendingRescan()) reload()
    }

    override fun onDestroy() {
        if (DataHub.rescanHandler != null) DataHub.rescanHandler = null
        super.onDestroy()
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

    /** Bildersuche in eigenen Worten (Embeddings auf NPU/GPU/CPU). */
    private fun openSearchAi() {
        startActivity(android.content.Intent(this, SearchActivity::class.java))
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
