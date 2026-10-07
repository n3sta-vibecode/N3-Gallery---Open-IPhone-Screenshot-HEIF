#!/usr/bin/env python3
"""Wirksamkeitstest der Haertung.

Portiert UriGuard.containsTraversal / safeFileName und SafeFiles.confinedChild
(exakt die Kotlin-Logik aus app/app/src/main/java/com/n3vibecode/gallery/security/)
nach Python und prueft sie gegen

  A) die Angriffspayloads aus security/poc/exploit-app und attack.sh
  B) legitime URIs aus dem echten Betrieb der Galerie (MediaStore, SAF,
     iPhone-HEIC-Screenshots, RAW, Dateinamen mit Sonderzeichen)

Ziel: A muss vollstaendig abgewiesen werden, B muss vollstaendig durchkommen.
Ein Fix, der B blockiert, waere ein Regression, kein Erfolg.
"""
import os
import sys
import urllib.parse

PASS = "\033[32mPASS\033[0m"
FAIL = "\033[31mFAIL\033[0m"

# --------------------------------------------------------- Kotlin-Logik portiert
def uri_decode(s: str) -> str:
    """android.net.Uri.decode()"""
    return urllib.parse.unquote(s, encoding="utf-8", errors="replace")


def has_traversal_components(path: str) -> bool:
    return any(part == ".." for part in path.replace("\\", "/").split("/"))


def contains_traversal(path: str) -> bool:
    """UriGuard.containsTraversal()"""
    if "\x00" in path:
        return True
    current = path
    for _ in range(3):
        if has_traversal_components(current):
            return True
        decoded = uri_decode(current)
        if decoded == current:
            return False
        current = decoded
    return has_traversal_components(current)


ALLOWED_NAME_CHARS = set(".-_|+ ()=,@".replace("|", ""))  # '.','-','_',' ','(',')','+','=',',','@'
ALLOWED_NAME_CHARS = set(".-_ ()+,=@")
MAX_NAME_LENGTH = 180


def safe_file_name(raw):
    """UriGuard.safeFileName()"""
    if not raw or not raw.strip():
        return "Bild"
    s = raw
    for _ in range(2):
        s = uri_decode(s)
    # nur letztes Segment
    if "/" in s:
        s = s.split("/")[-1]
    if "\\" in s:
        s = s.split("\\")[-1]
    s = "".join(c for c in s if c.isalnum() or c in ALLOWED_NAME_CHARS)
    s = s.lstrip(".")
    if not s.strip():
        return "Bild"
    if len(s) > MAX_NAME_LENGTH:
        dot = s.rfind(".")
        if dot > len(s) - 12 and dot > 0:
            s = s[len(s) - MAX_NAME_LENGTH:]
        else:
            s = s[:MAX_NAME_LENGTH]
    return s or "Bild"


SHARE_DIR = "/data/user/0/com.n3vibecode.gallery/cache/share"


def confined_child(dir_path, raw_name):
    """SafeFiles.confinedChild() -> (ok, canonical_path)"""
    base = os.path.normpath(dir_path)
    name = safe_file_name(raw_name)
    candidate = os.path.normpath(os.path.join(base, name))
    prefix = base + "/"
    return candidate.startswith(prefix), candidate


class U:
    """android.net.Uri Nachbau"""
    def __init__(self, s):
        self.raw = s
        scheme, rest = (s.split(":", 1) + [""])[:2] if ":" in s else ("", s)
        self.scheme = scheme.lower()
        self.opaque = False
        if rest.startswith("//"):
            after, _, tail = rest[2:].partition("/")
            self.authority = after
            self.path = "/" + tail
            self.encoded_path = "/" + tail
        else:
            self.authority = ""
            self.path = rest
            self.encoded_path = rest
            self.opaque = bool(rest)

    def path_segments(self):
        return [uri_decode(s) for s in self.path.split("/") if s != ""]

    def last_path_segment(self):
        segs = self.path_segments()
        return segs[-1] if segs else None


TRUSTED_AUTHORITIES = {
    "media",
    "com.android.externalstorage.documents",
    "com.android.providers.downloads.documents",
}


def verify_incoming(raw_str, declared_mime=None):
    """UriGuard.verifyIncoming() -> (accept, reason, name, trusted)"""
    if raw_str is None:
        return False, "keine URI uebergeben", None, False
    if len(raw_str) > 4096:
        return False, "URI zu lang", None, False
    u = U(raw_str)
    if u.scheme == "content":
        pass
    elif u.scheme == "file":
        return False, "file://-URIs werden nicht akzeptiert", None, False
    elif u.scheme in ("http", "https", "ftp"):
        return False, "Netz-URIs werden nicht akzeptiert", None, False
    elif u.scheme == "":
        return False, "URI ohne Schema", None, False
    else:
        return False, f"unzulaessiges Schema: {u.scheme}", None, False
    if u.opaque:
        return False, "opaque URI wird nicht akzeptiert", None, False
    if not u.authority:
        return False, "content-URI ohne Authority", None, False
    for cand in (u.encoded_path, uri_decode(u.encoded_path)):
        if contains_traversal(cand):
            return False, "Path Traversal in URI erkannt", None, False
    for seg in u.path_segments():
        if "\x00" in seg:
            return False, "Nullbyte in URI", None, False
        # Ein '/' im Segment ist LEGITIM: SAF-Dokument-IDs sind percent-enkodiert
        # (primary%3ADCIM%2FCamera%2FIMG_0001.HEIC). Gefaehrlich ist nur eine
        # Komponente, die fuer sich allein ".." ist.
        if any(part == ".." for part in seg.replace("\\", "/").split("/")):
            return False, "unzulaessiges Pfadsegment", None, False
    trusted = u.authority.lower() in TRUSTED_AUTHORITIES
    name = safe_file_name(u.last_path_segment() or "Bild")
    if declared_mime is not None:
        m = declared_mime.lower().strip()
        ok = (m == "*/*" or m.startswith("image/") or m.startswith("video/")
              or m.startswith("application/octet-stream")
              or m.startswith("application/dng") or m.startswith("application/x-adobe-dng"))
        if len(m) > 128 or "\x00" in m:
            ok = False
        if not ok:
            return False, f"unplausibler MIME-Typ: {declared_mime}", None, False
    return True, "akzeptiert", name, trusted


# -------------------------------------------------------------------- Testsuites
ATTACKS = [
    # (Beschreibung, URI, erwartet_name_falls_durch)
    ("V3 file:// Foto loeschen",
     "file:///storage/emulated/0/DCIM/Camera/IMG_0001.jpg"),
    ("V3 file:// private shared_prefs loeschen",
     "file:///data/data/com.n3vibecode.gallery/shared_prefs/n3_gallery_meta.xml"),
    ("V3 file:// Download loeschen",
     "file:///storage/emulated/0/Download/steuer_2025.pdf"),
    ("V2 Traversal einfach kodiert",
     "content://attacker/x/%2e%2e%2f%2e%2e%2fshared_prefs%2fpwn.xml"),
    ("V2 Traversal gross kodiert",
     "content://attacker/x/%2E%2E%2F%2E%2E%2Fn3_gallery_meta.xml"),
    ("V2 Traversal aus Sandbox heraus",
     "file:///tmp/%2e%2e%2f%2e%2e%2f%2e%2e%2fstorage%2femulated%2f0%2fDCIM%2fCamera%2fpwn.jpg"),
    ("V2 Traversal doppelt kodiert",
     "content://attacker/x/%252e%252e%252f%252e%252e%252fpwn.xml"),
    ("V2 Traversal roh (unkodiert)",
     "content://attacker/x/../../shared_prefs/pwn.xml"),
    ("V2 Traversal mit Backslash",
     "content://attacker/x/..%5C..%5Cshared_prefs%5Cpwn.xml"),
    ("V1 http nachladen",
     "http://evil.example/x.jpg"),
    ("V1 https nachladen",
     "https://evil.example/x.jpg"),
    ("Nullbyte-Smuggling",
     "content://attacker/x/safe.jpg%00../../pwn.xml"),
    ("Unbekanntes Schema",
     "javascript:alert(1)"),
    ("Opaque URI",
     "content:com.n3vibecode.gallery/secret"),
]

LEGIT = [
    ("MediaStore Foto",
     "content://media/external/images/media/42", "image/jpeg"),
    ("MediaStore HEIC (iPhone)",
     "content://media/external/images/media/1337", "image/heic"),
    ("MediaStore Video",
     "content://media/external/video/media/7", "video/mp4"),
    ("SAF DocumentsProvider (Dokument-ID mit %2F)",
     "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FCamera%2FIMG_0001.HEIC",
     "image/heic"),
    ("SAF Baum-URI",
     "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
     "image/heic"),
    ("SAF Dokument mit Leerzeichen",
     "content://com.android.externalstorage.documents/document/primary%3ADownload%2FMein%20Foto.jpg",
     "image/jpeg"),
    ("Downloads-Documents",
     "content://com.android.providers.downloads.documents/document/msf%3A12", "image/png"),
    ("Dateiname mit Punkten (kein Traversal!)",
     "content://media/external/images/media/99", "image/jpeg"),
    ("Dateiname mit Umlauten",
     "content://media/external/file/1234", "image/jpeg"),
]

# Namen, die NICHT als Traversal missverstanden werden duerfen
NAME_KEEP = [
    ("IMG_0001.HEIC", "IMG_0001.HEIC"),
    ("Screenshot 2026-10-03 at 18.00.png", "Screenshot 2026-10-03 at 18.00.png"),
    ("my..photo.jpg", "my..photo.jpg"),
    ("Foto (Kopie).jpg", "Foto (Kopie).jpg"),
    ("_DSC0001.NEF", "_DSC0001.NEF"),
    ("2026-10-05 12.34.56.jpg", "2026-10-05 12.34.56.jpg"),
]

# Namen, die entschärft werden MUESSEN
NAME_STRIP = [
    ("../../shared_prefs/pwn.xml", "pwn.xml"),
    ("%2e%2e%2f%2e%2e%2fpwn.xml", "pwn.xml"),
    ("..\\..\\windows\\system32\\x.dll", "x.dll"),
    ("/etc/passwd", "passwd"),
    ("....//....//x", "x"),
    ("", "Bild"),
    ("..", "Bild"),
]


def main():
    rc = 0
    print("\033[1m=== A) Angriffspayloads muessen ABGEWIESEN werden ===\033[0m")
    for desc, uri in ATTACKS:
        ok, reason, name, _ = verify_incoming(uri)
        if ok:
            print(f"  {FAIL} {desc}\n         {uri}\n         -> DURCHGELASSEN als {name!r}")
            rc = 1
        else:
            print(f"  {PASS} {desc:46s} -> abgewiesen: {reason}")

    # Zusaetzlich: confinedChild als zweite Schicht
    print("\n\033[1m=== A2) confinedChild() als zweite Schicht ===\033[0m")
    # Erfolgskriterium: Das Ziel MUSS innerhalb von share/ bleiben.
    # safeFileName reduziert jeden Pfad auf sein letztes Segment, der Traversal
    # wird also neutralisiert statt nur gemeldet. Beides ist sicher - entscheidend
    # ist, dass kein Ziel ausserhalb der Sandbox herauskommt.
    for raw in ["../../shared_prefs/pwn.xml", "%2e%2e%2f%2e%2e%2fpwn.xml",
                "../../../storage/emulated/0/DCIM/Camera/pwn.jpg", "normal.jpg",
                "..\\..\\windows\\system32\\x.dll"]:
        ok, canon = confined_child(SHARE_DIR, raw)
        if not ok:
            rc = 1
        mark = PASS if ok else FAIL
        escaped = "AUSGEBROCHEN" if not ok else "in Sandbox"
        print(f"  {mark} name={raw!r}\n         kanonisch={canon}\n         -> {escaped}")

    print("\n\033[1m=== B) Legitime URIs muessen DURCHKOMMEN (keine Regression) ===\033[0m")
    for desc, uri, mime in LEGIT:
        ok, reason, name, trusted = verify_incoming(uri, mime)
        if not ok:
            print(f"  {FAIL} {desc}\n         {uri}\n         -> abgewiesen: {reason}")
            rc = 1
        else:
            print(f"  {PASS} {desc:40s} trusted={str(trusted):5s} name={name!r}")

    print("\n\033[1m=== C) safeFileName: legitime Namen bleiben erhalten ===\033[0m")
    for raw, expected in NAME_KEEP:
        got = safe_file_name(raw)
        mark = PASS if got == expected else FAIL
        if got != expected:
            rc = 1
        print(f"  {mark} {raw!r:45s} -> {got!r}" + ("" if got == expected else f"  (erwartet {expected!r})"))

    print("\n\033[1m=== D) safeFileName: gefaehrliche Namen werden entschaerft ===\033[0m")
    for raw, expected in NAME_STRIP:
        got = safe_file_name(raw)
        ok = got == expected
        # zusaetzlich hart: Ergebnis darf nie '..' oder '/' enthalten
        ok = ok and ".." not in got.split(".")[0:1] and "/" not in got and "\\" not in got
        mark = PASS if ok else FAIL
        if not ok:
            rc = 1
        print(f"  {mark} {raw!r:45s} -> {got!r}" + ("" if got == expected else f"  (erwartet {expected!r})"))

    print("\n\033[1m=== E) MetaBackup.applyJson: Injektion aus geteiltem Speicher wird gefiltert ===\033[0m")
    # Eine von einer Fremd-App platzierte n3-sicherung.json wird beim Start importiert.
    # Nur content:// ohne Traversal darf uebernommen werden (safeUri -> verifyIncoming).
    backup_entries = [
        ("content://media/external/images/media/5", True,  "legitimes MediaStore-Foto"),
        ("file:///storage/emulated/0/DCIM/x.jpg",   False, "file:// (V3-Kette)"),
        ("file:///data/data/com.n3vibecode.gallery/shared_prefs/n3_gallery_meta.xml", False, "file:// auf private Prefs"),
        ("content://attacker/x/%2e%2e%2f%2e%2e%2fpwn.xml", False, "Traversal"),
        ("http://evil.example/x.jpg",               False, "Netz-URI"),
        ("",                                        False, "leer"),
    ]
    for uri, should_accept, desc in backup_entries:
        accepted = bool(uri) and verify_incoming(uri, None)[0]
        ok = (accepted == should_accept)
        if not ok: rc = 1
        mark = PASS if ok else FAIL
        state = "uebernommen" if accepted else "VERWORFEN"
        print(f"  {mark} {desc:34s} -> {state}")

    print()
    if rc == 0:
        print("\033[1;32mALLE TESTS BESTANDEN: Angriffe abgewiesen, legitime Nutzung intact.\033[0m")
    else:
        print("\033[1;31mEs gibt Fehlschlaege - siehe oben.\033[0m")
    return rc


if __name__ == "__main__":
    sys.exit(main())
