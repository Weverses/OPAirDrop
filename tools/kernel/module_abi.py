#!/usr/bin/env python3
"""Read-only ELF64/AArch64 module CRC audit; Python standard library only.

Stock imports are evidence of expected CRCs, not a replacement Module.symvers.
Passing this audit does not prove layout, KCFI, GKI access or firmware compatibility.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys


def cstring(data, offset=0):
    return data[offset:].split(b"\0", 1)[0].decode("utf-8", errors="replace")


def inspect(path):
    data = Path(path).read_bytes()
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{path}: requires uncompressed little-endian ELF64")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    if header[0] != 1 or header[1] != 183:
        raise ValueError(f"{path}: requires AArch64 relocatable module")
    shoff, shsize, shnum, shstr = header[5], header[10], header[11], header[12]
    if shsize != 64 or shnum == 0 or not 0 < shstr < shnum:
        raise ValueError(f"{path}: unsupported section table")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shsize)
                for i in range(shnum)]

    def payload(section):
        offset, size = section[4:6]
        if offset + size > len(data):
            raise ValueError(f"{path}: truncated section")
        return data[offset:offset + size]

    names = payload(sections[shstr])
    named = {cstring(names, s[0]): s for s in sections}
    modinfo = {}
    for entry in payload(named[".modinfo"]).split(b"\0"):
        if b"=" in entry:
            key, value = entry.decode(errors="replace").split("=", 1)
            modinfo.setdefault(key, []).append(value)
    versions = {}
    if "__versions" in named:
        raw = payload(named["__versions"])
        if len(raw) % 64:
            raise ValueError(f"{path}: unsupported modversion record size")
        for offset in range(0, len(raw), 64):
            crc = struct.unpack_from("<Q", raw, offset)[0]
            name = cstring(raw[offset + 8:offset + 64])
            if name:
                versions[name] = f"0x{crc:08x}"
    if "__version_ext_names" in named or "__version_ext_crcs" in named:
        name_data = payload(named["__version_ext_names"])
        if not name_data.endswith(b"\0"):
            raise ValueError(f"{path}: unterminated extended version names")
        raw_names = name_data.rstrip(b"\0").split(b"\0")
        if any(not name for name in raw_names):
            raise ValueError(f"{path}: empty extended version name")
        raw_crcs = payload(named["__version_ext_crcs"])
        if len(raw_crcs) != len(raw_names) * 4:
            raise ValueError(f"{path}: inconsistent extended versions")
        for offset, raw_name in enumerate(raw_names):
            crc = struct.unpack_from("<I", raw_crcs, offset * 4)[0]
            name = raw_name.decode(errors="replace")
            value = f"0x{crc:08x}"
            if name in versions and versions[name] != value:
                raise ValueError(f"{path}: conflicting basic/extended CRC: {name}")
            versions[name] = value
    undefined = []
    exports = []
    if ".symtab" in named:
        section = named[".symtab"]
        raw = payload(section)
        strings = payload(sections[section[6]])
        if section[9] != 24 or len(raw) % 24:
            raise ValueError(f"{path}: unsupported symbol record size")
        for offset in range(0, len(raw), 24):
            name_offset, info, _, index, _, _ = struct.unpack_from("<IBBHQQ", raw, offset)
            name = cstring(strings, name_offset)
            if name and index == 0 and info >> 4 in (1, 2):
                undefined.append(name)
            if name.startswith("__ksymtab_"):
                exports.append(name.removeprefix("__ksymtab_"))
    return {"file": str(Path(path).resolve()), "sha256": hashlib.sha256(data).hexdigest(),
            "modinfo": modinfo, "versions": versions,
            "undefined": sorted(set(undefined)), "exports": sorted(set(exports)),
            "signature_trailer_present": data.endswith(b"~Module signature appended~\n")}


def reference(paths):
    expected = {}
    for path in paths:
        modules = json.loads(Path(path).read_text()) if Path(path).suffix == ".json" else [inspect(path)]
        if not isinstance(modules, list):
            raise ValueError(f"{path}: expected JSON list from inspect")
        for module in modules:
            for name, crc in module["versions"].items():
                crc = f"0x{int(crc, 16):08x}"
                if name in expected and expected[name] != crc:
                    raise ValueError(f"stock reference CRC conflict: {name}")
                expected[name] = crc
    if not expected:
        raise ValueError("stock references have no modversion evidence")
    return expected


def symvers(paths):
    result = {}
    for path in paths:
        for number, line in enumerate(Path(path).read_text().splitlines(), 1):
            fields = line.split()
            if not fields:
                continue
            if len(fields) < 4:
                raise ValueError(f"{path}:{number}: malformed Module.symvers")
            crc = f"0x{int(fields[0], 16):08x}"
            name = fields[1]
            if name in result and result[name] != crc:
                raise ValueError(f"symvers CRC conflict: {name}")
            result[name] = crc
    if not result:
        raise ValueError("empty Module.symvers")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    show = commands.add_parser("inspect")
    show.add_argument("modules", nargs="+")
    check = commands.add_parser("check")
    check.add_argument("--stock", nargs="+", required=True)
    check.add_argument("--symvers", nargs="+", required=True)
    parser.add_argument("--out", type=Path, help="write full JSON instead of stdout")
    args = parser.parse_args()
    if args.command == "inspect":
        result = [inspect(path) for path in args.modules]
        code = 0
    else:
        expected = reference(args.stock)
        built = symvers(args.symvers)
        mismatches = {name: {"stock": crc, "build": built[name]}
                      for name, crc in expected.items() if name in built and built[name] != crc}
        missing = sorted(set(expected) - set(built))
        result = {"expected_symbols": len(expected), "matched": sum(
            built.get(name) == crc for name, crc in expected.items()),
            "mismatches": mismatches, "missing": missing,
            "status": "FAIL" if mismatches else "INCOMPLETE" if missing else "CRC_MATCH_ONLY",
            "limitations": "No proof of KCFI, struct layout, GKI access, provider binding or firmware support."}
        code = 1 if mismatches else 2 if missing else 0
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.write_text(serialized)
    else:
        print(serialized, end="")
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, struct.error) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(3)
