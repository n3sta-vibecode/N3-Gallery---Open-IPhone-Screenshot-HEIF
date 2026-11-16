#!/usr/bin/env node
/*
 * Erzeugt die Offline-Kartendaten für N3 Gallery (kein Internet in der App nötig).
 *
 *   app/src/main/assets/map/world.dat.gz   – vereinfachte Landflächen für die gezeichnete Karte
 *   app/src/main/assets/map/places.dat.gz  – Orte (ab 5 000 Einwohnern) für die Ortsanzeige
 *
 * Quellen (alle frei, Nennung in der App unter „Über die App“):
 *   Landflächen: Natural Earth 1:50m (gemeinfrei)  – TopoJSON über npm "world-atlas"
 *   Ortsnamen:   GeoNames (CC BY 4.0)              – PBF über npm "cities.json"
 *   Ländernamen: i18n-iso-countries (MIT), countries-list (MIT)
 *
 * Aufruf:   bash tools/karte/hole-daten.sh      (lädt die Quellen nach tools/karte/.data)
 *           node tools/karte/genmap.js         (schreibt die Dateien in die Assets)
 *
 * Formate (little endian, Strings UTF-8):
 *
 *   world.dat.gz  (gzip-gepackt)
 *     int32 magic 'N3W1' | int32 ringCount
 *     je Ring: int32 pointCount, dann pointCount × (int32 lonE5, int32 latE5)
 *
 *   places.dat.gz  (gzip-gepackt)
 *     int32 magic 'N3P1' | int32 countryCount
 *     je Land:  varint length + length Bytes: "CC\nde\nen\nfr\nit" (CC = ISO 3166-1 alpha-2)
 *     int32 placeCount
 *     je Ort:   int32 lonE5 | int32 latE5 | u8 popGrade | u8 countryIdx | u16 nameIdx
 *     int32 nameCount
 *     je Name:  varint length + UTF-8 Bytes
 *
 *   popGrade: 0 = < 5 000, 1 = ≥ 5 000, 2 = ≥ 20 000, 3 = ≥ 50 000, 4 = ≥ 100 000,
 *             5 = ≥ 500 000, 6 = ≥ 1 Mio.  (größere Orte werden beim Zeichnen zuletzt gesetzt)
 */
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

const ROOT = path.join(__dirname, '..', '..')
const DATA = path.join(__dirname, '.data')
/** Dateien werden gzip-gepackt geschrieben (gleicher Inhalt, ~ 70 % kleiner im APK). */
const gz = (file, buf) => fs.writeFileSync(file, zlib.gzipSync(buf, { level: 9 }))
const OUT = process.argv[2] || path.join(ROOT, 'app', 'src', 'main', 'assets', 'map')

// Abhängigkeiten aus tools/karte/.data (siehe hole-daten.sh)
process.env.NODE_PATH = path.join(DATA, 'node_modules')
require('module').Module._initPaths()
const Pbf = require(path.join(DATA, 'node_modules', 'pbf'))

fs.mkdirSync(OUT, { recursive: true })

// ------------------------------------------------------------------ Landflächen

const topo = JSON.parse(fs.readFileSync(path.join(DATA, 'world-atlas', 'countries-50m.json'), 'utf8'))
const { transform, arcs } = topo
const sx = transform.scale[0], sy = transform.scale[1]
const tx = transform.translate[0], ty = transform.translate[1]

/** TopoJSON-Bögen in absolute Koordinaten umrechnen. */
const absArcs = arcs.map(arc => {
  let x = 0, y = 0
  return arc.map(d => {
    x += d[0]; y += d[1]
    return [x * sx + tx, y * sy + ty]
  })
})

function ringFromArcIndexes(idxs) {
  const pts = []
  for (const i of idxs) {
    const arc = i < 0 ? absArcs[~i].slice().reverse() : absArcs[i]
    for (let k = pts.length ? 1 : 0; k < arc.length; k++) pts.push(arc[k])
  }
  return pts
}

const rawRings = []
for (const g of topo.objects.countries.geometries) {
  if (g.type === 'Polygon') {
    g.arcs.forEach(a => rawRings.push(ringFromArcIndexes(a)))
  } else if (g.type === 'MultiPolygon') {
    g.arcs.forEach(poly => poly.forEach(a => rawRings.push(ringFromArcIndexes(a))))
  }
}

function ringArea(pts) {
  let a = 0
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i], q = pts[(i + 1) % pts.length]
    a += p[0] * q[1] - q[0] * p[1]
  }
  return Math.abs(a / 2)
}

/** Punkte ausdünnen – bei Handy-Auflösung unsichtbar, spart über die Hälfte der Daten. */
function simplify(pts, minStep) {
  const out = [pts[0]]
  for (let i = 1; i < pts.length; i++) {
    const last = out[out.length - 1]
    if (Math.abs(pts[i][0] - last[0]) + Math.abs(pts[i][1] - last[1]) >= minStep) out.push(pts[i])
  }
  if (out.length > 2) {
    const a = out[0], b = out[out.length - 1]
    if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) < minStep) out.pop()
  }
  return out
}

/**
 * Ringe an der Datumsgrenze (±180°) teilen.
 *
 * Ohne das entstehen beim Zeichnen breite Streifen über die ganze Karte (Russland mit
 * Tschukotka, Grönland, Fidschi): ein Ring „springt“ dort von +180 auf −180 und wird
 * beim Füllen quer über den Ozean geschlossen. Beim Teilen wird zusätzlich ein Punkt
 * auf der Grenze eingefügt, beide Teilringe enden also genau dort.
 */
function splitAtAntimeridian(ring) {
  const jumps = []
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i], b = ring[(i + 1) % ring.length]
    if (Math.abs(b[0] - a[0]) > 180) jumps.push(i)
  }
  if (jumps.length === 0) return [ring]

  const start = (jumps[0] + 1) % ring.length
  const rot = ring.slice(start).concat(ring.slice(0, start))

  const out = []
  let cur = []
  for (let i = 0; i < rot.length; i++) {
    const a = rot[i], b = rot[(i + 1) % rot.length]
    let bAdj = b[0]
    let boundary
    if (b[0] - a[0] > 180) { bAdj -= 360; boundary = -180 }        // nach Westen über −180
    else if (b[0] - a[0] < -180) { bAdj += 360; boundary = 180 }   // nach Osten über +180
    if (boundary === undefined) { cur.push(a); continue }
    const t = (boundary - a[0]) / (bAdj - a[0])
    const lat = a[1] + (b[1] - a[1]) * t
    cur.push([boundary, lat])
    out.push(cur)
    cur = [[-boundary, lat]]
  }
  if (cur.length) out.push(cur)
  return out.filter(r => r.length >= 3)
}

const rings = []
for (const r of rawRings) {
  if (ringArea(r) < 0.02) continue                       // Inseln unter ~ 50 × 50 km
  if (Math.max(...r.map(p => p[1])) < -55) continue      // Antarktis: für Fotos nutzlos, nur Ballast
  const s = simplify(r, 0.02)                            // ~ 2 km Auflösung
  if (s.length < 4) continue
  for (const part of splitAtAntimeridian(s)) if (part.length >= 4) rings.push(part)
}

const wb = []
function wI32(v) { const b = Buffer.alloc(4); b.writeInt32LE(v | 0); wb.push(b) }
wI32(0x3157334e)  // 'N3W1'
wI32(rings.length)
for (const r of rings) {
  wI32(r.length)
  for (const [lon, lat] of r) {
    wI32(Math.round(lon * 1e5))
    wI32(Math.round(lat * 1e5))
  }
}
const worldBuf = Buffer.concat(wb)
gz(path.join(OUT, 'world.dat.gz'), worldBuf)

// ------------------------------------------------------------------ Ländernamen

const dic = require(path.join(DATA, 'node_modules', 'i18n-iso-countries'))
const natives = require(path.join(DATA, 'node_modules', 'countries-list')).countries
for (const lang of ['de', 'en', 'fr', 'it']) {
  try {
    dic.registerLocale(require(path.join(DATA, 'node_modules', 'i18n-iso-countries', 'langs', lang + '.json')))
  } catch (e) { /* Sprache fehlt – nicht schlimm */ }
}

function countryNames(cc) {
  const vals = []
  const push = v => { if (v && !vals.includes(v)) vals.push(v) }
  push(dic.getName(cc, 'de'))
  push(dic.getName(cc, 'en'))
  push(dic.getName(cc, 'fr'))
  push(dic.getName(cc, 'it'))
  const n = natives[cc]
  if (n) { push(n.native); push(n.name) }
  return vals.length ? vals : [cc]
}

// ------------------------------------------------------------------ Orte

const pbf = new Pbf(fs.readFileSync(path.join(DATA, 'cities.json', 'cities.pbf')))
let lastLat = 0, lastLon = 0
const places = []
const nameIndex = new Map()
const names = []

function nameIdx(s) {
  let i = nameIndex.get(s)
  if (i === undefined) { i = names.length; names.push(s); nameIndex.set(s, i) }
  return i
}

function grade(pop) {
  if (pop >= 1000000) return 6
  if (pop >= 500000) return 5
  if (pop >= 100000) return 4
  if (pop >= 50000) return 3
  if (pop >= 20000) return 2
  if (pop >= 5000) return 1
  return 0
}

while (pbf.pos < pbf.length) {
  const c = { pop: 0, lat: 0, lon: 0, name: '', country: '', fc: '' }
  pbf.readMessage((tag, _, p) => {
    if (tag === 2) c.name = p.readString()
    else if (tag === 3) c.country = p.readString()
    else if (tag === 7) c.fc = p.readString()
    else if (tag === 9) c.pop = p.readVarint()
    else if (tag === 10) { lastLon += p.readSVarint(); c.lon = lastLon / 1e5 }
    else if (tag === 11) { lastLat += p.readSVarint(); c.lat = lastLat / 1e5 }
  }, c)
  const pop = c.pop || 0
  if (pop < 5000) continue                 // kleinere Orte bringen keinen Mehrwert
  if (!c.name || !c.country) continue
  if (c.fc === 'PPLX') continue            // Stadtteile/Ortsgebiete
  places.push({ lon: c.lon, lat: c.lat, grade: grade(pop), name: c.name, cc: c.country })
}

const countries = [...new Set(places.map(p => p.cc))].sort()
const countryIdx = new Map(countries.map((cc, i) => [cc, i]))

const pb = []
function wVarint(v) {
  const bytes = []
  while (v >= 0x80) { bytes.push((v & 0x7f) | 0x80); v >>>= 7 }
  bytes.push(v)
  pb.push(Buffer.from(bytes))
}
function wString(s) {
  const b = Buffer.from(s, 'utf8')
  wVarint(b.length)
  pb.push(b)
}
function wI32b(v) { const b = Buffer.alloc(4); b.writeInt32LE(v | 0); pb.push(b) }

wI32b(0x3150334e)  // 'N3P1'
wI32b(countries.length)
for (const cc of countries) wString([cc, ...countryNames(cc)].join('\n'))

wI32b(places.length)
for (const p of places) {
  wI32b(Math.round(p.lon * 1e5))
  wI32b(Math.round(p.lat * 1e5))
  pb.push(Buffer.from([p.grade, countryIdx.get(p.cc) & 0xff]))
  const b = Buffer.alloc(2)
  b.writeUInt16LE(nameIdx(p.name))
  pb.push(b)
}
wI32b(names.length)
for (const n of names) wString(n)

const placesBuf = Buffer.concat(pb)
gz(path.join(OUT, 'places.dat.gz'), placesBuf)

const wGz = fs.statSync(path.join(OUT, 'world.dat.gz')).size
const pGz = fs.statSync(path.join(OUT, 'places.dat.gz')).size
console.log('world.dat.gz ', (wGz / 1024).toFixed(0) + ' kB  (' + rings.length + ' Ringe, ' +
  (worldBuf.length / 1024).toFixed(0) + ' kB roh)')
console.log('places.dat.gz', (pGz / 1024).toFixed(0) + ' kB  (' + places.length + ' Orte, ' +
  names.length + ' Namen, ' + countries.length + ' Länder, ' + (placesBuf.length / 1024).toFixed(0) + ' kB roh)')
