"""How replayed queues treat marked playlists ("Keep together in SMART"), per variant and seed group.

    python companion_metrics.py --prepared <features dir> --marks <mark_playlists.py out dir> \
        --queues <dir with variant .tsv files> [--queues <another dir>] [--first 24]

For every variant file and for the in-seeds and out-seeds of mark_playlists.py (rows inside one of the specific
marked playlists, rows in none): theme (share of the first tracks inside a marked playlist with the seed, the broad
playlist not counted), together (share of steps from the seed whose two tracks share a specific marked playlist;
`any` counts the broad one too), harsh (steps with audio cosine below 0.2), other (share of tracks sharing an
unmarked real playlist with the seed; MPD libraries only), the longest run of one artist and the share of queues
with five or more in a row. The broad playlist is the last line of companions.txt when marks.json says so.
"""
import argparse
import json
from pathlib import Path

import numpy as np

from compare_plans import ruler, read


def load_marks(marks):
    """The marked groups, the specific ones, the playlist ids behind the marks and the two seed sets."""
    groups = [set(int(x) for x in line.split(",")) for line in (marks / "companions.txt").read_text().split("\n") if line]
    info = json.loads((marks / "marks.json").read_text()) if (marks / "marks.json").exists() else None
    specific = groups[:-1] if info else groups
    marked_ids = set(info["broad_from"]) if info else set()
    seeds = {name: {int(x) for x in (marks / f"{name}.seeds").read_text().split()} for name in ("in", "out")}
    return groups, specific, marked_ids, info is not None, seeds


def measure(ruled, marks, path, first=24):
    """{"in": rows, "out": rows}, one row per queue: theme, together, any, harsh, other, longest run, 5+ run."""
    audio, meta, member = ruled
    groups, specific, marked_ids, mpd, seeds = marks
    out = {}
    queues = read(path)
    for label, wanted in seeds.items():
        rows = []
        for key, q in queues.items():
            seed = int(key.split("@")[0])
            if seed not in wanted or not q["played"]:
                continue
            played = q["played"][:first]
            path_rows = [seed] + played
            mine = [g for g in specific if seed in g]
            theme = np.mean([any(r in g for g in mine) for r in played]) if mine else np.nan
            steps = list(zip(path_rows, path_rows[1:]))
            together = np.mean([any(x in g and y in g for g in specific) for x, y in steps])
            together_any = np.mean([any(x in g and y in g for g in groups) for x, y in steps])
            # Row-wise dot products; Accelerate's matmul raises spurious FP flags on these float64 rows.
            cosines = np.einsum("ij,ij->i", audio[path_rows[:-1]], audio[path_rows[1:]])
            harsh = np.mean(cosines < 0.2)
            unmarked = member[seed] - marked_ids
            other = np.mean([bool(member[r] & unmarked) for r in played]) if mpd and unmarked else np.nan
            artists = [meta[r][1].strip().lower() for r in path_rows]
            run = best = 1
            for i in range(1, len(artists)):
                run = run + 1 if artists[i] and artists[i] == artists[i - 1] else 1
                best = max(best, run)
            rows.append((theme, together, together_any, harsh, other, best, best >= 5))
        out[label] = np.array(rows, float).reshape(-1, 7)
    return out


def line(name, label, r):
    return (f"{name[-52:]:52s} {label:>5s} {len(r):4d} {np.nanmean(r[:, 0]) * 100:5.1f}% "
            f"{np.nanmean(r[:, 1]) * 100:5.1f}% {np.nanmean(r[:, 2]) * 100:5.1f}% {np.nanmean(r[:, 3]) * 100:5.1f}% "
            f"{np.nanmean(r[:, 4]) * 100:5.1f}% {np.mean(r[:, 5]):5.2f} {np.mean(r[:, 6]) * 100:3.0f}%")


HEADER = (f"{'variant':52s} {'seeds':>5s} {'n':>4s} {'theme':>6s} {'togeth':>6s} {'any':>6s} {'harsh':>6s} "
          f"{'other':>6s} {'run':>5s} {'5+':>4s}")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--prepared", type=Path, required=True)
    ap.add_argument("--marks", type=Path, required=True)
    ap.add_argument("--queues", type=Path, action="append", required=True)
    ap.add_argument("--first", type=int, default=24)
    a = ap.parse_args()
    audio, _, meta, member = ruler(a.prepared)
    ruled = (np.nan_to_num(audio), meta, member)
    marks = load_marks(a.marks)
    print(HEADER)
    for folder in a.queues:
        for path in sorted(folder.glob("*.tsv")):
            for label, r in measure(ruled, marks, path, a.first).items():
                if len(r):
                    print(line(f"{folder.name}/{path.stem}", label, r))


if __name__ == "__main__":
    main()
