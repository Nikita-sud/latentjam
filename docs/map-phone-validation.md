# Physical Android map validation — 14 September 2026

Tested an in-place release update on a Samsung Galaxy S24 Ultra (SM-S928B), Android 16 / API 36,
1080 × 2340, normal font scale. The connected library contained 1,079 tracks. Existing files,
playlists, listening history, page preferences and paused playback were retained; no application
storage or analysis cache was cleared. All screenshots, UI dumps and traces from this device stayed
in temporary local files. Repository demonstration media still uses fictional tracks only.

## Issues reproduced and fixed

1. **Map gestures could drag the parent page.** A 12-second Android trace showed neighbouring-page
   composition and prefetch during pinch/pan. The longest `AndroidOwner:measureAndLayout` section
   took 58.6 ms, and there were 102 lazy-prefetch trace sections. The content pager now leaves
   gestures to the Map while that tab is settled. Using the settled tab allows an incoming page
   swipe to complete. Page navigation remains available through tab taps in the top carousel.
   The repeated trace after this fix contained neither measure/layout sections nor lazy-prefetch
   sections during the gesture workload.
2. **Statistics called a subset the whole library.** The unplayed headline described 902 tracks
   assigned to regions as the library size, although the Map correctly drew all 1,079 tracks.
   Plays and unplayed headlines now explicitly describe mapped regions in all 18 locales. Their
   calculations and neutral treatment of unassigned dots are unchanged.
3. Viewport snapshot state is now read once per draw, instead of three times for every visible dot.
   This removes redundant observation work; no independent speedup is attributed to this small change.

## Calculation on the phone

The old and updated `LibraryLayout.compute` implementations ran in the same standalone Android
ART harness on the phone. Input was deterministic synthetic data: 1,079 normalized vectors of
1,344 dimensions. Three 180-vector warmups preceded three paired cold computations; pair order
alternated. Fixture construction was excluded from the timings. This uses the production numerical
sources, but is not a timing of opening the app or its library on the Map.

| Pair | Previous | Updated | Coordinates |
| ---: | ---: | ---: | --- |
| 1 | 5,329 ms | 3,241 ms | Bit-identical |
| 2 | 5,224 ms | 3,235 ms | Bit-identical |
| 3 | 5,107 ms | 3,256 ms | Bit-identical |
| Median | **5,224 ms** | **3,241 ms** | **38% less time** |

This excludes indexing, clustering, storage, history reads and UI rendering. No personal library
vectors or media were used in this harness.

## UI frame measurements

Each sample contained three repeated 1×→4×→1× pinch cycles with a 100-pixel pan and return.
Coordinates, input timing and the selected largest region were held constant. Counters were reset
before each sample; three samples were collected per build, with no benchmark computation running
at the same time. Android reported frame intervals of approximately 16.67 ms.

These measurements preceded the naming follow-up below. The table contains the median of each
metric across the three samples, from `dumpsys gfxinfo`:

| Build | Frames per sample | p50 | p95 | p99 | Janky frames |
| --- | ---: | ---: | ---: | ---: | ---: |
| Previously installed release | 226 | 11 ms | 21 ms | 38 ms | 11.89% |
| Initial map update, before phone fixes | 298 | 10 ms | 23 ms | 73 ms | 13.09% |
| Gesture-fix update | 291 | 14 ms | 22 ms | 32 ms | 22.68% |

**This does not establish an overall FPS improvement.** The gesture fix removes the long
neighbouring-page layout stalls, but changes which content handles the input and produces more Map
frames. Routine drawing still misses frame deadlines. The final per-run jank rates were 37.80%,
22.68% and 18.56%; a steady 60 fps claim would be incorrect. Profiling the remaining draw work is
still warranted. System load, runtime warmup and a charging phone also limit these short samples.

Final post-workload app PSS was approximately 219 MiB. This is a snapshot, not peak memory; the
long-running baseline had substantial swapped pages, so a before/after app-memory reduction is
not claimed. Battery drain and sustained thermal behaviour were not measured.

## Naming outcome on this library

The first update left all five neutral discovery regions unchanged. A follow-up audit, with the
user's explicit permission, returned only aggregate genre/year/language distributions and group
shares. It established that missing tags were not the main cause: nearly every member of these
regions had genre metadata. Single-genre thresholds and missing compound-genre aliases discarded
useful mixed-style evidence.

The final rules recognize Eurodance, Europop, Hi-NRG and trance families, and admit a two-style
name only when each side has meaningful independent support and the union covers at least two
thirds of the coherent region. Blends preserve their minority members; tests guard against counting
overlapping tags twice, tiny secondary styles and unsupported cover selection. Explicit novelty
and sound-effect genre tags now participate in content routing as well.

The phone's aggregate result changed from **17 regions / five neutral names** to **18 regions /
one neutral name**. All 1,079 tracks remain on the Map, with 894 assigned to regions.
Verified new blends include Pop / Rock, Dance / Pop, and Classical / Production
music. The remaining neutral region does not clear the single-style or two-style evidence gates;
a specific name is deliberately not invented. The additional region comes from content routing,
not from increasing the clustering target.

Native checks passed for all four lenses with stable plot bounds, the region selector including
its final item, opening a 92-track region collection and returning with selection retained, and
navigation through tab taps. Top-carousel dragging was not a supported interaction before this
change and is not claimed as a passing check. Playback remained paused throughout.

## Validation and build identity

The final Android release build, iOS simulator compilation and all 1,001 Android host tests
passed. String keys and format argument types match across all 18 locales. `git diff --check`
passes. The earlier 989-test run is recorded in the linked validation; the naming follow-up adds 12 regression tests.

Installed arm64 release APK SHA-256:

```text
5f4255b23ca919ff0d298c58b1f33f3e0f23459174fb88b5de3f2da59729df4d
```

The package remains `io.github.nikitasud.latentjam.kmp`, version 0.3.0 / code 3, signed for in-place
updates. Installation success and the resulting UI were checked rather than relying on the
unchanged marketing version to identify the build. The installed APK hash matched the local
release, its debuggable flag was absent, and the final aggregate UI check confirmed 18 regions
with one neutral label. No fatal exception was recorded for the final app process. Temporary diagnostic
source and device helpers were removed.

The final multi-touch replay on the naming release was inconclusive: the temporary input injector
reported `Injection failed` and terminated. The app itself remained responsive and recorded no
fatal exception. The earlier gesture-fix release passed the repeated multi-touch workload and
trace check; the last attempted replay is not counted as an additional passing gesture test.
