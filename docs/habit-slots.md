# Learn from my habits — soft personal slots (FIT arm SOFT, λ = 2)

Test build, 19 October 2026. Not a release and not yet confirmed on a sealed window (W4, November).

## What it does

When SMART starts a queue from a track, the tracks the listener really played after it (or after its artist)
whose habit outweighs how far they would pull the queue from the seed — at most five — are spread over the first
ten slots at 1-based slots floor(k · 10 / m) + 1 for m chosen rows (5 rows: 1, 3, 5, 7, 9; 2 rows: 1, 6; one row:
1), as FIT's `slots3.py` does (`interleave(chosen, base, len(chosen))`). SMART's own plan fills the other slots in
its order without repeats, and the rest of that plan follows. Later top-ups of the running queue are SMART's own
walk, unchanged. This is the PLAN_ITEM5 v5 arm SOFT(λ = 2) on top of the chain (deviation 4b).

Setting: *Settings → SMART engine → Recommendations → Learn from my habits* (`learn_from_habits`), on by default,
all locales. Off, no pick that ever followed the seed or its artist, or no row passing the score = SMART's queue unchanged.
A change takes effect at the next queue start. Not in local backup yet.

Why: on the ListenBrainz (CC0) FIT set (2,120 listeners, four row orders, scenario A; `slots_result.json` on
`claude/smart-stage0-eval-f2a727`) SLOTS5 has return Hit@10 0.205 vs chain 0.033 vs blended personal
weights 0.090, all-target Hit@10 0.145 vs 0.068, first-listen Hit@10 0.068 vs chain 0.095, artist adjacency
below people's. The layer shipped now is SOFT(λ = 2), chosen on FIT for flow as well (`slots3_result.json`).

## Code

- `core/smart/.../HabitSlots.kt` — the reference port: `novelty`, `activation`, `transitionCounts`,
  `transitionList`, `interleave` (score.py `novelty`, `activation`, `transitions`, `baselines()['transition']`;
  slots.py `interleave` with m = 5).
- `composeApp/.../HabitSlotsPlanner.kt` — the app's history projection, origin rule and tie key.
- `EngineNextTrackChooser` — applies it when it plans for a queue that is the seed alone (both playback controllers
  replace the queue with the started track in SMART mode, so every SMART start, a For You or Map journey
  included, goes through here; journeys played with SMART off keep their own list).

## Parity

`HabitSlotsGoldenTest` (core:smart host test) reproduces the reference on 36 FIT queries (30 from libraries of
≤ 800 rows, 6 larger; 15 with a track transition in the top five, 8 with only artist transitions, 13 ACT-R only;
32 with a non-empty novelty set): the transition list's top 20 and the SLOTS5 queue, row for row, on the path
without deviations (every listen may count, the reference's own random key given as its rank). It also checks
that ranking only heard candidates gives the same top five. Regenerate the fixture with

```sh
python3 tools/research/habit_slots/export_golden.py \
  <stage0 worktree>/tools/research/listenbrainz_eval <stage0 worktree>/tools/research/output/listenbrainz-eval/fit \
  core/smart/src/androidHostTest/resources/habit_slots_golden.tsv.gz
```

## Intended deviations

1. **Origin rule (the Statistics page's play-counting rule, docs/statistics-insights-proposal.md "Pick" and
   Addendum A).** A pair A → B is counted only when B is a *pick* (`origin.start == USER_PICK`: the listener
   started B — a tap, search, For You, Play/Shuffle of a collection, a queue tap). So an album's or playlist's
   own order (AUTO_ADVANCE / SKIP_NEXT inside the collection), shuffle draws, SMART's planned plays (they carry a
   `smartPlanPosition` and are never picks), repeat-one, resumes and legacy lines (no origin) never count as
   "what followed". Every play still takes its place in the sequence (as the A of the next pair), in ACT-R and in
   the novelty set. The reference had no origins and counted every consecutive listen; in its terms the app
   counts the subset of pairs whose second listen was chosen.
2. **Skips are not listens.** Skipped events are dropped before pairing (ListenBrainz listens contain no skips),
   so A → skipped S → B pairs A with B.
3. **Tie key.** The reference draws a PCG64 key from the research query key, which has no app equivalent. The app
   uses a splitmix64 key of the track id salted with the planning time (one fixed order per plan, a new one per
   plan). Ties at the tie key are reached only by tracks with equal counts and equal activation.
4. **Only real transitions are placed (19 Oct, after the owner's first try).** A personal slot takes only a row
   with a track or artist→track count above zero, in the list's order, at most five; the rows the reference ranks
   by ACT-R or its random key (overall favourites unrelated to the seed) are left to SMART. Fewer such rows leave
   their slots to SMART (they fill 1, 3, … first); none = SMART's queue unchanged. Why: on the phone the queue
   swung between the owner's phonk favourites and SMART's neighbours; FIT agrees (SLOTS5 genre-family breaks 28 %
   vs chain 16.5 %, descriptor jumps 9.5 % vs 1.1 %, language switches 15 % vs 4 %). This is not the measured
   SLOTS5 arm any more (closer to slots_rule, without its top-up); research v2 with flow gates may change it again.
   The golden test still pins the reference list; this filter sits on top of it.
4b. **Soft score = FIT's SOFT(λ = 2) (19 Oct; faithful after an adversarial review).** Real-transition rows (count
   above zero) are scored s = t − λ·P, λ = 2 (`HabitFlow.LAMBDA`, the λ* of `slots3_result.json`, PLAN_ITEM5 v5,
   research commit e892e133); up to five with s > 0 enter, by s, ties in transition-list order.
   t = ln(1 + c) / ln(1 + max c), c = track count + 0.5 · artist count (`ARTIST_WEIGHT`; a direct transition counts
   in both, ≈ 1.5×, as in PLAN_ITEM5). P = mean of three parts in [0, 1]: genre family and pack language (0 same,
   1 different, 0.5 when either is unknown) and artist-descriptor distance (0 at cosine ≥ HI = 0.7051427364349365,
   FIT's median real consecutive different-artist cosine; 1 at ≤ 0.6036645770072937; linear; 0.5 without both pack
   descriptors). The chain branch had no λ chosen on FIT (M1 0.284 vs 0.282), so on top of the app's chain this
   remains a test setting until W9 confirms it. Remaining differences from the FIT arm:
   - **Genre family source:** the alias family (Genres' table) of the track's own first genre tag value; FIT read
     the teacher's genres, which the app does not ship.
   - **Audio part (off):** an optional fourth part of P on the on-device audio vectors (0 at cosine ≥ the library's
     75th percentile of 2,000 fixed-seed random pairs, 1 at ≤ its 25th, 0.5 when unknown) exists behind
     `HabitFlow.AUDIO_PART = false`; it is not in the FIT arm and not measured.
   - **No scorer, no slots:** if the app has no flow scorer, every row's P is unknown (0.5), which λ = 2 never lets
     through (t ≤ 1), so the queue is SMART's own.
   - Count shrinkage and album/autoplay pair filtering are not implemented (a FIT item is pending).
   History of this layer on the test build: SLOTS5 (top five of the full list incl. ACT-R filler) → real
   transitions only → strict coherence gate → soft score λ = 1 with audio → this. Each change answered the owner
   hearing the queue swing between genres; the FIT flow numbers agree (SLOTS5 genre breaks 28 % vs chain 16.5 %).
5. **Candidates** are the tracks SMART may append (its library minus SMART exclusions, already-queued and removed
   rows); listens are those of SMART's library. The reference: the whole library minus the seed and novelty set.
6. **History window.** The newest 10,000 events (the SMART window, skips included before they are dropped), no
   110-day cut (the reference's scenario A); seed time = the planning moment.
7. **Length.** After the ten-slot window the whole SMART plan follows (the reference kept ten rows), then normal
   top-ups.
8. **Log.** Personal rows are served by SMART and logged with their plan position (odd positions 1–9 of a plan
   whose seed started the queue), so they never count as picks later — the list cannot teach itself.
