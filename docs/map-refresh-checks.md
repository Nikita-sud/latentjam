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
