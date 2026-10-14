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

Generation disables minification only in this variant. The script validates
unobfuscated rules, merges flags deterministically, and records input hashes.
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
