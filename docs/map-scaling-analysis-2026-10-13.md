# Map scaling analysis — 13 October 2026

Status: baseline device measurements and algorithm research completed; comparative device and
gesture measurements interrupted by loss of the ADB connection. No production optimization or
increase to the 3,000-track limit has shipped from this experiment.

Work is isolated on `codex/map-performance-0.9.1`, based on release commit `9525b7b2`.
The installed music app remains 0.9.0 / code 14. The original working directory and its uncommitted
changes were preserved. Only an isolated profiling APK was installed; no music-app storage,
playlists, media, history or analysis caches were cleared.

## Recommendation

Prototype **sparse-neighbor Barnes–Hut t-SNE**, retaining the existing fusion, PCA-50, perplexity 20,
cache, warm initialization and alignment. This is a candidate, not a validated replacement.
It addresses the quadratic force calculation and dense probability matrix together. Optimizing
only the force calculation leaves the large affinity allocation in place.

Keep UI rendering as a separate investigation. A faster layout algorithm can shorten a cache miss;
it does not by itself improve pinch/pan frame times on an already constructed map.

## Evidence from the connected phone

Samsung Galaxy S24 Ultra (SM-S928B), Snapdragon SM8650, Android 16 / API 36, arm64,
1080 × 2340. The actual library contained 1,107 tracks. At the initial snapshot the phone was
charging, battery 44%, battery sensor 31.0°C. These are initial conditions, not a sustained thermal
or battery study. Results on this flagship cannot establish performance on the correspondent's Honor.

The new opt-in `MapLayoutProfileDeviceTest` ran the production Kotlin reduction and exact t-SNE
sources in the independent smart test APK. Input: deterministic synthetic clustered vectors,
1,344 dimensions, not extracted music data. Three 180-row warmups preceded each set of measured
runs. Fixture creation was excluded, numerical work used Android background thread priority,
and each embedding ran all 1,000 iterations. Finite output and cancellation-check counts passed.

| Rows | Recorded runs | PCA | Affinities | Iterations | Total |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,107 | 3; component medians | 1.242 s | 1.669 s | 4.083 s | **6.985 s** |
| 3,000 | round 0 | 3.319 s | 12.430 s | 28.379 s | **44.128 s** |
| 3,000 | round 1 | 3.132 s | 12.208 s | 33.335 s | **48.674 s** |

Both instrumented invocations completed `OK (1 test)`, including all three requested runs at
3,000 rows. Only two 3,000-row stage records were retrieved before disconnection; the third is
not included or inferred. Their full invocation durations were 26.455 s and 152.262 s, including
warmups, fixture creation, GC and output persistence. The updated harness now also writes a log
file to avoid relying solely on logcat; that final change compiled but was not rerun on the device.

Raw retained measurements: [phone-exact-2026-10-13.csv](../tools/research/map_scaling/phone-exact-2026-10-13.csv).
At 1,107 rows approximately 82% of time is inside t-SNE rather than PCA. At 3,000 rows it is
approximately 93%. This supports changing the scaling of t-SNE before tuning PCA.

Sampled used Java heap peaked at 18.97 MiB / 86.95 MiB for these fixture sizes. The 20 ms sampler
can miss a peak and includes uncollected garbage. It is neither live retained memory nor total
process RSS. **The existing test manifest enables `largeHeap=true`, giving this process 512 MiB;
the music app does not enable it, and the device's normal growth limit is 256 MiB.** A successful
test run therefore does not establish the app's memory safety at larger sizes.

The largest observed optimizer-iteration interval was 5.46 ms at 1,107 rows and 61.19 ms in the
retained 3,000-row runs. This includes scheduling/GC effects; it is not UI frame time or a direct
cancel-latency benchmark. Cancellation is checked at each affinity row and optimizer iteration;
PCA checks at its subspace iterations. Any native replacement must preserve cooperative cancellation.

The installed application's map-loading snapshot was PSS 307,763 KiB, RSS 352,356 KiB,
SwapPSS 72,122 KiB and graphics 97,752 KiB. This is one whole-app snapshot, not a peak or a
measurement of layout-only memory. A SMART log separately reported 17 regions for 1,107 semantic
tracks in 1,073 ms; that is region discovery, not t-SNE. No reliable cold-open or cached-open
latency was captured. The earlier September ART harness used a different environment and cannot
be used with these timings to claim a release regression.

## Why raising the limit alone is insufficient

`Tsne.affinities` holds an `N × N` FloatArray, then creates the packed symmetric probability array.
That phase needs approximately `6N²` bytes just for those matrices. The optimizer's two packed
arrays need approximately `4N²` bytes and traverse every pair in every iteration.

| Tracks | Affinity matrices alone | Optimizer matrices alone | Original 1,344-d vectors |
| ---: | ---: | ---: | ---: |
| 1,107 | 7.01 MiB | 4.67 MiB | 5.68 MiB |
| 3,000 | 51.49 MiB | 34.32 MiB | 15.38 MiB |
| 4,643 | 123.34 MiB | 82.22 MiB | 23.80 MiB |
| 10,000 | 572.19 MiB | 381.43 MiB | 51.27 MiB |

These are array-size calculations, not measured peaks. Phase columns are not all simultaneously
live; delayed GC, reduction buffers, SMART models, library objects, images and UI add further cost.
The 4,643-row device test was not run after disconnection. The 10,000-row affinity matrices alone
exceed the normal app heap on this phone. Removing the guard is not a sufficient fix.

## Papers and implementations

SciSpace was used to find the research, followed by checking the papers and upstream source.
Catalog results include preprints; publication status and claims were checked at the original source.

| Approach | Primary evidence / repository | Fit for LatentJam |
| --- | --- | --- |
| Barnes–Hut t-SNE | van der Maaten, [JMLR 2014](https://jmlr.org/papers/v15/vandermaaten14a.html); [qdtsne](https://github.com/libscran/qdtsne) | First candidate: approximate distant-point forces with a tree and store sparse neighbors. The paper's `O(N log N)` claim concerns gradient computation; exact neighbor search can still become expensive in high dimensions. |
| FIt-SNE | Linderman et al., [paper](https://arxiv.org/abs/1712.09005), published in Nature Methods 2019; [source](https://github.com/KlugerLab/FIt-SNE) | FFT/interpolation acceleration is worth evaluating for much larger libraries. It adds FFT dependencies and integration work; the paper does not establish a speed advantage on our phone at 4,643 tracks. |
| openTSNE and incremental insertion | Poličar et al., [JSS 2024](https://www.jstatsoft.org/article/view/v109i03); [source](https://github.com/pavlin-policar/openTSNE) | Useful desktop reference and a design for inserting new tracks into an existing embedding. Python/Cython is not a direct KMP dependency. Keeping a reference map fixed can reduce movement but requires detecting drift and deciding when to rebuild. |
| UMAP | McInnes et al., [original paper](https://arxiv.org/abs/1802.03426); C++ [umappp](https://github.com/libscran/umappp) | A useful comparison candidate. The earlier real-library study favored t-SNE for this app; speed alone does not justify switching. |

Upstream `qdtsne` is a small header-only C++ candidate and builds with Android NDK 28.2 here.
Its top-level license is MIT, but `SPTree.hpp` and `Status.hpp` retain BSD-4-Clause conditions,
including an advertising acknowledgement. It must not be described as entirely MIT.
The original `lvdmaaten/bhtsne` also carries that condition. `umappp` advertises BSD-2-Clause,
and openTSNE BSD-3-Clause. Full dependency notices need checking before shipping a selected engine.
No third-party implementation was added to the app by this experiment.

The existing [873-track study](map-page.md#3-layout--what-near-means-on-screen) measured
playlist@15 / artist@15 / trustworthiness of 0.368 / 0.458 / 0.965 for t-SNE, versus
0.354 / 0.437 / 0.961 for its tested UMAP configuration. Its paired playlist comparison favored
t-SNE. This is evidence against a blind switch, not proof that every UMAP implementation or
parameter choice will perform worse.

## Prototype and preliminary quality

Added [research tools](../tools/research/map_scaling/README.md), outside the production dependency
graph. The probe uses pinned qdtsne/dependency commits, one thread, Float32, 60 nearest neighbors,
perplexity 20, 1,000 iterations, theta 0.5 or 0.3, the app's cold seed and matched learning schedule.
It uses the **exact PCA outputs captured from the phone baseline** as input, removing differences
in reduction from this comparison. Sparse affinities, entropy solver, float accumulation and tree
forces still differ from the Kotlin implementation. This is not a controlled test of theta alone.

The Android arm64 executable compiled successfully. It has **not run on the phone**. A macOS host
execution verified finite outputs and repeatability: two theta-0.5 executions at each size produced
byte-identical outputs. Host timings are retained as host diagnostics only; no phone speedup or
RSS reduction is claimed from comparing a macOS C++ process with an Android ART process.

Exact Kotlin outputs came from the phone; candidate outputs below came from the host:

| Rows | Method | Trustworthiness@15 | Neighbor recall@15 | Neighbor overlap with exact map |
| ---: | --- | ---: | ---: | ---: |
| 1,107 | Exact | 0.98056 | 0.39229 | 1.00000 |
| 1,107 | BH theta 0.5 | 0.98124 | 0.40355 | 0.60367 |
| 1,107 | BH theta 0.3 | 0.98136 | 0.40500 | 0.59681 |
| 3,000 | Exact | 0.97857 | 0.27529 | 1.00000 |
| 3,000 | BH theta 0.5 | 0.97907 | 0.28182 | 0.47784 |
| 3,000 | BH theta 0.3 | 0.97942 | 0.28371 | 0.44867 |

These metrics use all synthetic points and their reduced 50-d input as reference. They do not
measure PCA loss relative to the original 1,344-d space, musical relevance, or confidence intervals
across libraries. The tiny quality differences are not evidence that BH is musically better.
Similarity to the original high-dimensional neighborhood remains good on this fixture, while the
specific 2D neighborhoods change substantially. A cache-version change and stability validation
are necessary if the implementation changes. Cold-map overlap is not a measurement of warm-start
stability after adding tracks.

## UI work and release gates still outstanding

Current `MapTab` already uses a spatial index to avoid offscreen dots, cached dot visuals/text,
and reads the viewport once per draw. It still constructs an outline path and label placements
and issues individual visible-circle draws. These are profiling candidates, not established
causes of the remaining jank. The September gesture report did not establish an overall FPS gain.

Before selecting and integrating an engine:

1. Restore ADB, run the same numerical inputs through the candidate on this phone, then compare
   1,107 / 3,000 / 4,643 tracks sequentially with repeats and thermal context. Capture peak app
   memory under the normal heap limit; native RSS and sampled Java heap are different metrics.
2. Replay representative real music vectors and curated playlists, including duplicates, mixed
   genres, small libraries, missing modalities, and additions/removals. Define acceptable quality
   loss before inspecting candidate results; include paired comparisons and warm-map stability.
3. Measure cached-map pinch/pan separately with no numerical workload running: frame-time
   distributions, jank, trace sections and allocations. Then test targeted rendering changes.
4. Verify cancellation at all stages, lifecycle behavior, cached-map fallback, deterministic ordering,
   invalid/empty input and Android/iOS integration. A JNI-only Android solution would not cover iOS.
5. Change the cache algorithm version and size guard only after the selected engine passes these
   checks. Keep prior map alignment and avoid rebuilding the whole map for every visit.

Verified in this branch: final profiling APK assembly; host and Android probe compilation;
host finite/deterministic runs; independent metric sanity checks; `git diff --check`.
The numerical test adds no production behavior and is opt-in; a full application test suite was
not rerun for these research-only additions.
