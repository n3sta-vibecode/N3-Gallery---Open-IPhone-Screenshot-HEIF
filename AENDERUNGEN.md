# N3 Gallery – was neu ist

## 1.31 – SVG (Vektorgrafik) wird angezeigt

**Neu:** Die App öffnet jetzt **SVG-Dateien** – und auch **SVGZ** (gzip-gepackt).

Android kann SVG nicht von sich aus anzeigen (es kennt nur die eigene VectorDrawable-Form),
deshalb ist ein schlanker SVG-Renderer mit eingebaut (Apache-2.0-Lizenz, ca. 190 kB).

**Wie es arbeitet:** Ein SVG ist keine Bilddatei mit Pixeln, sondern eine Zeichenanleitung.
Statt zu „dekodieren“ zeichnet die App das Bild **direkt in der gebrauchten Größe**:

* Kachel im Raster (z. B. 64 oder 256 px) → es entstehen nur diese Pixel.
* Großansicht → Bildschirmgröße.
* Ergebnis: **in jeder Größe gestochen scharf**, und der Speicherbedarf bleibt winzig
  (ein 2000 × 2000 px großes SVG belegt als Kachel nur wenige Kilobyte – statt 16 MB als
  volles Rasterbild).
* Transparenz bleibt erhalten; fehlt eine Größenangabe im SVG, wird eine quadratische
  Fläche angenommen, damit nichts abgeschnitten wird.

**Wo SVG-Dateien auftauchen:** im Medienindex (Dateien mit MIME-Typ `image/svg+xml`) und in
allen per „Ordner hinzufügen“ eingebundenen Ordnern (SD-Karte, Download, NAS-Sync). Die
Formatecke zeigt „SVG“, und im Format-Tab gibt es die Gruppe „SVG (Vektorgrafik)“.

Bearbeiten/Speichern: SVG lässt sich wie gewohnt bearbeiten; gespeichert wird die Bearbeitung
als **neue Kopie als JPEG** (SVG kann die App nicht zurückschreiben) – das Original bleibt
unverändert. Das steht auch so im Speichern-Dialog.

## 1.30 – Ordnergrößen stimmen wieder, Scrollen mit geladenen Bildern flüssig

### a) „Bei allen Ordnern steht 0 Dateien“ – ein Fehler in der Zahl

Die Anzeige baute den Text einmal mit **0** auf (`„0 Dateien“`) und setzte danach nur noch
diese fertige Zeichenkette ein. Der Platzhalter war also schon verbraucht – deshalb stand
überall 0, egal wie viele Fotos drin waren. Jetzt wird die Zahl pro Zeile richtig
eingesetzt: bei **Geräteordnern**, **eigenen (SAF-)Ordnern**, **Favoriten**, **Mit Notiz**
und **Tags**.

### b) Scrollen: die Ursache war Dekodieren im Haupt-Thread

Die Vorschauen liegen seit 1.26 zusätzlich **komprimiert** im Arbeitsspeicher (das spart
viel Platz). Beim Anzeigen einer Kachel wurden diese Bytes aber **im Haupt-Thread**
ausgepackt – also mitten im Bildaufbau ein JPEG entpacken, pro Kachel. Genau das ruckelte:
je mehr Bilder schon geladen waren, desto mehr Arbeit pro Bildschirm.

Jetzt:

* Im Haupt-Thread werden nur noch **fertige Bilder** gesucht. Fehlt eines, wird die
  Vorschau im **Hintergrund** ausgepackt und dann eingesetzt (das dauert 1–3 ms, es wird
  nur eine stille Fläche gezeigt statt eines Rucklers).
* **Vorladen nur noch, wenn der Finger ruht.** Vorher wurden mitten im Wischen alle 220 ms
  bis zu 60 Vorladeaufträge abgeschickt und nahmen den sichtbaren Kacheln die Rechenzeit.
  Jetzt wird direkt nach dem Wischen vorgeladen (doppelte Menge), während des Wischens
  gehört die CPU den sichtbaren Kacheln.
* **Kein Layout-Durchlauf pro Kachel:** Der Kachelabstand wird nur noch gesetzt, wenn er
  sich wirklich ändert (vorher rief jede Kachel beim Binden ein `setPadding` auf und löste
  damit einen kompletten Layout-Durchlauf des Rasters aus).
* **Rastergröße ist fest** (`setHasFixedSize`) – die RecyclerView überspringt dadurch
  unnötiges Neuvermessen.
* Die komprimierte Ablage wird nur noch bis 512 px geführt (Kachelgrößen). Das
  Komprimieren der 1024er-Stufe kostete Rechenzeit, die beim Scrollen fehlt.

## 1.29 – Löschen-Knopf ist jetzt da, wo man ihn sucht

Rückmeldung war: „Der Foto löschen Knopf fehlt.“ Er war vorhanden, aber an einer Stelle,
an die man praktisch nicht kommt: als **letztes Element einer seitlich scrollbaren Reihe**
im **hochziehbaren Info-Blatt**. Man musste also erst das Blatt aufziehen und die Reihe
seitlich schieben – daher wirkte er nicht existent.

Jetzt gibt es drei gut erreichbare Wege:

* **Papierkorb-Symbol oben rechts** in der Großansicht – direkt neben Teilen, immer sichtbar
  (wie in der Apple-Fotos-App).
* **Im Blatt steht „Löschen“ jetzt ganz vorne** in der Reihe und ist rot eingefärbt.
* **Im Raster:** langes Drücken auf ein Foto → „Löschen“ im Menü.

In allen Fällen kommt zuerst eine Rückfrage mit dem Dateinamen; ab Android 10 anschließend
die **System-Bestätigung** von Android (die kann man nicht umgehen – das ist Absicht, damit
nichts versehentlich verschwindet). Danach wird die Mediathek neu eingelesen und das Raster
aktualisiert.

## 1.28 – Updates installieren wieder (fester Signaturschlüssel) + Sicherung von Notizen/Favoriten

### a) Warum „App ist nicht installiert“ kam – und was geändert wurde

Android erlaubt ein Update **nur**, wenn die neue APK mit **demselben Schlüssel** signiert ist
wie die bereits installierte App. Bisher signierte der Buildserver mit dem *Debug*-Schlüssel,
den er **bei jedem Lauf neu erzeugt** – jede Version hatte also eine andere Signatur. Folge:
Das Update wurde abgelehnt („App wurde nicht installiert“), obwohl die App installiert war.

Jetzt:

* Ein **fester Schlüssel** liegt im Projekt (`ci/n3-ci.p12`, dazu `keystore.properties`).
  Er ist **nicht** der private Release-Schlüssel, sondern nur für die Testbuilds gedacht.
* **Release und Test** werden damit signiert – Updates funktionieren damit auch für die
  TEST-App.
* Der Build schreibt den **Signatur-Fingerabdruck** ins Protokoll. Daran sieht man sofort,
  dass er bei jeder Version gleich bleibt.

**Einmalig:** Die gerade installierte Version stammt noch vom alten Schlüssel. Deshalb
einmal **deinstallieren** und 1.28 frisch installieren (oder die TEST-APK nehmen, die
parallel installiert). Ab 1.28 lassen sich Updates dann direkt über die App installieren.

Ehrlich dazu: Der CI-Schlüssel liegt im Repository, damit die Builds ohne Zugangsdaten
laufen. Für dieses Projekt ist das in Ordnung; wer später eine geheim gehaltene Signatur
möchte, kann den Schlüssel in ein GitHub-Secret verschieben.

### b) Notizen, Tags und Favoriten überleben die Neuinstallation

Da für den Schlüsselwechsel einmal deinstalliert werden muss, sichert die App diese drei
Dinge jetzt automatisch:

* Nach jeder Änderung (verzögert, im Hintergrund) schreibt sie eine kleine JSON-Datei
  `n3-sicherung.json` nach `Downloads/N3 Gallery/`.
* Beim Start holt sie die Sicherung automatisch zurück, wenn die App noch keine eigenen
  Notizen/Favoriten hat (also nach einer frischen Installation) – und legt sie beim ersten
  Start gleich selbst an, damit sie schon existiert, bevor jemand deinstalliert.
* Die **Bilddateien** werden nie verändert; bearbeitete Kopien liegen ohnehin in der Galerie.

### c) Signatur: Schlüssel und Anleitung im Projekt

* **`SIGNIEREN.md`** beschreibt alles: Schlüsseldaten, Fingerabdrücke und wie man mit
  Android Studio, Gradle oder `apksigner` signiert – plus wie man einen **eigenen privaten
  Schlüssel** für Google Play erstellt.
* **`tools/signieren.sh`** signiert eine vorhandene APK mit dem Projekt-Schlüssel und prüft
  den Fingerabdruck, **`tools/keystore-neu.sh`** erzeugt einen eigenen Schlüssel,
  **`tools/keystore-info.sh`** zeigt Inhaber und Fingerabdrücke an.
* Das Projekt-ZIP im Release enthält jetzt **Schlüssel, Anleitung und Skripte**, sodass
  eigene Builds dieselbe Signatur haben und sich weiterhin aktualisieren lassen.

## 1.27 – Speichern-Knopf sichtbar, HEIC deutlich schneller

### a) „Kein Speichern-Knopf“ – jetzt ist er unmissverständlich

Der Speichern-Knopf war ein **kleines lila Häkchen ohne Beschriftung** oben rechts – auf
einem dunklen Hintergrund leicht zu übersehen. Jetzt:

* **Beschrifteter, gefüllter Knopf „Speichern“** (mit Häkchen-Symbol) oben rechts.
* Der Speichern-Dialog hat **richtige Knöpfe** – „Als neue Kopie speichern“,
  „Original überschreiben“ (nur wenn möglich) und „Abbrechen“. Vorher war das eine Liste
  aus Textzeilen, die wie Fließtext wirkte.
* Der Fehlergrund beim Speichern steht als Dialog da, nicht mehr als kurzer Hinweis.

### b) HEIC/HEIF öffnet jetzt schnell

Drei echte Bremsen, alle gefunden und behoben:

* **Der System-Decoder schaltete sich nach 3 Fehlversuchen für die ganze Sitzung ab.**
  Waren die ersten drei HEIFs problematisch (z. B. Apple-Screenshots mit Kachel-Raster),
  liefen danach **alle** HEICs über den langsamen Software-Decoder – mehrere Sekunden pro
  Bild. Jetzt wird das **pro Datei** gemerkt: ein normales Foto nimmt weiter den schnellen
  Hardware-Weg.
* **Dunkles Hardware-Ergebnis wurde verworfen.** Bei 10-Bit-/HDR-HEICs kann der
  Hardware-Pfad ein (fast) schwarzes Bild liefern. Bisher wurde es weggeworfen und
  zusätzlich der Software-Weg probiert – und wenn der auch dunkel war, landete die Datei
  beim langsamsten Weg (libheif). Jetzt wird das Hardware-Ergebnis **aufbewahrt** und nur
  durch ein sichtbares Bild ersetzt; ein schwarzes Bild gibt es nur noch, wenn wirklich
  kein Weg etwas liefert.
* **Kein zweites Dekodieren für dasselbe Foto.** Beim Antippen einer Kachel lief bisher
  immer zusätzlich die 1024-px-Vorschau – also ein zweiter voller HEIC-Dekodiervorgang
  parallel zum Vollbild. Liegt die Raster-Kachel schon im Speicher (ab 256 px), entfällt
  die Vorschau jetzt komplett: direkt das scharfe Vollbild.
* Zusätzlich wird die **exakte Zielgröße** vorgegeben (wie iOS: in Anzeigegröße dekodieren)
  statt nur einer Zweier-Stufe – wieder weniger Pixel zu rechnen und weniger Speicher.
* Und pro Datei wird gemerkt, welcher Weg zum Ziel geführt hat. Beim zweiten Öffnen wird
  direkt dieser Weg genommen, ohne Fehlversuche.

### c) Dunkle Vorschaubilder kosten nicht mehr doppelt

Bei Nachtaufnahmen prüfte die App bisher **jedes Mal** erneut, ob das dunkle Vorschaubild
vielleicht ein Dekodierfehler ist – und dekodierte dafür ein zweites Mal. Jetzt wird das
pro Datei einmal geprüft und gemerkt. Das beschleunigt den Hintergrund-Aufbau bei
Nachtaufnahmen spürbar.

## 1.26 – Speichern repariert, schnelleres Öffnen, Vorschauen bleiben im Speicher

### a) Bearbeitete Fotos speichern – jetzt wirklich

Drei Ursachen, alle behoben:

* **Leere Bilder in der Galerie:** Wenn das Schreiben still scheiterte, blieb ein
  0-Byte-Eintrag in der Galerie stehen – sah aus wie „gespeichert“, war aber nichts.
  Jetzt wird nach dem Schreiben **nachgesehen** (Datei wirklich mit Inhalt da?) und ein
  misslungener Eintrag wird **wieder entfernt**.
* **„Gespeichert“, aber nirgends zu finden:** Als Ausweichweg wurde früher in den
  app-internen Ordner geschrieben (`Android/data/…`). Den zeigt **keine** Galerie an.
  Dieser Weg ist weg. Stattdessen: Galerie mit `Pictures/N3 Gallery` → falls das Gerät
  das ablehnt, Galerie direkt in `Pictures` → auf Android 8/9 der öffentliche Bilder-Ordner.
* **Letzter Ausweg ohne Datenverlust:** Nimmt die Galerie die Datei trotzdem nicht an,
  liegt das Bild im Teilen-Ordner und ein Dialog bietet **„Teilen“** an (z. B. in Fotos
  sichern). Und der Fehlergrund steht als Dialog da, nicht mehr als kurz aufblitzender
  Hinweis.

Zusätzlich: Klappt das Aufbereiten wegen Speichermangels nicht, wird automatisch eine
Stufe kleiner gerechnet (4096 → 2560 → 1600 px) statt mit „konnte nicht aufbereitet
werden“ abzubrechen. Bei HEIC steht im Speichern-Dialog, dass die Kopie als JPEG
entsteht (HEIC lässt sich nicht überschreiben).

### b) Erstes Antippen eines Fotos aus dem Raster: kürzere Wartezeit

* **Vorher dekodierten drei Bilder gleichzeitig:** Die Großansicht hält die Nachbarfotos
  für das Wischen bereit (`offscreenPageLimit = 1`) und ließ **jede** dieser Seiten sofort
  das Vollbild in Bildschirmgröße rechnen. Drei schwere Dekodiervorgänge teilten sich die
  CPU – das angetippte Foto wartete mit. Jetzt lädt **nur die sichtbare Seite** das
  Vollbild; die Nachbarn bleiben bei der schnellen 1024-px-Vorschau (die fürs Wischen
  reicht) und starten das Vollbild, sobald sie sichtbar werden.
* **Kein doppeltes Dekodieren derselben Größe:** Läuft für ein Foto schon eine
  Dekodierung, hängen sich weitere Anfragen an dieselbe Arbeit an, statt sie parallel zu
  wiederholen.
* **Vollbild-Threads mit höherer Priorität** als die Hintergrundarbeit des Rasters.
* Die Raster-Kachel erscheint weiterhin **sofort** beim Antippen (schon im Speicher).

### c) Vorschauen im RAM – „schon geladen“ heißt jetzt „bleibt da“

Bisher lagen Vorschauen nur als fertige Bilder im Speicher. Ein 512-px-Bild belegt so
**1 MB**, ein 64-px-Bild 16 kB – bei vielen Fotos ist der Speicher schnell voll und die
ältesten Vorschauen fielen heraus (und mussten später neu dekodiert werden).

Jetzt liegt **jede Vorschau zusätzlich komprimiert im RAM** (JPEG/PNG-Bytes, bis zu 32 MB):

* Ein 512-px-Bild braucht so nur ~40 kB statt 1 MB – es passen **etwa 25× so viele**
  Vorschauen in denselben Speicher.
* Wird eine Kachel erneut gebraucht (zurückscrollen, Zoom ändern, Foto öffnen), ist sie in
  Millisekunden wieder da – **ohne** erneutes Dekodieren (das bei HEIC/RAW Sekunden kostet).
* Das gilt zusätzlich zur Festplatten-Ablage: nach einem App-Neustart sind die Vorschauen
  weiterhin sofort da.
* Die Vorschauen aus dem Hintergrund-Aufbau werden **nur** komprimiert abgelegt, damit der
  Speicher für die sichtbaren Kacheln frei bleibt.

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
