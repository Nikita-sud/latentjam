"""Records the SMART nets' real inputs on the training libraries (cold and synthetic-history seeds).

    python record.py FEATURES_ROOT OUT_DIR [--pool 4]

Runs the app's chain (smart/ runner, baseline nets) with the worker's recorder on: every training
library prepared under FEATURES_ROOT, both seed files written by train_seeds.py. One .npz per run.
"""
import argparse
import concurrent.futures as cf
import json
import os
import subprocess
import time
from pathlib import Path

from paths import WORK as ROOT


def run(job):
    folder, mode, out = job
    env = dict(os.environ, SMART_DUMP=str(out), SMART_DUMP_LIBRARY=str(folder), OMP_NUM_THREADS="1",
               OPENBLAS_NUM_THREADS="1", MKL_NUM_THREADS="1")  # one core per worker, as the app runs it
    launch = json.loads((ROOT / "smart" / "launch.json").read_text())
    t0 = time.time()
    r = subprocess.run(["nice", "-n", "10", *launch, str(folder), str(ROOT / "baseline" / "ml"), mode,
                        str(out / f"{folder.name}.{mode}.tsv"), "--seeds", str(folder / f"train.{mode}.seeds"),
                        "--order", "journey", "--tz", "Europe/Amsterdam"],
                       env=env, capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(f"{folder.name} {mode}: {r.stderr[-800:]}")
    return f"{folder.name} {mode}: {time.time() - t0:.0f}s"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("features")
    ap.add_argument("out")
    ap.add_argument("--pool", type=int, default=4)
    args = ap.parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    jobs = [(f, m, out) for f in sorted(Path(args.features).resolve().glob("train_*")) for m in ("cold", "history")]
    with cf.ThreadPoolExecutor(args.pool) as ex:
        for line in ex.map(run, jobs):
            print(line, flush=True)


if __name__ == "__main__":
    main()
