"""Mark playlists of an MPD library as "Keep together in SMART", the way a listener might, and pick seeds.

    python mark_playlists.py --prepared <features dir> --out <dir> [--specific 8] [--broad-extra 8] [--seeds-in 100]
        [--seeds-out 50] [--seed 0]

The owner's library (2026-09-24 backup) has 16 of 22 playlists marked, from 3 to 459 tracks, small ones inside big
ones. MPD libraries are built from real playlists of 10 to 98 tracks (rows.json `groups`), so the marks here are
--specific random playlists and one broad playlist: the union of those and --broad-extra more, which nests the
specific ones the way a big genre playlist holds smaller ones.

Writes <out>/companions.txt (one marked group per line, comma-separated rows, the replay harness's
-Ddiet.companions format), <out>/in.seeds and <out>/out.seeds (rows in a specific marked playlist, and rows in no
marked playlist at all; one row per line, the runner's cold seed format), <out>/all.seeds (both) and
<out>/marks.json (which playlists).
"""
import argparse
import json
from pathlib import Path

import numpy as np


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--prepared", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--specific", type=int, default=8)
    ap.add_argument("--broad-extra", type=int, default=8)
    ap.add_argument("--seeds-in", type=int, default=100)
    ap.add_argument("--seeds-out", type=int, default=50)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    rows = json.loads((a.prepared / "rows.json").read_text())
    groups = [sorted(set(g)) for g in rows["groups"]]
    rng = np.random.default_rng(a.seed)
    order = rng.permutation(len(groups))
    specific = [int(g) for g in order[:a.specific]]
    extra = [int(g) for g in order[a.specific:a.specific + a.broad_extra]]
    broad = sorted(set().union(*(groups[g] for g in specific + extra)))
    marked = [groups[g] for g in specific] + [broad]
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "companions.txt").write_text("".join(",".join(map(str, g)) + "\n" for g in marked))
    inside = sorted(set().union(*(groups[g] for g in specific)))
    outside = sorted(set(range(rows["n"])) - set(broad))
    seeds_in = sorted(int(r) for r in rng.choice(inside, size=min(a.seeds_in, len(inside)), replace=False))
    seeds_out = sorted(int(r) for r in rng.choice(outside, size=min(a.seeds_out, len(outside)), replace=False))
    (a.out / "in.seeds").write_text("".join(f"{r}\n" for r in seeds_in))
    (a.out / "out.seeds").write_text("".join(f"{r}\n" for r in seeds_out))
    (a.out / "all.seeds").write_text("".join(f"{r}\n" for r in sorted(seeds_in + seeds_out)))
    (a.out / "marks.json").write_text(json.dumps(dict(
        library=rows["name"], n=rows["n"], specific=specific, broad_from=specific + extra,
        sizes=[len(g) for g in marked], seeds_in=len(seeds_in), seeds_out=len(seeds_out), seed=a.seed,
    ), indent=1) + "\n")
    print(f"{rows['name']}: marked sizes {[len(g) for g in marked]}, seeds {len(seeds_in)} in + {len(seeds_out)} out")


if __name__ == "__main__":
    main()
