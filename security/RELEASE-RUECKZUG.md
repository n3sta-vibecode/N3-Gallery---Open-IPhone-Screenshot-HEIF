# Release-Rückzug — Protokoll (2026-10-08)

**Status: ALLE öffentlichen Releases offline.** Keine verwundbare APK und keine
Keystore-haltige Projekt-ZIP ist mehr über GitHub herunterladbar.

## Warum

Die Sicherheitsanalyse ([SICHERHEITS-ANALYSE.md](SICHERHEITS-ANALYSE.md)) ergab:

1. **Alle** Testbuild-APKs sind mit dem **kompromittierten CI-Schlüssel** `ci/n3-ci.p12`
   signiert (V5, End-to-End bewiesen). Jede dieser APKs ist damit ein Artefakt, dessen
   Signatur jeder nachbauen kann → Fake-„Updates".
2. Alle APKs bis 1.31 enthalten ausnutzbare Schwachstellen (V1–V4, V8, V11, V12).
3. Die **Projekt-ZIPs** in den Releases enthalten den privaten Keystore
   (`ci/n3-ci.p12` + Passwort, bzw. `n3-release.jks`) → direkter Secret-Leak pro Download.

Die GitHub-Traffic-Stats belegten zudem **376 Clones / 160 unique cloners in 14 Tagen**
(Spike ab ~10/03, Referrer reddit.com / android-time.de): Das Repo wird aktiv geklont,
der Schlüssel ist faktisch in freier Wildbahn. Ein Belassen der verwundbaren Downloads
hätte die Exposition laufend vergrössert.

## Was offline genommen wurde (vollständige Liste, vor dem Löschen gesichert)

Gelöscht via `gh release delete <tag> --yes --cleanup-tag` (Release + Assets + Tag):

| Tag | Assets (vor Löschung) |
|---|---|
| testbuild-1.31 | N3-Gallery-1.31-release.apk · -TEST.apk · N3Gallery-Projekt-1.31.zip |
| testbuild-1.30 | N3-Gallery-1.30-release.apk · -TEST.apk · N3Gallery-Projekt-1.30.zip |
| testbuild-1.29 | N3-Gallery-1.29-release.apk · -TEST.apk · N3Gallery-Projekt-1.29.zip |
| testbuild-1.28 | N3-Gallery-1.28-release.apk · -TEST.apk · N3Gallery-Projekt-1.28.zip |
| testbuild-1.27 | N3-Gallery-1.27-release.apk · -TEST.apk · N3Gallery-Projekt-1.27.zip |
| testbuild-1.26 | N3-Gallery-1.26-release.apk · -TEST.apk · N3Gallery-Projekt-1.26.zip |
| testbuild-1.25 | N3-Gallery-1.25-release.apk · -TEST.apk · N3Gallery-Projekt-1.25.zip |
| testbuild-1.24 | N3-Gallery-1.24-release.apk · -TEST.apk · N3Gallery-Projekt-1.24.zip |
| testbuild-1.23 | N3-Gallery-1.23-release.apk · -TEST.apk · N3Gallery-Projekt-1.23.zip |
| testbuild-1.22 | N3-Gallery-1.22-release.apk · -TEST.apk · N3Gallery-Projekt-1.22.zip |
| Beta | (Release-Seite, Assets siehe Manifest unten) |

Danach: `gh release list` → **leer**. Vollständiges Asset-Manifest mit Grössen/URLs zur
Dokumentation: siehe Anhang.

## Was dadurch NICHT behoben ist (bleibt Aufgabe des Owners)

Der Rückzug stoppt **neue Downloads**, nicht aber:

- **`main`-Spitze:** `ci/n3-ci.p12` + `keystore.properties` liegen weiterhin im aktuellen
  `main`-Tree → **jeder neue `git clone` lädt den Schlüssel.** Sofort entfernen, siehe
  [HOTFIX-MAIN.md](HOTFIX-MAIN.md).
- **Git-Historie:** die Blobs bleiben bis `git filter-repo` + force-push abrufbar.
- **Bestehende Klons/Installationen:** ~160 Klons besitzen den Schlüssel bereits; installierte
  alte APKs bleiben verwundbar und können nicht per Update migriert werden (Neuinstallation).

## Wieder-Veröffentlichung (sicher)

1. Neuen privaten Schlüssel erzeugen (`tools/keystore-neu.sh`), Passwort in Passwortmanager.
2. Gehärteten Build (`1.32-hardened`) mit dem NEUEN Schlüssel signieren (CI-Secrets, kein Repo-Key).
3. Neues Release anlegen; in Release-Notes + README **Zertifikat-Fingerprint (SHA-256)** und
   **APK-SHA-256** veröffentlichen; Nutzern sagen: alte Version deinstallieren, nur von dieser
   Seite mit geprüftem Fingerprint installieren.
4. Keine Projekt-ZIPs mit Keystore mehr publishen (CI tut das in der gehärteten Fassung nicht).

## Anhang: Asset-Manifest vor Löschung

### testbuild-1.31
  N3-Gallery-1.31-release.apk  (30359251 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.31/N3-Gallery-1.31-release.apk
  N3-Gallery-1.31-TEST.apk  (30359187 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.31/N3-Gallery-1.31-TEST.apk
  N3Gallery-Projekt-1.31.zip  (1004206 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.31/N3Gallery-Projekt-1.31.zip
### testbuild-1.30
  N3-Gallery-1.30-release.apk  (30260723 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.30/N3-Gallery-1.30-release.apk
  N3-Gallery-1.30-TEST.apk  (30260659 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.30/N3-Gallery-1.30-TEST.apk
  N3Gallery-Projekt-1.30.zip  (968316 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.30/N3Gallery-Projekt-1.30.zip
### testbuild-1.29
  N3-Gallery-1.29-release.apk  (30260723 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.29/N3-Gallery-1.29-release.apk
  N3-Gallery-1.29-TEST.apk  (30260659 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.29/N3-Gallery-1.29-TEST.apk
  N3Gallery-Projekt-1.29.zip  (966634 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.29/N3Gallery-Projekt-1.29.zip
### testbuild-1.28
  N3-Gallery-1.28-release.apk  (30260647 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.28/N3-Gallery-1.28-release.apk
  N3-Gallery-1.28-TEST.apk  (30260583 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.28/N3-Gallery-1.28-TEST.apk
  N3Gallery-Projekt-1.28.zip  (965243 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.28/N3Gallery-Projekt-1.28.zip
### testbuild-1.27
  N3-Gallery-1.27-release.apk  (30260647 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.27/N3-Gallery-1.27-release.apk
  N3-Gallery-1.27-TEST.apk  (30260583 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.27/N3-Gallery-1.27-TEST.apk
  N3Gallery-Projekt-1.27.zip  (929039 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.27/N3Gallery-Projekt-1.27.zip
### testbuild-1.26
  N3-Gallery-1.26-release.apk  (30260455 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.26/N3-Gallery-1.26-release.apk
  N3-Gallery-1.26-TEST.apk  (30260391 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.26/N3-Gallery-1.26-TEST.apk
  N3Gallery-Projekt-1.26.zip  (927322 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.26/N3Gallery-Projekt-1.26.zip
### testbuild-1.25
  N3-Gallery-1.25-release.apk  (30243259 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.25/N3-Gallery-1.25-release.apk
  N3-Gallery-1.25-TEST.apk  (30243195 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.25/N3-Gallery-1.25-TEST.apk
  N3Gallery-Projekt-1.25.zip  (923349 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.25/N3Gallery-Projekt-1.25.zip
### testbuild-1.24
  N3-Gallery-1.24-release.apk  (30243259 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.24/N3-Gallery-1.24-release.apk
  N3-Gallery-1.24-TEST.apk  (30243199 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.24/N3-Gallery-1.24-TEST.apk
  N3Gallery-Projekt-1.24.zip  (921125 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.24/N3Gallery-Projekt-1.24.zip
### testbuild-1.23
  N3-Gallery-1.23-release.apk  (30242453 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.23/N3-Gallery-1.23-release.apk
  N3-Gallery-1.23-TEST.apk  (30242389 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.23/N3-Gallery-1.23-TEST.apk
  N3Gallery-Projekt-1.23.zip  (915548 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.23/N3Gallery-Projekt-1.23.zip
### testbuild-1.22
  N3-Gallery-1.22-release.apk  (30242453 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.22/N3-Gallery-1.22-release.apk
  N3-Gallery-1.22-TEST.apk  (30242389 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.22/N3-Gallery-1.22-TEST.apk
  N3Gallery-Projekt-1.22.zip  (913560 B)  https://github.com/n3sta-vibecode/N3-Gallery---Open-IPhone-Screenshot-HEIF/releases/download/testbuild-1.22/N3Gallery-Projekt-1.22.zip
### Beta
