"""Seeds for recording SMART-net training inputs on the training libraries.

    python train_seeds.py FEATURES_ROOT     # writes <library dir>/train.cold.seeds and train.history.seeds

Cold: 600 random tracks per library. History: 1,000 synthetic listening moments per library, built
from the library's playlists in play order (MPD keeps each playlist's track positions): the seed is a
playlist track, the session before it is up to 25 preceding tracks of the same playlist, one song
apart in time, with 15 % skipped early; older sessions from other playlists sit hours to weeks
earlier (0-3 for 30 % of moments, a new listener; 5-60 otherwise, up to about a thousand plays), so the
state net sees short, medium and long histories like a phone's log. The runner adds
the seed itself as the latest play, as the app does.
"""
import json
import os
import sys
from pathlib import Path

import numpy as np

from paths import WORK

LIBS = WORK / "libraries"
COLD, HISTORY = int(os.environ.get("COLD_SEEDS", 600)), int(os.environ.get("HISTORY_SEEDS", 1000))
DAY = 86_400_000
HOURS = np.array([1, .5, .3, .2, .2, .3, .8, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 6, 7, 7, 6, 5, 3, 2])  # listening by hour
HOURS = HOURS / HOURS.sum()


def session(rng, tracks, end_ts):
    """Events (row, ts, fraction, skipped, completed) for tracks played back to back, ending at end_ts."""
    events, ts = [], end_ts
    for row in reversed(tracks):
        skipped = rng.random() < 0.15
        length = int(rng.uniform(150_000, 270_000))
        fraction = float(rng.uniform(0.02, 0.4)) if skipped else 1.0
        ts -= int(length * fraction) + int(rng.uniform(1_000, 8_000))
        events.append((row, ts, fraction, skipped, not skipped))
    return events[::-1]


def history_line(rng, sessions, start_of_year):
    pool = [s for s in sessions if len(s) >= 3]
    s = pool[int(rng.integers(len(pool)))]
    p = int(rng.integers(1, len(s)))
    seed = s[p]
    seed_ts = start_of_year + int(rng.integers(0, 365)) * DAY + int(rng.choice(24, p=HOURS)) * 3_600_000 \
        + int(rng.integers(0, 3_600_000))
    current = s[max(0, p - int(rng.integers(1, 26))):p]
    events = session(rng, current, seed_ts - int(rng.uniform(2_000, 30_000)))
    first = events[0][1] if events else seed_ts
    older_sessions = int(rng.integers(0, 4)) if rng.random() < 0.3 else int(rng.integers(5, 61))  # new vs long-time user
    for _ in range(older_sessions):
        other = pool[int(rng.integers(len(pool)))]
        k = int(rng.integers(3, min(len(other), 30) + 1))
        start = int(rng.integers(0, len(other) - k + 1))
        first -= int(rng.uniform(2 * 3_600_000, 21 * DAY))
        older = session(rng, other[start:start + k], first)
        first = older[0][1]
        events = older + events
    text = ",".join(f"{r}:{t}:{np.format_float_positional(np.float32(f), unique=True, trim='-')}:{int(sk)}:{int(c)}"
                    for r, t, f, sk, c in events)
    return f"{seed}\t{seed_ts}\t{text}"


def main(root):
    for folder in sorted(Path(root).glob("train_*")):
        name = folder.name.split(".")[0]
        doc = json.loads((LIBS / f"{name}.json").read_text())
        data = json.loads((folder / "rows.json").read_text())
        new_of = {old: new for new, old in enumerate(data["library_rows"])}
        sessions = [[new_of[r] for r in s if r in new_of] for s in doc["sessions"]]
        rng = np.random.default_rng(sum(map(ord, name)))  # fixed per library
        cold = sorted(rng.choice(data["n"], size=min(COLD, data["n"]), replace=False).tolist())
        (folder / "train.cold.seeds").write_text("".join(f"{r}\n" for r in cold))
        start = 1_735_689_600_000  # 2025-01-01 UTC
        lines = [history_line(rng, sessions, start) for _ in range(HISTORY)]
        (folder / "train.history.seeds").write_text("".join(l + "\n" for l in lines))
        n_events = [l.count(",") + 1 if l.split("\t")[2] else 0 for l in lines]
        print(f"{name}: {len(cold)} cold seeds, {len(lines)} history seeds, events per seed median "
              f"{int(np.median(n_events))} max {max(n_events)}")


if __name__ == "__main__":
    main(sys.argv[1])
