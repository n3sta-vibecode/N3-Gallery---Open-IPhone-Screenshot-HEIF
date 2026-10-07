#!/usr/bin/env python3
"""Minimal binary AndroidManifest.xml (AXML) decoder -- pure python, no Java.

Chunk layouts used:
  ResChunk_header      type(u16) headerSize(u16) size(u32)
  ResStringPool_header + stringCount(u32) styleCount(u32) flags(u32)
                       stringsStart(u32) stylesStart(u32)  -> headerSize 28
  ResXMLTree_node      + lineNumber(u32) comment(u32)       -> headerSize 16
  ResXMLTree_attrExt     ns(4) name(4) attributeStart(2) attributeSize(2)
                         attributeCount(2) idIndex(2) classIndex(2) styleIndex(2)
  ResXMLTree_attribute   ns(4) name(4) rawValue(4)
                         ResValue{ size(2) res0(1) dataType(1) data(4) }  = 20 bytes
"""
import struct
import sys
import zipfile

CHUNK_AXML = 0x0003
CHUNK_STRINGPOOL = 0x0001
CHUNK_RESMAP = 0x0180
CHUNK_START_NS = 0x0100
CHUNK_END_NS = 0x0101
CHUNK_START_TAG = 0x0102
CHUNK_END_TAG = 0x0103
CHUNK_TEXT = 0x0104

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# android:* attribute resource ids relevant to a security review
KNOWN = {
    0x01010000: "theme", 0x01010001: "label", 0x01010002: "icon",
    0x01010003: "name", 0x01010005: "value", 0x01010006: "permission",
    0x01010007: "readPermission", 0x01010008: "writePermission",
    0x01010009: "protectionLevel", 0x0101000F: "launchMode",
    0x0101000E: "exported", 0x01010012: "exported", 0x01010013: "process",
    0x01010016: "authorities", 0x01010017: "grantUriPermissions",
    0x0101001B: "permissionFlags", 0x0101001C: "maxSdkVersion",
    0x01010024: "excludeFromRecents", 0x01010025: "debuggable",
    0x01010026: "theme", 0x01010203: "mimeType",
    0x0101002A: "fullBackupContent", 0x01010030: "minSdkVersion",
    0x01010037: "usesCleartextTraffic",     0x0101021B: "versionCode", 0x0101021C: "versionName",
    0x01010273: "parentActivityName", 0x0101000B: "allowBackup",
    0x0101028E: "enabled", 0x010102BF: "largeHeap",
    0x010102D0: "configChanges", 0x010102D3: "hardwareAccelerated",
    0x0101030F: "roundIcon", 0x01010442: "extractNativeLibs",
    0x01010364: "supportsRtl", 0x01010530: "usesPermissionFlags",
    0x01010270: "compileSdkVersion", 0x01010031: "targetSdkVersion",
    0x0101002E: "scheme", 0x0101002F: "host", 0x01010029: "pathPattern",
    0x01010265: "pathPrefix", 0x0101002C: "path",
}


def read_string_pool(buf, off):
    string_count, style_count, flags, strings_start, styles_start = struct.unpack(
        "<IIIII", buf[off + 8:off + 28])
    is_utf8 = (flags & (1 << 8)) != 0
    offs = struct.unpack(f"<{string_count}I", buf[off + 28:off + 28 + string_count * 4])
    base = off + strings_start
    out = []
    for o in offs:
        q = base + o
        try:
            if is_utf8:
                n = buf[q]
                q += 1
                if n & 0x80:
                    n = ((n & 0x7F) << 8) | buf[q]
                    q += 1
                m = buf[q]
                q += 1
                if m & 0x80:
                    m = ((m & 0x7F) << 8) | buf[q]
                    q += 1
                out.append(buf[q:q + m].decode("utf-8", "replace"))
            else:
                (n,) = struct.unpack("<H", buf[q:q + 2])
                q += 2
                if n & 0x8000:
                    (n2,) = struct.unpack("<H", buf[q:q + 2])
                    n = ((n & 0x7FFF) << 16) | n2
                    q += 2
                out.append(buf[q:q + n * 2].decode("utf-16-le", "replace"))
        except Exception:
            out.append("")
    return out


def fmt_val(typ, data, strings):
    if typ == TYPE_STRING:
        return strings[data] if data < len(strings) else ""
    if typ == TYPE_INT_BOOLEAN:
        return "true" if data != 0 else "false"
    if typ == TYPE_INT_DEC:
        return str(struct.unpack("<i", struct.pack("<I", data))[0])
    if typ == TYPE_INT_HEX:
        return f"0x{data:X}"
    if typ == TYPE_REFERENCE:
        return f"@0x{data:08X}"
    return f"<type{typ}:0x{data:08X}>"


def main():
    path = sys.argv[1]
    inner = sys.argv[2] if len(sys.argv) > 2 else "AndroidManifest.xml"
    raw = zipfile.ZipFile(path).read(inner)
    (t, hdr, total) = struct.unpack("<HHI", raw[:8])
    if t != CHUNK_AXML:
        print(f"[!] not AXML: 0x{t:04X}")
        return 1
    strings, resmap = [], []
    pos, depth = hdr, 0
    print(f"=== {inner}  ({total} bytes) ===")
    while pos + 8 <= len(raw):
        ctype, chdr, csize = struct.unpack("<HHI", raw[pos:pos + 8])
        if csize < 8 or pos + csize > len(raw):
            break
        body = pos + chdr
        if ctype == CHUNK_STRINGPOOL:
            strings = read_string_pool(raw, pos)
        elif ctype == CHUNK_RESMAP:
            resmap = list(struct.unpack(f"<{(csize - chdr)//4}I", raw[body:pos + csize]))
        elif ctype == CHUNK_START_TAG:
            ns, nm = struct.unpack("<II", raw[body:body + 8])
            a_start, a_size, a_count = struct.unpack("<HHH", raw[body + 8:body + 14])
            tag = strings[nm] if nm < len(strings) else "?"
            print("  " * depth + f"<{tag}")
            ap = body + a_start
            for _ in range(a_count):
                a_ns, a_nm, a_raw, _vs, _r0, a_type, a_data = struct.unpack(
                    "<IIIHBBi", raw[ap:ap + 20])
                aname = strings[a_nm] if a_nm < len(strings) else "?"
                rid = resmap[a_nm] if a_nm < len(resmap) else 0
                is_android = a_ns < len(strings) and strings[a_ns] == ANDROID_NS
                prefix = "android:" if is_android else ""
                if is_android and rid in KNOWN:
                    aname = KNOWN[rid]
                if a_raw != 0xFFFFFFFF and a_raw < len(strings):
                    val = strings[a_raw]
                else:
                    val = fmt_val(a_type, a_data & 0xFFFFFFFF, strings)
                print("  " * depth + f'    {prefix}{aname}="{val}"')
                ap += 20
            depth += 1
        elif ctype == CHUNK_END_TAG:
            ns, nm = struct.unpack("<II", raw[body:body + 8])
            tag = strings[nm] if nm < len(strings) else "?"
            depth = max(0, depth - 1)
            print("  " * depth + f"/>  <!-- end {tag} -->")
        pos += csize
    return 0


if __name__ == "__main__":
    sys.exit(main())
