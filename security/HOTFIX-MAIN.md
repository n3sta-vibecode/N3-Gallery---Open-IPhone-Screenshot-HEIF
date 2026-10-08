# HOTFIX-MAIN — Sanierung von `main` (nur vom Owner auszuführen)

Der Session-Branch `arena/a3d1c69d-n3-gallery-open-iphone-screens` enthält die vollständige
Härtung (`1.32-hardened`) und **keinen** Keystore. `main` enthält den kompromittierten
Schlüssel jedoch weiterhin **in der Spitze** und in der Historie. Da auf `main` nur der
Repo-Owner pushen darf, sind die folgenden Schritte bewusst als Runbook dokumentiert.

**Reihenfolge ist wichtig:** erst Spitze (stoppt neue Klons), dann Historie, dann Rotation,
dann sicheres Release. Die Releases sind bereits offline
([RELEASE-RUECKZUG.md](RELEASE-RUECKZUG.md)).

---

## Schritt 1 — Schlüssel von der `main`-Spitze entfernen (AKUT, stoppt neue Klons)

Jeder `git clone` von `main` lädt derzeit `ci/n3-ci.p12` + `keystore.properties`. Sofort:

```bash
git checkout main
git pull --ff-only

# 1a) Die beiden Secret-Dateien entfernen
git rm ci/n3-ci.p12 keystore.properties

# 1b) In .gitignore die Negationen LOESCHEN, die die Secrets gewhitelistet haben:
#       !ci/n3-ci.p12
#       !keystore.properties
#     (Kommentarzeile darueber, z. B. "# Nur der CI-Schluessel ...", ebenfalls entfernen)
#     Manuell oder:
sed -i '/^!ci\/n3-ci\.p12$/d; /^!keystore\.properties$/d' .gitignore

# 1c) Sicherstellen, dass keine weitere Secret-Kopie im Tree liegt
grep -rniE 'storePassword|keyPassword' --include='*.properties' --include='*.kts' . || true
find . -path ./.git -prune -o \( -name '*.jks' -o -name '*.p12' -o -name '*.pfx' \) -print

git add .gitignore
git commit -m "security: kompromittierten CI-Signierschluessel von main entfernen (V5)"
git push origin main
```

> Hinweis: Optional kann `main` stattdessen komplett durch den gehärteten Branch ersetzt
> werden (dieser enthält dieselbe Quellbasis + alle Fixes und keinen Keystore). Wegen
> getrennter Historien ist dafür ein Merge mit `--allow-unrelated-histories` oder ein
> Reset nötig — das ist eine bewusste Owner-Entscheidung, kein Automatik-Schritt.

## Schritt 2 — Git-Historie bereinigen (Blobs dauerhaft entfernen)

Die Blobs bleiben über alte Commits abrufbar, bis die Historie umgeschrieben ist:

```bash
pip install git-filter-repo
git filter-repo --invert-paths \
  --path ci/n3-ci.p12 \
  --path keystore.properties \
  --path ci/n3-release.jks \
  --path n3-release.jks \
  --force
git push origin main --force
```

Danach gilt: **Alle Mitwirkenden/Clons müssen neu klonen** (die Historien-Sha ändern sich).
Bestehende ~160 Klons behalten den Schlüssel trotzdem → deshalb Schritt 3.

## Schritt 3 — Schlüssel rotieren (der alte ist verbrannt)

```bash
bash tools/keystore-neu.sh n3-release.jks     # neuer privater Schluessel + Zufallspasswort
# Passwort in den Passwortmanager; n3-release.jks OFFLINE an zwei Orten lagern, NIE ins Repo.
cp keystore.properties.template keystore.properties   # mit den neuen Angaben fuellen
```

CI-Secrets setzen (statt Repo-Datei): `N3_KEYSTORE_BASE64`, `N3_STORE_PASSWORD`,
`N3_KEY_ALIAS`, `N3_KEY_PASSWORD`, `N3_STORE_TYPE`. Die gehärtete CI
(`.github/workflows/build-apk.yml`) bricht ohne echten Schlüssel ab und verweigert Debug-
sowie den bekannten CI-Schlüssel.

**Konsequenz akzeptieren:** Bestehende, mit dem alten Schlüssel signierte Installationen
können **nicht** per Update migriert werden (Android verweigert Signaturwechsel). Nutzer
müssen **deinstallieren + neu installieren**.

## Schritt 4 — Sicheres Release veröffentlichen

```bash
./gradlew assembleRelease          # signiert mit dem NEUEN Schluessel
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

Neues GitHub-Release anlegen und in Release-Notes + README veröffentlichen:
- **Zertifikat-Fingerprint (SHA-256)** des neuen Schlüssels
- **APK-SHA-256**
- Hinweis: „Alte Versionen unsicher/Signatur kompromittiert — bitte deinstallieren und nur
  diese Version mit übereinstimmendem Fingerprint installieren."
- **Keine** Projekt-ZIPs mit Keystore anhängen.

## Schritt 5 — Nutzer informieren

Dort, wo die Nutzer herkommen (README-Banner ist gesetzt; zusätzlich z. B. Reddit /
android-time.de, falls dort verlinkt wurde): alte APKs unsicher, nur die offizielle
Release-Seite mit geprüftem Fingerprint verwenden, Neuinstallation nötig.

---

## Checkliste

- [ ] Schritt 1: `ci/n3-ci.p12` + `keystore.properties` nicht mehr in `main`-Spitze
- [ ] Schritt 1: `.gitignore`-Negationen entfernt
- [ ] Schritt 2: Historie bereinigt + force-push, Clons informiert
- [ ] Schritt 3: neuer privater Schlüssel erzeugt, alter als kompromittiert behandelt
- [ ] Schritt 3: CI-Secrets gesetzt, kein Repo-Key
- [ ] Schritt 4: sicheres Release mit Fingerprint + SHA-256 veröffentlicht
- [ ] Schritt 5: Nutzerwarnung veröffentlicht
- [ ] Releases: verwundbare Assets offline (bereits erledigt, siehe RELEASE-RUECKZUG.md)
