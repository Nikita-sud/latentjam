#!/usr/bin/env python3
"""Build the research probe from existing, pinned checkouts; never downloads dependencies."""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", required=True)
    parser.add_argument("--deps-prefix", required=True, help="Each checkout is PREFIX + dependency name")
    parser.add_argument("--android", action="store_true", help="Link the Android C++ runtime statically")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    dependencies = json.loads((here / "dependencies.json").read_text())
    includes = []
    for name, dependency in dependencies.items():
        directory = Path(args.deps_prefix + name)
        revision = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
        changes = subprocess.check_output(["git", "-C", str(directory), "status", "--porcelain"], text=True)
        if revision != dependency["commit"] or changes:
            raise ValueError("Expected a clean pinned checkout: " + str(directory))
        includes.extend(["-I", str(directory / "include")])
    command = [args.compiler, "-O3", "-std=c++17", "-ffp-contract=off"]
    if args.android:
        command.append("-static-libstdc++")
    command.extend([str(here / "qdtsne_probe.cpp"), *includes, "-o", str(args.output)])
    subprocess.run(command, check=True)
    print("Built", args.output)


if __name__ == "__main__":
    main()
