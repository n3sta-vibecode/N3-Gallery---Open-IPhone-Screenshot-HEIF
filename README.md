# N3 Gallery — App by N3 Vibecode

Eine Android-Galerie, die aussieht und sich anfühlt wie **Apple Fotos / Samsung Gallery / Google Fotos** –
aber **fast jedes Fotoformat öffnet**: JPEG, PNG, WebP, GIF, BMP, TIFF, **HEIC/HEIF** (inkl. Apple-Screenshot-Container
mit Kachel-Raster und Gain-Map-HDR), **AVIF**, **DNG** und alle gängigen **Kamera-RAWs**
(CR2/CR3, NEF, ARW, ORF, RW2, RAF, PEF, SRW, 3FR, IIQ, X3F …) sowie **HEVC/H.265-Video**.

**Kein Internet, keine Cloud, keine Konten.** Alles bleibt auf dem Gerät.

---

## 🔑 Signieren / eigener Schlüssel

Welcher Schlüssel verwendet wird, wie man selbst APKs signiert und wie man einen **eigenen
privaten Schlüssel** (z. B. für Google Play) erstellt, steht in **[SIGNIEREN.md](SIGNIEREN.md)**.
Kurz: `tools/signieren.sh deine.apk` signiert mit dem Projekt-Schlüssel,
`tools/keystore-neu.sh` erzeugt einen eigenen.

## 🆕 Version 1.28 – Updates lassen sich installieren + Sicherung von Notizen/Favoriten

* **„App ist nicht installiert“ behoben:** Jeder bisherige Build war mit einem anderen
  Schlüssel signiert (der Buildserver erzeugt seinen Debug-Schlüssel jedes Mal neu), daher
  lehnte Android jedes Update ab. Jetzt signieren alle Builds mit einem **festen Schlüssel**
  – ab dieser Version klappen Updates direkt über die installierte App.
  *Einmalig* muss dafür die alte Version deinstalliert und 1.28 frisch installiert werden
  (oder die TEST-APK, die parallel installiert).
* **Notizen, Tags und Favoriten werden automatisch gesichert** (`Downloads/N3 Gallery/n3-sicherung.json`,
  angelegt beim ersten Start) und nach einer Neuinstallation automatisch zurückgeholt –
  die Bilder selbst bleiben ohnehin unangetastet.

## Version 1.27 – Speichern-Knopf sichtbar, HEIC viel schneller

* **Klarer Speichern-Knopf:** oben rechts jetzt ein beschrifteter, gefüllter Knopf
  „Speichern“ (vorher nur ein kleines lila Häkchen ohne Text), und der Speichern-Dialog
  hat echte Knöpfe (Kopie speichern / Original überschreiben / Abbrechen).
* **HEIC/HEIF öffnet deutlich schneller:** Der System-Decoder wird nicht mehr nach drei
  Fehlversuchen für die ganze Sitzung abgeschaltet (das ließ danach alle HEICs über den
  langsamen Software-Weg laufen), dunkle Hardware-Ergebnisse werden aufbewahrt statt
  verworfen, und beim Antippen entfällt das zweite Dekodieren derselben Datei.
* **In Anzeigegröße dekodieren** (exakte Zielgröße statt nur Zweier-Stufe) und pro Datei
  merken, welcher Weg funktioniert.

## Version 1.26 – Speichern repariert, schneller öffnen, Vorschauen im RAM

* **Bearbeitete Fotos speichern funktioniert jetzt:** Es wird geprüft, ob wirklich Daten
  geschrieben wurden (keine leeren Bilder in der Galerie mehr), der unsichtbare
  app-interne Ausweichordner ist weg (dort fand keine Galerie das Bild), und als letzter
  Ausweg bietet ein Dialog „Teilen“ an. Klappt das Aufbereiten aus Speichernot nicht,
  wird automatisch kleiner gerechnet.
* **Erstes Öffnen eines Fotos ist kürzer:** Nur die sichtbare Seite lädt das Vollbild –
  vorher dekodierten drei Seiten gleichzeitig. Gleiche Dekodierungen werden nicht mehr
  doppelt gestartet.
* **Vorschauen bleiben im Speicher:** Jede Vorschau liegt zusätzlich komprimiert im RAM
  (~25× mehr Vorschauen passen hinein als vorher) – Zurückscrollen und Zoomwechsel sind
  dadurch praktisch ohne Wartezeit.

## Version 1.25 – Apple-Prinzip: scharfe Kacheln, schneller bei vielen Fotos

* **Kein unscharfes Aufblühen mehr:** Kacheln erscheinen in echter Kachelgröße, nicht mehr
  als hochgerechnete Mini-Vorschau („zuerst unscharf“ war die Rückmeldung).
* **Viel schneller bei vielen Fotos nebeneinander:** Größenstufen jetzt 64/128/256/512/1024 px
  – kleine Kacheln lesen nur noch eine 64-px-Vorschau statt 256 px (16 × weniger Pixel).
* **Hintergrund-Aufbau wie die Apple-Thumbnail-Datenbank:** ein Daemon mit Hintergrund-Priorität
  erzeugt die Vorschauen der ganzen Bibliothek in Kachelgröße und legt sie dauerhaft ab –
  beim Wischen pausiert er, damit die sichtbaren Kacheln Vorrang haben.
* **Hardware-Dekoder zuerst für HEIC/HEIF/AVIF** (Android 10+), mit automatischem Rückfall,
  wenn der Hardware-Pfad schwarze Bilder liefert.

## Version 1.24 – Mini-Vorschau, Apple-artiges Zuschneiden, robustes Speichern

* **Mini-Vorschau (48 px) für jedes Foto:** wird immer zuerst gezeigt (weich hochgerechnet)
  statt grauer Kacheln – bei weit herausgezoomtem Raster ist sie sogar die Zielgröße.
* **Zuschneiden wie in der Apple-Fotos-App:** Rahmen steht fest, Foto wird darunter
  verschoben/gezoomt, acht Griffe mit großer Trefferfläche, Seitenverhältnisse füllen den
  Ausschnitt automatisch komplett.
* **Speichern von bearbeiteten Fotos robust:** drei Speicherwege nacheinander, klare
  Fehlermeldungen, Schreibfreigabe auf Android 8/9, höhere Auflösung der Vorlage (3600 px).

## Version 1.23 – Tippen repariert, Kacheln viel schneller gefüllt

* **Tippen auf ein Foto öffnet jetzt immer genau dieses Foto.** Vorher holte sich die
  Großansicht die Liste nur global aus dem Speicher; sobald eine andere Ansicht (Album,
  Tag, Ordner, Sammlung) sie überschrieben hatte, öffnete ein Tipp das falsche Foto oder
  gar keins. Position und Bild-URI werden jetzt gemeinsam übergeben und geprüft.
* **Kacheln füllen sich deutlich schneller:** bis zu 600 Kacheln werden nach dem Aufbau
  im Hintergrund in Listenreihenfolge fertiggestellt (hinter den sichtbaren, damit Wischen
  und Tippen Vorrang behalten), Vorschauen werden über alle Größenstufen hinweg
  wiederverwendet statt neu dekodiert, und es dekodieren 3–6 Threads mit
  Hintergrund-Priorität.
* Details und ältere Versionen: `AENDERUNGEN.md`

## Version 1.22 – flüssiges Scrollen + Foto-Editor

**Zwei Dinge, die vorher gefehlt haben:**

1. **Kein Scroll-Ruckeln mehr / nichts wartet mehr auf sich selbst.**
   Was konkret geändert wurde:
   * **Datums-Formatierung im Cache:** Vorher wurde für *jedes einzelne Foto* ein neuer
     `SimpleDateFormat` gebaut – bei 20 000 Fotos blockierte das den Haupt-Thread sekundenlang.
     Jetzt gibt es einen Formatierer je Muster und einen Zwischenspeicher für Tages-/Monats-Titel.
   * **Raster-Aufbau im Hintergrund:** Die Liste (Fotos + Überschriften + Summen) wird auf einem
     Hintergrund-Thread gebaut und über `DiffUtil` übergeben. Dadurch wird beim Aktualisieren
     oder Zoomen nur noch gezeichnet, was sich wirklich geändert hat – kein kompletter Neuaufbau.
   * **Favoriten/Notizen/Tags im Speicher:** Statt bei jedem Bild erneut in die Preferences zu
     schauen, liegen die Daten als Index im Speicher (einmal im Hintergrund geladen).
   * **Nur der sichtbare Tab rechnet:** Die vier Tabs wurden vorher alle gleichzeitig aufgebaut
     und bei jeder Änderung komplett neu berechnet. Unsichtbare Tabs werden jetzt nur als
     „schmutzig“ markiert und erst beim Wechsel wirklich aktualisiert.
   * **Dekodieren mit Hintergrund-Priorität** und weniger Threads (vorher 8, jetzt 2–5) – die
     Oberfläche bekommt dadurch jederzeit CPU-Zeit. Vorladen (Prefetch) der nächsten Bildschirme
     läuft in der Warteschlange hinter den sichtbaren Bildern.
   * **Sofort etwas zu sehen:** Beim Start wird zuerst nur der Android-Medienindex gelesen
     (Galerie ist sofort gefüllt), eigene SAF-Ordner kommen kurz danach nach.
2. **Foto-Editor: zuschneiden, zeichnen, Textfelder.**
   Erreichbar über das **Pinsel-Symbol** in der Großansicht (oben) oder **langes Drücken** auf
   eine Kachel.
   * **Zuschneiden** mit Griffen, Drittel-Raster und Seitenverhältnissen (Frei, 1:1, 4:3, 3:4, 16:9, 9:16)
   * **Zeichnen** freihand, 10 Farben, Strichstärke einstellbar
   * **Textfelder** platzieren, verschieben, doppelt antippen zum Ändern, duplizieren, löschen
   * **Rückgängig / Wiederholen** für alle Schritte, in das Bild zoomen mit zwei Fingern
   * **Speichern** als neue Kopie (`Pictures/N3 Gallery`, erscheint sofort in der Galerie) **oder**
     direkt in die Originaldatei (bei Bedarf mit Androids Bestätigungsdialog)

### APK herunterladen (direkt auf dem Handy)

**Releases → „Testbuild 1.23“** öffnen, dann eine der beiden Dateien antippen:

| Datei | Was | Installation |
|---|---|---|
| `N3-Gallery-1.23-TEST.apk` | identische App, aber als zweite App „N3 Gallery TEST“ | **parallel** installierbar – die vorhandene App und alle Notizen/Favoriten bleiben unangetastet |
| `N3-Gallery-1.23-release.apk` | die reguläre App (gleiche Kennung) | nur nach Deinstallieren der alten Version – dieser Automatik-Build ist mit dem CI-Schlüssel signiert, nicht mit `n3-release.jks` |

> Für ein echtes Update „in place“ (ohne Deinstallieren) das Projekt wie gewohnt in Android Studio
> bauen – dann wird automatisch mit `n3-release.jks` (siehe `keystore.properties`) signiert.

---

## 🚀 Sofort ausprobieren (fertige APK)

| Datei | Was | Größe |
|---|---|---|
| `release/N3-Gallery-1.5-release.apk` | **Fertig signierte Release-APK** – aufs Handy kopieren, antippen, installieren | 29 MB |

> **Neu in 1.5:** Begrüßungsdialog beim ersten Start (Logo + iPhone-Screenshot-Hinweis), Systemleisten-Abstand im
> Info-Blatt (letzte EXIF-Zeile liegt nie unter der Navigations-/Gestenleiste).
> **Neu in 1.3:** eigenes **N3-Vibecode-Logo** als App-Icon + im „Über“-Dialog, **Werbe-Banner „iPhone-Screenshots & HEIC/HEIF öffnen“** in der Zeitleiste,
> und das Info-Blatt hat nur noch zwei saubere Zustände (zu / ganz offen) – damit ist die EXIF-Liste garantiert vollständig scrollbar.
> **Neu in 1.2:** Info-/EXIF-Blatt erstmals überarbeitet – siehe „Bedienung des Info-Blattes“.
> **Neu in 1.1:** eingebauter HEIF-Decoder (libheif + libde265) – siehe „Warum HEIC/HEIF jetzt immer geht“.
> Ein Update über die alte Version (1.0) ist möglich, der Signaturschlüssel ist derselbe.
> Die 29 MB kommen fast komplett vom mitgelieferten Decoder (HEVC + AV1 für 2 CPU-Typen).

Installation: APK aufs Handy (Kabel, Cloud, USB-Stick) → im Dateimanager antippen →
*„Installation aus unbekannten Quellen erlauben“* → fertig. Läuft ab **Android 8 (API 26)** bis Android 15.
Signiert mit dem mitgelieferten Schlüssel `n3-release.jks` (CN=N3 Vibecode).

Das komplette **Android-Studio-Projekt** liegt im Ordner `N3Gallery/`
(als ZIP: `N3Gallery-AndroidStudio-Projekt.zip`).

---

## Funktionen

| Bereich | Was drin ist |
|---|---|
| **Zeitleiste** | Raster (3 Spalten) nach Tag gruppiert: „Heute“, „Gestern“, aufklappbare Datumsüberschriften mit Anzahl + Gesamtgröße |
| **Tags / Alben** | Eigene Tags mit Deckbild, plus Alben „Favoriten“ und „Mit Notiz“; Filter-Chips, Suche über Tags/Notizen |
| **Formate** | Automatische Gruppierung: RAW · HEIC/HEIF · AVIF · JPEG · PNG · WebP · GIF · TIFF · BMP · Videos (HEVC/H.264 …) |
| **Ordner** | Geräteordner (MediaStore-Buckets) und eigene per SAF hinzugefügte Ordner (für RAW/HEIC außerhalb des Medienindex) |
| **Detailansicht** | Vollbild, Wischen zwischen Bildern, Info-Blatt hochziehen, Favorit, Teilen, Löschen, „EXIF kopieren“, Tippen blendet Bedienelemente aus |
| **Metadaten** | Datei & Format, **Sensor & Aufnahme**, Kamera & Optik, **GPS**, **RAW/DNG-Details**, **HEIF/AVIF-Containertechnik**, Notizen/XMP + Rohdump **aller** EXIF-Felder |
| **Notizen & Tags** | Werden als **echte EXIF/XMP-Metadaten in die Bilddatei geschrieben** (ImageDescription, UserComment, XMP dc:description/dc:subject) |

### Warum HEIC/HEIF jetzt immer geht (Fix in 1.1)

Apple-Container sind **gekachelt**: iPhone-Fotos und Screenshots liegen als **Grid-HEIF** vor
(z. B. 8 × 6 Kacheln à 512 px, die Android erst zusammensetzen muss), häufig zusätzlich 10-Bit/HDR.
Androids System-Decoder kann das auf vielen Geräten **nicht** – Ergebnis: „kein Dekoder für dieses Format“.

Die App bringt deshalb einen eigenen Decoder mit:

* **libheif + libde265** → dekodiert HEVC-codierte HEIC/HEIF, inkl. Grid-/Kachel-Container und 10/12-Bit
* **libdav1d + libaom** → AVIF (auch auf Android 8–11, wo das System es noch nicht kann)
* Die Anzeige-Reihenfolge ist jetzt: System-Decoder → **integrierter libheif** → eingebettete Kamera-Vorschau (RAW) → eingebettetes JPEG → Kachel-Notfallweg.
* In der Detailansicht steht als kleiner Hinweis, welcher Weg benutzt wurde, und im Metadaten-Abschnitt
  **„HEIF/AVIF-Technik“** siehst du jetzt zusätzlich **Kachel-Raster (Spalten × Zeilen, Kachelgröße, Ausgabe)**
  und das **primäre Bild** des Containers.

Im Abschnitt *HEIF/AVIF-Technik* steht außerdem, ob der interne Decoder aktiv ist, und es werden
**Kachel-Raster** angezeigt, z. B. „8 Spalten × 6 Zeilen = 48 Kacheln à 512 × 512 px · Ausgabe 4032 × 3024 px“.

> Nebenbei behoben: Apple legt die Grid-Beschreibung nicht als eigene Box ab, sondern **in der `idat`-Box**
> (`construction_method = 1`). Der Inspektor löst diese Offsets jetzt korrekt auf – dadurch stimmen
> Kachel-Infos und der Notfall-Weg „JPEG im HEIF-Container“ auch bei Apple-Dateien.

### Falls ein Format trotzdem nicht angezeigt wird

1. In der Detailansicht auf **„Details & EXIF“** tippen → Abschnitt *HEIF/AVIF-Technik* öffnen.
   Dort steht jetzt genau, was in der Datei steckt (Codierung, Profil, Kacheln, Bit-Tiefe, Container-Marke).
2. Fehlermeldung enthält ebenfalls diese Kurzdiagnose – damit lässt sich das Format eindeutig bestimmen.
3. Schick mir die Datei (oder nur die Diagnosezeile), dann baue ich den passenden Weg ein.

### iPhone-Screenshots & Logo (neu in 1.3)

* **App-Icon & „Über“-Dialog** nutzen jetzt das N3-Vibecode-Logo (Laptop + Phone mit Code, Klammern-Marke).
  Alle Icon-Dichten (mdpi … xxxhdpi) inkl. adaptivem Icon und runder Variante sind im Projekt enthalten.
* **Banner in der Zeitleiste** (oberste Kachel-Reihe): „iPhone-Screenshots & HEIC/HEIF öffnen – wie am iPhone ·
  Kachel-Raster (Grid-Container), 10-Bit & HDR inklusive“. Antippen öffnet den „Über“-Dialog.
* Auch der „Über“-Text bewirbt das HEIF-Feature jetzt prominent.

Logo-Dateien: `res/drawable-nodpi/n3_brand.png` (Banner/About), `res/drawable-nodpi/n3_mark.png`,
`res/mipmap-*/ic_launcher*.png` (Launcher-Icons).

### Bedienung des Info-/EXIF-Blattes (Fix in 1.2, vereinfacht in 1.3)

Vorher klemmte das Blatt: Solange es nur „angepeekt“ unten stand, war der Scrollbereich **höher als der
sichtbare Ausschnitt** – die Liste schluckte dadurch Wischgesten, und das untere Ende der Metadaten war
nicht erreichbar. Jetzt gilt:

In 1.3 gibt es **keinen halb-offenen Zustand mehr** (`peekHeight = 0`, `skipCollapsed = true`) – das war die
Ursache für die klemmenden Wischgesten. Das Blatt ist entweder ganz zu oder ganz offen:

| Geste | Wirkung |
|---|---|
| Nach **oben** ziehen (überall am Blatt) | Blatt öffnet sich **ganz** → volle Detail-Liste, garantiert bis zum letzten Eintrag scrollbar |
| Nach **unten** ziehen | Blatt schließt sich |
| **✕** rechts oben im Blatt | Blatt schließen |
| **Zurück-Taste** | schließt zuerst das Blatt, dann die Ansicht |
| Tippen auf den **Dateinamen** im Blatt | auf-/zuklappen |
| Tippen aufs **Bild** | Kopfzeile ein-/ausblenden |

Technisch: Im offenen Zustand füllt das Blatt den ganzen Bildschirm → Scrollbereich = sichtbarer Bereich,
damit ist das Ende der EXIF-Liste immer erreichbar. Im geschlossenen Zustand wird der Listen-Scroll
gesperrt und die Touch-Events gehen direkt an das Blatt (sonst verschluckt die Liste das Hochziehen).

### Anzeige-Engine (wie Formate geöffnet werden)

1. **Video** → Einzelbild über `MediaMetadataRetriever`
2. **Moderne Formate** → `ImageDecoder` (Systemdekoder), mit automatischer EXIF-Drehung
3. **libheif (integriert)** → gekachelte Apple-Container, 10-Bit/HDR-HEIF, AVIF unter Android 8–11
4. **RAW (DNG, CR2/CR3, NEF, ARW, ORF, RW2, RAF, PEF, SRW …)** → die **eingebettete Kamera-Vorschau** wird aus der
   Datei gesucht (größter JPEG-Block) und angezeigt – exakt wie es Kamera-Apps tun
5. **Fallback** → Suche nach einem eingebetteten JPEG irgendwo im Container. Damit laufen auch exotische
   Container-Varianten, z. B. **HEIF mit JPEG-kodiertem Bild** (JPEG-Varianten nutzen dieselbe Huffman-/Zickzack-Kodierung)
6. **HEIF/AVIF-Inspektor** → liest die ISO-BMFF-Boxen (`ftyp`, `meta`, `iinf`, `iloc`, `iprp/ipco`, `iref`) direkt:
   Container-Marke, Bildanzahl, Typen (hvc1/av01/jpeg/grid), Primärgröße (ispe), Bit-Tiefe (pixi), Farbraum (colr),
   **HDR-Gain-Map** (Apple/Google), Alpha, HEVC-Profil/Level. Läuft auch dann, wenn der Systemdekoder die Datei nicht mag –
   Metadaten sieht man immer.

### Metadaten in die Datei schreiben — was geht

| Format | In die Datei schreiben | Hinweis |
|---|---|---|
| JPEG, PNG, WebP | ✅ Ja | `ImageDescription` + `UserComment` + XMP (mit Merge in vorhandenes XMP-Paket) |
| HEIC/HEIF, AVIF, DNG, RAW, TIFF | ➖ Android kann diese nicht beschreiben | Notiz bleibt in der App; zusätzlich **XMP-Sidecar** exportierbar (`.xmp`, wie Lightroom bei RAW) |

Android verlangt beim Schreiben in fremde Fotos ab Android 10 eine kurze Systembestätigung – die App fragt sie automatisch an
(`MediaStore.createWriteRequest`), schreibt danach in die Originaldatei und zeichnet die Vorschau neu.

---

## Selbst bauen

**Voraussetzungen:** Android Studio (Ladybug oder neuer) mit **JDK 17**, **AGP 8.11.1**, **Gradle 8.13**,
**compileSdk 36** (der mitgelieferte HEIF-Decoder verlangt 36), **minSdk 26**.
Für andere CPU-Typen (Emulator) in `app/build.gradle.kts` einfach `"x86_64"` zu `abiFilters` hinzufügen.

### Variante A — Android Studio (empfohlen)

1. **Android Studio** öffnen → *Open* → Ordner **`N3Gallery`** auswählen
2. Warten, bis Gradle synchronisiert hat (JDK 17: *Settings → Build Tools → Gradle → Gradle JDK 17*)
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
4. Die APK liegt danach in `app/build/outputs/apk/release/app-release.apk`
   → aufs Handy kopieren, antippen, „Installation aus unbekannten Quellen“ erlauben.

Oder per Kommandozeile:

```bash
cd N3Gallery
./gradlew assembleRelease      # signierte APK (siehe keystore.properties)
./gradlew installRelease       # direkt aufs angeschlossene Gerät
```

### Variante B — GitHub Action (APK ohne Android Studio)

Der Workflow `.github/workflows/build-apk.yml` ist enthalten: Repository anlegen, Projekt pushen,
unter **Actions → „APK bauen“ → Run workflow**. Am Ende liegt die `app-release.apk` als
**Artifact** zum Herunterladen bereit.

### Signatur

Im Projekt liegt ein eigener Release-Key (`n3-release.jks`, Passwort/Alias in `keystore.properties`).
Damit ist die APK **fertig signiert und installierbar** – auch ohne Play Store. Für eine eigene Veröffentlichung
einfach beides ersetzen:

```bash
keytool -genkeypair -v -keystore mein-key.jks -alias mein-alias \
  -keyalg RSA -keysize 2048 -validity 10950
# dann keystore.properties anpassen
```

Fehlt `keystore.properties`, baut Gradle automatisch mit dem Debug-Key – ebenfalls installierbar.

---

## Technik

* Kotlin, Material 3, ViewPager2, RecyclerView, Coroutines — **100 % offline**, keine Internet-Berechtigung im Manifest
* `minSdk 26` (Android 8) · `targetSdk 35` (Android 15)
* Paketname: `com.n3vibecode.gallery`
* Wichtige Klassen:
  * `data/Repository.kt` – MediaStore + SAF-Ordner einlesen
  * `data/ExifRepository.kt` – komplette Metadaten-Aufbereitung
  * `data/ExifWriter.kt` – Notizen/Tags als EXIF/XMP schreiben (inkl. Rechte-Handling & XMP-Merge)
  * `image/Decoder.kt` – Anzeige-Engine (4-stufig, siehe oben)
  * `image/HeifInspector.kt` – HEIF/AVIF-Box-Parser
  * `image/JpegFinder.kt` – eingebettete Vorschauen aus RAW/Exoten finden
  * `ui/*` – Zeitleiste, Tags-Alben, Formate, Ordner, Detailansicht
* `image/HeifSupport.kt` – eingebauter HEIF/AVIF-Decoder (libheif)

### Lizenz-/Patent-Hinweis

Die Bibliothek `io.github.awxkee:avif-coder` ist BSD-3/Apache-2.0 lizenziert und bindet libheif (LGPL),
libde265 (LGPL) sowie libdav1d (BSD) ein. HEVC/HEIC ist patentbehaftet – für private Nutzung und eigene
Geräte unproblematisch, für eine kommerzielle Veröffentlichung im Play Store bitte die Lizenzfragen prüfen.

### Berechtigungen (bewusst minimal)

* `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (+ `READ_MEDIA_VISUAL_USER_SELECTED` ab Android 14)
* `READ_EXTERNAL_STORAGE` (nur Android ≤ 12)
* `ACCESS_MEDIA_LOCATION` (ungezensierte GPS-Daten)
* `WRITE_EXTERNAL_STORAGE` (nur Android ≤ 10, fürs Metadaten-Schreiben)
* **Kein** Internet, **kein** Netzwerkzugriff, **keine** Analyse-Bibliotheken

---

*App by N3 Vibecode*

## Neu in 1.5 – Metadaten-Seite (voll scrollbar)
- Im Bildschirm gibt es jetzt den Button **„Metadaten“**. Er öffnet eine **eigene Vollbild-Seite** (wie in Google Fotos) mit allen EXIF-/Sensor-/Format-Daten – ganz normal scrollbar bis zum letzten Eintrag, ohne Bottom-Sheet-Mechanik.
- Die Seite hat eine Toolbar mit Zurück-Pfeil, Favorit (★), Teilen (Datei) und dieselben Aktionen wie das Info-Blatt: Notiz & Tags, „EXIF kopieren“, „Als Text teilen“.
- Das Info-Blatt bleibt zusätzlich erhalten – beide Wege führen zu denselben Daten.

## Neu in 1.6
- **ℹ️ öffnet jetzt die Vollbild-Metadaten-Seite** (kein Bottom-Sheet) – die Liste scrollt dort immer vollständig bis zum letzten Eintrag. Das klassische Info-Blatt gibt es weiterhin per **Langdruck** auf ℹ️.
- **Dauerhafte Werbe-Leiste** direkt unter der Titelleiste („★ iPhone-Screenshots & HEIC/HEIF öffnen – wie am iPhone“, antippen → Info-Dialog) – auf allen Tabs sichtbar.
- **HEIC-Badge** in der Metadaten-Seite: bei Apple-HEIF steht oben „★ iPhone-HEIC geöffnet (N3-Decoder)“, bei AVIF entsprechend.
- Leerer Zustand wirbt mit: „iPhone-Screenshots (HEIC/HEIF) werden unterstützt – auch Kachel-Raster, 10-Bit und HDR.“
- Button „Metadaten-Seite“ steht in der Detailansicht jetzt an erster Stelle und ist fett hervorgehoben.

## Neu in 1.7 – Zoom & flüssiges Scrollen
- **Zoom im Vollbild:** Zwei Finger = zoomen (bis 6×, Brennpunkt unter den Fingern), **Doppeltipp = 1× ⇄ 2,5×**, ein Finger wenn gezoomt = Bild verschieben. Während des Zoomens blättert der Bildwechsler nicht um.
- **Zoom im Raster (mehr Fotos auf einmal):** Im Raster zwei Finger zusammenziehen = mehr Spalten (bis 8), auseinanderziehen = größere Vorschau (bis 2 Spalten). Zusätzlich über das Menü **„Rastergröße (mehr Fotos)“** einstellbar. Einstellung wird gemerkt.
- **Metadaten-Seite scrollt jetzt frei und vollständig:** Das alte Layout schob den unteren Teil der Seite (Koordinator-Layout + AppBar) aus dem Bild – dadurch waren die letzten Zeilen unerreichbar. Jetzt: feste Kopfzeile, Scrollbereich mit dem ganzen Platz, feste Buttonleiste, Ränder für Status- und Navigationsleiste.
- **Info-Blatt in der Detailansicht:** Beim offenen Blatt ist das Blatt selbst nicht mehr ziehbar (das hatte das Scrollen der Liste gestört) – die Liste scrollt frei; geschlossen wird über ✕, Zurück, die Kopfzeile oder den Griff nach unten.

## Neu in 1.8 – Rastergröße & Zeitleiste nach Tag/Monat/Jahr
- **Mehr als 3 Fotos nebeneinander:** In der Zeitleiste sitzt jetzt eine Steuerleiste mit **−** / **+** und der Anzeige „4 Spalten“. Einstellbar von **2 bis 10 Spalten**. Zusätzlich weiterhin: zwei Finger im Raster zusammenziehen/auseinanderziehen, oder Menü → „Rastergröße (mehr Fotos)“. Standard ist jetzt **4 Spalten**.
- **Gruppierung frei wählbar:** Chips **Tag · Monat · Jahr · Alle Fotos** oben in der Zeitleiste. Bei „Monat“ z. B. „Oktober 2026 · 214 Dateien · 3,2 GB · 1.–31. Okt 2026“, bei „Jahr“ „2026“, bei „Alle Fotos“ eine durchgehende Liste ohne Überschriften. Die Wahl wird gemerkt.
- Menüpunkt „Rastergröße (mehr Fotos)“ jetzt mit Icon direkt in der Titelleiste (falls dort Platz ist).
- Willkommens-Dialog bewirbt die neuen Bedienelemente.

## Neu in 1.9 – Zwei-Finger-Zoom im Raster (live)
- **Finger zur Mitte ziehen** → die Kacheln werden **live kleiner und zahlreicher** (mehr Fotos nebeneinander, bis 10 Spalten)
- **Finger auseinanderziehen** → die Kacheln werden **größer und weniger** (bis 2 Spalten)
- Die Spaltenzahl folgt dem Fingerabstand direkt (4 Spalten + halber Abstand = 8 Spalten). Während der Geste erscheint mittig eine Einblendung „6 Spalten“, jede Stufe wird gespeichert und fühlt sich mit kurzem Tipp-Feedback an.
- Technisch: eigener `RecyclerView.OnItemTouchListener` (statt setOnTouchListener) – das Raster bekommt die Zwei-Finger-Geste zuverlässig, angetippte Kacheln bleiben nicht „gedrückt“ hängen, und das Wischen zwischen den Tabs greift nicht mehr dazwischen (`requestDisallowInterceptTouchEvent`).

## Neu in 1.10 – Zwei-Finger-Zoom jetzt garantiert (eigene Raster-Klasse)
- Die Geste läuft jetzt in `widget/ZoomGridRecyclerView` und wird in `dispatchTouchEvent` abgefangen – also **ganz früh im Ereignisweg**, bevor Kacheln, Scrollen oder das Wischen zwischen den Tabs etwas davon merken. `requestDisallowInterceptTouchEvent(true)`, solange zwei Finger aufliegen.
- `onInterceptTouchEvent` gibt bei zwei Fingern `true` zurück: keine Kachel bleibt gedrückt, kein Foto öffnet sich versehentlich.
- **Finger zusammenziehen = mehr Fotos** (kleinere Kacheln, bis 10 Spalten) · **auseinanderziehen = größer und weniger** (bis 2 Spalten). Live-Einblendung „6 Spalten“ + kurzes Tipp-Feedback.
- Beim ersten Start nach dem Update erscheint unten ein **Hinweis-Overlay**: „Zwei Finger zusammenziehen = mehr Fotos auf einmal“ (nur einmal, danach nie wieder).
- Weiterhin zusätzlich: Knöpfe −/+ in der Steuerleiste, Menü „Rastergröße (mehr Fotos)“, Chips Tag/Monat/Jahr/Alle.

## Neu in 1.11 – Rauszoomen wie in Google Fotos + Rückwege aus dem Vollbild
- **Im geöffneten Foto: zwei Finger zusammenziehen** (über die Normalgröße hinaus) → zurück zur Übersicht mit allen Fotos. Genau das Verhalten der Google-Fotos-App.
- **Im geöffneten Foto: nach unten wischen** → ebenfalls zurück zur Übersicht.
- Hinweistext im Vollbild erweitert: „Zwei Finger = zoomen · Doppeltipp = 2× · Zusammenziehen bis klein oder nach unten wischen = alle Fotos“.
- Raster: Zwei-Finger-Geste wird nur noch an **einer** Stelle gefüttert (kein Doppel-Ereignis), −/+ Knöpfe jetzt auch in der **Formate**-Ansicht.
- Hinweis zum Prüfen der Version: Menü ⋮ → „Über die App“ zeigt „N3 Vibecode Gallery 1.11“.

## Neu in 1.12 – neuer Name: „N3 Photos - Open iPhone HEIF Screenshots“
- **App-Name (Launcher, Einstellungen → Apps, Task-Wechsler):** „N3 Photos - Open iPhone HEIF Screenshots“
- Kurzform im App-Kopf, im Willkommens- und Über-Dialog sowie in Fußzeilen: **N3 Photos**
- In die Datei geschriebener Tool-Name (EXIF `Software`, XMP `CreatorTool`): **N3 Photos**
- Paketname bleibt `com.n3vibecode.gallery` → **Update über alle bisherigen Versionen möglich** (kein Deinstallieren nötig)
- Enthält zusätzlich alle Änderungen aus 1.11 (Rauszoomen im Foto → Übersicht, Wischen nach unten → Übersicht, −/+ auch in der Formate-Ansicht)

## Neu in 1.13 – Zweisprachig (EN/DE), neuer Name, bis 32 Spalten
- **App-Name:** „N3 Gallery- Open HEIF iPhone Screenshots“ (Kurzform in der Titelleiste: „N3 Gallery“)
- **Englisch + Deutsch:** Standardsprache ist Englisch (`res/values/strings.xml`), Deutsch liegt in `res/values-de/strings.xml`. Die App folgt der Systemsprache; zusätzlich Menü ⋮ → **Sprache / Language** mit Systemsprache · Deutsch · English (`AppCompatDelegate.setApplicationLocales`, AndroidX-Dienst für Android < 13 ist im Manifest angemeldet).
- **Metadaten zweisprachig:** `data/Labels.kt` übersetzt Abschnittstitel, Zeilenbeschriftungen und zusammengesetzte Werte (Kacheln, Spalten, Pixel …). Fußzeile, „Alle EXIF-Felder“, Klartext-Export (Kopieren/Teilen) nutzen dieselbe Quelle.
- **Datumsangaben folgen der App-Sprache** (`Fmt` nutzt jetzt die Locale der App-Konfiguration; „Heute/Gestern/Vorgestern“ und „Unbekanntes Datum“ kommen aus den Ressourcen).
- **Bis zu 32 Spalten:** Rastergrenze von 10 auf 32 erhöht; −/+ arbeiten mit sinnvoller Schrittweite (1 / 2 / 4); Dialog bietet 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32. Bei ≥ 12 Spalten werden Kachel-Beschriftungen (RAW/HEIC, Video-Symbol, Favoriten-Herz) ausgeblendet, damit die Miniaturen sauber bleiben; Vorschaugröße bis 64 px herunter.
- Alle restlichen fest eingebauten deutschen Texte (Toasts, Dialogtitel, HEIF-Hinweis, Sidecar) sind jetzt Ressourcen.

## Neu in 1.14 – Übersicht „alle Fotos auf einen Blick“ (kein Raster-Limit mehr)
- **Zieht man im Raster über die 32 Spalten hinaus**, öffnet sich automatisch die **Übersicht**: alle Fotos der Liste als **ein** Mosaik, das genau auf den Bildschirm passt – 500, 5 000, 50 000 Fotos, kein Scrollen, keine Grenzmeldung.
- Die Kacheln werden nach und nach gefüllt (Ansicht ist sofort da, Fortschritt „1 234 / 5 678“ oben rechts, Zeitbudget 30 s). **Tippen auf eine Kachel** öffnet das Foto groß; **zwei Finger** zoomen in die Übersicht hinein; Zurück/← schließt.
- Erreichbar außerdem über das Menü ⋮ → **„Übersicht – alle Fotos auf einem Bildschirm“** und über **„+“**, wenn die Rastergrenze erreicht ist.
- `widget/MosaicView`: Mosaik-Bitmap (RGB_565, max. 1600 × 3200), Matrix-Zoom/Pan, Trefferabbildung vom Tipp auf die Kachel (Index → `DetailActivity`).
- Datei-Auswahl-Dialog der Rastergröße bietet weiterhin 2–32 Spalten; darüber hinaus übernimmt die Übersicht.
- App-Name in der Kopfzeile: jetzt der **volle Name** (zweizeilig) statt der Kurzform; Über-Dialog trägt den vollen Namen als Titel.

## Neu in 1.15 – Icon, Sprachknopf, Rechtliches & Kontakt
- **Neues App-Icon:** nur Laptop + Telefon aus dem N3-Vibecode-Logo (ohne angeschnittenes „<N3“) – Legacy-Icon (abgerundetes Quadrat), rundes Icon und adaptiver Vordergrund (transparent, Motiv im sicheren Bereich) in allen Dichten neu erzeugt.
- **Sichtbarer Sprachknopf in der Titelleiste:** zeigt „EN“ bzw. „DE“ und schaltet mit einem Tipp zwischen Deutsch und Englisch um (`AppCompatDelegate.setApplicationLocales`). Zusätzlich weiterhin Menü ⋮ → Sprache.
- **Über-Dialog:** Haftungsausschluss („Diese App wird kostenlos und ohne jede Gewährleistung bereitgestellt … keine Haftung, insbesondere nicht für Datenverluste …“) und **Kontakt: n3-vibecode@programmer.net** (antippbar, öffnet das Mailprogramm). Beides in Englisch und Deutsch.

## Neu in 1.16 – neues App-Icon
- Neues Icon aus dem gelieferten Bild: randlos im Apple-Stil (eckig mit 22 % Radius), runde Variante für runde Launcher, adaptives Icon (Motiv mit Rand auf kräftigem Blau #2E8AEF als Hintergrund) plus passende Monochrom-Variante.
- Alle Dichten (mdpi 48 … xxxhdpi 192) neu erzeugt; Prüfung im Paket: 15 farbige Icon-PNGs vorhanden.

## Neu in 1.17 – Sprachwahl beim ersten Start
- **Willkommens-Dialog fragt jetzt die Sprache ab:** Knöpfe **„Deutsch“** und **„English“** direkt im Begrüßungsdialog – ein Tipp, die App startet sofort in der gewählten Sprache.
- Zusätzlich weiterhin: **„EN“/„DE“-Knopf in der Titelleiste** und Menü ⋮ → Sprache (Systemsprache · Deutsch · English).
- Icon (neu seit 1.16), Haftungshinweis + Kontakt `n3-vibecode@programmer.net` im Über-Dialog sind enthalten.
- Prüfung im Paket: Englisch („Timeline“, „File name“) **und** Deutsch („Zeitleiste“, „Dateiname“) sind enthalten; 15 farbige Icon-Bilder in allen Dichten.

## Neu in 1.18 – Markenbilder auf das neue Icon umgestellt
- Alle Logo-Bilder **in der App** (Willkommens-Dialog, Über-Dialog, Banner in der Zeitleiste) zeigen jetzt das neue Icon + Schriftzug „N3 Gallery · Open HEIF iPhone Screenshots · App by N3 Vibecode“ (`drawable-nodpi/n3_brand.png` 1000 × 400, `n3_mark.png` 512², `n3_mark_small.png` 320²).
- Kopfzeile der App: voller Name „N3 Gallery - Open HEIF iPhone Screenshots“ (wird automatisch gekürzt, nichts wird mehr abgeschnitten).
- Sprachen: Englisch (Standard) + Deutsch, umschaltbar per Willkommens-Dialog, „EN/DE“-Knopf in der Titelleiste und Menü ⋮ → Sprache.

## Neu in 1.19 – Fotos laden in Echtzeit, Rauszoomen zeigt wirklich mehr

- Kacheln sind jetzt quadratisch (vorher feste Höhe 120 dp → beim Rauszoomen passten nie mehr Fotos auf den Bildschirm).
- Bildlader: neueste Anfrage zuerst, veraltete Anfragen werden abgebrochen, 6 Threads, Systemvorschau (Android 10+), Festplatten-Cache, Größenstufen.
- Gesamtübersicht lädt parallel und ohne 30-s-Limit (die ältesten Fotos blieben vorher dunkel).
- Überschriften und Banner spannen über die ganze Zeile.

## Neu in 1.20 – Antippen zeigt das Foto sofort

- Großansicht zeigt beim Antippen sofort die Raster-Kachel, dann eine 1024-px-Vorschau, dann das Vollbild (statt Ladekreis bis zum Vollbild).
- Vollbild wird in Bildschirmgröße (×1,25) statt ×2 dekodiert – deutlich schneller bei 50/200-MP-Fotos.
- Nachbarfotos werden beim Wischen im Voraus geladen.

## Neu in 1.21 – Großansicht hat Vorrang

- Vorschau und Vollbild laufen auf eigenen Threads (3) und warten nicht mehr hinter den Raster-Kacheln; Raster-Laden pausiert kurz, solange ein angetipptes Foto lädt.
