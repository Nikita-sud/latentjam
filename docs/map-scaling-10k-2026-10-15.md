# Map up to 10,000 tracks — 15 October 2026

The Map now lays out and finds regions for libraries of up to **10,000 tracks** (was 3,000).
Branch `feat/map-scale-10k`, cut from `codex/adversarial-0.9.1` (8102b432). Builds on
[the scaling analysis](map-scaling-analysis-2026-10-13.md),
[the region algorithm search](map-algorithm-search-2026-10-14.md) and
[the average-linkage device note](map-average-linkage-device-2026-10-14.md).

## What changed

| Part | Up to the cutoff | Above the cutoff |
| --- | --- | --- |
| Layout (`LibraryLayout`) | exact t-SNE, unchanged, up to **2,000** | **Barnes–Hut t-SNE** in pure Kotlin (`BarnesHutTsne`), 2,001–10,000 |
| Regions (`AverageLinkageClustering`) | exact average linkage, unchanged, up to **3,000** | **sampled average linkage**, 3,001–10,000 |

Both new paths are common Kotlin, so Android and iOS run the same code. The experimental native
backend (`core/map-native`, `latentjam.experimentalMap`) is untouched and still off.

**Barnes–Hut t-SNE** follows van der Maaten (JMLR 2014): exact 60-nearest-neighbour search
(3 × perplexity), sparse symmetric affinities, and a quadtree rebuilt every iteration with
θ = 0.5. Everything else is shared with the exact path: PCA-50, perplexity 20, the exaggeration
and momentum schedule, learning rate, gains, start positions, recentering, warm start and
alignment. Memory is linear: about 30 MB of its own at 10,000 rows. Cancellation is polled at
every neighbour row, every bandwidth row and every optimizer iteration. PCA now also polls
every 256 rows; before this it polled once per pass, which left a 2.7 s gap at 10,000 rows.

**Sampled average linkage.** 3,000 tracks are chosen by a hash of their id, so the sample is
stable as a library grows. They seed micro-clusters, and every other track joins its nearest
seed. A track that is farther than the 0.75 cut from every seed stays alone, so it ends up
outside the regions, as in exact linkage. UPGMA then runs over the weighted micro-clusters. For
centered unit directions the average pairwise cosine distance between groups is exactly
`1 − S_a·S_b / (|a||b|)`, where S is the sum of a group's directions. So the merge distances are
exact, and the only approximation is that a micro-cluster is never split. The nearest seed is
found by shortlisting 32 seeds in a fixed 128-d random projection, then comparing them in full
dimension. Rows are reordered in place, not copied. When the work budget cannot hold this, the
previous k-means fallback still applies.

Other fixes found during measurement:

- `collectRegions` allocated a centroid for every hierarchy label, one per outside track
  included. Without rival evidence it now builds centroids only for kept regions, with identical
  output. This saved 22 MB of peak heap at 10,000 tracks.
- `LibraryLayout.compute` and `LibraryWorlds.discover` compared their full estimate, input
  included, against free heap measured *after* the input had been allocated. That counted the
  51 MiB input twice and would refuse a 10,000-track map that fits. They now compare
  `projectionWorkBytes` / `regionWorkBytes`. The pre-allocation check in `AppMapPage` still uses
  the full estimate.
- On Android, `availableMapWorkBytes` subtracted the whole native heap (ONNX models, decoders)
  from the Java budget. The Kotlin map paths allocate only Java arrays, and the Java limit alone
  bounds those. On the 10,000-track app below, the native charge (~81 MB during analysis) made the
  Map show "Not enough free memory" with Java headroom to spare. Native heap is now charged only
  when the experimental native backend is built in, since that backend allocates natively.

## Device measurements

`MapScaleDeviceTest` (new, opt-in) runs the production `LibraryLayout.compute` and
`AverageLinkageClustering.cluster` on a deterministic synthetic library: 8 families × 6 styles,
Zipf-like sizes, 15% scattered tracks, 1,344-d. It logs total and per-phase time, sampled peak
Java heap, the longest gap between cancellation polls and the latency after a cancel. No
music-app data is read.

**Device:** emulator `LJ-api30` (Android 11, arm64, 4 vCPU), booted `-read-only` on Apple
Silicon. `dalvik.vm.heapgrowthlimit` and `dalvik.vm.heapsize` were set to **256m**, the app's
real growth limit on the owner's phone. Every run logged `heap_limit_bytes=268435456`, so the
test APK's `largeHeap` gave no extra room. The owner's phone and LJ-demo were not used.
Background thread priority as in the app. A second emulator was analysing audio during the runs,
so timings carry some host contention.

### Time and memory

The peak is sampled used Java heap, input matrix included: 54 MB, or 51 MiB, at 10,000.

| Stage | Tracks | Path | Total | PCA | Affinities | Optimizer | Peak heap | Estimate |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Layout | 2,000 | exact | 35.1 s | 1.8 s | 0.9 s | 32.4 s | 31 MB | 35 MB |
| Layout | 3,000 | exact (before) | 75.9 s | 2.5 s | 2.0 s | 71.4 s | 55 MB | 61 MB |
| Layout | 3,000 | Barnes–Hut | **23.4 s** | 2.5 s | 0.7 s | 20.1 s | 29 MB | 34 MB |
| Layout | 5,000 | Barnes–Hut | 42.4 s | 4.4 s | 1.9 s | 36.2 s | 46 MB | 51 MB |
| Layout | 10,000 | Barnes–Hut | **91.1 s** | 8.6 s | 7.4 s | 75.0 s | **85 MB** | 94 MB |
| Regions | 3,000 | exact | 17.0 s | | | | 56 MB | 77 MB |
| Regions | 5,000 | sampled | 14.5 s | | | | 86 MB | 98 MB |
| Regions | 10,000 | sampled | **23.3 s** | | | | **117 MB** | 135 MB |

On the owner's S24 Ultra, exact layout at 3,000 took 44–52 s
([13 Oct](map-scaling-analysis-2026-10-13.md)), against 75.9 s here. The emulator is therefore
roughly 1.5× slower than that phone. Scaling by that ratio gives roughly 60 s for a 10,000-track
layout on the S24; a mid-range phone will be slower. This is an estimate, **not a phone
measurement**. A layout is computed once per library change, cached, and warm-started afterwards.

The same 10,000-track runs with **80 MB of extra Java heap held** (`mapBallastMb=80`, standing in
for the app's own heap) completed. Layout peaked at 170 MB and regions at 201 MB, against a
256 MB limit.

### Cancellation (10,000 tracks)

| Stage | Cancelled at | Phase reached | Stopped within |
| --- | ---: | --- | ---: |
| Layout | 3 s | PCA | 3.6 ms |
| Layout | 20 s | neighbour search / affinities | 66 ms |
| Layout | 60 s | optimizer | 6.1 ms |
| Regions | 3 s | centering / projection | 0.5 ms |
| Regions | 12 s | nearest-seed search | 0.6 ms |

The longest gap between two polls in a whole uncancelled run was 182 ms for layout and 27 ms for
regions at 10,000. The layout gap includes GC pauses.

## Quality against the exact paths

`MapScaleReplay` (new, opt-in host test) compares the new paths with the exact ones on the same
input. It used the private 1,104-track device dump (`MAP_DIAG_DIR`, 960-d audio + 384-d
metadata with production fusion, 20 playlists) and the synthetic fixture. Only aggregates are
reported here; the private vectors stay outside Git.

### Layout

Metrics: trustworthiness@15 and neighbour recall@15 are measured against the PCA-50 input;
playlist@15 is the share of 2D neighbours that share a playlist; class@15 is the share that
share a planted style. *exact, seed 2* is the exact algorithm with a different start, which shows
how much two exact runs already differ.

| Input | Method | trust@15 | recall@15 | playlist / class@15 | overlap with exact |
| --- | --- | ---: | ---: | ---: | ---: |
| library 1,104 | exact | 0.9746 | 0.6092 | 0.6300 | 1 |
| library 1,104 | exact, seed 2 | 0.9756 | 0.6075 | 0.6343 | 0.794 |
| library 1,104 | **Barnes–Hut** | **0.9797** | **0.6257** | **0.6382** | 0.800 |
| synthetic 1,000 | exact | 0.9743 | 0.6223 | 0.7483 | 1 |
| synthetic 1,000 | Barnes–Hut | 0.9850 | 0.6349 | 0.7483 | 0.734 (seed 2: 0.726) |
| synthetic 3,000 | exact | 0.9848 | 0.4545 | 0.8483 | 1 |
| synthetic 3,000 | Barnes–Hut | 0.9856 | 0.4590 | 0.8483 | 0.644 (seed 2: 0.681) |

Barnes–Hut is at or above exact on every metric. It differs from exact about as much as exact
differs from itself with another start. The differences are small and from one real library;
this is no claim of superiority. At 2,000 and below, nothing changes, so the owner's
1,107-track map stays on the exact path.

### Regions

ARI is computed against the exact hierarchy's raw regions (size ≥ 4, before semantic routing and
naming), with every outside track its own singleton. *exact on 90% subsets* shows how much exact
regions already move when 10% of the library is removed. *k-means fallback* is what libraries
above 3,000 tracks would otherwise get. A sample fraction of 0.3 matches production at 10,000
(3,000 of 10,000).

| Input | Exact regions / assigned | Sampled 0.5 ARI | Sampled 0.3 ARI | Exact on 90% subsets | k-means fallback |
| --- | --- | ---: | ---: | ---: | ---: |
| library 1,104 | 36 / 1,054 | 0.735 | 0.699 | 0.78–0.83 | 0.397 |
| synthetic 1,000 | 51 / 820 | 0.992 | 0.991 | 0.995–0.997 | 0.584 |
| synthetic 3,000 | 59 / 2,487 | 0.995 | 0.994 | 0.996–0.998 | 0.356 |

Synthetic purity stays 1.0. On the real library, sampling moves regions somewhat more than
dropping 10% of tracks does, but far less than the k-means fallback. Two variants were tried and
rejected:

- Joining tracks to sample clusters instead of to micro-clusters: ARI 0.59–0.60.
- Micro-clusters without the distance guard: scattered tracks contaminated regions, with
  synthetic purity dropping to 0.93.

A 64-d projection with 16 candidates scored 0.68; the chosen 128-d / 32 matched a full
nearest-seed search.

## Real app at 10,000 tracks

PENDING: filled in below once the in-app run on `LJ-big36` finishes.

## Limits and what was not measured

- Timings come from an emulator, not a phone, with some host contention. The phone figure above
  is a scaled estimate.
- Quality on real data comes from one 1,104-track library. No real library above 3,000 tracks
  was available, so the sampled path was compared with the exact one by forcing small samples.
- The memory gate still uses the 1/8-heap reserve and counts uncollected garbage as used, so on
  a 192 MB heap a 10,000-track library can still be refused or fall back to k-means regions while
  much else is resident. That is a refusal with an explanation, never a crash.
- For You's Sonic Journey session copies the full fused matrix (54 MB at 10,000). That is
  existing behaviour and not part of the Map paths measured here.
- The Barnes–Hut layout is a different picture from exact t-SNE. A 2,001–3,000-track library
  with a cached exact map keeps it until the library changes; the recompute then warm-starts
  from the stored positions.

## Reproduce

```sh
# Host quality replay (synthetic; add MAP_DIAG_DIR=<private dump> for the real library)
MAP_SCALE_REPLAY=1 MAP_SCALE_SIZES=1000,3000 ./gradlew :core:smart:testAndroidHostTest \
  --tests "*MapScaleReplay*" --rerun-tasks

# Device: an emulator you may change, never LJ-demo or a phone without permission
export ANDROID_SERIAL=emulator-5560
adb root && adb shell setprop dalvik.vm.heapgrowthlimit 256m && adb shell setprop dalvik.vm.heapsize 256m
adb shell stop && adb shell start
./gradlew :core:smart:assembleAndroidDeviceTest
adb install -r -t core/smart/build/outputs/apk/androidTest/smart-androidTest.apk
adb shell am instrument -w -r -e class io.github.nikitasud.latentjam.smart.cluster.MapScaleDeviceTest \
  -e mapScale 1 -e mapRows 10000 -e mapStage layout \
  io.github.nikitasud.latentjam.smart.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as io.github.nikitasud.latentjam.smart.test cat files/map-scale.log
```

Optional arguments: `-e mapStage regions|layout-bh`, `-e mapCancelAfterMs 3000`,
`-e mapBallastMb 80`.
