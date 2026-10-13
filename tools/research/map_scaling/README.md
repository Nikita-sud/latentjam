# Map scaling experiment

Research only: no Gradle production dependency, JNI wrapper, app setting or changed track limit.
Findings: [13 October 2026 report](../../../docs/map-scaling-analysis-2026-10-13.md).

## Exact Kotlin baseline on a physical Android device

Build `:core:smart:assembleAndroidDeviceTest`, then install
`core/smart/build/outputs/apk/androidTest/smart-androidTest.apk` using `adb -s DEVICE install -r`.
This is the isolated `io.github.nikitasud.latentjam.smart.test` package, not the music app.
Run only the opt-in class (a full device-test suite contains unrelated tests):

```sh
adb -s DEVICE shell am instrument -w -r \
  -e class io.github.nikitasud.latentjam.smart.cluster.MapLayoutProfileDeviceTest \
  -e mapProfile 1 -e mapRows 1107 -e mapRepeats 3 \
  io.github.nikitasud.latentjam.smart.test/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE logcat -d -v brief -s 'LJMapProfile:I' '*:S'
adb -s DEVICE exec-out run-as io.github.nikitasud.latentjam.smart.test \
  cat files/map-reduced-1107.f32 > /tmp/map-reduced-1107.f32
adb -s DEVICE exec-out run-as io.github.nikitasud.latentjam.smart.test \
  cat files/map-exact-1107.f32 > /tmp/map-exact-1107.f32
```

The final source also appends each measured result to `files/map-profile-N.log`; copy it using
the same `exec-out run-as` pattern. Logs append across invocations, so retain run provenance.
The initial measured APK logged to logcat only. The fixture is synthetic and reads no media.
Repeat with 3000 and 4643 rows after earlier work finishes. The test permits at most 5000 rows
and bypasses the production 3000 guard **only inside this test**. The test APK has an existing
`largeHeap=true` manifest, so passing it does not prove memory safety in the music app.

## Native Barnes–Hut probe

Clone the five URLs in `dependencies.json` outside the repository. Check out the exact listed
commits; their license notices remain in those checkouts. `qdtsne` includes BSD-4-Clause files,
despite its top-level MIT license. No third-party source is vendored or shipped by this experiment.

For directories `/tmp/map-deps-qdtsne`, `/tmp/map-deps-knncolle`, etc.:

```sh
python3 tools/research/map_scaling/build_probe.py \
  --compiler "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android24-clang++" \
  --android --deps-prefix /tmp/map-deps- --output /tmp/qdtsne-probe
adb -s DEVICE push /tmp/qdtsne-probe /data/local/tmp/latentjam-map-qdtsne-probe
adb -s DEVICE push /tmp/map-reduced-1107.f32 /data/local/tmp/latentjam-map-reduced-1107.f32
adb -s DEVICE shell chmod 755 /data/local/tmp/latentjam-map-qdtsne-probe
adb -s DEVICE shell /data/local/tmp/latentjam-map-qdtsne-probe 1107 50 \
  /data/local/tmp/latentjam-map-reduced-1107.f32 /data/local/tmp/latentjam-map-bh-1107.f32 0.5
adb -s DEVICE pull /data/local/tmp/latentjam-map-bh-1107.f32 /tmp/map-bh-1107.f32
```

Use `--compiler clang++` without `--android` for a host smoke/quality run. Keep host and phone
timing results separate. Inputs are little-endian row-major f32, outputs `N × 2`; the probe takes
already reduced `N × 50` inputs, so it excludes PCA. It uses one thread, perplexity 20, 60 nearest
neighbors, 1000 iterations, the app's cold coordinate seed, theta 0.5 (also evaluate 0.3),
max tree depth 20, and no leaf approximation. Native background nice priority is requested.
Its RSS is process RSS, not directly comparable to the Kotlin sampler's used Java heap.

Run sequentially with no other numerical tests or UI workloads. Record warmup, charging/thermal
state and app/runtime identity; alternate baseline/candidate order for a release comparison.
Validate on representative real vectors before changing the app. ART vs C++ comparisons also
include compiler/runtime differences and cannot isolate the effect of Barnes–Hut alone.

## Quality

```sh
python3 tools/research/map_scaling/quality.py --rows 1107 --dims 50 \
  --input /tmp/map-reduced-1107.f32 /tmp/map-exact-1107.f32 /tmp/map-bh-1107.f32
```

Requires NumPy and SciPy. It computes exact trustworthiness@15 and neighbor recall against the
given matrix, plus neighborhood overlap with the first embedding. On the reduced matrix these
do not measure PCA loss or musical playlist/artist quality. Different final coordinates are
expected. Musical quality and stability after library changes need separate real-data replay.
Keep personal vectors, IDs and media outside Git; checked-in results contain aggregates only.
