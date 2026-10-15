#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the same UI journeys against an already installed, isolated benchmark APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
from datetime import datetime, timezone

PACKAGE = "io.github.nikitasud.latentjam.kmp.benchmark"
TEST_PACKAGE = "io.github.nikitasud.latentjam.benchmark"
SCENARIOS = ("scrollTracks", "changeTrackSort", "openClosePlayer", "coldStartAndFirstList")
ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--label", required=True, help="Unique run label; existing runs are never overwritten")
    parser.add_argument("--apk", type=Path, required=True, help="Expected installed target, used for SHA-256 verification")
    parser.add_argument("--fixture", required=True, help="Stable fixture identity without private song names")
    parser.add_argument("--tracks", type=int, required=True)
    parser.add_argument("--source", required=True, help="Source revision and description of measurement-only patches")
    parser.add_argument("--profile", choices=("none", "require", "full"), default="full")
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--pause-description", default="Pause|Пауза", help="Regex for the fixture locale's pause button")
    parser.add_argument("--sort-title-label", default="Title|Название")
    parser.add_argument("--sort-artist-label", default="Artist|Исполнитель")
    parser.add_argument("--scenarios", nargs="+", choices=SCENARIOS, default=SCENARIOS[:3])
    parser.add_argument("--output", type=Path, default=ROOT / "build/ui-optimization")
    parser.add_argument("--adb", default=str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
        parser.error("label may contain only ASCII letters, numbers, hyphens and underscores")
    if not 1 <= args.iterations <= 30 or args.tracks < 20:
        parser.error("use 1..30 iterations and a settled fixture of at least 20 tracks")
    output = args.output / args.label
    output.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]

    def command(*parts):
        return subprocess.check_output(adb + list(parts), text=True).strip()

    def media_fingerprint():
        # No titles, artists, paths or audio leave the phone: only IDs, revisions and sizes.
        query = ["content", "query", "--user", "0", "--uri", "content://media/external/audio/media",
                 "--projection", "_id:date_modified:_size:duration", "--where", "is_music=1",
                 "--sort", "_id ASC"]
        rows = command("shell", shlex.join(query)).splitlines()
        if not rows or any(not line.startswith("Row:") for line in rows):
            raise RuntimeError("Cannot fingerprint the prepared MediaStore music fixture")
        return {"rows": len(rows), "sha256": hashlib.sha256("\n".join(rows).encode()).hexdigest()}

    fixture_media = media_fingerprint()
    if fixture_media["rows"] != args.tracks:
        raise RuntimeError("The MediaStore music count differs from --tracks")
    expected = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    installed = command("shell", "pm", "path", "--user", "0", PACKAGE)
    paths = [line.removeprefix("package:") for line in installed.splitlines() if line.startswith("package:")]
    if len(paths) != 1:
        raise RuntimeError("Expected one standalone benchmark APK, not missing/split APKs")
    actual = command("shell", "sha256sum", paths[0]).split()[0]
    harness_paths = command("shell", "pm", "path", "--user", "0", TEST_PACKAGE).splitlines()
    if len(harness_paths) != 1 or not harness_paths[0].startswith("package:"):
        raise RuntimeError("Expected one installed benchmark harness APK")
    harness_hash = command("shell", "sha256sum", harness_paths[0].removeprefix("package:")).split()[0]
    if expected != actual:
        raise RuntimeError(f"Installed benchmark differs from --apk: {actual} != {expected}")
    remote = f"/sdcard/Android/media/{TEST_PACKAGE}/ui-{args.label}"
    probe = subprocess.run(adb + ["shell", "test", "-e", remote], capture_output=True)
    if probe.returncode == 0:
        raise RuntimeError(f"Remote output already exists: {remote}; choose a new label")
    command("shell", "mkdir", "-p", remote)
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "label": args.label, "source": args.source,
        "package": PACKAGE, "apk_sha256": actual, "harness_sha256": harness_hash,
        "fixture": args.fixture, "tracks": args.tracks, "fixture_media": fixture_media,
        "compilation": args.profile, "iterations": args.iterations, "scenarios": args.scenarios,
        "device": {key: command("shell", "getprop", prop) for key, prop in {
            "model": "ro.product.model", "sdk": "ro.build.version.sdk", "fingerprint": "ro.build.fingerprint",
            "emulator": "ro.kernel.qemu",
        }.items()},
        "display": command("shell", "wm", "size"),
        "density": command("shell", "wm", "density"),
        "peak_refresh_rate": command("shell", "settings", "get", "system", "peak_refresh_rate"),
        "min_refresh_rate": command("shell", "settings", "get", "system", "min_refresh_rate"),
    }
    for phase in ("before",):
        (output / f"thermal-{phase}.txt").write_text(command("shell", "dumpsys", "thermalservice"))
        (output / f"battery-{phase}.txt").write_text(command("shell", "dumpsys", "battery"))
    (output / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
    tests = ",".join(f"{TEST_PACKAGE}.AppBenchmarks#{name}" for name in args.scenarios)
    instrumentation = ["am", "instrument", "-w", "-r", "-e", "class", tests,
        "-e", "profile", args.profile, "-e", "iterations", str(args.iterations),
        "-e", "pauseDescription", args.pause_description,
        "-e", "sortTITLELabel", args.sort_title_label, "-e", "sortARTISTLabel", args.sort_artist_label,
        "-e", "additionalTestOutputDir", remote,
        f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"]
    invocation = adb + ["shell", shlex.join(instrumentation)]
    with (output / "instrumentation.log").open("w") as log:
        result = subprocess.run(invocation, stdout=log, stderr=subprocess.STDOUT)
    log = (output / "instrumentation.log").read_text()
    metadata["completed_utc"] = datetime.now(timezone.utc).isoformat()
    match = re.search(r"OK \((\d+) tests?\)", log)
    metadata["fixture_media_after"] = media_fingerprint()
    metadata["passed"] = (result.returncode == 0 and match is not None and int(match[1]) == len(args.scenarios)
                          and metadata["fixture_media_after"] == fixture_media)
    (output / "thermal-after.txt").write_text(command("shell", "dumpsys", "thermalservice"))
    (output / "battery-after.txt").write_text(command("shell", "dumpsys", "battery"))
    (output / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
    subprocess.run(adb + ["pull", remote, str(output / "results")], check=True)
    print(json.dumps({"output": str(output), "passed": metadata["passed"]}))
    if not metadata["passed"]:
        raise SystemExit("Instrumentation failed; inspect instrumentation.log (partial data is preserved)")


if __name__ == "__main__":
    main()
