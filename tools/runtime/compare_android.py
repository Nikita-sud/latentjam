#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare ORT AARs through Android JNI without installing/replacing the app."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import zipfile
import uuid
import numpy as np

ROOT = Path(__file__).resolve().parents[2]

def run(args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, text=True, **kwargs)

def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def read_tensor(path):
    raw = path.read_bytes()
    shape = json.loads(path.with_suffix(".shape.json").read_text())
    if not isinstance(shape, list) or any(type(d) is not int or d < 0 for d in shape):
        raise ValueError("Output shape must contain concrete nonnegative dimensions")
    if len(raw) % 4:
        raise ValueError("Output byte count is not a whole number of Float32 values")
    values = np.frombuffer(raw, dtype="<f4")
    if math.prod(shape) != values.size:
        raise ValueError(f"Shape {shape} requires {math.prod(shape)} floats, found {values.size}")
    if not np.isfinite(values).all():
        raise ValueError("Output contains NaN or infinity")
    return shape, values, raw


def compare_tensors(baseline, candidate):
    shape_a, a, raw_a = read_tensor(baseline)
    shape_b, b, raw_b = read_tensor(candidate)
    if shape_a != shape_b:
        raise ValueError(f"Output shape changed: {shape_a} -> {shape_b}")
    if a.size != b.size:
        raise ValueError(f"Output float count changed: {a.size} -> {b.size}")
    delta = np.abs(a.astype(np.float64) - b.astype(np.float64))
    return {"shape": shape_a, "floats": int(a.size), "finite": True,
            "bitwise_equal": raw_a == raw_b,
            "max_abs_error": float(delta.max(initial=0)),
            "rms_error": float(np.sqrt(np.mean(delta ** 2))) if delta.size else 0.0}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--candidate", type=Path, required=True)
    p.add_argument("--custom-ops", type=Path, required=True)
    p.add_argument("--sdk", type=Path, required=True)
    p.add_argument("--serial", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--xnnpack", action="store_true")
    p.add_argument("--require-bitwise", action="store_true",
                   help="Fail if any candidate output differs bitwise, including XNNPACK if requested")
    p.add_argument("--audio", type=Path, action="append", default=[],
                   help="Repeat for local audio excerpts passed to prepare_parity_fixtures.py")
    p.add_argument("--listener-vectors", type=Path,
                   help="Optional directory containing audio.f32 library embeddings")
    args = p.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sdk = args.sdk.resolve()
    adb = [sdk / "platform-tools/adb", "-s", args.serial]
    abi = run([*adb, "shell", "getprop", "ro.product.cpu.abi"], capture_output=True).stdout.strip()
    if abi != "arm64-v8a":
        raise SystemExit("This probe uses ARM64 custom ops; test other ABIs separately.")
    # A fresh destination prevents reruns from using stale native libraries or output files.
    identity = hashlib.sha256((digest(args.baseline) + digest(args.candidate)).encode()).hexdigest()[:12]
    remote = "/data/local/tmp/latentjam-ort-" + identity + "-" + uuid.uuid4().hex[:8]
    prepare = [sys.executable, ROOT / "tools/runtime/prepare_parity_fixtures.py",
               "--output", out, "--stock-aar", args.baseline.resolve()]
    for audio in args.audio:
        prepare.extend(["--audio", audio.resolve()])
    if args.listener_vectors:
        prepare.extend(["--listener-vectors", args.listener_vectors.resolve()])
    run(prepare)
    shutil.copy2(args.custom_ops, out / "device/libljq4.so")
    run([*adb, "push", out / "device", remote], stdout=subprocess.DEVNULL)
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    report = {"device": args.serial, "abi": abi, "remote_directory": remote, "custom_ops_sha256": digest(args.custom_ops),
              "models": {f.name: digest(f) for f in (out / "device/models").glob("*.onnx")},
              "fixtures": json.loads((out / "probe-cases.json").read_text()),
              "require_bitwise": args.require_bitwise, "runs": {}}
    jobs = [("baseline", args.baseline, "cpu"), ("candidate", args.candidate, "cpu")]
    if args.xnnpack:
        jobs.append(("candidate-xnnpack", args.candidate, "xnnpack"))
    for name, aar, provider in jobs:
        folder = out / name
        folder.mkdir(exist_ok=True)
        native = folder / "native"
        native.mkdir(exist_ok=True)
        classes = folder / "classes.jar"
        with zipfile.ZipFile(aar) as z:
            classes.write_bytes(z.read("classes.jar"))
            for entry in z.namelist():
                if entry.startswith("jni/arm64-v8a/") and entry.endswith(".so"):
                    (native / Path(entry).name).write_bytes(z.read(entry))
        run([java / "javac", "-cp", classes, "-d", folder, ROOT / "tools/runtime/OrtParity.java"])
        probe = folder / "probe.jar"
        run([sdk / "build-tools/36.0.0/d8", "--min-api", "24", "--lib",
             sdk / "platforms/android-36/android.jar", "--output", probe,
             classes, *sorted(folder.glob("*.class"))])
        run([*adb, "push", folder, remote + "/" + name], stdout=subprocess.DEVNULL)
        native_remote = remote + "/" + name + "/native"
        command = ["env", "CLASSPATH=" + remote + "/" + name + "/probe.jar",
                   "LD_LIBRARY_PATH=" + native_remote, "app_process",
                   "-Djava.library.path=" + native_remote, "/system/bin", "OrtParity",
                   remote, remote + "/results-" + name, provider]
        with (folder / "probe.log").open("w") as log:
            process = subprocess.run([*map(str, adb), "shell", shlex.join(command)],
                                     text=True, stdout=log, stderr=subprocess.STDOUT, timeout=900)
        record = {"aar_sha256": digest(aar), "provider": provider, "exit_code": process.returncode}
        report["runs"][name] = record
        if process.returncode:
            (out / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
            if name == "baseline":
                raise SystemExit("Baseline failed: " + str(folder / "probe.log"))
            continue
        run([*adb, "pull", remote + "/results-" + name, folder / "outputs"], stdout=subprocess.DEVNULL)
    baseline = out / "baseline/outputs"
    expected = {f.name for f in baseline.glob("*.f32")}
    if not expected:
        raise SystemExit("No output tensors")
    errors = []
    for name, _, _ in jobs[1:]:
        if report["runs"][name]["exit_code"]:
            continue
        folder = out / name / "outputs"
        actual = {f.name for f in folder.glob("*.f32")}
        if actual != expected:
            errors.append(f"Output tensor set changed: {name}; missing={sorted(expected-actual)}; extra={sorted(actual-expected)}")
        comparisons = {}
        for file in sorted(expected & actual):
            try:
                comparison = compare_tensors(baseline / file, folder / file)
                comparisons[file] = comparison
                if args.require_bitwise and not comparison["bitwise_equal"]:
                    errors.append(f"Bitwise mismatch: {name}/{file}")
            except (OSError, ValueError) as failure:
                comparisons[file] = {"validation_error": str(failure)}
                errors.append(f"Invalid tensor: {name}/{file}: {failure}")
        report["runs"][name]["outputs"] = comparisons
    report["validation_errors"] = errors
    report["passed"] = not errors and not any(r["exit_code"] for r in report["runs"].values())
    # Numerical equivalence is evidence, not automatic approval of a runtime upgrade.
    report["adoption"] = "experimental; iOS, physical-device memory/performance and network checks required"
    (out / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print(out / "comparison.json")
    if not report["passed"]:
        raise SystemExit("Candidate rejected: see per-run logs and comparison.json")

if __name__ == "__main__":
    main()
