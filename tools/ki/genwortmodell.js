#!/usr/bin/env node
/*
 * Erzeugt das eingebaute Wort-Bedeutungsmodell der Bildersuche:
 *      app/src/main/assets/ai/wortmodell.txt
 *
 * Warum? Die App hat kein Internet. Ein Gemma-Embedding-Modell (~ 200 MB) kann sie nicht
 * nachladen – es muss beim Bauen im App-Paket liegen (siehe README-KI.md). Damit die Suche
 * trotzdem auf JEDEM Gerät und sofort funktioniert, liegt dieses kleine Modell bei: eine
 * Liste von Wörtern, die im Zusammenhang mit Fotos vorkommen, jeweils mit einem
 * Bedeutungsvektor. Wörter, die dicht beieinander liegen, bedeuten Ähnliches
 * („strand“ und „meer“), Wörter, die weit auseinander liegen, nicht.
 *
 * Aufbau: Begriffe (Themen) spannen den Raum auf. Jedes Wort bekommt Gewichte auf einige
 * Themen. Dadurch finden sich Verwandtschaften wie „urlaub“ → „strand“, „meer“, „hotel“.
 *
 * Aufruf: node tools/ki/genwortmodell.js
 */
const fs = require('fs')
const path = require('path')

const OUT = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'ai')
fs.mkdirSync(OUT, { recursive: true })

/**
 * Themen (dimensions). Jedes Thema ist eine „Richtung“ im Bedeutungsraum.
 * Reihenfolge = Index im Vektor.
 */
const TOPICS = [
  'natur', 'wasser', 'berg', 'stadt', 'innen', 'person', 'tier', 'pflanze',
  'essen', 'technik', 'text', 'nacht', 'warm', 'kalt', 'sport', 'verkehr',
  'feier', 'arbeit', 'kunst', 'himmel', 'schnee', 'strand', 'wald', 'wüste',
  'musik', 'kind', 'mode', 'geld', 'gesundheit', 'bauwerk'
]
const DIM = TOPICS.length + 2 // + zwei Reserve-Achsen für Schreibweise/Wortart

const T = {}
TOPICS.forEach((t, i) => { T[t] = i })

/** Wort → Themen mit Gewicht. */
const WORDS = {
  // --- Wasser / Strand / Urlaub
  strand: { strand: 1, wasser: 0.8, natur: 0.4, himmel: 0.3, warm: 0.4 },
  meer: { wasser: 1, strand: 0.7, natur: 0.4, blau: 0 },
  see: { wasser: 1, natur: 0.5, berg: 0.2, wald: 0.2 },
  fluss: { wasser: 1, natur: 0.5, wald: 0.3 },
  bach: { wasser: 0.9, natur: 0.4, wald: 0.4 },
  wasser: { wasser: 1, natur: 0.3 },
  welle: { wasser: 0.9, strand: 0.6, sport: 0.2 },
  surfen: { sport: 0.9, wasser: 0.7, strand: 0.6 },
  tauchen: { sport: 0.8, wasser: 0.9, strand: 0.5 },
  boot: { verkehr: 0.7, wasser: 0.9 },
  schiff: { verkehr: 0.8, wasser: 0.8 },
  segeln: { sport: 0.6, wasser: 0.9, verkehr: 0.4 },
  urlaub: { strand: 0.6, natur: 0.4, himmel: 0.3, warm: 0.4, reise: 0 },
  reise: { verkehr: 0.4, natur: 0.3, stadt: 0.2 },
  hotel: { stadt: 0.5, innen: 0.4, bauwerk: 0.4 },
  insel: { wasser: 0.9, strand: 0.7, natur: 0.4 },
  bucht: { wasser: 0.8, strand: 0.6 },
  hafen: { wasser: 0.7, verkehr: 0.6, stadt: 0.4 },
  pool: { wasser: 0.8, sport: 0.4, innen: 0.3 },
  schwimmen: { sport: 0.8, wasser: 0.7 },

  // --- Berge / Natur
  berg: { berg: 1, natur: 0.6, himmel: 0.3, kalt: 0.3 },
  berge: { berg: 1, natur: 0.6, kalt: 0.3 },
  gipfel: { berg: 1, natur: 0.5, sport: 0.3 },
  wandern: { sport: 0.7, berg: 0.8, natur: 0.7 },
  wanderung: { sport: 0.7, berg: 0.8, natur: 0.7 },
  alpen: { berg: 1, natur: 0.6, schnee: 0.4 },
  tal: { berg: 0.7, natur: 0.6 },
  hügel: { berg: 0.6, natur: 0.7, pflanze: 0.4 },
  fels: { berg: 0.8, natur: 0.5 },
  klettern: { sport: 0.9, berg: 0.7 },
  natur: { natur: 1, pflanze: 0.5 },
  landschaft: { natur: 0.9, berg: 0.4, himmel: 0.4 },
  wald: { wald: 1, pflanze: 0.8, natur: 0.9 },
  baum: { wald: 0.9, pflanze: 0.9, natur: 0.7 },
  bäume: { wald: 0.9, pflanze: 0.9, natur: 0.7 },
  blume: { pflanze: 1, natur: 0.7, warm: 0.3 },
  blumen: { pflanze: 1, natur: 0.7, warm: 0.3 },
  garten: { pflanze: 0.8, natur: 0.5, bauwerk: 0.2 },
  park: { pflanze: 0.8, natur: 0.6, stadt: 0.3 },
  wiese: { pflanze: 0.8, natur: 0.8 },
  feld: { pflanze: 0.6, natur: 0.7, arbeit: 0.3 },
  wüste: { wüste: 1, warm: 0.6, natur: 0.3 },
  sand: { strand: 0.9, wüste: 0.4, warm: 0.3 },
  schnee: { schnee: 1, kalt: 0.9, natur: 0.3, berg: 0.5 },
  winter: { schnee: 0.8, kalt: 0.9 },
  eis: { kalt: 0.9, schnee: 0.7, wasser: 0.4 },

  // --- Himmel / Wetter / Licht
  himmel: { himmel: 1, natur: 0.4 },
  wolke: { himmel: 1, natur: 0.3 },
  wolken: { himmel: 1, natur: 0.3 },
  sonne: { himmel: 0.8, warm: 0.7, natur: 0.3 },
  sonnig: { himmel: 0.7, warm: 0.7 },
  sonnenuntergang: { himmel: 0.9, warm: 0.8, natur: 0.5, kunst: 0.3 },
  sonnenaufgang: { himmel: 0.9, warm: 0.5, natur: 0.5 },
  abend: { nacht: 0.5, himmel: 0.5, warm: 0.4 },
  nacht: { nacht: 1, himmel: 0.4 },
  dunkel: { nacht: 0.8, kalt: 0.2 },
  regen: { himmel: 0.7, kalt: 0.4, natur: 0.3 },
  nebel: { himmel: 0.6, kalt: 0.4, natur: 0.5 },
  gewitter: { himmel: 0.8, nacht: 0.3 },
  regenbogen: { himmel: 0.8, natur: 0.4, kunst: 0.3 },
  licht: { kunst: 0.3, himmel: 0.4 },
  schatten: { kunst: 0.3, natur: 0.2 },

  // --- Stadt / Bauwerke
  stadt: { stadt: 1, bauwerk: 0.5, verkehr: 0.3 },
  dorf: { stadt: 0.5, bauwerk: 0.4, natur: 0.3 },
  straße: { stadt: 0.7, verkehr: 0.8 },
  strasse: { stadt: 0.7, verkehr: 0.8 },
  platz: { stadt: 0.7, bauwerk: 0.4 },
  gebäude: { bauwerk: 1, stadt: 0.7 },
  gebaeude: { bauwerk: 1, stadt: 0.7 },
  haus: { bauwerk: 0.9, stadt: 0.5, innen: 0.3 },
  kirche: { bauwerk: 0.9, kunst: 0.4, stadt: 0.5 },
  turm: { bauwerk: 0.9, stadt: 0.5 },
  brücke: { bauwerk: 0.9, verkehr: 0.5, wasser: 0.3 },
  bruecke: { bauwerk: 0.9, verkehr: 0.5, wasser: 0.3 },
  schloss: { bauwerk: 1, kunst: 0.4, stadt: 0.3 },
  museum: { kunst: 0.8, bauwerk: 0.6, stadt: 0.5 },
  markt: { stadt: 0.7, essen: 0.5, person: 0.4 },
  skyline: { stadt: 0.9, bauwerk: 0.8, himmel: 0.3 },
  hochhaus: { stadt: 0.9, bauwerk: 0.9 },
  altstadt: { stadt: 0.8, bauwerk: 0.7, kunst: 0.3 },

  // --- Innen
  zimmer: { innen: 1, bauwerk: 0.3 },
  wohnung: { innen: 1, bauwerk: 0.4 },
  küche: { innen: 0.9, essen: 0.6 },
  kueche: { innen: 0.9, essen: 0.6 },
  bad: { innen: 0.9, wasser: 0.3 },
  büro: { innen: 0.8, arbeit: 0.8, technik: 0.4 },
  buero: { innen: 0.8, arbeit: 0.8, technik: 0.4 },
  schule: { innen: 0.6, arbeit: 0.5, kind: 0.5 },
  restaurant: { essen: 0.7, innen: 0.6, stadt: 0.4 },
  cafe: { essen: 0.7, innen: 0.6, stadt: 0.4 },
  café: { essen: 0.7, innen: 0.6, stadt: 0.4 },
  mahlzeit: { essen: 1, innen: 0.3 },

  // --- Personen / Familie
  person: { person: 1 },
  leute: { person: 1, stadt: 0.3 },
  mensch: { person: 1 },
  menschen: { person: 1, stadt: 0.3 },
  porträt: { person: 1, kunst: 0.3 },
  portraet: { person: 1, kunst: 0.3 },
  selfie: { person: 1, kunst: 0.2 },
  gesicht: { person: 1 },
  familie: { person: 0.9, kind: 0.6, feier: 0.4 },
  freund: { person: 0.9, feier: 0.3 },
  freunde: { person: 0.9, feier: 0.4 },
  kind: { kind: 1, person: 0.6 },
  kinder: { kind: 1, person: 0.6, feier: 0.3 },
  baby: { kind: 1, person: 0.6 },
  babybauch: { kind: 0.8, person: 0.8 },
  hochzeit: { feier: 1, person: 0.9, mode: 0.5 },
  geburtstag: { feier: 1, person: 0.7, essen: 0.4 },
  party: { feier: 1, person: 0.8, musik: 0.4, nacht: 0.4 },
  fest: { feier: 0.9, person: 0.5 },
  weihnachten: { feier: 0.9, warm: 0.3, innen: 0.4 },
  grüße: { person: 0.4, text: 0.4 },
  gruesse: { person: 0.4, text: 0.4 },

  // --- Tiere
  tier: { tier: 1, natur: 0.4 },
  tiere: { tier: 1, natur: 0.4 },
  hund: { tier: 1, person: 0.3 },
  katze: { tier: 1, innen: 0.3 },
  // Haustier-Familie: soll zusammen mit hund/katze/tier treffen
  haustier: { tier: 1, innen: 0.4 },
  haustiere: { tier: 1, innen: 0.4 },
  pferd: { tier: 1, sport: 0.3, natur: 0.4 },
  vogel: { tier: 1, natur: 0.4, himmel: 0.3 },
  vögel: { tier: 1, natur: 0.4, himmel: 0.3 },
  fisch: { tier: 0.8, wasser: 0.6 },
  insekt: { tier: 0.9, pflanze: 0.3 },
  schmetterling: { tier: 0.9, pflanze: 0.4, warm: 0.3 },
  zoo: { tier: 1, stadt: 0.3 },
  wildtier: { tier: 1, natur: 0.6 },

  // --- Essen
  essen: { essen: 1 },
  speise: { essen: 1 },
  kuchen: { essen: 1, feier: 0.4, warm: 0.2 },
  torte: { essen: 1, feier: 0.5 },
  brot: { essen: 1, arbeit: 0.2 },
  kaffee: { essen: 0.8, innen: 0.4 },
  bier: { essen: 0.8, feier: 0.5 },
  wein: { essen: 0.8, feier: 0.5 },
  obst: { essen: 1, pflanze: 0.3, gesundheit: 0.4 },
  gemüse: { essen: 1, pflanze: 0.4, gesundheit: 0.4 },
  gemuese: { essen: 1, pflanze: 0.4, gesundheit: 0.4 },
  pizza: { essen: 1 },
  frühstück: { essen: 1, innen: 0.3 },
  fruehstueck: { essen: 1, innen: 0.3 },
  grill: { essen: 0.9, feier: 0.4, warm: 0.3 },

  // --- Technik / Bildschirm / Screenshot
  screenshot: { technik: 1, text: 1 },
  bildschirmfoto: { technik: 1, text: 1 },
  bildschirm: { technik: 1, text: 0.6 },
  handy: { technik: 1 },
  smartphone: { technik: 1 },
  iphone: { technik: 1, geld: 0.2 },
  computer: { technik: 1, arbeit: 0.6 },
  laptop: { technik: 1, arbeit: 0.6 },
  tastatur: { technik: 0.9, arbeit: 0.5 },
  software: { technik: 0.9, arbeit: 0.4 },
  app: { technik: 0.8, text: 0.3 },
  chat: { technik: 0.7, text: 0.9, person: 0.4 },
  nachricht: { text: 0.9, technik: 0.5 },
  email: { text: 0.8, arbeit: 0.5, technik: 0.4 },
  dokument: { text: 1, arbeit: 0.5 },
  papier: { text: 0.7, arbeit: 0.4 },
  rechnung: { text: 0.8, geld: 0.8, arbeit: 0.4 },
  tabelle: { text: 0.8, arbeit: 0.6, technik: 0.4 },
  qr: { technik: 0.8, text: 0.6 },
  barcode: { technik: 0.8, text: 0.5 },
  kabel: { technik: 0.8 },
  kamera: { technik: 0.8, kunst: 0.3 },

  // --- Arbeit / Geld
  arbeit: { arbeit: 1 },
  beruf: { arbeit: 1 },
  meeting: { arbeit: 0.9, person: 0.5, innen: 0.5 },
  projekt: { arbeit: 0.8, text: 0.3 },
  geld: { geld: 1 },
  bank: { geld: 0.8, stadt: 0.4, bauwerk: 0.3 },
  kasse: { geld: 0.8, stadt: 0.3 },
  preis: { geld: 0.7, text: 0.4 },
  einkauf: { geld: 0.6, stadt: 0.5, essen: 0.3 },
  quittung: { geld: 0.8, text: 0.8 },

  // --- Verkehr
  auto: { verkehr: 1 },
  wagen: { verkehr: 1 },
  fahrrad: { verkehr: 0.8, sport: 0.7 },
  velo: { verkehr: 0.8, sport: 0.7 },
  zug: { verkehr: 1 },
  bahn: { verkehr: 1 },
  bahnhof: { verkehr: 0.9, stadt: 0.5, bauwerk: 0.4 },
  flugzeug: { verkehr: 1, himmel: 0.4 },
  flughafen: { verkehr: 1, bauwerk: 0.4 },
  bus: { verkehr: 1 },
  roller: { verkehr: 0.9 },
  motorrad: { verkehr: 0.9, sport: 0.4 },
  parkplatz: { verkehr: 0.7, stadt: 0.3 },

  // --- Sport
  sport: { sport: 1 },
  fussball: { sport: 1, feier: 0.2 },
  fußball: { sport: 1, feier: 0.2 },
  laufen: { sport: 0.9, gesundheit: 0.4 },
  joggen: { sport: 0.9, gesundheit: 0.4 },
  fitness: { sport: 0.8, gesundheit: 0.6, innen: 0.3 },
  ski: { sport: 0.9, schnee: 0.8, berg: 0.6 },
  skifahren: { sport: 0.9, schnee: 0.8, berg: 0.6 },
  snowboard: { sport: 0.9, schnee: 0.8, berg: 0.5 },

  // --- Kunst / Musik
  kunst: { kunst: 1 },
  bild: { kunst: 0.6, technik: 0.2 },
  foto: { kunst: 0.6, technik: 0.3 },
  malerei: { kunst: 1 },
  graffiti: { kunst: 0.9, stadt: 0.6 },
  musik: { musik: 1, kunst: 0.4 },
  konzert: { musik: 1, feier: 0.6, nacht: 0.4, person: 0.6 },
  gitarre: { musik: 1 },
  klavier: { musik: 1, innen: 0.4 },
  theater: { kunst: 0.7, musik: 0.3, innen: 0.4 },

  // --- Mode / Gesundheit
  mode: { mode: 1 },
  kleidung: { mode: 1 },
  schuhe: { mode: 1 },
  tasche: { mode: 0.8, geld: 0.3 },
  schmuck: { mode: 0.9, geld: 0.5 },
  gesundheit: { gesundheit: 1 },
  arzt: { gesundheit: 1, person: 0.4, innen: 0.4 },
  medizin: { gesundheit: 1, text: 0.3 },
  krankenhaus: { gesundheit: 0.9, bauwerk: 0.5, stadt: 0.3 },
  sportplatz: { sport: 0.9, stadt: 0.3 }
}

// ------------------------------------------------------------------ Vektoren schreiben

const lines = []
lines.push('# N3 Gallery – eingebautes Wort-Bedeutungsmodell (siehe tools/ki/genwortmodell.js)')
lines.push('# Aufbau:  dim <Anzahl>   und danach je Zeile:  Wort  Wert1 Wert2 …')
lines.push(`dim ${DIM}`)

// Themen als eigene Wörter, damit auch „berg“ in Suchtexten direkt trifft
const rows = []
TOPICS.forEach((t, i) => {
  const v = new Array(DIM).fill(0)
  v[i] = 1
  rows.push([t, v])
})

function vecFor(weights) {
  const v = new Array(DIM).fill(0)
  for (const key of Object.keys(weights)) {
    const idx = T[key]
    if (idx === undefined) {
      // Unbekanntes Thema: als eigene Achse über den Hash verteilen (bleibt trotzdem stabil)
      const h = DIM - 2 + (hash(key) % 2)
      v[h] += weights[key]
      continue
    }
    v[idx] += weights[key]
  }
  // Normieren passiert in der App – hier nur schreiben
  return v
}

function hash(s) {
  let h = 0
  for (const c of s) h = h * 31 + c.charCodeAt(0)
  return h & 0x7fffffff
}

for (const [word, weights] of Object.entries(WORDS)) {
  rows.push([word, vecFor(weights)])
}

// Ausgabe: nur Werte bis zur letzten Stelle ungleich 0 schreiben (kleine Datei)
for (const [word, v] of rows) {
  let last = v.length - 1
  while (last >= 0 && v[last] === 0) last--
  const values = v.slice(0, last + 1).map(x => {
    const r = Math.round(x * 1000) / 1000
    return String(r)
  })
  lines.push(`${word} ${values.join(' ')}`)
}

const text = lines.join('\n') + '\n'
fs.writeFileSync(path.join(OUT, 'wortmodell.txt'), text)
console.log('wortmodell.txt:', (text.length / 1024).toFixed(0) + ' kB,', rows.length, 'Einträge,',
  DIM, 'Dimensionen')
