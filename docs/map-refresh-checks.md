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

### Piece 2 review, round 1 (26 October 2026): REVISE. The PASS above is withdrawn.

A fresh adversarial reviewer recomputed every number (they match) and confirmed that the freeze was
only appended to. The verdict is still not supported:

1. **`store.load()` ran before the heap baseline.** The state's heap was therefore never counted
   in refresh's delta:
   - `mean` and `basis` (1,344 × 50 floats);
   - the 10,000 decoded id strings;
   - the checksum and validation pass.

   In the app, loading is part of the refresh path. With change1's 0.15 MiB margin this can flip the
   verdict.
2. **The unit was not declared.** The table used MiB. Read in decimal MB, change1's margin is 0.06.
3. **The load's time was outside the timer.**
4. **Heap polling at 10 ms can miss short peaks.** Refresh runs get about 8× fewer samples than
   today. This is a disclosed limitation of the method, applied to both modes.
5. **"Heap is unaffected by host load" is not supported.** GC timing shifts under load.

**Re-run, declared before it runs:**
- Same device, data, scenarios and 5 alternating runs. The host is idle this time: no Gradle and
  no other jobs during the series.
- Refresh mode loads the state **inside** the sampled and timed window (`load_in_window=1`, with
  `load_ms` logged).
- The heap row is scored against **2,000,000 bytes**, the stricter reading of "2 MB".
- The time row is unchanged.
- If the heap row fails, the frozen fallback applies: keep only the patched index array and stream
  the affinities per row, then re-measure.

## Piece 4: regions with identity (frozen 26 October 2026, before the run)

**Code under test:** `RegionIdentity` (`initial` / `join` / `rediscover`, rows streamed from
`LibraryVectorRows`) with `DELTA = 0.2`. Regions come from today's `LibraryWorlds.discover`.
**Harness:** `MapRegionIdentityReplay` (opt-in host test, `MAP_REGIONS=1`).

### Pilot (synthetic seed 7 and the FMA pilot draw; it sets δ only)

Logs: `piece4-pilot1-2026-10-26.txt` and `piece4-pilot2-2026-10-26.txt`.

**Pilot 1** (δ ∈ {0, 0.02, 0.05, 0.1}) counted every settled track whose region differed as
churn. That included tracks that had **no** region before and got one. On the k-means switch this
is coverage growth: average linkage leaves 16 % of tracks out, k-means about 4 %. It is not
movement, so the definition was split before the freeze:

- **moved:** a settled track had a region and now has another one or none (the row);
- **gained:** a settled track had none and now has one (reported).

Per-region moved shares (p50 / p90 / max over old regions) are logged too.

**Pilot 2** (δ ∈ {0, 0.05, 0.1, 0.2}), moved churn:

| | δ = 0 | 0.05 | 0.1 | 0.2 |
| --- | --- | --- | --- | --- |
| synthetic s7, average linkage | ≤ 0.8 % | ≤ 0.8 % | ≤ 0.4 % | ≤ 0.2 % |
| synthetic s7, switch to k-means | 0 | 0 | 0 | 0 |
| FMA pilot, average linkage | up to 2.5 % | up to 2.2 % | up to 2.1 % (change2) | ≤ 1.6 % |
| FMA pilot, switch to k-means | 4–7 % | 3–5 % | 2.1–3.6 % | ≤ 1.6 % |

- **Today, for contrast** (fresh worlds matched to the old ids by overlap, no stickiness): FMA
  moves 2.5–9 % on average linkage and about 42 % on the switch.
- Purity, coverage, new-style and minimum-size rows pass at every δ.
- **δ = 0.2 is the only value that passes every row on both libraries and both paths.**
- The 2 % chain's moved-from-start share is 1.8 % (FMA) and 0.01 % (synthetic) at δ = 0.2.

### Data and paths (none used before)

- **Data:**
  - synthetic seeds 47 / 53 / 59 at 10,000 tracks;
  - FMA draws s707 (5,000), s808 (5,000) and s909 (6,500).
- **The FMA draws are new random draws from the same 7,989-track pool** as the pilot draw and
  piece 1, so they overlap in tracks.
- **Paths:**
  - **Average linkage:** "before" and "after" both use it.
  - **Switch:** "before" uses average linkage, "after" uses the k-means fallback (`workBudgetBytes = 1`),
    as a 256 MB device at 10,000 tracks does (round 3).

**Cases per library:**

- additions of 1 / 2 / 5 %;
- changed vectors at 2 %;
- album additions of about 1 %;
- one new style at about 2 % (for FMA, from its held-out split only);
- the 2 % chain: start at 88 %, add 0.5 % per step for 20 steps, join every step, and rediscover
  when more than 2 % is pending.

### Rows

Each case must pass on both paths, on every library. "Fresh" is today's discovery of the after
library given fresh identities.

| Row | Pass |
| --- | --- |
| settled moved churn | ≤ 2 % (gained and per-region distribution logged, today's contrast logged) |
| purity (synthetic style / FMA top genre) | ≥ fresh − 0.01 |
| coverage | ≥ fresh − 0.02 |
| new style in its own region (novel case) | ≥ fresh − 0.10 |
| new regions | none below `MIN_CLUSTER_SIZE` (4) |
| 2 % chain (average linkage) | purity ≥ fresh − 0.01, coverage ≥ fresh − 0.02, **and moved churn from the start ≤ 2 %** |

**For You with the flag on.** This is checked by code path and review, not by this harness:
- For You reads `LibraryRegionsService.state.worlds`.
- With the flag on and the analysis over, those are `RegionIdentityKeeper`'s worlds (joined
  between rebuilds, rediscovered at a rebuild).
- The piece-3 emulator run exercises it.

**Deviation from the plan.** The plan says identity is stored "in the sidecar". It is stored in its
own file, `map_region_identity.bin`, beside the sidecar. Regions and layout are written by different
stages, and one file each keeps the layout state's codec unchanged. A missing or corrupt file costs
one rediscovery.

**Not colour.** The Map has no per-region colours today: the Worlds lens marks only the selected
region. So the stable `colour` index is stored but has nothing to read it yet. What identity keeps
visible is the region's id (which orders the region list), its name and its members.

### Piece 2 re-run result (26 October 2026): PASS as declared, with a caveat on what the heap row measures

**Logs.**
- `piece2-heap-rerun-2026-10-26.txt`: 20 runs. Every refresh refreshed, none stale, all with
  `load_in_window=1`.
- `piece2-heap-rerun-hostload-2026-10-26.txt`: the host load per run.
- Another session's Gradle test executor was running during 16 of the 20 runs. My own Gradle was
  idle throughout.

| Scenario | Today: time / heap delta | Refresh: time / heap delta (load inside) | Time ratio | Heap: refresh − today | Verdict |
| --- | --- | --- | --- | --- | --- |
| add1 | 18,868 ms / 29,262,352 B | 2,471 ms / 8,522,952 B | 0.131 | −20.7 MB | PASS |
| change1 | 19,698 ms / 29,190,576 B | 3,604 ms / 10,998,528 B | 0.183 | −18.2 MB | PASS |

- The state load takes 32–133 ms and is now inside both the timer and the sampled window.

**Caveat: what the heap row measures.**
- **Today's** peak delta is about 29 MB in both series.
- **Refresh** measured about 29 MB in the first series, with the load outside the window, and
  5.5–11 MB now. Moving the load *inside* made the reading smaller, which no change in work explains.
- **Most likely reading:** the sampled `totalMemory − freeMemory` is dominated by when ART's
  collector runs. Used heap climbs to the next collection trigger, about 29 MB above the post-GC
  baseline here, and then drops. So the sample measures garbage headroom, not the live working set.
- **What this supports:** the refresh does not need more Java heap than today's build.
- **What it does not support:** the size of the margin.
- **Follow-up**, not run here: a stronger heap metric, either the smallest `-Xmx` (via `setprop`) at
  which each mode still completes, or ART's `art.gc.bytes-allocated` / an `hprof` of the peak.

> **Timing of this freeze.** The text below was written to the scratchpad at 01:39 CET on
> 26 October, before part B's Android cold launch ran (02:00–02:03). It was committed only after
> that run, at the same time as part B's log and before part A ran. Part B's outcome was not used to
> change it.

## Piece 3: the deferred rebuild (frozen 26 October 2026, before the run)

**Code under test:**
- `MapRebuild` (headless; composeApp commonMain).
- Android: `MapRebuildWorker` (WorkManager unique work, charging + device idle).
- iOS: `registerMapRebuildTask` / `BGProcessingTaskRequest` with `requiresExternalPower`.
- The in-app fallback (24 h on external power), the `MapPageCache` rebuild revision and the debug
  "stale" marker.

**Harness:** `MapRebuildDeviceTest` (androidDeviceTest; `-e mapRebuild 1`) for the work itself, plus
the app on the emulator and simulator for the launch paths.

### A. The rebuild's work on `LJ-api29`

**Setup:**
- 256 MB heap; synthetic seed 61 at 10,000 tracks.
- A background-priority thread, as on the map lane.
- Before: the map and regions of 99 %. After: 100 %; the added 1 % is not settled.
- Stages, as `MapRebuild` runs them:
  1. region discovery, with the budget measured by the app's `availableMapWorkBytes` formula, so the path is the one the app would pick;
  2. `RegionIdentity.rediscover`;
  3. `LibraryLayoutRefresh.build`;
  4. the state write.
- One full run, then 10 runs stopped at uniformly random deadlines inside the full run's duration
  (seeded).

| Row | Pass |
| --- | --- |
| completes | the full run completes (no OOM) on the path the formula picks; path, budget and peak Java-heap delta logged |
| time | ≤ 9 min (540,000 ms) |
| longest gap between two cancellation polls, whole run | ≤ 250 ms (stage of the longest gap logged) |
| cancellation | each of the 10 deadline runs ends `cancelled` ≤ 250 ms after its deadline |
| old map intact after a cancel | the state file is untouched by every run cancelled before its write stage |

**Reported, not rows:**
- per-stage times;
- the longest single chunk read. In the app each chunk read holds the engine lock, so this stands
  in for SMART's longest wait. The device test has no real engine: the chunk is an array copy here,
  while the engine also fuses the rows, so the app's wait is longer by that work. This is stated as
  a stand-in.

### B. Launch paths

**Android, on `LJ-api29`** (a debug build of the app):
- Setup: the refresh override on, and the `map_rebuild_request` debug trigger, so a cold start
  enqueues the work.
- Steps: start, force-stop, then `adb shell cmd jobscheduler run -f <pkg> <job id>` with the process
  dead.
- **Row:** the job starts a cold process, `MapRebuild.run` logs an outcome, and WorkManager records
  the work as finished.

**iOS simulator** (a separate simulator, not the booted "LJ iPhone Duo"):
- Steps: the debug app with the override and the task registered; process killed; then
  `_simulateLaunchForTaskWithIdentifier` (lldb).
- **Row:** the handler runs, `MapRebuild.run` logs an outcome, and the task completes.
- If the simulator path cannot be driven, it is reported as **not run**, not as a pass.

**Expiry and cancel:**
- Row A covers the work being cancelled.
- The iOS expiration handler cancels the job; `invokeOnCompletion` then completes the task and
  reschedules it.
- WorkManager's `isStopped` is the worker's poll. Both are checked by reading the code, not on a
  device.

### What the emulator cannot show (stated before the run)

- Neither emulator nor simulator has an analysed library of ≥ 2,000 tracks. So a launched rebuild
  will find nothing pending, and `NOT_NEEDED` is the expected outcome.
- The swap-in of a rebuilt map is covered by a unit test instead (`MapPageCacheTest`: a new
  revision reloads the layout from disk although the fingerprint is the same), and by row A doing
  the work itself.
- The 24 h fallback is not exercised on a device. Its timer is in memory, so it restarts with the
  process, and that is disclosed.

### Piece 4 freeze review, round 1 (26 October 2026): REVISE. The freeze is revised below, before any check run.

A fresh adversarial reviewer found two kinds of problem: rows that cannot fail, and bugs in the
wiring. Each is addressed as follows (issue numbers are the reviewer's).

**1. Purity on the switch path cannot fail.** The fresh map there is k-means: 16 coarse regions
against about 210 kept ones, and purity grows with the region count.
- **New row, placed agreement.** It covers the tracks this round placed: new or changed tracks,
  and settled tracks that had no region before. It is the share whose label matches their region's
  majority label, compared against the same tracks in the fresh map of the same path.
  **Pass: ≥ fresh − 0.05.**
- Region counts are logged next to purity.
- On the switch, the average-linkage fresh map is also logged (purity and placed agreement), for
  contrast only: the device cannot run average linkage.

**2. Coverage is one-sided.** `rediscover` keeps settled tracks that the new worlds leave out, so
identity's coverage cannot fall below what it had. Disclosed. The coverage row still guards the
other side: tracks that lose their region.

**3. The new-style row passes automatically when fresh scores 0.** When the fresh map does not give
the new style a region of its own (own = 0), the row now reads **n/a**, not a pass.

**4. Both paths for the chain.** A switch chain is added, with k-means at every rediscovery.

**5. The harness changed after pilot 2.** The chain's churn verdict and the placed-agreement row
were added after pilot 2. Pilot 3 is run with the final harness.

**6. The "today" contrast is now exact.** It uses only the fresh worlds, each given the old id it
overlaps most, with no keeping of tracks the fresh worlds leave out.

**7. The new-style case could reuse the pilot's style.** The style the pilot picked on FMA is
logged and excluded in the check (`MAP_REGIONS_EXCLUDE_STYLE`).

**8. δ.** 0.2 was the edge of the pilot grid, so pilot 3 adds 0.3. In plain terms, δ = 0.2 means
"settled tracks almost never move": per-region p90 moved is 0 everywhere. So the rows that can catch
a bad identity map are purity on average linkage, the new placed-agreement row and coverage.

**Code fixes:**

- **9.** A join used to ignore changes to playlists, the novelty preference and the readiness tier
  (and, found while fixing it, tag edits).
  - The snapshot now stores a context of the non-vector inputs. A different context refuses the
    join and rediscovers.
  - Each track's hash now mixes in its tags as discovery reads them, so a tag edit marks the track
    pending and schedules the rebuild.
- **10.** Ids could be reused when the highest-id region vanished. The snapshot now carries a
  high-water mark (`nextId`, codec format 2).
- **11.** The input-version comment was corrected. An encoder or fusion-weight change makes every
  hash differ, which leads to a full rediscovery with nothing settled, so every region gets a new id.
- **12.** A region dropped for want of rows is now logged.
- **Also:** a join with pending tracks now goes through `MapRebuild.requested`, so the 24 h fallback
  timer starts.

**Pilots 3–5** (pilot data only; logs `piece4-pilot3..5-2026-10-26.txt`):

- **Pilot 3** (final harness, δ ∈ {0.1, 0.2, 0.3}) found a mechanism weakness. On the k-means
  switch, new, changed and previously unassigned tracks were poured into the one fine region their
  coarse world matched. FMA change2 on the switch failed placed agreement at every δ (0.479 vs
  0.481). At δ = 0.3, change2 purity on average linkage also failed.
- **Pilot 4** placed those tracks as a join would: nearest kept core that clears its floor. That
  fixed the switch, but the new FMA style was absorbed into old regions (own 0.33 → 0).
- **Pilot 5**, the rule frozen here: an unsettled track has no stickiness. It goes to whichever is
  nearer, its rediscovered world's centroid or the nearest kept region's core (if it clears the
  floor).
  - At δ = 0.2, every row passes on both libraries and both paths, except two:
    - **FMA change2 on average linkage, purity:** 0.7218 vs 0.7228, a miss of 0.001. The changed
      vectors are midpoints of two random tracks.
    - **The FMA novel style on the switch:** n/a, because fresh own is 0.
  - On the switch, placed agreement is 0.51–0.54 against fresh's 0.50–0.53.
  - The new style keeps its own region on average linkage (0.33 = fresh).
  - Chain moved share from the start: 1.8 % (average linkage) and 1.6 % (switch).

**Frozen:**
- **Code:** δ = 0.2, the pilot-5 placement and every other code fix above.
- **Rows:** the table above plus placed agreement ≥ fresh − 0.05, the new-style row as n/a when
  fresh is 0, and the chain on both paths.
- **The pilot's FMA new style ("Hip-Hop Beats") is excluded** in the check.
- **Known risk, named before the run:** purity on changed vectors on average linkage. In the pilot
  it missed by 0.001.
- **No more tuning.** Further pilots would fit δ and the rule to the pilot draw.

**Check command:**

```
MAP_REGIONS=1 MAP_REGIONS_MODE=check MAP_REGIONS_DELTAS=0.2 MAP_REGIONS_SEEDS=47,53,59 \
MAP_REGIONS_FMA=<s707>/fma-5000,<s808>/fma-5000,<s909>/fma-6500 MAP_REGIONS_EXCLUDE_STYLE="Hip-Hop Beats" \
./gradlew :core:smart:testAndroidHostTest --tests '*MapRegionIdentityReplay' --rerun
```

### Piece 4 freeze review, round 2 (26 October 2026): REVISE. Addressed below, then re-reviewed before the run.

**Fixes:**

- **Placed agreement measured the wrong thing (2a, 2b).** It now:
  - counts **labelled** tracks only (synthetic style, FMA top genre);
  - gives both maps the same denominator, so a track without a region counts as disagreeing;
  - gates on the **new or changed** tracks only. Previously unassigned tracks are logged as
    `looseAgree`.
- **Settled tracks could lose their region (6a).** A leftover group too small to be a region now
  returns its settled tracks to their old region. This is a code change, so one confirmation pilot
  (pilot 6, δ = 0.2 only) runs on the pilot data before the check. It does not tune anything: if it
  fails a row, that is reported, not fixed.
- **Floor (6b).** The unsettled rule uses the kept region's stored floor, measured against its
  previous centroid. This is documented in `rediscover`, as is the order of the steps (6d).
- **A tag-only edit re-placed a track (8).** Row hashes and tag hashes are now separate (codec
  format 3). A new vector rejoins. A tag edit only marks the track pending, so the rebuild renames
  its region without moving it.

**Disclosures (3, 4, 7):**

- **Known limitation on k-means devices (3).** On the switch path, which a 256 MB device takes at
  10,000 tracks, a new style is absorbed into existing regions. FMA pilot: identity own = 0, the
  k-means fresh map also 0, the average-linkage map 0.33. The row reads n/a there because today's
  device map does no better. The average-linkage values are logged for contrast. This is a property
  of the k-means fallback, not of identity, and it is out of this plan's scope ("reducing the memory
  of average-linkage regions").
- **Logged, not gating (4).** These cannot fail on the switch, because identity keeps about 13×
  more, finer regions than k-means fresh:
  - purity on the switch;
  - the switch chain's purity and coverage.
- **Pilot honesty (7).** The unsettled-placement rule was chosen *because* it fixed pilot 4's
  failures on this same pilot draw, so the pilot passes are not evidence for it. The check draws are
  new random draws, but from the same 7,989-track pool as the pilot draw, so a rule fitted to the
  pilot is only partly tested out of sample.

**Binding rows.** This table replaces the earlier ones. The check is graded on it alone.

| # | Row | Paths | Pass |
| --- | --- | --- | --- |
| R1 | settled moved churn, per case | both | ≤ 2 % |
| R2 | purity, per case | average linkage | ≥ fresh − 0.01 |
| R3 | coverage, per case | both | ≥ fresh − 0.02 |
| R4 | placed agreement (new or changed labelled tracks, unassigned = disagree), per case | both | ≥ fresh(same path) − 0.05 |
| R5 | new style's own region (novel case) | both | ≥ fresh − 0.10; n/a when fresh = 0 |
| R6 | new regions | both | none below `MIN_CLUSTER_SIZE` |
| R7 | chain, moved churn from the start | both | ≤ 2 % |
| R8 | chain purity | average linkage | ≥ fresh − 0.01 |
| R9 | chain coverage | average linkage | ≥ fresh − 0.02 |

Logged, not gating:
- purity and the chain's purity and coverage on the switch;
- gained tracks, `looseAgree` and the per-region moved distribution;
- today's moved share;
- the average-linkage contrast on the switch.

### Piece 3 result, part A (26 October 2026)

Log: `piece3-device-2026-10-26.txt`. APK built from b5f22eb8.

| Row | Result | Verdict |
| --- | --- | --- |
| completes | completed on the **average-linkage** path the formula picked (budget 176.5 MB); peak Java-heap delta 117.8 MB | PASS |
| time | 33.7 s (regions 6.2 s, identity 0.07 s, layout 27.5 s, state write 0.06 s) | PASS |
| longest gap between polls | 58.6 ms (state-write stage) | PASS |
| cancellation | **8 of 10** ended `cancelled`, 0.2–12.9 ms after the deadline. Runs 3 and 9 drew deadlines (28.4 s, 32.5 s) later than those runs took, because they were warmer than the first full run. They completed, so they are not cancellation samples. | **not met as worded** (10 of 10 required); latency ≤ 250 ms holds on every sample |
| old map intact after a cancel | the state file was untouched in all 8 cancelled runs | PASS |

- **Longest chunk read** (the stand-in for SMART's lock wait; an array copy here, not the
  engine's fusion work): 0.19 ms.
- **Path caveat.** In this bare test process the formula allowed average linkage. In the app the
  engine's index and the library also sit on the heap, so a 256 MB phone at 10,000 tracks is likely
  to take k-means, as round 3 found. That path was not exercised on the device here.

**Supplementary cancellation set, declared before it runs.**
- 10 deadlines, uniform in [0, 26,000 ms), which is 80 % of the warm full run, so each is a real
  cancellation.
- Same device and data.
- **Pass:** all 10 end `cancelled` ≤ 250 ms after the deadline, with the state file untouched.
- It supplements the frozen row and does not replace it. Pilot 6 runs on the host at the same time;
  this is disclosed.

**Supplementary cancellation set (result).**
- Log: `piece3-device-supplementary-2026-10-26.txt`. APK built from the 901a4026 tree.
- **10 of 10 ended `cancelled`**, 0.14–12.4 ms after the deadline, with the state file untouched.
  The deadlines fell in the region stage 2 times and in the layout stage 8 times.
- **PASS.**
- The full run repeated part A: 35.1 s, peak heap delta 119.0 MB, longest gap between polls 58.0 ms.

### Piece 3, part B results (26 October 2026)

- **Android cold launch: PASS.** Log: `piece3-android-coldlaunch-2026-10-26.txt`.
  - JobScheduler started a fresh process for WorkManager's `SystemJobService`.
  - `MapRebuild.run` returned `NOT_NEEDED` in 57 ms (nothing pending on the emulator), and WorkManager
    recorded `SUCCESS`.
  - An earlier run in the live process returned `NOT_READY` (the launch's own analysis was running)
    and was retried with backoff, as designed.
- **iOS simulator: NOT RUN.** Log: `piece3-ios-simulator-2026-10-26.txt`.
  - `_simulateLaunchForTaskWithIdentifier` reports that no task request with that identifier was
    scheduled.
  - The submit at launch never reached the BackgroundTasks framework. `BGTaskScheduler` is not
    available on the Simulator; Apple supports this only on a device under the debugger.
  - It needs one real-iPhone run; the parent session raises it with the owner.
- **Expiry and cancel:**
  - Part A covers the work's cancellation and that the old map stays intact.
  - The iOS expiration path (`invokeOnCompletion` completes and reschedules) and WorkManager's
    `isStopped` poll are checked by reading the code, not on a device.

### Reboot: RECEIVE_BOOT_COMPLETED is dropped (decided 26 October 2026)

WorkManager's library manifest declares `RECEIVE_BOOT_COMPLETED` so that scheduled work survives a
reboot. The parent session decided to drop it: privacy posture, the F-Droid listing, and a lost
pending rebuild costs nothing.

- `androidApp`'s manifest removes it with `tools:node="remove"`, as it already does for Media3's
  `ACCESS_NETWORK_STATE`.
- **A reboot drops a pending rebuild until the next app start.** At every process start (Android
  `LatentJamApplication.onCreate`, iOS `MainViewController`), `MapRebuild.resumePending` reads the
  stored state. If the layout changed since its last full build, is stale, or regions have pending
  tracks, it enqueues the rebuild again; a stale map also gets its debug marker back.
- Tests: `MapRebuildResumeTest`, which covers:
  - a change that is owed;
  - stale;
  - regions only;
  - nothing owed;
  - the flag off;
  - an unreadable state.
- The merged manifest is checked to have lost the permission.

**Pilot 6 (confirmation after the round-2 fixes, δ = 0.2, pilot data, no tuning).**
- Log: `piece4-pilot6-2026-10-26.txt`.
- **Synthetic s7 passes every binding row on both paths.**
- **FMA pilot fails three binding rows:**
  - **R2:** change2 purity on average linkage, 0.7211 vs 0.7228. This is the known risk named
    above.
  - **R4:** add2 placed agreement on average linkage, 0.537 vs fresh 0.590 − 0.05 = 0.540.
  - **R4:** the novel case on the switch, placed agreement 0.750 vs 0.833 − 0.05 = 0.783. This falls
    under the k-means limitation disclosed above.
- R1, R6, R7 and every other row pass. The chain's moved share from the start is 1.6 % on both
  paths.
- As declared, nothing is changed in response: the check runs with this code and these rows, and
  **is expected to be at risk on R2 and R4 for FMA**.

### Piece 4 freeze review, round 3 (26 October 2026): REVISE. Addressed below; the check then runs without another round, as the reviewer allowed.

**1. Verdict rule.** Piece 4 PASSES only if every cell of R1–R9 passes, on every library × case ×
path where the row applies. n/a never counts as a pass, and it is reported as n/a.
- **Any FAIL means piece 4 FAILS as frozen**, and the flag stays off.
- There is no re-run with a changed δ, rule or rows. A later attempt is a new frozen round on new
  draws.

**2. R4 on the novel case's switch path stays binding.** The disclosed k-means limitation covers
R5 only. An R4 failure there counts like any other.

**3. R4's granularity is accepted as frozen.** In the novel case, R4 counts only the labelled new
tracks: 12 on the FMA pilot. One track is 0.083, more than the 0.05 tolerance. The alternatives
would happen to rescue pilot 6's known failure (9/12 vs 10/12):
- a tolerance of max(0.05, 1/n);
- n/a below 30 tracks.

So none is adopted now. The  count is logged for every case.

**4. Correction to round 2's fix 8.**
- **The earlier claim was wrong.** In that code, a tag-only edit marked the track pending, so the
  rebuild treated it as unsettled and could move it.
- **The code now matches the claim.** A tag-only edit sets a separate  flag (codec
  format 3, bit 2 of the flags byte). The track then owes the rebuild but **stays settled**, so
  sticky placement applies at δ.
- **Naming.** A matched region keeps its old name: that is the plan's identity rule. A tag edit
  changes names only for regions that are new or re-formed at the rebuild.
- The harness never edits tags, so the check's numbers are unaffected.

**5. Grading map.** Grade from the `delta=0.2` lines.

| Row | Log line | Field | Paths |
| --- | --- | --- | --- |
| R1 | per case | `churn=` | both |
| R2 | per case | `verdict purity=` | `switch=false` only |
| R3 | per case | `coverage=` | both |
| R4 | per case | `placed=` (the verdict, not the count) | both |
| R5 | per case | `own=` | novel lines only |
| R6 | per case | `minSize=` | both |
| R7 | `chain` | `churn=` | both |
| R8 | `chain` | `purity=` | `switch=false` only |
| R9 | `chain` | `coverage=` | `switch=false` only |

Ignore these fields:
- `own=PASS` on lines that are not novel;
- the purity and coverage verdicts printed for `switch=true` where the table says
  `switch=false` only.

**Disclosures.**
- **R3 on the switch path cannot fail.** Identity coverage is about 0.89 there, against about 0.70
  for k-means fresh, and settled tracks are kept.
- **The R1 and R7 denominators include settled tracks that had no region before.** That is about
  22 % on FMA, so it dilutes moved churn. This is consistent with every pilot.

**Command:**

```
MAP_REGIONS=1 MAP_REGIONS_MODE=check MAP_REGIONS_DELTAS=0.2 MAP_REGIONS_SEEDS=47,53,59 \
MAP_REGIONS_FMA=/private/tmp/claude-501/-Users-nichitabulgaru-Documents-LJ-latentjam--claude-worktrees-research-map-cost-91141c/a70fc488-7434-4db2-8aee-455a6707983f/scratchpad/fma-p4-s707/fma-5000,/private/tmp/claude-501/-Users-nichitabulgaru-Documents-LJ-latentjam--claude-worktrees-research-map-cost-91141c/a70fc488-7434-4db2-8aee-455a6707983f/scratchpad/fma-p4-s808/fma-5000,/private/tmp/claude-501/-Users-nichitabulgaru-Documents-LJ-latentjam--claude-worktrees-research-map-cost-91141c/a70fc488-7434-4db2-8aee-455a6707983f/scratchpad/fma-p4-s909/fma-6500 \
MAP_REGIONS_EXCLUDE_STYLE="Hip-Hop Beats" MAP_REGIONS_LOG=<scratch>/p4-check.log \
./gradlew :core:smart:testAndroidHostTest --tests '*MapRegionIdentityReplay' --rerun
```

The FMA paths are the scratchpad draws made by `map_gate_fma_dump.py --seed 707|808|909`. They are
not committed; they can be regenerated from the pool.
