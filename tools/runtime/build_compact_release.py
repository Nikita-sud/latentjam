#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""Build checked release APKs with an explicit, model-matched ARM64 reduced ORT candidate."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def native_members(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate ZIP members: {path}")
        return {name: {"bytes": entry.file_size, "sha256": hashlib.sha256(archive.read(name)).hexdigest()}
                for name in names if name.startswith("jni/") and name.endswith(".so")
                for entry in [archive.getinfo(name)]}


def validate_candidate(root, repo, stock):
    manifest_path = repo / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    match = re.search(r'^onnxruntime\s*=\s*"([^"]+)"',
                      (root / "gradle/libs.versions.toml").read_text(), re.MULTILINE)
    if not match or match.group(1) != manifest["ort_version"]:
        raise ValueError("Reduced ORT version differs from the app")
    version = manifest["candidate_version"]
    if version != f'{manifest["ort_version"]}-latentjam-arm64':
        raise ValueError("Unexpected reduced ORT candidate version")
    model_dir = root / "androidApp/src/main/assets/ml"
    models = {path.name: sha(path) for path in sorted(model_dir.glob("*.onnx"))}
    if not models or models != manifest["models"]:
        raise ValueError("Reduced ORT must match the exact shipped model set and hashes")
    candidate = repo / f"com/microsoft/onnxruntime/onnxruntime-android/{version}/onnxruntime-android-{version}.aar"
    if sha(candidate) != manifest["candidate_aar_sha256"] or sha(stock) != manifest["stock_aar_sha256"]:
        raise ValueError("Candidate or stock AAR differs from the manifest")
    if manifest["custom_abis"] != ["arm64-v8a"] or set(manifest["retained_stock_abis"]) != {"armeabi-v7a", "x86", "x86_64"}:
        raise ValueError("Expected only ARM64 custom runtime; all other ABIs must retain stock")
    expected, original = native_members(candidate), native_members(stock)
    if expected.keys() != original.keys():
        raise ValueError("Candidate changed the native library inventory")
    for abi in ABIS:
        for library in ("libonnxruntime.so", "libonnxruntime4j_jni.so"):
            if f"jni/{abi}/{library}" not in expected:
                raise ValueError(f"Candidate lacks {abi}/{library}")
    for name in expected:
        if not name.startswith("jni/arm64-v8a/") and expected[name] != original[name]:
            raise ValueError(f"Non-ARM64 runtime must be stock: {name}")
    arm64 = "jni/arm64-v8a/libonnxruntime.so"
    if expected[arm64]["bytes"] >= original[arm64]["bytes"]:
        raise ValueError("ARM64 candidate is not smaller than stock; refusing a silently full runtime")
    for name, count in manifest["native_bytes"].items():
        if name not in expected or expected[name]["bytes"] != count:
            raise ValueError(f"Native size differs from manifest: {name}")
    return {"manifest": manifest, "manifest_sha256": sha(manifest_path),
            "candidate_aar": str(candidate), "candidate_aar_sha256": sha(candidate),
            "stock_aar": str(stock), "stock_aar_sha256": sha(stock), "native": expected}



def packaged_native(candidate, strip_tool):
    """Reproduce AGP's native stripping on disposable copies; never mutate the candidate AAR."""
    result = {"tool": str(strip_tool), "tool_sha256": sha(strip_tool),
              "tool_version": subprocess.check_output([str(strip_tool), "--version"], text=True).strip(),
              "commands": [], "native": {}}
    with zipfile.ZipFile(candidate) as archive, tempfile.TemporaryDirectory(prefix="latentjam-ort-strip-") as temporary:
        for name in sorted(native_members(candidate)):
            # Only generated paths below our temporary directory, never archive-controlled paths.
            copy = Path(temporary) / (str(len(result["commands"])) + ".so")
            copy.write_bytes(archive.read(name))
            command = [str(strip_tool), "--strip-unneeded", str(copy)]
            subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            result["commands"].append({"member": name, "argv": command})
            result["native"][name] = {"bytes": copy.stat().st_size, "sha256": sha(copy)}
    return result


def verify_runtime(apk, abi, expected):
    """Check the packaged bytes, even if Gradle succeeded using a different dependency."""
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate APK members: {apk}")
        packaged_abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
        if packaged_abis != {abi}:
            raise ValueError(f"Expected one {abi} split, got {packaged_abis}: {apk}")
        for source, wanted in expected.items():
            if not source.startswith(f"jni/{abi}/"):
                continue
            target = source.replace("jni/", "lib/", 1)
            if target not in names or hashlib.sha256(archive.read(target)).hexdigest() != wanted["sha256"]:
                raise ValueError(f"Packaged ORT does not match the checked candidate: {apk.name}: {target}")


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def source_identity(root):
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=root)
    return {"commit": git("rev-parse", "HEAD").decode().strip(),
            "status": git("status", "--porcelain=v1", "--untracked-files=all").decode(),
            "tracked_diff_sha256": hashlib.sha256(git("diff", "--binary", "HEAD")).hexdigest(),
            "untracked_sha256": {name: sha(root / name) for name in
                                  git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0") if name}}


def validate_source(identity, allow_dirty=False):
    if identity["status"] and not allow_dirty:
        raise ValueError("Source tree has local changes; commit the candidate first, or use --allow-dirty for a development build")


def validate_strip_tool(root, tool):
    # llvm-strip can be a symlink to llvm-objcopy; validate its owning NDK,
    # rather than rejecting a legitimate resolved executable name.
    versions = set()
    for module in ("audio-dsp", "map-native", "ort-ops"):
        text = (root / f"core/{module}/build.gradle.kts").read_text()
        match = re.search(r'ndkVersion\s*=\s*"([^"]+)"', text)
        if not match:
            raise ValueError(f"Cannot determine NDK version for {module}")
        versions.add(match.group(1))
    if len(versions) != 1:
        raise ValueError("Native modules use different NDK versions")
    properties = next((p / "source.properties" for p in tool.resolve().parents
                       if (p / "source.properties").is_file()), None)
    match = re.search(r'^Pkg.Revision\s*=\s*(\S+)\s*$', properties.read_text(), re.MULTILINE) if properties else None
    if not match or match.group(1) not in versions:
        raise ValueError(f"Strip tool must belong to the app's NDK {next(iter(versions))}")
    return match.group(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reduced-repo", required=True, type=Path, help="Local Maven directory containing manifest.json")
    parser.add_argument("--stock-aar", required=True, type=Path, help="Original AAR identified by manifest.stock_aar_sha256")
    parser.add_argument("--strip-tool", required=True, type=Path, help="llvm-strip from the same NDK used by AGP (currently 28.2.13676358)")
    parser.add_argument("--output", type=Path, default=Path("build/reports/compact-release"))
    parser.add_argument("--max-workers", type=int, default=2)
    parser.add_argument("--offline", action="store_true", help="Pass --offline to Gradle; wrapper never downloads a runtime")
    parser.add_argument("--check-only", action="store_true", help="Validate inputs and record the command, without building APKs")
    parser.add_argument("--allow-dirty", action="store_true", help="Explicit development build; result cannot receive release status 'verified'")
    args = parser.parse_args()
    if args.max_workers < 1:
        parser.error("--max-workers must be positive")
    root = Path(__file__).resolve().parents[2]
    repo, stock = args.reduced_repo.resolve(), args.stock_aar.resolve()
    strip_tool = args.strip_tool.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / "provenance.json"
    report = {"status": "failed", "started_utc": datetime.now(timezone.utc).isoformat(),
              "wrapper_argv": [sys.executable, *sys.argv], "project": str(root), "apks": []}
    try:
        report["source"] = source_identity(root)
        validate_source(report["source"], args.allow_dirty)
        report["ndk_version"] = validate_strip_tool(root, strip_tool)
        report["runtime"] = validate_candidate(root, repo, stock)
        report["native_packaging"] = packaged_native(Path(report["runtime"]["candidate_aar"]), strip_tool)
        init = root / "tools/runtime/use-reduced-ort.init.gradle"
        report["init_script_sha256"] = sha(init)
        command = [str(root / "gradlew"), ":androidApp:assembleRelease", ":androidApp:checkReleaseDexRegisters",
                   ":androidApp:checkReleaseNativeLibraries", "--no-configuration-cache",
                   f"--max-workers={args.max_workers}", "-I", str(init), f"-Platentjam.reducedOrtRepo={repo}"]
        if args.offline:
            command.append("--offline")
        report["gradle_argv"] = command
        print(json.dumps({"gradle_argv": command, "output": str(output)}, indent=2), flush=True)
        if args.check_only:
            report["status"] = "preflight_passed_no_build"
            return 0
        with (output / "gradle.log").open("w") as log:
            result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        report["gradle_exit_code"] = result.returncode
        if result.returncode:
            raise ValueError(f"Gradle failed with {result.returncode}; see {output / 'gradle.log'}")
        # Inputs must remain the ones whose hashes were recorded at the beginning of this run.
        if (validate_candidate(root, repo, stock) != report["runtime"] or
                sha(init) != report["init_script_sha256"] or
                sha(strip_tool) != report["native_packaging"]["tool_sha256"]):
            raise ValueError("Runtime inputs or init script changed during the build")
        resolver = module(root / "tools/release/resolve-apk.py", "compact_resolver")
        inventory = module(root / "tools/ci/apk_inventory.py", "compact_inventory")
        for abi in ABIS:
            apk = resolver.resolve(root, "release", abi)
            verify_runtime(apk, abi, report["native_packaging"]["native"])
            item = inventory.inspect(apk)  # Required custom libraries plus ELF/ZIP 16 KiB gates.
            item.update({"path": str(apk), "abi": abi, "mib": item["bytes"] / 1048576,
                         "runtime": "reduced" if abi == "arm64-v8a" else "stock"})
            report["apks"].append(item)
            print(f'{abi}: {item["bytes"]} bytes ({item["mib"]:.2f} MiB), SHA256 {item["sha256"]}', flush=True)
        report["source_after"] = source_identity(root)
        if report["source_after"] != report["source"]:
            raise ValueError("Source files changed during the build; repeat for attributable APKs")
        report["status"] = "verified_with_local_changes" if report["source"]["status"] else "verified"
        report["gates"] = ["Gradle release/DEX/native", "packaged runtime SHA256 after NDK stripping", "ELF/ZIP 16 KiB"]
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        report["error"] = str(error)
        print(f"Compact release failed: {error}", file=sys.stderr)
        return 1
    finally:
        report["finished_utc"] = datetime.now(timezone.utc).isoformat()
        temporary = report_path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(report, indent=2) + "\n")
        temporary.replace(report_path)
        print(f"Provenance: {report_path}", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
