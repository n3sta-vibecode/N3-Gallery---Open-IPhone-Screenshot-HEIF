# N3 Gallery – was neu ist

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

**Releases → „Testbuild 1.23“** öffnen und antippen:

| Datei | Kennung / Name | Installation |
|---|---|---|
| `N3-Gallery-1.23-TEST.apk` | `…gallery.dev` · „N3 Gallery TEST“ | läuft **parallel** zur vorhandenen App – nichts wird ersetzt, Notizen/Favoriten bleiben. **So ausprobieren:** In der App steht oben „N3 Gallery TEST“ – daran siehst du, dass die neue Version läuft. |
| `N3-Gallery-1.23-release.apk` | `…gallery` · „N3 Gallery- Open HEIF iPhone Screenshots“ | die reguläre App; **vorher alte Version deinstallieren**, weil dieser Automatik-Build mit dem CI-Schlüssel signiert ist (nicht mit `n3-release.jks`) |
| `N3Gallery-Projekt-1.23.zip` | Android-Studio-Projekt | für ein echtes Update „in place“ in Android Studio bauen – dort wird mit `n3-release.jks` signiert |

Nach dem Antippen: *„Installation aus unbekannten Quellen erlauben“* → fertig.
Läuft ab Android 8 (API 26). Keine Internet-Berechtigung, alles bleibt auf dem Gerät.

## Kurz ausprobiert?

* Scrollen: durch die Zeitleiste wischen – die Kacheln kommen jetzt gefüllt nach.
* Bilder: Pinsel-Symbol → zeichnen → Text setzen → zuschneiden → speichern.
* Zwei Finger im Raster = mehr/weniger Spalten (unverändert).
