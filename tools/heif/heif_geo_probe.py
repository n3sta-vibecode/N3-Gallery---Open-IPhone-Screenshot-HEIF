#!/usr/bin/env python3
"""
Prüft die HEIF-Leser der App gegen echte Apple-HEICs — ohne Android, ohne Gerät.

Warum: Auf dem Gerät stand bei iPhone-Fotos „kein Ort gespeichert“ und kein
Aufnahme-Sensor. Zwei mögliche Ursachen:

  1. Die Datei enthält wirklich keine EXIF-/GPS-Daten (z. B. Screenshot, WhatsApp-Bild).
  2. Der Leser kommt an die Daten nicht heran.

Dieses Werkzeug beantwortet (1) und (2) getrennt, indem es genau die Wege nachbaut,
die die App geht, und die Ergebnisse vergleicht:

  • EXIF-IFD über die HEIF-`meta`-Box (`iinf`/`iloc` → Item „Exif“) — so macht es
    `androidx.exifinterface.media.ExifInterface`
  • XMP (`exif:GPSLatitude`), wie `ExifRepository.xmpLatLong`
  • ISO 6709 (`+47.0506+008.3066/`), wie `ExifRepository.parseIso6709`
  • Orientierung (`irot`/`imir`) wie im neuen `HeifGeo.orientationDegrees`

Benutzung:
    python3 tools/heif/heif_geo_probe.py BILD.HEIC [mehr …]
    python3 tools/heif/heif_geo_probe.py --self-test

Das Skript ist absichtlich ohne Fremdpakete (nur Standardbibliothek), damit es in
GitHub Actions ohne Installation läuft.
"""
import os
import struct
import sys

# ---------------------------------------------------------------- ISO-BMFF lesen


class Reader:
    def __init__(self, data):
        self.d = data
        self.n = len(data)

    def u8(self, p):
        return self.d[p]

    def u16(self, p):
        return struct.unpack(">H", self.d[p:p + 2])[0]

    def u32(self, p):
        return struct.unpack(">I", self.d[p:p + 4])[0]

    def u64(self, p):
        return struct.unpack(">Q", self.d[p:p + 8])[0]


def boxes(data, start=0, end=None):
    """Liefert (typ, inhalt_start, inhalt_ende) für alle Boxen im Bereich."""
    end = len(data) if end is None else end
    p = start
    out = []
    while p + 8 <= end:
        size = struct.unpack(">I", data[p:p + 4])[0]
        typ = data[p + 4:p + 8].decode("latin-1")
        header = 8
        if size == 1:
            if p + 16 > end:
                break
            size = struct.unpack(">Q", data[p + 8:p + 16])[0]
            header = 16
        elif size == 0:
            size = end - p
        if size < header or p + size > end:
            break
        out.append((typ, p + header, p + size))
        p += size
    return out


def find(data, path, start=0, end=None):
    """Erste Box entlang eines Pfads, z. B. ['meta', 'iinf']."""
    cur_start, cur_end = start, (len(data) if end is None else end)
    found = None
    parent = None
    for name in path:
        found = None
        for typ, s, e in boxes(data, cur_start, cur_end):
            if typ == name:
                found = (s, e)
                break
        if found is None:
            return None
        cur_start, cur_end = found
        # `meta` ist eine FullBox: nach den 4 Byte version/flags beginnen erst die Kindboxen
        if name == "meta":
            cur_start += 4
        parent = name
    return found


# ---------------------------------------------------------------- EXIF im HEIF


def exif_tiff_from_heif(data):
    """
    Apple legt die EXIF-Daten als eigenes Item in den Container, Typ `Exif`.
    Der Inhalt ist: 4-Byte-Offset auf den TIFF-Header, dann die TIFF-Struktur
    (byte order + IFD). Genau dort stehen GPS und Kamera/Sensor.
    """
    meta = find(data, ["meta"])
    if not meta:
        return None
    mstart, mend = meta
    r = Reader(data)

    # ---- iinf: Item-IDs und Typen
    iinf = find(data, ["meta", "iinf"])
    items = {}
    if iinf:
        s, e = iinf
        version = data[s]
        p = s + 4
        if version == 0:
            count = r.u16(p)
            p += 2
        else:
            count = r.u32(p)
            p += 4
        for _ in range(count):
            if p + 8 > e:
                break
            size = r.u32(p)
            if size < 8 or p + size > e:
                break
            typ = data[p + 4:p + 8].decode("latin-1")
            # infe: version(1) flags(3) item_ID(2 bei v0/1, 4 ab v2) protection(2) type(4) name(…)
            # Der Item-Typ steht IN der infe-Box (bei p+16), nicht der Boxname.
            item_type = data[p + 16:p + 20].decode("latin-1")
            version_infe = data[p + 8]
            # Layout von infe: version(1)+flags(3)+item_ID(2)+protection(2)+type(4)
            # (Apple schreibt Item-IDs als 16-Bit, auch bei Version 2 – so liest es auch
            #  androidx.exifinterface. Deshalb hier ebenfalls 16 Bit.)
            item_id = r.u16(p + 12)
            items[item_id] = item_type
            p += size

    # ---- iloc: wo liegen die Daten
    iloc = find(data, ["meta", "iloc"])
    if not iloc:
        return None
    s, e = iloc
    version = data[s]
    p = s + 4
    # Achtung: `version` steht an s, danach 3 Byte flags – p zeigt also schon auf sizes.
    sizes = data[p]
    offset_size = (sizes >> 4) & 0x0F
    length_size = sizes & 0x0F
    sizes2 = data[p + 1]
    base_size = (sizes2 >> 4) & 0x0F
    index_size = sizes2 & 0x0F if version in (1, 2) else 0
    p += 2
    count = r.u16(p) if version < 2 else r.u32(p)
    p += 2 if version < 2 else 4

    def read_n(pos, n):
        v = 0
        for i in range(n):
            v = (v << 8) | data[pos + i]
        return v, pos + n

    extents = {}
    for _ in range(count):
        item_id = r.u16(p) if version < 2 else r.u32(p)
        p += 2 if version < 2 else 4
        construction = 0
        if version in (1, 2):
            construction = r.u16(p) & 0x0F
            p += 2
        p += 2  # data_reference_index
        base, p = read_n(p, base_size)
        n_ext = r.u16(p)
        p += 2
        lst = []
        for _ in range(n_ext):
            if index_size:
                _, p = read_n(p, index_size)
            off, p = read_n(p, offset_size)
            ln, p = read_n(p, length_size)
            lst.append((base + off, ln))
        extents[item_id] = lst

    if os.environ.get("HEIF_DEBUG"):
        print("DEBUG items:", items)
        print("DEBUG extents:", extents)

    # ---- Exif-Item finden und lesen
    for item_id, typ in items.items():
        if typ.lower() != "exif":
            continue
        for off, ln in extents.get(item_id, []):
            if ln <= 0 or off + ln > len(data):
                continue
            blob = data[off:off + ln]
            if len(blob) < 8:
                continue
            skip = struct.unpack(">I", blob[0:4])[0]
            start = 4 + skip
            tiff = blob[start:]
            if tiff[:2] not in (b"II", b"MM"):
                # Manche Programme schreiben die TIFF-Daten ohne Offset-Feld
                tiff = blob[4:] if blob[4:6] in (b"II", b"MM") else tiff
            if tiff[:2] in (b"II", b"MM"):
                return tiff
    return None


# ---------------------------------------------------------------- TIFF/EXIF auswerten


GPS_TAGS = {
    0x0000: "GPSVersionID", 0x0001: "GPSLatitudeRef", 0x0002: "GPSLatitude",
    0x0003: "GPSLongitudeRef", 0x0004: "GPSLongitude", 0x0005: "GPSAltitudeRef",
    0x0006: "GPSAltitude", 0x0007: "GPSTimeStamp", 0x001D: "GPSDateStamp",
    0x0011: "GPSImgDirection", 0x0010: "GPSImgDirectionRef",
}

EXIF_IFD_TAGS = {
    0x9003: "DateTimeOriginal", 0x829A: "ExposureTime", 0x829D: "FNumber",
    0x8827: "ISOSpeedRatings", 0x920A: "FocalLength", 0xA405: "FocalLengthIn35mmFilm",
    0xA002: "PixelXDimension", 0xA003: "PixelYDimension", 0xA433: "LensMake",
    0xA434: "LensModel", 0x927C: "MakerNote", 0xA001: "ColorSpace",
    0x9010: "OffsetTime", 0x9011: "OffsetTimeOriginal",
}

IFD0_TAGS = {
    0x010F: "Make", 0x0110: "Model", 0x0112: "Orientation", 0x0131: "Software",
    0x0132: "DateTime", 0x8769: "ExifIFD", 0x8825: "GPSIFD",
}

TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}


def read_ifd(tiff, offset, endian):
    """Liest ein IFD und liefert {tag: (typ, werte)} plus Zeiger auf Sub-IFDs."""
    def u16(p):
        return struct.unpack(endian + "H", tiff[p:p + 2])[0]

    def u32(p):
        return struct.unpack(endian + "I", tiff[p:p + 4])[0]

    if offset + 2 > len(tiff):
        return {}, {}
    count = u16(offset)
    entries = {}
    for i in range(count):
        p = offset + 2 + i * 12
        if p + 12 > len(tiff):
            break
        tag = u16(p)
        typ = u16(p + 2)
        n = u32(p + 4)
        size = TYPE_SIZE.get(typ, 1) * n
        raw = tiff[p + 8:p + 8 + size] if size <= 4 else tiff[u32(p + 8):u32(p + 8) + size]
        entries[tag] = (typ, n, raw)
    return entries, {}


def rationals(raw, endian, count=None):
    out = []
    for i in range(0, min(len(raw), 8 * (count or len(raw) // 8)), 8):
        num = struct.unpack(endian + "I", raw[i:i + 4])[0]
        den = struct.unpack(endian + "I", raw[i + 4:i + 8])[0]
        out.append(num / den if den else 0.0)
    return out


def text_of(raw):
    return raw.split(b"\x00")[0].decode("latin-1", "replace").strip()


def read_tiff(tiff):
    """Liest IFD0, Exif-IFD und GPS-IFD. Liefert die Tags, die die App anzeigt."""
    if endian_of(tiff) is None:
        return {"ifd0": {}, "exif": {}, "gps": {}}
    E = "<" if tiff[:2] == b"II" else ">"

    def u16(p):
        return struct.unpack(E + "H", tiff[p:p + 2])[0]

    def u32(p):
        return struct.unpack(E + "I", tiff[p:p + 4])[0]

    def entries_of(offset):
        if offset <= 0 or offset + 2 > len(tiff):
            return {}
        count = u16(offset)
        out = {}
        for i in range(count):
            p = offset + 2 + i * 12
            if p + 12 > len(tiff):
                break
            tag, typ, n = u16(p), u16(p + 2), u32(p + 4)
            size = TYPE_SIZE.get(typ, 1) * n
            if size <= 4:
                raw = tiff[p + 8:p + 8 + size]
            else:
                val_off = u32(p + 8)
                raw = tiff[val_off:val_off + size]
            out[tag] = (typ, n, raw)
        return out

    def value(typ, n, raw):
        if typ == 2:
            return text_of(raw)
        if typ == 5:
            vals = rationals(raw, E)
            return vals[0] if len(vals) == 1 else vals
        if typ == 3:
            return struct.unpack(E + "H", raw[:2])[0] if len(raw) >= 2 else None
        if typ == 1:
            return list(raw)
        if typ in (4, 9):
            return struct.unpack(E + "I", raw[:4])[0] if len(raw) >= 4 else None
        return None

    ifd0_off = u32(4)
    ifd0 = entries_of(ifd0_off)
    out = {"ifd0": {}, "exif": {}, "gps": {}}
    for tag, (typ, n, raw) in ifd0.items():
        name = IFD0_TAGS.get(tag)
        if name:
            out["ifd0"][name] = value(typ, n, raw)

    exif_ptr = ifd0.get(0x8769)
    if exif_ptr:
        for tag, (typ, n, raw) in entries_of(value(4, 1, exif_ptr[2])).items():
            name = EXIF_IFD_TAGS.get(tag)
            if name:
                out["exif"][name] = value(typ, n, raw)

    gps_ptr = ifd0.get(0x8825)
    if gps_ptr:
        for tag, (typ, n, raw) in entries_of(value(4, 1, gps_ptr[2])).items():
            name = GPS_TAGS.get(tag)
            if name:
                out["gps"][name] = value(typ, n, raw)
    return out


def endian_of(tiff):
    if len(tiff) < 8:
        return None
    if tiff[:2] not in (b"II", b"MM"):
        return None
    return "little" if tiff[:2] == b"II" else "big"


def _ifd_pointer(tiff, ifd_offset, tag, endian):
    count = struct.unpack(endian + "H", tiff[ifd_offset:ifd_offset + 2])[0]
    for i in range(count):
        p = ifd_offset + 2 + i * 12
        if struct.unpack(endian + "H", tiff[p:p + 2])[0] == tag:
            return struct.unpack(endian + "I", tiff[p + 8:p + 12])[0]
    return None


def dms_to_deg(values, ref):
    if not values or len(values) < 3:
        return None
    deg = values[0] + values[1] / 60.0 + values[2] / 3600.0
    if ref in ("S", "W"):
        deg = -deg
    return deg


# ---------------------------------------------------------------- XMP / ISO 6709

import re


def xmp_coordinate(text, key):
    m = re.search(key + r"[^0-9+\-]{0,24}([+\-]?\d{1,3}(?:[.,]\d+)?)[^0-9NSEWnsew]{0,4}([NSEWnsew])?", text)
    if not m:
        return None
    v = float(m.group(1).replace(",", "."))
    h = (m.group(2) or "").upper()
    if h in ("S", "W") or v < 0:
        v = -abs(v)
    return v if abs(v) <= 180 else None


def iso6709(text):
    m = re.search(r"([+\-]\d{2,3}[.,]\d{2,8})([+\-]\d{2,3}[.,]\d{2,8})", text)
    if not m:
        return None
    a = float(m.group(1).replace(",", "."))
    b = float(m.group(2).replace(",", "."))
    if abs(a) > 90 or abs(b) > 180:
        return None
    return a, b


# ---------------------------------------------------------------- Orientierung


def orientation_degrees(data):
    """irot (0/90/180/270) und imir aus iprp/ipco – so wie HeifGeo es liest."""
    ipco = find(data, ["meta", "iprp", "ipco"])
    if not ipco:
        return None, False
    s, e = ipco
    r = Reader(data)
    deg, mirrored = None, False
    p = s
    while p + 8 <= e:
        size = r.u32(p)
        typ = data[p + 4:p + 8].decode("latin-1")
        if size < 8 or p + size > e:
            break
        if typ == "irot":
            # 1 Byte: 2 Bit Winkel, gezählt in 90° gegen den Uhrzeigersinn
            deg = (data[p + 8] & 0x03) * 90
        elif typ == "imir":
            mirrored = True
        p += size
    return deg, mirrored


# ---------------------------------------------------------------- Auswertung


def probe(path):
    with open(path, "rb") as f:
        data = f.read()
    result = {"file": path, "size": len(data), "problems": []}

    top = [t for t, _, _ in boxes(data)]
    if "ftyp" not in top:
        result["problems"].append("Keine ftyp-Box – das ist keine HEIF/AVIF-Datei.")
        return result
    ftyp = find(data, ["ftyp"])
    s, e = ftyp
    result["major"] = data[s:s + 4].decode("latin-1")
    result["brands"] = [data[i:i + 4].decode("latin-1") for i in range(s + 8, e - 3, 4)]

    tiff = exif_tiff_from_heif(data)
    result["exif_item"] = tiff is not None
    gps_lat = gps_lon = None
    if tiff:
        t = read_tiff(tiff)
        result["make"] = t["ifd0"].get("Make")
        result["model"] = t["ifd0"].get("Model")
        result["taken"] = t["exif"].get("DateTimeOriginal")
        result["lens"] = t["exif"].get("LensModel")
        result["f_number"] = t["exif"].get("FNumber")
        result["iso"] = t["exif"].get("ISOSpeedRatings")
        result["focal35"] = t["exif"].get("FocalLengthIn35mmFilm")
        result["gps_tags"] = sorted(t["gps"].keys())
        gps_lat = dms_to_deg(t["gps"].get("GPSLatitude"), t["gps"].get("GPSLatitudeRef"))
        gps_lon = dms_to_deg(t["gps"].get("GPSLongitude"), t["gps"].get("GPSLongitudeRef"))
    else:
        result["problems"].append(
            "Kein Exif-Item im Container lesbar – dann kann auch die App keinen Sensor/GPS zeigen."
        )

    # XMP als Text im ganzen meta-Bereich suchen (so macht es die App über ExifInterface)
    meta = find(data, ["meta"])
    meta_text = data[meta[0]:meta[1]].decode("latin-1", "replace") if meta else ""
    xmp_lat = xmp_coordinate(meta_text, "GPSLatitude") if "GPSLatitude" in meta_text else None
    xmp_lon = xmp_coordinate(meta_text, "GPSLongitude") if "GPSLongitude" in meta_text else None
    iso = iso6709(data[: 256 * 1024].decode("latin-1", "replace"))

    result["gps_exif"] = (gps_lat, gps_lon) if gps_lat is not None else None
    result["gps_xmp"] = (xmp_lat, xmp_lon) if xmp_lat is not None and xmp_lon is not None else None
    result["gps_iso6709"] = iso
    result["orientation"], result["mirrored"] = orientation_degrees(data)

    if result["gps_exif"] is None and result["gps_xmp"] is None and result["gps_iso6709"] is None:
        if "IMG_" in str(result.get("brands")) or result.get("major", "").startswith("heic"):
            result["problems"].append(
                "Datei enthält keine GPS-Daten in keinem der drei Wege – "
                "vermutlich Screenshot oder Ortungsdienst war aus."
            )
    return result


def show(r):
    print("=" * 72)
    print(r["file"], f"({r['size']:,} Bytes)".replace(",", "."))
    if "major" in r:
        print(f"  Container : {r['major']} · {', '.join(r.get('brands', [])[:6])}")
        print(f"  Exif-Item : {'ja' if r['exif_item'] else 'NEIN'}")
        for k in ("make", "model", "lens", "taken", "f_number", "iso", "focal35"):
            if r.get(k) is not None:
                print(f"  {k:<10}: {r[k]}")
        print(f"  GPS (EXIF): {r['gps_exif']}")
        print(f"  GPS (XMP) : {r['gps_xmp']}")
        print(f"  GPS (6709): {r['gps_iso6709']}")
        print(f"  Drehung   : {r['orientation']}° gespiegelt={r['mirrored']}")
    for p in r["problems"]:
        print("  ⚠", p)
    print()


def self_test():
    """
    Baut im Speicher ein minimales HEIF mit Exif-Item, GPS und irot und prüft,
    dass der Leser genau die Werte findet, die die App danach anzeigt.
    """
    import json

    def build_heif(make="Apple", model="iPhone 15 Pro", lat=(47, 3, 2.3), lon=(8, 18, 23.9),
                   ref_lat="N", ref_lon="E", rotate=1, lens="iPhone 15 Pro back camera 6.765mm f/1.78",
                   fnumber=(178, 100), iso=64, focal35=24):
        """
        Baut ein minimales, aber strukturell korrektes Apple-HEIF im Speicher:
        ftyp + meta(iinf, iloc, iprp/ipco(irot)) + mdat(Bild, Exif-Item).

        Wichtig sind die **absoluten** Offsets: die GPS-Werte liegen hinter dem GPS-IFD,
        und alle Zeiger sind vom TIFF-Anfang gerechnet.
        """
        E = ">"

        def rat(pair):
            return struct.pack(E + "II", pair[0], pair[1])

        lat_raw = b"".join(rat((int(v * 1000), 1000)) for v in lat)
        lon_raw = b"".join(rat((int(v * 1000), 1000)) for v in lon)
        make_raw = make.encode() + b"\x00"
        model_raw = model.encode() + b"\x00"
        lens_raw = lens.encode() + b"\x00"

        # ---- Aufbau: TIFF-Kopf(8) · IFD0 · Exif-IFD · GPS-IFD · Datenblöcke
        gps_count = 4        # Ref Lat, Ref Lon, Lat, Lon
        exif_count = 6       # Belichtungszeit, Blende, ISO, Datum, KB-Brennweite, Objektiv
        ifd0_size = 2 + 4 * 12 + 4        # Make, Model, Exif-Zeiger, GPS-Zeiger
        exif_ifd_size = 2 + exif_count * 12 + 4
        gps_ifd_size = 2 + gps_count * 12 + 4

        ifd0_off = 8
        exif_off = ifd0_off + ifd0_size
        gps_off = exif_off + exif_ifd_size
        data_off = gps_off + gps_ifd_size

        lat_off = data_off
        lon_off = lat_off + len(lat_raw)
        make_off = lon_off + len(lon_raw)
        model_off = make_off + len(make_raw)
        lens_off = model_off + len(model_raw)
        date_raw = b"2026:07:14 18:22:31\x00"
        date_off = lens_off + len(lens_raw)
        # RATIONAL-Werte (8 Byte) passen nicht in einen Eintrag -> eigener Datenblock
        exposure_raw = struct.pack(E + "II", 1, 120)
        exposure_off = date_off + len(date_raw)
        fnumber_raw = rat(fnumber)
        fnumber_off = exposure_off + len(exposure_raw)

        ifd0 = struct.pack(E + "H", 4)
        ifd0 += struct.pack(E + "HHI", 0x010F, 2, len(make_raw)) + struct.pack(E + "I", make_off)
        ifd0 += struct.pack(E + "HHI", 0x0110, 2, len(model_raw)) + struct.pack(E + "I", model_off)
        ifd0 += struct.pack(E + "HHI", 0x8769, 4, 1) + struct.pack(E + "I", exif_off)
        ifd0 += struct.pack(E + "HHI", 0x8825, 4, 1) + struct.pack(E + "I", gps_off)
        ifd0 += struct.pack(E + "I", 0)
        assert len(ifd0) == ifd0_size

        # ---- Exif-IFD (aufsteigend nach Tag)
        exif_ifd = struct.pack(E + "H", exif_count)
        exif_ifd += struct.pack(E + "HHI", 0x829A, 5, 1) + struct.pack(E + "I", exposure_off)  # 1/120 s
        exif_ifd += struct.pack(E + "HHI", 0x829D, 5, 1) + struct.pack(E + "I", fnumber_off)   # f/1.78
        exif_ifd += struct.pack(E + "HHI", 0x8827, 3, 1) + struct.pack(E + "HH", iso, 0)     # ISO
        exif_ifd += struct.pack(E + "HHI", 0x9003, 2, len(date_raw)) + struct.pack(E + "I", date_off)
        # Kleinbild-Äquivalent: 24 mm als SHORT, direkt im Eintrag
        exif_ifd += struct.pack(E + "HHI", 0xA405, 3, 1) + struct.pack(E + "HH", focal35, 0)
        exif_ifd += struct.pack(E + "HHI", 0xA434, 2, len(lens_raw)) + struct.pack(E + "I", lens_off)
        exif_ifd += struct.pack(E + "I", 0)

        # ---- GPS-IFD
        gps_ifd = struct.pack(E + "H", gps_count)
        gps_ifd += struct.pack(E + "HHI", 0x0001, 2, 2) + (b"N\x00" if ref_lat == "N" else b"S\x00").ljust(4, b"\x00")
        gps_ifd += struct.pack(E + "HHI", 0x0002, 5, 3) + struct.pack(E + "I", lat_off)
        gps_ifd += struct.pack(E + "HHI", 0x0003, 2, 2) + (b"E\x00" if ref_lon == "E" else b"W\x00").ljust(4, b"\x00")
        gps_ifd += struct.pack(E + "HHI", 0x0004, 5, 3) + struct.pack(E + "I", lon_off)
        gps_ifd += struct.pack(E + "I", 0)

        tiff = (b"MM" + struct.pack(E + "H", 42) + struct.pack(E + "I", ifd0_off)
                + ifd0 + exif_ifd + gps_ifd
                + lat_raw + lon_raw + make_raw + model_raw + lens_raw + date_raw
                + exposure_raw + fnumber_raw)
        # Der Datenblock muss genau dort beginnen, wo die Zeiger hinzeigen
        assert len(ifd0) == ifd0_size, (len(ifd0), ifd0_size)
        assert len(exif_ifd) == exif_ifd_size, ("Exif-IFD", len(exif_ifd), exif_ifd_size, exif_count)
        assert len(gps_ifd) == gps_ifd_size, (len(gps_ifd), gps_ifd_size)
        assert len(tiff) == fnumber_off + len(fnumber_raw), (
            "TIFF-Länge", len(tiff), fnumber_off + len(fnumber_raw))

        exif_payload = struct.pack(">I", 0) + tiff  # 4-Byte-Offset + TIFF

        def box(typ, payload):
            return struct.pack(">I", 8 + len(payload)) + typ.encode() + payload

        ftyp = box("ftyp", b"heic" + struct.pack(">I", 0) + b"mif1heic")
        infe1 = box("infe", bytes([2, 0, 0, 0]) + struct.pack(">H", 1) + struct.pack(">H", 0)
                    + b"hvc1" + b"Bild\x00")
        infe2 = box("infe", bytes([2, 0, 0, 0]) + struct.pack(">H", 2) + struct.pack(">H", 0)
                    + b"Exif" + b"EXIF\x00")
        iinf = box("iinf", bytes([0, 0, 0, 0]) + struct.pack(">H", 2) + infe1 + infe2)

        def build_iloc(exif_off, exif_len, img_off, img_len):
            body = bytes([0, 0, 0, 0]) + bytes([0x44, 0x00]) + struct.pack(">H", 2)
            body += struct.pack(">HHH", 1, 0, 1) + struct.pack(">I", img_off) + struct.pack(">I", img_len)
            body += struct.pack(">HHH", 2, 0, 1) + struct.pack(">I", exif_off) + struct.pack(">I", exif_len)
            return box("iloc", body)

        irot = struct.pack(">I", 9) + b"irot" + bytes([rotate])
        iprp = box("iprp", box("ipco", irot))

        dummy_img = b"\x00" * 64
        probe_meta = box("meta", bytes([0, 0, 0, 0]) + iinf + build_iloc(0, 0, 0, 0) + iprp)
        img_off = len(ftyp) + len(probe_meta) + 8      # + mdat-Boxkopf
        exif_off = img_off + len(dummy_img)
        meta = box("meta", bytes([0, 0, 0, 0]) + iinf
                   + build_iloc(exif_off, len(exif_payload), img_off, len(dummy_img)) + iprp)
        mdat = box("mdat", dummy_img + exif_payload)
        return ftyp + meta + mdat

    ok = True

    def check(name, got, want, tol=0.0):
        nonlocal ok
        if got is None or want is None:
            good = got == want
        elif isinstance(want, float):
            good = abs(got - want) <= tol
        else:
            good = got == want
        print(("  OK   " if good else "  FAIL ") + f"{name}: {got!r} (erwartet {want!r})")
        ok = ok and good

    print("Selbsttest: gebautes Apple-HEIF mit GPS, Sensor und Drehung")
    blob = build_heif()
    tiff = exif_tiff_from_heif(blob)
    print("  Exif-Item gefunden:", tiff is not None)
    if tiff is None:
        return False
    t = read_tiff(tiff)
    check("Make", t["ifd0"].get("Make"), "Apple")
    check("Model", t["ifd0"].get("Model"), "iPhone 15 Pro")
    lat = dms_to_deg(t["gps"].get("GPSLatitude"), t["gps"].get("GPSLatitudeRef"))
    lon = dms_to_deg(t["gps"].get("GPSLongitude"), t["gps"].get("GPSLongitudeRef"))
    check("Breitengrad", lat, 47 + 3 / 60 + 2.3 / 3600, tol=1e-4)
    check("Längengrad", lon, 8 + 18 / 60 + 23.9 / 3600, tol=1e-4)
    deg, mir = orientation_degrees(blob)
    check("Drehung", deg, 90)
    check("gespiegelt", mir, False)

    print("\nSelbsttest: Südhalbkugel/Westen")
    blob2 = build_heif(ref_lat="S", ref_lon="W", lat=(33, 52, 7.8), lon=(151, 12, 33.4))
    t2 = read_tiff(exif_tiff_from_heif(blob2))
    check("Breitengrad S", round(dms_to_deg(t2["gps"].get("GPSLatitude"),
                                             t2["gps"].get("GPSLatitudeRef")), 4), -33.8688, tol=1e-3)
    check("Längengrad W", round(dms_to_deg(t2["gps"].get("GPSLongitude"),
                                           t2["gps"].get("GPSLongitudeRef")), 4), -151.2093, tol=1e-3)

    print("\nSelbsttest: kaputte Eingaben")
    check("kein HEIF", exif_tiff_from_heif(b"GIF89a...."), None)
    check("leer", exif_tiff_from_heif(b""), None)
    check("XMP ohne Ort", xmp_coordinate("nichts", "GPSLatitude"), None)
    check("ISO6709 Müll", iso6709("keine koordinaten"), None)

    print("\nErgebnis:", "alles bestanden" if ok else "FEHLER GEFUNDEN")
    return ok


def main(argv):
    if len(argv) < 2 or argv[1] == "--self-test":
        return 0 if self_test() else 1
    worst = 0
    for path in argv[1:]:
        try:
            r = probe(path)
        except Exception as exc:  # noqa: BLE001
            print(path, "konnte nicht gelesen werden:", exc)
            worst = 1
            continue
        show(r)
        if r["problems"]:
            worst = 1
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv))
