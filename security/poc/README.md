# PoC-Verzeichnis — N3 Gallery Sicherheitsanalyse

Nachweise und Gegenproben zu [`../SICHERHEITS-ANALYSE.md`](../SICHERHEITS-ANALYSE.md).
Alles dient ausschließlich dem Test auf **eigenen** Geräten bzw. dem Nachweis der
Befunde am Quellcode — nicht dem Angriff auf Dritte.

## Skripte (laufen ohne Android-Gerät)

| Datei | Zweck | Befund |
|---|---|---|
| `verify_traversal.py` | Portiert die exakte Semantik von `android.net.Uri` + `java.io.File` und wendet sie auf die Original-Codepfade an. Beweist, dass `%2e%2e%2f` → `../` wird und `File(parent, child)` nicht normalisiert. | V1, V2, V3 |
| `verify_fix.py` | Portiert `UriGuard`/`SafeFiles` der gehärteten Fassung. Weist 14 Angriffspayloads + 6 Injektions-URIs ab und lässt 15 legitime Fälle durch (Regressionsschutz!). | V1–V3, V11, V12 |
| `verify_signing.py` | Lädt Signaturschlüssel + Passwort aus dem Git-Verlauf, entschlüsselt den privaten Schlüssel und gleicht den Fingerabdruck mit der ausgelieferten APK ab. **End-to-End-Beweis** der Kompromittierung. Benötigt `pip install cryptography`. | V5, V6 |
| `apk_sig.py` | Inspektor für das APK-Signaturschema (V1/V2/V3/V4, APK-Signing-Block). Zeigt, dass die APK kein V1 hat (Janus-sicher) und mit welchem Zertifikat sie signiert ist. | V5 |
| `axml_dump.py` | Dekodiert das binäre `AndroidManifest.xml` ohne `aapt`/Java. | V1, V4 |
| `jks_crack.py` | Offline-Wörterbuchprüfung gegen einen JKS-Keystore. Zeigt: der Release-JKS hält stand, der CI-P12 hat ein mitgeliefertes triviales Passwort. | V5 |
| `attack.sh` | adb-Angriffsskript für ein **eigenes** Testgerät. `DRY_RUN=1` ist die Voreinstellung (zeigt nur, setzt den Löschen-Intent nicht ab). Zum Ausführen: `DRY_RUN=0 ./attack.sh`. | V1–V4, V11, V12 |

## `backup-extract/` — Backup-/Speicher-Analysatoren

| Datei | Zweck | Befund |
|---|---|---|
| `extract_notes.py` | Parst ein Android-`.ab`-Backup (Header, zlib, tar) und gibt die Klartext-Notizen aus `n3_gallery_meta.xml` aus. | V4 |
| `read_meta_sicherung.py` | Liest die `n3-sicherung.json`, die `MetaBackup` in den **geteilten** Speicher schreibt, und gibt Notizen/Tags/Favoriten aus. | V11 |

## `exploit-app/` — Android-PoC-App (KEINE Berechtigungen)

Demonstriert, dass eine völlig rechtelose Dritt-App die Galerie angreifen kann.
Bau mit Android Studio (oder `./gradlew :app:assembleDebug`). Die App deklariert
**keine** Android-Berechtigung — der Angriff funktioniert allein über die exportierte
`MainActivity` der Galerie und den eigenen `PayloadProvider`.

| Button | Befund | Was passiert |
|---|---|---|
| `V3: beliebige Datei LOESCHEN` | V3 | `ACTION_VIEW` mit `file://` → „Löschen" tippen → Datei weg, kein Systemdialog |
| `V1+V3: eigene private Notizen loeschen` | V3 | zerstört `shared_prefs/n3_gallery_meta.xml` |
| `V2: PATH TRAVERSAL Schreibziel` | V2 | `%2e%2e%2f`-Dateiname → „Teilen" tippen → Schreiben außerhalb des Share-Cache |
| `V12: beliebige Datei UEBERSCHREIBEN` | V12 | `file://` → Editor → „Speichern" → Ziel wird überschrieben |
| `V1: beliebige Datei LESEN + exfiltrieren` | V1 | `file://`/`content://` auf private Daten → „Teilen" → `ExfilActivity` empfängt |
| `DoS: 256-MB-Stream` | V8 | `PayloadProvider` streamt 512 MB in den Dekoder |

`PayloadProvider` ist der Angreifer-ContentProvider (liefert `file://`-URIs bzw. eine
512-MB-Pipe). `ExfilActivity` nimmt die per „Teilen" abgezogenen Daten entgegen und zeigt
sie an.

## Verantwortlicher Umgang

- `attack.sh` läuft standardmäßig im `DRY_RUN=1` (zeigt nur).
- `verify_signing.py` lädt den kompromittierten Schlüssel **ausschließlich**, um die
  Kompromittierung nachzuweisen; er wird nirgends neu abgelegt oder verteilt.
- PoC-App und Skripte nur auf eigenen Geräten / eigenen Repositorys einsetzen.
