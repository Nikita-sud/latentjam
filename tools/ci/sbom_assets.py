#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Augment CycloneDX with manually reviewed native trees and exact bundled asset hashes."""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    bom = json.loads(args.input.read_text())
    assert bom["bomFormat"] == "CycloneDX"
    manifest = json.loads((ROOT / "tools/ci/bundled-components.json").read_text())
    components = bom.setdefault("components", [])
    added = []
    def add(name, kind, sha, license, description, locations):
        ref = "latentjam-bundled:" + name
        components.append({"bom-ref": ref, "type": kind, "name": name, "version": sha[:16],
                           "hashes": [{"alg": "SHA-256", "content": sha}],
                           "licenses": [{"expression": license}], "description": description,
                           "properties": [{"name": "latentjam:source-path", "value": p} for p in locations]})
        added.append(ref)
    for entry in manifest["native"]:
        directory = ROOT / entry["path"]
        files = sorted(p for p in directory.rglob("*") if p.is_file())
        if not files:
            raise ValueError("Missing native source: " + entry["path"])
        sha = hashlib.sha256()
        for path in files:
            sha.update((str(path.relative_to(directory)) + "\0" + digest(path) + "\n").encode())
        add(entry["name"], "library", sha.hexdigest(), entry["license"],
            "Vendored source tree, not a separately resolved Gradle artifact. " + entry["url"], [entry["path"]])
    android = ROOT / "androidApp/src/main/assets/ml"
    ios = ROOT / "iosApp/iosApp/ml"
    expected = set(manifest["assets"])
    actual = {p.name for p in android.iterdir() if p.suffix in (".onnx", ".bin")} | {"text_vocab.txt"}
    if actual != expected:
        raise ValueError("Review the asset inventory: " + str(actual ^ expected))
    for name, entry in manifest["assets"].items():
        sha = digest(android / name)
        if digest(ios / name) != sha:
            raise ValueError("Android/iOS asset mismatch: " + name)
        add(name, "file", sha, entry["license"], entry["provenance"],
            [str((android / name).relative_to(ROOT)), str((ios / name).relative_to(ROOT))])
    # Connect additions to the application root instead of listing disconnected components.
    application = bom["metadata"]["component"]
    application["type"] = "application"
    application["name"] = "LatentJam"
    application["version"] = re.search(r'versionName = "([^"]+)"',
        (ROOT / "androidApp/build.gradle.kts").read_text()).group(1)
    application.pop("purl", None) # The aggregation project's placeholder coordinates are not an artifact.
    lock = (ROOT / "iosApp/Podfile.lock").read_text()
    version = re.search(r"^  - onnxruntime-c \(([^)]+)\)$", lock, re.M).group(1)
    ref = "pkg:cocoapods/onnxruntime-c@" + version
    components.append({"bom-ref": ref, "type": "library", "name": "onnxruntime-c",
        "version": version, "purl": ref, "licenses": [{"expression": "MIT"}],
        "properties": [{"name": "latentjam:lockfile-sha256", "value": digest(ROOT / "iosApp/Podfile.lock")}],
        "description": "iOS CocoaPods runtime; exact binary hashes are platform-build artifacts."})
    added.append(ref)
    root = application.get("bom-ref")
    if not root:
        raise ValueError("SBOM has no application component reference")
    dependencies = bom.setdefault("dependencies", [])
    edge = next((d for d in dependencies if d["ref"] == root), None)
    if edge is None:
        edge = {"ref": root, "dependsOn": []}
        dependencies.append(edge)
    edge.setdefault("dependsOn", []).extend(added)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bom, indent=2) + "\n")

if __name__ == "__main__":
    main()

