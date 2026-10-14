package com.n3vibecode.key

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.math.BigInteger

/** Platform Views only: no Compose, WebView, support library or background worker. */
class MainActivity : Activity() {
    private val bg = Color.rgb(16, 21, 18)
    private val surface = Color.rgb(29, 37, 31)
    private val ink = Color.rgb(242, 246, 237)
    private val muted = Color.rgb(167, 180, 168)
    private val accent = Color.rgb(210, 245, 138)
    private lateinit var store: RuleStore
    private lateinit var body: LinearLayout
    private var capture: ((Int) -> Unit)? = null
    private var captureDialog: AlertDialog? = null
    private var pickingApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
        render()
    }
    override fun onResume() { super.onResume(); if (::store.isInitialized) render() }
    override fun onPause() {
        stopCapture()
        captureDialog?.dismiss()
        super.onPause()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun round(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.space(height: Int) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(height))) }
    private fun card(): LinearLayout = column().apply { background = round(surface); setPadding(dp(20), dp(20), dp(20), dp(20)) }
    private fun addCard(view: View) {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) bg else ink)
        backgroundTintList = ColorStateList.valueOf(if (primary) accent else Color.rgb(49, 61, 51))
        minHeight = dp(52); setOnClickListener { action() }
    }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; clipToPadding = false }
        body = column().apply { setPadding(dp(24), dp(24), dp(24), dp(28)) }
        scroll.addView(body)
        setContentView(scroll)
        // Android 15 enforces edge-to-edge: respect system bars, cutouts and the IME.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        scroll.requestApplyInsets()
        body.addView(text("N3 VIBECODE   /   KEY MAPPER", 11f, accent, true).apply { letterSpacing = .12f })
        body.space(30)
        body.addView(text("Deine Tasten.\nDeine Regeln.", 38f, ink, true))
        body.space(12)
        body.addView(text("Mehr Möglichkeiten. Weniger App.", 16f, muted))
        body.space(28)
        val connected = KeyService.instance != null
        val status = card()
        val heading = row()
        val labels = column()
        labels.addView(text(if (!connected) "○  Einrichtung nötig" else if (store.enabled) "●  Tastenbelegung aktiv" else "○  Pausiert", 17f, if (connected && store.enabled) accent else ink, true))
        labels.space(4)
        labels.addView(text(if (!connected) "Bedienungshilfedienst einschalten" else if (store.enabled) "${store.read().size} Belegungen · lokal auf deinem Gerät" else "Die normalen Tastenfunktionen bleiben aktiv", 13f, muted))
        heading.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            contentDescription = "Tastenbelegung aktivieren oder pausieren"
            isChecked = store.enabled && connected
            setOnCheckedChangeListener { _, checked ->
                if (!connected && checked) { isChecked = false; disclosure() }
                else if (connected) { store.enabled = checked; render() }
            }
        }
        heading.addView(toggle)
        status.addView(heading)
        if (!connected) { status.space(12); status.addView(button("Dienst aktivieren", true) { disclosure() }) }
        addCard(status)
        body.space(12)
        val title = row()
        title.addView(text("Belegungen", 23f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(text("${store.read().size} REGELN", 11f, muted))
        body.addView(title)
        body.space(14)
        val rules = store.read()
        if (rules.isEmpty()) {
            val empty = card()
            empty.addView(text("01  /  Fang mit einer Taste an", 12f, accent, true))
            empty.space(14)
            empty.addView(text("Ein Klick kann mehr.", 23f, ink, true))
            empty.space(8)
            empty.addView(text("Lauter für Musik. Beide Tasten für eine App. Du entscheidest, was passiert.", 15f, muted))
            empty.space(16)
            empty.addView(button("Lauter · 1× einrichten") { editRule(null, "24") })
            addCard(empty)
        } else rules.forEach { rule ->
            val item = card()
            item.addView(text("${RuleStore.keyName(rule.trigger)}   ·   ${rule.count}×", 13f, accent, true))
            item.space(8)
            item.addView(text(rule.label(), 20f, ink, true))
            item.space(10)
            item.addView(text("Kurz drücken    →    Bearbeiten", 12f, muted))
            item.isFocusable = true
            item.setOnClickListener { editRule(rule) }
            item.contentDescription = "${RuleStore.keyName(rule.trigger)}, ${rule.count} mal, ${rule.label()}. Zum Bearbeiten tippen."
            addCard(item)
        }
        body.addView(button("+  Belegung hinzufügen", true) { editRule(null) }, LinearLayout.LayoutParams(-1, dp(56)))
        body.space(28)
        body.addView(text("Feinabstimmung", 23f, ink, true))
        body.space(14)
        val timing = card()
        timing.addView(text("Mehrfachklick-Pause     ${store.gap} ms", 16f, ink, true))
        timing.space(6)
        timing.addView(text("Nach dieser Pause wird die genaue Klickzahl ausgewertet. Auch ein Einfachklick wartet so lange.", 14f, muted))
        timing.space(10)
        timing.addView(button("Zeitfenster anpassen") { editTiming() })
        addCard(timing)
        val limit = card()
        limit.addView(text("1, 2, 3 … deine Zahl.", 19f, accent, true))
        limit.space(8)
        limit.addView(text("Kein festes Mehrfachklicklimit. Lauter + Leiser: innerhalb von 150 ms drücken und beide loslassen. Kurzklicks dauern höchstens 500 ms.", 14f, muted))
        limit.space(14)
        limit.addView(button("Gerätegrenzen & Datenschutz") { help() })
        addCard(limit)
        body.space(12)
        body.addView(text("N3 KEY  1.0\nApp by n3 Vibecode\nOffline · Keine Werbung · Kotlin", 12f, muted).apply { gravity = Gravity.CENTER })
    }

    private fun disclosure() {
        AlertDialog.Builder(this).setTitle("Deine Tasten, nicht deine Inhalte")
            .setMessage("N3 Key benötigt den Android-Bedienungshilfedienst, um eingerichtete Hardwaretasten auch außerhalb der App abzufangen und deine gewählten Systemaktionen auszuführen.\n\nVerarbeitet werden Tastencodes und Klickabstände. Es werden keine Bildschirminhalte gelesen, keine Eingaben protokolliert und keine Daten übertragen. Nur deine Belegungen werden lokal gespeichert.\n\nIm Anlernmodus wird die gedrückte Taste angezeigt. Der Dienst ist optional und jederzeit deaktivierbar. Fortfahren öffnet die Android-Einstellungen; dort aktivierst du N3 Key selbst.")
            .setNegativeButton("Abbrechen", null).setPositiveButton("Einstellungen öffnen") { _, _ ->
                try {
                    startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, KeyService::class.java)))
                } catch (_: Exception) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }.show()
    }
    private fun help() {
        AlertDialog.Builder(this).setTitle("Leicht. Aber nicht grenzenlos.")
            .setMessage("• Samsung Side-/Power-/Bixby-Tasten sind oft vom System reserviert. Wenn beim Anlernen kein Tastencode erscheint, kann N3 Key diese Taste ohne Root nicht belegen. Eine dedizierte Aktionstaste funktioniert nur, wenn One UI ihre Ereignisse weitergibt. System-Notfallfunktionen werden nicht umgangen.\n\n• Bei ausgeschaltetem oder gesperrtem Bildschirm entscheidet das Gerät, ob Tastenereignisse und Aktionen verfügbar sind. Keine garantierte Screen-off-Steuerung.\n\n• Nur Tasten mit einer Regel werden abgefangen. Unbelegte Klickzahlen bei abgefangenen Lautstärketasten ändern nach der Pause die Medienlautstärke (im Gespräch die Gesprächslautstärke). Unbelegte Zusatz-/Kombitasten führen keine Aktion aus. Originalaktionen fremder Tasten können nicht wiedergegeben werden.\n\n• Lange Lautstärkedrücke ändern die Lautstärke, statt Kurzklick-Regeln auszulösen. Lange Kombidrücke lösen nichts aus. Mehrfachklicks sind nicht auf 2 oder 3 begrenzt; echte Unendlichkeit ist technisch unmöglich.\n\n• Andere Tastenmapper und der Android-Lautstärketasten-Kurzbefehl können kollidieren. Bei Bedarf dort deaktivieren.\n\n• Nach APK-Installation: Falls Android den Dienst sperrt, unter App-Info → ⋮ → Eingeschränkte Einstellungen zulassen (nur wenn du dieser App vertraust). Falls One UI den Dienst beendet: N3 Key bei Bedarf aus den Apps im tiefen Standby entfernen.\n\n• Kein Internet, kein Konto, kein Tracking. Kein Wake-Lock, kein Polling, kein Vordergrunddienst. RAM-Bedarf hängt von Android ab und wurde nicht auf einem Gerät gemessen.\n\nApp by n3 Vibecode")
            .setPositiveButton("Verstanden", null)
            .setNeutralButton("Kameraberechtigung") { _, _ -> requestCamera() }.show()
    }
    private fun editTiming() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(store.gap.toString()); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle("Klickpause in Millisekunden")
            .setMessage("250–1500 ms. Größere Werte machen lange Klickfolgen einfacher, verzögern aber die Aktion.")
            .setView(input).setNegativeButton("Abbrechen", null).setPositiveButton("Speichern", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toLongOrNull()
            if (value == null || value !in 250L..1500L) input.error = "Bitte 250 bis 1500 eingeben"
            else { store.gap = value; dialog.dismiss(); render() }
        } }
        dialog.show()
    }
    private fun editRule(original: Rule?, initial: String = "24") {
        var trigger = original?.trigger ?: initial
        var action = original?.action ?: "play"
        var app = original?.app ?: ""
        var appLabel = original?.appLabel ?: ""
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val keyButton = button(RuleStore.keyName(trigger)) {}
        keyButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Welche Taste?")
                .setItems(arrayOf("Lauter", "Leiser", "Lauter + Leiser gleichzeitig", "Aktions- / andere Taste anlernen")) { _, i ->
                    if (i == 3) learnKey { code -> trigger = code.toString(); keyButton.text = RuleStore.keyName(trigger) }
                    else { trigger = arrayOf("24", "25", TapEngine.CHORD)[i]; keyButton.text = RuleStore.keyName(trigger) }
                }.show()
        }
        box.addView(text("TASTE", 11f, muted, true)); box.addView(keyButton)
        box.space(12); box.addView(text("ANZAHL KURZER KLICKS", 11f, muted, true))
        val count = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((original?.count ?: BigInteger.ONE).toString())
            contentDescription = "Anzahl kurzer Klicks, positive ganze Zahl ohne festes Klicklimit"
            setTextColor(ink); textSize = 22f
        }
        box.addView(count)
        box.addView(text("Exakte Zahl, z. B. 1, 2, 5 oder 100. Auslösung nach der Klickpause.", 12f, muted))
        box.space(18); box.addView(text("AKTION", 11f, muted, true))
        val actionButton = button(original?.label() ?: Actions.labels.getValue(action)) {}
        actionButton.setOnClickListener {
            val options = Actions.labels.entries.toList()
            AlertDialog.Builder(this).setTitle("Was soll passieren?").setItems(options.map { it.value }.toTypedArray()) { _, i ->
                val chosen = options[i].key
                if (chosen == "app") pickApp { pkg, label ->
                    action = "app"; app = pkg; appLabel = label; actionButton.text = label
                } else {
                    action = chosen; app = ""; appLabel = ""; actionButton.text = options[i].value
                    if (chosen == "torch") requestCamera()
                }
            }.show()
        }
        box.addView(actionButton)
        val editorScroll = ScrollView(this).apply { addView(box) }
        val builder = AlertDialog.Builder(this).setTitle(if (original == null) "Neue Belegung" else "Belegung bearbeiten")
            .setView(editorScroll).setNegativeButton("Abbrechen", null).setPositiveButton("Speichern", null)
        if (original != null) builder.setNeutralButton("Löschen") { _, _ ->
            AlertDialog.Builder(this).setTitle("Belegung löschen?").setMessage("${RuleStore.keyName(original.trigger)} · ${original.count}×")
                .setNegativeButton("Behalten", null).setPositiveButton("Löschen") { _, _ -> store.save(store.read().filter { it.id != original.id }); render() }.show()
        }
        val dialog = builder.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = try { BigInteger(count.text.toString().trim()) } catch (_: Exception) { null }
            if (n == null || n.signum() <= 0) { count.error = "Bitte eine positive ganze Zahl eingeben"; return@setOnClickListener }
            if (action == "app" && app.isBlank()) { toast("Bitte eine App auswählen."); return@setOnClickListener }
            val rule = Rule(trigger, n, action, app, appLabel)
            fun save() { store.put(rule, original?.id); dialog.dismiss(); render() }
            if (store.read().any { it.id == rule.id && it.id != original?.id }) {
                AlertDialog.Builder(this).setTitle("Vorhandene Belegung ersetzen?")
                    .setMessage("Für diese Taste und Klickzahl gibt es schon eine Aktion.")
                    .setNegativeButton("Zurück", null).setPositiveButton("Ersetzen") { _, _ -> save() }.show()
            } else save()
        } }
        dialog.show()
    }
    private fun learnKey(onCode: (Int) -> Unit) {
        if (KeyService.instance == null) {
            AlertDialog.Builder(this).setTitle("Zuerst den Dienst aktivieren")
                .setMessage("Nur der Bedienungshilfedienst kann prüfen, ob One UI diese Taste auch außerhalb der App freigibt.")
                .setNegativeButton("Zurück", null).setPositiveButton("Einrichten") { _, _ -> disclosure() }.show()
            return
        }
        val info = text("Drücke jetzt kurz die gewünschte Hardwaretaste.\n\nErscheint kein Tastencode, gibt Android diese Taste nicht frei. Power-/Seitentasten sind meist gesperrt.", 16f, ink).apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val dialog = AlertDialog.Builder(this).setTitle("Taste anlernen").setView(info)
            .setNegativeButton("Abbrechen", null).create()
        captureDialog = dialog
        capture = { code ->
            stopCapture()
            dialog.dismiss()
            onCode(code)
            toast("Erkannt: ${RuleStore.keyName(code.toString())}")
        }
        KeyService.instance?.learning = capture
        dialog.setOnDismissListener { stopCapture(); captureDialog = null }
        dialog.show()
    }
    private fun stopCapture() { capture = null; KeyService.instance?.learning = null }
    private fun requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this).setTitle("Taschenlampe erlauben")
            .setMessage("Android benötigt dafür die Kameraberechtigung. N3 Key nimmt keine Fotos auf. Ohne Erlaubnis bleiben alle anderen Aktionen nutzbar.")
            .setNegativeButton("Später", null).setPositiveButton("Erlauben") { _, _ -> requestPermissions(arrayOf(Manifest.permission.CAMERA), 7) }.show()
    }
    private fun pickApp(selected: (String, String) -> Unit) {
        if (pickingApps) return
        pickingApps = true
        val loading = AlertDialog.Builder(this).setMessage("Installierte Apps laden …").setCancelable(false).show()
        Thread {
            val apps = try {
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                    .distinctBy { it.first }.sortedBy { it.second.lowercase() }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                pickingApps = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading.dismiss()
                if (apps.isEmpty()) toast("Keine startbaren Apps gefunden.")
                else AlertDialog.Builder(this).setTitle("App auswählen")
                    .setItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i -> selected(apps[i].first, apps[i].second) }
                    .setNegativeButton("Abbrechen", null).show()
            }
        }.start()
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
}
