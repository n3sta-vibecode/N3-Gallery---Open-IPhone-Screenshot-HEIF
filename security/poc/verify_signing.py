#!/usr/bin/env python3
"""PoC [V5/V6]: Beweis, dass der Signaturschluessel der veroeffentlichten APK
oeffentlich im Repository liegt UND nutzbar ist.

Reproduziert die komplette Kette OHNE Geraet, direkt aus dem Git-Verlauf:

  1. keystore.properties aus origin/main lesen  -> Passwort im Klartext
  2. ci/n3-ci.p12 aus origin/main extrahieren    -> der Signaturschluessel selbst
  3. Privaten Schluessel mit dem Passwort laden  -> beweist: Schluessel ist NUTZBAR
  4. Signaturzertifikat der veroeffentlichten Release-APK extrahieren
  5. Fingerabdruecke vergleichen                 -> beweist: DIESE APK ist damit signiert

Ergebnis: Jeder, der das Repo lesen kann, kann eine APK signieren, die Android auf
jedem Geraet mit installierter N3 Gallery als legitimes Update akzeptiert.

Voraussetzung:  pip install cryptography
Aufruf:         python3 verify_signing.py [pfad-zur-release.apk]
                (ohne APK-Argument wird nur Schluessel+Passwort aus dem Git-Verlauf
                geladen und der Fingerabdruck berichtet)
"""
import hashlib
import subprocess
import sys


def git_show(ref: str, path: str) -> bytes:
    """Holt eine Datei aus einem Git-Ref (Blob)."""
    out = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        capture_output=True, check=False,
    )
    if out.returncode != 0:
        raise SystemExit(f"[!] git show {ref}:{path} fehlgeschlagen: {out.stderr.decode(errors='replace')}")
    return out.stdout


def fp256(der: bytes) -> str:
    return ":".join(f"{b:02X}" for b in hashlib.sha256(der).digest())


def main() -> int:
    ref = "origin/main"
    print(f"[*] Git-Ref: {ref}\n")

    # 1) Passwort aus keystore.properties
    try:
        props = git_show(ref, "keystore.properties").decode("utf-8", "replace")
    except SystemExit as e:
        print(e)
        return 1
    pw = None
    for line in props.splitlines():
        if line.startswith("storePassword="):
            pw = line.split("=", 1)[1].strip()
    if pw is None:
        print("[!] kein storePassword in keystore.properties gefunden")
        return 1
    print(f"[1] Passwort aus keystore.properties (KLARTEXT im Repo): {pw!r}")

    # 2) Schluessel extrahieren
    p12 = git_show(ref, "ci/n3-ci.p12")
    print(f"[2] ci/n3-ci.p12 aus dem Repo extrahiert: {len(p12)} Bytes")

    # 3) privaten Schluessel laden
    try:
        from cryptography.hazmat.primitives.serialization import pkcs12, Encoding
    except ImportError:
        print("[!] 'cryptography' fehlt:  pip install cryptography")
        return 1
    try:
        key, cert, _ = pkcs12.load_key_and_certificates(p12, pw.encode())
    except Exception as e:
        print(f"[!] Laden mit dem Repo-Passwort fehlgeschlagen: {e}")
        return 1
    print(f"[3] PRIVATER SCHLUESSEL GELADEN: {key.__class__.__name__}, {key.key_size} Bit")
    print(f"    => Das Passwort aus dem Repo funktioniert. Der Schluessel ist nutzbar.")
    print(f"    Zertifikat: {cert.subject}")
    keyfp = fp256(cert.public_bytes(Encoding.DER))
    print(f"    SHA-256   : {keyfp}")

    # 4)+5) optional: APK-Signatur vergleichen
    if len(sys.argv) > 1:
        apk = sys.argv[1]
        try:
            from cryptography.hazmat.primitives.serialization import Encoding  # noqa
        except Exception:
            pass
        # APK-Signing-Block parsen (V2) -> Zertifikat
        import struct, zipfile
        data = open(apk, "rb").read()
        eocd = data.rfind(b"PK\x05\x06")
        cd_off = struct.unpack("<I", data[eocd + 16:eocd + 20])[0]
        mo = cd_off - 16
        if data[mo:cd_off] != b"APK Sig Block 42":
            print("[!] APK hat keinen Signing-Block (nur V1?)")
            return 1
        (size,) = struct.unpack("<Q", data[mo - 8:mo])
        start = cd_off - size - 8
        pos = start + 8
        apk_der = None
        while pos + 12 <= cd_off - 24:
            (plen,) = struct.unpack("<Q", data[pos:pos + 8])
            (pid,) = struct.unpack("<I", data[pos + 8:pos + 12])
            if pid == 0x7109871A:  # V2
                payload = data[pos + 12:pos + 8 + plen]
                (slen,) = struct.unpack("<I", payload[0:4])
                signers = payload[4:4 + slen]
                (signer_len,) = struct.unpack("<I", signers[0:4])
                signer = signers[4:4 + signer_len]
                (sd_len,) = struct.unpack("<I", signer[0:4])
                sd = signer[4:4 + sd_len]
                (dig_len,) = struct.unpack("<I", sd[0:4])
                x = 4 + dig_len
                (cert_len,) = struct.unpack("<I", sd[x:x + 4])
                c = x + 4
                (one,) = struct.unpack("<I", sd[c:c + 4])
                apk_der = sd[c + 4:c + 4 + one]
                break
            pos += 8 + plen
        if apk_der is None:
            print("[!] kein V2-Zertifikat in der APK gefunden")
            return 1
        apkfp = fp256(apk_der)
        print(f"\n[4] Signaturzertifikat der APK {apk}")
        print(f"    SHA-256   : {apkfp}")
        print(f"[5] Abgleich mit dem Schluessel aus dem Repo:")
        if apkfp == keyfp:
            print("    \033[1;31m*** UEBEREINSTIMMUNG ***\033[0m")
            print("    Die veroeffentlichte APK ist mit dem oeffentlichen Repo-Schluessel")
            print("    signiert. Jeder kann damit ein von Android akzeptiertes Update bauen.")
            return 2
        print("    -> keine Uebereinstimmung (APK mit anderem Schluessel signiert)")
    else:
        print("\n[*] Kein APK-Argument: zum Vergleich")
        print("    python3 verify_signing.py N3-Gallery-1.31-release.apk")

    print("\n[!] Befund V5/V6 bestaetigt: Schluessel + Passwort sind oeffentlich und nutzbar.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
