# Smaller Android ONNX Runtime candidate

## Every channel (release/0.9.3-full, 2026-10-28)

Every release build packages the reduced ARM64 runtime: GitHub release APKs, the Play bundle and F-Droid.
Debug and test builds keep Maven Central's full runtime.

- `tools/runtime/required-operators.config` is the committed operator list for the shipped models. When a model
  changes, regenerate it with `build_reduced_ort.py` (it needs the matching onnxruntime wheel), commit it, rebuild
  and rerun the parity probe.
- `tools/runtime/ort_source_build.sh ORT_SOURCE NDK SDK STOCK_AAR|-` builds the runtime from source: no wheel, no
  prebuilt protoc, source archives checked by SHA-1. It writes the local Maven repository `build/reduced-ort/maven`.
- `settings.gradle.kts` substitutes that runtime into release configurations after checking the ORT version, the exact
  model set, the operator list and the AAR hash. `latentjam.reducedOrtRepo` points elsewhere.
- `androidApp:checkReleaseOnnxRuntime` fails a release whose ARM64 `libonnxruntime.so` is not the reduced one.
  `-Platentjam.stockOrt=true` packages the full runtime on purpose; the app CI workflow does this, and
  `compact-release.yml` builds and checks the reduced runtime.

```sh
git clone --depth 1 --branch v1.26.0 --recursive --shallow-submodules https://github.com/microsoft/onnxruntime.git /tmp/ort
JAVA_HOME=/path/to/jdk17 bash tools/runtime/ort_source_build.sh /tmp/ort "$ANDROID_HOME/ndk/28.2.13676358" "$ANDROID_HOME" -
./gradlew :androidApp:assembleRelease
```

2026-10-28 result for the c095 models (A0 encoder 4c2570aa, semantic head c9ceb0f3, text encoder 81386311):

- The operator list changed (MatMulNBits and HardSigmoid gone; FusedGemm kept by 940b8b51).
- The ARM64 runtime is 11.28 MB (full: 27.41 MB).
- Android parity is bitwise on emulator-5586, 13 / 13 cases with three real excerpts, for both the wheel-based and the
  from-source build.
- Release APKs: arm64-v8a 36,955,416 bytes (full runtime: 53,077,360); armeabi-v7a and x86_64 unchanged.
- F-Droid arm64-v8a (compressed native libraries, unsigned): 28,555,060 bytes.


## Reliability upgrade gate (2026-10-14)

The application still uses full ORT **1.26.0**. Environment telemetry is explicitly
disabled in both Android and iOS before sessions are created.

`compare_android.py` compares independently compiled Java/JNI probes using the
two supplied official AARs and the current three models. It records model, fixture,
custom-op and AAR hashes, loading/inference logs and per-tensor numerical differences.
Optional `--xnnpack` is a separate run. No application is replaced.

```sh
python3 tools/runtime/compare_android.py \
  --baseline /path/onnxruntime-android-1.26.0.aar \
  --candidate /path/onnxruntime-android-1.31.0.aar \
  --custom-ops /path/arm64-v8a/libljq4.so \
  --sdk /path/android-sdk --serial SERIAL --output /tmp/ort-comparison --xnnpack
```

On the available ARM64 API 36.1 emulator, 1.26.0 completed all ten synthetic
cases (39,927 output floats), corrupt-model rejection and error recovery.
The official 1.31.0 AAR loaded its environment but failed during the first model
load with SIGILL, exit 132, at an `rdsvl` instruction in `libonnxruntime.so`
(offset `0xb62e44`). CPU and XNNPACK runs failed identically. This is an emulator
compatibility finding, not a claim that every ARM phone fails. It blocks adopting
1.31.0 here; iOS parity, device performance and network observations are not
inferred from this run. The reduced 1.31.0 rebuild is intentionally not promoted.

A separate XNNPACK run on the retained **1.26.0** version passed semantic batch 1
but failed at batch 4 in `/Concat_3`: non-concat axis 0 had dimensions 1 and 4.
The same fixtures completed with the CPU provider (including a repeat with
bitwise-equal outputs). This blocks enabling XNNPACK for the current variable
batch pipeline; it does not establish a speed or memory benefit. The Android
`app_process` exit was 137 after the uncaught `OrtException`, not evidence of OOM.

The reduced builder accepts an isolated `--ort-version`, disables telemetry at
build time for 1.31+, preserves deterministic ZIP metadata, and requires matching
source/runtime versions. The substitution manifest now requires the **exact**
shipped model set, including removals. Other ABIs retain the full stock runtime.

This is an **opt-in ARM64 build**, not a replacement for the default dependency.
It keeps ONNX model loading, graph optimization, the CPU provider, JNI, and C++
exceptions. It removes unused providers and operator kernels. The app's model
files and Java API are unchanged. Other ABIs are copied from the stock AAR.

The builder generates a union of the original graph operators and ARM-optimized
operators using the **same ORT version** as the app. Counting only the original
graphs misses fused kernels. We deliberately do not enable minimal build,
operator type reduction, or `--disable_exceptions` in this candidate.

Official references:

- [Custom ORT builds](https://onnxruntime.ai/docs/build/custom.html)
- [ORT model conversion](https://onnxruntime.ai/docs/performance/model-optimizations/ort-format-models.html)
- [Android builds](https://onnxruntime.ai/docs/build/android.html)

## Rebuild

Requirements: Python 3.11+, `onnx`, `numpy`, exactly the app's `onnxruntime`
version, CMake, Ninja, Android SDK and NDK. The upstream 1.26.0 Java packaging
uses an older Android Gradle plugin; use **JDK 17** for this native build. Use
the repository's usual JDK for the application build.

For the current version:

```sh
git clone --depth 1 --branch v1.26.0 --recursive --shallow-submodules \
  https://github.com/microsoft/onnxruntime.git /tmp/latentjam-ort-source
python3 -m venv /tmp/latentjam-ort-python
/tmp/latentjam-ort-python/bin/pip install onnxruntime==1.26.0 onnx numpy
```

Pass the downloaded Gradle-cache copy of `onnxruntime-android-1.26.0.aar` as
`--stock-aar`. The music encoder uses LatentJam's own operator, so the model
conversion needs a host build of it (`--custom-op-library`); its operators are
left out of the runtime's list. From the repository root:

```sh
cmake -S core/ort-ops/src/main/cpp -B /tmp/ljq4-host -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/ljq4-host
JAVA_HOME=/path/to/jdk17 \
/tmp/latentjam-ort-python/bin/python tools/runtime/build_reduced_ort.py \
  --custom-op-library /tmp/ljq4-host/libljq4.dylib \
  --source /tmp/latentjam-ort-source \
  --output /tmp/latentjam-reduced-ort \
  --sdk /path/to/android-sdk \
  --ndk /path/to/android-sdk/ndk/28.2.13676358 \
  --stock-aar /path/to/onnxruntime-android-1.26.0.aar
```

This publishes a local Maven candidate and `maven/manifest.json`. The manifest
records source revision, model hashes, original and candidate AAR hashes,
operators, native sizes, and the build command. Source must match the release
tag, and conversion version must match the version catalog.

## Android validation

`OrtParity.java` runs through the Android Java/JNI API used by the app. It loads
model **bytes** with sequential, single-threaded sessions. It also checks that a
corrupt model and invalid tensor shape produce an `OrtException`, and that the
next valid call succeeds. This protects the app's existing failure handling.

Create deterministic fixtures with:

```sh
python tools/runtime/prepare_parity_fixtures.py \
  --output /tmp/latentjam-ort-probe \
  --stock-aar /path/to/onnxruntime-android-1.26.0.aar \
  --listener-vectors /path/to/library-vectors \
  --audio /path/to/track.mp3
```

`--listener-vectors` is optional; it expects `audio.f32` (960 columns).
Without it, normalized synthetic vectors are used. Repeat `--audio` to add
real ten-second excerpts; these require `ffmpeg` and at least 30 seconds of
audio. Synthetic cases cover three semantic batch sizes, text lengths
3/12/32/48 and three waveforms. All fixture hashes are recorded.

Compile `OrtParity.java` against the generated `classes.jar`, convert both to
one DEX jar with Android `d8`, copy the arm64 `libljq4.so` that Gradle builds for
`core/ort-ops` into the `device` directory (the music encoder needs it), and push
that directory to an ARM64 Android device or emulator. Run the same probe twice with the stock and candidate
native libraries selected by `LD_LIBRARY_PATH` and `-Djava.library.path`:

```sh
CLASSPATH=/data/local/tmp/probe/probe.jar \
LD_LIBRARY_PATH=/data/local/tmp/probe/stock \
app_process -Djava.library.path=/data/local/tmp/probe/stock /system/bin \
  OrtParity /data/local/tmp/probe /data/local/tmp/probe/results-stock
```

Then repeat with the candidate directory. Compare every `.f32` output, including
shape and finiteness. A successful native build alone is not an inference test.

## Checked compact release command

After parity passes for the candidate, prefer the checked wrapper for distributable APKs:

```sh
python3 tools/runtime/build_compact_release.py \
  --reduced-repo /absolute/path/to/latentjam-reduced-ort/maven \
  --stock-aar /absolute/path/to/onnxruntime-android-1.26.0.aar \
  --strip-tool /android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/HOST/bin/llvm-strip
```

`--check-only` validates inputs without running Gradle; `--offline` prevents Gradle downloads.
The source tree must be clean, and the strip tool must belong to the NDK pinned by all native
modules. `--allow-dirty` is available for explicit development builds, which receive
`verified_with_local_changes` instead of release status `verified`.
The wrapper does not fetch/build a runtime, install an APK or publish a release. It uses the
existing strict init script and release/DEX/native gates, then verifies both packaged ORT libraries
against the candidate AAR after the same NDK `llvm-strip --strip-unneeded` packaging transform,
by SHA256. Raw AAR and expected packaged native hashes are recorded separately, along with the
strip tool hash/version and commands. An accidentally packaged full ARM64 runtime is a hard failure.
The stock AAR must match the manifest; every non-ARM64 native library in the candidate must match
that stock artifact. **Only ARM64 is reduced; ARMv7 and x86_64 retain full ORT.**

`build/reports/compact-release/provenance.json` records the exact commands, source revision and
dirty-file fingerprints, manifest/AAR/native hashes, APK sizes and hashes, and verification status.
The accompanying `gradle.log` contains build/gate output. Use the default ignored report directory
or an output directory outside the checkout; do not change source files during the build. Success
requires `status: verified`; `preflight_passed_no_build` is only input validation. This packaging
check does not replace device inference parity or prove runtime compatibility.

Regression checks for the wrapper itself: `python3 -B tools/runtime/test_compact_release.py`.

`.github/workflows/compact-release.yml` rebuilds the reduced runtime and executes this wrapper
on changes to the models/runtime tooling, and on manual dispatch. Ordinary application CI also
checks the stock split and four-ABI F-Droid packages. The compact job checks build and packaging;
it does not replace the separate ARM64 device inference-parity check.

## Candidate APK

After Android parity passes, build with the optional init script:

```sh
./gradlew :androidApp:assembleRelease --no-configuration-cache \
  -I tools/runtime/use-reduced-ort.init.gradle \
  -Platentjam.reducedOrtRepo=/tmp/latentjam-reduced-ort/maven
```

The init script rejects a shipped graph that is new or whose hash changed,
mismatched runtime versions, or a modified candidate AAR. The manifest must match the exact shipped graph set, including removals. An ordinary Gradle invocation still uses the official full runtime. If
a model changes, regenerate the operator config, rebuild, and repeat Android
parity before using the reduced build. Custom `modelLocator` assets with other
operators are outside this candidate's validated contract.

## 2026-10-01 result

The ARM64 library was built from ORT 1.26.0 revision
`8c546c37b43caaca1fa25db430dab94b901cf277`, with NDK 28.2.13676358. All five
models ran on an ARM64 Android emulator through JNI. Across 21 cases including
three real audio excerpts, all 47,047 output floats were **bitwise identical**
to the stock 1.26.0 runtime. Both error cases and subsequent recovery passed.
This is emulator coverage, not a physical-device performance claim.

The stripped runtime is 11,723,184 bytes and JNI is 83,120 bytes. Raw logs,
fixtures, per-case differences, hashes, and candidate artifacts are in
`tools/research/output/ort-reduction-20261001/` (ignored research output).

Two release APKs from the same current app sources measure 72,194,061 bytes
(68.85 MiB) with stock ORT and 56,514,485 bytes (53.90 MiB) with the candidate:
**15,679,576 bytes / 21.72% smaller**. Only the two ARM64 native libraries differ
inside the APK; all DEX and model assets are identical. APK signing verification,
16 KiB ZIP alignment, emulator installation, and cold launch passed. A separate
deliberately stale model manifest was correctly rejected before building.

## 2026-10-06 result

After the music encoder was replaced by the activation-trained p50 graph, the regenerated operator
config was identical, so the 2026-10-05 candidate AAR stays valid; only the manifest's model hash
changed. Emulator parity with real excerpts from three FMA tracks: 21 cases, 47,047 output floats,
bitwise identical to the stock runtime; both error cases and the recovery passed.

## 2026-10-10: 0.9.0 drops the SMART nets

The app stopped shipping `predictor_state.onnx` and `predictor_scorer_n100.onnx`. At that time,
the three remaining graphs were byte-identical to entries in the 2026-10-06 candidate's five-model
manifest and had passed its parity run; the removed nets' operators remained unused. This is
historical compatibility evidence, not permission to use that old manifest with today's gate.

The current init script requires the **exact** shipped graph set, so it rejects that five-model
manifest. Regenerate the operators and manifest through the current reduced-runtime builder,
rebuild the candidate and repeat device parity on the current three models before using the
[checked compact release command](#checked-compact-release-command). Do not remove manifest
entries manually to bypass the gate. The current fixtures cover ten synthetic cases, with optional
real audio excerpts added separately.

## 2026-10-15 compact build

The exact three-model ORT 1.26.0 rebuild passes physical-device CPU parity:
13 cases / 42,807 Float32 values, including three public FMA excerpts, with
matching shapes and bitwise outputs; invalid-model/shape rejection and recovery
pass. ARM64 APK size is 37,145,894 bytes (35.43 MiB). Only the ORT native libraries
differ from the 52,956,542-byte full-runtime UI APK.
The stored candidate's manifest and AAR are required inputs to the wrapper;
an ordinary `assembleRelease` still intentionally builds the full runtime.

`compare_android.py --require-bitwise` makes numerical differences fatal. It
compares actual output shapes, counts and finiteness, and accepts repeated
`--audio` plus optional `--listener-vectors` fixtures. Every run requires a new
local output directory and creates an isolated device directory.
