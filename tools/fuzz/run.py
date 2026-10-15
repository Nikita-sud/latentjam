#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run a standalone Jazzer target using compiled host-test classes (JUnit 4 is unchanged)."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TARGETS = {
    "id3": ("core/library", "io.github.nikitasud.latentjam.library.tags.Id3Fuzz"),
    "flac": ("core/library", "io.github.nikitasud.latentjam.library.tags.FlacFuzz"),
    "ogg": ("core/library", "io.github.nikitasud.latentjam.library.tags.OggFuzz"),
    "mp4": ("core/library", "io.github.nikitasud.latentjam.library.tags.Mp4Fuzz"),
    "backup": ("composeApp", "io.github.nikitasud.latentjam.app.BackupFuzz"),
    "m3u": ("composeApp", "io.github.nikitasud.latentjam.app.M3uFuzz"),
}

def replay_seeds(directory):
    """A replay must name input files; no operands would start fuzzing until the outer timeout."""
    seeds = sorted(path for path in directory.iterdir() if path.is_file())
    if not seeds:
        raise ValueError(f"Replay corpus has no seed files: {directory}")
    return seeds

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=TARGETS)
    parser.add_argument("--jazzer", required=True, type=Path, help="Extracted, pinned standalone Jazzer executable")
    parser.add_argument("--seconds", type=int, default=300)
    parser.add_argument("--replay", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 86400:
        parser.error("--seconds must be 1..86400")
    seeds = ROOT / "tools/fuzz/corpus" / args.target
    try:
        replay = replay_seeds(seeds) if args.replay else []
    except (OSError, ValueError) as error:
        parser.error(str(error))
    module, target = TARGETS[args.target]
    subprocess.run(["./gradlew", "-I", "tools/fuzz/classpath.init.gradle",
                    ":" + module.replace("/", ":") + ":exportFuzzClasspath", "--max-workers=2",
                    "--no-configuration-cache"], cwd=ROOT, check=True)
    classpath = (ROOT / module / "build/fuzz/classpath.txt").read_text()
    output = ROOT / "build/fuzz" / args.target
    corpus = output / "corpus"
    crashes = output / "crashes"
    corpus.mkdir(parents=True, exist_ok=True)
    crashes.mkdir(parents=True, exist_ok=True)
    generated = ROOT / "build/fuzz/generated"
    if module == "core/library":
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
        subprocess.run([java, "-cp", classpath,
                        "io.github.nikitasud.latentjam.library.tags.TagFuzzSeeds", str(generated)], check=True)
    command = [str(args.jazzer.resolve()), "--cp=" + classpath, "--target_class=" + target,
               "--instrumentation_includes=io.github.nikitasud.latentjam.**",
               "--jvm_args=-Xmx512m", "--reproducer_path=" + str(crashes),
               "-max_len=1048576", "-timeout=10", "-rss_limit_mb=2048",
               "-artifact_prefix=" + str(crashes) + os.sep]
    if args.replay:
        command += [str(path) for path in replay]
    else:
        command += ["-max_total_time=" + str(args.seconds), str(corpus), str(seeds)]
        if module == "core/library":
            command += [str(generated / args.target)]
    subprocess.run(command, cwd=ROOT, check=True, timeout=args.seconds + 120)

if __name__ == "__main__":
    main()

