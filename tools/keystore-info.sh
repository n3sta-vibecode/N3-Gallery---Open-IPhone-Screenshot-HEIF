#!/usr/bin/env bash
# Zeigt Inhaber und Fingerabdruecke eines Signaturschluessels.
#
#   tools/keystore-info.sh [datei] [passwort]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILE="${1:-}"
PASS="${2:-}"
PROPS="$ROOT/keystore.properties"

if [ -z "$FILE" ]; then
  FILE="$(grep -E "^storeFile=" "$PROPS" | head -1 | cut -d= -f2-)"
  [ -n "$PASS" ] || PASS="$(grep -E "^storePassword=" "$PROPS" | head -1 | cut -d= -f2-)"
fi
[ -f "$ROOT/$FILE" ] || FILE="$FILE"
[ -f "$FILE" ] || { echo "Schluesseldatei nicht gefunden: $FILE"; exit 1; }
[ -n "$PASS" ] || { echo "Passwort fehlt."; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
case "$FILE" in
  *.p12|*.pfx)
    openssl pkcs12 -in "$FILE" -passin "pass:$PASS" -nokeys -clcerts -out "$TMP/c.pem" 2>/dev/null ;;
  *)
    keytool -exportcert -keystore "$FILE" -storepass "$PASS" -alias n3ci -rfc \
      -file "$TMP/c.pem" 2>/dev/null || \
    keytool -exportcert -keystore "$FILE" -storepass "$PASS" -rfc -file "$TMP/c.pem" ;;
esac

openssl x509 -in "$TMP/c.pem" -noout -subject -dates \
  -fingerprint -sha256 -fingerprint -sha1 -fingerprint -md5
