# KI-Suche: EmbeddingGemma 2 auf dem Gerät

Die Bildersuche arbeitet **wie in „Google Edge Gallery“**: Der Suchtext und jedes Foto
bekommen einen Zahlenvektor (Embedding), gesucht wird nach der größten Ähnlichkeit.
Gerechnet wird **auf dem Gerät**.

```
Suchtext ──► EmbeddingGemma 2 (MediaPipe Tasks Retrieval / LiteRT-LM) ──► Vektor ─┐
                                                                                  ├─► Kosinus-Ähnlichkeit ──► Treffer
Foto-Beschreibung ──► EmbeddingGemma 2                                            ─┘
```

## 1. Welches Modell?

[**EmbeddingGemma 2**](https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/):
740 M Parameter, nativ multimodal (Text, Bild, Audio in einem Vektorraum), Apache 2.0,
veröffentlicht am 6. Oktober 2026. Google setzt es in „Google AI Edge Gallery“ für
„Instant Media Search“ ein.

Die App nutzt `com.google.mediapipe:tasks-retrieval:1.2.3` (**UniversalEmbedder**) auf der
LiteRT-LM-Laufzeit – mit **fest eingetragener** `litertlm-android:0.19.1`, weil die von
MediaPipe mitgelieferte Version (0.17.0-alpha1) zu alt ist: dort fehlt die Klasse
`ActivationDataType`, und der Start scheitert mit `NoClassDefFoundError`. Geprüft wird das
in `diag-modell.yml` und bei jedem Bau in `build-apk.yml`. Die Modelldateien liegen im `.litertlm`-Format auf Hugging Face
(LiteRT-Community):

| Variante in der App | Datei | Größe | Inhalt |
| --- | --- | --- | --- |
| Text (270M) | `embeddinggemma-2-text-270m.litertlm` | 164 626 432 B ≈ 157 MiB | nur Text |
| Text + Bild (440M) | `embeddinggemma-2-text-vision-440m.litertlm` | 387 710 976 B ≈ 370 MiB | empfohlen |
| alles (740M) | `embeddinggemma-2-740m.litertlm` | 484 622 336 B ≈ 462 MiB | Text, Bild, Audio |

Die Sollgrößen stehen in `ModelStore.Variant` und werden beim Download geprüft: Eine
abgebrochene Datei wird nie als Modell verwendet, sondern als „unvollständig“ angezeigt und
beim nächsten Versuch an derselben Stelle fortgesetzt.

## 2. Modell holen – direkt in der App

In der **Bildersuche** steht unter dem Suchfeld eine Leiste, solange kein Modell vorhanden
ist:

1. **„Modell laden“** → Auswahl der drei Varianten → Download startet **in der App**, mit
   Fortschrittsbalken in MB, „Abbrechen“ und einer Platzprüfung vorher.
2. Danach liegt die Datei in `files/models/` (App-Speicher), die Suchmaschine startet neu
   und der Index wird neu berechnet.
3. Die Leiste verschwindet, in der Statuszeile steht z. B. `◆ EmbeddingGemma 2 · GPU`.

Über **Modell …** (Überlaufmenü der Bildersuche) geht das jederzeit auch dann, wenn schon eine
Datei im Ordner liegt: laden, Dateien ansehen, entfernen.

Ist das Modell schon auf dem Gerät (z. B. per Dateimanager geladen), lässt es sich mit
**„Datei wählen“** übernehmen.

> **Netzwerk:** Die App hat `INTERNET` **nur** für diesen Download (und optional für
> OpenStreetMap-Kacheln). Beides passiert ausschließlich auf Antippen. Ohne Modell läuft die
> Suche mit dem eingebauten Wortmodell (Abschnitt 4) – vollständig offline.

## 3. Beschleuniger

`ai/GemmaEmbedder.kt` startet das Modell in dieser Reihenfolge und **prüft jede Stufe mit
einem Testlauf**:

| Stufe | Wie | Hinweis |
| --- | --- | --- |
| **GPU** | `Delegate.GPU` (LiteRT) | schnellste Stufe, auf den meisten Geräten ab Android 8 verfügbar |
| **CPU** | `Delegate.CPU` | immer möglich; das 270M-Modell bleibt auch hier flott |
| *Ersatz* | eingebautes Wortmodell (`ai/LocalTextModel.kt`) | wenn kein Modell vorhanden ist |

> **Zur NPU:** EmbeddingGemma 1 ließ sich über den `TextEmbedder` noch mit `Delegate.NPU`
> ansprechen. EmbeddingGemma 2 läuft über LiteRT-LM, das in MediaPipe nur GPU und CPU
> anbietet – die NPU wird dort nicht adressiert. **Seit 1.38** probiert die App deshalb
> zuerst `Backend.NPU` von LiteRT-LM direkt (mit Testlauf und Rückfall auf GPU → CPU).
> kommt die Stufe in `GemmaEmbedder.create` einfach vor die GPU.

## 4. Was in den Foto-Vektor einfließt

Damit die Suche ohne Bildanalyse treffsicher ist, wird zu jedem Foto ein
**Beschreibungstext** gebaut (`ai/SearchIndex.kt`):

* Dateiname und Ordner (z. B. „Screenshot“, „IMG 2043“)
* eigene **Notizen und Tags** (XMP) – die stärksten Hinweise
* **Ort** („Luzern“, „Schweiz“ – offline aus der Ortsliste)
* **Zeit**: Jahr, Monat, Wochentag, Tageszeit („Nacht“, „Abend“, „Frühstück“)
* Format (HEIC, RAW, SVG …), Video, Hochformat/Screenshot, Panorama
* Kamera/Objektiv, Favoriten

Der Suchtext wird mit demselben Modell eingebettet – dadurch findet „strandurlaub“ auch
Bilder mit der Notiz „Sommer am Meer“. Zusätzlich läuft immer die klassische Suche
(Dateiname/Ordner/Tags) mit. Der Index wird in `n3_search_index.v2` gespeichert – zusammen mit einem Stempel, zu welchem
Modell und Beschleuniger die Vektoren gehören. Dadurch wird nach einem Modellwechsel neu
gerechnet und nach einem normalen Start nichts doppelt.

**Wann rechnet die App?** Beim Start und nach jedem Neuscan, im Hintergrund
(`SearchIndex.warmUp`, angestoßen über `DataHub.onLibraryChanged`). Nach jedem Foto gibt es eine
kurze Pause, damit Scrollen und Tastatur Vorrang haben; ist die App im Hintergrund, wird
länger gewartet (Akku). Alle Modellaufrufe laufen nacheinander – die LiteRT-LM-Laufzeit ist
nicht thread-sicher.

**Selbsttest:** Menü **⋮ → „Modell testen …“** in der Bildersuche lädt das Modell neu und
zeigt das Ergebnis im Klartext (Weg, Beschleuniger, Dimensionen – oder den genauen Fehler).

**Wenn es nicht läuft**, sagt die App es: Statuszeile („läuft nicht: GPU: … · CPU: …“) und
leeres Suchergebnis nennen den Grund aus `GemmaEmbedder.lastFailure` / `SearchIndex.lastError()`
– kein stiller Rückfall auf das Wortmodell mehr.

> Mit der **Text+Bild-Variante** (440M) kann später zusätzlich der Bildinhalt selbst
> eingebettet werden (`embedImage`) – der Aufbau dafür steht schon.

## 5. Ohne Modell: das eingebaute Wortmodell

`assets/ai/wortmodell.txt` (~12 kB, erzeugt von `tools/ki/genwortmodell.js`) enthält rund
245 Wörter mit Bedeutungsvektoren („strand“ liegt bei „meer“, „urlaub“, „sonne“) sowie
Zeichen-3-Gramme für unbekannte Wörter und Tippfehler. Das ist kein Ersatz für ein echtes
Embedding-Modell, aber die Suche funktioniert **auf jedem Gerät sofort**.

## 6. Datenschutz

Suche, Index und Embeddings bleiben auf dem Gerät. Es gibt keine Cloud, kein Konto, keine
Kennung. Netzwerkzugriff nur beim ausdrücklichen Herunterladen des Modells (und optional
für OpenStreetMap-Kacheln).
