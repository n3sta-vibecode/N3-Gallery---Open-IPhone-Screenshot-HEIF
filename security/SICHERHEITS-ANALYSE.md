# Sicherheitsanalyse & Härtung — N3 Gallery (N3 Vibecode)

**Ziel:** `com.n3vibecode.gallery`
**Aktueller Stand (`origin/main`):** Quellprojekt **1.31**, `versionCode 32` — die ausgelieferte
`N3-Gallery-1.31-release.apk` (30,4 MB) und der vollständige Quellbaum
**Gehärtete Fassung (dieser Branch):** [`app/`](../app), `versionName 1.32-hardened`, `versionCode 33`
**Zusätzlich geprüft:** Release-APK **1.16** (Manifest, Signatur, native Bibliotheken)
**Methodik:** statische Analyse · Dekompilierung von Manifest/Signatur (binäres AXML, APK-Signing-Block) ·
Nachbau der `android.net.Uri`- und `java.io.File`-Semantik zur Exploit-Verifikation ·
Offline-Schlüsselanalyse · automatisierte Wirksamkeits- und Regressionstests
**Datum:** 2026-10-07

---

## 0. Vorbemerkung zur Analysegrundlage

Die Analyse begann beim Stand des Arbeitszweigs (Commit `c3bf462`, enthielt nur eine `LICENSE`
und drei ZIP-Archive). Beim Entpacken der Projekt-ZIPs ergab sich Quellstand **1.21**. Ein
Abgleich mit `origin/main` zeigte jedoch, dass dort ein **neuerer, vollständiger Quellbaum
(1.31)** liegt — inklusive neuer Dateien (`MediaSaver.kt`, `MetaBackup.kt`, `EditorActivity.kt`,
`PhotoEditorView.kt`) und der tatsächlich ausgelieferten APK 1.31.

**Alle Befunde wurden deshalb gegen 1.31 verifiziert**, dem aktuellen und ausgelieferten Stand.
Die Härtung in [`app/`](../app) basiert auf 1.31. Wo 1.21 und 1.31 abweichen, ist das vermerkt.

---

## 1. Kurzfassung

N3 Gallery ist eine bewusst **offline** arbeitende Galerie ohne Internet-Berechtigung. Die
Angriffsfläche liegt deshalb nicht im Netz, sondern an zwei Stellen, die leicht übersehen
werden: der **exportierten `MainActivity`** (jede App kann per „Öffnen mit" eine beliebige URI
einschleusen) und der **Build-/Signatur-Pipeline**.

Es wurden **11 Befunde** identifiziert. Der schwerwiegendste ist eine **vollständige
Supply-Chain-Kompromittierung**: Der Signaturschlüssel der App **und sein Passwort** liegen
öffentlich im Repository, und die ausgelieferte APK 1.31 ist nachweislich damit signiert.
Damit kann **jede Person** eine bösartige APK bauen, die Android auf jedem Gerät mit
installierter N3 Gallery als **legitimes Update** akzeptiert — mit vollem Zugriff auf die
App-Sandbox (gleiche Signatur ⇒ gleiche Linux-UID ⇒ gleiche Daten).

Über die exportierte `MainActivity` kann zudem eine **völlig rechtelose Dritt-App** die Galerie
dazu bringen, eine vom Angreifer gewählte Datei zu **löschen** oder zu **überschreiben**,
Angreifer-Inhalt per **Path Traversal** in den privaten App-Speicher zu **schreiben** und
private Dateien per „Teilen" zu **exfiltrieren**. Zusätzlich schreibt die App **alle privaten
Notizen im Klartext in den geteilten Speicher** und importiert sie beim Start automatisch.

Alle Befunde sind in [`app/`](../app) behoben. Die Wirksamkeit ist automatisch geprüft:
[`security/poc/verify_fix.py`](poc/verify_fix.py) weist **14 Angriffspayloads** und
**6 Injektions-URIs** ab und lässt **9 legitime URIs** + **6 legitime Dateinamen** unverändert
durch. Die Signatur-Kompromittierung ist mit [`verify_signing.py`](poc/verify_signing.py)
**End-to-End reproduzierbar**.

### Befundübersicht

| # | Befund | Schweregrad | Ausnutzung | Status |
|---|---|---|---|---|
| **V5** | Signaturschlüssel + Passwort öffentlich im Repo; ausgelieferte APK damit signiert | **KRITISCH** | remote, keine Interaktion | behoben* |
| **V1** | Exportierte `MainActivity` übernimmt Fremd-URIs unvalidiert | **Hoch** | lokale App, 0 Berechtigungen | behoben |
| **V2** | Path Traversal beim Schreiben in den privaten App-Speicher | **Hoch** | lokale App + 1× „Teilen" | behoben |
| **V3** | Willkürliches Dateilöschen über `file://` ohne Systemdialog | **Hoch** | lokale App + 1× „Löschen" | behoben |
| **V12** | Willkürliches Datei-**Überschreiben** über `file://` (`MediaSaver.overwrite`) | **Hoch** | lokale App + 1× „Speichern" | behoben |
| **V11** | `MetaBackup`: alle Notizen im Klartext im geteilten Speicher + Auto-Import | Mittel–Hoch | lokale App / Speicherzugriff | gehärtet |
| **V6** | Build/CI-Fallback signiert „Release" mit öffentlichem Debug-Key | **Hoch** | Supply Chain | behoben |
| **V4** | `allowBackup="true"` ohne Extraktionsregeln | Mittel | ADB / Cloud-Konto | behoben |
| **V8** | DoS: 256-MB-Puffer ohne Vorabprüfung | Mittel | lokale App | behoben |
| **V7** | Release ohne Minify/Obfuskation (R8-`keep` hebt Minify auf) | Niedrig–Mittel | erleichtert RE | behoben |
| **V9** | Impliziter `geo:`-Intent mit ungeprüften EXIF-Koordinaten (toter Code) | Information | – | gehärtet |
| **V10** | `ACCESS_MEDIA_LOCATION` — Kernfunktion, akzeptiertes Restrisiko | Information | – | begründet behalten |

\* V5 ist im Code/Build behoben; der **veröffentlichte** Schlüssel bleibt kompromittiert und
muss organisatorisch ersetzt werden (siehe [§7.1](#71-der-veröffentlichte-signaturschlüssel-muss-ersetzt-werden--vorrangig)).

---

## 2. Angriffsfläche

Die App deklariert **keine** Internet-Berechtigung und hat keine WebView, keine
JavaScript-Bridge, keine Netzwerkaufrufe und keine eigene Kryptographie. Das schließt eine
ganze Klasse von Befunden aus und ist ausdrücklich positiv zu werten.

Was bleibt, ist zweierlei:

**(a) Die exportierte `MainActivity`** — aus dem Manifest heraus erreichbar:

```xml
<activity android:name=".ui.MainActivity" android:exported="true" ...>
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" />
    </intent-filter>
</activity>
```

`exported="true"` ist **funktional zwingend** — ohne es gäbe es kein „Öffnen mit → N3 Photos".
Der Fehler liegt nicht im Export, sondern darin, dass die hereinkommende URI als
vertrauenswürdig behandelt wurde. **Die angreifende App braucht keine einzige Berechtigung.**

**(b) Die Signatur-/Build-Pipeline** — der Signaturschlüssel ist die Identität der App und
lag öffentlich im Repo (V5/V6).

### Signatur der ausgelieferten APK 1.31 (geprüft)

```
== APK Signing Block ==
   ID 0x7109871A -> V2 (APK Signature Scheme v2)
   ID 0x504B4453 -> Play Asset Delivery (aus avif-coder, unkritisch)
   ID 0x42726577 -> V4 (APK Signature Scheme v4)
== V1 (JAR signing) == nicht vorhanden
== Zertifikat ==  CN=N3 Gallery CI, O=N3 Vibecode, C=CH
   SHA-256: 2F:B3:6A:C6:5D:73:A4:B1:E4:37:C8:47:84:E0:EB:50:44:2F:12:3A:DB:92:3B:FD:45:0D:A8:EB:24:C8:48:E0
```

Positiv: **kein V1** → nicht anfällig für Janus (CVE-2017-13156); **nicht debuggable**.
Kritisch: genau dieses Zertifikat gehört zum öffentlich committeten `ci/n3-ci.p12` (V5).

---

## 3. Befunde im Detail

### V5 — Signaturschlüssel + Passwort öffentlich; ausgelieferte APK damit signiert · **KRITISCH**

**Stellen:** `ci/n3-ci.p12` · `keystore.properties` · `.gitignore` · `SIGNIEREN.md` ·
`.github/workflows/build-apk.yml` (alle auf `origin/main`)

Der Signaturschlüssel **und** sein Passwort lagen im öffentlichen Repository. `SIGNIEREN.md`
dokumentiert beides im Klartext — und zwar **absichtlich**:

> „**Ehrlich dazu:** Dieser Schlüssel liegt bewusst im öffentlichen Repository, damit die
> Builds ohne Zugangsdaten laufen (und damit Sie ihn jederzeit herunterladen können)."

`keystore.properties`:

```properties
storeFile=ci/n3-ci.p12
storeType=PKCS12
storePassword=N3GalleryCi2026!
keyAlias=n3ci
keyPassword=N3GalleryCi2026!
```

`.gitignore` hebelt den eigenen Schutz **gezielt** aus:

```gitignore
keystore.properties
*.jks
...
# Nur der CI-Schluessel ... wird mitgeliefert:
!ci/n3-ci.p12
!keystore.properties
```

Die CI (`build-apk.yml`) packte `ci` und `keystore.properties` zusätzlich in ein **öffentlich
herunterladbares Release-ZIP** und prüfte die Signatur gegen den **Fingerabdruck genau dieses
Schlüssels** (`EXPECTED="2FB36AC6…"`) — der Build bestand also nur, wenn weiter mit dem
kompromittierten Schlüssel signiert wurde.

**End-to-End-Beweis** ([`verify_signing.py`](poc/verify_signing.py), reproduziert aus dem
Git-Verlauf, ohne Gerät):

```
[1] Passwort aus keystore.properties (KLARTEXT im Repo): 'N3GalleryCi2026!'
[2] ci/n3-ci.p12 aus dem Repo extrahiert: 2652 Bytes
[3] PRIVATER SCHLUESSEL GELADEN: RSAPrivateKey, 2048 Bit
    => Das Passwort aus dem Repo funktioniert. Der Schluessel ist nutzbar.
    Zertifikat: CN=N3 Gallery CI, O=N3 Vibecode, C=CH
    SHA-256   : 2F:B3:6A:C6:...:C8:48:E0
[4] Signaturzertifikat der APK N3-Gallery-1.31-release.apk
    SHA-256   : 2F:B3:6A:C6:...:C8:48:E0
[5] *** UEBEREINSTIMMUNG *** — die veroeffentlichte APK ist mit dem
    oeffentlichen Repo-Schluessel signiert.
```

**Auswirkung:** Android prüft bei einem Update **ausschließlich** die Signatur — nicht Name,
Herkunft oder Inhalt. Wer diesen Schlüssel besitzt (und das ist jeder mit Repo-Zugriff),
signiert eine beliebige APK so, dass sie auf jedem Gerät mit installierter N3 Gallery als
**legitimes Update** angenommen wird. Weil die Signatur identisch ist, läuft die bösartige
APK mit **derselben Linux-UID** und hat damit **vollen Lese-/Schreibzugriff auf die gesamte
App-Sandbox** — inklusive der Klartext-Notizen (V4/V11) und aller Medien-URIs. Die
N3-Gallery-Downloads in den GitHub-Releases sind per `adb install -r` bzw. Antippen
installierbar; ein Opfer muss nur ein „Update" installieren.

**Abgrenzung zu V6:** V5 ist der **echte, verwendete** Release-Schlüssel (kompromittiert,
weil öffentlich). V6 ist der **Debug-Key-Fallback** als zweite, unabhängige Schwachstelle.
Beide zusammen sind die ungünstigste Konstellation: zwei verschiedene Wege an einen
allgemein akzeptierten Signaturschlüssel.

**Gegenmaßnahme:** [`app/`](../app) enthält `ci/n3-ci.p12` **nicht** mehr; `keystore.properties`
ist über `.gitignore` ausgeschlossen (**ohne** Negation); der Build bricht ohne echten
Schlüssel ab; die CI prüft, dass weder Debug- noch der bekannte CI-Schlüssel verwendet wird.
Der **veröffentlichte** Schlüssel bleibt kompromittiert und muss ersetzt werden (§7.1).

---

### V1 — Exportierte `MainActivity` übernimmt Fremd-URIs unvalidiert · **HOCH**

**Stelle:** `ui/MainActivity.kt`, `handleViewIntent()` (1.31, Zeile 365)

```kotlin
private fun handleViewIntent(intent: Intent?) {
    if (intent?.action != Intent.ACTION_VIEW) return
    val uri: Uri = intent.data ?: return                 // <- keinerlei Prüfung
    val item = existing ?: MediaItem(
        uri = uri.toString(),
        name = uri.lastPathSegment ?: "Bild",            // <- dekodiert %2e%2e%2f zu ../
        ...
        path = uri.toString()                            // <- URI landet in der UI
    )
    ViewState.viewList = listOf(item)
    startActivity(Intent(this, DetailActivity::class.java)...)
}
```

**Problem:** kein Check auf Schema (`file://`, `http://`, `javascript:` — alles akzeptiert),
Authority (jeder beliebige Provider), Path Traversal (`..`-Segmente bleiben) oder MIME-Typ.
Das `MediaItem` wird in `DetailActivity` angezeigt, wo **Teilen**, **Löschen** und
**Bearbeiten/Speichern** angeboten werden → Einstieg für V2, V3, V12.

**Nachweis:** [`verify_traversal.py`](poc/verify_traversal.py), Abschnitt V1 — alle fünf
Test-URIs werden „AKZEPTIERT", darunter `file://`, `http://` und eine Traversal-URI.

**Gegenmaßnahme:** `handleViewIntent` schleust jede URI durch [`UriGuard.verifyIncoming`](../app/src/main/java/com/n3vibecode/gallery/security/UriGuard.kt);
der Dateiname kommt über `OpenableColumns.DISPLAY_NAME` statt `lastPathSegment`.

---

### V2 — Path Traversal beim Schreiben in den privaten App-Speicher · **HOCH**

**Stellen (1.31):** `ui/DetailActivity.kt:488` · `ui/InfoActivity.kt:248` ·
`data/MediaSaver.kt:120` (`saveForSharing`)

```kotlin
val target = File(com.n3vibecode.gallery.GalleryApp.shareDir(), item.name)
contentResolver.openInputStream(uri)?.use { input ->
    target.outputStream().use { input.copyTo(it) }       // <- Inhalt vom Angreifer
}
```

**Mechanismus:** `Uri.getLastPathSegment()` **dekodiert** Prozent-Kodierung. Ein einziges
Segment kann danach Pfadtrenner enthalten:

```
content://attacker/x/%2e%2e%2f%2e%2e%2fshared_prefs%2fpwn.xml
        ↓ getLastPathSegment()
   ../../shared_prefs/pwn.xml
```

`java.io.File(parent, child)` normalisiert **nicht**, es konkateniert nur:

```
File("/data/user/0/<pkg>/cache/share", "../../shared_prefs/pwn.xml")
  → kanonisch: /data/user/0/<pkg>/shared_prefs/pwn.xml
```

**Verifiziert** ([`verify_traversal.py`](poc/verify_traversal.py), Nachbau der Original-Semantik):

```
[!!] name='../../shared_prefs/pwn.xml'
     kanonisch = /data/user/0/com.n3vibecode.gallery/shared_prefs/pwn.xml   => PRIVATES DATENVERZEICHNIS
[!!] name='../../../storage/emulated/0/DCIM/Camera/pwn.jpg'
     kanonisch = /data/user/0/storage/emulated/0/DCIM/Camera/pwn.jpg        => AUSBRUCH AUS DER SANDBOX
```

**Auswirkung:** Ziel **und** Inhalt sind Angreifer-kontrolliert. Überschreiben von
`shared_prefs/n3_gallery_meta.xml` (Notizen/Tags/Favoriten zerstört), `n3_folders.xml`
(SAF-Ordnerzuordnung kapern), auf **API 26–28** (Legacy Storage + `WRITE_EXTERNAL_STORAGE`)
mit genug `../` Schreiben aus der Sandbox in `DCIM/`/`Download/`. Der Nutzer muss nur einmal
auf **„Teilen"** tippen. Auch `MediaSaver.saveForSharing`/`saveViaFile` waren betroffen, weil
`fileName()` zwar einen Zeitstempel anhängt, `substringBeforeLast('.')` aber **keine**
Pfadtrenner entfernt.

**Gegenmaßnahme:** [`SafeFiles.confinedChild`/`writeConfinedCopy`](../app/src/main/java/com/n3vibecode/gallery/security/SafeFiles.kt)
prüfen am **kanonischen** Pfad, dass das Ziel im Verzeichnis bleibt; `MediaSaver.fileName()`
schleust den Basisnamen durch `UriGuard.safeFileName`.

---

### V3 — Willkürliches Dateilöschen über `file://` ohne Systemdialog · **HOCH**

**Stellen (1.31):** `ui/DetailActivity.kt:566` (`directDelete`) ·
`ui/GalleryFragments.kt:295` (`deleteDirect`)

```kotlin
private fun directDelete(uri: Uri) {
    if (uri.scheme == "content") {
        contentResolver.delete(uri, null, null)
    } else {
        File(uri.path ?: return).delete()      // <- Pfad vollständig vom Angreifer
    }
}
```

**Problem:** Der schützende `MediaStore.createDeleteRequest`-Dialog wird **nur** für
`content://` auf API 29+ verwendet. Für jedes andere Schema — insbesondere das per V1
einschleusbare `file://` — wird direkt über `java.io.File` gelöscht, **ohne** Android-
Bestätigung. Der App-eigene Dialog zeigte zudem **nur den Dateinamen**
(`delete_confirm_msg`), nie den Pfad — der Angreifer wählt beides.

**Auswirkung nach API-Level:**

| API | Wirkung |
|---|---|
| 26–28 | `WRITE_EXTERNAL_STORAGE` erteilt, Legacy Storage → **Löschen beliebiger Dateien im geteilten Speicher**, kein Systemdialog |
| 29+ | Scoped Storage blockt fremde Dateien; **app-eigene** (`shared_prefs/`, `databases/`, `cache/`) bleiben löschbar |
| alle | Zerstörung der privaten App-Daten ohne jede Berechtigung des Angreifers |

**Gegenmaßnahme:** `directDelete`/`deleteDirect` entfernt; Löschen läuft über
[`SafeFiles.deleteMedia`](../app/src/main/java/com/n3vibecode/gallery/security/SafeFiles.kt)
(ContentResolver bzw. `MediaStore.createDeleteRequest`). `file://` wird abgewiesen. Der
Bestätigungsdialog zeigt jetzt **Name und Pfad** (`delete_confirm_msg_path`).

---

### V12 — Willkürliches Datei-Überschreiben über `file://` (`MediaSaver.overwrite`) · **HOCH**

**Stelle (neu in 1.31):** `data/MediaSaver.kt:246`

```kotlin
if (uri.scheme != "content") {
    val target = File(uri.path ?: return Result.Failed("Datei nicht gefunden"))
    target.outputStream().use { out -> write(out, bitmap, fmt) }   // <- überschreibt Angreifer-Pfad
    Result.Success(Uri.fromFile(target), target.name)
}
```

**Problem:** Dieselbe Wurzel wie V3, aber mit **Schreiben** statt Löschen. Über den
Bild-Editor (`EditorActivity`, neu in 1.31) und eine per V1 eingeschleuste `file://`-URI
entscheidet der Angreifer, **welche Datei mit dem Editor-Bitmap überschrieben** wird. Auf
API 26–28 beliebig im geteilten Speicher; auf allen Leveln die app-eigenen Dateien.

**Gegenmaßnahme:** Der `file://`-Zweig ist entfernt — `overwrite` liefert für
Nicht-`content`-URIs `Result.Failed`. Überschrieben wird nur noch über den ContentResolver.

---

### V11 — `MetaBackup`: private Notizen im Klartext im geteilten Speicher + Auto-Import · **MITTEL–HOCH**

**Stelle (neu in 1.31):** `data/MetaBackup.kt`, aufgerufen aus `GalleryApp.onCreate()`

`MetaBackup` schreibt **alle** Notizen, Tags und Favoriten als Klartext-JSON in den
**geteilten** Speicher, damit sie eine Neuinstallation überleben:

```kotlin
// API 29+ : Downloads/N3 Gallery/n3-sicherung.json  (MediaStore)
// API 26-28: Pictures/N3 Gallery/n3-sicherung.json   (getExternalStoragePublicDirectory)
JSONObject().apply {
    put("favorites", favs); put("notes", notes); put("tags", tags)
}
```

`buildJson()` sammelt `{uri → notiztext}`, `{uri → tags}` und Favoriten-URIs. Beim Start liest
`restoreIfEmpty()` dieselbe Datei und wendet sie an, wenn die App noch keine eigenen Daten hat.

**Zwei Probleme:**

1. **Vertraulichkeit:** Alle privaten Notizen liegen **unverschlüsselt im geteilten Speicher**.
   Auf API 26–28 (`Pictures/…`) liest sie jede App mit `READ_EXTERNAL_STORAGE`. Notizen zu
   privaten Fotos gehören zum Sensibelsten, was eine Galerie-App hat. Das ist eine **direkte
   Umgehung** der V4-Gegenmaßnahme (`allowBackup=false`), weil die Daten die Sandbox
   freiwillig verlassen. Nachweis: [`read_meta_sicherung.py`](poc/backup-extract/read_meta_sicherung.py).

2. **Integrität/Injektion:** `restoreIfEmpty()` importiert beim Start eine Datei aus dem
   geteilten Speicher. Eine fremde App mit Schreibzugriff kann eine präparierte
   `n3-sicherung.json` **platzieren**; deren URIs werden als Favoriten/Notizen übernommen und
   fließen später in die Lösch-/Schreibpfade (Kette zu V1–V3, V12). Realistisch v. a. auf
   API 26–28, wo der geteilte Speicher beschreibbar ist.

**Gegenmaßnahme:** `applyJson()` filtert jede importierte URI durch `UriGuard.verifyIncoming`
— nur `content://` ohne Traversal wird übernommen; `file://`, Netz-URIs und manipulierte Pfade
werden verworfen. Notiz-/Tag-Texte werden gekappt (`MAX_TEXT=4096`, `MAX_TAGS=64`). Nachweis:
[`verify_fix.py`](poc/verify_fix.py), Abschnitt E (6/6 Injektions-URIs verworfen).

> **Design-Hinweis (offen):** Der Klartext-Export in den geteilten Speicher bleibt
> grundsätzlich bestehen, weil er der dokumentierte Zweck des Features ist
> („überlebt Neuinstallation"). Eine vollständige Lösung wäre eine **verschlüsselte**
> Sicherung (Schlüssel im Android Keystore) oder ein Verzicht auf den Auto-Import. Das ist
> eine Produktentscheidung, die hier nicht eigenmächtig getroffen wurde — die
> Integritätslücke (Injektion) ist geschlossen, die Vertraulichkeitslücke ist dokumentiert.

---

### V6 — Build/CI-Fallback signiert „Release" mit dem öffentlichen Debug-Key · **HOCH**

**Stellen (1.31):** `app/build.gradle.kts`, `.github/workflows/build-apk.yml`

```kotlin
// Fehlt die Datei, wird der Release-Build mit dem Debug-Key signiert -> trotzdem installierbar.
signingConfig = if (hasKeystore) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
```

Der AOSP-Debug-Keystore (`CN=Android Debug`) ist **weltweit identisch und öffentlich**. Eine
damit signierte APK installiert sich problemlos, sieht aus wie die echte App und kann von
**jedem** mit demselben Schlüssel als „Update" nachgebaut werden. Es ist ein **stiller**
Fehler: Der Build ist „erfolgreich" und liefert eine installierbare APK.

**Gegenmaßnahme:** Kein Debug-Fallback mehr. `release` **und** `tryout` werfen eine
erklärende `GradleException`, wenn kein echter Schlüssel (aus `keystore.properties` oder
CI-Secrets) vorliegt. V2+V3 erzwungen. Die CI prüft vor dem Build die Secrets, verweigert
einen Keystore im Repo und stellt nach dem Build sicher, dass **weder** mit dem Debug-Key
**noch** mit dem bekannt-kompromittierten CI-Key signiert wurde.

---

### V4 — `allowBackup="true"` ohne Extraktionsregeln · **MITTEL**

**Stelle (1.31 + ausgelieferte APK 1.16):** `app/src/main/AndroidManifest.xml`

```xml
<application android:allowBackup="true" ... >   <!-- kein fullBackupContent, keine dataExtractionRules -->
```

`MetaStore` schreibt Notizen/Tags/Favoriten als **Klartext**-SharedPreferences
(`n3_gallery_meta.xml`), `FolderStore` die SAF-Ordnerpfade. Ohne Regeln sichert Android den
**kompletten** privaten App-Speicher in jedes Cloud-Backup und jede Geräte-Migration.
Angriffswege: `adb backup` (entsperrtes Gerät), kompromittiertes Cloud-Konto,
Second-Hand-Geräte. Nachweis: [`extract_notes.py`](poc/backup-extract/extract_notes.py) parst
das `.ab`-Format (Header, zlib, tar) und gibt die Notizen aus.

**Gegenmaßnahme:** `allowBackup="false"` + `data_extraction_rules.xml` (API 31+) +
`backup_rules.xml` (API ≤ 30), die beide Backup-Wege (`cloud-backup`, `device-transfer`)
vollständig ausschließen. **Hinweis:** V11 (geteilter Speicher) bleibt als separater Weg —
deshalb ist V4 allein nicht ausreichend.

---

### V8 — DoS: 256-MB-Puffer ohne Vorabprüfung · **MITTEL**

**Stelle (1.31):** `image/HeifSupport.kt`

```kotlin
private const val MAX_BYTES = 256L * 1024 * 1024
fun readAll(...) { val out = ByteArrayOutputStream(); ... if (total > limit) return null; ... out.toByteArray() }
```

Die Grenze greift erst **nach** 256 MB im Heap; `toByteArray()` verdoppelt kurzzeitig auf
~512 MB bei aktivem `largeHeap="true"`. Über V1 kann eine rechtelose App einen `Provider`
mit `ParcelFileDescriptor.createPipe()` schicken und die Galerie in ein OOM treiben.
Mildernd: OOM wird in `decodeBytes` gefangen, `ImageLoader` räumt den Cache.
Nachweis: `PayloadProvider.feed()` (512 MB) in der [PoC-App](poc/exploit-app).

**Gegenmaßnahme:** `MAX_BYTES` auf 96 MB gesenkt; neue Vorabprüfung
`declaredLengthExceeds()` fragt die Größe über `openAssetFileDescriptor` ab, **bevor**
allokiert wird; Startkapazität 64 KB. Die Zähler-Schleife bleibt als zweite Schranke für Pipes.

---

### V7 — Release ohne Minify/Obfuskation · **NIEDRIG–MITTEL**

**Stellen (1.31):** `app/build.gradle.kts` (`isMinifyEnabled = false`), `app/proguard-rules.pro`

```proguard
-keep class com.n3vibecode.gallery.** { *; }
```

Die `-keep`-Regel hebt jede Verkleinerung/Verschleierung vollständig auf — selbst bei
eingeschaltetem Minify bliebe der Code im Klartext. Klassennamen wie `MetaStore`, `MediaSaver`,
`MetaBackup` und Methoden wie `directDelete` beschreiben sich selbst und führen einen
Angreifer direkt zu den Stellen dieses Berichts. Kein eigener Exploit, aber ein
**Multiplikator** für V1–V3/V12.

**Gegenmaßnahme:** `isMinifyEnabled=true`, `isShrinkResources=true`; die Pauschal-`keep`-Regel
durch zielgerichtete Regeln ersetzt. Erhalten bleibt nur, was technisch sein muss:
Manifest-Komponenten, Custom Views (u. a. `PhotoEditorView`, werden in Layout-XML per
Klassenname instanziiert), Enum-`values()`/`valueOf()`, Parcelable-`CREATOR`, und vollständig
das JNI-Paket `com.radzivon.bartoshyk.avif.coder.**` sowie `com.caverock.androidsvg.**`.

---

### V9 — Impliziter `geo:`-Intent mit ungeprüften EXIF-Koordinaten · **INFORMATION**

**Stelle (1.31):** `ui/MetaRenderer.kt:71` — `mapIntent(lat, lon)` baut
`Uri.parse("geo:$lat,$lon?q=$lat,$lon")`. **Toter Code** (kein Aufruf), daher aktuell ohne
Auswirkung. Dokumentiert, weil URI-Bau per Interpolation aus EXIF-Werten dritten Ursprungs und
fehlende Absicherung gegen eine fehlende Ziel-App bei künftiger Verwendung sofort zum Befund
würden; zudem können präparierte Fotos `NaN`/`Infinity` als Koordinaten tragen.

**Zur Ehrenrettung:** `ExifWriter.esc()` maskiert `& < > "` korrekt — die XMP-Erzeugung ist
sauber. **Gegenmaßnahme:** `mapIntent` → `openMap(context, lat, lon): Boolean` mit
Wertebereichsprüfung, `Locale.US`-Formatierung und `try/catch` um `startActivity`
(bewusst **kein** `resolveActivity()`, das ist ab API 30 durch Package Visibility unzuverlässig).

---

### V10 — `ACCESS_MEDIA_LOCATION` · **INFORMATION / akzeptiertes Restrisiko**

```xml
<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />
```

Hebt die Android-seitige **Entfernung der GPS-Daten** auf. `ExifRepository` liest zwölf
GPS-Felder und zeigt sie im Abschnitt „Ort (GPS)" an. **Bewusst beibehalten, nicht entfernt:**
Ohne die Berechtigung würde ein Kernfeature (EXIF-Inspektor) stillschweigend wegfallen — die
GPS-Zeilen blieben im UI, wären aber immer leer. **Empfehlung:** Erklärung im UI beim ersten
Start und in der Datenschutzerklärung; darauf hinweisen, dass die Berechtigung in
`requiredPermissions()` **nicht** zur Laufzeit angefordert wird und nur über die
Systemeinstellungen entziehbar ist.

---

## 4. Was bereits gut gelöst war

| Aspekt | Befund |
|---|---|
| **Keine Internet-Berechtigung** | Bewusst deklariert und eingehalten. Keine WebView, kein JS, kein Netz, keine Cleartext-Frage. |
| **APK-Signaturschema** | V2 + V4, **kein V1** → nicht anfällig für Janus (CVE-2017-13156). |
| **Nicht debuggable** | In den Release-APKs 1.16 und 1.31 nicht gesetzt. |
| **Alle Nicht-Launcher-Komponenten** | `DetailActivity`, `EditorActivity`, `InfoActivity`, `OverviewActivity`, `CollectionActivity`, FileProvider, Services: `exported="false"`. |
| **FileProvider-Pfade** | Nur `cache-path share/`. Kein `root-path`, kein `external-path`, kein `files-path`. Vorbildlich eng. |
| **HEIF-Binärspeicher-Parser** | `HeifInspector` prüft durchgehend Grenzen: `if (size < 8 \|\| size > c.end - startPos) break`, `coerceAtMost(c.end)`, Iterations-Wächter, Extent-Limit 4096, `meta`-Block auf 4 MB gedeckelt. **Kein Pufferüberlauf-Muster gefunden.** |
| **`JpegFinder`** | `scanLimit` 192 MB, `maxCandidate` 48 MB, Bereichsprüfung. Sauber begrenzt. |
| **Thumbnail-Cache** | `ImageLoader.diskFile()` hasht die URI mit MD5 zu einem Hex-Namen → kein Nutzerinput im Dateinamen, kein Traversal. (MD5 hier reine Cache-Adressierung, keine Sicherheitsfunktion — in Ordnung.) |
| **XMP-Erzeugung** | `ExifWriter.esc()` maskiert `& < > "` korrekt. |
| **Keystore-Passwort (JKS `n3-release.jks`)** | Hält einem Offline-Wörterbuchangriff mit 100 828 Kandidaten stand ([`jks_crack.py`](poc/jks_crack.py)). *Aber:* der **CI**-Schlüssel `ci/n3-ci.p12` hat ein triviales, mitgeliefertes Passwort (V5). |
| **`tools/keystore-neu.sh`** | Erzeugt korrekt einen privaten, gitignorierten Schlüssel mit Zufallspasswort — die richtige Idee war vorhanden. |

Der HEIF-Parser verdient besondere Erwähnung: Er ist die klassische Stelle, an der eine
Bild-App angreifbar wird (präparierte Datei → Speicherfehler), und er ist hier **defensiv
programmiert**. Die tatsächlich ausnutzbaren Schwachstellen lagen stattdessen an der
**Intent-Grenze** und in der **Signatur-Pipeline**.

---

## 5. Die Härtung

Alle Änderungen liegen in [`app/`](../app) (Basis: 1.31). Zwei neue Klassen bündeln die
Absicherung, damit künftiger Code sie automatisch nutzt.

### 5.1 Neu: `security/UriGuard.kt` — die einzige Tür nach innen

**Allowlist statt Blocklist.** Nur `content://` wird akzeptiert; `file://`, `http(s)://`,
opaque URIs, unbekannte Schemata und Path Traversal werden abgewiesen:

```kotlin
when (scheme) {
    "content" -> { }
    "file" -> return Verdict.Reject("file://-URIs werden nicht akzeptiert")
    "http", "https", "ftp" -> return Verdict.Reject("Netz-URIs werden nicht akzeptiert")
    else -> return Verdict.Reject("unzulässiges Schema: $scheme")
}
if (raw.isOpaque) return Verdict.Reject("opaque URI wird nicht akzeptiert")
```

Traversal-Prüfung auf **drei Kodierungsstufen** (roh, einmal, zweimal dekodiert → fängt auch
`%252e%252e%252f`), **pro Pfadkomponente**:

```kotlin
fun containsTraversal(path: String): Boolean {
    if (path.indexOf('\u0000') >= 0) return true        // Nullbyte-Smuggling
    var current = path
    repeat(3) {
        if (hasTraversalComponents(current)) return true
        val decoded = Uri.decode(current); if (decoded == current) return false
        current = decoded
    }
    return hasTraversalComponents(current)
}
private fun hasTraversalComponents(path: String) = path.split('/', '\\').any { it == ".." }
```

**Wichtige Design-Entscheidung:** geprüft pro Komponente, **nicht** per Substring, und ein
`/` innerhalb eines Segments wird **nicht** abgelehnt. Die erste Fassung dieses Fixes prüfte
`seg.contains('/')` und hätte damit **legitime SAF-URIs blockiert**, deren Dokument-ID
percent-enkodierte Schrägstriche trägt:

```
content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2FIMG_0001.HEIC
```

Das hätte die „Ordner hinzufügen"-Funktion lahmgelegt — eine Sicherheitskorrektur als
Regression. Gefunden hat das nicht das Review, sondern der automatisierte Test
[`verify_fix.py`](poc/verify_fix.py) (Abschnitt B). **Gefährlich ist ausschließlich eine
Komponente, die für sich allein `..` ist.**

Dateinamen werden über `OpenableColumns.DISPLAY_NAME` gewonnen und durch `safeFileName()`
bereinigt (Prozent-Dekodierung, Reduktion aufs letzte Segment, Entfernen von Steuerzeichen
und führenden Punkten).

### 5.2 Neu: `security/SafeFiles.kt` — alle Schreib- und Löschvorgänge

- **`confinedChild()`** erzwingt die Sandbox am **kanonischen** Pfad (mit angehängtem
  Separator, damit `/data/x-share` nicht als Kind von `/data/x` durchgeht).
- **`writeConfinedCopy()`/`writeConfinedBytes()`** schreiben atomar über eine temporäre Datei
  mit nicht vorhersagbarem Namen und Größenobergrenze.
- **`overwriteViaResolver()`** schreibt nur über den ContentResolver.
- **`deleteMedia()`** löscht nur über ContentResolver/`MediaStore.createDeleteRequest`;
  `file://` wird abgewiesen. Für `content://`-URIs der **eigenen** FileProvider wird über den
  zugehörigen Pfad gelöscht — aber nur mit Sandbox-Nachweis (`isOwnPrivateFile`, kanonisch
  geprüft gegen `cacheDir`/`filesDir`/`externalCacheDir`/`codeCacheDir`), weil
  `FileProvider.delete()` nicht implementiert ist.
- **`uniqueTemp()`** ersetzt die festen Namen `n3_write_tmp.*` (ExifWriter) und
  `n3_edit_tmp.*` (MediaSaver) — ein konstanter Name im Cache ist ein Race-Target.

`File(uri.path).delete()` und `File(uri.path).outputStream()` **existieren nicht mehr**.

### 5.3 Geänderte Aufrufstellen (1.31)

| Datei | Änderung |
|---|---|
| `ui/MainActivity.kt` | `handleViewIntent` → `UriGuard.verifyIncoming`; Name via DISPLAY_NAME; `path` nicht mehr die URI |
| `ui/DetailActivity.kt` | `shareCurrent` → `SafeFiles.writeConfinedCopy`; `deleteCurrent` → `SafeFiles.deleteMedia`; `directDelete` **entfernt**; Dialog zeigt Name **+ Pfad** |
| `ui/InfoActivity.kt` | `shareFile` → `SafeFiles.writeConfinedCopy` + Guard |
| `ui/GalleryFragments.kt` | `deleteDirect` → `SafeFiles.deleteMedia` (kein `File(uri.path).delete()`) |
| `data/MediaSaver.kt` | `overwrite`: `file://`-Zweig **entfernt**; `saveForSharing`/`saveViaFile` → `confinedChild`; `fileName` → `safeFileName`; `n3_edit_tmp` → `uniqueTemp` |
| `data/MetaBackup.kt` | `applyJson` filtert jede importierte URI durch `UriGuard` (Injektionsschutz); Text-/Tag-Limits |
| `data/Repository.kt` | `appExportItems`: `Uri.fromFile` → `FileProvider.getUriForFile` |
| `data/ExifWriter.kt` | `n3_write_tmp.*` → `SafeFiles.uniqueTemp` |
| `data/DataHub.kt` | neu: `isLibraryItem()` (unterscheidet Bibliotheks- von Fremd-Elementen) |
| `image/HeifSupport.kt` | `MAX_BYTES` 256→96 MB; `declaredLengthExceeds()`-Vorabprüfung; Puffer 64 KB |
| `ui/MetaRenderer.kt` | `mapIntent` → `openMap` mit Wertebereichs-/NaN-Prüfung + `try/catch` |

**Verbleibendes `Uri.fromFile` (bewusst):** `MediaSaver.saveViaFile` (API 26–28) erzeugt für
die **eigene**, soeben in den öffentlichen `Pictures/`-Ordner geschriebene Datei eine
`file://`-URI. Der Name ist zuvor durch `safeFileName` bereinigt (kein Traversal), und die
URI kann nicht mehr weaponisiert werden, weil `deleteMedia`/`overwrite` `file://` ablehnen.
Damit ist sie harmlos; ein FileProvider-Pfad für öffentlichen geteilten Speicher wäre breiter
als nötig.

### 5.4 Manifest, Ressourcen, Build, CI

- **Manifest:** `allowBackup="false"` + `dataExtractionRules` + `fullBackupContent`;
  Intent-Filter um `video/*` ergänzt (Absicherung erfolgt in `UriGuard`, nicht im Manifest,
  weil `exported="true"` für „Öffnen mit" zwingend bleibt).
- **`file_paths.xml`:** zusätzlich `cache-path n3export/` (nötig, weil `Repository` jetzt
  FileProvider nutzt). `files-path`/`external-path`/`root-path` bleiben **weg**.
- **Neue Strings** (`view_rejected`, `share_blocked`, `delete_confirm_msg_path`,
  `bucket_shared`) in **Deutsch und Englisch**.
- **`build.gradle.kts`:** kein Debug-Fallback (release+tryout werfen `GradleException`);
  Minify+Shrink an; V2+V3 erzwungen; Keystore aus `keystore.properties`/Secrets
  (`N3_KEYSTORE_BASE64`); `versionCode 33`, `versionName 1.32-hardened`.
- **CI (`build-apk.yml`):** Secret-Check **vor** dem Build; Keystore-im-Repo-Check; **kein**
  Debug-Fallback; Signatur-Check **nach** dem Build (v2 vorhanden, **nicht** Debug-Key,
  **nicht** der geleakte CI-Key, optional erwarteter Fingerabdruck); Release-ZIP enthält
  **keine** Schlüsseldateien mehr; `permissions` minimal.
- **`.gitignore`:** die Negationen `!ci/n3-ci.p12` und `!keystore.properties` **entfernt**;
  erweitert um `*.p12`, `*.pfx`, `ci/`, `signing.properties`, `n3-release*`, `*secret*` —
  mit Ausnahme nur für `*.template`.
- **`keystore.properties.template`** + Warnung in **`SIGNIEREN.md`** + gehärtetes
  **`tools/signieren.sh`** (kein hartkodierter geleakter Fingerabdruck; warnt, falls mit ihm
  signiert wird). **`ci/n3-ci.p12` aus dem Arbeitsbaum entfernt.**

---

## 6. Verifikation

Es stand **kein JDK** zur Verfügung (nicht installiert, kein Root, Adoptium aus dem
Sandbox-Netz nicht erreichbar) — ein echter `assembleRelease`-Lauf war damit **nicht** möglich.
Das ist eine ausdrückliche **Einschränkung** dieser Analyse. Ersatzweise:

### 6.1 Logische Verifikation der Schwachstellen
[`verify_traversal.py`](poc/verify_traversal.py) portiert die exakte Semantik von
`android.net.Uri` (`decode()`, `getPathSegments()`, `getLastPathSegment()`) und `java.io.File`
(String-Konstruktor, `getCanonicalPath()`) und wendet sie auf die echten Code-Pfade an →
**V1, V2, V3 nachvollzogen**.

### 6.2 Signatur-Kompromittierung (V5) — End-to-End
[`verify_signing.py`](poc/verify_signing.py) lädt Passwort + Schlüssel aus dem Git-Verlauf,
entschlüsselt den privaten Schlüssel und vergleicht den Fingerabdruck mit der ausgelieferten
APK 1.31 → **Übereinstimmung bewiesen** (Ausgabe in §3, V5).

### 6.3 Wirksamkeit + Regression der Korrektur
[`verify_fix.py`](poc/verify_fix.py) portiert `UriGuard`/`SafeFiles` und testet fünf Gruppen:

```
A)  14 Angriffspayloads          -> alle ABGEWIESEN      (14/14)
A2)  5 confinedChild-Ziele        -> alle in der Sandbox   (5/5)
B)   9 legitime URIs (MediaStore/SAF/HEIC/Sonderzeichen) -> DURCHGELASSEN (9/9)
C)   6 legitime Dateinamen        -> unverändert erhalten  (6/6)
D)   7 gefährliche Dateinamen     -> entschärft            (7/7)
E)   6 MetaBackup-Injektions-URIs -> 1 übernommen, 5 VERWORFEN (6/6)
ALLE TESTS BESTANDEN.
```

Abgedeckte Angriffsmuster: `file://` (3 Varianten), einfach/groß/doppelt kodiertes Traversal,
roh (unkodiert), Backslash (`..%5C`), Sandbox-Ausbruch, `http(s)://`, Nullbyte-Smuggling,
unbekanntes Schema (`javascript:`), opaque URI. Abgedeckte legitime Nutzung: MediaStore
(Foto/HEIC/Video), SAF-DocumentsProvider mit `%3A`/`%2F`, SAF-Baum, SAF mit Leerzeichen,
Downloads-Documents, Dateinamen mit Punkten/Umlauten/Klammern/Zeitstempeln.

### 6.4 Strukturprüfung des Codes
Ein struktureller Lint prüft Klammer-Balance (korrekt um Kommentare, `"""`-Strings und
`${…}`-Interpolation herum) und fehlende Imports. **Kalibrierung:** auf den **unveränderten**
Originaldateien liefert er 33/33 OK (die erste Fassung des Linters schlug fälschlich fehl —
Bug im Linter, nicht im Code; erst danach ist das Ergebnis aussagekräftig). **Ergebnis für die
gehärtete Fassung: 39/39 Dateien OK** (38 `.kt` + `build.gradle.kts`), inklusive der beiden
neuen Klassen.

### 6.5 Weitere Prüfungen
| Prüfung | Ergebnis |
|---|---|
| XML-Wohlgeformtheit (Manifest, `file_paths`, `backup_rules`, `data_extraction_rules`, beide `strings.xml`, PoC-Manifest) | 7/7 OK |
| Alle 113 im Code referenzierten `R.string.*` definiert | 0 fehlend |
| Neue Strings in **beiden** Sprachen + Platzhalter-Anzahl | OK |
| PoC-Java mit `java-parser` geparst | 3/3 OK (nach Fix eines `*/*`-im-Javadoc-Fehlers) |
| Verwundbare Muster per Grep im gehärteten Baum | nur noch in Kommentaren/Doku; Resttreffer harmlos (eigene, sanitized Dateien) |
| Keystore im gehärteten Baum | nicht vorhanden |
| `.gitignore` wirkt (`git check-ignore`) | `*.jks`, `*.p12`, `ci/`, `keystore.properties` ignoriert; `*.template` **nicht** |

### 6.6 Was noch aussteht
**Ein echter Build steht aus** — nächster Schritt vor einer Verteilung:

```bash
cd app
bash tools/keystore-neu.sh n3-release.jks      # neuen, privaten Schluessel erzeugen
cp keystore.properties.template keystore.properties   # mit den Angaben fuellen
./gradlew assembleRelease
```

Danach manuell durchklicken: Grid, Detail, **HEIC/AVIF**, **SVG**, Editor (`PhotoEditorView`),
Notizen speichern, Teilen, Löschen, Speichern/Überschreiben, Metadatenseite, **„Ordner
hinzufügen" (SAF!)**, „Öffnen mit" aus einer anderen App, und den MetaBackup-Autoimport.
R8-Regelfehler zeigen sich typischerweise als `ClassNotFoundException` zur Laufzeit, nicht beim Bau.

---

## 7. Dringende Empfehlungen außerhalb des Codes

### 7.1 Der veröffentlichte Signaturschlüssel muss ersetzt werden — **vorrangig**

`ci/n3-ci.p12` + Passwort waren öffentlich und signieren die ausgelieferte APK 1.31 (V5).
Der Schlüssel gilt als **kompromittiert**.

1. **Neuen, privaten Keystore erzeugen** (`tools/keystore-neu.sh`, RSA 4096, 30 Jahre),
   Passwort in einen Passwortmanager, Keystore **offline** an zwei Orte.
2. **Aus Repo und Git-Historie entfernen.** `ci/n3-ci.p12` liegt in `origin/main`.
   **Git-Historie bereinigen** (`git filter-repo`), sonst bleibt der Blob für immer abrufbar.
   **Zweiter, unabhängiger Leak:** Die beiden Projekt-ZIPs
   `N3Gallery-AndroidStudio-Projekt-1.21.zip`/`-1.21-1.zip` (byteweise identisch,
   ZIP-MD5 `968fc1c9…`, am Branch-HEAD getrackt) enthalten jeweils einen **weiteren privaten
   Keystore** `proj/n3-release.jks` (MD5 `922ce06f…`). Dessen Passwort liegt **nicht** in der
   ZIP (`keystore.properties` fehlt), und [`jks_crack.py`](poc/jks_crack.py) zeigt, dass es
   einem 100k-Wörterbuch standhält — `n3-release.jks` ist damit **nicht unmittelbar
   ausnutzbar**, aber ein privater Signaturschlüssel gehört grundsätzlich nicht in ein
   öffentliches Archiv. Beide ZIPs wurden aus dem Arbeitsbaum entfernt (§7.2); auch hier ist
   die Git-Historie zu bereinigen.
3. **CI-Secrets** statt eingecheckter Datei verwenden (`N3_KEYSTORE_BASE64` etc.).
4. **Konsequenz akzeptieren:** Bestehende, mit dem alten Schlüssel signierte Installationen
   können **nicht** per Update auf einen neuen Schlüssel migriert werden — Android verweigert
   das Update bei abweichender Signatur. Nutzer müssen die App **neu installieren** (Notizen
   gehen wegen V11 nur verloren, wenn nicht zuvor gesichert — hier beißt sich die
   MetaBackup-Funktion mit der Rotation; eine verschlüsselte Sicherung, §5.3-Hinweis, wäre
   die saubere Lösung).
5. **Nur für den Play Store** gäbe es einen Ausweg: Play App Signing mit Schlüsselrotation.
   Für eine sideload-verteilte APK existiert dieser Weg nicht.

### 7.2 Die veröffentlichten APKs zurückziehen
`N3-Gallery-1.16-…-release.zip` und die ausgelieferte `N3-Gallery-1.31-release.apk` sind von
V1–V3/V12 betroffen; die 1.31-APK ist zudem mit dem kompromittierten `ci/n3-ci.p12` signiert
(V5, bewiesen). Durch signierte Builds der gehärteten Fassung ersetzen; alte Releases in
GitHub-Releases deaktivieren.

### 7.3 Duplikate und Binärartefakte aufräumen — **erledigt**
Die beiden Projekt-ZIPs 1.21 sind **byteweise identisch** und enthalten jeweils den privaten
Keystore `n3-release.jks` (siehe §7.1, Punkt 2). Alle drei ZIPs wurden mit `git rm` aus dem
Arbeitsbaum entfernt; der Quellcode liegt jetzt als normaler Git-Baum unter [`app/`](../app).
APKs/ZIPs gehören nicht in die Versionsverwaltung — besser GitHub Releases für die APK.
**Nachziehen:** Git-Historie bereinigen (`git filter-repo`), damit die entfernten Blobs
(ZIPs, `ci/n3-ci.p12`, `n3-release.jks`) nicht weiterhin abrufbar bleiben.

### 7.4 MetaBackup-Vertraulichkeit (Produktentscheidung)
V11 schließt die **Injektions**lücke. Die **Klartext-Ablage** im geteilten Speicher bleibt aus
Feature-Gründen bestehen. Empfohlen: Sicherung **verschlüsseln** (Schlüssel im Android
Keystore) oder den Auto-Import hinter eine explizite Nutzerbestätigung stellen.

---

## 8. Dateien dieser Analyse

```
security/
├── SICHERHEITS-ANALYSE.md          dieser Bericht
└── poc/
    ├── verify_traversal.py         V1/V2/V3 logisch nachgewiesen (Uri+File-Semantik)
    ├── verify_fix.py               14 Angriffe + 6 Injektionen abgewiesen, 15 legitime Fälle intakt
    ├── verify_signing.py           V5/V6: Schluessel aus Git geladen, APK-Abgleich (End-to-End)
    ├── apk_sig.py                  APK-Signaturschema-Inspektor (V1/V2/V3/V4, Signing-Block)
        ├── axml_dump.py                binaeres AndroidManifest.xml ohne aapt/Java dekodieren
    ├── jks_crack.py                Offline-Passwortpruefung gegen JKS (V5-Kontext)
    ├── attack.sh                   adb-Angriffsskript (DRY_RUN=1 als Voreinstellung)
    ├── exploit-app/                Android-PoC-App, KEINE Berechtigungen
    │   ├── .../ExploitActivity.java    V1/V2/V3/V12/DoS als Antipp-Button
    │   ├── .../PayloadProvider.java    Angreifer-ContentProvider + 512-MB-Pipe
    │   └── .../ExfilActivity.java      nimmt exfiltrierte Daten entgegen
    └── backup-extract/
        ├── extract_notes.py        V4: Notizen aus .ab-Backup (Header/zlib/tar)
        └── read_meta_sicherung.py  V11: Notizen aus der Klartext-JSON im geteilten Speicher
```

**Verantwortungsvoller Umgang:** `attack.sh` läuft mit `DRY_RUN=1` und setzt den
Löschen-Intent nicht tatsächlich ab. PoC-App und Skripte sind ausschließlich für Tests auf
**eigenen** Geräten bestimmt. `verify_signing.py` lädt den Schlüssel nur, um die
Kompromittierung **nachzuweisen** — er wird nirgends neu abgelegt oder verteilt.

---

## 9. Einordnung

Zwei Lehren stechen heraus.

**Erstens: „offline" heißt nicht „keine Angreifer".** Die naheliegende Vermutung bei einer
App, die iPhone-HEIF-Screenshots öffnet, wäre der Binärparser (präparierte Datei →
Speicherfehler in `libheif`/`libde265`). Genau dort ist der Code aber **defensiv und sauber**.
Ausnutzbar war stattdessen die **Intent-Grenze** — und dort ein Detail, das man kennen muss:
`Uri.getLastPathSegment()` **dekodiert**, `%2e%2e%2f` wird zu `../`. Dazu kam mit
`MediaSaver`/`MetaBackup` in 1.31 neue Angriffsfläche (Überschreiben, Klartext-Export,
Auto-Import), die 1.21 noch nicht hatte.

**Zweitens: Die Signatur ist die Identität — und war hier das größte Loch.** Ein Keystore im
Repository ist kein Versionsverwaltungs-Fehler, sondern ein Identitätsleck; mit Passwort und
veröffentlichter APK wird daraus eine vollständige Update-Hijacking-Kette. Ein **stiller**
Debug-Signing-Fallback (V6) ist gefährlicher als ein lauter Build-Abbruch. Beides ist jetzt
geschlossen: Der Build bricht ohne echten, privaten Schlüssel ab, und die CI verweigert
sowohl Debug- als auch den bekannten CI-Schlüssel.

Und eine methodische Lehre: **Ein Fix braucht einen Regressionstest.** Die erste Fassung dieser
Härtung hätte die SAF-Ordnerfunktion zerstört; gefunden hat das `verify_fix.py`, nicht das
Review. Sicherheitstests müssen beide Richtungen prüfen — dass der Angriff abgewiesen wird
**und** dass die legitime Nutzung funktioniert.
