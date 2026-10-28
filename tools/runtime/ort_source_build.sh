#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds LatentJam's reduced ARM64 ONNX Runtime from source and publishes it as the local Maven repository every
# release build reads (settings.gradle.kts, latentjam.reducedOrtRepo; default build/reduced-ort/maven). One path for
# every channel: GitHub release APKs, the Play bundle and F-Droid's build server.
#
#   tools/runtime/ort_source_build.sh ORT_SOURCE NDK SDK STOCK_AAR [OUTPUT_REPO] [DEPS_MIRROR]
#
# ORT_SOURCE  onnxruntime v1.26.0 with its submodules (git clone --branch v1.26.0 --recursive; an F-Droid srclib)
# NDK         Android NDK r28c (28.2.13676358)
# SDK         Android SDK root
# STOCK_AAR   onnxruntime-android-1.26.0.aar from Maven Central: its Java classes and its other ABIs are kept;
#             "-" downloads it from Maven Central and checks it against tools/runtime/onnxruntime-android-<v>.aar.sha256
# OUTPUT_REPO default <repo>/build/reduced-ort/maven
# DEPS_MIRROR default <repo>/build/reduced-ort/mirror; filled by ort_deps_mirror.py (SHA-1 checked; set
#             LATENTJAM_ORT_OFFLINE=1 to use a prepared mirror and never download)
#
# Nothing here needs the onnxruntime Python wheel or a prebuilt binary: the operator list is the committed
# tools/runtime/required-operators.config (build_reduced_ort.py regenerates it when a model changes, and the Gradle
# check refuses a runtime whose models or operator list differ), and protoc is built from the mirrored protobuf
# sources instead of ORT's downloaded protoc binary. JDK 17 for ORT's Java packaging; CMake ≥ 3.28, Ninja, Python 3.
set -euo pipefail

ORT_SOURCE=$(cd "$1" && pwd) NDK=$2 SDK=$3 STOCK_AAR=$4
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUTPUT_REPO=${5:-$ROOT/build/reduced-ort/maven}
MIRROR=${6:-$ROOT/build/reduced-ort/mirror}
OPERATORS="$HERE/required-operators.config"
VERSION=$(sed -n 's/^onnxruntime = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")
CANDIDATE="$VERSION-latentjam-arm64"
WORK=${LATENTJAM_ORT_WORK:-$ROOT/build/reduced-ort/work}

[ "$(git -C "$ORT_SOURCE" describe --tags --exact-match 2>/dev/null)" = "v$VERSION" ] || {
  echo "ORT source is not the v$VERSION release the app uses" >&2; exit 2; }
[ -f "$ORT_SOURCE/cmake/external/onnx/CMakeLists.txt" ] || {
  echo "ORT source lacks its submodules (git submodule update --init --recursive)" >&2; exit 2; }

python3 "$HERE/ort_deps_mirror.py" "$ORT_SOURCE" "$MIRROR" ${LATENTJAM_ORT_OFFLINE:+--offline}

if [ "$STOCK_AAR" = "-" ]; then
  STOCK_AAR="$MIRROR/onnxruntime-android-$VERSION.aar"
  [ -f "$STOCK_AAR" ] || curl --fail --location --retry 3 -o "$STOCK_AAR" \
    "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$VERSION/onnxruntime-android-$VERSION.aar"
fi
EXPECTED=$(cat "$HERE/onnxruntime-android-$VERSION.aar.sha256")
ACTUAL=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$STOCK_AAR")
[ "$ACTUAL" = "$EXPECTED" ] || { echo "$STOCK_AAR is not Maven Central's onnxruntime-android-$VERSION.aar" >&2; exit 2; }

# protoc for this host, from the same protobuf archive ORT builds against.
PROTOBUF_ZIP=$(find "$MIRROR" -path "*protocolbuffers/protobuf/*" -name "*.zip" | head -1)
rm -rf "$WORK"; mkdir -p "$WORK"
(cd "$WORK" && unzip -q "$PROTOBUF_ZIP")
PROTOBUF_SRC=$(find "$WORK" -maxdepth 1 -type d -name "protobuf-*" | head -1)
cmake -S "$PROTOBUF_SRC" -B "$WORK/protoc" -G Ninja -DCMAKE_BUILD_TYPE=Release -Dprotobuf_BUILD_TESTS=OFF \
  -Dprotobuf_WITH_ZLIB=OFF > "$WORK/protoc.log"
cmake --build "$WORK/protoc" --target protoc >> "$WORK/protoc.log"
PROTOC="$WORK/protoc/protoc"

# The configuration build_reduced_ort.py builds (its manifest's build_command), from the committed operator list.
python3 "$ORT_SOURCE/tools/ci_build/build.py" \
  --build_dir "$WORK/native" --config Release --cmake_generator Ninja \
  --android --android_sdk_path "$SDK" --android_ndk_path "$NDK" --android_abi arm64-v8a --android_api 24 \
  --build_shared_lib --build_java --enable_lto --disable_ml_ops \
  --include_ops_by_config "$OPERATORS" --skip_tests --skip_submodule_sync --skip_pip_install \
  --cmake_deps_mirror_dir "$MIRROR" --path_to_protoc_exe "$PROTOC" \
  --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF --parallel "${JOBS:-4}"

STRIP=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)
mkdir -p "$WORK/aar"
(cd "$WORK/aar" && unzip -q "$STOCK_AAR")
for lib in libonnxruntime.so libonnxruntime4j_jni.so; do
  built=$(find "$WORK/native/Release" -name "$lib" -not -path "*/intermediates/*" -print -quit)
  [ -n "$built" ] || { echo "the build produced no $lib" >&2; exit 1; }
  "$STRIP" --strip-unneeded -o "$WORK/aar/jni/arm64-v8a/$lib" "$built"
done

DEST="$OUTPUT_REPO/com/microsoft/onnxruntime/onnxruntime-android/$CANDIDATE"
rm -rf "$OUTPUT_REPO"; mkdir -p "$DEST"
AAR="$DEST/onnxruntime-android-$CANDIDATE.aar"
# Deterministic archive: sorted entries and fixed timestamps.
python3 - "$WORK/aar" "$AAR" <<'PY'
import sys, zipfile
from pathlib import Path
src, out = Path(sys.argv[1]), sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(q for q in src.rglob('*') if q.is_file()):
        info = zipfile.ZipInfo(p.relative_to(src).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o644 << 16
        z.writestr(info, p.read_bytes(), zipfile.ZIP_DEFLATED)
PY
cat > "$DEST/onnxruntime-android-$CANDIDATE.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>com.microsoft.onnxruntime</groupId><artifactId>onnxruntime-android</artifactId>
<version>$CANDIDATE</version><packaging>aar</packaging></project>
POM

# What settings.gradle.kts checks: the shipped models, the operator list and this AAR.
python3 - "$ROOT" "$OUTPUT_REPO" "$CANDIDATE" "$VERSION" "$OPERATORS" "$AAR" "$STOCK_AAR" "$ORT_SOURCE" <<'PY'
import hashlib, json, subprocess, sys, zipfile
from pathlib import Path
root, repo, candidate, version, operators, aar, stock, source = sys.argv[1:]
h = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
with zipfile.ZipFile(aar) as z:
    native = {i.filename: i.file_size for i in z.infolist() if i.filename.startswith('jni/arm64-v8a/') and i.filename.endswith('.so')}
    retained = sorted({i.filename.split('/')[1] for i in z.infolist() if i.filename.startswith('jni/')} - {'arm64-v8a'})
models = {p.name: h(p) for p in sorted(Path(root, 'androidApp/src/main/assets/ml').glob('*.onnx'))}
commit = subprocess.run(['git', '-C', source, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
Path(repo, 'manifest.json').write_text(json.dumps({
    'ort_version': version, 'candidate_version': candidate, 'source_commit': commit, 'models': models,
    'operators_sha256': h(operators), 'candidate_aar_sha256': h(aar), 'stock_aar_sha256': h(stock),
    'custom_abis': ['arm64-v8a'], 'retained_stock_abis': retained, 'native_bytes': native, 'built_from_source': True,
}, indent=1) + '\n')
PY
echo "reduced ORT repository: $OUTPUT_REPO"
