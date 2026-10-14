#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build a pinned TagLib reader and independently check LatentJam's tag serialization.
TagLib runs only on the development/CI host and is never linked into the application.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REVISION = "deadc2990767dfbda0701e0ab35fdeea653db08f"  # TagLib 2.3.2
UTFCPP_REVISION = "2d8e20b22dcb3e9b3c4f52103182ebda949c6089"

def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, help="Existing checkout of the exact revision (offline use)")
    parser.add_argument("--skip-export", action="store_true", help="Reuse the already exported fixtures")
    args = parser.parse_args()
    output = ROOT / "build/taglib"
    source = (args.source_dir or output / "source").resolve()
    if not source.exists():
        source.mkdir(parents=True)
        run("git", "init", source)
        run("git", "-C", source, "fetch", "--depth", "1", "https://github.com/taglib/taglib.git", REVISION)
        run("git", "-C", source, "checkout", "--detach", "FETCH_HEAD")
        run("git", "-C", source, "submodule", "update", "--init", "--depth", "1")
    for checkout, revision in [(source, REVISION), (source / "3rdparty/utfcpp", UTFCPP_REVISION)]:
        actual = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if actual != revision:
            raise SystemExit(f"Wrong pinned source: {checkout}: {actual}")
        run("git", "-C", checkout, "diff", "--exit-code", "HEAD", "--")
    build = output / "build"
    run("cmake", "-S", ROOT / "tools/taglib", "-B", build, "-DCMAKE_BUILD_TYPE=Release", f"-DTAGLIB_SOURCE={source}")
    run("cmake", "--build", build, "--parallel", "2", "--target", "taglib_oracle")
    fixtures = output / "fixtures"
    if not args.skip_export:
        run("./gradlew", "-I", "tools/fuzz/classpath.init.gradle", ":core:library:exportFuzzClasspath",
            "--no-configuration-cache", "--max-workers=2", cwd=ROOT)
        classpath = (ROOT / "core/library/build/fuzz/classpath.txt").read_text().strip()
        java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else "java"
        run(java, "-cp", classpath, "io.github.nikitasud.latentjam.library.tags.TagLibFixtures", fixtures)
    cases = {}
    for row in (fixtures / "manifest.tsv").read_text().splitlines():
        parts = row.split("\t")
        if parts[0] == "case": cases[parts[1]] = {"extension": parts[2], "edits": {}}
        else: cases[parts[1]]["edits"][parts[2]] = bytes.fromhex(parts[3]).decode("utf-8")
    def read(path):
        result = subprocess.run([str(build / "taglib_oracle"), str(path)], text=True, capture_output=True, timeout=15, check=True)
        properties = {}
        for line in result.stdout.splitlines():
            key, value = [bytes.fromhex(p).decode("utf-8") for p in line.split("\t")]
            properties.setdefault(key, []).append(value)
        return properties
    failures = []
    for name, case in cases.items():
        try:
            before = read(fixtures / f"{name}.before.{case['extension']}")
            after = read(fixtures / f"{name}.after.{case['extension']}")
            for field, value in case["edits"].items():
                expected = [value] if value else []
                if after.get(field, []) != expected:
                    failures.append(f"{name}: {field}: expected {expected!r}, got {after.get(field, [])!r}")
            for field in before.keys() | after.keys():
                if field not in case["edits"] and before.get(field, []) != after.get(field, []):
                    failures.append(f"{name}: untouched {field} changed")
        except (subprocess.SubprocessError, ValueError) as error:
            failures.append(f"{name}: {error}")
    report = {"taglibRevision": REVISION, "utfcppRevision": UTFCPP_REVISION,
              "cases": len(cases), "failures": failures, "runtimeDependency": False}
    destination = ROOT / "build/reports/taglib.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failures: raise SystemExit(1)

if __name__ == "__main__": main()
