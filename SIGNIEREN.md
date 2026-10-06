# APK signieren – Schlüssel und Anleitungen

Diese Datei beschreibt **alles zum Signieren** der App: welcher Schlüssel verwendet wird,
wie man damit selbst eine APK signiert, und wie man einen eigenen, privaten Schlüssel
erstellt (empfohlen für Google Play).

---

## 1. Der Schlüssel, mit dem die App gebaut wird

Android erlaubt ein **Update nur**, wenn die neue APK mit **demselben Schlüssel** signiert
ist wie die installierte App. Deshalb liegt im Projekt ein fester Schlüssel:

| Was | Wert |
|---|---|
| Datei | `ci/n3-ci.p12` |
| Typ | PKCS12 (funktioniert in Android Studio, `keytool` und `apksigner`) |
| Alias | `n3ci` |
| Speicher-Passwort | `N3GalleryCi2026!` |
| Schlüssel-Passwort | `N3GalleryCi2026!` |
| Gültig bis | 28.09.2056 |
| Inhaber | `CN=N3 Gallery CI, O=N3 Vibecode, C=CH` |
| SHA-256 | `2F:B3:6A:C6:5D:73:A4:B1:E4:37:C8:47:84:E0:EB:50:44:2F:12:3A:DB:92:3B:FD:45:0D:A8:EB:24:C8:48:E0` |
| SHA-1 | `38:26:2D:96:97:24:27:CC:2B:B2:DA:77:67:8C:F3:3B:19:03:05:43` |
| MD5 | `B2:4F:8C:E9:94:CA:E1:23:0C:02:0E:1B:B3:DD:3F:A9` |

Die Zugangsdaten stehen außerdem in `keystore.properties` im Projekt-Root – dort liest
Gradle sie automatisch.

**Wichtig:** Diesen Schlüssel **nie verlieren**. Nur damit lassen sich künftige Versionen
über eine bestehende Installation legen. Diese Datei und `keystore.properties` sichern
(z. B. USB-Stick, verschlüsseltes Backup).

**Ehrlich dazu:** Dieser Schlüssel liegt bewusst im öffentlichen Repository, damit die
Builds ohne Zugangsdaten laufen (und damit Sie ihn jederzeit herunterladen können). Er ist
deshalb **nicht** als Upload-Schlüssel für Google Play geeignet – dafür den eigenen
Schlüssel aus Abschnitt 4 nehmen.

---

## 2. Selbst signieren

### a) Mit Gradle (einfachster Weg)

`keystore.properties` liegt im Projekt – es ist nichts einzustellen:

```bash
./gradlew assembleRelease     # normale App  -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleTryout      # TEST-App     -> app/build/outputs/apk/tryout/app-tryout.apk
./gradlew bundleRelease       # AAB für Play -> app/build/outputs/bundle/release/app-release.aab
```

Beide APKs sind fertig signiert.

### b) In Android Studio

1. **Build → Generate Signed Bundle / APK…**
2. **APK** wählen (für Play **Android App Bundle**)
3. **Choose existing…** → `ci/n3-ci.p12` auswählen
   * Key store password: `N3GalleryCi2026!`
   * Key alias: `n3ci`
   * Key password: `N3GalleryCi2026!`
4. Variante **release** (oder **tryout** für die Test-App) → **Create**

### c) Auf der Kommandozeile (apksigner)

Für eine **unsignierte** APK zuerst ausrichten, dann signieren:

```bash
# Pfade anpassen (build-tools-Version aus dem SDK-Ordner)
BT="$HOME/Android/Sdk/build-tools/36.0.0"

"$BT/zipalign" -p -f 4 unsigniert.apk ausgerichtet.apk
"$BT/apksigner" sign \
  --ks ci/n3-ci.p12 --ks-type PKCS12 \
  --ks-pass pass:'N3GalleryCi2026!' \
  --key-pass pass:'N3GalleryCi2026!' \
  --ks-key-alias n3ci \
  --out N3-Gallery-signiert.apk ausgerichtet.apk

# Kontrolle: Fingerabdruck muss 2F:B3:6A:…:48:E0 sein
"$BT/apksigner" verify --print-certs N3-Gallery-signiert.apk
```

Oder das mitgelieferte Skript benutzen:

```bash
tools/signieren.sh unsigniert.apk
```

### d) Mit einer Signier-App auf dem Handy

Manche APK-Signer-Apps möchten **JKS**. PKCS12 vorher umwandeln:

```bash
keytool -importkeystore \
  -srckeystore ci/n3-ci.p12 -srcstoretype PKCS12 \
  -srcstorepass 'N3GalleryCi2026!' \
  -destkeystore n3-ci.jks -deststoretype JKS \
  -deststorepass 'N3GalleryCi2026!' \
  -srcalias n3ci -destalias n3ci \
  -srckeypass 'N3GalleryCi2026!' -destkeypass 'N3GalleryCi2026!'
```

Dann in der App `n3-ci.jks`, Passwort `N3GalleryCi2026!`, Alias `n3ci` angeben.

---

## 3. Fingerabdruck prüfen

Nur wenn der Fingerabdruck gleich bleibt, klappt das Update:

```bash
apksigner verify --print-certs deine-app.apk | grep "SHA-256"
# erwartet: 2fb36ac65d73a4b1e437c84784e0eb50442f123adb923bfd450da8eb24c848e0
```

Der Build im GitHub-Workflow macht das automatisch und **bricht ab**, wenn die Signatur
abweicht.

---

## 4. Eigener, privater Schlüssel (empfohlen für Google Play)

Für Play nimm einen **eigenen** Schlüssel, der nur dir gehört:

```bash
tools/keystore-neu.sh          # erzeugt n3-release-neu.jks + keystore.properties.neu
```

Oder von Hand:

```bash
keytool -genkeypair -v \
  -keystore n3-release.jks -storetype JKS \
  -alias n3 -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=N3 Vibecode, O=N3 Vibecode, C=CH"
```

Danach `keystore.properties` anpassen:

```properties
storeFile=n3-release.jks
storeType=JKS
storePassword=<dein Passwort>
keyAlias=n3
keyPassword=<dein Passwort>
```

`keystore.properties` und `*.jks` sind per `.gitignore` **nicht** im Repository – der
private Schlüssel bleibt also auf deinem Rechner. Wichtig: **Backup** anlegen, ohne den
Schlüssel lassen sich keine Updates mehr veröffentlichen (bei Play kann das nur der
Play-Support mit dem Play-App-Signatur-Schlüssel retten).

### Google Play in Kurzform

1. **Play Console → App einrichten → App-Signatur:** „Play App Signing“ verwenden
   (Google verwaltet den endgültigen App-Schlüssel, du signierst nur mit dem
   *Upload-Schlüssel*).
2. Als Upload-Schlüssel den **eigenen** Schlüssel aus diesem Abschnitt nehmen (nicht
   `n3-ci.p12`).
3. **Android App Bundle** hochladen: `./gradlew bundleRelease`
   → `app/build/outputs/bundle/release/app-release.aab`
4. Beim ersten Upload zeigt Play die Fingerabdrücke an – SHA-1/SHA-256 dort ggf. für
   Google-Dienste (Firebase, Maps) eintragen.

---

## 5. Kurz: was ist was

| Datei | Bedeutung |
|---|---|
| `ci/n3-ci.p12` | fester Build-Schlüssel (im Repo, für Updates der Testbuilds) |
| `keystore.properties` | sagt Gradle, welcher Schlüssel benutzt wird |
| `n3-release.jks` | **dein** privater Schlüssel (nicht im Repo – selbst erzeugen, Backup!) |
| `SIGNIEREN.md` | diese Anleitung |
| `tools/signieren.sh` | signiert eine vorhandene APK mit dem Projekt-Schlüssel |
| `tools/keystore-neu.sh` | erzeugt einen eigenen privaten Schlüssel |
