# KI-Suche: Gemma-Embedding im Modell (offline, auf der NPU)

Die Bildersuche arbeitet **wie in „Google Edge Gallery“**: Der Suchtext und jedes Foto
bekommen einen Zahlenvektor (Embedding), gesucht wird nach der größten Ähnlichkeit.
Gerechnet wird **auf dem Gerät** – die App hat bewusst **keine Internet-Berechtigung**.

```
Suchtext ──► Gemma-Embedding (MediaPipe / LiteRT)  ──►  Vektor ─┐
                                                               ├─► Kosinus-Ähnlichkeit ──► Treffer
Foto-Beschreibung ──► Gemma-Embedding              ──►  Vektor ─┘
```

## 1. Beschleuniger: NPU → GPU → CPU (automatisch)

`ai/GemmaEmbedder.kt` startet das Modell in dieser Reihenfolge und **prüft jede Stufe mit
einem Testlauf**. Erst wenn ein Vektor zurückkommt, gilt die Stufe als aktiv:

| Stufe | Bibliothek | Hinweis |
| --- | --- | --- |
| **NPU** | MediaPipe `Delegate.NPU` (LiteRT + NPU-Treiber) | schnellste Stufe; braucht ein NPU-taugliches Modell und eine NPU im Gerät (Snapdragon 8 Gen 3+, Tensor G3+, Dimensity 9000+ …) |
| **GPU** | MediaPipe `Delegate.GPU` | funktioniert auf fast allen Geräten ab Android 8 |
| **CPU** | MediaPipe `Delegate.CPU` | immer möglich, langsamer |
| *Ersatz* | eingebautes Wortmodell (`ai/LocalTextModel.kt`) | wenn **kein** Modell im App-Paket liegt – dann funktioniert die Suche trotzdem, nur weniger klug |

In der Suchseite steht unter dem Eingabefeld, welche Stufe gerade rechnet
(`⚡ Gemma Embedding (NPU)`, `◦ Wortmodell (eingebaut)` …).

## 2. Modell einlegen (einmalig, vor dem Bauen)

Die fertige `.task`-Datei von Google AI Edge herunterladen und in den Ordner
`app/src/main/assets/models/` legen. Die App erkennt automatisch die erste passende Datei:

| Dateiname (eine davon) | Modell |
| --- | --- |
| `gemma_embedding_npu.task` | EmbeddingGemma 300M, NPU-Variante (bevorzugt) |
| `embedding_gemma.task` | EmbeddingGemma 300M, int4/int8 (ca. 200 MB) |
| `embedding_gemma_300m.task` | wie oben, anderer Name |
| `universal_sentence_encoder.tflite` | kleiner Ersatz (ca. 6 MB) |

```bash
mkdir -p app/src/main/assets/models
# EmbeddingGemma 300M (MediaPipe-Variante), ca. 200 MB:
curl -L -o app/src/main/assets/models/embedding_gemma.task \
  https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task

# oder der kleine Ersatz (ca. 6 MB):
curl -L -o app/src/main/assets/models/universal_sentence_encoder.tflite \
  https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite
```

> **Warum nicht automatisch herunterladen?** Die App darf kein Internet nutzen
> (keine `INTERNET`-Berechtigung im Manifest – das ist eine bewusste Zusage dieser App).
> Also muss das Modell beim Bauen im Paket liegen. Ohne Modell baut die App normal weiter
> und nutzt das eingebaute Wortmodell.

## 3. Was in den Foto-Vektor einfließt

Damit die Suche ohne Bildanalyse (das würde Minuten pro Bibliothek kosten) richtig gut
trifft, wird zu jedem Foto ein **Beschreibungstext** gebaut (`ai/SearchIndex.kt`):

* Dateiname und Ordner (z. B. „Screenshot“, „IMG 2043“)
* eigene **Notizen und Tags** (XMP) – die stärksten Hinweise
* **Ort** („Luzern“, „Schweiz“ – offline aus der Ortsliste, siehe unten)
* **Zeit**: Jahr, Monat, Wochentag, Tageszeit („Nacht“, „Abend“, „Frühstück“)
* Format (HEIC, RAW, SVG …), Video, Hochformat/Screenshot, Panorama
* Kamera/Objektiv, Favoriten

Der Suchtext wird mit demselben Modell eingebettet – dadurch findet „strandurlaub“ auch
Bilder mit der Notiz „Sommer am Meer“. Zusätzlich läuft immer die klassische Suche
(Dateiname/Ordner/Tags) mit, damit z. B. „IMG_2043“ sicher gefunden wird.

Der Index wird nach dem ersten Aufbau in `n3_search_index.v1` (App-Speicher) abgelegt und
beim nächsten Start wiederverwendet.

## 4. Ohne Modell: das eingebaute Wortmodell

`assets/ai/wortmodell.txt` (~11 kB, erzeugt von `tools/ki/genwortmodell.js`) enthält rund
240 Wörter mit Bedeutungsvektoren („strand“ liegt bei „meer“, „urlaub“, „sonne“) sowie
Zeichen-3-Gramme für unbekannte Wörter und Tippfehler. Das ist kein Ersatz für ein echtes
Embedding-Modell, aber es sorgt dafür, dass die Suche **auf jedem Gerät sofort** sinnvolle
Treffer liefert – auch ohne 200 MB Download.

## 5. Datenschutz

Alles bleibt auf dem Gerät: kein Netzwerk, kein Konto, keine Cloud. Die MediaPipe-Bibliothek
arbeitet rein lokal; die Modelldatei liegt im App-Paket.
