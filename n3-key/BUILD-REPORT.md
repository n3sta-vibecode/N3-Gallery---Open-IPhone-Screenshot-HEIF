# N3 Key 1.0 — Build- und Testbericht

**Datum:** 14. Oktober 2026  
**Artefakt:** `apk/N3-Key-1.0-by-n3-Vibecode.apk`  
**Paket:** `com.n3vibecode.key`  
**APK-Größe:** 49.833 Bytes (49,8 kB / 48,7 KiB)  
**Android:** mindestens API 28 (Android 9), Ziel-/Compile-API 35

## Tatsächlich ausgeführte Prüfungen

| Prüfung | Ergebnis |
| --- | --- |
| Kotlin-Quellcode gegen Android API 35 kompilieren | Erfolgreich |
| Reine Kotlin-Tests der Klick-Erkennung | **28 / 28 bestanden** |
| Ressourcen mit AAPT2 kompilieren/verknüpfen | Erfolgreich |
| R8-Optimierung und DEX-Erstellung | Erfolgreich |
| APK mit RSA-2048-Testschlüssel signieren | Erfolgreich |
| `apksigner verify --verbose --print-certs` | **Verifies**, APK Signature Scheme v3 für minSdk 28 |
| ZIP-Integrität | Erfolgreich, keine defekten Einträge |
| Unkomprimierte `resources.arsc`, 4-Byte-Ausrichtung | Erfolgreich |
| DEX enthält Android-Einstiegspunkte | MainActivity, KeyService, onCreate, onKeyEvent, onServiceConnected vorhanden |
| Manifest / Accessibility-Metadaten aus fertiger APK auslesen | Paket, SDK-Versionen, Startaktivität, Key-Filter und fehlender Fensterinhaltszugriff bestätigt |
| Berechtigungen | Nur CAMERA und MODIFY_AUDIO_SETTINGS, **kein INTERNET** |
| Shell-Syntax der Build-/Testskripte | Erfolgreich |

R8 meldete ausschließlich doppelte `META-INF/MANIFEST.MF`-Ressourcen der Eingabe-JARs; diese werden nicht in die APK übernommen. Keine Kotlin-Kompilierfehler.

**Nicht ausgeführt:** Installation/Start auf Android, Emulator- oder One-UI-Hardwaretests, Android-Lint/Gradle-Build, GitHub Actions, RAM-/Installationsspeichermessung, vollständiger Geräte-/Berechtigungs-/Sperrbildschirmtest. Die Test-APK ist daher kein als gerätegetestet ausgegebener Release.

## APK-Prüfsumme

```text
SHA-256
bbd31e335685aa575637803980dff69de5b799fec4d55feecfd3cecda08dbdcd
```

Öffentliches Test-Signierzertifikat:

```text
CN=N3 Vibecode Test Build, O=n3 Vibecode, C=DE
SHA-256: 675c44cd282e5f5e1400713574431cddb401e2d7bbdab1609d20c5eb033211e2
```

Der private Testschlüssel ist nicht Teil des Projekts. Die Signatur bestätigt die Integrität dieses Builds, nicht eine externe Sicherheitsprüfung oder einen App-Store-Release.

## Lokaler Build-Weg

Die Sandbox hatte zunächst kein JDK/Android SDK und konnte die üblichen Google-/Gradle-Downloadhosts nicht erreichen. Deshalb wurde die APK mit `tools/build-standalone.sh` aus vorhandenen/einzeln bezogenen Build-Werkzeugen erstellt — **keine umbenannte Galerie-APK, kein Web-Wrapper und keine fremde App-Vorlage**.

- Java 17.0.9 via PyPI `jdk4py==17.0.9.2`.
- Kotlin JVM compiler 2.1.20 via npm `kotlin-compiler@2.1.20`.
- Android API 35 `android.jar` aus `Sable/android-platforms` (GitHub).
- AAPT2 und apksigner 0.9 aus dem npm-Paket `@postar/apktool-node@0.3.4` (gebündelte Android-Buildwerkzeuge, nicht in der App).
- R8 8.2.4-benzo aus `yaap/prebuilts_r8` (GitHub; Build-Werkzeug, nicht Teil der Laufzeit).
- Ressourcen/DEX gepackt, gespeicherte ZIP-Ressourcen auf 4 Byte ausgerichtet, Testsignatur erzeugt und geprüft.

Werkzeug-SHA-256 zur Dokumentation dieses Builds:

| Werkzeug | SHA-256 |
| --- | --- |
| Kotlin npm-Archiv 2.1.20 | `de1476137985c2d3aa1bd50d8976bb7eb900ca564321686ca2458d0fce45c346` |
| android.jar | `4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b` |
| AAPT2 | `a037accc63700aad0fdfdb47e3428cf9c3155ac9d8b35097ff52ec59b4237aaf` |
| R8 | `1e1c24ae183c9404e5e07edeaba27403e437eb71ec3ed7a2f393ead722a5781e` |
| apksigner | `eefdd6aed9db9fb849e4c98a50d8741e19d1b674ba6547220bcb9c3ed152123a` |

Der reguläre Android-Studio-/Gradle-Build verwendet AGP 8.9.1 mit dessen offiziellen Android-Buildwerkzeugen. Durch andere Toolversionen, Ressourcen-Shrinking, Zeitstempel und Signierung ist dieser nicht byteidentisch mit der hier erzeugten Test-APK.
