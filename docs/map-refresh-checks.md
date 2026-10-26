# Map refresh — focused checks per piece

Implementation of the plan in `docs/map-cost-research-2026-10-25.md` (research branch
`claude/research-map-cost-91141c`, "Implementation plan", commit 5802b23f). Each piece's check is
frozen here before it runs. Public data only: synthetic `hierarchicalFixture` libraries and FMA
draws from the licence-clean general-stand pool. Never the owner's library.

## Piece 1: layout refresh (frozen 27 October 2026, before the run)

**Code under test:** `LibraryLayoutRefresh.build` / `refresh` at commit 440169ef.
**Harness:** `MapRefreshCheck` (opt-in host test, `core/smart/src/androidHostTest`).

**Data** (none used before):

- synthetic seeds 37 / 41 / 43 at 10,000 tracks;
- FMA draws s404 (5,000), s505 (5,000) and s606 (6,500), made by the research branch's
  `tools/research/map_gate_fma_dump.py --seed`.

**Cases per library.** In every case the "before" map is a full build through
`LibraryLayoutRefresh.build`, and the "after" map is `refresh`.

- uniform additions of 0.5 / 1 / 2 %;
- changed vectors at 0.5 / 1 / 2 %;
- album additions of about 1 % (10–20-track groups from one style);
- for FMA only, a held-out-split addition of 1 %;
- **a 2 % chain:**
  - start at 88 % of the library and add 0.5 % per step for 20 steps;
  - the map turns stale when the change since the last full build exceeds 2 %;
  - a stale map is rebuilt (`build`, warm from the refreshed map) before the next step.

**Rows.** Each must hold in every case on every library; "today" is today's warm full build
(`LibraryLayout.compute` from the before map).

| Row | Pass |
| --- | --- |
| trust@15 | ≥ today − 0.005 |
| recall@15 | ≥ today × 0.97 |
| class@15 (style / FMA top genre) | ≥ today − 0.010 |
| RF (recall@15 of changed tracks) | ≥ today × 0.85 |
| lost neighbours of unchanged tracks (1 − kept15) | ≤ 0.5 × today's |
| p90 move of unchanged tracks | ≤ 0.5 × today's and ≤ 0.02 of the map width |
| refresh decision | every case is refreshed (none declined); 0.5 % and 1 % uniform or changed cases are not marked stale unless a hint fires (hints are counted and reported) |
| neighbour lists | the new state's lists are bit-identical to `BarnesHutTsne.nearestNeighbours` over its reduced rows |
| overlap@15 with a cold build, against a 5-seed noise band | **per case** ≥ the band's minimum − 0.02; **mean over cases** ≥ the band's mean − 0.05 |
| CF of changed tracks, against the same band | **per case** ≥ the band's minimum − 0.02; **mean over cases** ≥ the band's mean − 0.05 |
| chain, at every step that turns stale and at the last step | the waiting map against a cold build of that library: trust ≥ cold − 0.005, recall ≥ cold × 0.97, RF ≥ cold × 0.85; and bit-identical lists at every step |

**The noise band** is five cold builds of the after library with seeds +1, +3, +5, +7 and +9:
- for overlap: each band build's overlap with the default cold build;
- for CF: each band build's CF for the changed tracks.

**Verdict.** Piece 1 passes only if every row passes. A failing row is reported and nothing is
re-gated on this data.

### Piece 1 result (26 October 2026): FAIL as frozen

**Run.** 6 libraries, 45 cases plus 6 chains, 1 h 47 min on the host.
- Log: `docs/map-refresh-logs/piece1-check-2026-10-26.txt`.
- Score: `docs/map-refresh-logs/piece1-score-2026-10-26.txt`.

| Row | Result | Verdict |
| --- | --- | --- |
| trust, class, lost neighbours, p90 move, bit-exact lists | 45 / 45 each | PASS |
| recall@15 | 44 / 45. Fails on synthetic s41 album1 (0.2624 vs 0.2709, 0.969×). | FAIL |
| RF | 44 / 45. Fails on FMA s505 album1 (0.608 vs 0.733, 0.83×). | FAIL |
| overlap with a cold build, per case against the band minimum − 0.02 | 24 / 45. **Every synthetic case fails**, from 0.5 % on: the refreshed map agrees with a cold build at 0.45–0.50, against a band minimum of 0.53–0.55. Today's warm rebuild sits at about 0.54. Every FMA case passes. | FAIL |
| CF, per case against the band minimum − 0.02 | 42 / 45. Fails on FMA s505 add0.5 (0.728 vs 0.755), s505 album1 (0.679 vs 0.789) and s606 album1 (0.708 vs 0.814). | FAIL |
| overlap, mean − band mean ≥ −0.05 | −0.033 | PASS |
| CF, mean − band mean ≥ −0.05 | −0.008 | PASS |
| small changes not marked stale | 24 / 24 | PASS (reported) |
| 2 % chains | 6 / 6, 5 rebuilds each | PASS |
| refresh time (host) | 2.7–3.1 s against 16.3–18.4 s (synthetic, 10,000); 1.0–1.4 s against 6.1–8.1 s (FMA) | (reported) |

**What it means.**

- **Overlap.** On synthetic data the refresh keeps the previous map's own t-SNE solution, and that
  solution agrees with a cold build of the new library somewhat less than two cold builds agree
  with each other. The per-case band row detects this; the mean row and every quality row against
  the input do not fail.
- **Albums.** Whole-style albums are placed less well than a full rebuild would place them. This
  is the known weakness.
- **Consequence.** The piece is **not** passed. The flag stays off by default. The remaining
  pieces are separable and continue.

### Change of criterion after piece 1 (26 October 2026, decided by the delegated owner)

**Overlap with a cold build becomes a reported number, not a pass/fail row,** from the next round
on.

- **Why.** Two cold builds of the same library agree at only about 0.54 (synthetic). The refresh
  deliberately keeps the previous map's own t-SNE solution, which is the point of it. So agreement
  with one particular cold solution does not measure layout quality.
- **What judges the layout instead:**
  - fidelity to the high-dimensional space: trust, recall, RF, CF and class;
  - stability: lost neighbours and p90 move.

  All of these passed except on three whole-style album cases.
- **Piece 1 itself stays FAIL as frozen.** This changes the criterion for later rounds only; it
  does not re-score this run.

**Albums and the stale hint.** This was checked first, as asked.

- In the piece-1 run, **none of the six album cases was marked stale**:
  - their shares are 1.02–1.24 %, under the 2 % budget;
  - the geometry hint did not fire;
  - the check harness did not pass artist names, so the app's unseen-artist hint was not
    exercised.
- So today the stale hint does **not** route these albums to the deferred rebuild.

**Follow-up round (to be predeclared here before it runs):**
- a few exaggerated iterations limited to grouped newcomers (changed tracks whose nearest
  neighbours are mostly other changed tracks);
- a row on the album cases' recall, RF and CF;
- a re-check of whether the unseen-artist hint marks real album additions.

## Piece 2: refresh heap (frozen 26 October 2026, before the run)

**Code under test:** `LibraryLayoutRefresh.refresh` with its state loaded from
`FileMapRefreshStateStore`, which maps the state from disk (`MapRefreshHeapDeviceTest`).

**Setup.**
- Device: `LJ-api29`, booted read-only, with a 256 MB heap set by `setprop` (the image is
  rootable). `LJ-demo` and the phone are not used.
- Data: synthetic seed 61 at 10,000 tracks.
- Scenarios: add1 and change1.
- Runs: 5 alternating runs per mode, with the host idle.

**Rows** (medians of 5):

| Row | Pass |
| --- | --- |
| Java heap | refresh peak delta ≤ today's peak delta + 2 MB; the mapped file's size is reported separately |
| time | refresh ≤ 0.25 × today (`LibraryLayout.compute`, streamed and warm) |

**iOS.** The simulator footprint is not measured in this piece; that is stated as unmeasured.

**If the heap row fails** (predeclared fallback): keep only the patched index array, stream the
affinities per row, then re-measure. If it still fails, the piece fails.

### Piece 2 result (26 October 2026): PASS

Log: `docs/map-refresh-logs/piece2-heap-2026-10-26.txt` (20 runs, 5 per mode and scenario, modes
alternating; APK built from commit 2f9a2298). Every refresh run refreshed (none stale, none declined).

| Scenario | Today: time / heap delta | Refresh: time / heap delta | Time ratio | Heap: refresh − today | Verdict |
| --- | --- | --- | --- | --- | --- |
| add1 | 19,449 ms / 27.75 MB | 2,583 ms / 29.20 MB | 0.133 | +1.45 MB | PASS |
| change1 | 20,148 ms / 27.82 MB | 3,659 ms / 29.68 MB | 0.182 | +1.85 MB | PASS |

- The mapped state file is 4.62 MB (add1) and 4.67 MB (change1). It is memory-mapped, not Java heap.
- **Margin.** change1's heap margin is small: 0.15 MB under the 2 MB limit. The per-run spread
  is 29.07–29.86 MB, against 27.75–28.52 MB for today.
- **Deviation from the setup.** The host was *not* idle: a host-side Gradle test (the piece-4
  pilot) and a read-only code search ran during the series. Both modes alternated through the same
  load, so the time ratio is the fair comparison. Absolute times may be inflated. Heap is
  unaffected by host load.
- The fallback was not needed.
- **iOS:** the simulator footprint is unmeasured, as frozen.
