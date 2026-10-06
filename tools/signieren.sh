#!/usr/bin/env bash
# Signiert eine vorhandene (unsignierte) APK mit dem Projekt-Schluessel.
#
#   tools/signieren.sh unsigniert.apk [ausgabe.apk]
#
# Der Schluessel liegt in ci/n3-ci.p12 (Zugangsdaten siehe SIGNIEREN.md bzw.
# keystore.properties). Danach wird der Fingerabdruck geprueft.
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
"$SIGNER" verify --print-certs "$OUT" | grep -i "SHA-256 digest" || true
echo "Erwartet: 2fb36ac65d73a4b1e437c84784e0eb50442f123adb923bfd450da8eb24c848e0"
