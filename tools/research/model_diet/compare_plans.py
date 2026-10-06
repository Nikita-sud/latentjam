"""Compare replay_plans.py variants: does a queue keep to the pick's neighbourhood, and at what cost?

    python compare_plans.py --features <baseline features root> RUN_DIR [RUN_DIR ...]

RUN_DIR is a replay_plans.py output (off.tsv, continue.tsv, carry.tsv; the library is read from its
manifest). Rulers are fixed across variants: 0.7.1's audio and artist-descriptor vectors, centred and
unit length, from --features (the bundle report's baseline feature folders), and the library's playlists.

Per queue:
- skipped: plan slots given to tracks outside the pick's neighbourhood while suitable tracks remained
  (per plan, max(0, min(suitable, length) - close)); suitable and close are the chain's own criterion
  as replay_plans.py records it;
- heard: tracks of the pick's neighbourhood that the queue plays;
- cost: the audit's transition cost, mean squared 1 - (0.65 audio cosine + 0.35 descriptor cosine)
  over adjacent pairs from the pick on, also only at the first track of each later plan;
- audio: mean adjacent audio cosine (audio_join: only into each later plan's first track); descriptor:
  mean adjacent artist-descriptor cosine; weak: share of adjacent audio pairs under 0.2;
- playlist: share of tracks sharing a playlist with the pick, first 12 and all;
- artists: distinct artists per track, the most tracks by one artist, the longest run of one artist.
Differences are paired by seed, with 95 % bootstrap intervals (2,000 resamples).
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

VARIANTS = ("off", "continue", "carry", "join")


def unit(m):
    m = m - m.mean(0)
    return m / np.maximum(np.linalg.norm(m, axis=1, keepdims=True), 1e-12)


def ruler(folder):
    rows = json.loads((folder / "rows.json").read_text())
    n = rows["n"]
    audio = unit(np.fromfile(folder / "audio.f32", "<f4").reshape(n, 960).astype(np.float64))
    desc = np.fromfile(folder / "descriptor.f32", "<f4").reshape(n, -1).astype(np.float64)
    desc = unit(desc)
    meta = [line.rstrip("\n").split("\t") for line in open(folder / "meta.tsv")]
    member = defaultdict(set)
    for g, group in enumerate(rows["groups"]):
        for r in group:
            member[r].add(g)
    return audio, desc, meta, member


def read(path):
    out = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        key, played, bounds, intents, flags, plans, hood = line.split("\t")
        out[key] = dict(
            played=[int(x) for x in played.split(",") if x],
            bounds=[int(x) for x in bounds.split(",") if x],
            flags=[c == "1" for c in flags],
            plans=[tuple(int(v) for v in p.split("/")) for p in plans.split(";") if p],
            hood=int(hood),
        )
    return out


def measures(q, seed, audio, desc, meta, member):
    path = np.array([seed] + q["played"])
    # Row-wise dot products; Accelerate's matmul raises spurious FP flags on these float64 rows.
    a = np.einsum("ij,ij->i", audio[path[:-1]], audio[path[1:]])
    d = np.einsum("ij,ij->i", desc[path[:-1]], desc[path[1:]])
    cost = (1 - (0.65 * a + 0.35 * d)) ** 2
    starts = [b for b in q["bounds"][:-1]]  # index into played of each later plan's first track
    artists = [meta[r][1].strip().lower() for r in q["played"]]
    named = [x for x in artists if x]
    counts = defaultdict(int)
    for x in named:
        counts[x] += 1
    run = best = 0
    for i, x in enumerate(artists):
        run = run + 1 if i and x and x == artists[i - 1] else 1
        best = max(best, run)
    hits = [bool(member[seed] & member[r]) for r in q["played"]] if member[seed] else None
    return dict(
        skipped=sum(max(0, min(left, n) - close) for left, close, n in q["plans"]),
        skipped_later=sum(max(0, min(left, n) - close) for left, close, n in q["plans"][1:]),
        heard=sum(q["flags"]),
        cost=cost.mean(),
        cost_join=cost[starts].mean() if starts else np.nan,
        audio=a.mean(),
        audio_join=a[starts].mean() if starts else np.nan,
        descriptor=d.mean(),
        weak=(a < 0.2).mean(),
        playlist12=np.mean(hits[:12]) if hits else np.nan,
        playlist=np.mean(hits) if hits else np.nan,
        distinct=len(set(named)) / max(len(q["played"]), 1),
        top_artist=max(counts.values()) if counts else 0,
        run=best,
    )


def boot(diff, rng):
    diff = np.asarray(diff, float)
    diff = diff[~np.isnan(diff)]
    if len(diff) < 2:
        return np.nan, np.nan, np.nan
    m = diff[rng.integers(0, len(diff), (2000, len(diff)))].mean(1)
    return diff.mean(), np.percentile(m, 2.5), np.percentile(m, 97.5)


PAIRS = (("join", "off"), ("join", "continue"), ("join", "carry"), ("carry", "off"), ("carry", "continue"))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--features", type=Path, required=True)
    ap.add_argument("--json", type=Path)
    ap.add_argument("runs", type=Path, nargs="+")
    a = ap.parse_args()
    rng = np.random.default_rng(0)
    report = {}
    pooled = defaultdict(lambda: defaultdict(list))
    for run in a.runs:
        manifest = json.loads((run / "manifest.json").read_text())
        # replay_plans.py ends each command with: prepared assets mode output --seeds FILE --order journey
        prepared = Path(next(iter(manifest["commands"].values()))[-8])
        audio, desc, meta, member = ruler(a.features / prepared.name)
        variants = [v for v in VARIANTS if (run / f"{v}.tsv").exists()]
        queues = {v: read(run / f"{v}.tsv") for v in variants}
        keys = [k for k in queues[variants[0]] if all(k in queues[v] for v in variants)]
        per = {v: [measures(queues[v][k], int(k.split("@")[0]), audio, desc, meta, member) for k in keys]
               for v in variants}
        pairs = [p for p in PAIRS if p[0] in variants and p[1] in variants]
        name = f"{run.name} ({manifest['plans']}, {len(keys)} seeds)"
        report[name] = {}
        print(f"\n== {name}")
        print("  " + " " * 13 + "  ".join(f"{v:>9s}" for v in variants) + "   " +
              "   ".join(f"{x}-{y:<8s} [95 % CI]     " for x, y in pairs))
        for metric in per[variants[0]][0]:
            values = {v: np.array([m[metric] for m in per[v]], float) for v in variants}
            deltas = {f"{x}-{y}": boot(values[x] - values[y], rng) for x, y in pairs}
            report[name][metric] = dict(mean={v: float(np.nanmean(values[v])) for v in variants}, deltas=deltas)
            print(f"  {metric:13s}" + "  ".join(f"{np.nanmean(values[v]):9.4f}" for v in variants) + "   " +
                  "   ".join(f"{d[0]:+.4f} [{d[1]:+.4f},{d[2]:+.4f}]" for d in deltas.values()))
            if run.name.split("-")[0] in ("r1k_a", "r1k_b", "r1k_c", "s1k_a", "s1k_b", "r3k"):
                for v in variants:
                    pooled[metric][v].extend(values[v])
        shares = {v: float(np.mean([m["skipped"] > 0 for m in per[v]])) for v in variants}
        print("  queues with a skipped slot: " + "  ".join(f"{v} {shares[v] * 100:.1f} %" for v in variants))
        report[name]["queues_with_skips"] = shares
    if pooled:
        print("\n== six MPD libraries pooled")
        for metric, values in pooled.items():
            variants = list(values)
            values = {v: np.array(values[v], float) for v in variants}
            pairs = [p for p in PAIRS if p[0] in variants and p[1] in variants]
            deltas = {f"{x}-{y}": boot(values[x] - values[y], rng) for x, y in pairs}
            report.setdefault("MPD pooled", {})[metric] = dict(
                mean={v: float(np.nanmean(values[v])) for v in variants}, deltas=deltas)
            print(f"  {metric:13s}" + "  ".join(f"{np.nanmean(values[v]):9.4f}" for v in variants) + "   " +
                  "   ".join(f"{d[0]:+.4f} [{d[1]:+.4f},{d[2]:+.4f}]" for d in deltas.values()))
    if a.json:
        a.json.write_text(json.dumps(report, indent=2, default=float) + "\n")


if __name__ == "__main__":
    main()
