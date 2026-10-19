# Learn from my habits — personal-transition slots (SLOTS5)

Test build, 19 October 2026. Not a release and not yet confirmed on a sealed window (W4, November).

## What it does

When SMART starts a queue from a track, the tracks the listener really played after it (or after its artist)
take up to the 1-based slots 1, 3, 5, 7 and 9 when they keep the queue's flow (deviation 4b); SMART's own plan fills the other slots in its order without repeats, and the
rest of that plan follows. Later top-ups of the running queue are SMART's own walk, unchanged.

Setting: *Settings → SMART engine → Recommendations → Learn from my habits* (`learn_from_habits`), on by default,
all locales. Off, or no pick that ever followed the seed or its artist = SMART's queue unchanged.
A change takes effect at the next queue start. Not in local backup yet.

Why: on the ListenBrainz (CC0) FIT set (2,120 listeners, four row orders, scenario A; `slots_result.json` on
`claude/smart-stage0-eval-f2a727`) SLOTS5 has return Hit@10 0.205 vs chain 0.033 vs blended personal
weights 0.090, all-target Hit@10 0.145 vs 0.068, first-listen Hit@10 0.068 vs chain 0.095, artist adjacency
below people's.

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
4b. **Coherence gate (19 Oct, from FIT v2 / slots2_rule.json).** Real transitions alone still broke the flow on FIT
   (top-10 descriptor jumps 6.1 % vs chain 0.6 %, language switches 11 % vs 3 %). A real-transition row is placed only
   if it is coherent with the seed and *known*: both tracks have an artist-knowledge-pack descriptor (the adapter's
   guesses do not count), their cosine is ≥ 0.6036645770072937 (FIT's frozen jump threshold), their genre families
   agree when both have one, and their pack languages agree when both have one. Failing rows are skipped and deeper
   coherent rows move up (up to five); empty slots go to SMART. Stricter than the research's RC (which let unknown
   rows through); not W4-validated. The family is the alias family (Genres' table) of the track's *first genre tag
   value* — the research read the teacher's genres, which the app does not ship. Code: `HabitCoherence`.
5. **Candidates** are the tracks SMART may append (its library minus SMART exclusions, already-queued and removed
   rows); listens are those of SMART's library. The reference: the whole library minus the seed and novelty set.
6. **History window.** The newest 10,000 events (the SMART window, skips included before they are dropped), no
   110-day cut (the reference's scenario A); seed time = the planning moment.
7. **Length.** After the ten-slot window the whole SMART plan follows (the reference kept ten rows), then normal
   top-ups.
8. **Log.** Personal rows are served by SMART and logged with their plan position (odd positions 1–9 of a plan
   whose seed started the queue), so they never count as picks later — the list cannot teach itself.
