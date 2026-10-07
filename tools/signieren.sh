#!/usr/bin/env bash
# Signiert eine vorhandene (unsignierte) APK mit dem Projekt-Schluessel.
#
#   tools/signieren.sh unsigniert.apk [ausgabe.apk]
#
# Schluessel + Zugangsdaten kommen aus keystore.properties (nicht versioniert).
# GEHÄRTET: Der frueher mitgelieferte oeffentliche CI-Schluessel ci/n3-ci.p12 ist
# kompromittiert und wurde entfernt; dieses Skript nutzt keinen hartkodierten
# Fingerabdruck mehr, sondern zeigt den tatsaechlich verwendeten an.
set -euo pipefail

APK="${1:-}"
OUT="${2:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "Benutzung: tools/signieren.sh unsigniert.apk [ausgabe.apk]"
  exit 1
fi
if [ -z "$OUT" ]; then
  OUT="${APK%.apk}-signiert.apk"
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/keystore.properties"
[ -f "$PROPS" ] || { echo "keystore.properties nicht gefunden: $PROPS"; exit 1; }

get() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-; }
STORE="$(get storeFile)"
TYPE="$(get storeType)"; TYPE="${TYPE:-PKCS12}"
STOREPASS="$(get storePassword)"
ALIAS="$(get keyAlias)"
KEYPASS="$(get keyPassword)"; KEYPASS="${KEYPASS:-$STOREPASS}"
[ -f "$ROOT/$STORE" ] || { echo "Schluesseldatei fehlt: $ROOT/$STORE"; exit 1; }

# build-tools finden (neueste Version)
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$BT" ]; then
  echo "build-tools nicht gefunden (ANDROID_HOME setzen)."
  exit 1
fi
ZIPALIGN="${BT}zipalign"
SIGNER="${BT}apksigner"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ALIGNED="$TMP/aligned.apk"
if [ -x "$ZIPALIGN" ]; then
  "$ZIPALIGN" -p -f 4 "$APK" "$ALIGNED"
else
  cp "$APK" "$ALIGNED"
fi

"$SIGNER" sign \
  --ks "$ROOT/$STORE" --ks-type "$TYPE" \
  --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" \
  --ks-key-alias "$ALIAS" \
  --out "$OUT" "$ALIGNED"

echo ""
echo "Signiert: $OUT"
echo "Verwendeter Fingerabdruck (SHA-256):"
"$SIGNER" verify --print-certs "$OUT" | grep -i "SHA-256 digest" || true
# Warnung, falls versehentlich mit dem oeffentlich bekannten, kompromittierten
# CI-Schluessel signiert wurde.
LEAKED="2fb36ac65d73a4b1e437c84784e0eb50442f123adb923bfd450da8eb24c848e0"
if "$SIGNER" verify --print-certs "$OUT" 2>/dev/null | grep -qi "$LEAKED"; then
  echo "WARNUNG: Signiert mit dem OEFFENTLICH BEKANNTEN CI-Schluessel (kompromittiert)!"
  echo "         Bitte einen eigenen, privaten Schluessel verwenden (tools/keystore-neu.sh)."
  exit 2
fi
