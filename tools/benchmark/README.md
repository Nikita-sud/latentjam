# Application benchmarks and measured profiles

The module is opt-in: add `-Platentjam.benchmarks=true`. Its target uses
`io.github.nikitasud.latentjam.kmp.benchmark`, so installation and fixture
controls cannot replace the normal application. The target is not debuggable
and does not request `largeHeap`. The instrumentation APK is debuggable.

## Generate profiles

Use an API 33+ device/emulator, with a prepared music library and audio permission.
Let scanning/indexing settle before collecting. Keep Tracks and Map enabled.
Do not benchmark an empty first-run permission screen.

```sh
./gradlew :androidApp:assembleBenchmark :benchmark:assembleBenchmark \
  -Platentjam.benchmarks=true -Platentjam.profileGeneration=true
adb -s SERIAL install -r androidApp/build/outputs/apk/benchmark/androidApp-arm64-v8a-benchmark.apk
adb -s SERIAL install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
adb -s SERIAL shell am instrument -w -r \
  -e class io.github.nikitasud.latentjam.benchmark.AppBaselineProfile \
  io.github.nikitasud.latentjam.benchmark/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL pull /storage/emulated/0/Android/media/io.github.nikitasud.latentjam.benchmark /tmp/lj-profiles
python3 tools/benchmark/install_profiles.py /tmp/lj-profiles \
  --apk androidApp/build/outputs/apk/benchmark/androidApp-arm64-v8a-benchmark.apk \
  --fixture 'device/API, fixture identity, track count and readiness'
```

Generation disables minification only in this variant. The script checks basic rule syntax
and the presence of app class names, merges flags deterministically, and records input hashes.
This is not a complete ART grammar or proof that every input rule is unobfuscated.
AGP reads `androidApp/src/main/baseline-prof.txt` and
`androidApp/src/main/baselineProfiles/startup-prof.txt`; these paths are intentionally different.
Commit the two text profiles and provenance together. Startup rules cover launch
and the first list; baseline rules additionally cover search, player and return.
An emulator may need `-e androidx.benchmark.suppressErrors EMULATOR`; this does
not make its timings physical-device evidence.

## Compare the same optimized APK

Rebuild without `latentjam.profileGeneration`, install once, and run
`AppBenchmarks#coldStartAndFirstList` with instrumentation argument
`-e profile none`, then `-e profile require`. Require fails if the packaged
profile cannot be installed. Repeat in reversed order on the same physical
device with the same data and thermal state. Preserve JSON results and Perfetto
traces from the instrumentation output directory. Never compare debug to release.

`searchPlayerAndReturn` covers the remaining journey. `readyMap` requires an
existing completed layout and checks the on-disk cache before measuring.
`uncachedMap` clears only `map_layout.bin` and its temporary file via a
shell-permission receiver present exclusively in the benchmark source set;
both scenarios restart the process to discard in-memory layout/regions.
Their MapReady interval includes startup, region discovery and projection.
Indexing must already be complete, with no more than 3,000 usable tracks.
It is not a native-kernel-only measurement.

For map adoption freeze vector/playlist fixtures and their hashes before running
the existing research replay tools. Thresholds are recorded in
`map-acceptance.json`. Record Java heap, native heap and process PSS separately.
The larger heap requested by the existing core instrumentation manifest must
not be used as app-memory evidence.

## Reproducibility

`python3 tools/ci/reproducible_android.py` makes two independent clean unsigned
F-Droid builds, with the committed profiles enabled. It keeps APKs, logs and
exact source/output hashes under `build/reports`. It needs 5 GiB of free space.
A mismatch requires artifact-level investigation; do not disable profiles or
downgrade the Android toolchain without identifying the differing artifact.

## UI optimization: before/after on the same phone

The three focused `AppBenchmarks` methods use existing AndroidX Macrobenchmark
and UIAutomator, without a runtime dependency in the app:

- `scrollTracks`: eight bounded swipes down and eight up through the actual library,
  including artwork decode/reuse and lazy row composition.
- `changeTrackSort`: three title → artist → title round trips, waiting for the
  **published list order** tag before the next action. The menu label alone is
  not proof that asynchronous preparation has finished.
- `openClosePlayer`: five player open/Back transitions after pausing the selected
  track outside the measurement. Search, playback decoding and normalization
  are not mixed into this scenario.

Build the benchmark target and harness without `latentjam.profileGeneration`.
The target needs only measurement tags on the track list, published sort order,
menu button and choices; copy those tags to the baseline revision as well. Use
an exact source revision for the baseline, not an older APK with unrelated code
changes. Keep both APKs and their SHA-256 values. Install only the separate
`.kmp.benchmark` package with `adb install -r`; preserve its data between runs.
The scripts do not install APKs, clear app data or change system settings.
The harness restores title-ascending order before scrolling/sorting, respecting
the application behavior where selecting the active field toggles direction.

Prepare at least 20 tracks (prefer the same representative large library),
allow scanning to finish, and keep background audio analysis idle. Keep the
phone unlocked in portrait, on the same display/font settings and thermal
conditions. Real media is read through the separately granted audio permission;
no production private app data needs to be copied. The default pause selector
supports English/Russian; for another locale pass `--pause-description`,
`--sort-title-label` and `--sort-artist-label`. Dropdown menus have a separate
Compose semantics owner, so their visible labels are used rather than assuming
that root test tags automatically become resource IDs inside a popup.

```sh
python3 tools/benchmark/run_ui.py --serial SERIAL --label before-full-1 \
  --apk /absolute/path/to/before.apk --source 'COMMIT + measurement-only tags' \
  --fixture 'same-device-media-library-v1' --tracks 1110 --iterations 5
# Install the optimized target only; leave the same harness and fixture in place.
python3 tools/benchmark/run_ui.py --serial SERIAL --label after-full-1 \
  --apk androidApp/build/outputs/apk/benchmark/androidApp-arm64-v8a-benchmark.apk \
  --source 'COMMIT + UI changes' --fixture 'same-device-media-library-v1' \
  --tracks 1110 --iterations 5
python3 tools/benchmark/compare_ui.py \
  build/ui-optimization/before-full-1 build/ui-optimization/after-full-1 \
  --output build/reports/ui-optimization-comparison.json
```

`full` compilation is the default for isolating UI code changes from JIT warmup
and differences in profile coverage. `--profile require` checks production
baseline-profile behavior separately. Do not compare different compilation
modes. Repeat in reversed order and compare the per-iteration spread before
claiming a gain. The runner verifies the installed target and harness APK hashes, fingerprints
MediaStore music IDs/revisions/sizes/durations without song names, and records
the fixture/source/device/display settings and thermal/battery state, and preserves
JSON plus Perfetto traces in a unique output directory. It does not suppress
benchmark errors. Failed runs keep partial evidence and must not be reported as
passing measurements.

The comparison reports frame CPU/overrun percentiles and per-iteration P95
ranges. Positive `frameOverrunMs` means the frame missed its deadline; the
percentage is reported per iteration. P95 ranges are not confidence intervals.
The sort trace duration includes UIAutomator synchronization and must not be
called sort computation time. A result from one S24 Ultra does not establish a
speedup on slower phones or on iOS. Map gesture measurement remains separate
until a completed, fixed map fixture exists; background indexing would confound
these UI measurements.

To check startup separately with the packaged baseline profile, run matching
`--profile require --scenarios coldStartAndFirstList --iterations 5` sessions.
Do not mix these results with the full-compilation interaction benchmarks.

## Recorded phone validation

The 2026-10-15 aggregate report recorded the exact source/APK/harness hashes and the unchanged 1,110-track fixture.
It covers stock ORT 1.26.0 APKs only. The interaction comparison is inconclusive:
thermal conditions changed, and NotificationShade interrupted one injected swipe
in the after suite. A one-iteration scroll retry passed. The successful player
and sort samples remain diagnostic; the failed suite is not counted as passing.

The separate five-iteration startup pair required the packaged profile on both
APKs and ran at thermal status 0. Median initial display was 268.336 → 266.527 ms.
The FirstList trace was 2116.679 → 2399.668 ms, including UIAutomator waits. These
results establish neither a first-list speedup nor an FPS improvement. Local raw
logs and traces remain under `build/ui-optimization`; they are not committed.

## Upstream references used for the UI work

The measurement and background-work approach follows Google's
[Compose performance codelab](https://developer.android.com/codelabs/jetpack-compose-performance)
and [Macrobenchmark guide](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview):
measure optimized builds on a physical device, compare frame CPU/deadline
metrics, use full compilation for runtime changes and profile compilation for
startup. Artwork sizing follows the existing
[Coil 3.4.0 BitmapFactoryDecoder](https://github.com/coil-kt/coil/blob/3.4.0/coil-core/src/androidMain/kotlin/coil3/decode/BitmapFactoryDecoder.kt)
source's sampled decode/density approach. This work reuses dependencies already
in LatentJam; it adds no production UI framework or image-loading library.
