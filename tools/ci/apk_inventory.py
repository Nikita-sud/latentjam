#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Record APK sizes/hashes and fail on missing custom ops or invalid 16 KiB native alignment."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

REQUIRED_LIBRARIES = {"libonnxruntime.so", "libonnxruntime4j_jni.so", "libljq4.so",
                      "libljmap.so", "liblj_equalizer.so"}

def inspect(path):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    result = {"apk": path.name, "bytes": path.stat().st_size,
              "sha256": digest, "native": []}
    with zipfile.ZipFile(path) as apk, path.open("rb") as raw:
        names = set(apk.namelist())
        if len(names) != len(apk.namelist()):
            raise ValueError(f"{path}: duplicate ZIP members")
        abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
        for abi in abis:
            missing = REQUIRED_LIBRARIES - {name.rsplit('/', 1)[-1] for name in names if name.startswith(f'lib/{abi}/')}
            if missing:
                raise ValueError(f"{path}: {abi} lacks {', '.join(sorted(missing))}")
        result["abis"] = sorted(abis)
        for name in sorted(names):
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            _, abi, library = name.split("/")
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
    parser.add_argument("--abis", nargs="+", help="Required ABI set across the APKs (detects missing whole splits/ABIs)")
    args = parser.parse_args()
    apks = sorted(args.directory.glob("*.apk"))
    if not apks:
        raise SystemExit("No APKs found")
    report = [inspect(p) for p in apks]
    if args.abis and {abi for item in report for abi in item["abis"]} != set(args.abis):
        raise SystemExit("Packaged ABI set differs from --abis")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    for item in report:
        print(item["apk"], item["bytes"], item["sha256"])

if __name__ == "__main__":
    main()

