#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Record APK sizes/hashes and fail on missing custom ops or invalid 16 KiB native alignment."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

def inspect(path):
    result = {"apk": path.name, "bytes": path.stat().st_size,
              "sha256": hashlib.file_digest(path.open("rb"), "sha256").hexdigest(), "native": []}
    with zipfile.ZipFile(path) as apk, path.open("rb") as raw:
        names = set(apk.namelist())
        for name in sorted(names):
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            _, abi, library = name.split("/")
            if library == "libonnxruntime.so":
                for required in ("libljq4.so", "libljmap.so", "liblj_equalizer.so"):
                    if f"lib/{abi}/{required}" not in names:
                        raise ValueError(f"{path}: {abi} lacks {required}")
            info = apk.getinfo(name)
            data = apk.read(name)
            if abi in ("arm64-v8a", "x86_64"):
                if data[:6] != b"\x7fELF\x02\x01":
                    raise ValueError(f"{name}: expected little-endian ELF64")
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                loads = 0
                for i in range(count):
                    header = offset + i * size
                    kind = struct.unpack_from("<I", data, header)[0]
                    if kind == 1:
                        loads += 1
                        file_offset, address = struct.unpack_from("<QQ", data, header + 8)
                        alignment = struct.unpack_from("<Q", data, header + 48)[0]
                        if alignment < 16384 or file_offset % 16384 != address % 16384:
                            raise ValueError(f"{name}: LOAD segment not 16 KiB aligned")
                if not loads:
                    raise ValueError(f"{name}: no LOAD segments")
                if info.compress_type == zipfile.ZIP_STORED:
                    raw.seek(info.header_offset + 26)
                    name_len, extra_len = struct.unpack("<HH", raw.read(4))
                    if (info.header_offset + 30 + name_len + extra_len) % 16384:
                        raise ValueError(f"{name}: APK data not 16 KiB aligned")
            result["native"].append({"path": name, "bytes": info.file_size,
                                    "sha256": hashlib.sha256(data).hexdigest()})
    if not result["native"]:
        raise ValueError(f"{path}: no native libraries")
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    apks = sorted(args.directory.glob("*.apk"))
    if not apks:
        raise SystemExit("No APKs found")
    report = [inspect(p) for p in apks]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    for item in report:
        print(item["apk"], item["bytes"], item["sha256"])

if __name__ == "__main__":
    main()

