# Map improvements — 14 September 2026

Map remains optional in Settings → Pages. This update improves calculation cost, supported region
names, and the actions available from each region.

## Region discovery and naming

- Automatic cluster count now respects the usable indexed population and the minimum four tracks
  per region. A sixteen-track library requests at most four clusters rather than eight. Missing,
  invalid, or duplicated vectors cannot inflate that count. Large-library tuning and explicit-k
  experiment overrides are unchanged.
- A genre, artist, semantic genre, or decade needs at least 60% support across the entire candidate
  region, plus enough admitted tracks. A missing or contradictory tag on the original medoid no
  longer vetoes the supported majority: the closest supporting member becomes the representative.
- A narrow subtype or decade that leaves too few tracks falls back to the supported broader genre.
  A genre/decade intersection must itself clear the whole-region threshold; a majority of a
  majority is insufficient.
- Shared original release decade is an additional fallback when more specific evidence is absent.
  Edition year is used when original year is unavailable. Artist comparisons normalize case and
  whitespace and ignore unknown/compilation placeholders.
- Playlist and album naming still uses the existing containment rules. Neutral names remain when
  there is insufficient evidence, rather than claiming an unsupported musical theme.

The Android fixture reproduced the small-library problem with sixteen generated, tagged tracks:
four visible islands were previously presented as one generic fallback. The repaired build shows
four four-track regions, named after their fictional albums: Night Current, Golden Hours,
Soft Geometry, and Quiet Orbit.

## Rendering and interactions

- The region menu exposes every region with its name and track count. Labels can be tapped, avoid
  overlapping one another, and prioritize the selected region. More labels fit as the user zooms.
  Repeated names receive stable suffixes, including case/whitespace variants, so region choices
  remain distinguishable visually and with a screen reader.
- Pinch zoom stays anchored under the fingers. Reset sits outside the plot so it cannot cover a
  label or intercept its tap.
- “Show tracks” opens the region as a collection, including track selection and playlist actions.
  Returning preserves the selected region. Playback resolves current visible descriptors.
- The unplayed view provides “Play unheard,” based on saved listening history and rechecked when
  pressed. Region and SMART playback identify the displayed region in the player source label.
  This filter is available after the first recorded play when unplayed tracks remain. Small
  regions use an honest total-only headline instead of requiring a darkest-region comparison.
- A cached spatial index limits drawing work to visible cells while preserving original paint
  order and circle-edge coverage. In deterministic 6× zoom fixtures, 1,000 dots require 50
  candidates and 4,000 require 250 before exact clipping. These are work counts, not FPS claims.

## Calculation and memory

t-SNE stores symmetric matrices in packed form and evaluates each pair's force once. Affinity
preparation reuses the distance matrix and avoids repeated probability writes. PCA stops promptly
after cancellation. The layout method, iteration count, cache version, and 3,000-track limit remain
unchanged.

Paired full `LibraryLayout.compute` measurements on the development JVM, using deterministic
1,344-dimensional synthetic vectors and three cold samples per size:

| Tracks | Previous median | Updated median |
| ---: | ---: | ---: |
| 400 | 687 ms | 400 ms |
| 873 | 3,039 ms | 1,377 ms |
| 2,400 | 15,125 ms | 8,255 ms |

All nine cold layouts and three warm layouts were byte-identical to the pre-change implementation.
Host contention affected smaller samples; the 2,400-track reduction was consistently around 45%.
This measures layout calculation, excluding indexing, clustering, disk I/O, and rendering. It is
not a page-opening or physical-phone timing claim.

Quadratic numeric storage is halved. At 3,000 tracks, optimizer matrices require approximately
36 MB instead of 72 MB; peak affinity-preparation numeric storage is approximately 54 MB instead of
108 MB. These values exclude vectors, PCA, runtime overhead, and the rest of the app.

Warm Map/For You visits cache coverage by the engine's existing index revision and an owned copy of
the requested IDs. Reopening an unchanged population does not copy or hash embedding rows. Matrix
construction primes this small identity cache without retaining the consumed matrix. Tests cover
population changes, audio replacement, metadata promotion/retagging, clearing analysis, and release.

## Reproduce validation

Final validation: **989 Android host tests passed**, zero failures, errors, or skipped tests.
Compared with the preceding debugging baseline, there are 42 additional passing test entries,
including the opt-in benchmark entry. Android assembly and iOS simulator compilation pass; Android
lint has zero errors and four existing platform/API warnings. Resource keys and format placeholders
match across all 18 locales. `git diff --check` passes.

Native verification passed in Russian at normal and 130% text size:

- Four named four-track regions, and selection of a different region through the menu.
- Tapping a painted label changes the selected region.
- “Show tracks” opens exactly the region's members; Back preserves the selection.
- Two-finger zoom around a visible region preserves its anchor; Reset restores the overview.
- The unplayed filter remains available for four-track regions and uses a total-only headline.
- “Play unheard” queues exactly Open Skies and Goodnight, Satellite, the two synthetic tracks with
  no recorded plays. The player identifies Quiet Orbit as the queue source.
- No fatal exception in the tested app process.

The [captured map](media/map.png) uses only fictional media.

Using JDK 21:

```sh
./gradlew testAndroidHostTest :androidApp:assembleDebug \
  :composeApp:compileKotlinIosSimulatorArm64 :androidApp:lintDebug --offline
```

Opt-in timing harness (diagnostic output, no wall-clock pass/fail threshold):

```sh
MAP_LAYOUT_BENCHMARK=1 ./gradlew :core:smart:testAndroidHostTest --offline \
  --tests '*LayoutPerformanceReplay*' --rerun-tasks
```

The native checks use the existing `tools/readme/generate_demo_library.py` and
`tools/readme/seed_demo_history.py` in a freshly reset, isolated API 36.1 ARM64 emulator. Only
fictional tags, original synthesized audio, generated artwork, and synthetic history were used.

## Scope

The subsequent [physical-phone validation](map-phone-validation.md) found and fixed a parent-pager
gesture conflict and misleading statistics scope. A consented aggregate naming audit led to
compound-genre alias fixes, supported two-style names, and explicit genre-based content routing.
That follow-up reduced neutral regions from five to one on the tested phone and increased the
passing test count to 1,001. Its device timings and remaining rendering limits are documented there.

iOS compilation is checked; iOS runtime, physical-device battery use, and full-app peak memory are
not measured. Neutral labels remain appropriate when a region lacks sufficiently shared evidence.
