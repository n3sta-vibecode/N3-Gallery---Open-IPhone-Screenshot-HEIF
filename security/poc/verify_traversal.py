#!/usr/bin/env python3
"""Verifikation von [V1]/[V2]/[V3] OHNE Geraet.

Portiert die exakte Semantik von android.net.Uri (AbstractWiki/Uri.java:
decode(), getPathSegments(), getLastPathSegment()) und java.io.File
(String-Konstruktor + canonicalPath) nach Python und wendet sie auf die
echten Code-Pfade von N3 Gallery an.

Belegte Quellstellen (Stand 1.21):
  MainActivity.handleViewIntent()      name = uri.lastPathSegment ?: "Bild"
  DetailActivity.shareCurrent()        File(GalleryApp.shareDir(), item.name)
  InfoActivity.shareFile()             File(GalleryApp.shareDir(), current.name)
  DetailActivity.directDelete()        File(uri.path ?: return).delete()
  GalleryApp.shareDir()                File(instance.cacheDir, "share")
"""
import os
import urllib.parse

OK = "\033[32m[OK]\033[0m"
BAD = "\033[31m[!!]\033[0m"


# ------------------------------------------------------- android.net.Uri Semantik
class AndroidUri:
    """Nachbau von android.net.Uri fuer hierarchische URIs."""

    def __init__(self, s: str):
        self.raw = s
        scheme, rest = (s.split(":", 1) + [""])[:2] if ":" in s else ("", s)
        self.scheme = scheme.lower()
        if rest.startswith("//"):
            after, _, tail = rest[2:].partition("/")
            self.authority = after
            self.path = "/" + tail
        else:
            self.authority = ""
            self.path = rest

    @staticmethod
    def decode(s: str) -> str:
        """Uri.decode(): Prozent-Kodierung wird aufgeloest."""
        return urllib.parse.unquote(s, encoding="utf-8", errors="replace")

    def path_segments(self):
        """Uri.getPathSegments(): leere Segmente werden uebersprungen, DEKODIERT."""
        out = []
        for seg in self.path.split("/"):
            if seg == "":
                continue
            out.append(self.decode(seg))
        return out

    def last_path_segment(self):
        segs = self.path_segments()
        return segs[-1] if segs else None


# ------------------------------------------------------- java.io.File Semantik
class JFile:
    def __init__(self, *parts):
        # File(File parent, String child): Kind wird angehaengt, KEINE Normalisierung
        joined = ""
        for p in parts:
            p = str(p)
            if joined and not joined.endswith("/"):
                joined += "/"
            joined += p.lstrip("/") if joined else p
        self.path = joined

    def canonical(self) -> str:
        return os.path.normpath(self.path)

    def __repr__(self):
        return f"File({self.path!r})"


SHARE_DIR = "/data/user/0/com.n3vibecode.gallery/cache/share"
APP_DATA = "/data/user/0/com.n3vibecode.gallery"


def headline(t):
    print(f"\n\033[1m=== {t} ===\033[0m")


# ------------------------------------------------------------------ [V1] Eingang
headline("V1 - MainActivity.handleViewIntent() nimmt URIs unvalidiert an")
tests = [
    "content://media/external/images/media/42",
    "file:///storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
    "file:///data/data/com.n3vibecode.gallery/shared_prefs/n3_gallery_meta.xml",
    "http://evil.example/x.jpg",
    "content://poc.n3gallery.exploit.payload/attack/%2e%2e%2f%2e%2e%2fshared_prefs%2fpwn.xml",
]
for t in tests:
    u = AndroidUri(t)
    name = u.last_path_segment() or "Bild"
    accepted = "AKZEPTIERT"  # handleViewIntent hat keinerlei Filter
    flag = BAD if u.scheme in ("file", "http", "https") or ".." in name else OK
    print(f"  {flag} scheme={u.scheme!r:10} authority={u.authority!r:38} -> name={name!r}  [{accepted}]")
print("""
  Befund: Es gibt KEINEN Check auf scheme, authority, Traversal oder Mime.
  Jedes dieser Konstrukte wird zu einem MediaItem und in DetailActivity geoeffnet.""")

# ---------------------------------------------------- [V2] Traversal beim Schreiben
headline("V2 - DetailActivity.shareCurrent(): File(shareDir(), item.name)")
traversal_uris = [
    "content://attacker/x/%2e%2e%2f%2e%2e%2fshared_prefs%2fpwn.xml",
    "content://attacker/x/%2E%2E%2F%2E%2E%2Fn3_gallery_meta.xml",
    "file:///tmp/%2e%2e%2f%2e%2e%2f%2e%2e%2fstorage%2femulated%2f0%2fDCIM%2fCamera%2fpwn.jpg",
    "content://attacker/normal.jpg",
]
for t in traversal_uris:
    u = AndroidUri(t)
    name = u.last_path_segment() or "Bild"
    f = JFile(SHARE_DIR, name)
    canon = f.canonical()
    inside = canon.startswith(SHARE_DIR + "/")
    mark = OK if inside else BAD
    print(f"  {mark} name={name!r}")
    print(f"        File(...) = {f.path}")
    print(f"        kanonisch = {canon}")
    if not inside:
        if canon.startswith(APP_DATA):
            print("        => SCHREIBT INS PRIVATE DATENVERZEICHNIS der Galerie!")
        else:
            print("        => SCHREIBT AUS DEM APP-SANDBOX HERAUS (auf API<=28 mit")
            print("           WRITE_EXTERNAL_STORAGE auch nach /storage/emulated/0)!")

print("""
  Kernmechanik: Uri.getLastPathSegment() DEKODIERT Prozent-Kodierung.
  '%2e%2e%2f' wird zu '../'. File(parent, child) normalisiert nicht.
  Der Inhalt kommt aus contentResolver.openInputStream(uri) -> Angreifer-kontrolliert.""")

# --------------------------------------------------------- [V3] willkuerliches Loeschen
headline("V3 - DetailActivity.directDelete(): File(uri.path).delete()")
delete_uris = [
    "file:///storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
    "file:///storage/emulated/0/Download/steuer_2025.pdf",
    "file:///data/data/com.n3vibecode.gallery/shared_prefs/n3_gallery_meta.xml",
    "file:///data/data/com.n3vibecode.gallery/databases/whatever.db",
]
for t in delete_uris:
    u = AndroidUri(t)
    # deleteCurrent(): target = uri, weil resolved.uri beginnt nicht mit "content://"
    # directDelete(): scheme != "content" -> File(uri.path).delete()
    goes_through_mediastore = u.scheme == "content"
    f = JFile(u.path)
    if goes_through_mediastore:
        print(f"  {OK} {u.path} -> MediaStore.createDeleteRequest (Systemdialog)")
    else:
        print(f"  {BAD} {u.path}")
        print(f"        -> directDelete() -> File({f.path!r}).delete()  KEIN Systemdialog")
        print(f"        -> Dialog zeigt dem Opfer nur den Dateinamen: {u.last_path_segment()!r}")

print("""
  Befund: Fuer jedes Schema ausser 'content' wird direkt ueber java.io.File geloescht.
  Auf API 26-29 hat die App WRITE_EXTERNAL_STORAGE und darf im geteilten Speicher
  loeschen. Auf API>=29 scheitert das nur an Scoped Storage - die app-EIGENEN
  Dateien (shared_prefs, databases, cache) bleiben aber immer loeschbar.""")

# --------------------------------------------------------- Bewertung der Gegenprobe
headline("Gegenprobe - was waere korrekt?")
print(f"""  {OK} Nur scheme == 'content' zulassen
  {OK} Nur Authority 'media' (MediaStore) bzw. per SAF erteilte DocumentsProvider
  {OK} lastPathSegment NIE als Dateinamen verwenden; stattdessen
      OpenableColumns.DISPLAY_NAME via Query UND anschliessend auf '/' und
      '..' pruefen bzw. auf einen sicheren Zeichensatz reduzieren
  {OK} Nach File(parent, child) canonicalPath bilden und pruefen, dass er mit
      parent.canonicalPath + File.separator beginnt
  {OK} Loeschen ausschliesslich ueber ContentResolver.delete() / MediaStore.
      createDeleteRequest(); File.delete() auf fremden URIs komplett entfernen""")

print("\n\033[1mErgebnis: V1, V2 und V3 sind ohne Geraet logisch nachvollzogen.\033[0m")
