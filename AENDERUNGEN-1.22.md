# N3 Gallery 1.22 – was neu ist

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

**Releases → „Testbuild 1.22“** öffnen und antippen:

| Datei | Kennung / Name | Installation |
|---|---|---|
| `N3-Gallery-1.22-TEST.apk` | `…gallery.dev` · „N3 Gallery TEST“ | läuft **parallel** zur vorhandenen App – nichts wird ersetzt, Notizen/Favoriten bleiben |
| `N3-Gallery-1.22-release.apk` | `…gallery` · „N3 Gallery- Open HEIF iPhone Screenshots“ | die reguläre App; **vorher alte Version deinstallieren**, weil dieser Automatik-Build mit dem CI-Schlüssel signiert ist (nicht mit `n3-release.jks`) |
| `N3Gallery-Projekt-1.22.zip` | Android-Studio-Projekt | für ein echtes Update „in place“ in Android Studio bauen – dort wird mit `n3-release.jks` signiert |

Nach dem Antippen: *„Installation aus unbekannten Quellen erlauben“* → fertig.
Läuft ab Android 8 (API 26). Keine Internet-Berechtigung, alles bleibt auf dem Gerät.

## Kurz ausprobiert?

* Scrollen: durch die Zeitleiste wischen – die Kacheln kommen jetzt gefüllt nach.
* Bilder: Pinsel-Symbol → zeichnen → Text setzen → zuschneiden → speichern.
* Zwei Finger im Raster = mehr/weniger Spalten (unverändert).
