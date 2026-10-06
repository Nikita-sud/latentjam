"""Build and replay the actual Kotlin continuation policy on prepared research data.

A separate build avoids mutating the frozen report runner or reusing its queue cache.
The bundle report's --src controls feature extraction, not the SMART runner build.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for name in ("benchmark", "source", "prepared", "assets", "seeds", "out"):
        ap.add_argument(f"--{name}", type=Path, required=True)
    ap.add_argument("--mode", choices=("cold", "history"), required=True)
    ap.add_argument("--length", type=int, default=20)
    ap.add_argument("--legacy", action="store_true", help="replay the unchanged default policy as a control")
    args = ap.parse_args()
    if args.length <= 0:
        ap.error("--length must be positive")
    args.out.mkdir(parents=True, exist_ok=True)
    output = args.out / "queues.tsv"
    if output.exists():
        ap.error("output already contains queues.tsv; use a new output directory")
    runner = (args.benchmark / "smart/src/Runner.kt").read_text()
    needle = "val result = SmartChain(snapshot, runtime).build("
    if runner.count(needle) != 1:
        raise ValueError("unsupported harness source; the SmartChain call is not unique")
    runner = runner.replace(needle, """val result = SmartChain(snapshot, runtime, tuning = ChainTuning(
                    continueAfterExhaustion = System.getProperty("diet.continue") == "true",
                )).build(""")
    runner_path = args.out / "Runner.kt"
    runner_path.write_text(runner)
    build = args.out / "build"
    subprocess.run([sys.executable, str(args.benchmark / "smart/build.py"), "--src", str(args.source),
                    "--runner", str(runner_path), "--out", str(build)], check=True)
    launch = json.loads((build / "launch.json").read_text())
    command = launch[:1] + [f"-Ddiet.continue={str(not args.legacy).lower()}"] + launch[1:] + [
        str(args.prepared.resolve()), str(args.assets.resolve()), args.mode, str(output.resolve()),
        "--seeds", str(args.seeds.resolve()), "--length", str(args.length), "--order", "journey",
    ]
    manifest = dict(command=command, policy="legacy" if args.legacy else "continueAfterExhaustion",
                    source_build_sha256=sha(build / "source-hashes.json"), seeds_sha256=sha(args.seeds),
                    models={name: sha(args.assets / name) for name in
                            ("predictor_state.onnx", "predictor_scorer_n100.onnx")},
                    prepared_inputs={name: sha(args.prepared / name) for name in
                                     ("audio.f32", "text.f32", "descriptor.f32", "energy.f32", "meta.tsv")
                                     if (args.prepared / name).exists()})
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    with (args.out / "run.log").open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(output)


if __name__ == "__main__":
    main()
