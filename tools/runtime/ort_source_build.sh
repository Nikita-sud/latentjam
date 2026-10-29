#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds LatentJam's reduced ARM64 ONNX Runtime from source and publishes it as the local Maven repository every
# release build reads (settings.gradle.kts, latentjam.reducedOrtRepo; default build/reduced-ort/maven). One path for
# every channel: GitHub release APKs, the Play bundle and F-Droid's build server.
#
#   tools/runtime/ort_source_build.sh ORT_SOURCE NDK SDK [OUTPUT_REPO]
#
# ORT_SOURCE  onnxruntime v1.26.0 with its submodules (git clone --branch v1.26.0 --recursive; an F-Droid srclib).
#             Read only: nothing is patched or written there.
# NDK         Android NDK r28c (28.2.13676358)
# SDK         Android SDK root
# OUTPUT_REPO default <repo>/build/reduced-ort/maven
#
# Everything in the AAR is built here, from ORT_SOURCE and the SHA-1-checked source archives ORT's CMake fetches:
#   1. protoc for this host, from the protobuf sources (instead of ORT's downloaded protoc binary);
#   2. libonnxruntime.so with ORT's tools/ci_build/build.py, native only: no --build_java, so none of ORT's Gradle
#      builds run (its Android AAR step needs AGP 7.4.2, which fails on JDK 21);
#   3. the ai.onnxruntime classes with javac (src/main/java + src/main/android, as ORT's Android AAR), which also
#      writes the JNI headers;
#   4. libonnxruntime4j_jni.so from java/src/main/native with tools/runtime/onnxruntime4j_jni/CMakeLists.txt (ORT's
#      flags for that target);
#   5. an AAR with only those: classes.jar, AndroidManifest.xml and jni/arm64-v8a. It holds no other ABI: the release
#      build takes the other ABIs from Maven Central's onnxruntime-android, as an ordinary Gradle dependency
#      (androidApp's stockOrtJniLibs task); an F-Droid arm64-v8a build skips that task and packages none of it.
#
# The operator list is the committed tools/runtime/required-operators.config (build_reduced_ort.py regenerates it
# when a model changes, and the Gradle check refuses a runtime whose models or operator list differ).
#
# Intermediate files go to LATENTJAM_ORT_WORK (default ${TMPDIR:-/tmp}/latentjam-ort-<version>), outside the source
# tree, and are deleted at the end unless LATENTJAM_ORT_KEEP_WORK=1. The dependency archives go to
# LATENTJAM_ORT_MIRROR (default <work>/mirror) and are kept; ort_deps_mirror.py checks each one against ORT's
# cmake/deps.txt, and LATENTJAM_ORT_OFFLINE=1 uses a prepared mirror and never downloads.
#
# Needs JDK 17 or newer (javac), CMake >= 3.28, Ninja, Python 3 with flatbuffers, and a host C++ compiler for protoc.
set -euo pipefail

[ $# -ge 3 ] && [ $# -le 4 ] || { sed -n '8,14p' "$0" >&2; exit 2; }
ORT_SOURCE=$(cd "$1" && pwd) NDK=$(cd "$2" && pwd) SDK=$3
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUTPUT_REPO=${4:-$ROOT/build/reduced-ort/maven}
OPERATORS="$HERE/required-operators.config"
VERSION=$(sed -n 's/^onnxruntime = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")
CANDIDATE="$VERSION-latentjam-arm64"
ABI=arm64-v8a
API=24
WORK=${LATENTJAM_ORT_WORK:-${TMPDIR:-/tmp}/latentjam-ort-$VERSION}
WORK=${WORK%/}
MIRROR=${LATENTJAM_ORT_MIRROR:-$WORK/mirror}
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac

[ "$(git -C "$ORT_SOURCE" describe --tags --exact-match 2>/dev/null)" = "v$VERSION" ] || {
  echo "ORT source is not the v$VERSION release the app uses" >&2; exit 2; }
[ -f "$ORT_SOURCE/cmake/external/onnx/CMakeLists.txt" ] || {
  echo "ORT source lacks its submodules (git submodule update --init --recursive)" >&2; exit 2; }
case "$WORK/" in "$ROOT"/*) echo "LATENTJAM_ORT_WORK must be outside the source tree" >&2; exit 2;; esac

python3 "$HERE/ort_deps_mirror.py" "$ORT_SOURCE" "$MIRROR" ${LATENTJAM_ORT_OFFLINE:+--offline}
rm -rf "$WORK/protoc" "$WORK/native" "$WORK/java" "$WORK/jni" "$WORK/aar"
mkdir -p "$WORK/protoc"

# 1. protoc for this host, from the same protobuf archive ORT builds against.
PROTOBUF_ZIP=$(find "$MIRROR" -path "*protocolbuffers/protobuf/*" -name "*.zip" | head -1)
(cd "$WORK/protoc" && unzip -q "$PROTOBUF_ZIP")
PROTOBUF_SRC=$(find "$WORK/protoc" -maxdepth 1 -type d -name "protobuf-*" | head -1)
cmake -S "$PROTOBUF_SRC" -B "$WORK/protoc/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -Dprotobuf_BUILD_TESTS=OFF \
  -Dprotobuf_WITH_ZLIB=OFF > "$WORK/protoc.log"
cmake --build "$WORK/protoc/build" --target protoc >> "$WORK/protoc.log"

# 2. libonnxruntime.so: the configuration build_reduced_ort.py builds (its manifest's build_command), from the
# committed operator list, without the Java parts.
python3 "$ORT_SOURCE/tools/ci_build/build.py" \
  --build_dir "$WORK/native" --config Release --cmake_generator Ninja \
  --android --android_sdk_path "$SDK" --android_ndk_path "$NDK" --android_abi "$ABI" --android_api "$API" \
  --build_shared_lib --enable_lto --disable_ml_ops \
  --include_ops_by_config "$OPERATORS" --skip_tests --skip_submodule_sync --skip_pip_install \
  --cmake_deps_mirror_dir "$MIRROR" --path_to_protoc_exe "$WORK/protoc/build/protoc" \
  --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF --targets onnxruntime --parallel "${JOBS:-4}"
RUNTIME="$WORK/native/Release/libonnxruntime.so"
[ -f "$RUNTIME" ] || { echo "the build produced no libonnxruntime.so" >&2; exit 1; }

# 3. The Java API: ORT's Android AAR compiles src/main/java and src/main/android for Java 17; -h writes the JNI
# headers the native sources include.
mkdir -p "$WORK/java/classes" "$WORK/java/headers"
find "$ORT_SOURCE/java/src/main/java" "$ORT_SOURCE/java/src/main/android" -name "*.java" | LC_ALL=C sort \
  > "$WORK/java/sources.txt"
"$JAVAC" --release 17 -encoding UTF-8 -nowarn -h "$WORK/java/headers" -d "$WORK/java/classes" @"$WORK/java/sources.txt"

# 4. The JNI library, linked against the runtime just built.
cmake -S "$HERE/onnxruntime4j_jni" -B "$WORK/jni" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="$API" -DORT_SOURCE="$ORT_SOURCE" -DORT_BUILD_DIR="$WORK/native/Release" \
  -DJNI_HEADERS="$WORK/java/headers" > "$WORK/jni.log"
cmake --build "$WORK/jni" >> "$WORK/jni.log"

# 5. The AAR.
STRIP=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)
mkdir -p "$WORK/aar/jni/$ABI" "$WORK/aar/META-INF/com/android/build/gradle"
"$STRIP" --strip-unneeded -o "$WORK/aar/jni/$ABI/libonnxruntime.so" "$RUNTIME"
"$STRIP" --strip-unneeded -o "$WORK/aar/jni/$ABI/libonnxruntime4j_jni.so" "$WORK/jni/libonnxruntime4j_jni.so"
cat > "$WORK/aar/AndroidManifest.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="ai.onnxruntime" >

    <uses-sdk android:minSdkVersion="$API" />

</manifest>
XML
: > "$WORK/aar/R.txt"
printf 'aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=1\nminCompileSdkExtension=0\nminAndroidGradlePluginVersion=1.0.0\n' \
  > "$WORK/aar/META-INF/com/android/build/gradle/aar-metadata.properties"

DEST="$OUTPUT_REPO/com/microsoft/onnxruntime/onnxruntime-android/$CANDIDATE"
rm -rf "$OUTPUT_REPO"; mkdir -p "$DEST"
AAR="$DEST/onnxruntime-android-$CANDIDATE.aar"
# Deterministic archives (sorted entries, fixed timestamps): classes.jar, then the AAR around it.
python3 - "$WORK/java/classes" "$WORK/aar/classes.jar" "$WORK/aar" "$AAR" <<'PY'
import sys, zipfile
from pathlib import Path
def pack(src, out):
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(q for q in Path(src).rglob('*') if q.is_file()):
            info = zipfile.ZipInfo(p.relative_to(src).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            z.writestr(info, p.read_bytes(), zipfile.ZIP_DEFLATED)
pack(sys.argv[1], sys.argv[2])
pack(sys.argv[3], sys.argv[4])
PY
cat > "$DEST/onnxruntime-android-$CANDIDATE.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>com.microsoft.onnxruntime</groupId><artifactId>onnxruntime-android</artifactId>
<version>$CANDIDATE</version><packaging>aar</packaging></project>
POM

# What settings.gradle.kts checks: the shipped models, the operator list and this AAR.
python3 - "$ROOT" "$OUTPUT_REPO" "$CANDIDATE" "$VERSION" "$OPERATORS" "$AAR" "$ORT_SOURCE" <<'PY'
import hashlib, json, subprocess, sys, zipfile
from pathlib import Path
root, repo, candidate, version, operators, aar, source = sys.argv[1:]
h = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
with zipfile.ZipFile(aar) as z:
    native = {i.filename: i.file_size for i in z.infolist() if i.filename.startswith('jni/') and i.filename.endswith('.so')}
    classes = hashlib.sha256(z.read('classes.jar')).hexdigest()
models = {p.name: h(p) for p in sorted(Path(root, 'androidApp/src/main/assets/ml').glob('*.onnx'))}
commit = subprocess.run(['git', '-C', source, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
Path(repo, 'manifest.json').write_text(json.dumps({
    'ort_version': version, 'candidate_version': candidate, 'source_commit': commit, 'models': models,
    'operators_sha256': h(operators), 'candidate_aar_sha256': h(aar), 'classes_jar_sha256': classes,
    'custom_abis': ['arm64-v8a'], 'native_bytes': native, 'built_from_source': True,
}, indent=1) + '\n')
PY

[ "${LATENTJAM_ORT_KEEP_WORK:-}" = 1 ] || rm -rf "$WORK/protoc" "$WORK/native" "$WORK/java" "$WORK/jni" "$WORK/aar"
echo "reduced ORT repository: $OUTPUT_REPO"
