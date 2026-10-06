"""Score replay_plans.py settings against 0.7.1 by a written protocol (sweep-2026-10-06/protocol.json).

    python sweep_eval.py --features <0.7.1 features root> --pool mpd=DIR,DIR --pool owner=DIR,DIR [--select] [--accept V ...]

Each DIR holds the new bundle's variants (replay_plans.py output); DIR-v071 holds 0.7.1's queues (v071.tsv) for the
same seeds. Metrics come from compare_plans.measures on fixed 0.7.1 rulers. Deltas are paired by seed within a pool,
with 95 % bootstrap intervals (2,000 resamples).

--select applies the selection rule: on every pool, skipped slots at most half of 0.7.1's, audio adjacency at least
0.7.1's minus 0.003, weak share at most 0.7.1's plus 0.003; then the largest mean playlist-share gain over the pools.
--accept applies the acceptance rule to the named variants: on every pool, playlist share delta lower bound >= -0.010,
skipped delta upper bound < 0, audio adjacency delta lower bound >= -0.005, weak share delta upper bound <= +0.005.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

import compare_plans as cp

METRICS = ("playlist", "playlist12", "skipped", "audio", "audio_join", "weak", "top_artist", "run", "cost")


def load_set(directory, features):
    manifest = json.loads((directory / "manifest.json").read_text())
    prepared = Path(next(iter(manifest["commands"].values()))[-8])
    audio, desc, meta, member = cp.ruler(features / prepared.name)
    files = {p.stem: p for p in directory.glob("*.tsv")}
    files["v071"] = directory.parent / f"{directory.name}-v071" / "v071.tsv"
    queues = {name: cp.read(path) for name, path in files.items() if path.exists()}
    keys = sorted(set.intersection(*(set(q) for q in queues.values())))
    return {name: {m: np.array([cp.measures(q[k], int(k.split("@")[0]), audio, desc, meta, member)[m] for k in keys], float)
                   for m in METRICS} for name, q in queues.items()}


def boot(d, rng):
    d = d[~np.isnan(d)]
    m = d[rng.integers(0, len(d), (2000, len(d)))].mean(1)
    return float(d.mean()), float(np.percentile(m, 2.5)), float(np.percentile(m, 97.5))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--features", type=Path, required=True)
    ap.add_argument("--pool", action="append", required=True, help="name=DIR,DIR,...")
    ap.add_argument("--select", action="store_true")
    ap.add_argument("--accept", nargs="*", default=[])
    ap.add_argument("--json", type=Path)
    a = ap.parse_args()
    rng = np.random.default_rng(0)
    pools = {}
    for spec in a.pool:
        name, dirs = spec.split("=", 1)
        merged = defaultdict(lambda: defaultdict(list))
        for d in dirs.split(","):
            for variant, metrics in load_set(Path(d), a.features).items():
                for m, values in metrics.items():
                    merged[variant][m].append(values)
        common = set.intersection(*(set(v) for v in merged.values())) if merged else set()
        pools[name] = {v: {m: np.concatenate(merged[v][m]) for m in METRICS} for v in merged
                       if len(merged[v]["playlist"]) == len(dirs.split(","))}
    variants = sorted(set.intersection(*(set(p) for p in pools.values())) - {"v071"})
    result = {}
    for name, pool in pools.items():
        ref = pool["v071"]
        print(f"\n== {name} ({len(ref['playlist'])} seeds): 0.7.1 playlist {np.nanmean(ref['playlist']) * 100:.2f} %, "
              f"skipped {np.nanmean(ref['skipped']):.2f}, audio {np.nanmean(ref['audio']):.4f}, weak {np.nanmean(ref['weak']) * 100:.2f} %")
        print(f"  {'variant':24s} {'playlist Δ pp [CI]':28s} {'skipped Δ':18s} {'audio Δ [CI]':26s} {'weak Δ pp':16s} {'top artist':10s} {'run':6s}")
        for v in variants:
            row = {m: boot(pool[v][m] - ref[m], rng) for m in METRICS}
            result.setdefault(v, {})[name] = row
            pl, sk, au, wk = row["playlist"], row["skipped"], row["audio"], row["weak"]
            print(f"  {v:24s} {pl[0] * 100:+6.2f} [{pl[1] * 100:+.2f},{pl[2] * 100:+.2f}]    {sk[0]:+6.2f} [{sk[2]:+.2f}]   "
                  f"{au[0]:+.4f} [{au[1]:+.4f},{au[2]:+.4f}]   {wk[0] * 100:+5.2f} [{wk[2] * 100:+.2f}]   "
                  f"{np.nanmean(pool[v]['top_artist']):5.2f}     {np.nanmean(pool[v]['run']):4.2f}")
    if a.select:
        print("\n== selection rule")
        ranked = []
        for v in variants:
            ok = all(
                np.nanmean(pools[p][v]["skipped"]) <= 0.5 * np.nanmean(pools[p]["v071"]["skipped"]) and
                np.nanmean(pools[p][v]["audio"]) >= np.nanmean(pools[p]["v071"]["audio"]) - 0.003 and
                np.nanmean(pools[p][v]["weak"]) <= np.nanmean(pools[p]["v071"]["weak"]) + 0.003
                for p in pools)
            gain = np.mean([np.nanmean(pools[p][v]["playlist"]) - np.nanmean(pools[p]["v071"]["playlist"]) for p in pools])
            ranked.append((ok, gain, v))
        for ok, gain, v in sorted(ranked, key=lambda r: (not r[0], -r[1])):
            print(f"  {'eligible ' if ok else 'excluded '} {v:24s} mean playlist gain {gain * 100:+.2f} pp")
    for v in a.accept:
        print(f"\n== acceptance: {v}")
        verdict = True
        for p in pools:
            r = result[v][p]
            checks = {"playlist >= -1 pp": r["playlist"][1] >= -0.010, "fewer skipped": r["skipped"][2] < 0,
                      "audio >= -0.005": r["audio"][1] >= -0.005, "weak <= +0.5 pp": r["weak"][2] <= 0.005}
            verdict &= all(checks.values())
            print(f"  {p}: " + ", ".join(f"{k} {'yes' if ok else 'NO'}" for k, ok in checks.items()) +
                  f"; playlist gain significant: {'yes' if r['playlist'][1] > 0 else 'no'}")
        print(f"  better by the protocol: {'YES' if verdict else 'NO'}")
    if a.json:
        a.json.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
