#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Merge measured, unobfuscated human-readable profiles into deterministic source files."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]

def merge(paths):
    rules = {}
    for path in paths:
        for line in path.read_text().splitlines():
            if not line or line.startswith("#"):
                continue
            match = re.fullmatch(r"([HSP]*)(\[*L.+)", line)
            if not match:
                raise ValueError("Invalid profile rule in " + str(path) + ": " + line)
            flags, descriptor = match.groups()
            rules.setdefault(descriptor, set()).update(flags)
    if not any("Lio/github/nikitasud/latentjam/" in descriptor for descriptor in rules):
        raise ValueError("No LatentJam rules: generate with -Platentjam.profileGeneration=true")
    return "".join("".join(f for f in "HSP" if f in rules[d]) + d + "\n" for d in sorted(rules))

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("directory", type=Path)
    p.add_argument("--apk", type=Path, required=True, help="The unminified APK used to generate these profiles")
    p.add_argument("--fixture", required=True, help="Short fixture and device description; no private song names")
    args = p.parse_args()
    manifest = {"apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                "fixture": args.fixture, "profiles": {}}
    for kind in ("baseline", "startup"):
        paths = sorted(args.directory.glob("*-" + kind + "-prof.txt"))
        if not paths:
            raise SystemExit("Missing measured " + kind + " profile")
        content = merge(paths)
        output = ROOT / "androidApp/src/main" / (kind + "-prof.txt")
        output.write_text(content)
        manifest["profiles"][kind] = {"rules": len(content.splitlines()),
            "sha256": hashlib.sha256(content.encode()).hexdigest(),
            "inputs": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}}
    (ROOT / "tools/benchmark/profile-provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")

if __name__ == "__main__":
    main()
