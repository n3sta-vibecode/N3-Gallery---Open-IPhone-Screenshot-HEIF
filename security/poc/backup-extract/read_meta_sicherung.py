#!/usr/bin/env python3
"""PoC [V11]: Notizen/Tags/Favoriten aus der MetaBackup-Datei im GETEILTEN Speicher.

N3 Gallery (MetaBackup.kt) schreibt ALLE Notizen, Tags und Favoriten als
Klartext-JSON in den geteilten Speicher, damit sie eine Neuinstallation
überleben:

  * API 29+ : Downloads/N3 Gallery/n3-sicherung.json  (über MediaStore)
  * API 26-28: Pictures/N3 Gallery/n3-sicherung.json  (getExternalStoragePublicDirectory)

Zweites Problem: Beim Start liest restoreIfEmpty() genau diese Datei und wendet
ihre Einträge an, wenn die App noch keine eigenen Notizen/Favoriten hat. Eine
fremde App mit Schreibzugriff auf den geteilten Speicher (API 26-28: ganz normal
mit WRITE_EXTERNAL_STORAGE) kann also eine präparierte n3-sicherung.json
PLATZIEREN, die beim nächsten Start importiert wird (Injektion). Die ungehärtete
Fassung übernahm dabei auch file://-URIs, die anschließend in die Lösch-/Schreib-
Kette (V1-V3) fließen.

Dieses Skript liest eine solche Datei und gibt die privaten Inhalte aus.

Aufruf:
    # von einem Gerät gezogen (API 26-28, z. B. per adb):
    python3 read_meta_sicherung.py n3-sicherung.json
    adb pull /sdcard/Pictures/N3\ Gallery/n3-sicherung.json
    adb pull /sdcard/Download/N3\ Gallery/n3-sicherung.json
"""
import json
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    try:
        data = json.load(open(path, encoding="utf-8"))
    except Exception as e:
        print(f"[!] konnte {path} nicht als JSON lesen: {e}")
        return 1

    print(f"[*] Datei           : {path}")
    print(f"[*] version         : {data.get('version')}")
    print(f"[*] app             : {data.get('app')}")
    ts = data.get("time")
    if isinstance(ts, (int, float)) and ts > 0:
        import datetime
        print(f"[*] Zeitstempel     : {datetime.datetime.utcfromtimestamp(ts/1000)} UTC")

    notes = data.get("notes", {}) or {}
    tags = data.get("tags", {}) or {}
    favs = data.get("favorites", []) or []

    print(f"\n=== {len(notes)} Notizen (KLARTEXT) ===")
    for uri, text in notes.items():
        print(f"  {uri}")
        print(f"    -> {text}")

    print(f"\n=== {len(tags)} Einträge mit Tags ===")
    for uri, arr in tags.items():
        print(f"  {uri}")
        print(f"    -> {', '.join(arr) if isinstance(arr, list) else arr}")

    print(f"\n=== {len(favs)} Favoriten ===")
    for uri in favs:
        print(f"  {uri}")

    print("\n[!] Befund: Notizen/Tags/Favoriten liegen unverschlüsselt im GETEILTEN")
    print("    Speicher und werden beim Start automatisch zurückimportiert.")
    print("    Gegenmaßnahme (gehärtete Fassung): restoreIfEmpty() filtert jede")
    print("    importierte URI durch UriGuard – file://, Netz-URIs und Traversal")
    print("    werden verworfen (siehe verify_fix.py, Abschnitt E).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
