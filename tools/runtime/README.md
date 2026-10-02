# Smaller Android ONNX Runtime candidate

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
`--stock-aar`. From the repository root:

```sh
JAVA_HOME=/path/to/jdk17 \
/tmp/latentjam-ort-python/bin/python tools/runtime/build_reduced_ort.py \
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

`--listener-vectors` is optional; it expects `audio.f32` (960 columns) and
`text.f32` (384 columns). Without it, normalized synthetic vectors are used.
Repeat `--audio` to add real ten-second excerpts; these require `ffmpeg` and
at least 30 seconds of audio. Synthetic cases cover three semantic batch sizes,
text lengths 3/12/32/48, cold and partial histories, padded candidate pools,
text dropout, and three waveforms. All fixture hashes are recorded.

Compile `OrtParity.java` against the generated `classes.jar`, convert both to
one DEX jar with Android `d8`, and push the `device` directory to an ARM64 Android
device or emulator. Run the same probe twice with the stock and candidate
native libraries selected by `LD_LIBRARY_PATH` and `-Djava.library.path`:

```sh
CLASSPATH=/data/local/tmp/probe/probe.jar \
LD_LIBRARY_PATH=/data/local/tmp/probe/stock \
app_process -Djava.library.path=/data/local/tmp/probe/stock /system/bin \
  OrtParity /data/local/tmp/probe /data/local/tmp/probe/results-stock
```

Then repeat with the candidate directory. Compare every `.f32` output, including
shape and finiteness. A successful native build alone is not an inference test.

## Candidate APK

After Android parity passes, build with the optional init script:

```sh
./gradlew :androidApp:assembleRelease --no-configuration-cache \
  -I tools/runtime/use-reduced-ort.init.gradle \
  -Platentjam.reducedOrtRepo=/tmp/latentjam-reduced-ort/maven
```

The init script rejects changed model hashes, mismatched runtime versions, or
a modified candidate AAR. An ordinary Gradle invocation still uses the official
full runtime. If a model changes, regenerate the operator config, rebuild, and
repeat Android parity before using the reduced build. Custom `modelLocator`
assets with other operators are outside this candidate's validated contract.

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
