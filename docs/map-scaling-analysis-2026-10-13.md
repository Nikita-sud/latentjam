# Map scaling analysis — 13 October 2026

Status: numerical comparisons and cached-map gesture measurements completed after reconnecting
the phone. The Barnes–Hut engine remains a research prototype; the production algorithm, fusion
weights and 3,000-track limit are unchanged. Requested outline/fade changes are built in this branch.

Work is isolated on `codex/map-performance-0.9.1`, based on release commit `9525b7b2`.
The installed music app remains 0.9.0 / code 14. The original working directory and its uncommitted
changes were preserved. An isolated profiling APK and a same-signer read-only exporter were used; no music-app storage,
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

Both initial invocations completed `OK (1 test)`, including all three requested runs at 3,000 rows.
Only two initial 3,000-row stage records were retrieved; the third is not included or inferred.
The final harness writes stage logs to files and was rerun successfully after reconnection:

| Rows | Reconnected exact runs | PCA | Affinities | Iterations | Total |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,107 | 3; component medians | 1.242 s | 1.677 s | 4.066 s | **6.984 s** |
| 3,000 | 1 | 3.710 s | 14.203 s | 34.588 s | **52.501 s** |
| 4,643 | 1 | 5.994 s | 39.807 s | 93.312 s | **139.113 s** |

These are isolated test-process timings, not app open latency. During numerical runs the phone
was also used for video playback, so foreground load and scheduling were not controlled. Native
and exact runs were sequential but not randomized or interleaved. The range is meaningful;
precise speedup ratios and causal attribution to the algorithm alone are not justified.

Initial measurements: [phone-exact-2026-10-13.csv](../tools/research/map_scaling/phone-exact-2026-10-13.csv).
Reconnected measurements: [phone-comparison-2026-10-13.json](../tools/research/map_scaling/phone-comparison-2026-10-13.json).
At 1,107 rows approximately 82% of time is inside t-SNE rather than PCA. At 3,000 rows it is
approximately 93%. This supports changing the scaling of t-SNE before tuning PCA.

Sampled used Java heap peaked at 18.97 MiB / 86.95 MiB for these fixture sizes. The 20 ms sampler
can miss a peak and includes uncollected garbage. It is neither live retained memory nor total
process RSS. **The existing test manifest enables `largeHeap=true`, giving this process 512 MiB;
the music app does not enable it, and the device's normal growth limit is 256 MiB.** A successful
test run therefore does not establish the app's memory safety at larger sizes.

The sampled used Java heap reached 191.44 MiB at 4,643 rows in the 512 MiB test process.
The largest observed optimizer-iteration interval was 5.46 ms at 1,107 rows, 61.19 ms at 3,000,
and 190.66 ms at 4,643. This includes scheduling/GC effects; it is not UI frame time or a direct
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
The 4,643-row test succeeded only in the separate large-heap process. The 10,000-row affinity
matrices alone exceed the normal app heap on this phone. Removing the guard is not a sufficient fix.

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
perplexity 20, 1,000 iterations, theta 0.5 or 0.3, and the app's cold seed. The initial host probe
used eta 200; this did **not** match the app's effective gradient scale. Kotlin multiplies its
force by four, whereas qdtsne does not. The corrected phone comparison uses native **eta 800**.
Eta 200 remains a separately labeled sensitivity run. Adaptive gains at zero velocity still
differ, so the corrected value does not make the two optimizers identical.
It uses the **exact PCA outputs captured from the phone baseline** as input, removing differences
in reduction from this comparison. Sparse affinities, entropy solver, float accumulation and tree
forces still differ from the Kotlin implementation. This is not a controlled test of theta alone.

The Android arm64 executable ran successfully on the phone. All outputs were finite, and all three
4,643-row candidate outputs were byte-identical. It uses native nice priority 10; ART's background
thread also has Android scheduling-group behavior. Comparisons therefore include runtime,
compiler and scheduler differences. Native RSS is not interchangeable with sampled Java heap.

| Rows | BH theta 0.5, eta 800 runs | BH stage median | Observed range | PCA excluded |
| ---: | ---: | ---: | ---: | --- |
| 1,107 | 3 | **2.283 s** | 1.836–3.720 s | Yes |
| 3,000 | 3 | **7.683 s** | 7.625–10.410 s | Yes |
| 4,643 | 3 | **16.880 s** | 13.866–18.540 s | Yes |

At 4,643 rows the candidate process peaked at 13.82–13.91 MiB RSS. This excludes the app, PCA,
models and UI and cannot support a percentage memory-reduction claim for the app. Adding the
measured 5.994 s exact PCA to the 16.880 s candidate median gives a roughly 23 s component
estimate, **not an observed integrated pipeline**.

Phone synthetic quality against the same reduced 50-dimensional inputs:

| Rows | Exact trust@15 | BH trust@15 | Exact recall@15 | BH recall@15 | BH/exact neighborhood overlap |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,107 | 0.98056 | 0.98107 | 0.39229 | 0.40054 | 0.63674 |
| 3,000 | 0.97857 | 0.97933 | 0.27529 | 0.28311 | 0.45718 |
| 4,643 | 0.97779 | 0.97878 | 0.23890 | 0.24757 | 0.38764 |

Historical host sensitivity results below used **eta 200**, not the corrected phone configuration.
Exact Kotlin outputs came from the phone; candidate outputs in this table came from the host:

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

## Region meaning and current-library audit

Regions are discovered in the high-dimensional fused space, not by drawing borders around 2D
dots. The default input uses 25% audio / 75% metadata squared-distance weighting. Clustering,
confidence trimming, genre evidence, semantic routing and naming can all change final membership.
The recommended cluster count is a size heuristic (16 for 1,107 tracks), not an inferred count
of natural genres. Routing may split it into more named regions.

The current phone UI reported **17 regions, 974 assigned tracks out of 1,107**. The remaining
**133 tracks were unassigned**; that number is not the size of the selected region. A title such
as A + B describes a mixture and does not promise that every member has both tags. Playlist and
album renaming must also be considered before interpreting a label's evidence.

An initial 873-track historical replay omitted semantic head scores and therefore cannot verify
today's visible regions. Its 33.66% audio-neighbor and 81.12% metadata-neighbor agreement measured
which modality the grouping followed, **not musical accuracy**. Likewise silhouette, genre purity
and artist agreement cannot replace listening or independent user-curated relevance judgments;
tags/artist names already influence the input. The fresh phone snapshot and 25/75, 50/50, 65/35
weight comparison supersede that replay for current-library conclusions.

### Fresh snapshot and weight comparison

The user-authorized 13 October snapshot was downloaded through a separately signed Android
instrumentation package targeting the installed app. The app's signing certificate matched;
the main APK was not replaced, and no root, uninstall, clear-data or backup-flag change was used.
The ZIP and nine exported data files passed SHA-256/length checks. Original index files were
stable in size and modification time during copying. Raw files and replay inputs remain outside Git.

MediaStore returned 1,110 music rows; applying the three hidden entries reproduced the visible
**1,107-track library**. All 1,107 have finite 960-dimensional audio vectors, while 1,106 have
384-dimensional metadata vectors. Production retains the remaining track through its normalized
audio-only fallback. Its missing text row is not explained by the two SMART exclusions or a stored
failure entry; the cause cannot be established from this snapshot alone.

The replay uses current playlist membership, valid embedded-tag enrichment, production fusion,
clustering and album/playlist naming. It includes all 1,107 semantic scores, inferred locally from
the unchanged shipped ONNX head, rather than pretending the runtime's in-memory semantic cache
was exported. These production clustering sources are unchanged from tag `v0.9.0`. The 25/75
replay reproduced the observed **17 regions / 974 assigned / 133 unassigned**. Matching totals
supports the reconstruction but does not verify every member against the app's live in-memory set.

| Audio / metadata | Regions | Assigned / 1,107 | Coverage | Same-playlist precision among 15 fused-space neighbors |
| --- | ---: | ---: | ---: | ---: |
| 25 / 75 (current) | 17 | 974 | 88.0% | 62.68% |
| 50 / 50 | 18 | 959 | 86.6% | 62.94% |
| 65 / 35 | 18 | 872 | 78.8% | 61.16% |

The playlist metric uses 20 eligible playlists, 763 memberships and 469 unique tracks; 22 playlists
exist in storage, including ineligible/too-small groups. It measures neighbors in the full fused
space, **not the 2D map**. The 50/50 change is +0.26 percentage points; its exploratory paired
playlist-bootstrap 95% interval is [-0.29, +0.87] points. Overlapping groups, varied playlist intent,
one library and one clustering seed limit interpretation. No clear relevance improvement is established.
Pair retention within playlist regions was 43.5% / 45.9% / 23.7%; that measure also favors larger
regions and is supporting evidence only. Higher audio weight changes region coverage as well as
membership, so agreement among assigned tracks alone must not be compared without its denominator.

The selected **Game ost + Production music** region contains **38 tracks**, not 133. Ten have the
game-OST family and nine production music: their union supports the label for **19/38 (50%)**.
The other half carries different literal genre families, but **nine of those have related
soundtrack/video-game-music/video-game-cover labels**. A broader manual tag reading thus finds
28/38 thematically related; this is an exploratory synonym check, not measured listening accuracy.
It would be wrong to label all 19 literal mismatches as irrelevant tracks. Genre vocabulary and
label wording are part of the problem, alongside the remaining mixed styles.
Another mixed label, Rap + Dance, reaches the
same threshold, 34/68. This is allowed by the current blend-naming rule and shows why a readable
title is not a guarantee of a homogeneous listening set. Overall, the current configuration has
no generic names, but that alone is not evidence of better regions.

**Decision:** retain 25/75 for 0.9.1. A listening comparison of 50/50 is more justified than adopting
65/35 globally. The first product-quality target is more honest mixed-region naming and visibility
of weak membership, rather than increasing audio weight and assuming that all regions improve.
No production weight or naming threshold was changed by this experiment. Aggregate evidence is
saved in [current-library-regions-2026-10-13.json](../tools/research/map_scaling/current-library-regions-2026-10-13.json).

The old real-vector projection replay remains only a preliminary engine check: exact playlist@15
0.34790 versus BH theta 0.5/eta 800 0.34939. Its exploratory paired playlist delta interval
[-0.00890, +0.01425] included zero. Overlapping playlists, one historical library and no predefined
equivalence margin prevent claims of equivalence or superiority. The earlier published 873-track
study used a different PCA/initialization/iteration configuration and is not a release baseline.

### MOSS-Music pilot on 15 available regions

The user-approved diagnostic ran the actual [MOSS-Music](https://github.com/OpenMOSS/MOSS-Music)
8B-Instruct model on a dedicated A40, using the pinned source/weights and official Transformers
route documented in the [research runner](../tools/research/map_scaling/moss/README.md).
It did not train a model, change production embeddings or send metadata to the captioner.

The sample covers **75 tracks from 15 regions**: two central, two boundary and one seeded random
available track per region. These regions contain 794 of the 974 assigned tracks; two regions
with 180 members lacked strong local audio matches and were excluded. Only five sampled members
were evaluated from each covered region. Local copies match normalized basename, exact byte size
and duration, but phone audio hashes were unavailable, so identity is not cryptographically proven.

Seventy-one tracks supplied two fixed-position excerpts; four short tracks supplied one, giving
**146 real clips**. The blind manifest additionally contained silence and two exact duplicates.
All WAVs were mono 16 kHz with metadata stripped; only opaque clip IDs and audio reached MOSS.
All **149** answers completed with valid caption JSON and no token-limit truncation. Silence
produced no claimed instruments, vocals, rhythm or energy, though its broad label was
"instrumental". Both duplicates produced exactly equal descriptions. These narrow controls
establish repeatability and response to zero input, not musical accuracy.

The model claimed stereo in **111/146 real clips**, despite mono inputs, and supplied unverified
numerical BPM in 130/146. BPM, stereo/width and speculative origin were excluded from conclusions.
An isolated agent reviewed all 146 caption texts against a seven-field acoustic-description
rubric without seeing identities or region memberships. This was AI text review, not human
listening. Conservative missing-evidence handling left only **503/2775** track pairs evaluable;
matched hard controls covered 23 comparisons in eight MUSIC regions, and random controls covered
16 in nine. That coverage cannot support a single score for all 15 regions or a reliable choice
between 25/75 and 50/50. Secondary lexical similarity favored within-region captions in most
sampled MUSIC regions, but it measures wording and inherits caption errors.

Qualitative review of the frozen descriptions finds:

- One small Rock region has a consistently forceful guitar/bass/drum character at both its center
  and boundaries. Another small Rock region has a more melodic vocal-guitar character. A third,
  larger Rock region includes quieter piano-led and ballad-like excerpts. Identical labels hide
  these potentially useful distinctions.
- Synth-pop has a clear electronic center; Dance / Pop and Disco also have recurring instrumental
  and vocal patterns in this sample. Individual tracks can change arrangement between excerpts,
  and some apparent changes may be model errors.
- Rap + Dance is less convincing: broad bass/drums/synth descriptions connect substantially
  different vocal and instrumental examples. One extreme discrepancy occurs between two excerpts
  of a single track, so blaming clustering alone would be unwarranted.
- Game ost + Production music has an orchestral/piano center, with vocal funk, quiet piano and
  electronic/guitar material at its edges. This supports a broad thematic collection more than a
  homogeneous listening set. Soundtracks can legitimately have different arrangements. The 50/50
  variant drops a quiet piano example, which is not by itself an improvement.
- The two Pop / Rock labels describe broad, partly different mixtures. NOVELTY is a content
  category; acoustic uniformity does not establish whether something is a meme.

A separate text-only MOSS pass compared 15 anonymous actual groups with 15 controls. MUSIC used
the same 70 tracks shuffled within excerpt-count strata; NOVELTY had a separate order control.
Only instruments, vocals and energy were retained. The model sometimes treated unknown vocals
as absent and contradicted explicit rap descriptions. Review found unsupported or contradicted claims in 20/30 responses
(19 in main claims, one only in an uncertainty statement), nine uninformative responses and
one supported control response. Normalized means were 1.43/4 for actual MUSIC groups and 1.50/4
for shuffled controls, with median 2 for both; these scores cannot establish good or bad regions
because the judge failed evidence checks. Five exact numeric strings were transparently converted
to integers; original responses were never rewritten. The
[aggregate evidence](../tools/research/map_scaling/moss/moss-pilot-2026-10-13.json) preserves
strict and normalized summaries, while all private answers and audit failures remain archived.

**Decision remains 25/75 for 0.9.1.** Use these results to prioritize honest region names and a
small listening review of questionable boundaries. Neither automatic renaming from five samples
nor a global weight change is justified. All private audio, mappings, outputs and review notes are
retained outside Git. The pod was deleted after download and SHA-256 checks; the deletion returned
HTTP 204 and a subsequent read returned 404. Approximate compute charge from elapsed time was
$0.35 at $0.59/hour, excluding any storage/other fees; this is not a provider invoice.


## Cached-map gestures and requested UI changes

`MapTab` already uses a spatial index to avoid offscreen dots, cached dot visuals/text, and reads
the viewport once per draw. After the user closed floating video and left the phone idle, three
two-finger zoom/pan runs exercised the installed 0.9.0 map without a numerical workload:

| Run | Frames | Janky frames | p50 | p95 | p99 | High-input-latency count |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 756 | 2 (0.26%) | 8 ms | 12 ms | 14 ms | 76 |
| 1 | 756 | 5 (0.66%) | 8 ms | 12 ms | 15 ms | 216 |
| 2 | 756 | 3 (0.40%) | 9 ms | 12 ms | 15 ms | 122 |

GPU p99 was 3 ms. These runs show that constructing a new map and interacting with a cached map
are different bottlenecks. They do not prove low touch latency: synchronous gesture injection was
itself often late, and the trace showed queued late frames. Perfetto's `BufferStuffing` represents
latency even when presentation remains regular ([FrameTimeline documentation](https://github.com/google/perfetto/blob/main/docs/data-sources/frametimeline.md)).

The first 32 MiB trace wrapped, discarding two chunks and process-start metadata. Retained slices
included 727 Compose owner draws, roughly 0.92 ms average / 4.54 ms maximum, but this incomplete
trace cannot support a whole-run allocation or input-latency conclusion. The reusable
[gesture helper and 64 MiB configuration](../tools/research/map_scaling/ui/README.md) preserve
the procedure and checks needed for another capture.

This branch removes the selected-region convex-hull fill/stroke while retaining dots, labels,
selection and gestures. An outline can enclose unrelated points after projection and suggests
a stronger geometric boundary than the clustering supports. It also moves bottom clearance
inside the scrolling content: the canvas can now continue beneath the existing player fade,
instead of ending at the black strip. The portrait page uses the shared top fade when scrolled.
Fullscreen safe insets and landscape clearance are retained.

The UI changes passed Android release assembly, strict release DEX checks and iOS simulator
compilation. They have **not yet been installed or visually checked on the phone**; the user
requested the phone back after the fresh export. No before/after gesture performance gain is claimed.

## Integration gates still outstanding

Before selecting and integrating an engine:

1. Repeat the engine comparison with foreground load controlled and baseline/candidate order
   alternated. Capture integrated peak app memory under the normal heap limit; native RSS and
   sampled Java heap are different metrics. Existing same-phone prototype timings establish
   feasibility, not an integrated release speedup.
2. Replay representative real music vectors and curated playlists, including duplicates, mixed
   genres, small libraries, missing modalities, and additions/removals. Define acceptable quality
   loss before inspecting candidate results; include paired comparisons and warm-map stability.
3. Install and visually check the requested UI changes; repeat cached-map pinch/pan with a complete
   trace, including input latency and allocations. Do not optimize individual draw calls on the
   assumption that they caused the cold-layout delay.
4. Verify cancellation at all stages, lifecycle behavior, cached-map fallback, deterministic ordering,
   invalid/empty input and Android/iOS integration. A JNI-only Android solution would not cover iOS.
5. Change the cache algorithm version and size guard only after the selected engine passes these
   checks. Keep prior map alignment and avoid rebuilding the whole map for every visit.

Verified in this branch: final profiling APK assembly and device runs; host and Android probe
compilation/execution; finite/deterministic checks; metric sanity checks; gesture-helper assembly;
Android release/DEX checks and iOS simulator compilation for the UI changes. The numerical test
adds no production behavior and is opt-in. A full unrelated application test suite was not rerun.
