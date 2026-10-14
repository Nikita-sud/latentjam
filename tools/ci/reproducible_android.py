#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build two clean unsigned F-Droid APKs from the same tracked + untracked source snapshot."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output", type=Path, default=ROOT / "build/reports/reproducibility.json")
    args = p.parse_args()
    if shutil.disk_usage(tempfile.gettempdir()).free < 5 * 1024 ** 3:
        raise SystemExit("At least 5 GiB free temporary space is required for two clean builds.")
    files = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others",
                                     "--exclude-standard"], cwd=ROOT).decode().split("\0")
    # Do not copy ignored credentials, local SDK paths, build caches or any existing outputs.
    files = sorted({f for f in files if f and (ROOT / f).is_file()})
    hashes = {f: hashlib.sha256((ROOT / f).read_bytes()).hexdigest() for f in files}
    report = {"sources": hashes, "builds": []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for iteration in (1, 2):
        with tempfile.TemporaryDirectory(prefix="latentjam-repro-") as folder:
            checkout = Path(folder)
            for file in files:
                output = checkout / file
                output.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / file, output)
                if hashlib.sha256(output.read_bytes()).hexdigest() != hashes[file]:
                    raise SystemExit("Sources changed while taking the snapshot: " + file)
            log = args.output.with_suffix(f".build{iteration}.log")
            with log.open("w") as stream:
                subprocess.run(["./gradlew", ":androidApp:assembleRelease", "-Platentjam.fdroid=true",
                                "--no-build-cache", "--no-configuration-cache", "--max-workers=2"],
                               cwd=checkout, stdout=stream, stderr=subprocess.STDOUT, check=True)
            apks = list((checkout / "androidApp/build/outputs/apk/release").glob("*.apk"))
            if len(apks) != 1:
                raise SystemExit("Expected one unsigned universal F-Droid APK")
            apk = apks[0]
            saved = args.output.with_suffix(f".build{iteration}.apk")
            shutil.copy2(apk, saved)
            report["builds"].append({"sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                    "bytes": apk.stat().st_size, "log": str(log), "apk": str(saved)})
    report["identical"] = report["builds"][0]["sha256"] == report["builds"][1]["sha256"]
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    if not report["identical"]:
        raise SystemExit("Unsigned APKs differ; investigate the artifacts before changing profiles/toolchains.")
    print(args.output)

if __name__ == "__main__":
    main()
