#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare official ORT AARs through Android JNI without installing/replacing the app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import zipfile
import numpy as np

ROOT = Path(__file__).resolve().parents[2]

def run(args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, text=True, **kwargs)

def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--candidate", type=Path, required=True)
    p.add_argument("--custom-ops", type=Path, required=True)
    p.add_argument("--sdk", type=Path, required=True)
    p.add_argument("--serial", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--xnnpack", action="store_true")
    args = p.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    sdk = args.sdk.resolve()
    adb = [sdk / "platform-tools/adb", "-s", args.serial]
    abi = run([*adb, "shell", "getprop", "ro.product.cpu.abi"], capture_output=True).stdout.strip()
    if abi != "arm64-v8a":
        raise SystemExit("This probe uses ARM64 custom ops; test other ABIs separately.")
    # Content-derived destination avoids overwriting another probe or user data.
    identity = hashlib.sha256((digest(args.baseline) + digest(args.candidate)).encode()).hexdigest()[:12]
    remote = "/data/local/tmp/latentjam-ort-" + identity
    run([sys.executable, ROOT / "tools/runtime/prepare_parity_fixtures.py",
         "--output", out, "--stock-aar", args.baseline.resolve()])
    shutil.copy2(args.custom_ops, out / "device/libljq4.so")
    run([*adb, "push", out / "device", remote], stdout=subprocess.DEVNULL)
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    report = {"device": args.serial, "abi": abi, "custom_ops_sha256": digest(args.custom_ops),
              "models": {f.name: digest(f) for f in (out / "device/models").glob("*.onnx")},
              "fixtures": json.loads((out / "probe-cases.json").read_text()), "runs": {}}
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
    for name, _, _ in jobs[1:]:
        if report["runs"][name]["exit_code"]:
            continue
        folder = out / name / "outputs"
        if {f.name for f in folder.glob("*.f32")} != expected:
            raise SystemExit("Output tensor set changed: " + name)
        comparisons = {}
        for file in sorted(expected):
            a, b = np.fromfile(baseline / file, "<f4"), np.fromfile(folder / file, "<f4")
            if a.shape != b.shape or not np.isfinite(a).all() or not np.isfinite(b).all():
                raise SystemExit("Invalid tensor: " + file)
            delta = np.abs(a.astype(np.float64) - b)
            comparisons[file] = {"floats": int(a.size), "bitwise_equal": (baseline / file).read_bytes() == (folder / file).read_bytes(),
                                 "max_abs_error": float(delta.max(initial=0)),
                                 "rms_error": float(np.sqrt(np.mean(delta ** 2)))}
        report["runs"][name]["outputs"] = comparisons
    # Numerical equivalence is evidence, not automatic approval of a runtime upgrade.
    report["adoption"] = "experimental; iOS, physical-device memory/performance and network checks required"
    (out / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print(out / "comparison.json")
    if any(r["exit_code"] for r in report["runs"].values()):
        raise SystemExit("Candidate rejected: see per-run logs and comparison.json")

if __name__ == "__main__":
    main()
