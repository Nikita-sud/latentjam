#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""Render and check LatentJam's frozen F-Droid recipe (tools/release/fdroid/recipe.yml).

  fdroid_recipe.py render --commit HASH --base METADATA.yml --out DIR
      fdroiddata files for this tree's version: METADATA.yml (fdroiddata master's file) plus the four build blocks
      for this commit, CurrentVersion/CurrentVersionCode, and srclibs/onnxruntime.yml when missing. Written through
      fdroidserver's own writer, so `fdroid rewritemeta` leaves them unchanged.
  fdroid_recipe.py check [--master METADATA.yml]
      fails (exit 1) when F-Droid's scanner would reject a block on a `git archive` export of HEAD, when the recipe's
      pins differ from the tree (ONNX Runtime srclib tag, NDK), or when fdroidserver reads another version from the
      tree; exits 3 when the recipe differs from the last four blocks on fdroiddata master, i.e. this release needs
      an fdroiddata MR (fetched from GitLab unless --master gives the file).

Needs fdroidserver (master, as fdroiddata's CI installs it): ~/.cache/latentjam/fdroidserver-venv/bin/python.
"""
import argparse
import copy
import io
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RECIPE = Path(__file__).resolve().parent / "fdroid" / "recipe.yml"
MASTER_URL = "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/{appid}.yml"
# Fields checkupdates changes when it copies the blocks; everything else must stay identical.
PER_RELEASE = {"versionName", "versionCode", "commit", "disable"}
NDK_MODULES = ["core/ort-ops", "core/map-native", "core/audio-dsp"]

try:
    import ruamel.yaml
    from fdroidserver import common, metadata, scanner
except ImportError as error:  # pragma: no cover
    sys.exit(f"fdroid_recipe.py needs fdroidserver ({error}); use ~/.cache/latentjam/fdroidserver-venv/bin/python")


def load_recipe(path=RECIPE):
    # YAML 1.2 as fdroidserver reads metadata: `yes` stays the string Gradle flavour "yes".
    return ruamel.yaml.YAML(typ="safe", pure=True).load(Path(path).read_text())


def dump_yaml(data):
    stream = io.StringIO()
    writer = ruamel.yaml.YAML(typ="safe", pure=True)
    writer.default_flow_style = False
    writer.sort_base_mapping_type_on_output = False
    writer.dump(data, stream)
    return stream.getvalue()


def tree_version(root):
    """(versionName, base versionCode) as fdroidserver's checkupdates reads them from androidApp."""
    app = metadata.App()
    app.id = load_recipe()["appid"]
    paths = common.manifest_paths(str(Path(root, "androidApp")), ["yes"])
    name, code, _package = common.parse_androidmanifests(paths, app)
    return name, code


def tree_pins(root):
    ort = re.search(r'^onnxruntime = "([^"]+)"', Path(root, "gradle/libs.versions.toml").read_text(), re.M).group(1)
    ndks = {m: re.search(r'ndkVersion = "([^"]+)"', Path(root, m, "build.gradle.kts").read_text()).group(1)
            for m in NDK_MODULES}
    return ort, ndks


def pin_problems(recipe, root):
    """Check (5): what the recipe pins must be what the tree builds with."""
    ort, ndks = tree_pins(root)
    problems = []
    for module, ndk in ndks.items():
        if ndk != recipe["ndk_version"]:
            problems.append(f"{module} uses NDK {ndk}, the recipe {recipe['ndk_version']}: update recipe.yml (ndk:, "
                            f"ndk_version), then this release needs an fdroiddata MR")
    for build in recipe["builds"]:
        for srclib in build.get("srclibs", []):
            name, ref = srclib.split("@", 1)
            if name == "onnxruntime" and ref != f"v{ort}":
                problems.append(f"the recipe builds ORT {ref}, the app uses v{ort}: update recipe.yml, then this "
                                f"release needs an fdroiddata MR")
    return problems


def builds_for(recipe, version_name, base_code, commit):
    builds = []
    for digit, block in enumerate(recipe["builds"], start=1):
        build = {"versionName": version_name, "versionCode": base_code * 10 + digit, "commit": commit}
        build.update(copy.deepcopy(block))
        builds.append(build)
    return builds


def normalised(app_yaml_text):
    """An app's metadata as fdroidserver reads it (its own defaults and types), from YAML text."""
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp, "metadata", "app.yml")
        path.parent.mkdir()
        path.write_text(app_yaml_text)
        return metadata.parse_metadata(path)


def recipe_builds(recipe, version_name="0", base_code=1, commit="0" * 40):
    """The recipe's blocks as fdroidserver's Build objects (its defaults and types)."""
    return normalised(dump_yaml({"Builds": builds_for(recipe, version_name, base_code, commit)}))["Builds"]


def frozen_part(build):
    return {k: v for k, v in dict(build).items() if k not in PER_RELEASE}


def recipe_vs_master(recipe, master_text):
    """Check (3): differences that the bot would not carry over (an MR is needed when any exist)."""
    master = normalised(master_text)
    ours = normalised(dump_yaml({"Builds": builds_for(recipe, "0", 1, "0" * 40), **recipe["app"]}))
    differences = []
    last = master.get("Builds", [])[-len(recipe["builds"]):]
    if len(last) != len(recipe["builds"]):
        differences.append("fdroiddata master has fewer build blocks than the recipe")
    for index, (theirs, mine) in enumerate(zip(last, ours["Builds"])):
        a, b = frozen_part(theirs), frozen_part(mine)
        for key in sorted(set(a) | set(b)):
            if a.get(key) != b.get(key):
                differences.append(f"block {index + 1} {key}: master {a.get(key)!r}, recipe {b.get(key)!r}")
    for key, value in recipe["app"].items():
        if master.get(key) != ours.get(key):
            differences.append(f"{key}: master {master.get(key)!r}, recipe {ours.get(key)!r}")
    for key in ("UpdateCheckData", "Disabled", "NoSourceSince"):
        if master.get(key):
            differences.append(f"{key} is set on master ({master.get(key)!r}): the bot will not update")
    return differences


def export_head(root, destination):
    """`git archive HEAD` of root, unpacked into destination: what F-Droid clones, without ignored build outputs."""
    data = subprocess.run(["git", "-C", str(root), "archive", "--format=tar", "HEAD"], check=True,
                          capture_output=True).stdout
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        archive.extractall(destination, filter="data")


def model_prebuild(tree, block):
    """What a block's prebuild leaves in the scanned tree: the reduced ORT repository for the arm64 block."""
    if "build/reduced-ort/maven" in block.get("scanignore", []):
        aar = Path(tree, "build/reduced-ort/maven/com/microsoft/onnxruntime/onnxruntime-android/x/placeholder.aar")
        aar.parent.mkdir(parents=True)
        with zipfile.ZipFile(aar, "w") as archive:
            archive.writestr("classes.jar", b"placeholder")


def scan_problems(tree, block):
    """Checks (1) and (2): fdroidserver's own scanner on the tree, as `fdroid build` runs it after prebuild."""
    build = block
    log = io.StringIO()
    handler = __import__("logging").StreamHandler(log)
    logger = __import__("logging").getLogger()
    logger.addHandler(handler)
    try:
        count = scanner.scan_source(str(tree), build)
    finally:
        logger.removeHandler(handler)
    errors = [line for line in log.getvalue().splitlines()
              if line.startswith(("Found ", "Non-exist", "Unused")) or "path:" in line]
    return count, errors


def check(root, master_text):
    recipe = load_recipe()
    failures = pin_problems(recipe, root)
    name, code = tree_version(root)
    gradle = Path(root, "androidApp/build.gradle.kts").read_text()
    expected_name = re.search(r'versionName = "([^"]+)"', gradle).group(1)
    expected_code = int(re.search(r"versionCode = (\d+)", gradle).group(1))
    if (name, code) != (expected_name, expected_code):
        failures.append(f"fdroidserver reads {name} ({code}) from the tree, build.gradle.kts says "
                        f"{expected_name} ({expected_code})")
    with tempfile.TemporaryDirectory() as tmp:
        clean = Path(tmp, "clean")
        export_head(root, clean)
        for digit, block in enumerate(recipe_builds(recipe), start=1):
            tree = Path(tmp, f"block{digit}")
            subprocess.run(["cp", "-R", str(clean), str(tree)], check=True)
            model_prebuild(tree, block)
            count, errors = scan_problems(tree, block)
            if count:
                failures.append(f"block {digit} ({block['gradleprops'][-1]}): the scanner reports {count} "
                                f"problem(s): " + "; ".join(errors[:10]))
    differences = recipe_vs_master(recipe, master_text) if master_text is not None else []
    return failures, differences


def render(root, commit, base_path, out_dir):
    recipe = load_recipe()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise SystemExit("--commit takes a full commit hash (peel a tag with ^{commit})")
    name, code = tree_version(root)
    out = Path(out_dir)
    target = out / "metadata" / f"{recipe['appid']}.yml"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(Path(base_path).read_text())
    app = metadata.parse_metadata(target)
    builds = [b for b in app.get("Builds", []) if b.versionCode // 10 != code and b.versionCode != code]
    builds.extend(recipe_builds(recipe, name, code, commit))
    app["Builds"] = builds
    for key, value in recipe["app"].items():
        app[key] = value
    app["CurrentVersion"] = name
    app["CurrentVersionCode"] = code * 10 + len(recipe["builds"])
    metadata.write_metadata(target, app)
    for srclib, fields in recipe["srclibs"].items():
        path = out / "srclibs" / f"{srclib}.yml"
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(dump_yaml(fields))
    return target


def master_metadata(appid, path):
    if path:
        return Path(path).read_text()
    with urllib.request.urlopen(MASTER_URL.format(appid=appid), timeout=60) as response:
        return response.read().decode()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("render")
    r.add_argument("--commit", required=True)
    r.add_argument("--base", required=True, help="fdroiddata master's metadata file for the app")
    r.add_argument("--out", required=True, help="an fdroiddata checkout (or a directory laid out like one)")
    c = sub.add_parser("check")
    c.add_argument("--master", help="fdroiddata master's metadata file (default: fetched from GitLab)")
    c.add_argument("--offline", action="store_true", help="skip the comparison with fdroiddata master")
    args = parser.parse_args(argv)
    common.config = common.read_config() if Path("config.yml").exists() else {}
    common.options = argparse.Namespace(verbose=False, json=False, refresh_scanner=False)
    if args.command == "render":
        print(render(ROOT, args.commit, args.base, args.out))
        return 0
    recipe = load_recipe()
    master = None if args.offline else master_metadata(recipe["appid"], args.master)
    failures, differences = check(ROOT, master)
    for failure in failures:
        print(f"FAIL: {failure}")
    for difference in differences:
        print(f"MR NEEDED: {difference}")
    if failures:
        return 1
    if differences:
        return 3
    print("frozen F-Droid recipe: OK, fdroiddata master matches; the bot ships this release without an MR"
          if master is not None else "frozen F-Droid recipe: OK (not compared with fdroiddata master)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
