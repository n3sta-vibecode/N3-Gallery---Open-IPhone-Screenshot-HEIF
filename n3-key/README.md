# N3 Key

**App by n3 Vibecode.** Schlanker Hardware-Tastenmapper in Kotlin, mit nativen Android-Views. Android 9+ (API 28), für One UI ausgelegt, ohne Samsung-exklusive SDKs. Kein Root nötig für vom System freigegebene Tasten.

## APK installieren

1. [`apk/N3-Key-1.0-by-n3-Vibecode.apk`](apk/N3-Key-1.0-by-n3-Vibecode.apk) auf das Telefon laden und öffnen. Bei Bedarf der verwendeten Dateimanager-/Browser-App die Installation unbekannter Apps erlauben.
2. N3 Key öffnen → **Dienst aktivieren** → Datenschutzhinweis lesen → den Dienst in den Android-Bedienungshilfen selbst einschalten.
3. Falls Android eine manuell installierte App sperrt: **App-Info → ⋮ → Eingeschränkte Einstellungen zulassen**. Nur erlauben, wenn du der App vertraust. Die genaue Bezeichnung hängt von One UI ab.
4. **Belegung hinzufügen** → Taste wählen → positive Klickzahl eintragen → Aktion auswählen → speichern.
5. Für eine dedizierte Aktionstaste **Aktions- / andere Taste anlernen** wählen. Erscheint kein Tastencode, leitet das Gerät diese Taste nicht an den Dienst weiter.

Die APK ist eine optimierte, **lokal signierte Testversion**, nicht auf einem echten Samsung-Gerät getestet. Kein Play-Store-Release. Der Test-Signierschlüssel gehört nicht zum Quellcode; spätere Builds mit anderem Schlüssel erfordern eine Deinstallation und verlieren dabei die lokalen Regeln. Für dauerhafte Updates einen eigenen sicheren Release-Schlüssel verwenden.

## Belegungen

| Eingabe | Verhalten |
| --- | --- |
| Lauter oder Leiser einmal kurz | Eigene Aktion nach der Klickpause |
| 2, 3, 5, 100 oder weitere kurze Klicks | Exakte Klickzahl frei konfigurierbar |
| Lauter + Leiser | Beide innerhalb von 150 ms drücken; beide loslassen |
| Mehrfache Lauter/Leiser-Kombination | Wiederholte vollständige Kombidrücke werden gezählt |
| Dedizierte Aktions-/Zusatz-/Tastaturtaste | Anlernbar, **wenn Android sie an Accessibility weitergibt** |

**Kein festes Klicklimit:** Konfiguration und Zähler verwenden `BigInteger`, nicht eine feste Einfach-/Doppel-/Dreifachliste. Endlos weiterklicken löst jedoch nie eine Abschlussaktion aus: Erst die Pause beendet die Folge. Mathematische Unendlichkeit ist auf endlicher Hardware nicht möglich.

**Zeitfenster:** Standard 450 ms, einstellbar von 250 bis 1500 ms. Ein Kurzklick dauert maximal 500 ms. Der nächste Tastendruck muss innerhalb der Pause beginnen; sein Loslassen wird abgewartet. Auch ein Einfachklick muss auf die Pause warten, sonst ließe er sich nicht sicher von mehreren Klicks unterscheiden.

**Keine Mehrfachauslösung:** Eine 3×-Folge löst nur die 3×-Regel aus, nicht zusätzlich 1× und 2×. Kombis unterdrücken die Einzelaktionen dieser beiden Drücke. Beim Pausieren oder Ändern von Regeln werden ausstehende Aktionen verworfen.

## Aktionen

- Taschenlampe umschalten (optionale Kameraberechtigung)
- Musik Play/Pause, nächster / vorheriger Titel
- Installierte App oder Kamera öffnen
- Screenshot (ab Android 11; geschützte Inhalte bleiben geschützt)
- Benachrichtigungen / Schnelleinstellungen öffnen
- Startbildschirm, Zurück, letzte Apps
- Bildschirm sperren
- Lautstärke erhöhen / senken
- Keine Aktion — diese Eingabe blockieren

Kein beliebiger Root-/Shell-Befehl, keine Eingabeinjektion und keine Umgehung des Sperrbildschirms. Android kann einzelne Aktionen verweigern; die App zeigt dann eine Meldung.

## Wichtige Gerätegrenzen

- **Power-/Side-/Bixby-Tasten:** Samsung fängt sie häufig vor normalen Apps ab. Besonders *ein kurzer Druck auf die Seitentaste* lässt sich ohne Root nicht allgemein frei belegen. N3 Key verspricht dies nicht. Reservierte Power-, Home-, Back-, Assist-, Wake- und Sleep-Codes werden nicht angelernt. Sicherheits- und Notfallfunktionen werden nicht umgangen.
- **Display aus / gesperrt:** Funktion hängt davon ab, ob One UI Tastenevents und die gewählte Aktion zulässt. Kein Wake-Lock und keine garantierte Screen-off-Funktion.
- **Andere Tastenmapper:** Android kann nur einen filternden Bedienungshilfedienst priorisieren. Andere Mapper und der systemeigene Lautstärketasten-Bedienungshilfe-Kurzbefehl können kollidieren.
- **Unbelegte Tasten:** Ohne passende Taste in irgendeiner Regel bleiben die Originalereignisse vollständig beim System.
- **Unbelegte Klickzahl einer abgefangenen Lautstärketaste:** Nach der Pause wird Medienlautstärke geändert, bei aktivem Gespräch Gesprächslautstärke. Das ist ein Ersatz, keine identische Reproduktion des kontextabhängigen Systemverhaltens (z. B. Kamera-Auslöser oder Klingelton).
- **Unbelegte Zusatz-/Kombitastenfolgen:** Keine Aktion. Fremde Tastenevents können ohne privilegierte Rechte nicht in andere Apps zurückinjiziert werden.
- **Langes Halten:** Gehaltene Einzel-Lautstärketasten ändern die Lautstärke bei Wiederholungsereignissen, ohne diese als Mehrfachklicks zu zählen. Ohne Wiederholungsereignisse wird beim Loslassen ein Lautstärkeschritt nachgeholt. Lange Kombidrücke führen keine Aktion aus.
- **Hintergrund:** Falls Samsung den Dienst stoppt, N3 Key bei Bedarf aus den Apps im tiefen Standby entfernen. Ein Neustart/OS-Update kann eine erneute Dienstaktivierung erforderlich machen.

## Leichtgewicht & Datenschutz

- Nur Kotlin-Standardbibliothek (durch R8 stark reduziert) und Android-Framework; keine Compose-, AndroidX-, Analytics-, Werbe- oder Netzwerkbibliotheken.
- Kein `INTERNET`, kein Wake-Lock, kein Foreground-Service, kein Polling, kein regelmäßiger Job, keine Boot-Automation.
- Ein vom System gebundener AccessibilityService und ein Handler-Timer nur während einer Klickfolge. Der Dienst muss für globale Belegungen im Hintergrund verfügbar sein; null RAM ist daher nicht möglich.
- Native Views, keine WebView, keine Vorschaubilder aller Apps, App-Liste erst bei Auswahl geladen.
- Keine angeforderten Accessibility-Bildschirminhalte, keine Texte, keine Eingabeprotokolle, keine Netzwerkübertragung. Nur Regel-Tastencodes, Klickabstände und optional im Anlernmodus eine gedrückte Taste werden verarbeitet.
- Regeln ausschließlich in lokalen `SharedPreferences`; Android-Backup deaktiviert. Deinstallation löscht Regeln.
- Kamera nur für die Taschenlampe. Kein Fotozugriff und keine Fotoaufnahme. `MODIFY_AUDIO_SETTINGS` ist eine normale Berechtigung zur Lautstärkesteuerung.

**APK-Größe und Prüfungen:** siehe [BUILD-REPORT.md](BUILD-REPORT.md). Installationsspeicher und RAM wurden nicht auf einem Gerät gemessen und hängen von Android und der Regelanzahl ab.

## Entwickeln

Das Verzeichnis `n3-key` in Android Studio öffnen. JDK 17, Android SDK 35, Gradle 8.13, AGP 8.9.1 und Kotlin 2.1.20.

```sh
cd n3-key
./gradlew :app:assemblePreview :app:lintPreview
```

`preview` ist R8-optimiert und mit dem lokalen Android-Debug-Schlüssel installierbar. `release` ist absichtlich unsigniert; einen eigenen Release-Schlüssel außerhalb des Repositorys verwalten. `debug` ist unoptimiert. Die GitHub-Workflow-Datei baut die Test-APK bei passenden Änderungen und manuell; in dieser Sitzung wurde sie nicht ausgeführt.

### Tests ohne Android-Gerät

Mit `kotlinc` und `java` im PATH (oder `KOTLIN_HOME`/`JAVA_HOME` gesetzt):

```sh
tools/test.sh
```

28 deterministische Tests der reinen Kotlin-Erkennung: Einzel-/Mehrfachklick, 2.000 Klicks, `BigInteger`, Timergrenzen, lange Drücke, Kombis in beiden Reihenfolgen, Abbruch und Reset. Keine externen Test-Abhängigkeiten.

`tools/build-standalone.sh` baut alternativ mit vorhandenen Kotlin-, AAPT2-, R8- und apksigner-Werkzeugen ohne Gradle/Netzwerk. Die benötigten Umgebungsvariablen stehen im Skript. Das wurde für die beiliegende APK verwendet. Toolchains, Build-Caches und private Signierschlüssel sind nicht eingecheckt.

### Noch auf echtem Gerät prüfen

1. Dienst bewusst aktivieren, Start/Pause und Dienst-Deaktivierung testen.
2. Je 1×, 2× und 5× Lauter mit unterschiedlichen Aktionen belegen. Keine vorzeitige 1×-Aktion erwarten.
3. Lauter + Leiser innerhalb und außerhalb der 150-ms-Grenze testen, inklusive mehrfacher Kombination.
4. Lange Drücke und unbelegte Klickzahlen auf Lautstärkefunktion prüfen; Pause darf keine ausstehende Aktion nachliefern.
5. Zusatz-/Aktionstaste anlernen, außerhalb der App testen. Nicht erkannte reservierte Tasten als Gerätebeschränkung behandeln.
6. Taschenlampe mit erteilter und verweigerter Berechtigung sowie bereits eingeschalteter Lampe testen.
7. App-Start, Musik, Screenshot, Sperren und Systemaktionen prüfen; Sperrbildschirm und Display-aus getrennt testen.
8. One UI-Hintergrundmanagement, Neustart, Android 15/16-Inset-Darstellung und große Schrift prüfen.
9. RAM mit `adb shell dumpsys meminfo com.n3vibecode.key`, Installationsgröße über App-Info ermitteln.

## Lizenz

Projekt unter der vorhandenen GPL-3.0-Lizenz des Repositorys. Kotlin-Hinweise und Apache-2.0-Lizenz in `NOTICE` / `LICENSE-KOTLIN.txt` und in den APK-Assets.
