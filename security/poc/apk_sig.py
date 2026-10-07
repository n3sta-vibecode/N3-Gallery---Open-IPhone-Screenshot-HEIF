#!/usr/bin/env python3
"""APK signing-scheme inspector (pure python, no Java needed).

Locates the APK Signing Block that sits immediately before the ZIP
Central Directory and reports which signature schemes are present.

Why it matters:
  * v1 (JAR / META-INF/*.SF) only  -> vulnerable to the Janus class of
    APK-tampering attacks on Android 5-8 (CVE-2017-13156): an attacker can
    prepend a malicious DEX and the system still trusts the v1 signature.
  * v2/v3 sign the whole file      -> tampering breaks the signature.
  * debug key in a RELEASE build    -> anyone with the (public) AOSP debug
    keystore can sign an "update" that Android accepts.
"""
import struct
import sys
import zipfile

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SCHEME_IDS = {
    0x7109871A: "V2 (APK Signature Scheme v2)",
    0xF05368C0: "V3 (APK Signature Scheme v3)",
    0x1B93AD61: "V3.1 (APK Signature Scheme v3.1)",
    0x42726577: "V4 (APK Signature Scheme v4)",
    0x2146444E: "DM (Dependency Metadata)",
    0x42226E64: "Play asset delivery",
}

# The well-known public AOSP / Android Studio debug certificate DN and modulus.
DEBUG_CERT_MARKERS = [
    b"Android Debug",
    b"CN=Android Debug",
]


def find_signing_block(data: bytes):
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        return None, None
    cd_off = struct.unpack("<I", data[eocd + 16:eocd + 20])[0]
    magic_off = cd_off - 16
    if magic_off < 0 or data[magic_off:cd_off] != APK_SIG_BLOCK_MAGIC:
        return None, cd_off
    size = struct.unpack("<Q", data[magic_off - 8:magic_off])[0]
    block_start = cd_off - size - 8
    return (block_start, cd_off), cd_off


def parse_pairs(data: bytes, start: int, end: int):
    pos = start + 8  # skip leading size field
    out = []
    while pos + 12 <= end - 24:
        (pair_len,) = struct.unpack("<Q", data[pos:pos + 8])
        if pair_len <= 0 or pos + 8 + pair_len > end - 24:
            break
        (pid,) = struct.unpack("<I", data[pos + 8:pos + 12])
        out.append((pid, pair_len - 4))
        pos += 8 + pair_len
    return out


def main() -> int:
    path = sys.argv[1]
    data = open(path, "rb").read()
    print(f"[*] APK: {path}  ({len(data):,} bytes)\n")

    zf = zipfile.ZipFile(path)
    names = zf.namelist()

    v1 = [n for n in names if n.startswith("META-INF/") and
          n.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))]
    print("== V1 (JAR signing, META-INF) ==")
    if v1:
        for n in v1:
            print(f"   present: {n}  ({zf.getinfo(n).file_size:,} bytes)")
    else:
        print("   not present")

    print("\n== APK Signing Block (V2 / V3 / V4) ==")
    span, cd_off = find_signing_block(data)
    if span is None:
        print("   NOT FOUND -> this APK has NO v2/v3 signature.")
        if v1:
            print("   [!!!] V1-ONLY SIGNATURE.")
            print("   [!!!] Exposed to Janus-style tampering (CVE-2017-13156) on Android 5.0-8.x.")
            print("         An attacker can prepend a classes.dex; the v1 signature still verifies")
            print("         because v1 ignores file bytes outside the ZIP entries.")
    else:
        start, end = span
        print(f"   block at 0x{start:X}..0x{end:X}, central dir at 0x{cd_off:X}")
        pairs = parse_pairs(data, start, end)
        if not pairs:
            print("   (block present but no readable ID pairs)")
        for pid, plen in pairs:
            label = SCHEME_IDS.get(pid, f"unknown ID 0x{pid:08X}")
            print(f"   ID 0x{pid:08X} -> {label}  ({plen} bytes)")

    print("\n== Certificate ==")
    for n in v1:
        if n.upper().endswith((".RSA", ".DSA", ".EC")):
            blob = zf.read(n)
            print(f"   {n}: {len(blob)} bytes")
            for m in DEBUG_CERT_MARKERS:
                if m in blob:
                    print(f"   [!!!] contains marker {m!r} -> looks like the PUBLIC AOSP DEBUG key!")
                    print("   [!!!] A release APK signed with the debug key can be 'updated' by anyone,")
                    print("         because the AOSP debug keystore password/cert are published.")

    print("\n== Native libs / manifest sanity ==")
    for n in names:
        if n.endswith(".so"):
            print(f"   {n}  ({zf.getinfo(n).file_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
