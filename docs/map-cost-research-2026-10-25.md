# Making the Map cheap to build and keep current — research, 25 October 2026

The owner asked whether the library Map needs a full recomputation on every change, and how it
can become much cheaper on a phone while staying useful and accurate. This note does four things:

- collects outside work (products, papers, engineering notes);
- maps it onto today's code;
- ranks the options, with a predeclared gate for each;
- reports a cheap offline replay that sharpens the ranking
  ([below](#measurement-what-a-library-change-costs-and-moves-today)).

The replay used synthetic data only, never the owner's library. Nothing in the app changed.
Review: three fresh adversarial rounds. Round 1 (REVISE) was answered by extending the replay and
rewriting the recommendation. Round 2 (REVISE) brought text and plan fixes. Round 3 was READY
with minors, now fixed; see [Review log](#review-log).

## Summary

- **Every change costs a cold build today.**
  - Any change to the vector fingerprint recomputes the whole layout
    (`MapPageCache.layout` → `LibraryLayout.compute`).
  - The warm start does not shorten the run. It is still 1,000 iterations, the first 250 with
    early exaggeration ×12.
  - At 10,000 tracks on the `LJ-api30` emulator that is PCA 8.6 s + neighbours/affinities 7.4 s +
    optimizer 75 s ≈ 91 s ([10k note](map-scaling-10k-2026-10-15.md)).
  - In the replay a warm rerun takes as long as a cold build (≈ 16 s on the host).
- **The warm start barely holds the map still, even when nothing changed.**
  - Stored positions are normalized to the 0..1 box. The raw t-SNE map has an RMS radius of about
    47, against about 0.3 for the box, so the warm start begins ~150× compressed. Early
    exaggeration then re-forms it.
  - Rerunning today's path on an **unchanged** library keeps only **53–55 %** of each track's 15
    nearest map neighbours. With +1 % new tracks it keeps 47 %.
  - `alignToPrevious` keeps the map in the same frame, but locally it is reshuffled every time.
  - Rescaling the start alone does not help (today's schedule from the raw scale keeps 43 %). The
    exaggeration has to go as well.
- **Top recommendation: "refresh, don't rebuild" for small changes.**
  - Keep the raw scale.
  - Place every new **or changed** track at the mean of its 5 nearest unchanged tracks in PCA-50.
  - Run **150 iterations without exaggeration** over all rows.
  - Fall back to today's full build when the change is large (> 3 %) or forms a new cluster.
  - At 10,000 tracks, over two seeds, +1 %, +5 % and flagged changes, the refresh took **4.8–5.1 s
    against ~16 s** on the host (0.31×).
  - Unchanged tracks kept **83–86 %** of their neighbours after +1 % (today 47–48 %).
  - trust@15 stayed within 0.001 and recall@15 within 0.005 of today's (+1 %, +5 % and flagged
    changes). New/changed tracks' own recall@15 was 0.91–0.97× today's.
  - Only +1 % passes every *measured* gate row on both seeds. Time, heap, router, chain, FMA and
    held-out seeds are not measured yet. At about 5 % (uniform or flagged changes),
    overlap with a cold build passes in 2 of 4 seed-cases.
  - Projected on the emulator: ~20–27 s against ~91 s; ~5–12 s with cached inputs (approach 3).
    These projections are unverified.
- **Three hard limits, found by the replay.**
  1. **Changed rows must be named.** If a re-analysed track keeps its stale position, the short
     refresh cannot move it: its recall@15 drops to 0.13 against 0.27. The opaque coverage
     fingerprint cannot say which rows changed, so a per-track vector hash is a prerequisite.
  2. **A whole new style is not a small change.** Placing a never-seen cluster from its nearest
     old tracks scatters it into neighbouring clusters: its own class@15 drops from 1.00 to
     0.78–0.84, and its recall@15 to 0.52–0.71× today's. That needs a novelty check that routes it
     to the full build.
  3. **It drifts from a cold map as change grows.** Against the earlier gate's "overlap with
     full Barnes–Hut ≥ seed noise − 0.10" row, +1 % passes on both seeds, +5 % passes on one, and
     +10 % fails on both. Flagged 5 % changes fail on seed 11.
- **This is not the rejected "sample and place".**
  - That design rejected on stability: placing 70 % of the library from a cold 30 % sample moved
    old tracks 2–3× more than today. Its best variant already ran 250 full iterations over every
    row.
  - What differs here is the start. 95–99 % of the rows begin at their optimized positions, *at
    true scale*, with no exaggeration to re-form them.
  - The replay shows this is what holds the map still. In the first run (seed 7, +1 %; second section of the log), 150 iterations without exaggeration from the compressed start kept 35 % of neighbours, against 88 % from the raw scale.
- **Outside practice agrees with this shape.**
  - Shipping products recompute offline and ship a static map (Every Noise, Music Galaxy, Google's
    AI Experiments, Latent Scope), or store a map and fill in stragglers (AudioMuse-AI).
  - Nomic Atlas withdrew in-place map updates over consistency problems.
  - Apple Photos, the closest on-device analogue, assigns new items to existing clusters at once
    and re-clusters overnight while charging.

## Current design (verified in code, `claude/research-map-cost-91141c` at 2619533d)

| Piece | Where | What it does |
| --- | --- | --- |
| Cache key | `composeApp/.../MapPageCache.kt` | The layout is reused only when `fingerprint` equals the vector-coverage fingerprint (one opaque 64-bit hash) and `LibraryLayout.covers` holds. While analysis runs (`!audioVectorsReady`), a layout computed here less than 5 min ago (`MAP_LAYOUT_REUSE`) is shown again for the same tracks, though their vectors have moved. Otherwise `compute(stored ?: disk)` runs. |
| Projection | `LibraryLayout.compute(rows, previous, …)` | Mean pass → `Pca.reduceStreamed` (subspace iteration to 50 components, streamed, bit-identical to the held path) → backend → `alignToPrevious` → `normalize` to 0..1 (one scale factor for both axes). `MAX_TRACKS` 10,000; `EXACT_MAX_TRACKS` 2,000. |
| Warm start | `LibraryLayout.warmStart` + `Tsne.initialPositions` | Stored 0..1 positions. Tracks without one start at the centroid of those that have one. Adds 1e-3 noise. A cold start is uniform random ±0.01. |
| Optimizer | `BarnesHutTsne.embed` | Exact 60-NN (brute force over PCA-50), perplexity 20, θ 0.5, **1,000 iterations, the first 250 with early exaggeration 12**, learning rate 200, momentum 0.5 → 0.8. The `iterations`, `exaggerationIterations` and `scheduleOffset` parameters exist; production uses the defaults. |
| Memory | `MapMemoryBudget.projectionStreamedBytes` | 39.4 MiB at 10,000 × 1,344, streamed. |
| Regions | `App.kt` ~1290 → `LibraryWorlds.discover` → `AverageLinkageClustering` (exact ≤ 3,000; above that, micro-clusters seeded by a stable 3,000 sample). Spherical k-means `TrackClustering` is the fallback. | Rerun whenever the vector fingerprint, tags, playlists, preference or readiness tier changes. Builds its own fused space, which is the visit's heap peak (~155 MB on `LJ-big36`). |

## Measurement: what a library change costs and moves today

**Harness.** `tools/research/MapIncrementalReplay.kt`. Copy it into
`core/smart/src/androidHostTest/...` to run; the command is in its header. The raw log is
`tools/research/map-incremental-replay-2026-10-25.txt`.

**Data.** Synthetic `hierarchicalFixture`, 10,000 × 1,344-d, seeds 7 and 11. Seed 7 is the
sample-and-place gate fixture, so these results are a **pilot**: the parameters and thresholds
below were chosen after seeing them, and the gate must run on new seeds and FMA.

**Scenarios.** The "before" map is built cold. The "after" map is built warm from it, through the
real `LibraryLayout.computeWith` (PCA, warm start, alignment, normalization).

- `same` — nothing changes; the noise floor.
- `addP` — P % of tracks, spread evenly, are new.
- `style` — one whole style (331 / 378 tracks, about 3.5 %) is new: a cluster the old map never
  saw.
- `change5` — 5 % of tracks keep their id but their vector moves halfway to another random
  track's, standing in for analysis replacing a provisional vector.

**Metrics.**

- trust@15 and recall@15 against the full library's PCA-50, as in `MapSampleReplay`.
- `RF`: recall@15 of the new/changed tracks only.
- `CF`: the share of the new/changed tracks' map neighbours in the same style.
- *kept15* and *move*: over unchanged tracks, before vs after, in the 0..1 box.
- *overlapCold*: 15-NN overlap with a cold build of the final library. For scale, the noise between
  two cold seeds is 0.535 (seed 7) and 0.567 (seed 11).
- Host JVM, single thread. Times include PCA.

**Variants.**

- `today` — the production path.
- `today-s` — today's schedule from the start rescaled to the previous raw spread.
- `warm-150s` — 150 iterations, no exaggeration, late momentum, rescaled start. Changed rows keep
  their stale position; new rows start at the centroid.
- `place+Ns` — new/changed rows go to the mean of their 5 nearest unchanged rows, then N such
  iterations.

**Unchanged library (`same`), seed 7 / seed 11**

| Variant | Time | kept15 | median move | trust@15 | recall@15 |
| --- | ---: | ---: | ---: | ---: | ---: |
| today | 16.1 / 16.0 s | 0.550 / 0.532 | 0.032 / 0.030 | 0.9837 / 0.9846 | 0.2688 / 0.2675 |
| today-s (rescaled start only) | 16.7 / 16.6 s | 0.439 / 0.428 | 0.027 / 0.029 | 0.9829 / 0.9839 | 0.2649 / 0.2636 |
| warm-150s | 4.8 / 4.8 s | 0.969 / 0.969 | 0.001 / 0.001 | 0.9837 / 0.9849 | 0.2675 / 0.2667 |

**place+150s against today, 10,000 tracks** (each cell is seed 7 / seed 11)

| Scenario | Time (today ≈ 16 s) | kept15 refresh vs today | trust@15 refresh vs today | recall@15 refresh vs today | RF refresh vs today | overlapCold refresh (today) |
| --- | ---: | --- | --- | --- | --- | --- |
| +1 % | 4.9 / 4.8 s | 0.860 / 0.827 vs 0.467 / 0.475 | 0.9838 / 0.9844 vs 0.9840 / 0.9845 | 0.2686 / 0.2652 vs 0.2696 / 0.2666 | 0.249 / 0.260 vs 0.273 / 0.269 | 0.492 / 0.497 (0.533 / 0.551) |
| +5 % | 5.0 / 5.0 s | 0.699 / 0.674 vs 0.422 / 0.404 | 0.9837 / 0.9841 vs 0.9839 / 0.9847 | 0.2661 / 0.2639 vs 0.2690 / 0.2669 | 0.254 / 0.252 vs 0.272 / 0.268 | 0.458 / **0.443** (0.536 / 0.527) |
| +10 % | 5.1 / 5.1 s | 0.599 / 0.552 vs 0.366 / 0.343 | 0.9834 / 0.9838 vs 0.9838 / 0.9847 | 0.2628 / 0.2610 vs 0.2689 / 0.2670 | 0.247 / 0.253 vs 0.267 / 0.269 | **0.426 / 0.401** (0.545 / 0.554) |
| changed 5 %, flagged | 5.0 / 5.1 s | 0.657 / 0.654 vs 0.394 / 0.387 | 0.9832 / 0.9842 vs 0.9840 / 0.9845 | 0.2644 / 0.2638 vs 0.2694 / 0.2668 | 0.251 / 0.256 vs 0.271 / 0.277 | 0.441 / **0.441** (0.542 / 0.552) |
| changed 5 %, **not flagged** (warm-150s) | 4.9 / 5.1 s | 0.690 / 0.694 | **0.9731 / 0.9749** | **0.2574 / 0.2579** | **0.126 / 0.136** | **0.404 / 0.410** |
| new style (~3.5 %) | 4.9 / 4.9 s | 0.564 / 0.567 vs 0.348 / 0.346 | **0.9791 / 0.9791** vs 0.9841 / 0.9843 (seed 7 sits exactly on the threshold at 4 decimals) | **0.2599 / 0.2573** vs 0.2692 / 0.2659 | **0.173 / 0.119** vs 0.245 / 0.230; CF **0.84 / 0.78** vs 1.00 | **0.410 / 0.410** (0.543 / 0.547) |

Bold marks a result that would fail the gate in [approach 1](#1-refresh-at-true-scale-recommended-first).
Overlap thresholds are the seed noise minus 0.10: 0.435 for seed 7 and 0.467 for seed 11.

**What this shows.**

1. **The optimizer is nearly the whole cost, and the warm start does not cut it.**
2. **Today's map reshuffles on every recompute.** Even when nothing changed, it keeps only about
   half of the local neighbourhoods. The cause is the compressed start together with exaggeration.
   Removing both holds the map still (0.97 kept on `same`).
3. **Newcomers need placement.** Started at the centroid at true scale they cannot travel (RF
   0.05 at +1 %; see the log). Placement alone clumps them (RF 0.15–0.17). Placement plus 150
   iterations recovers 0.91–0.97× of today's RF. 300 iterations adds about 0.005 RF and costs 2 s
   and some stability; 50 iterations loses 0.01–0.03 RF.
4. **Changed rows behave like newcomers, but only if they are flagged.** Unflagged, they halve
   their recall and drag trust@15 down by 0.01.
5. **A new cluster is the failure case the literature predicts**: placement "cannot create new
   clusters" (Kobak & Berens). Interestingly, `warm-150s` (all newcomers start together at the
   centroid) keeps the new style together (CF 1.00, RF 0.20 / 0.19). Placement is what tears it
   apart. A novelty check should therefore route such changes to the full build.
6. **Agreement with a cold map falls as change grows.** That sets the refresh threshold near
   +1–3 %, with today's full build above it.
7. **Projected emulator cost at 10,000.** PCA 8.6 s + neighbours 7.4 s + 150 iterations. The
   per-iteration cost on the emulator is between 0.027 s (250 un-exaggerated iterations in
   6.7 s, sample-and-place run) and 0.075 s (today's 1,000-iteration average). That gives
   **~20–27 s against ~91 s**. Caching PCA and the neighbour graph (approach 3) would leave
   ~5–12 s. Not measured on a device.

**Not measured:** chains of refreshes (drift), deletions, real music data (FMA), device time and
heap, regions.

## Ranked approaches

Ranked by expected saving against accuracy and stability risk, for the common case: a few tracks
added or re-analysed in a library that already has a Map.

| # | Approach | Saving (10k, emulator) | Quality / stability risk | Evidence |
| --- | --- | --- | --- | --- |
| 1 | Refresh at true scale: per-track change detection + kNN placement + 150 un-exaggerated iterations; full build above ~3 % or on novelty | ~91 s → ~20–27 s per small change, and far *more* stable | Needs changed-row hashes; new clusters and > 5 % changes must fall back; drift over chains unmeasured | Replay above; openTSNE, Poličar 2019 |
| 2 | Instant placement (openTSNE-style `transform`) while analysis runs; refresh deferred | Small change → < 2–3 s, refresh at thresholds or on charge | Placed tracks clump until the refresh (RF 0.15 vs 0.27) | Poličar 2019/2023, Kobak & Berens 2019, AudioMuse-AI, Apple Photos |
| 3 | Cache the inputs: frozen PCA basis and mean + patched 60-NN graph and affinities; regions by nearest-region assignment | Refresh ~20–27 s → ~5–12 s; regions seconds → milliseconds | Frozen basis drifts from the true PCA; regions go stale until rediscovered | PyNNDescent `update`, HNSW, Apple Photos, Zhong 2005 |
| 4 | Cheaper cold builds: learning rate n/12, KL-based stopping, PCA start, faster repulsion, threads | First build ~91 s → perhaps 40–60 s | Low if gated; every map changes once (`VERSION` bump) | Belkina 2019, Linderman 2019, Kobak & Linderman 2021 |
| 5 | Parametric / learned projection (parametric t-SNE/UMAP, NNP) | Placement in ms | **High**: our own pilot lost half the recall | [MLP pilot](map-parametric-mlp-pilot-2026-10-13.md), Espadoto 2020, Sainburg 2021 |

**Not ranked: already rejected, and nothing new answers why they failed.**

- **Sample and place, and landmark layouts.** LSP, LAMP, PLMP and Landmark MDS are the same
  family: fast, but below t-SNE on neighbourhoods. Rejected 16 Oct
  ([note](map-sample-place-2026-10-16.md)).
- **GMM/vMF regions.** Rejected 28 Sep (memory note *Map worlds GMM spike*).

### 1. Refresh at true scale (recommended first)

**Idea.** A small library change becomes a short, local correction of the stored map instead of a
rerun of the full schedule.

**Plug-in.**

1. **Name the changed rows (prerequisite).** Store a 63-bit hash of each track's fused row (the snapshot codec keeps 63 bits per float pair).
   - Hash it in the streamed mean pass in `SampledTsne.reduce`, which `LibraryLayout.computeStreamed`
     calls before `Pca.reduceStreamed` (which expects centred rows). That pass already reads every row through `LibraryVectorRows.read`, so hashing costs no
     extra pass. `LibraryVectorRows` itself only serves chunks.
   - The layout snapshot in `IndexStore` holds one uniform width: two finite floats per entry,
     with the finite-pattern codec. Per-track hashes therefore need their own keys at 63 bits per
     float pair, roughly doubling the entries and key strings. A small separate store is
     probably cleaner. Budget well over 80 KB either way.
   - A track is *flagged* if it has no usable position or its hash differs.
2. **Store the raw spread.** Keep the RMS radius of the raw t-SNE output, before alignment and
   normalization, rewritten on every run. It is one value under the `\u0000lj-layout-meta:`
   pattern, next to `STORED_FINGERPRINT_KEY`, padded to the snapshot's two finite floats per entry.
3. **Refresh branch in `LibraryLayout.compute(rows, previous, …)`.** It applies when all of these
   hold:
   - flagged ≤ 3 % of the library (the gate picks 1, 2 or 3 %);
   - the novelty check passes. It is defined by distance, not by who the neighbours are: a flagged
     row is *novel* if its distance to its nearest unflagged row, in the reduced space, exceeds 2×
     the library's median distance to the 5th nearest neighbour. The refresh applies only if at
     most 10 % of flagged rows are novel. (A neighbour-share rule would misfire on a new album,
     whose tracks are each other's nearest neighbours.) The 2× and 10 % are frozen now; the gate
     tests them;
   - the placed share since the last full build is ≤ 10 % (the gate's chain result sets it);
   - the stored layout has a refreshable version.

   The refresh itself:
   - rescale `previous` to the stored spread;
   - place each flagged row at the mean of its 5 nearest unflagged rows in the reduced space;
   - run `BarnesHutTsne.embed(…, iterations = 150, exaggerationIterations = 0,
     scheduleOffset = 250)` with all existing parameters;
   - then `alignToPrevious` and `normalize`, as today.

   Otherwise the path is today's build.
   - **Interface.** `MapEmbeddingBackend.embed` has no schedule parameters, so it must gain them
     (or a refresh variant) rather than the refresh calling `BarnesHutTsne.embed` directly and
     skipping backend selection.
   - **Backends.** Libraries of ≤ 2,000 tracks use `ExactMapEmbedding` → `Tsne.embed`, which has
     no `iterations` / `exaggerationIterations` / `scheduleOffset` parameters yet. The
     experimental native `BarnesHutMapEmbedding` (qdtsne) has none either. Both need the same
     refresh schedule, or the refresh applies to `ApproximateMapEmbedding` only, and the gate's 1k
     FMA library checks whichever is chosen.
   - **Limits.** Every fallback full build is today's reshuffling path (kept15 ≈ 0.35–0.55), so
     the stability gain is lost each time one runs. During first analysis, or after a change of
     fusion model, nearly every row is rehashed. The refresh then does not apply, and approach 2
     (provisional placement) is what helps there.
4. **Memory.** The same arrays as today, plus the hash table. Peak heap is gated anyway.

The parameters are frozen now, before the gate: k = 5, 150 iterations, no exaggeration, learning
rate 200, late momentum.

**Measure.**

- Every scenario above, on **held-out seeds 13, 17 and 19**.
- Two scenarios the pilot did not cover:
  - **album-clustered additions**: groups of 10–20 tracks drawn from one style, about 1 % in all.
    They must stay on the refresh path.
  - **sub-threshold novel styles**: one held-out style subsampled to 0.5 %, 1 % and 2 %, which the
    size rule alone would let through.
- **FMA-derived** libraries from the 22 Oct general stand at 1k, 5k and 10k, with genre in place of
  style.
- A **chain**: ten refreshes of +1 %, plus one of −2 % (removals), compared with a cold build of
  the final library.
- The novelty router on `style` vs `add5`.
- Emulator time, peak heap on `LJ-big36` (192 MB) and `LJ-api30` (256 MB), and cancellation.

**Predeclared gate.** Comparisons are against today's warm rerun on the same input, and every
pass must hold on every seed and library. All inputs are synthetic or FMA, never the owner's
library.

| Metric | Pass |
| --- | --- |
| trust@15 (all tracks) | ≥ today − 0.005 |
| recall@15 (all tracks) | ≥ today × 0.97 |
| class@15 / genre@15 (all tracks) | ≥ today − 0.010 |
| RF (flagged tracks' recall@15) | ≥ today × 0.85 |
| CF / genre of flagged tracks | ≥ today − 0.05 |
| overlap with a cold build of the final library | ≥ cold-seed noise − 0.10 |
| kept15 (unchanged tracks) | ≥ today + 0.10 |
| p90 move (unchanged tracks) | ≤ 0.5× today's and ≤ 0.02 of the map width |
| after the chain: trust, recall, RF of the final map | trust ≥ cold build − 0.005, recall ≥ cold × 0.97, RF ≥ cold × 0.85 (cold build of the final library, same seed) |
| novelty router | sends every sub-threshold novel style (0.5–2 %) to the full build; keeps ≥ 90 % of uniform +1 % and album-clustered cases on the refresh |
| time at 10,000 (emulator) | ≤ 0.4× today |
| peak heap at 10,000 (`LJ-big36`) | ≤ today's + 2 MB |
| cancellation | every phase stops within 250 ms, as today |

If the gate passes only at some thresholds, the refresh is adopted at the largest flagged share
(3 %, else 2 %, else 1 %) that passes. The placed-share budget is predeclared as a ladder: 10 %,
then 5 %, then 3 %. The chain test is run at each step, the largest that passes is adopted, and if
3 % fails the refresh is rejected.

### 2. Instant placement now, refresh later

**Idea.** The openTSNE `transform` pattern, as AudioMuse-AI and Apple Photos do it.
- When ≤ 2 % of tracks are new or changed (named by approach 1's row hashes), show them at once,
  placed into the frozen map.
- Compute neighbours in PCA-50 against the stored basis. Then, optionally, run ≤ 250 iterations
  that move only the newcomers against the fixed quadtree of the old map.
- `QuadTree.repelAt` already exists from the sample-and-place prototype. Use openTSNE's
  transform settings: perplexity 5, learning rate 0.1, exaggeration 1.5, gradient clipping.
- The full refresh (approach 1) waits until the change reaches 2 %, the Map is opened after
  analysis ends, or the device is charging. Use WorkManager with `setRequiresCharging` /
  `setRequiresDeviceIdle` under ~10 min, or iOS `BGProcessingTaskRequest(requiresExternalPower)`.

**Plug-in.**
- `MapPageCache.layout` gains a third outcome next to "matches" and "recompute": "patch".
  - Disk layout covers ≥ 98 % of the ids → place the rest → return a layout marked *provisional*
    (not saved under the new fingerprint, or saved with a provisional flag).
  - This replaces the 5-minute `MAP_LAYOUT_REUSE` crutch during analysis. That crutch shows the
    same tracks at positions computed from their stale vectors.
- It needs the PCA basis and mean on disk (50 × 1,344 floats ≈ 269 KB) to project new rows
  without a full PCA pass. That is the first half of approach 3.

**Measure.**
- Latency to a drawn Map after +1 track, +1 % and +2 %.
- recallNew@15 and the *edge pile-up* count (Islam & Fleischer 2026: placed points crowding at
  cluster edges).
- Whether provisional maps are visibly worse to a blind judge, rated only on FMA or synthetic
  libraries: a 40-pair blind check as in the
  general stand of 22 Oct, people-referenced as the queue-quality rule asks.

**Predeclared gate.**
- Old tracks move 0 (by construction).
- recallNew@15 ≥ 0.80 × today's. In the pilot, placement with no iterations reached only
  0.55–0.63×, so the newcomer-only iterations must recover most of the gap.
- Edge pile-up ≤ 1.5× a full refresh's.
- Time to draw at 10,000 + 1 % ≤ 3 s on the emulator.
- Blind judge preference for provisional vs refreshed has a lower bound ≥ 0.40. It may be worse,
  but not clearly worse, since it is temporary.
- Fails → keep approach 1 alone.

### 3. Cache what does not change

**Idea.** Stop paying PCA (8.6 s) and the 60-NN search (7.4 s) for rows that did not change.
- **Fixed PCA basis.** Project with the stored basis. Refit only when the change since the last
  fit exceeds a threshold, e.g. explained variance of new rows under the old basis drops more than
  2 % below the fit's.
- **Patched neighbour graph.** With a frozen basis *and a frozen centering mean* (and the same
  per-row normalization), old rows' reduced vectors are bit-identical. So
  only rows whose neighbour lists can contain a newcomer change. Brute-forcing m new rows
  against n costs m·n·50 multiply-adds: milliseconds for m = 100. Each old row's 60-list is
  updated when a newcomer beats its 60th distance. Bandwidths are recomputed for touched rows
  only.
- No ANN library is needed at 10,000. Exact brute force at 50-d is ~2.5·10⁹ multiply-adds. If it
  is ever needed (Map above 10k, or full 1,344-d), USearch (Apache-2.0, Android + iOS) or a
  clean-room NN-Descent fit. jelmerk/hnswlib is JVM-only.
- **Regions.** Assign new and changed tracks to the nearest existing region by centroid cosine,
  as Apple Photos does with running-mean clusters. Run `LibraryWorlds.discover` again only on
  the same thresholds as the refresh. When it reruns, match new regions to old by member overlap
  (Hungarian assignment) so names and colours persist. This also removes the visit's 155 MB heap
  peak from the common path.

**Plug-in.**
- `IndexStore` keys for the basis, mean and the 60-NN lists (10,000 × 60 × 8 B ≈ 4.8 MB).
- `BarnesHutTsne.embed` accepts precomputed `Neighbours` / `p`; it computes them internally today.
- The region cache in `App.kt` (`builtWorldsKey`) gains a patch path next to its equality check.

**Measure.**
- Basis drift: recall@15 and trust@15 of the refreshed map under the stale basis vs the refitted
  one, after +1 %, +5 % and +10 %, on synthetic and FMA libraries.
- Region assignment vs rediscovery: ARI and region-name agreement.
- Storage size and load time.

**Predeclared gate.**
- recall@15 under the stale basis ≥ 0.97 × refitted at the refit threshold.
- Patched kNN lists identical to a fresh exact search on the same reduced rows (bit-exact test).
- Region assignment: ARI vs rediscovery ≥ 0.90 and ≥ 90 % of region names unchanged up to the
  rediscovery threshold.
- Refresh time ≤ 0.6× approach 1's.

### 4. Cheaper cold builds (first build, version bumps)

Approaches 1–3 do not help the first build or a cold rebuild.
- **Learning rate n/12** (Belkina et al. 2019; Kobak & Berens 2019): 833 at 10,000 instead of
  200, so convergence takes fewer iterations.
- **KL-based stopping** of exaggeration and of the run (opt-SNE).
- **A PCA start** scaled to σ = 1e-4 instead of today's uniform random ±0.01 start
  (`Tsne.initialPositions`). Kobak & Linderman 2021 show random starts lose global structure, and
  an informative start may allow fewer exaggeration iterations.
- **FFT/grid repulsion** (FIt-SNE: ~12× faster gradients at 1M; at 10,000 the gain over
  Barnes–Hut is smaller, maybe 2–3×).
- **Multi-threading** the neighbour search and repulsion. The native `qdtsne` backend is
  single-threaded.

Expected: the optimizer's 75 s might drop to 30–40 s. It needs a `VERSION` bump, so every map
changes once.

**Gate.** It is the sample-and-place gate with the candidate in place of sample-and-place: same
quality rows vs today's full Barnes–Hut (trust −0.010, recall −10 % rel., class/genre −0.020,
overlap ≥ seed-noise − 0.10), time ≤ 0.6× at 10,000, and all inputs synthetic or FMA.

### 5. Parametric projection (not recommended now)

Parametric UMAP keeps trustworthiness within 0.3–1.5 points of UMAP on image benchmarks and
places a point in ~0.5 ms (Sainburg 2021; Islam & Fleischer 2026). But our own [MLP
pilot](map-parametric-mlp-pilot-2026-10-13.md) got 18–25 % held-out recall@15 for a direct
2-D network, against 55 % for the Kotlin projection. A coordinate-regression network (NNP) loses
~12 % neighbourhood hit (Espadoto 2020). Training on the device also contradicts "cheap".
Revisit only if approaches 1–3 fail, and then as a parametric t-SNE *loss* (not coordinate
regression) with old-point anchoring (the landmarked Parametric UMAP recipe).

## What the literature says

- **Why placement-only fails, and the standard fix.** Kobak & Berens place new points at the
  median of 10 neighbours with no optimization, and accept that it cannot create new clusters.
  Poličar et al. show placement alone clumps. They fix it with 250 iterations against a fixed
  reference at learning rate ~0.1 (200 makes new points "shoot off"), reporting 1–5 %
  misplacement. UMAP's transform piles points at cluster edges (2–3× the joint embedding; Islam
  & Fleischer 2026).
- **Stability vs quality compete** (Vernier et al. 2020). Per-frame t-SNE is the least stable of
  all methods tested; dynamic t-SNE (a movement penalty) is about as good spatially and more
  stable but sensitive to its weight. Our replay shows the cheapest stability lever is starting at
  the right scale *and* dropping early exaggeration. Either alone does not help: rescaled with exaggeration keeps 0.43–0.44 on an unchanged library, and compressed without it keeps 0.35 at +1 % (first run).
- **Global structure comes from initialization** (Kobak & Linderman 2021). Today's cold build
  starts from random noise, which is a candidate for approach 4. Procrustes alignment
  (`alignToPrevious`) is the standard way to keep successive maps in one frame.
- **kNN is not the bottleneck at 10k** (FIt-SNE timings; exact 50-d search is ~10⁹ operations);
  the gradient iterations are.
- **Products** mostly recompute offline. AudioMuse-AI stores a UMAP projection and fills in
  missing songs. Nomic withdrew in-place updates. Apple Photos assigns incrementally and
  re-clusters overnight while charging.

## Review log

- **Round 1 (fresh adversarial subagent): REVISE.** Thirteen issues. The blocker: changed vectors
  (the common case during analysis) had no mechanism, because the coverage fingerprint cannot name
  rows. The majors:
  - only uniform additions were tested;
  - the gates were tuned on the gate fixture, with one seed;
  - the 90 % trigger was untested;
  - the phone projection was really an emulator estimate;
  - the summary misstated why sample-and-place failed.

  The minors covered the missing overlap row, a seed-noise baseline, imprecise numbers,
  deletions, log gaps, the frozen-mean condition, and memory and evidence rules for the other
  gates.

  **Answered by:**
  - re-running the replay with `same`, `add10`, `style` and `change5`, `today-s`, a cold-seed
    baseline and a second seed;
  - making per-track change detection and a novelty router part of approach 1;
  - lowering the trigger to ≤ 3 % flagged;
  - freezing the parameters and moving the gate to held-out seeds 13/17/19 and FMA;
  - adding the overlap, chain-with-removals, heap and FMA-only rows;
  - relabelling all projections "emulator, unverified";
  - correcting the compression factor (~150×, RMS) and the `MAP_LAYOUT_REUSE` description.
- **Round 2 (fresh subagent): REVISE.** About 45 cells were checked against the log and match, and
  no replay bugs were found. Issues raised:
  - the compressed-start claim was not in the log;
  - three overlap failures were not bolded;
  - the novelty rule misfired on album additions and its test could not fail;
  - plug-in points were wrong for the code (no mean pass in `LibraryVectorRows`, IndexStore
    width, meta key, the exact and native backends);
  - small range errors, loose move and chain rows, the fallback ladder, and the
    full-rehash case.

  **Answered by:**
  - appending the first-run log and citing it;
  - bolding the failures and stating them in the Summary;
  - a distance-based novelty rule, plus album-clustered and sub-threshold scenarios;
  - corrected plug-in points;
  - p90 and cold-referenced chain rows and a predeclared ladder;
  - a limits paragraph.

  No data was re-run for these fixes, since none needed new data.
- **Round 3 (fresh subagent): READY.** No blockers or majors, about 60 cells re-checked. Seven
  minors were fixed in the text:
  - the mean-pass location (`SampledTsne.reduce`);
  - bolding of the style and unflagged recall failures, and the seed 7 style trust that sits on
    the threshold;
  - this intro;
  - "every *measured* row";
  - 63-bit hashes and padded meta values;
  - the `MapEmbeddingBackend` interface;
  - removing the self-declared status.

  The next piece of work is approach 1's held-out gate run.

## Sources

Checked by two research passes on 25 Oct 2026. "(DOI from memory)" marks four DOIs not opened.
Licences of code were read from GitHub. AGPL / non-commercial code must not be copied; ideas from
papers are fine.

**Products and projects**

1. Every Noise at Once — https://furia.com/log/382 ; https://en.wikipedia.org/wiki/Every_Noise_at_Once — remapped by hand from 2 of ~10 Echo Nest dimensions; never incremental; dormant since 2023.
2. Spotify, "How Spotify discovers the genres of tomorrow" — https://artists.spotify.com/es-419/blog/how-spotify-discovers-the-genres-of-tomorrow — map began as a debugging tool; method not public.
3. Spotify recursive UMAP + HDBSCAN (InfoQ) — https://www.infoq.com/news/2024/01/spotify-clustering/ — offline batch pipeline; no incremental story.
4. Music Galaxy (Primozic) — https://cprimozic.net/blog/building-music-galaxy/ ; https://github.com/Ameobea/spotifytrack (MIT) — node2vec → PCA 3-D, computed once offline, shipped static.
5. Google AI Experiments: Infinite Drum Machine / Bird Sounds — https://aiexperiments.withgoogle.com/drum-machine ; https://github.com/googlecreativelab/aiexperiments-drum-machine (Apache-2.0) — offline t-SNE of audio, fixed map in the client.
6. Plexamp Sonic Analysis — https://support.plex.tv/articles/sonic-analysis-music/ — server-side N-d "musical universe", hours to days per library; no 2-D layout published.
7. AudioMuse-AI — https://github.com/NeptuneHub/AudioMuse-AI (**AGPL-3.0, ideas only**), docs/ALGORITHM.md — UMAP 2-D computed at index rebuild and stored; songs lacking coordinates are filled in later; clustering redone each run.
8. beets-xtractor — https://pypi.org/project/beets-xtractor/ — descriptors only (Essentia, AGPL); no map.
9. Musicmap — https://digitalhumanitiesnow.org/2018/03/behold-the-musicmap-the-ultimate-interactive-genealogy-of-music-created-between-1870-and-2016/ — hand-curated.
10. TensorFlow Embedding Projector — https://arxiv.org/abs/1611.05469 — in-browser PCA/t-SNE on demand; no persistence.
11. Nomic Atlas — https://docs.nomic.ai/atlas/embeddings-and-retrieval/dimensionality-reduction ; NOMAD https://arxiv.org/abs/2505.15511 — UMAP < 50k; updating existing maps was deprecated for data-consistency flaws (release notes, seen in a search snippet; the page now 404s).
12. deepscatter — https://github.com/nomic-ai/deepscatter (non-commercial licence, unusable) — quadtree tiles for zoom loading.
13. Apple Embedding Atlas — https://arxiv.org/abs/2505.06386 ; https://github.com/apple/embedding-atlas (MIT) — UMAP + density clustering + zoom-stable labels; no incremental updates.
14. Embedding Atlas clustering — https://arxiv.org/abs/2504.07285 — KDE clustering on the 2-D projection; cheap region labelling.
15. WizMap — https://arxiv.org/abs/2306.09328 ; https://github.com/poloclub/wizmap (MIT) — quadtree multi-resolution summaries over precomputed coordinates.
16. Latent Scope — https://github.com/enjalot/latent-scope (MIT) — batch embed → UMAP → HDBSCAN; rerun to update.
17. DataMapPlot — https://github.com/TutteInstitute/datamapplot (MIT) — labelling and rendering only.
18. Apple, "Recognizing People in Photos" — https://machinelearning.apple.com/research/recognizing-people-photos — incremental greedy assignment at once, full clustering overnight while charging; 2.4 s vs 69.2 s against average linkage.

**kNN and graph building**

19. HNSW (Malkov & Yashunin) — https://arxiv.org/abs/1603.09320 ; https://github.com/nmslib/hnswlib (Apache-2.0) — incremental inserts, O(log n) search.
20. jelmerk/hnswlib — https://github.com/jelmerk/hnswlib (Apache-2.0) — pure Java; JVM-only (no iOS).
21. USearch — https://github.com/unum-cloud/usearch (Apache-2.0) — C++ HNSW with Java/Swift bindings, f16/i8.
22. NN-Descent (Dong, Charikar, Li 2011) — https://www.cs.princeton.edu/cass/papers/www11.pdf — kNN graph by neighbour-of-neighbour refinement; ~200 lines clean-room.
23. PyNNDescent — https://github.com/lmcinnes/pynndescent (BSD-2) — has `update()` for an incremental kNN graph.
24. Annoy — https://github.com/spotify/annoy (Apache-2.0) — read-only index; no inserts.
25. Faiss — https://github.com/facebookresearch/faiss (MIT); ARM PQ https://arxiv.org/abs/2203.02505.
26. Charikar 2002, SimHash — https://www.cs.princeton.edu/courses/archive/spr04/cos598B/bib/CharikarEstim.pdf — sign-projection bits estimate angles; a cheap prefilter.

**On-device / in-browser**

27. tsnejs — https://github.com/karpathy/tsnejs (MIT) — exact t-SNE; a few thousand points.
28. umap-js — https://github.com/PAIR-code/umap-js (Apache-2.0) — step-wise UMAP in JS.
29. tfjs-tsne / linear-complexity GPU t-SNE (Pezzotti et al.) — https://arxiv.org/abs/1805.10817 ; https://github.com/tensorflow/tfjs-tsne (Apache-2.0) — repulsion as a texture field; real-time in a browser.
30. FIt-SNE (Linderman et al. 2019) — https://pmc.ncbi.nlm.nih.gov/articles/PMC6402590 — FFT-interpolated repulsion, O(n); 12.6× gradient speed-up at 1M.
31. Android long-running work — https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running ; Constraints https://developer.android.com/reference/androidx/work/Constraints.Builder — ~10 min per worker unless foreground; charging/idle constraints.
32. iOS BGProcessingTaskRequest — https://developer.apple.com/documentation/backgroundtasks/bgprocessingtaskrequest — idle-time processing, `requiresExternalPower`.

**Out-of-sample, incremental and dynamic embeddings**

33. openTSNE (Poličar, Stražar, Zupan, JSS 2024) — https://jstatsoft.org/article/view/v109i03 ; API https://opentsne.readthedocs.io/en/latest/api/index.html (BSD-3) — `transform` defaults: perplexity 5, median of 25 neighbours start, 250 it, lr 0.1, exaggeration 1.5.
34. Poličar et al., "Embedding to reference t-SNE space…" (Mach. Learn. 2023) — https://www.biorxiv.org/content/10.1101/671404v1.full — placement alone clumps; 250 it against a fixed reference; 1–5 % misplacement.
35. Kobak & Berens 2019, "The art of using t-SNE…" — https://www.nature.com/articles/s41467-019-13056-x — median-of-10 placement; PCA init σ 1e-4; learning rate n/12; cannot create new clusters.
36. Rauber, Falcão, Telea 2016, Dynamic t-SNE — https://diglib.eg.org/handle/10.2312/eurovisshort20161164 — movement penalty between frames.
37. Pezzotti et al. 2017, A-tSNE — https://arxiv.org/abs/1512.01655 — progressive, refine only what changed.
38. Vieira et al. 2024, S+t-SNE — https://arxiv.org/abs/2403.17643 — streaming t-SNE with drift handling.
39. Fujiwara et al. 2020, incremental DR for streams — https://arxiv.org/abs/1905.04000 — incremental PCA + alignment to keep the mental map.
40. Gisbrecht et al. 2015, kernel t-SNE — https://doi.org/10.1016/j.neucom.2013.11.045 — explicit kernel map for new points.
41. van der Maaten 2009, parametric t-SNE — https://proceedings.mlr.press/v5/maaten09a.html.
42. Sainburg, McInnes, Gentner 2021, Parametric UMAP — https://arxiv.org/abs/2009.12981 — trust within 0.3–1.5 points of UMAP; landmark recipe for updates: https://umap-learn.readthedocs.io/en/latest/transform_landmarked_pumap.html.
43. Islam & Fleischer 2026, "On Out-of-sample Embedding in UMAP" — https://arxiv.org/abs/2606.04451 — transform piles points at cluster edges (2–3×); parametric best; 0.53 ms/point.
44. McInnes, Healy, Melville, UMAP — https://arxiv.org/abs/1802.03426 — subsample stability by Procrustes.
45. Aligned UMAP — https://umap-learn.readthedocs.io/en/latest/aligned_umap_basic_usage.html — joint fits with an alignment regulariser.
46. Ko, Jo, Seo 2020, Progressive UMAP — https://diglib.eg.org/handle/10.2312/evs20201061.
47. Wang et al. 2022, Joint t-SNE — https://virtual.ieeevis.org/year/2021/paper_v-full-1471.html — beats dynamic t-SNE on coherence.
48. Vernier et al. 2020, evaluating time-dependent projections — https://arxiv.org/abs/2002.07481 — temporal stress / Shepard metrics; per-frame t-SNE least stable.
49. Xu et al. / SONG, incremental parametric visualization — https://arxiv.org/abs/1912.04896.
50. Espadoto, Hirata, Telea 2020, NNP — https://arxiv.org/abs/1902.07958 — regression to t-SNE coordinates loses ~12 % neighbourhood hit.
51. Damrich et al. 2023, t-SNE ↔ UMAP via contrastive learning — https://iclr.cc/virtual/2023/poster/11861.

**Landmark / control-point projections**

52. de Silva & Tenenbaum, Landmark MDS — summary https://search.r-project.org/CRAN/refmans/bigmds/html/landmark_mds.html.
53. Paulovich et al. 2008, LSP — https://research.ucc.ie/en/publications/least-square-projection-a-fast-high-precision-multidimensional-pr/.
54. Joia et al. 2011, LAMP — https://doi.org/10.1109/TVCG.2011.220 (DOI from memory).
55. Paulovich, Silva, Nonato 2010, PLMP — https://www.sci.utah.edu/~csilva/papers/tvcg2010c.pdf.
56. Tejada, Minghim, Nonato 2003, Force Scheme — https://web2.icmc.usp.br/SCATUSU/RT/Relatorios_Tecnicos/RT_207.pdf.
57. Neves et al. 2020, Xtreaming — https://arxiv.org/abs/2003.09017.

**Other embedding algorithms, initialization, optimization**

58. PaCMAP (Wang et al., JMLR 2021) — https://www.jmlr.org/papers/v22/20-1061.html.
59. TriMap (Amid & Warmuth) — https://arxiv.org/abs/1910.00204.
60. LargeVis (Tang et al. 2016) — https://arxiv.org/abs/1602.00370.
61. Kobak et al. 2019, heavy-tailed kernels — https://arxiv.org/abs/1902.05804.
62. Kobak & Linderman 2021, initialization is critical — https://www.biorxiv.org/content/10.1101/2019.12.19.877522v1.full.pdf.
63. Belkina et al. 2019, opt-SNE — https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6882880/ — KL-based stopping; learning rate n/12.
64. Böhm, Berens, Kobak 2022, attraction–repulsion spectrum — https://www.jmlr.org/papers/v23/21-0055.html — why mild exaggeration in transform.
65. Chan et al. 2018, t-SNE-CUDA — https://arxiv.org/abs/1807.11824.

**Metrics and incremental clustering**

66. Venna & Kaski 2006, trustworthiness / continuity — https://research.cs.aalto.fi/pml/papers/wsom05_lmds.pdf.
67. Espadoto et al. 2021, quantitative survey of DR — https://webspace.science.uu.nl/~telea001/uploads/PAPERS/TVCG19/paper.pdf.
68. Nonato & Aupetit 2019, projection for visual analytics — https://qcai.qcri.org/publications/multidimensional-projection-for-visual-analytics-linking-techniques-with-distortions-tasks-and-layout-enrichment.
69. Sculley 2010, mini-batch k-means — https://doi.org/10.1145/1772690.1772862.
70. Zhong 2005, online spherical k-means — https://doi.org/10.1109/IJCNN.2005.1556436 (DOI from memory).
71. Zhang et al. 1996, BIRCH — https://dsf.berkeley.edu/cs286/papers/birch-sigmod1996.pdf.
72. Cao et al. 2006, DenStream — https://doi.org/10.1137/1.9781611972764.29 (DOI from memory).
73. Kuhn 1955, Hungarian method — https://doi.org/10.1002/nrl.3800020109 (DOI from memory).
