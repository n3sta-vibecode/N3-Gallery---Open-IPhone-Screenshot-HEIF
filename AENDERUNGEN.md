# N3 Gallery – was neu ist

## 1.25 – Apple-Prinzip: scharfe Kacheln, Hintergrund-Aufbau, Hardware-Dekoder

### a) Kein unscharfes Aufblühen mehr

Rückmeldung war: „alles ist zuerst unscharf und ladet erst dann“. Deshalb ist das
Hochrechnen einer kleinen Vorschau **komplett entfernt**:

* Eine Kachel zeigt ihr Bild erst in **echter Kachelgröße** – nichts wird mehr
  auseinandergezogen.
* Nur wenn die nächstkleinere Stufe höchstens **halb so klein** ist (also von sich aus
  scharf genug), wird sie kurz gezeigt. Sonst bleibt es bei einer ruhigen Fläche.
* Auch in der Großansicht ist die 48-px-Vorschau weg – auf Bildschirmgröße wäre sie
  zwanzigfach vergrößert gewesen (die 1024-px-Vorschau kommt weiterhin blitzschnell davor).

### b) Vorschaugrößen passend zur Kachel – der Grund für „langsam bei vielen Fotos“

Die Größenstufen sind jetzt **64 / 128 / 256 / 512 / 1024 px**. Vorher wurde bei kleinen
Kacheln (viele Spalten) eine 256-px-Vorschau dekodiert; jetzt reicht **64 px**:

* 64 statt 256 px heißt **16 × weniger Pixel** pro Kachel – genau dann, wenn viele Fotos
  nebeneinander stehen.
* Die passende Stufe wird gewählt, die nächstgrößere nur benutzt, wenn sie schon fertig ist.

### c) Hintergrund-Aufbau der ganzen Bibliothek (das Apple-Prinzip)

iOS dekodiert fürs Raster **nie** das Originalbild, sondern baut im Hintergrund eine
**Datenbank aus Vorschaubildern** in genau der Anzeigegröße auf. Genau das macht die App
jetzt auch:

* Ein **Daemon mit Hintergrund-Priorität** geht die Bibliothek in Listenreihenfolge durch,
  erzeugt jede Vorschau in Kachelgröße und legt sie **dauerhaft ab** (Speicher + Festplatte).
  Nach einem Neustart ist sie schon da.
* Schon fertige Fotos und solche, die sich nicht dekodieren lassen, werden übersprungen.
* **Beim Wischen pausiert der Daemon** (nur 8 ms Pause zwischen zwei Fotos, 90 ms solange
  gescrollt wird) – die sichtbaren Kacheln haben die CPU dann für sich allein.
* Ändert sich die Spaltenzahl, richtet sich der Aufbau automatisch auf die neue Kachelgröße
  neu aus.

### d) Hardware-Dekoder zuerst – wie iOS HEIC anzeigt

* Auf Android 10+ werden **HEIC/HEIF/AVIF zuerst vom Hardware-Dekoder** gelesen
  (`ImageDecoder`, Hardware-Puffer) – dieselbe Technik, die auf dem iPhone HEIC-Bilder
  praktisch ohne Rechenzeit darstellt. Das Ergebnis wird in ein normales Bild kopiert und
  der Hardware-Puffer sofort freigegeben.
* **Schwarze Vorschauen erkannt:** Manche 10-Bit-/HDR-HEICs liefern über den Hardware-Pfad
  schwarze Flächen. Die App prüft das Ergebnis und nimmt dann automatisch den Software-Weg.
* Fällt der System-Dekoder mehrfach aus, wird er für HEIF nicht weiter versucht – kein
  wiederholtes Warten bei jedem Foto.

### e) Warum das zusammen schnell ist

1. **Nie das Original fürs Raster** – nur die kleine Vorschau in Kachelgröße.
2. **Decodieren in genau der Anzeigegröße** – statt groß laden und klein rechnen.
3. **Einmal erzeugt, dauerhaft gespeichert** – beim zweiten Blick sofort da.
4. **Hardware statt Software** für HEIC.
5. **scrollen hat Vorrang** – Hintergrundarbeit pausiert, statt um CPU zu kämpfen.

## 1.24 – Mini-Vorschau (48 px), Zuschneiden wie bei Apple, Speichern robust

### a) Mini-Vorschau: der Vorschlag aus der Nachricht, umgesetzt

Genau wie vorgeschlagen wird zu jedem Foto eine **winzige Vorschau (48 × 48 px)** erzeugt.
Sie ist in Millisekunden verfügbar und wird **immer zuerst** angezeigt – weich
hochgerechnet, damit man sofort den Bildinhalt sieht statt einer grauen Fläche
(dasselbe Verfahren wie „Blur-up“ bei Apple/Google Fotos).

* Liegt bereits im Speicher oder auf der Festplatte → sofort da, auch nach App-Neustart.
* Ist das Raster weit herausgezoomt (viele Spalten, Kacheln ~40–60 px), ist die
  Mini-Vorschau sogar **direkt die Zielgröße** – dann braucht es gar kein großes Dekodieren.
* Bei sehr kleinen Kacheln werden Zeichnungen/Textfelder mit angezeigt.

Ehrlich dazu: Bei *normal* großen Kacheln (4–6 Spalten, ~200–300 px) skaliert keine
Vorschau beliebig hoch – dort ersetzt die Mini-Vorschau nicht das scharfe Bild, sie
überbrückt nur die Wartezeit. Deshalb bleibt die zweite Stufe (128/256/512/1024 px)
erhalten und wird weiterhin im Hintergrund vorgeladen.

> **Nachtrag 1.25:** Genau dieses Hochrechnen war in der Praxis nicht gut („zuerst
> unscharf“) und wurde in 1.25 wieder entfernt – siehe Abschnitt 1.25. Die 48-px-Stufe
> bleibt nur dort im Einsatz, wo sie die *Zielgröße* ist (weit herausgezoomtes Raster).

### b) Zuschneiden wie bei Apple

Vorher musste man genau eine Ecke treffen – deshalb war es mühsam. Jetzt:

* Der **Rahmen steht fest**, das **Foto wird darunter verschoben** (ein Finger) bzw.
  mit zwei Fingern gezoomt. Nach dem Zoomen kann man mit einem Finger weiterziehen.
* **Acht Griffe**: 4 Ecken (Winkel wie in iOS) + 4 Kantenmitten, mit großzügiger
  Trefferfläche (30 dp) – man muss nicht mehr präzise zielen.
* **Seitenverhältnisse** (Frei, 1:1, 4:3, 3:4, 16:9, 9:16) setzen den Rahmen passend und
  zoomen das Foto automatisch so, dass der Ausschnitt **immer vollständig gefüllt** ist
  (kein schwarzer Rand im Ergebnis).
* Drittel-Raster, abgedunkelter Außenbereich und „Rahmen = späteres Bild“.
* Der sichtbare Rahmen ist exakt das gespeicherte Bild (kein Umrechnen mehr).

### c) Bearbeitete Fotos speichern – jetzt robust

Der Speichervorgang hat vorher still scheitern können. Jetzt:

* **Drei Wege nacheinander:** Galerie (MediaStore) → öffentlicher Bilder-Ordner
  (Android 8/9) → app-eigener Ordner + Medien-Scan. Einer davon klappt immer.
* **Klare Meldungen** statt „nichts passiert“: fehlende Schreibfreigabe, kein
  beschreibbarer Ordner, Speichermangel beim Aufbereiten – jeweils auf Deutsch/Englisch.
* **Schreibfreigabe** wird auf Android 8/9 aktiv angefragt (auch beim Überschreiben).
* **Speichern ist immer möglich:** Auch ohne Änderung lässt sich eine Kopie anlegen.
* Beim Überschreiben wird nach dem Systemdialog zuverlässig weitergemacht; die Liste
  wird auch dann neu eingelesen, wenn der Hauptbildschirm gerade nicht sichtbar war.
* Die Bearbeitungsvorlage wird mit höherer Auflösung geladen (bis 3600 px) – ein
  1:1-Zuschnitt ist dadurch nicht mehr weicher als das Original.

---

## 1.23 – Tippen repariert, Laden beschleunigt

**Gemeldet:** „Das Anklicken von Fotos funktioniert nicht“ und „alles lädt erst später“.
Die Ursachen waren klar zu finden:

### a) Tippen: das falsche Foto (oder gar keins)

Die Detailansicht hat sich die Liste, zu der eine Position gehört, nur **global** aus dem
Speicher geholt. Sobald irgendeine andere Ansicht diese Liste überschrieb (Album, Tag,
Ordner, Sammlung, „Öffnen mit“), gehörte die Position zu einer anderen Liste – dann öffnete
ein Tipp ein **anderes Foto** oder, wenn die Position nicht existierte, **gar nichts**.

Jetzt:
* Beim Öffnen werden **Position und Bild-URI gemeinsam** übergeben.
* Die Detailansicht prüft die URI und bestimmt die Position daraus – es kann also nie
  mehr das falsche Bild erscheinen.
* Kachel-Klick kommt über eine Zuordnung „URI → Position“ aus genau der Liste, die das
  Raster gerade anzeigt (kein Suchen in fremden Listen).

### b) Laden: schneller gefüllte Kacheln

* **Vorwärmen im Hintergrund:** Nach dem Aufbau werden bis zu **600 Kacheln in
  Listenreihenfolge** fertiggestellt (also genau die, die beim Weiterscrollen drankommen).
  Diese Aufgaben liegen **hinter** den sichtbaren Bildern in der Warteschlange – Wischen
  und Tippen bleiben reaktionsschnell.
* **Vorschauen werden über Größenstufen hinweg wiederverwendet:** War schon eine andere
  Größe im Festplatten-Cache, wird sie passend skaliert statt neu dekodiert (bei HEIC/RAW
  ist Dekodieren der teuerste Schritt).
* **3–6 Dekodier-Threads** (statt 2–5), weiter mit Hintergrund-Priorität: Die Oberfläche
  verliert keine Reaktionszeit, die Kacheln füllen sich aber spürbar schneller.
* **Wischen lädt mehr vor** (60 statt 40 Kacheln je Schwung).
* Vorlade-Aufgaben legen ihre Bilder **nur auf die Festplatte**, nicht in den
  Speicher-Cache – dadurch bleiben die sichtbaren Kacheln im schnellen Speicher.
* Die Kachelgröße wird beim Spaltenwechsel neu berechnet (`requestLayout`), und die
  starre „feste Größe“ des Rasters ist entfernt – das vermeidet falsch vermessene Kacheln
  nach dem Zoomen.

---

## 1.22 – Scrollen flüssig, Foto-Editor neu

**Zwei Wünsche, zwei Baustellen – beide erledigt.**

## 1. Scrollen ruckelt nicht mehr, Bilder sind schneller da

Das Ruckeln kam nicht vom Raster selbst, sondern davon, dass der Haupt-Thread
(der Thread, der auf Finger reagiert) ständig mit anderer Arbeit zugestellt war.
Jede dieser Ursachen ist jetzt beseitigt:

| Ursache vorher | Jetzt |
|---|---|
| Für **jedes einzelne Foto** wurde beim Aufbau ein neues `SimpleDateFormat` gebaut (bei 20 000 Fotos mehrere Sekunden Haupt-Thread-Arbeit) | Ein Formatierer je Muster + Zwischenspeicher für Tages-/Monats-Titel |
| Favoriten/Notizen/Tags wurden für **jedes Foto** neu aus den Preferences gelesen | Alles liegt als Index im Speicher (einmal im Hintergrund geladen) |
| Alle **vier Tabs** wurden gleichzeitig aufgebaut und bei jeder Änderung komplett neu berechnet | Tabs sind nur aktiv, wenn sie sichtbar sind („schmutzige“ Tabs werden beim Wechsel nachgezogen) |
| Bei jeder Aktualisierung wurde das **komplette Raster neu gebaut** (`notifyDataSetChanged`) | Liste entsteht im Hintergrund, Übergabe per `DiffUtil` – neu gezeichnet wird nur, was sich wirklich ändert |
| **8 Dekodier-Threads** konkurrierten mit der Oberfläche um CPU | 2–5 Threads mit **Hintergrund-Priorität**; sichtbare Bilder haben Vorrang |
| Beim Start warteten alle Bilder, bis **alles** (inkl. SAF-Ordner) eingelesen war | Erst Medienindex (Galerie ist sofort gefüllt), eigene Ordner kommen kurz danach nach |
| Ein **kurz abgelegter zweiter Finger** brach das Scrollen ab (fühlte sich wie Verzögerung an) | Zwei-Finger-Zoom übernimmt erst, wenn wirklich gezoomt wird |

Dazu: **Vorladen** – die Bilder der nächsten ein bis zwei Bildschirme werden schon
im Hintergrund erzeugt (in der Warteschlange hinter den sichtbaren) und liegen dann
im Speicher- bzw. Festplatten-Cache (`cacheDir/thumbs`). Das Raster ist dadurch beim
Wischen durchgehend gefüllt, statt graue Kacheln zu zeigen.

## 2. Foto-Editor: zuschneiden, zeichnen, Textfelder

Erreichbar über das **Pinsel-Symbol** oben in der Großansicht, den Knopf
**„Foto bearbeiten“** im Info-Blatt oder **langes Drücken** auf eine Kachel.

* **Zuschneiden** – Rahmen mit vier Griffen, Drittel-Raster, Seitenverhältnisse
  Frei · 1:1 · 4:3 · 3:4 · 16:9 · 9:16
* **Zeichnen** – freihand, 10 Farben, Strichstärke über Schieberegler
* **Text** – Textfeld setzen (Tippen ins Bild), verschieben (ziehen),
  ändern (Doppeltipp), Farbe/Größe einstellen, duplizieren, löschen
* **Rückgängig / Wiederholen** für alle Schritte, **zwei Finger = zoomen**
* **Speichern**
  * *Als neue Kopie* → `Pictures/N3 Gallery`, erscheint sofort in der Galerie (JPEG,
    bei Transparenz/PNG-Quelle als PNG)
  * *Original überschreiben* → in die Originaldatei; verlangt Android eine Bestätigung,
    kommt der Systemdialog (bei HEIC/RAW u. Ä. wird stattdessen die Kopie angeboten)

## APK installieren (direkt auf dem Handy)

**Releases → „Testbuild 1.24“** öffnen und antippen:

| Datei | Kennung / Name | Installation |
|---|---|---|
| `N3-Gallery-1.24-TEST.apk` | `…gallery.dev` · „N3 Gallery TEST“ | läuft **parallel** zur vorhandenen App – nichts wird ersetzt, Notizen/Favoriten bleiben. **So ausprobieren:** In der App steht oben „N3 Gallery TEST“ – daran siehst du, dass die neue Version läuft. |
| `N3-Gallery-1.24-release.apk` | `…gallery` · „N3 Gallery- Open HEIF iPhone Screenshots“ | die reguläre App; **vorher alte Version deinstallieren**, weil dieser Automatik-Build mit dem CI-Schlüssel signiert ist (nicht mit `n3-release.jks`) |
| `N3Gallery-Projekt-1.24.zip` | Android-Studio-Projekt | für ein echtes Update „in place“ in Android Studio bauen – dort wird mit `n3-release.jks` signiert |

Nach dem Antippen: *„Installation aus unbekannten Quellen erlauben“* → fertig.
Läuft ab Android 8 (API 26). Keine Internet-Berechtigung, alles bleibt auf dem Gerät.

## Kurz ausprobiert?

* Scrollen: durch die Zeitleiste wischen – die Kacheln kommen jetzt gefüllt nach.
* Bilder: Pinsel-Symbol → zeichnen → Text setzen → zuschneiden → speichern.
* Zwei Finger im Raster = mehr/weniger Spalten (unverändert).
