# Map "sample and place" layout — 16 October 2026

Prototype and measurement of a cheaper Map layout for large libraries, on branch
`feat/map-sample-place` (cut from `feat/map-scale-10k`, 95c0b194). Context:
[the 10k scaling note](map-scaling-10k-2026-10-15.md).

## Predeclared gate (written before any candidate result was seen)

The candidate is compared with **full Barnes–Hut** (the current path above 2,000 tracks) on the
same input. Metrics as in `MapScaleReplay`: trust@15 and recall@15 against the PCA-50 input of
the *full* library (the same reference for both methods), playlist@15 (real library) or class@15
(synthetic), and overlap@15 with the full Barnes–Hut map. "BH seed 2" is Barnes–Hut with another
start: it measures how much two acceptable maps already differ.

The candidate is **acceptable** only if, on every input measured (synthetic 5,000 and 10,000;
the real 1,104-track library with a forced sample of 30 %, the production ratio at 10,000):

| Metric | Allowed loss vs full Barnes–Hut |
| --- | --- |
| trust@15 | ≤ 0.010 absolute |
| recall@15 | ≤ 10 % relative |
| playlist@15 / class@15 | ≤ 0.020 absolute |
| overlap@15 with full BH | ≥ overlap(BH seed 2, BH) − 0.10 |
| stability (+5 % tracks) | old tracks' 2-D 15-NN kept ≥ warm-started full BH − 0.05, and median displacement ≤ 1.5× full BH's |

and the cost side must be worth a new code path:

| Cost | Required |
| --- | --- |
| time at 10,000 (256 MB emulator) | ≤ 0.5× full Barnes–Hut |
| peak Java heap at 10,000 | ≤ 0.6× full Barnes–Hut |
| cancellation | every phase stops within 250 ms |

Outcome rules: all pass → adopt above a threshold; quality passes only at some sizes → adopt
above the smallest size where it passes; any quality row fails at 10,000 → reject.

## Verdict

**Reject sample-and-place at every size.** Every candidate fails the gate above at 10,000 tracks.
The best tuned variant is close on the neighbour metrics, but it moves existing tracks two to
three times more than today's warm-started Barnes–Hut when a library grows. The Map is built
around not moving. The cost side would have passed.

**What should ship instead (follow-up):** the second half of the idea, a PCA that reads rows one
at a time, works on its own. Subspace-iteration PCA can be streamed *exactly*: the streamed
reduction is **bit-identical** to today's, so the map does not change at all. Feeding today's
Barnes–Hut from it at 10,000 tracks took the same time (83.5 s against 82.1 s) and peaked at
**31 MB of Java heap instead of 84 MB**, because the 54 MB fused matrix is never held. It is now
wired into the app on `feat/map-streamed-pca` (see [below](#follow-up-streamed-pca-in-the-app-done-branch-featmap-streamed-pca)).

## What was built

All of it is `internal` prototype code in `core/smart`. No app path calls it, and existing outputs
are unchanged: the 555 existing host tests pass, the iOS test sources compile, and the existing PCA and
Barnes–Hut results are unchanged.

- `SampledTsne` (new). It takes 3,000 tracks in `AverageLinkageClustering.stableKey` order, the
  same stable sample the sampled regions use. It reduces every row and lays out the sample with
  `BarnesHutTsne`. Every other track goes to the perplexity-weighted mean of its 10 nearest
  sampled tracks in PCA-50 (Gaussian calibrated to perplexity 3.3, as in openTSNE's transform).
  Two refinements are optional:
  - *refine*: up to N iterations that move only the placed tracks, attracted to their sampled
    neighbours and repelled by the fixed sample through its quadtree (openTSNE-style).
  - *full*: N Barnes–Hut iterations over every row from the placed start, with sparse affinities
    over all rows, no exaggeration and late momentum.
- `Pca.reduceStreamed` (new). Each subspace iteration is one fused pass: project a row, then
  accumulate it into `rowsᵀ·scratch` immediately, in the same row and dimension order as
  `Pca.reduce`. The result is bit-identical; only `n × 50` is held. `SampledTsne.reduce` adds
  the same float centering and normalization as `LibraryLayout.reduceForEmbedding`. A new
  `PcaTest` case pins the bit identity, and it passes on the JVM and the iOS simulator. The
  replay also confirmed it at 1,104, 5,000, 6,000 and 10,000 rows.
- `LibraryLayout.computeSampled` / `computeStreamed` / `computeWith` (prototype entry points).
  `BarnesHutTsne.embed` gained an optional schedule; its defaults are unchanged.
  `QuadTree.repelAt` is `repel` for a point outside the tree.
- Harnesses: `MapSampleReplay` (host quality and stability) and new `MapScaleDeviceTest` stages,
  `layout-stream` and `layout-sampled[-stream]`. A `-stream` stage regenerates each synthetic
  row from its own seed, so the n × 1,344 matrix is never allocated.

### Tuning (not a gate input)

Variants were chosen on a separate fixture: synthetic, 6,000 tracks, seed 3. Only three were
taken to the gate:

| Variant | recall@15 (BH 0.337) | overlap with BH (seed noise 0.633) | Note |
| --- | ---: | ---: | --- |
| place only, PCA fitted on the sample | 0.214 | 0.192 | the idea as first proposed |
| + 30 / 100 refine iterations | 0.232 | 0.206 | refinement barely helps |
| BH on all rows, PCA fitted on the sample | 0.280 | 0.258 | **PCA on the sample alone costs ~17 % recall** |
| place only, full streamed PCA (`r0`) | 0.249 | 0.267 | |
| + 30 refine (`r30`) | 0.275 | 0.302 | |
| sample BH 500 it + place + 250 full BH it (`r0f250i500`) | 0.331 | 0.499 | best; also the fastest |

The PCA basis fitted on 3,000 sampled rows misses directions that matter for 15-NN in the
full-library PCA-50 space. On the real library the cost was smaller (recall 0.614 against
0.626), but the synthetic library is flat past ~48 directions. Fitting on every row is free once
streamed, so every gate candidate uses the full streamed PCA.

## Quality against full Barnes–Hut (gate inputs)

The reference for trust and recall is the full-library PCA-50. Gate thresholds are in brackets;
**bold** marks a failure.

**Synthetic, 10,000** (seed 7). Thresholds: trust ≥ 0.9737, recall ≥ 0.2401, class ≥ 0.8279,
overlap ≥ 0.4350.

| Method | trust@15 | recall@15 | class@15 | overlap with BH |
| --- | ---: | ---: | ---: | ---: |
| full Barnes–Hut | 0.9837 | 0.2668 | 0.8479 | 1 |
| BH seed 2 | 0.9836 | 0.2690 | 0.8479 | 0.5350 |
| `r0` | 0.9767 | **0.1583** | 0.8479 | **0.1474** |
| `r30` | 0.9826 | **0.1969** | 0.8479 | **0.1816** |
| `r0f250i500` | 0.9827 | 0.2597 | 0.8479 | **0.4292** |

**Synthetic, 5,000.** Thresholds: trust ≥ 0.9745, recall ≥ 0.3273, overlap ≥ 0.5189.

| Method | trust@15 | recall@15 | class@15 | overlap with BH |
| --- | ---: | ---: | ---: | ---: |
| full Barnes–Hut | 0.9845 | 0.3637 | 0.8452 | 1 |
| BH seed 2 | 0.9845 | 0.3634 | 0.8451 | 0.6189 |
| `r0` | 0.9802 | **0.2934** | 0.8452 | **0.3300** |
| `r30` | 0.9828 | **0.3107** | 0.8450 | **0.3547** |
| `r0f250i500` | 0.9837 | 0.3593 | 0.8452 | 0.5452 |

**Real library, 1,104 tracks, sample 331 (30 %, the production ratio at 10,000).** This is the
private dump (`MAP_DIAG_DIR`), with production fusion and 20 playlists; only aggregates are
shown. Thresholds: trust ≥ 0.9697, recall ≥ 0.5631, playlist ≥ 0.6182, overlap ≥ 0.6924.

| Method | trust@15 | recall@15 | playlist@15 | overlap with BH |
| --- | ---: | ---: | ---: | ---: |
| full Barnes–Hut | 0.9797 | 0.6257 | 0.6382 | 1 |
| BH seed 2 | 0.9784 | 0.6194 | 0.6340 | 0.7924 |
| `r0` | **0.9548** | **0.5223** | 0.6211 | **0.5488** |
| `r30` | **0.9687** | **0.5466** | 0.6278 | **0.5707** |
| `r0f250i500` | 0.9758 | 0.6178 | 0.6297 | 0.7558 |

### Stability (+5 % tracks)

Every 20th track is held out. The other 95 % are laid out cold, then the whole library is laid
out warm-started from those positions, as after an import. *kept* is the share of each old
track's 15 nearest old neighbours on the map that are still among them afterwards. *move* is the
median distance an old track travels in the normalized 0..1 box. The reference is today's path:
Barnes–Hut with warm start and alignment.

| Input | Method | kept | median move | p90 move | Gate |
| --- | --- | ---: | ---: | ---: | --- |
| synthetic 10,000 | full BH, warm | 0.422 | 0.029 | 0.068 | kept ≥ 0.372, move ≤ 0.044 |
| | `r0` | **0.322** | 0.032 | 0.070 | |
| | `r30` | 0.469 | 0.041 | 0.076 | |
| | `r0f250i500` | 0.380 | 0.039 | 0.082 | |
| synthetic 5,000 | full BH, warm | 0.489 | 0.028 | 0.071 | kept ≥ 0.439, move ≤ 0.043 |
| | `r0` | **0.438** | **0.048** | 0.089 | |
| | `r30` | 0.479 | **0.056** | 0.100 | |
| | `r0f250i500` | 0.476 | **0.045** | 0.100 | |
| library 1,104 | full BH, warm | 0.790 | 0.066 | 0.129 | kept ≥ 0.740, move ≤ 0.099 |
| | `r0` | **0.655** | **0.185** | 0.335 | |
| | `r30` | **0.711** | **0.171** | 0.333 | |
| | `r0f250i500` | 0.774 | **0.198** | 0.472 | |

Streamed PCA + Barnes–Hut needs no rows here: its input is bit-identical, so its map, and its
stability, are today's.

## Cost on a 256 MB-heap emulator

Same setup as [the 10k note](map-scaling-10k-2026-10-15.md): `LJ-api30` (Android 11, arm64,
4 vCPU), booted `-read-only`, `dalvik.vm.heapgrowthlimit` and `dalvik.vm.heapsize` set to 256m,
`heap_limit_bytes=268435456` logged, background priority, nothing else running. The owner's phone
was attached by USB and not used (`ANDROID_SERIAL=emulator-5560`). LJ-demo was not used.

The peak is sampled used Java heap (every 10 ms), everything included. For the held-matrix rows
that includes the input matrix (29 MB at 5,000, 56 MB at 10,000 before the run). The streamed
rows never allocate it. Streamed "PCA" time includes regenerating each synthetic row with a
random generator, which is slower than copying a row out of the vector index.

| Tracks | Method | Total | PCA | Sample BH | Place | Full BH | Peak heap |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 5,000 | full BH, held matrix (today) | 37.3 s | 4.0 s | | | 33.3 s | 45.6 MB |
| 5,000 | full BH, streamed PCA | 37.0 s | 4.3 s | | | 32.8 s | **16.5 MB** |
| 5,000 | `r0`, streamed | 23.5 s | 4.3 s | 19.1 s | 0.1 s | | 12.3 MB |
| 5,000 | `r0f250i500`, streamed | 16.6 s | 4.3 s | 9.8 s | 0.1 s | 2.4 s | 17.3 MB |
| 10,000 | full BH, held matrix (today) | 82.1 s | 7.5 s | | | 74.6 s | 84.4 MB |
| 10,000 | full BH, streamed PCA | 83.5 s | 8.7 s | | | 74.8 s | **30.8 MB** |
| 10,000 | `r0`, streamed | 27.9 s | 8.7 s | 18.7 s | 0.5 s | | 14.4 MB |
| 10,000 | `r0f250i500`, streamed | **25.7 s** | 8.7 s | 10.0 s | 0.4 s | 6.7 s | **32.4 MB** |
| 20,000 | full BH, streamed PCA | 191.3 s | 17.5 s | | | 173.8 s | 61.3 MB |
| 20,000 | `r0f250i500`, streamed | 47.3 s | 17.6 s | 9.9 s | 1.0 s | 19.0 s | 64.0 MB |

At 10,000, `r0f250i500` would have passed the cost side: 0.31× the time and 0.38× the heap
(required ≤ 0.5× and ≤ 0.6×). Most of the heap saving comes from streaming, not sampling: the
full Barnes–Hut on streamed rows is also at 0.37×. 20,000 was measured for cost only, since the
gate had already failed at 10,000. Quality at 20,000 was not measured.

### Cancellation (10,000)

| Method | Cancelled at | Phase reached | Stopped within |
| --- | ---: | --- | ---: |
| full BH, streamed PCA | 3 s | streamed PCA | 3.8 ms |
| full BH, streamed PCA | 20 s | neighbours / optimizer | 40.7 ms |
| `r0f250i500` | 3 s | streamed PCA | 18.8 ms |
| `r0f250i500` | 15 s | sample BH | 9.7 ms |
| `r0f250i500` | 22 s | full BH from the placed start | 0.2 ms |

The longest gap between two polls in an uncancelled run: 93 ms (streamed BH, 10,000), 178 ms
(streamed BH, 20,000) and 56 ms (`r0f250i500`, 10,000).

## Why sampling loses here

- **PCA on a sample** keeps the leading directions but not the tail. That tail decides many
  15-NN in a 50-d space (−17 % recall on its own, synthetic).
- **Placement** puts a track at a weighted mean of sampled positions. That cannot resolve
  structure finer than the sample's spacing, and placed tracks never repel one another.
  Refinement against a fixed sample adds repulsion from the sample only, so it recovers little.
  Only Barnes–Hut iterations over *all* rows bring recall back. Those need the full
  60-neighbour affinities, which is most of the work sampling was meant to avoid.
- **Stability**: the placed start is a new start, and the short full pass cannot settle it
  back. Old tracks moved 0.045–0.20 of the map width (median) where today's warm start moves them
  0.03–0.07.

## Follow-up: streamed PCA in the app (done, branch `feat/map-streamed-pca`)

The app's Map layout now uses the streamed reduction. The 54 MB fused matrix is no longer built
for the layout.

- **Engine API.** `SimilarityEngine.libraryMixRows(ids)` returns `LibraryVectorRows`. These are
  the rows `libraryMixVectors` would build, read on demand in chunks of 256 rows, one engine lock
  per chunk, so SMART waits at most one chunk. Each row is written by the same code as
  `LibraryVectorFusion.buildSelected` (`writeHybridRow` / `copyNormalized`) from
  `VectorIndex.vector(id)`. After the last read, `isCurrent()` compares the coverage fingerprint
  with the one the rows were planned against. If the index changed during the ~6 passes,
  `LibraryLayout.compute(rows)` returns null and lays out nothing. The interface default serves
  rows from a built space, which keeps other engines working but saves nothing.
- **Layout.** `LibraryLayout.compute(rows, …)` is mean pass → `Pca.reduceStreamed` → the same
  backend, warm start, alignment and normalization as `compute(space, …)`. For the same input it
  returns the same `LayoutPoint`s exactly (`MapStreamedRowsTest`: 300 hybrid tracks across a
  chunk boundary, including one audio-only and one metadata-only row, cold and warm-started; a
  deliberately broken row writer fails it).
- **Budget.** `MapMemoryBudget.projectionStreamedBytes` drops the `4·n·dim` input and adds one
  chunk buffer. At 10,000 × 1,344 that is **41,288,960 B (39.4 MiB)** against 93,672,704 B
  (89.3 MiB) held. `AppMapPage`'s pre-allocation check and `compute`'s own check both use it.
- **Unchanged.** Region discovery still builds its own fused space (`libraryMixFeatures` →
  `takeRowsForRegions`). For You's Sonic Journey still copies the matrix.

### Measured in the app on `LJ-big36` (192 MB heap, 10,000 tracks)

`LJ-big36` was booted `-read-only` (emulator-5570, `ANDROID_SERIAL` pinned) and its 0.6.0 install
was updated in place with this branch's arm64 release APK. All 10,000 tracks were then
fingerprinted, about 70 min at ~2.3/s. This is the state in which the 0.9.1 build showed *"Not
enough free memory to calculate the map"* ([10k note](map-scaling-10k-2026-10-15.md), step 4).
`dumpsys meminfo` Dalvik *Heap Alloc* was sampled several times a second; it includes garbage
not yet collected.

| Phase (Map tab opened at 09:05:38) | Dalvik heap allocated |
| --- | --- |
| idle, all fingerprints done, before opening | ~101 MB |
| region discovery (own fused space), first ~4 s | ~155 MB plateau |
| **streamed layout**, ~61 s | **108–112 MB steady, 125.5 MB highest sample** |
| after the Map drew | ~103 MB |

**The fused 10,000-track Map drew**: *"8971 of 10000 tracks across 17 regions"*, 71 s after the
tab opened, without a memory refusal. The layout itself held about 11 MB above the idle app. The
visit's peak belongs to region discovery, which this change does not touch. It is now the larger
of the two Map allocations on a 192 MB heap.

## Reproduce

```sh
# Host quality + stability (gate inputs). Tuning fixture: MAP_SAMPLE_TUNE=1 [MAP_SAMPLE_TUNE_N=6000]
MAP_SAMPLE_REPLAY=1 MAP_SAMPLE_SIZES=5000,10000 MAP_SAMPLE_VARIANTS=r0,r30,r0f250i500 \
  MAP_DIAG_DIR=<private dump> ./gradlew :core:smart:testAndroidHostTest --tests "*MapSampleReplay*" --rerun

# Device (an emulator you may change; never LJ-demo or a phone), heap props as in the 10k note
export ANDROID_SERIAL=emulator-5560
./gradlew :core:smart:assembleAndroidDeviceTest
adb install -r -t core/smart/build/outputs/apk/androidTest/smart-androidTest.apk
adb shell am instrument -w -r -e class io.github.nikitasud.latentjam.smart.cluster.MapScaleDeviceTest \
  -e mapScale 1 -e mapRows 10000 -e mapStage layout-sampled-stream -e mapFull 250 -e mapSampleIter 500 \
  io.github.nikitasud.latentjam.smart.test/androidx.test.runner.AndroidJUnitRunner
```

Stages: `layout` (today), `layout-stream` (streamed PCA + today's backend, up to 20,000),
`layout-sampled` / `layout-sampled-stream` with `-e mapRefine N`, `-e mapFull N` and
`-e mapSampleIter N`. Results go to `files/map-scale.log` as before.
