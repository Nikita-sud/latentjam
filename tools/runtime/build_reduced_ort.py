#!/usr/bin/env python3
"""Build a CPU-only ARM64 ORT AAR, retaining ONNX input and exception support.

Run with a Python environment containing onnx and the exact onnxruntime version
in gradle/libs.versions.toml. The source checkout must be the matching release.
Other ABIs and Java classes are retained from that release's stock AAR.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tomllib
import zipfile


REPO = Path(__file__).resolve().parents[2]


def run(command, **kwargs):
    print("Running:", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--stock-aar", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--native-build-dir", type=Path)
    parser.add_argument("--custom-op-library", type=Path, required=True,
                        help="libljq4 built for this host (core/ort-ops), to load the models that use it")
    args = parser.parse_args()
    import onnx
    import onnxruntime as ort
    from onnxruntime.tools.reduced_build_config_parser import parse_config

    version = tomllib.loads((REPO / "gradle/libs.versions.toml").read_text())["versions"]["onnxruntime"]
    if ort.__version__ != version:
        raise SystemExit(f"Conversion runtime {ort.__version__} differs from app runtime {version}")
    source = args.source.resolve()
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    tag_commit = subprocess.check_output(["git", "rev-parse", f"v{version}^{{commit}}"], cwd=source, text=True).strip()
    if source_commit != tag_commit:
        raise SystemExit(f"Source checkout must be exactly v{version}")
    run(["git", "diff", "--quiet", "HEAD", "--"], cwd=source)
    if args.stock_aar.name != f"onnxruntime-android-{version}.aar":
        raise SystemExit("Use the matching stock release AAR")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    models = sorted((REPO / "androidApp/src/main/assets/ml").glob("*.onnx"))
    if not models:
        raise SystemExit("No models found")
    model_hashes = {p.name: sha(p) for p in models}
    # Keep the application's original assets. Optimized models exist only here to
    # discover fused kernels that are absent from the source graph inventory.
    converted = output / "converted"
    run([sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort",
         models[0].parent, "--output_dir", converted, "--custom_op_library", args.custom_op_library.resolve(),
         "--optimization_style", "Fixed", "--target_platform", "arm",
         "--enable_type_reduction"])
    ops, _ = parse_config(str(converted / "required_operators_and_types.config"), False)
    ops.pop("latentjam", None)  # LatentJam's own operators live in libljq4, not in the runtime
    def nodes(graph):
        for node in graph.node:
            yield node
            for attribute in node.attribute:
                if attribute.type == onnx.AttributeProto.GRAPH:
                    yield from nodes(attribute.g)
                elif attribute.type == onnx.AttributeProto.GRAPHS:
                    for nested in attribute.graphs:
                        yield from nodes(nested)
    for path in models:
        model = onnx.load(path)
        versions = {o.domain or "ai.onnx": o.version for o in model.opset_import}
        for node in nodes(model.graph):
            domain = node.domain or "ai.onnx"
            if domain == "latentjam":  # LatentJam's own operators live in libljq4, not in the runtime
                continue
            ops.setdefault(domain, {}).setdefault(versions[domain], set()).add(node.op_type)
    config = output / "required-operators.config"
    config.write_text("# Original and ARM-optimized graphs; no type reduction.\n" + "\n".join(
        f"{domain};{opset};" + ",".join(sorted(names))
        for domain, versions in sorted(ops.items()) for opset, names in sorted(versions.items())
    ) + "\n")
    build = (args.native_build_dir or output / "native").resolve()
    command = [sys.executable, source / "tools/ci_build/build.py", "--build_dir", build,
        "--config", "Release", "--cmake_generator", "Ninja", "--android",
        "--android_sdk_path", args.sdk.resolve(), "--android_ndk_path", args.ndk.resolve(),
        "--android_abi", "arm64-v8a", "--android_api", "24", "--build_shared_lib",
        "--build_java", "--enable_lto", "--disable_ml_ops", "--include_ops_by_config", config,
        "--skip_tests", "--cmake_extra_defines", "onnxruntime_BUILD_UNIT_TESTS=OFF",
        "--parallel", str(args.jobs)]
    run(command, cwd=source)
    release = build / "Release"
    native = {f"jni/arm64-v8a/{name}": release / name
              for name in ("libonnxruntime.so", "libonnxruntime4j_jni.so")}
    if any(not p.is_file() for p in native.values()):
        raise SystemExit("Native build did not produce both libraries")
    # ELF symbols are not shipped by the stock AAR either; retain an unstripped
    # build for diagnostics and strip only the packaged copies.
    strip = next(args.ndk.glob("toolchains/llvm/prebuilt/*/bin/llvm-strip"))
    stripped = output / "stripped"
    stripped.mkdir(exist_ok=True)
    for entry, original in list(native.items()):
        path = stripped / original.name
        path.write_bytes(original.read_bytes())
        run([strip, "--strip-unneeded", path])
        native[entry] = path
    candidate_version = version + "-latentjam-arm64"
    artifact = output / "maven/com/microsoft/onnxruntime/onnxruntime-android" / candidate_version
    artifact.mkdir(parents=True, exist_ok=True)
    base_name = "onnxruntime-android-" + candidate_version
    aar = artifact / (base_name + ".aar")
    with zipfile.ZipFile(args.stock_aar) as original, zipfile.ZipFile(aar, "w", zipfile.ZIP_DEFLATED) as reduced:
        for entry in original.infolist():
            reduced.writestr(entry.filename, native[entry.filename].read_bytes()
                             if entry.filename in native else original.read(entry.filename))
    (artifact / (base_name + ".pom")).write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        '<groupId>com.microsoft.onnxruntime</groupId><artifactId>onnxruntime-android</artifactId>'
        f'<version>{candidate_version}</version><packaging>aar</packaging></project>\n')
    manifest = {"ort_version": version, "candidate_version": candidate_version,
        "source_commit": source_commit, "models": model_hashes, "operators_sha256": sha(config),
        "stock_aar_sha256": sha(args.stock_aar), "candidate_aar_sha256": sha(aar),
        "custom_abis": ["arm64-v8a"], "retained_stock_abis": ["armeabi-v7a", "x86", "x86_64"],
        "build_command": list(map(str, command)), "exceptions": True, "onnx_format": True,
        "native_bytes": {entry: path.stat().st_size for entry, path in native.items()}}
    (output / "maven/manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Candidate Maven repository:", output / "maven")
    print("Run the Android parity probe before using this candidate in a release.")


if __name__ == "__main__":
    main()
