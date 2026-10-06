#!/usr/bin/env bash
# Erzeugt einen EIGENEN, privaten Signaturschluessel fuer die App.
#
#   tools/keystore-neu.sh [dateiname.jks]
#
# Das Passwort wird zufaellig erzeugt und einmal angezeigt -> sofort notieren!
# Der private Schluessel ist per .gitignore NICHT im Repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="${1:-n3-release.jks}"
OUT="$ROOT/$NAME"
[ -f "$OUT" ] && { echo "$OUT existiert schon - nichts geaendert."; exit 1; }

command -v keytool >/dev/null 2>&1 || { echo "keytool fehlt (JDK installieren)."; exit 1; }

PASS="$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
ALIAS="n3"

echo "Erzeuge $NAME ..."
keytool -genkeypair -v \
  -keystore "$OUT" -storetype JKS \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=N3 Vibecode, O=N3 Vibecode, C=CH" >/dev/null

"$ROOT/tools/../tools/keystore-info.sh" "$OUT" "$PASS" 2>/dev/null || true

echo ""
echo "======================================================================"
echo "  FERTIG. Diese Angaben JETZT sichern (Passwort kommt nur hier vor!):"
echo ""
echo "  Datei:            $NAME"
echo "  Alias:            $ALIAS"
echo "  Passwort:         $PASS"
echo ""
echo "  Fuer Gradle zusaetzlich in keystore.properties eintragen:"
echo "    storeFile=$NAME"
echo "    storeType=JKS"
echo "    storePassword=$PASS"
echo "    keyAlias=$ALIAS"
echo "    keyPassword=$PASS"
echo ""
echo "  Wichtig: Datei + Passwort an einem sicheren Ort aufbewahren!"
echo "  Ohne diesen Schluessel sind spaeter keine Updates mehr moeglich."
echo "======================================================================"
