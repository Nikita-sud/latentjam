"""MPD libraries for TRAINING the compressed SMART nets, built only from playlists the six evaluation
libraries never drew, so a distilled net is never tuned on the inputs the bundle report scores it on.

    python train_libraries.py      # -> ../libraries/train_*.json

Same construction as smart-bench-2026-09-24 datasets.load_mpd (random: playlists in random order until
the target track count; snowball: a community of overlapping playlists), same track metadata and audio
previews. Each file also keeps its playlists in play order ("sessions"), from which synthetic
listening histories are drawn.
"""
import json
import os
import sys
from pathlib import Path

import numpy as np

from paths import BENCH, RESEARCH as RES, WORK

OUT = WORK / "libraries"
EVAL = [("r1k_a", 1000, "random", 1), ("r1k_b", 1000, "random", 2), ("r1k_c", 1000, "random", 3),
        ("s1k_a", 1000, "snowball", 4), ("s1k_b", 1000, "snowball", 5), ("r3k", 3000, "random", 6)]
TRAIN = [("train_r1", 1000, "random", 101), ("train_r2", 1000, "random", 102), ("train_r3", 2000, "random", 103),
         ("train_r4", 2000, "random", 104), ("train_r5", 3000, "random", 105), ("train_s1", 1000, "snowball", 106),
         ("train_s2", 2000, "snowball", 107), ("train_s3", 1000, "snowball", 108)]


def choose(playlists, keys, target, mode, seed):
    """datasets.load_mpd's playlist selection, over the given keys."""
    rng = np.random.default_rng(seed)
    chosen, members = [], set()
    if mode == "random":
        for k in rng.permutation(keys):
            chosen.append(k)
            members |= set(playlists[k].tolist())
            if len(members) >= target:
                break
    else:
        first = keys[int(rng.integers(len(keys)))]
        chosen.append(first)
        members |= set(playlists[first].tolist())
        while len(members) < target:
            sample = rng.choice(len(keys), size=min(4000, len(keys)), replace=False)
            best, best_ov = None, -1.0
            for j in sample:
                k = keys[j]
                if k in chosen:
                    continue
                ov = np.isin(playlists[k], list(members)).mean()
                if best_ov < ov < 0.9:
                    best, best_ov = k, ov
            chosen.append(best)
            members |= set(playlists[best].tolist())
    return chosen, sorted(members)


def main():
    os.chdir(BENCH)
    sys.path.insert(0, str(BENCH))
    import datasets as ds
    import pandas as pd
    import pyarrow.parquet as pq

    meta, _emb, playlists = ds._mpd_tables()
    keys = sorted(playlists)
    used = set()
    for name, target, mode, seed in EVAL:
        chosen, members = choose(playlists, keys, target, mode, seed)
        # The reconstruction must give exactly the evaluation library, or the exclusion means nothing.
        exported = {r["id"] for r in json.loads((OUT / f"{name}.json").read_text())["rows"]}
        assert {str(meta.track_id.iloc[r]) for r in members} == exported, f"{name} not reproduced"
        used |= set(chosen)
    free = [k for k in keys if k not in used]
    print(f"{len(keys)} playlists, {len(used)} drawn by the evaluation libraries, {len(free)} free")

    paths = pq.read_table(RES / "models/embed/mpd_mnv4_distilled.parquet", columns=["track_id", "path"]).to_pandas()
    path_of = dict(zip(paths.track_id, paths.path))
    sessions = pd.read_csv(RES / "data/manifests/mpd_sessions.csv",
                           dtype={"playlist_id": str, "position": np.int32, "track_id": str})
    row_of_id = {t: i for i, t in enumerate(meta.track_id)}
    ordered = {pid: [row_of_id[t] for t in g.sort_values("position").track_id if t in row_of_id]
               for pid, g in sessions[sessions.playlist_id.isin(set(free))].groupby("playlist_id")}

    OUT.mkdir(exist_ok=True)
    for name, target, mode, seed in TRAIN:
        chosen, rows = choose(playlists, free, target, mode, seed)
        local = {r: i for i, r in enumerate(rows)}
        doc = dict(name=name,
                   rows=[dict(id=str(meta.track_id.iloc[r]), title=meta.title.iloc[r], artist=meta.artist.iloc[r],
                              album=None, genre=None, year=None, duration_ms=None,
                              audio=path_of.get(meta.track_id.iloc[r])) for r in rows],
                   groups=[sorted(local[r] for r in playlists[k]) for k in chosen],
                   cold_seeds=list(range(len(rows))),
                   sessions=[[local[r] for r in ordered[k] if r in local] for k in chosen])
        (OUT / f"{name}.json").write_text(json.dumps(doc, ensure_ascii=False))
        print(f"{name}: {len(rows)} tracks, {len(chosen)} playlists")


if __name__ == "__main__":
    main()
