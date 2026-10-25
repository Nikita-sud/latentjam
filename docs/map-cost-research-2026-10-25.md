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

> **Latest: Round 3 (26 Oct), the owner's deferred-rebuild design: FAIL as frozen, not ready to
> implement. 10 frozen rows fail.**
> - **Strong:** refresh speed, 0.14× of today's time on `LJ-api29` (2.6 s vs 18.8 s).
>   Trust, recall, stability and kNN passed on all 57 ordinary changes. The deferred rebuild
>   restored new styles' RF, trust and recall in every case.
> - **Failing:**
>   - stored-state heap (≈ +8 MB over the margin);
>   - the rebuild's peak and region fallback on a 256 MB heap;
>   - additive regions (purity, coverage, own region, chain drift);
>   - overlap at ≥ 3 % changes;
>   - one 1 % album's CF;
>   - tiny new styles while waiting;
>   - one recovery CF.
> - Rebuild cancellation passed only as sampled (300–440 ms gaps between polls).
> - A post-hoc reading, not gated: the ≤ 2 % layout refresh is the most promising candidate for a
>   next round, but its 2 % budget was never run and its overlap margins are thin.
>
> See [Round 3 result](#round-3-result-gate-run-2526-october-2026).

> **Gate result (25 Oct, held-out):** approach 1 **failed as frozen**.
> - On every non-novel change, the refresh step passed every quality row except overlap on one
>   album case (0.003 short). On the three novel styles the router let through, RF and CF failed.
> - Alone, it cost 0.398× today's time on the emulator, a margin below run-to-run noise.
> - The novelty router failed both of its rows, and with it included the time row fails.
> - RF, CF, overlap, kept15 and the router's cancellation failed.
>
> See [Gate run](#gate-run-for-approach-1-25-october-2026-held-out). The bullets below are the
> pre-gate pilot.

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

## Gate run for approach 1 (25 October 2026, held-out)

**Verdict: FAIL as frozen. Do not implement approach 1 as specified.**

- **What works.** On every non-novel change, the refresh *step* passes every quality row except one: overlap on one album case, 0.464 against 0.467, a single noise draw. RF and CF failed only on the three novel styles the router let through. Unchanged tracks move 13–70× less at p90.
- **What fails.**
  - The novelty router fails both of its rows.
  - The router as built adds 2–4 s of brute-force search: with it, the host time is
    0.47–0.57×, which fails the time row; without it, the emulator time is 0.398×.
  - The router also fails cancellation.
  - kept15 fails on 8 of 25 cases, where today's map is already near the ceiling.
- **Next step.** A redesigned router and a corrected stability row need a new predeclared round.
  See [What happens next](#what-happens-next).

### Setup

The parameters and thresholds are exactly those frozen in
[approach 1](#1-refresh-at-true-scale-recommended-first). Nothing was tuned after the results
came in.

**Data** (all synthetic or public; never the owner's library):

- **Synthetic** `hierarchicalFixture`, 10,000 tracks, held-out seeds 13, 17 and 19. Seeds 7 and 11
  were the pilot.
- **FMA.** The 16 general-stand libraries pooled into 7,989 unique tracks (licence-clean FMA plus
  cc-audio, app-exact A0 and text vectors, fused as the app does). From the pool: random
  libraries of 1,000 and 5,000, plus the whole pool.
  - No public set with these vectors reaches 10,000; the P2 FMA list plus cc-audio gives 9,138
    audio-only rows.
  - Genre@15 uses the FMA top genre; "style" is the first sub-genre.
  - Built by `tools/research/map_gate_fma_dump.py`.

**Mechanism.**

- Rows are flagged by a hash of the fused row. The harness uses 64 bits where the plug-in
  prescribes 63 (the storage codec); this does not change which rows are flagged.
- The router applies the size rule (≤ 3 %), the distance novelty rule (2× the median
  5th-neighbour distance, ≤ 10 % novel) and the placed-share budget (10 / 5 / 3 %).
- Above 3 % changed, and for anything the router rejects, the full build is today's path, so it
  matches today by construction.

**Scenarios:** `same`; +1/2/3 % uniform; changed 1/2/3 %; album additions (10–20-track groups,
~1 %); a never-seen style at 0.5/1/2 %; +5 % and a whole style (router check only); chains of
ten +1 % steps then −2 %.

**Harness.**

- `tools/research/MapGateReplay.kt` (host). Logs: `tools/research/map-gate-2026-10-25.txt` and a
  labelled diagnostic, `map-gate-diagnostic-2026-10-25.txt`.
- `tools/research/MapRefreshDeviceTest.kt` (emulator). Log: `map-refresh-device-2026-10-25.txt`.
- The emulator was `LJ-big36`, booted `-read-only` on its own port. `LJ-demo` and the phone were
  not touched. The image is not rootable, so the test APK ran with its largeHeap limit (576 MB)
  instead of the app's 192 MB. The heap row compares peak *deltas*, which this does not affect.
- **Time and heap rule.** The rule (median of 5 clean runs per mode) was fixed after a first
  contended series had been seen. That series ran while the host gate was using the CPU and gave
  0.37–0.44× in single pairs. Both series are in the log.

### Pass / fail per gate row

Refreshed cases are the 25 the router sent to the refresh: synthetic 7, FMA-5000 11, FMA-7989 7.
RF and CF exist only where rows were flagged; the five `same` cases have none, so their
denominator is 20.

| Gate row | Threshold | Result | Verdict |
| --- | --- | --- | --- |
| trust@15 | ≥ today − 0.005 | 25 / 25; worst −0.0022 | **PASS** |
| recall@15 | ≥ today × 0.97 | 25 / 25; worst 0.992× | **PASS** |
| class@15 / genre@15 | ≥ today − 0.010 | 25 / 25; worst −0.0031 | **PASS** |
| RF (flagged recall@15) | ≥ today × 0.85 | 19 / 20. Fails on FMA-7989 novel 0.5 % (0.81×), a novel style the router let through. | **FAIL** |
| CF (flagged genre share) | ≥ today − 0.05 | 17 / 20. Fails on FMA-5000 novel 0.5 % (−0.058), FMA-5000 novel 1 % (−0.085) and FMA-7989 novel 0.5 % (−0.135), all novel styles the router let through. | **FAIL** |
| overlap with a cold build | ≥ cold-seed noise − 0.10 | 24 / 25. Fails on synthetic seed 13 album (0.464 vs 0.467). The noise reference is one cold-seed draw, so this miss is within plausible noise; it still fails as frozen. | **FAIL** |
| kept15 (unchanged tracks) | ≥ today + 0.10 | 17 / 25. Fails on FMA-5000 +1 % (0.973 vs 0.976 needed) and all 7 FMA-7989 cases (today keeps 0.868–0.903, so 0.968–1.003 was needed; the refresh got 0.962–0.987). | **FAIL** (the row sits at or near its ceiling where today already keeps ≈ 0.87 or more) |
| p90 move (unchanged tracks) | ≤ 0.5× today and ≤ 0.02 | 25 / 25. Refresh 0.002–0.007 of the map width; today 0.07–0.26. | **PASS** |
| chain vs a cold build of the final library | trust − 0.005, recall × 0.97, RF × 0.85 | 15 / 15 (5 libraries × each of the 10 / 5 / 3 % budgets). Weak test, though: see *Chains* below. | **PASS** (weak) |
| router: novel styles 0.5–2 % → full build | every one | 11 / 15. Synthetic 9 / 9; FMA-5000 0 / 3; FMA-7989 2 / 3. | **FAIL** |
| router: uniform +1 % and album cases kept on refresh | ≥ 90 % | 8 / 10 = 80 %. Uniform +1 %: 3 / 5 (synthetic seeds 17 and 19 rejected); album: 5 / 5. Synthetic +2/3 % and every synthetic changed case were also rejected. | **FAIL** |
| time at 10,000 (emulator) | ≤ 0.4× today | **As built, with the router:** the router is a brute-force search that the emulator stage does not include. On the host it adds 2–4 s, giving 0.47–0.57× → fail. **Refresh step alone:** emulator 0.398×, 8.15 s against 20.47 s, median of 5 clean runs (refresh 8.08–8.15 s, today 20.27–21.33 s, i.e. ±2.5 % run to run, more than the 0.002 margin). The rule (median of 5 clean runs) was fixed after a contended series had been seen, whose pairs gave 0.37–0.44 and whose median, about 0.42, fails. Host, router excluded: 0.30–0.34×. A router that reuses the 60-NN lists is untested. | **FAIL** as built; refresh step alone passes by the rule, with a margin below run-to-run noise |
| peak heap at 10,000 (emulator) | ≤ today + 2 MB | +0.08 MB (median 27.80 vs 27.71 MB). The host shows the same 58 MB for both. | **PASS** |
| cancellation | every phase stops within 250 ms | Refresh path on the emulator: 48 / 15 / 19 ms at 1 / 4 / 7 s, longest gap between polls 185 ms. The router as built does not poll: 1.7–4.1 s on the host, a *lower bound*, because the host probe times from the first poll after the deadline. | **FAIL** (router) |
| ladders | 3 → 2 → 1 % size; 10 → 5 → 3 % budget | No size threshold rescues the gate: the router and CF failures occur at 0.5–1 % changes. All three budgets were run, and the chain row passed at each. | — |
| `LJ-api30` (256 MB) emulator | in the frozen Measure list | Not run (another session was using that AVD); only `LJ-big36`. | not measured |
| FMA 1,000 tracks | — | Not applicable. ≤ 2,000 tracks uses exact t-SNE, which has no refresh schedule; the design limits the refresh to Barnes–Hut. | n/a |

### Why the router fails

The distance rule measures *isolation*, not *novelty*.

- **It rejects good refreshes on synthetic data.** 15 % of the fixture's tracks are scattered
  outliers, far from everything by construction. Any uniform change of 1 % or more therefore
  contains 7–18.5 % "novel" rows, so +2/3 % and every changed case went to a full build. In the
  chains, 9–10 of 11 steps were full builds.
- **It misses novel styles on FMA.** A held-out FMA sub-genre is not isolated in the fused space:
  its tracks sit close to other genres. So it went to the refresh (0 of 3 on FMA-5000), and
  placement scattered it (CF −0.06 to −0.14).

Both failures come from the rule itself, not from its thresholds.

### Chains, and a diagnostic run (not gate evidence)

**Why the gate chain is weak.**

- With the frozen 10 % budget, ten steps of +1 % of the full library reach the budget at step 10,
  which is a full build. The measured final map is therefore a full build plus one refresh.
- On synthetic data the router turned most steps into full builds anyway.

**Diagnostic.** To see the drift itself, the same chains were run with **no budget**, so ten
refreshes plus the −2 % removal ran with no full build. Synthetic seed 13 also had the novelty
check switched off.

| Library | trust final vs cold | recall vs cold | RF vs cold |
| --- | --- | --- | --- |
| synthetic s13 | 0.9833 vs 0.9843 | 0.982× | 0.954× |
| FMA-5000 | 0.9909 vs 0.9917 | 0.995× | 0.989× |
| FMA-7989 | 0.9937 vs 0.9941 | 0.9997× | 0.989× |

- All three stay inside the chain thresholds. In this diagnostic, the refresh did not accumulate
  visible damage over 11 steps.
- With the router off, synthetic seed 13 +1/2/3 % and changed 1/2/3 % pass **every** row: kept15
  0.70–0.84 against today's 0.41–0.48.
- The novel 1 % style passes too, except CF (0.88 vs 1.00) and overlap. That confirms a novelty
  check is needed; it has to be a different one.

This diagnostic was run after seeing the gate. It informs the next design and is not a pass.

### Does the recommendation stand?

**Partly.**

- **What stands, as a mechanism.** "Refresh, don't rebuild": placement at true scale plus 150
  iterations without exaggeration.  - On every non-novel change it passed every quality row except overlap on one album case (0.003
    short, one noise draw). On the novel styles the router let through, RF and CF failed.
  - Alone it cost 0.40× on the emulator, a margin below run-to-run noise.
  - It keeps the map far more still: on FMA, today's rerun moves unchanged tracks up to a quarter
    of the map width at p90.
  - The no-budget chain diagnostic (not gate evidence) showed no drift over 11 steps.
- **What does not.**
  1. **The router.** It must be redesigned and passed in a new round.
  2. **The kept15 row.** "+0.10 over today" was a gate design error. It sits at or near its
     ceiling when today already keeps ≈ 0.87 or more. The next round needs a relative row, for example "share of
     lost neighbours ≤ 0.5× today's". Checked afterwards, it would pass all 25 cases here, but it was not
     predeclared, so it does not count.
  3. **Cost.** The emulator saving is smaller than projected (0.40× against 0.22–0.30×). On this
     faster emulator, PCA and the 60-NN search, which a refresh still repeats, are a larger
     share. Approach 3 (caching the basis and the neighbour graph) is what would bring the
     refresh well below 0.4×, so it moves up from "later" to "with approach 1".

### What happens next

These are proposals for a new predeclared round on fresh seeds (e.g. 23, 29, 31) and a new FMA
draw. Nothing below has been gated.

- **Router.** Novelty becomes *group* novelty. A flagged row counts as novel only if at least 3 of
  its 5 nearest neighbours are flagged *and* its nearest unflagged row is farther than its 5th
  nearest flagged one. Isolated outliers are placed; a new cluster sends the change to a full
  build. The router reuses the 60-NN lists the build computes anyway, and polls per row.
  - Album additions are the hard case for this rule, since their tracks are each other's nearest
    neighbours. The rule must be tested against the album scenario before it is frozen.
  - If no rule separates albums from novel styles, the fallback is to refresh first and then
    check the result (the flagged rows' CF / RF proxy against their 60-NN in the input). Rebuild
    when the check fails.
- **Stability row.** The share of lost neighbours, (1 − kept15), must be ≤ 0.5× today's, plus
  p90 move as now.
- **Measurement fixes.**
  - Router time goes inside the time row.
  - Cancellation is measured from the deadline.
  - The overlap reference becomes a noise band from several cold seeds.
  - `LJ-api30` is included.
- **Approach 3 in the same round.** Frozen basis and mean, plus patched 60-NN lists, so the
  refresh no longer repeats PCA and the neighbour search. The time row moves to ≤ 0.25×.

## Round 2: approach 1 + 3 with a new router (plan frozen 25 October 2026, before the gate run)

> **Superseded, never scored.** The owner changed the target design before this gate finished (see
> Round 3). The run was stopped and its partial logs were set aside unread, so its gate libraries
> stayed unseen for Round 3. Note: stopping it also ended every Gradle daemon on the machine, which
> may have interrupted another session's build.

The owner chose to continue. This round tests the changes proposed in
[What happens next](#what-happens-next):

- approach 1 (refresh at raw scale) on top of approach 3 (frozen basis and mean, stored reduced
  rows, patched 60-NN lists);
- a new router that reuses the neighbour lists and polls per row;
- a relative stability row;
- router time inside the time row;
- cancellation measured from the deadline;
- overlap against a band of several cold seeds;
- the LW2 genre-family signal as a router variant.

Everything below was written **after the pilot and before any gate data was run**.

### Pilot (design only, not evidence)

**Pilot data:** synthetic seed 7 and the round-1 FMA draws (5,000 and 7,989), with
`tools/research/MapRound2Replay.kt` in pilot mode. Log: `tools/research/map-round2-pilot-2026-10-25.txt`.

**Routers tried** (all read the patched 60-NN lists):

- **G, group geometry.** A flagged row is novel if at least 3 of its 5 nearest rows are flagged
  *and* its nearest unflagged row is farther than 2× the library's median 5th-neighbour distance.
  The change goes to a full build if more than 10 % of flagged rows are novel.
  - Isolated outliers are no longer novel: synthetic uniform and changed cases all stayed on the
    refresh, where round 1 rejected them.
- **F, family conflict** (LW2 predicted families). The share of flagged rows whose predicted
  family differs from the majority family of their 5 nearest unflagged rows, against the same share
  for unflagged rows. Full build if the flagged share exceeds the library's own by 0.15.
- **C, refresh-then-check.** After the refresh, compare each flagged row's map recall proxy (its 15
  map neighbours against its 15 nearest input rows from the lists) with the same proxy for up to
  500 unflagged rows. Full build below 0.85× the unflagged proxy.

**Pilot routing** (new-style cases → full build is the wanted outcome):

| Pilot library | G | G + F | C | Albums and ordinary changes |
| --- | --- | --- | --- | --- |
| synthetic s7 | 3 / 3 new styles → full | n/a (no families) | 0 / 3 | all refreshed under every router |
| FMA-7989 | 3 / 3 | 3 / 3 | 2 / 3 | all refreshed under every router |
| FMA-5000 | 0 / 3 | 2 / 3 | 1 / 3 | all refreshed under every router |

No single router caught every pilot new style, but their **union** did.

**Two warnings from the pilot:**

- On FMA-7989 an album addition failed CF (−0.073) even though it was refreshed, as it should
  have been. Albums can fail the quality rows on their own.
- Approach 1+3 ran in 0.17–0.20× of today's time on the host.

### What is frozen

**Data** (never the owner's library):

- **Synthetic** `hierarchicalFixture`, 10,000 tracks, seeds **23, 29 and 31**. Never used before.
- **FMA:** three new random draws from the same 7,989-track pool:
  - seed 101 at 5,000 tracks;
  - seed 202 at 5,000 tracks;
  - seed 303 at 6,500 tracks.
  - The pool is the one used in round 1 and the pilot, so the FMA tracks themselves are **not**
    fresh; only the draws and scenarios are.
  - Built by `tools/research/map_gate_fma_dump.py --seed S --tagger <LW2 assets>`.

**Vectors.**

- FMA rows are the LW2 encoder's A0 audio (`mnv4-960-clean-a0-fma-v1`) plus the shipped text
  vector, fused as the app does.
- Synthetic rows come from no encoder.
- **The release encoder (`retrieval-distill-v1`) is not covered by this round.**

**LW2 tagger.**

- The family variant reads only the int8 head's *predicted* family. The dump script checks the
  Python port against `audio_tagger_golden.tsv` on every run and logs the result: all 6 rows agree
  on the argmax, max |Δp| = 0 in float64.
- FMA genre columns are evaluation labels only.
- The tagger was trained on FMA's official *training* split. Every new-style newcomer is therefore
  drawn from the validation / test split only, but the background library contains training-split
  tracks. The family variant's FMA results may still be optimistic.

**Scenarios** (per library, as round 1):

- `same`;
- +1 / 2 / 3 % uniform additions;
- changed 1 / 2 / 3 %;
- album additions: 10–20-track groups from one style, ~1 % in all;
- **new style** at 0.5 / 1 / 2 %.
  - The style is the one with ≥ 2 % held-out members whose library share is closest to 3 %,
    **excluding the pilot's style ("Soundtrack")**. If none qualifies, it is the style with the
    most held-out members, taking as many as exist.
  - On the gate draws this is "Field Recordings": 70–88 held-out tracks, so the "2 %" case is
    1.4–1.8 %.
  - Synthetic: a style that is closest to 3 % and absent before the change.
- chains: ten +1 % steps then −2 %, at budgets 10 / 5 / 3 %.

**Pipeline under test** (`tools/research/MapRefreshEngine.kt`).

- **After every full build**, the app keeps:
  - the centering mean;
  - the PCA basis;
  - each track's reduced (50-d) row;
  - its 60-NN list.
- **Each change** then runs these steps:
  1. Flag rows by fused-row hash.
  2. **Size gate**: more than 3 % flagged → full build.
  3. **Budget gate**: placed share since the last full build > B → full build.
  4. Project flagged rows with the frozen mean and basis.
  5. Patch the 60-NN lists. Clean rows keep their list and are offered the flagged rows; every
     other row is searched exactly.
  6. Route with **UNION = G, then F (when predicted families exist), then refresh, then C**. Any
     one of them → full build. Thresholds are exactly as in the pilot above.
  7. Place flagged rows from their 5 nearest unflagged list entries at the raw-scale start.
  8. Recompute the affinities over the patched lists.
  9. Run 150 iterations, no exaggeration, late momentum.
  10. Align and normalize.
- Every step polls for cancellation per row or per iteration.
- **Variants reported, not deciding:** G alone, G + F, C alone.

**Rows.** Each holds for **every refreshed case under UNION, on every gate library**. "today" is
today's warm full build on the same input. RF and CF apply only where rows were flagged.

| Row | Pass |
| --- | --- |
| trust@15 | ≥ today − 0.005 |
| recall@15 | ≥ today × 0.97 |
| class@15 / genre@15 | ≥ today − 0.010 |
| RF | ≥ today × 0.85 |
| CF | ≥ today − 0.05 |
| overlap with a cold build | ≥ mean of three cold-seed overlaps (seeds +1 / +3 / +5) − 0.10 |
| lost neighbours, 1 − kept15, unchanged tracks | ≤ 0.5 × today's |
| p90 move, unchanged tracks | ≤ 0.5 × today's and ≤ 0.02 of the map width |
| stale basis | recall@15 ≥ 0.97 × the approach-1 refresh (fresh PCA + exact kNN, same placement and iterations) |
| patched kNN | bit-identical (indices and distances) to `BarnesHutTsne.nearestNeighbours` over the same reduced rows |
| router: new styles | every new-style case (0.5–2 %) → full build |
| router: keeps | ≥ 90 % of uniform +1 % and album cases → refresh |
| chain, at each budget | final map vs a cold build of the final library: trust ≥ cold − 0.005, recall ≥ cold × 0.97, RF ≥ cold × 0.85. Budget ladder 10 → 5 → 3 %; adopt the largest that passes. |

**Device rows** (`MapRound2DeviceTest`):

- **Setup.** `LJ-big36`, booted read-only, on synthetic seed 23 with a +1 % uniform change.
  `LJ-api30` is used too if no other session is using it. `LJ-demo` is never used.
- **Time.** The refresh's total time, router included, must be ≤ 0.25× today's warm full build,
  taking the median of 5 runs each with the host idle. Host ratios are reported but do not decide.
- **Heap.** Peak delta ≤ today's + 2 MB (median of 5). The refresh holds the stored lists
  (~4.8 MB) and reduced rows (~2 MB), and the peak counts them.
- **Cancellation.** Deadlines at the middle of each phase; the latency from the deadline must be
  ≤ 250 ms.

**Verdict rule.** Approach 1 + 3 is ready to implement only if **every** row passes. Otherwise the
report says which rows fail and why, and nothing is tuned on gate data.

### Plan revisions after the plan review (still before any gate data was run)

The plan review returned REVISE; these changes supersede the text above where they differ.

1. **Heap.**
   - The neighbour patch now merges each clean row's stored list with the flagged rows in
     primitive arrays. The earlier boxed version would have added tens of MB.
   - The heap row counts the stored state at its production size (reduced rows, 60-NN lists, mean,
     basis and hashes as primitive arrays, about 7.2 MB at 10,000): **refresh peak delta + stored
     state ≤ today's peak delta + 2 MB.** This may fail by construction of the stored state; it is
     kept as frozen.
2. **Device stage** `tools/research/MapRound2DeviceTest.kt` is part of the reviewed material.
   Device time is measured for +1 % uniform *and* changed 1 %.
3. **Gate mode is hard-wired.** UNION is the primary; G, G + F and C are variants; "Soundtrack" is
   excluded.
4. **Family control.** FMA libraries add a `heldout1` scenario: +1 % uniform drawn only from the
   validation / test split. It must stay on the refresh, and it counts in the keeps row. This tests
   whether F fires on the tagger's train / held-out accuracy gap rather than on novelty.
5. **Three new styles per library**, not one.
   - FMA: the 3 sub-genres with the most held-out members, "Soundtrack" excluded. On the gate draws
     these are Field Recordings, Pop and Rock. Their names and held-out counts were read from the
     gate draws' *labels* before the run; no result was seen.
   - Synthetic: the 3 styles whose share is closest to 3 %.
   - Because some of these are not musically isolated (a held-out "Rock" sub-genre still has rock
     neighbours), the new-style router row becomes **consequence-based:** every new-style case is
     a UNION full build, *or* a UNION refresh that passes every quality row.
   - Field Recordings is predicted mostly "other" by the tagger (41–57 of 70–88), so F is largely
     blind to it. "Other" rows are skipped as rows.
6. **Scoring** is done by `tools/research/map_round2_score.py`, written before the run. It
   computes the quality rows over UNION refreshes, the two router rows, the chain rows with
   bit-exact kNN at every refreshed chain step, and the adopted budget.
7. **Cancellation.** The patch now polls every 64 rows, plus per searched row. Not pollable from
   test code: `BarnesHutTsne.jointProbabilities`' CSR merge (core code), and the cheap sort,
   align and normalize. The device stage logs the longest gap between polls; the row stays
   ≤ 250 ms from the deadline.
8. **Time accounting.** A router rejection's time is decision time plus the full build. The
   refresh's `ms` excludes persisting the new state.
9. **Family ties.** A conflict counts only when the row's own predicted family is not among the
   tied most common families of its 5 nearest unflagged rows.
10. **Disclosures.**
    - G's isolation clause ("2× the median 5th-neighbour distance") replaced round 1's proposal
      ("farther than its 5th nearest flagged row") during the pilot.
    - The pilot's FMA-7989 draw was the whole pool, so every FMA gate track was seen in the pilot.
      Only synthetic seeds 23/29/31 are fully out of sample.
    - 0.85 and 0.15 were the only check and family thresholds tried.
    - Patched-kNN bit-exactness relies on unchanged rows keeping their relative order. Production
      would break ties by track id, which would not affect quality.
    - The pilot already shows an album failing CF. If CF fails in the gate, it is not re-gated.

**Second plan review: REVISE. Fixed before any gate data was run:**

11. **The measured "today" is production's work only.**
    - No research state by-product: today does not run the harness's own extra PCA and kNN.
    - No extra copy of the matrix.
    - This applies to the device stage and the host heap probe.
12. **Measured refreshes skip building the research `State`.** It is a map of boxed ids;
    production would keep the primitive arrays that are already in the peak. The stored-state
    bytes are added to the heap row analytically (revision 1).
13. **The device router is UNION.** The longest gap between polls is measured from the start.
14. **The scorer fails instead of passing vacuously.** It expects, per library:
    - 9 new-style cases;
    - a `heldout1` case (FMA only);
    - a chain at every budget;
    - a verdict line for every UNION refresh.

    Anything missing fails every affected row.
15. **The tagger golden check is required.** The dump aborts on any argmax disagreement or
    |Δp| > 1e-9.
16. **Exact invocations** (one per library set): synthetic seeds 23 / 29 / 31; then
    `fma-gate-s101/fma-5000`, `fma-gate-s202/fma-5000` and `fma-gate-s303/fma-6500`, each with
    `MAP_R2_MODE=gate`. Device: `-e mapRound2 1 -e mapRows 10000 -e mapRouter UNION -e mapScenario
    add1|change1 -e mapMode today|refresh`, 5 runs per cell, alternating, host idle.
17. **Still unpolled:** the hashing pass now polls every 256 rows. A CHECK rejection then runs a full
    build, whose own kNN polls per row inside `BarnesHutTsne`.
18. **The new-style row** is a consequence of the quality rows plus the full-build count, not
    independent evidence.

**Third plan review: REVISE. Fixed:**

19. **A router rejection inside a measured run** now does production's full build only. There is
    no research state and no extra copy; the rows are consumed in place. The probe logs each run's
    outcome. Every build's matrix copy is made before its timer starts.
20. **The scorer requires:**
    - the six expected library names (`--expect`), with `mode=gate` on each;
    - add1 and album1 present per library;
    - every quality row on every verdict (RF / CF excused only for `same`).
21. **Disclosures.**
    - The device fixture has no predicted families, so on the device UNION = G + C.
    - The device's longest-gap measure does not cover the stretch from the last poll to the end.

**Separate, not evidence.** At the owner's explicit request, a qualitative look at his own
library's regions is made in the scratchpad and reported to him directly. It does not feed any
row, threshold or decision, and none of his data enters the repository.

## Round 3: refresh every change, defer the rebuild (plan frozen 25 October 2026, before the gate run)

**The owner changed the target design** ("делай как считаешь лучше"). The novelty router is no
longer a correctness gate.

- **The round-2 gate was stopped and never scored.** Its partial logs were moved aside unread, so
  the gate libraries (synthetic seeds 23 / 29 / 31; FMA draws s101 / s202 / s303) are still
  unseen. They are reused here.
- **Design under test:**
  1. **Every change** takes the refresh path. This is approach 1 + 3: frozen basis and mean,
     stored rows, patched 60-NN, raw-scale placement and 150 iterations. No router decides
     between refresh and rebuild.
  2. **Region identity persists.**
     - A new or changed track joins the region with the nearest centroid if it clears that
       region's 5th-percentile member cosine; otherwise it gets no region.
     - At a full rebuild, regions are **additive**. Settled tracks (present and unchanged since
       the last full build) keep their region. Pending tracks take the rediscovered world they
       fall in: that world's old region when its settled members are mostly (≥ 50 %) from one old
       region (greedy one-to-one, largest overlap first), otherwise a new region.
  3. **The full rebuild is deferred.**
     - The map is marked stale when the placed share since the last full build exceeds B
       (ladder 5 % → 3 %), or when a hint fires:
       - group-geometry novelty above 10 % (round 2's G);
       - the LW2 family conflict (round 2's F, predicted families only);
       - more than 50 % of new tracks by artists the library does not have.
     - The rebuild (layout today's way, warm from the waiting map, plus additive regions) would
       run in the background: WorkManager on charging + idle, iOS `BGProcessingTask` with
       `requiresExternalPower`. It swaps in when done. In this test it runs right after the stale
       step.
     - **The hints only decide how soon a rebuild is asked for, never whether the map is correct.**

**Pilot** (synthetic seed 7 and the round-1 FMA-5000 draw; the "Soundtrack" style; logs in
`tools/research/map-round3-pilot-2026-10-25.txt`). Design only, not evidence.

- **Refresh** passes every row on ordinary changes, +5 % included. Host time is 0.16–0.21×.
- **While a new style waits,** whole-map recall falls to 0.955–0.96× today's on synthetic data.
  This is the frozen basis missing the new style's direction, and the rebuild restores it. The
  waiting-recall bound is therefore **0.95×**, not 0.97×. This is the one threshold set from the
  pilot.
- **Recovery** after the rebuild passed everywhere.
- **Plain rediscovery** with overlap matching moved 1–11 % of unchanged FMA tracks between regions.
  A sticky variant moved 1–10 %. The **additive** rule moves 0 % by construction, with purity
  within 0.0075 of a fresh rediscovery, coverage equal or higher, and a new style getting its own
  region as often or more. Additive regions are the frozen design.
- **Hints:** geometry caught the synthetic new style at every size. On FMA, family caught
  Soundtrack only at 2 %; nothing fired at 0.5–1 %. No false hints on ordinary changes.

**Rows** (scored by `tools/research/map_round3_score.py`, written before the run; "today" is
today's warm full build on the same input):

| Group | Row | Pass |
| --- | --- | --- |
| Refresh (same, add1/2/3/5, change1/2/3, album1, heldout1) | trust, recall, class, RF, CF, overlap band, lost neighbours, p90 move, stale basis, bit-exact kNN | as round 2 |
| New style **while it waits** (3 styles × 0.5 / 1 / 2 %) | trust | ≥ today − 0.005 |
| | recall | ≥ today × **0.95** |
| | class | ≥ today − 0.010 |
| | lost neighbours | ≤ 0.5 × today's |
| | RF of the new tracks | ≥ 0.5 × today's (their CF loss and count are reported) |
| New style **after the deferred rebuild** | RF | ≥ 0.95 × today |
| | CF | ≥ today − 0.02 |
| | trust | ≥ today − 0.005 |
| | recall | ≥ today × 0.97 |
| Regions (every case) | churn of settled tracks | ≤ 0.001 |
| | purity (majority-label share) | ≥ a fresh rediscovery's − 0.01 |
| | coverage | ≥ a fresh rediscovery's − 0.02 |
| | new style in its own region | ≥ a fresh rediscovery's − 0.10 |
| Chains (ten +1 % steps then −2 %, B = 5 % and 3 %) | the waiting map at every stale step and the final step, against a cold build of that library | trust ≥ cold − 0.005, recall ≥ cold × 0.97, RF ≥ cold × 0.85; bit-exact kNN at every step; adopt the larger passing B |
| Hints (latency; **reported, not deciding**) | new styles ≥ 1 % marked stale | ≥ 2/3 |
| | false hints on ordinary 1–3 % changes | ≤ 20 % |

**Device rows** (`tools/research/MapRound3DeviceTest.kt`):

- **Setup.**
  - **`LJ-api29`** (2 GB RAM, 256 MB heap class), booted read-only on its own port, synthetic
    seed 23.
  - **`LJ-api30` is not used:** another session has the LatentJam app open on it, so it is not
    free. `LJ-demo` is never used.
  - The heap limit is set to 256 MB through `dalvik.vm.heapsize` if the image is rootable; this
    is recorded either way.
- **Refresh time** (no router, the geometry hint included): ≤ 0.25 × today's warm layout. Median
  of 5 runs each for add1 and change1, host idle.
- **Refresh heap:** peak delta + stored state + region identity ≤ today's peak delta + 2 MB
  (median of 5).
- **Deferred full rebuild** (layout + `LibraryWorlds.discover` over 10,000 tracks): completes,
  with peak heap inside the app's own budget formula, in **≤ 9 min** at background priority, which
  is a single WorkManager window. Median of 3.
- **Cancellation:** deadlines inside each refresh phase, inside the layout and inside region
  discovery. Latency from the deadline ≤ 250 ms.

**Plan review 1: REVISE. Fixed before any gate data was run:**

1. **Chains** now require bit-exact kNN at *every* step.
2. **The device rebuild** now includes building the refresh state the next refreshes need. This is
   an upper bound: a second PCA and 60-NN, both polled.
   - Region discovery runs under the app's work budget measured before the run. The log records
     the path taken (average linkage or k-means fallback) and peak against budget.
   - The rebuild starts warm from the 99 % state, a timing proxy for "warm from the waiting map".
   - Region-discovery cancellations are logged as cancellations.
3. **Long-term drift.**
   - Additive regions now recompute centroids and floors from their final membership, so a region
     created at a rebuild can be joined afterwards.
   - The chains track regions (join every step, additive at each rebuild). The chain row also
     requires the final additive regions to be within purity − 0.01 and coverage − 0.02 of a
     fresh discovery of the final library.
   - **Known limit:** additive regions never re-partition settled tracks. The intended policy is a
     full re-partition, which renames regions, done rarely and announced: for example when the
     library has grown more than 25 % since the last re-partition, or when the owner asks.
   - That policy is not gated here beyond the 11-step chains.
4. **Threshold provenance.** The region margins (purity − 0.01, coverage − 0.02, own region − 0.10)
   were written while the additive pilot's results were visible (worst purity gap −0.0075). They
   are pilot-set, like waiting recall 0.95.
5. **Region identity bytes** count about 52 B per track (a map on ART) plus 200 centroids.
6. **Rows true by construction are guards, not evidence:** settled churn, additive coverage, and
   non-novel purity. The own-region row excludes cases where a fresh rediscovery gives the new style
   no region either; their count is reported.
7. **Further specification.**
   - Pending tracks that fall in no rediscovered world keep their joined region.
   - A new region takes the discovered name; duplicate names are counted.
   - The unseen-artist hint is expected to fire on album additions. This is reported only.

**Plan review 2: REVISE. Fixed:**

8. **No spurious tiny regions.** At a rebuild, a pending track takes, in order:
   - its world's matched old region; else
   - the world's settled majority region (a split region keeps its id for pending tracks, so two split worlds may share it); else
   - a new region, but only when the world holds at least `TrackClustering.MIN_CLUSTER_SIZE` (4)
     pending tracks.

   Otherwise it keeps its joined region.
9. **New frozen row:** on ordinary changes, at most 1 new region per change and none below
   `MIN_CLUSTER_SIZE`. Chains log new-region sizes and duplicate names, and fail on a new region
   below `MIN_CLUSTER_SIZE`.
10. **Disclosures.**
    - The device rebuild's `rows.copyOf()` for region discovery sits inside the measured window, a
      conservative upper bound on its peak.
    - `region_path` is inferred from the region work budget (correct at 10,000).

**Verdict rule.** The design is ready to implement only if every row passes, hint rows excepted.
If a row fails, nothing is re-gated on gate data.

**Disclosures.**

- The FMA tracks are the pool used before (round 1, the pilots). Only synthetic seeds
  23 / 29 / 31 are fully out of sample.
- The gate style names (Field Recordings, Pop, Rock) were read from the draws' labels.
- The LW2 encoder vectors (FMA) are not the release encoder's.
- The tagger was trained on the FMA training split. New-style tracks are held-out-split only;
  the background is mixed.
- The unseen-artist hint uses the FMA artist field, which is library metadata, not an evaluation
  label.
- Synthetic libraries have no artists or families, so there only the geometry hint and the budget
  apply.

## Round 3 result (gate run 25–26 October 2026)

**Verdict: FAIL as frozen; the design is not ready to implement.** The frozen rule was "ready only
if every row passes", and 10 rows fail (the two chain budgets counted as one row, as frozen; the scorer prints them separately).

- **What did well** (stated as findings; this is not a "ready" subset):
  - On a weak emulator the refresh took **0.14–0.15× of today's time** (2.6 s against 18.8 s at
    10,000 tracks) and cancelled within 16 ms.
  - Every trust, recall, class, lost-neighbour, p90-move, stale-basis and kNN row passed on all 57
    ordinary changes.
  - The deferred rebuild restored the new styles' RF, trust and recall in all 54 cases.
- **What failed:**
  - heap (the stored state);
  - the deferred rebuild's peak on a 256 MB heap;
  - additive regions (purity, coverage, own region, chains);
  - overlap at changes of 3 % or more;
  - one album's CF at 1 %;
  - three tiny new styles while waiting;
  - one new style's recovery CF.

**Data and setup.** Logs: `tools/research/map-round3-gate-2026-10-25.txt`, the scorer output
`map-round3-score-2026-10-25.txt`, and `map-round3-device-2026-10-25.txt`.

- Six libraries, all public: synthetic seeds 23 / 29 / 31 and FMA draws s101 / s202 / s303. No
  owner data.
- 111 cases in total: 57 ordinary changes and 54 new-style cases.
- Chains at 5 % and 3 %.
- Device: `LJ-api29` with a 256 MB heap, booted read-only.

### Pass / fail per frozen row

| Row | Result | Verdict |
| --- | --- | --- |
| refresh: trust, recall, class, lost neighbours, p90 move, stale basis, bit-exact kNN | 57 / 57 each | **PASS** |
| refresh: RF | 51 / 51 | **PASS** |
| refresh: CF | 50 / 51. Fails on FMA s303 album1, a **1.02 %** change: 0.733 vs 0.810, −0.077. The pilot warned of this; not re-gated. | **FAIL** |
| refresh: overlap with a cold build | 49 / 57. All 8 failures are synthetic changes of 3 % or more (add3, add5, change3; e.g. 0.389 vs 0.432 at add5). The 1–2 % changes pass, some narrowly: synthetic s31 change2 by 0.002, s23 add2 by 0.006. | **FAIL** |
| waiting: trust, recall (0.95×), class, lost neighbours | 54 / 54 each | **PASS** |
| waiting: RF ≥ 0.5× | 51 / 54. Fails on 3 synthetic new styles at 0.5 % (0.34–0.44× while waiting). | **FAIL** |
| recovery: RF, trust, recall | 54 / 54 each | **PASS** |
| recovery: CF | 53 / 54. Fails on FMA s101 Pop 0.5 % (0.645 vs 0.683, −0.037). | **FAIL** |
| regions: settled churn ≤ 0.001 | 111 / 111 (a guard: 0 by construction) | **PASS** |
| regions: purity ≥ fresh − 0.01 | 106 / 111. Fails on 5 FMA s202 cases, −0.012 to −0.017. | **FAIL** |
| regions: coverage ≥ fresh − 0.02 | 110 / 111. Fails on FMA s101 heldout1 (−0.024). | **FAIL** |
| regions: ordinary changes create ≤ 1 new region, none below 4 tracks | 57 / 57 | **PASS** |
| regions: new style in its own region ≥ fresh − 0.10 | 41 / 46 (8 cases where a fresh rediscovery also gives no region are excluded). The pending tracks of a split world inherit the old majority region, so a new style is sometimes absorbed (e.g. 0.29 vs 0.61). | **FAIL** |
| chains at 5 % and at 3 % | 5 / 6 each. Every **layout** check passes at every stale and final step, and kNN is bit-exact throughout. FMA s303 fails on **regions**: purity after the chain is 0.738–0.739 against 0.757 fresh, −0.018 / −0.019. No budget can be adopted. | **FAIL** |
| hints (latency, not deciding) | new styles ≥ 1 % marked stale: 36 / 36; false hints on ordinary changes: 2 / 45 | pass (reported) |
| device: refresh time ≤ 0.25× | add1: 2.61 s vs 18.83 s = **0.138×**. change1: 2.81 s vs 18.97 s = **0.148×**. Medians of 5. | **PASS** |
| device: refresh heap ≤ today + 2 MB | Refresh peak delta 28.9 MB + stored state 8.7 MB = 37.6 MB, against today's 27.7 MB + 2 MB. The 8.7 MB is an estimate from array sizes, not a heap measurement: about 7.1 MB of 60-NN lists, reduced rows, basis and hashes, plus about 1.6 MB of region identity. | **FAIL** (≈ +8 MB) |
| device: deferred rebuild ≤ 9 min | 37.7–44.4 s (layout 19.2–21.4 s, state 15.0–19.1 s, regions 3.5–5.2 s) | **PASS** |
| device: rebuild peak inside the app's budget | Peak delta 71.0 MB against 57.5 MB available before the run. It includes a matrix copy for region discovery. Before the run, the app's own budget formula (regions need 81.4 MB, 57.5 MB available) chose the **k-means fallback** (16 regions). The host region rows used average linkage, so they do not describe the path a 10k-track, 256 MB device actually takes. | **FAIL** |
| device: cancellation ≤ 250 ms from the deadline | Refresh: 7–16 ms at 0.3 / 1.2 / 2.3 s, all inside the optimizer; deadlines in the earlier, shorter phases were not hit. Rebuild: 0.1–13 ms at 2 / 10 / 30 s (regions, state, layout). **But** the completed rebuilds show gaps of 315–438 ms between polls, so a deadline landing in such a gap would exceed 250 ms. The pass reflects where three sampled deadlines fell. | **PASS as sampled** (worst case not shown) |

**Disclosed deviations.**

- On the device, the frozen harness ran out of memory in the untimed setup: the research state
  build makes two extra copies of the 54 MB matrix. A memory-lean state build (`stateOfLean`: one
  consumed copy, row-by-row projection, numerically equivalent) was added after the freeze, for
  the device stage only. It is used in the setup **and in the measured rebuild's state step**, so
  the device rebuild row was not measured with frozen code. That lowers its peak and time, which
  cannot flip either verdict: the peak fails anyway, and the time passes by a wide margin.
- Host results use the frozen code.
- The rebuild's state step remains an upper bound: a second PCA and 60-NN that production would
  share with the layout.

### What this means (a post-hoc reading, not gated)

The following is interpretation after seeing the gate data. It is not a pass and does not change
the verdict.

1. **The most promising candidate for the next round** is the layout refresh for changes of
   ≤ 2 %, with approach-3 caches and a deferred full rebuild.
   - Every layout row except refresh CF passed at 1–2 %. The CF failure is one 1.02 % album.
   - The 1–2 % overlap margins are thin: 0.002–0.006 on two synthetic cases.
   - A 2 % stale budget was **not run**: the chains ran 5 % and 3 %.
   - A fresh round needs a 2 % chain, albums at 1–2 % on more FMA draws, and new seeds.
2. **The stored state needs a slimmer design** before the heap row can pass:
   - drop the stored distances and recompute them;
   - store reduced rows as fp16, memory-mapped from disk;
   - keep region identity as primitive arrays.

   The estimated 37.6 MB peak is well under a 256 MB heap limit; the failure is the frozen +2 MB
   margin.
3. **Additive regions keep every settled track's region**, which is what the owner asked for.
   But they drift behind a fresh rediscovery (purity −0.019 over a chain), and they sometimes
   absorb a new style into an old region. Region identity needs another design, for example
   rediscover and match by overlap, letting a settled track move only when its new region is
   clearly better, plus the planned periodic re-partition.
4. **The deferred rebuild on a 256 MB heap** goes over the app's budget, and its regions fall back
   to k-means by the app's own formula. An app rebuild must stream the matrix as the layout already
   does, and its region discovery must fit the budget.
5. **Cancellation inside the rebuild** has 300–440 ms gaps between polls, inside region discovery
   or the state build. They need finer polling before a 250 ms guarantee can be claimed.

## Implementation plan (27 October 2026, revised after review)

**Decision by the owner** ("разбирай и решай"): stop re-gating the design as one monolith.
Implement it in separable pieces, each with its own focused, predeclared check.

**Why.** In round 3:

- every layout quality row on ordinary changes passed, apart from one album's CF;
- the refresh ran at 0.14× today's time on a 256 MB device;
- the deferred rebuild recovered new styles.

The failures were engineering problems, not the refresh idea: the heap margin, the rebuild's memory
path, poll granularity and region identity.

### Where and how

- **Branch.** `feat/map-refresh`, in its own worktree, cut from `feat/lw2-093` at 3853a97b (the
  owner's current phone build). Nothing is committed on `feat/lw2-093`, nothing is pushed, and
  nothing is installed on the phone; the parent session does that.
- **Starting point.** On that branch the Map code is identical to what rounds 1–3 measured:
  `LibraryLayout`, `BarnesHutTsne`, `Pca`, `MapPageCache`, `AppMapPage`, and the region pipeline in
  `App.kt`.
- **No scheduler exists yet:** no WorkManager and no `BGTaskScheduler`. The iOS code refuses a
  `BGProcessingTask` that could not reconstruct its work on a cold launch, and piece 3 keeps that
  rule.
- **One flag,** `MapRefresh.enabled`, default **off**. With it off, behaviour is exactly today's.
- **Data.** Checks use public data only. The owner's library is for qualitative looks, in the
  scratchpad.
- **Fresh data per frozen check:**
  - pieces 1–2: synthetic seeds 37 / 41 / 43 and FMA draws s404 / s505 / s606;
  - piece 4: seeds 47 / 53 / 59 and draws s707 / s808 / s909;
  - piece 3 device: seed 61.
- **Every piece:**
  - its own freeze in this report;
  - an adversarial review before and after;
  - small commits without AI attribution;
  - host tests, iOS compile, and the `checkNativeIdentifiers` guard on new test names.

### Piece 0: the refresh state's format and versioning

- **Store.** A sidecar `map_refresh_state.bin`, handled by its own store: `expect` / `actual`
  `MapRefreshStateStore`, with Android, iOS and JVM (host-test) actuals.
  - **Reading** is memory-mapped: `MappedByteBuffer` on Android, `NSData` mapped on iOS.
  - **Writing** uses a temp file plus an atomic rename.
  - `IndexStore` is not used: it only takes two finite floats per entry and has no paths.
- **Contents**, in layout order, all **fp32** with **no distances**:
  - reduced rows (50-d);
  - 60-NN ids (`Int`);
  - row hashes;
  - mean, basis and the raw spread.
- **Size.** About 4.5 MB at 10,000 tracks, all memory-mapped, so not Java heap. fp16 rows were
  considered and dropped: they would break bit-exactness. Distances are recomputed from the fp32
  rows with the exact arithmetic of `nearestNeighbours`, so the patched lists stay bit-exact.
- **Header:**
  - format version and `cacheVersion`;
  - layout `VERSION`;
  - source (metadata / audio / hybrid);
  - dim and `COMPONENTS`;
  - audio encoder, text encoder and fusion versions;
  - the layout fingerprint it was written with;
  - a hash of the ordered id list;
  - a checksum.
- **Any mismatch means a full build.** In particular, a change of source (metadata → hybrid) or of
  encoder version always means a full build.
- **Shares** of the change are cumulative since the last full build: additions + changes + removals
  over the current size.
- **Check.** Host tests:
  - round-trip;
  - a truncated, stale or mismatched file is rejected and causes a full build;
  - the atomic write survives being killed mid-write (simulated);
  - JVM and iOS-simulator parity for the mapped reads.

### Piece 1: layout refresh (core/smart, then AppMapPage)

**Behaviour, applied in order:**

| Case | What happens |
| --- | --- |
| flag off | today's full build |
| analysis in progress (`audioVectorsReady` false) | today's behaviour, including the 5-minute `MAP_LAYOUT_REUSE`; the refresh is off until analysis ends |
| n ≤ 2,000 (`ExactMapEmbedding`), or the experimental native backend, or the library crossed 2,000 since the last full build | today's full build; there are no lists or sparse P to refresh from |
| no valid refresh state (piece 0 header mismatch, first build) | today's full build, which writes the state |
| change > 25 % | today's full build |
| change ≤ 2 % and no stale hint | **refresh** |
| change 2–25 %, or a stale hint | **refresh now** and mark the map **stale** for piece 3 |

**Code.**

- **Hashing** of the *raw fused input row* (before centring), as read in the *final projection pass*
  of `Pca.reduceStreamed`, so each hash describes the row that was actually projected. The next
  refresh can then compare raw rows without needing the mean.
  - The Map path never calls `isCurrent()`: a mid-read content change is tolerated, and the layout
    is filed under the older coverage fingerprint.
  - With hashes of the rows actually used, the next refresh flags exactly the rows that differ.
- `SampledTsne.reduce` / `reduceStreamed` expose their mean and basis.
- `MapEmbeddingBackend` returns a richer result: coordinates plus, for the Kotlin Barnes–Hut
  backend, the 60-NN index arrays.
- `BarnesHutTsne.embed` is refactored onto a new `optimize(p, initial, schedule)` and must stay
  **bit-identical**.
- A new `LibraryLayoutRefresh` ports the research engine minus routers:
  1. project flagged rows with the frozen mean and basis;
  2. patch the 60-NN (primitive merge, recomputed distances);
  3. place from the lists;
  4. `jointProbabilities`;
  5. 150 iterations without exaggeration;
  6. `alignToPrevious` and `normalize`.
- The stale hints are latency only: group geometry, the LW2 family conflict when predicted families
  exist, and unseen artists.
- **One writer.** Every layout and sidecar write happens under `mapWorkGate` on
  `AppGraph.mapLayoutDispatcher`, whether it comes from the UI or from the piece-3 worker.

**Check** (seeds 37 / 41 / 43, draws s404 / s505 / s606; frozen before it runs):

- **Equivalence.** On fixtures, the production refresh equals the research engine (fp32, no stored
  distances) bit-for-bit. The patched kNN equals an exact search, as a property test over random
  add / change / remove mixes. With the flag off, all existing Map tests stay bit-identical.
- **Quality.** Uniform, changed, album and held-out changes at 0.5 / 1 / 2 %, plus a **2 % chain**:
  20 steps of +0.5 %, stale at > 2 %, rebuild, continue.
  - Rows: trust, recall, class, RF, lost neighbours, p90 move, bit-exact kNN.
  - **Overlap and CF are judged against a 5-seed cold noise band:** pass if the refresh's value is
    ≥ the band's minimum − 0.02 per case, and ≥ the band's mean − 0.05 on average.
- **Size switches.** A library crossing 2,000 between builds, and one with the native backend: both
  take the full build.

### Piece 2: refresh heap

- With the state memory-mapped (piece 0), the refresh's Java heap holds only the patched index
  array (2.4 MB), a transient distance array (freed once P is built), P, and the placement and
  optimizer arrays.
- **Round 3 measured** a refresh peak of 28.9 MB against today's 27.7 MB, with these arrays
  included, so the margin is thin (about 0.8 MB). This is stated before the run.
- **Check** on `LJ-api29` (256 MB, read-only, seed 61), median of 5:
  - **Java heap:** refresh peak delta ≤ today's + 2 MB, with the mapped bytes reported separately
    as native or mapped memory.
  - **Time:** refresh ≤ 0.25× today.  - **iOS:** simulator physical footprint, measured with `task_info`, during the refresh and the
    rebuild, against today's. **Reported, not gated** (the simulator is not a device); stated as
    unmeasured on a real device.
- **If the heap row fails,** the planned fallback, predeclared, is: keep only the patched index
  array and stream the affinities per row. It is then re-measured; if it still fails, the piece is
  reported as failed and the flag stays off.
- Pieces 2 and 3 both use seed 61; they measure different things (refresh vs rebuild).

### Piece 3a: a region service outside Compose (before pieces 3 and 4)

- Map regions are For You's worlds, discovered today in a `LaunchedEffect` in `App.kt`. That
  pipeline moves into a `LibraryRegionsService` owned by `AppGraph`, which the UI observes.
- **With the flag off,** the service reproduces today's worlds exactly.
- **Check:** For You parity with the flag off, meaning identical worlds and mixes on fixtures. The
  existing region tests pass.

### Piece 4: regions with identity (after 3a)

**Stored** in the sidecar: the region id per track (an `IntArray`); per region its id, name,
colour index, centroid and floor.

**Between rebuilds:** a new or changed track joins the nearest centroid if it clears that region's
5th-percentile floor; otherwise it gets no region. For You uses these same regions while the map is
stale.

**At the full rebuild:**

1. Rediscover.
2. Match new regions to old by member overlap, greedy and one-to-one. A matched region keeps the
   old id, name and colour.
3. A settled track moves only if its rediscovered region has a different id *and* its cosine to
   that region's centroid beats its cosine to the old one by **δ**.
4. Unmatched worlds with at least `MIN_CLUSTER_SIZE` members become new regions.

**Pilot** (synthetic seed 7 and the FMA pilot draw) to set δ. It includes a **path switch**:
average linkage at one rebuild and k-means at the next, because on a 10,000-track, 256 MB device
the app's own budget formula picks k-means (round 3).

**Check** (seeds 47 / 53 / 59, draws s707 / s808 / s909), **on both region paths**:

- settled churn ≤ 2 %, with its distribution logged and today's churn for contrast;
- purity ≥ fresh − 0.01;
- coverage ≥ fresh − 0.02;
- new style in its own region ≥ fresh − 0.10;
- no new region below `MIN_CLUSTER_SIZE`;
- the 2 % chain;
- For You with the flag on: the mixes come from the identity regions.

**Not in this plan:** making average linkage fit a 256 MB heap at 10,000 tracks, for instance float
distances or linkage over the 128-d projection. That is a separate piece if wanted.

### Piece 3: the deferred full rebuild (after 3a and 4)

- **Headless entry.** A `MapRebuild` routine that builds everything from disk without UI:
  - **the track list and descriptors** from `AppGraph.library`'s persisted scan, with genre
    enrichment from its cached tag facts;
  - **the playlists** from `AppGraph.playlists`;
  - **the novelty preference** from settings;
  - **the album catalog for naming** from the library;
  - **the engine** loaded to `Ready` from `IndexStore`;
  - **then** `libraryMixRows`, the region service and the layout.
- **"Analysis complete" is a persisted test,** not the UI's `audioVectorsReady`: every track in the scanned list the Map uses (`tracks`, not `smartEligibleTracks`)
  has an audio vector for the current encoder version, checked from the index coverage. Otherwise the
  worker reschedules and builds nothing; a rebuild never uses partial audio coverage.
- **Region names.** The stored per-region name is the structured name
  (`LibraryWorld.name` / `nameSource` / `semanticTitle`), *not* a localized string. The UI localizes
  it at display time, as today.
- **Swap-in.** After a background rebuild writes the layout and sidecar, it bumps a persisted rebuild
  revision. The UI's `MapPageCache` and the region service's observers compare it on their next read
  and reload, so there is no stale in-memory map.
- **Rules.**
  - It runs under `mapWorkGate`, and never while an analysis is in progress (it reschedules
    instead).
  - `libraryMixRows` takes the engine lock once per chunk, so a rebuild interleaves with SMART.
    This is accepted, and SMART's longest wait is measured.
- **Android.** WorkManager (`androidx.work`, Apache-2.0) runs a `CoroutineWorker` as unique work,
  constrained to charging + device idle.
  - Check that the merged manifest (WorkManager's `InitializationProvider`), the `dataSync`
    `IndexingService` and F-Droid builds are unaffected.
- **iOS.** A `BGProcessingTaskRequest` with `requiresExternalPower`.
  - `Info.plist` gets `BGTaskSchedulerPermittedIdentifiers` and `processing` added to
    `UIBackgroundModes`.
  - The handler is registered before launch finishes, from the SwiftUI `App.init` or through
    `@UIApplicationDelegateAdaptor`.
  - The expiration handler cancels the rebuild, and it is rescheduled.
  - This is honest under the existing rule, because the rebuild's input is persisted.
- **Fallback.** If no scheduled run happens within 24 h, the next Map visit while charging runs the
  rebuild in-app. The number of fallback runs is logged.
- **Streaming.** Regions and the layout run one after the other, each inside `MapMemoryBudget`;
  the rebuild never holds two fused matrices. The refresh state is written from the layout's own
  basis and lists, with no second PCA or kNN.
- **Polling.** At most 250 ms of work between cancellation polls on the slowest device, in `Pca`'s
  fit and projection, the neighbour search, region discovery and the state write.
- **Debug marker.** A debug-only "stale" marker behind the flag. Otherwise a stale map looks like
  today's map.

**Check** (`LJ-api29`, seed 61, background priority):

- Completes inside the app's budget formula, on whichever region path the formula picks; the path
  and peak against budget are logged.
- Time ≤ 9 min.
- **The longest gap between polls over the whole rebuild ≤ 250 ms,** and cancellation at 10 random
  deadlines ≤ 250 ms.
- **Cold-launch tests:**
  - **Android:** process killed, then `adb shell cmd jobscheduler run -f`; the job completes and
    the map swaps in.
  - **iOS simulator:** process killed, then `_simulateLaunchForTaskWithIdentifier`; same result.
  - **Expiry:** an expired or cancelled run leaves the old map intact and reschedules.
- SMART's longest engine-lock wait during a rebuild is reported.

### Piece 5: the flag

- `MapRefresh.enabled`: a build constant plus a debug-settings toggle, default **off**.
- It is defaulted **on** only after the piece-2 and piece-3 device checks pass and the parent session
  has tried it on the phone.

### Order and reporting

**Order:** piece 0, then 1 + 2, then 3a, then 4, then 3, then 5. The plan summary is reported now;
progress is reported after each piece.

**Out of scope:**

- region naming from LW2 signals;
- reducing the memory of average-linkage regions;
- any UI beyond the debug marker.

- **Plan review log:** round 1 REVISE (3 blockers, 7 majors), round 2 REVISE (1 major: headless
  inputs), round 3 **READY**.

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

- **Gate verdict review (fresh subagent): REVISE.** No harness bug was found that flips a
  verdict. Issues raised:
  - wrong counts: 25 refreshed cases, not 26, and 20 for RF / CF;
  - router time was excluded from the time row without saying so (with it: 0.47–0.57× on the
    host);
  - "every quality row" over-claimed (the album overlap miss);
  - the chain diagnostic was cited as evidence;
  - the 0.398× pass was over-stated: the contended-series median of about 0.42 fails, and
    run-to-run noise exceeds the margin;
  - the cancellation probe under-measures;
  - kept15 was called "unattainable";
  - `LJ-api30` was not run, and the ladder outcome was not stated;
  - small number slips.

  **Answered by:** every point corrected in the section above. The verdict is unchanged (FAIL);
  the time row is now FAIL as built.
- **Confirming round (fresh subagent): REVISE.** Every number and count matched the logs. Three
  text fixes remained, now made:
  - "every quality row except overlap" now says that RF and CF failed on the novel styles the
    router let through, and the summary box lists RF and CF;
  - the contended range is 0.37–0.44;
  - the kept15 cut-off is ≈ 0.87.

  Verdict unchanged.

- **Round 3 verdict review (fresh subagent): REVISE.** All counts, medians and heap sums matched
  the logs; no owner data in the repository. Issues raised:
  - "every layout row passes at 1–2 %" and "fully restores" were false (the 1.02 % album CF;
    recovery CF);
  - the "ready layout half" was a post-hoc split, and the 2 % budget was never run;
  - the thin 2 % overlap margins were not reported;
  - rebuild poll gaps of 315–438 ms;
  - `stateOfLean` also affected the measured rebuild;
  - every refresh deadline fell inside the optimizer;
  - the 8.7 MB split, the k-means fallback's cause, and number nits.

  **Answered by:** a rewritten verdict that keeps FAIL, marks the reading post-hoc, reports the
  margins, gaps and the full `stateOfLean` disclosure, and corrects the numbers.
- **Confirming round: one number error** (worst purity gap −0.017, not −0.015), now fixed.
  Everything else matched.

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
