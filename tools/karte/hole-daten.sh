#!/usr/bin/env bash
# Lädt die Rohdaten für die Offline-Karte (nur zum Neuerzeugen der Kartendateien nötig).
#
# Die fertigen Dateien liegen bereits im Repository:
#   app/src/main/assets/map/world.dat.gz
#   app/src/main/assets/map/places.dat.gz
# Zum Bauen der App werden sie NICHT gebraucht – dieser Ordner ist nur für die Pflege.
#
#   bash tools/karte/hole-daten.sh     # Quellen nach tools/karte/.data laden
#   node tools/karte/genmap.js         # world.dat.gz + places.dat.gz neu schreiben
set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="$HIER/.data"
mkdir -p "$DATA"
cd "$DATA"

# Quellen
gh_zip() { # repo, ref, ziel
  local url="https://codeload.github.com/$1/zip/refs/heads/$2"
  echo "→ $1 ($2)"
  curl -fsSL "$url" -o tmp.zip
  rm -rf "$3" && unzip -q tmp.zip && mv "$1-$2" "$3" && rm -f tmp.zip
}

if [ ! -d world-atlas ]; then
  mkdir -p world-atlas
  echo "→ world-atlas (Natural Earth 1:50m)"
  curl -fsSL "https://registry.npmjs.org/world-atlas/-/world-atlas-2.0.2.tgz" -o wa.tgz
  tar xzf wa.tgz -C world-atlas --strip-components=1
  rm -f wa.tgz
fi

if [ ! -d cities.json ]; then
  mkdir -p cities.json
  echo "→ cities.json (GeoNames, CC BY 4.0)"
  curl -fsSL "https://registry.npmjs.org/cities.json/-/cities.json-1.1.66.tgz" -o cj.tgz
  tar xzf cj.tgz -C cities.json --strip-components=1
  rm -f cj.tgz
fi

if [ ! -d node_modules ]; then
  echo "→ Hilfspakete (pbf, i18n-iso-countries, countries-list)"
  cat > package.json <<'JSON'
{ "name": "n3-karte-werkzeuge", "private": true, "version": "1.0.0",
  "dependencies": {
    "pbf": "3.3.0",
    "i18n-iso-countries": "7.14.0",
    "countries-list": "3.1.0"
  } }
JSON
  npm install --silent --no-audit --no-fund
fi

echo "Fertig. Jetzt: node tools/karte/genmap.js"
