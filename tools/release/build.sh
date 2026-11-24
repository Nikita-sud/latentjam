#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# The one entry point for LatentJam release files, on the Mac, on GitHub Actions and on F-Droid's build server
# (docs/release-build-094-plan.md, section A).
#
#   tools/release/build.sh android                 unsigned per-ABI release APKs  -> build/release/<version>/
#   tools/release/build.sh bundle                  unsigned Play bundle (AAB)     -> build/release/<version>/
#   tools/release/build.sh ios                     unsigned IPA (macOS only)      -> build/release/<version>/
#   tools/release/build.sh fdroid-prebuild ABI [ORT_SOURCE NDK SDK]
#                                                  what F-Droid's recipe runs before its own Gradle build
#   tools/release/build.sh ort-source DIR          the pinned onnxruntime source with submodules, cloned into DIR
#   tools/release/build.sh sign DIR [--resign]     sign DIR's APKs with the release key (Mac, interactive password)
#   tools/release/build.sh verify-signed APK...    the checks CI's sign job runs after signing
#
# FROZEN: the fdroid-prebuild command line (this path, the subcommand name, its argument order and the ABI names) is
# copied into every future F-Droid build block by F-Droid's bot; tools/release/test_build_sh.py runs the four frozen
# lines. What the script does inside is free to change.
#
# Environment:
#   LATENTJAM_DRY_RUN=1        print the commands instead of running them
#   LATENTJAM_ORT_SOURCE       onnxruntime v<version> checkout with submodules (default: cloned into the cache)
#   LATENTJAM_ORT_CACHE=0      build the reduced runtime afresh into build/reduced-ort/maven (CI); default: cached
#                              under ~/.cache/latentjam/ort/<key>, key = tools/runtime/ + toolchain versions
#   LATENTJAM_REDUCED_ORT_REPO an already built reduced-ORT repository to use as is (CI: bundle after android)
#   ANDROID_HOME               Android SDK (default ~/Library/Android/sdk on macOS)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
NDK_VERSION=28.2.13676358
BUILD_TOOLS=36.0.0
# Where the reduced runtime's source is cloned from: a repository holding the history of github.com/microsoft/onnxruntime,
# which has answered 404 since 2026-11-18. The commit is what is trusted, not the address: a git checkout at any other
# commit is refused (verify_ort_source). tools/release/fdroid/recipe.yml gives F-Droid's srclib the same address.
ORT_SOURCE_URL=https://github.com/Nikita-sud/onnxruntime.git
ORT_SOURCE_VERSION=1.26.0
ORT_SOURCE_COMMIT=8c546c37b43caaca1fa25db430dab94b901cf277
# SHA-256 of the certificate every GitHub release APK has carried (the old debug key, now latentjam-release.p12).
RELEASE_CERT_SHA256=dd7001b32e9693c346d596fccdd65e6d307590a21878c49d785a2cabb4554a2b

run() {
  if [ "${LATENTJAM_DRY_RUN:-0}" = 1 ]; then printf '+ %s\n' "$*" >&2; else "$@"; fi
}
die() { echo "build.sh: $*" >&2; exit 2; }

app_version() { sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$ROOT/androidApp/build.gradle.kts"; }
ort_version() { sed -n 's/^onnxruntime = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml"; }
sdk_root() {
  local sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  [ -n "$sdk" ] || { [ "$(uname)" = Darwin ] && sdk=$HOME/Library/Android/sdk; }
  [ -d "$sdk" ] || die "no Android SDK (set ANDROID_HOME)"
  echo "$sdk"
}
out_dir() {
  local d
  d="$ROOT/build/release/$(app_version)"
  [ "${LATENTJAM_DRY_RUN:-0}" = 1 ] || mkdir -p "$d"
  echo "$d"
}

# Parallel ORT compile jobs for the machine this really is: CPUs available to this process, and memory from the
# cgroup limit when there is one (a container's /proc/meminfo shows the host). ORT's LTO steps need ~2 GB a job.
ort_jobs() {
  local cpus mem_kb limit
  cpus=$( (nproc 2>/dev/null || sysctl -n hw.ncpu) | head -1)
  if [ -r /sys/fs/cgroup/memory.max ] && limit=$(cat /sys/fs/cgroup/memory.max) && [ "$limit" != max ]; then
    mem_kb=$((limit / 1024))
  elif [ -r /proc/meminfo ]; then
    mem_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)
  else
    mem_kb=$(( $(sysctl -n hw.memsize) / 1024 ))
  fi
  local by_mem=$((mem_kb / 2097152))
  [ "$by_mem" -ge 1 ] || by_mem=1
  [ "$cpus" -lt "$by_mem" ] && echo "$cpus" || echo "$by_mem"
}

# Refuses a git checkout of the runtime's source whose HEAD is not the pinned commit. A directory without .git (an
# unpacked archive someone named) cannot be checked this way and is left to whoever named it.
verify_ort_source() {
  local source=$1 version head
  version=$(ort_version)
  [ "$version" = "$ORT_SOURCE_VERSION" ] \
    || die "the app uses onnxruntime $version, the pinned source is $ORT_SOURCE_VERSION: set ORT_SOURCE_VERSION and ORT_SOURCE_COMMIT"
  [ -e "$source/.git" ] || return 0
  # safe.directory: F-Droid's build user is not always the one that cloned the srclib.
  head=$(git -c "safe.directory=$source" -C "$source" rev-parse HEAD) || die "$source: its commit cannot be read"
  [ "$head" = "$ORT_SOURCE_COMMIT" ] \
    || die "$source is at commit $head, onnxruntime v$version is $ORT_SOURCE_COMMIT"
}

# The pinned source with its submodules, cloned into a directory that does not exist yet.
clone_ort_source() {
  local source=$1
  run git clone --quiet --depth 1 --branch "v$(ort_version)" --recursive --shallow-submodules \
    "$ORT_SOURCE_URL" "$source" >&2 || return 1
  [ "${LATENTJAM_DRY_RUN:-0}" = 1 ] || [ -e "$source/.git" ] || die "$source: the clone left no git checkout"
  verify_ort_source "$source"
}

# The reduced ARM64 ONNX Runtime's Maven repository, built if needed; prints its path.
reduced_ort_repo() {
  local sdk=$1 version ndk source key repo
  version=$(ort_version)
  ndk="$sdk/ndk/$NDK_VERSION"
  [ -d "$ndk" ] || die "NDK $NDK_VERSION missing: sdkmanager 'ndk;$NDK_VERSION'"
  if [ -n "${LATENTJAM_REDUCED_ORT_REPO:-}" ]; then echo "$LATENTJAM_REDUCED_ORT_REPO"; return; fi
  if [ "${LATENTJAM_ORT_CACHE:-1}" = 0 ]; then
    repo="$ROOT/build/reduced-ort/maven"
  else
    # The models count too: the built runtime's manifest names them, and settings.gradle.kts refuses another set.
    key=$( { (cd "$ROOT" && { find tools/runtime -type f ! -name '*.pyc'; ls androidApp/src/main/assets/ml/*.onnx; } \
               | LC_ALL=C sort | xargs shasum -a 256)
             echo "ort=$version ndk=$NDK_VERSION"; cmake --version | head -1; javac -version 2>&1; } \
           | shasum -a 256 | cut -c1-16)
    repo="$HOME/.cache/latentjam/ort/$key/maven"
  fi
  if [ -f "$repo/manifest.json" ] && [ "${LATENTJAM_ORT_CACHE:-1}" != 0 ]; then echo "$repo"; return; fi
  source=${LATENTJAM_ORT_SOURCE:-$HOME/.cache/latentjam/onnxruntime-v$version}
  if [ ! -f "$source/cmake/external/onnx/CMakeLists.txt" ]; then
    # Only a clone this script starts is removed when it fails; a checkout someone named is never touched.
    [ -n "${LATENTJAM_ORT_SOURCE:-}" ] && die "$source is not an onnxruntime v$version checkout with submodules"
    # A directory already there (an interrupted clone, someone's own work) is never deleted for them.
    [ -e "$source" ] && die "$source exists but is not a complete onnxruntime v$version checkout: move it away"
    ( clone_ort_source "$source" ) || { rm -rf "$source"; exit 1; }
  fi
  verify_ort_source "$source"
  # Work dir and download mirror on disk, per output; two worktrees with the same key share them, so build one at a
  # time. The mirror is kept.
  # With the cache off the output sits in this tree, and the work dir must not (ort_source_build.sh refuses it).
  local work=${LATENTJAM_ORT_WORK:-$( [ "${LATENTJAM_ORT_CACHE:-1}" = 0 ] && echo "${TMPDIR:-/tmp}/latentjam-ort-work" || echo "$(dirname "$repo")/work")}
  LATENTJAM_ORT_WORK=$work LATENTJAM_ORT_MIRROR=${LATENTJAM_ORT_MIRROR:-$HOME/.cache/latentjam/ort-mirror-$version} \
    JOBS=${JOBS:-$(ort_jobs)} run bash "$ROOT/tools/runtime/ort_source_build.sh" "$source" "$ndk" "$sdk" "$repo" >&2 \
    || exit 1
  echo "$repo"
}

gradle_release() {
  (cd "$ROOT" && ANDROID_HOME=$(sdk_root) run ./gradlew "$@" -Platentjam.unsigned=true)
}

cmd_android() {
  local sdk repo out version
  sdk=$(sdk_root); repo=$(reduced_ort_repo "$sdk") || exit 1; version=$(app_version); out=$(out_dir)
  # The plain command, configuration cache as gradle.properties sets it: the command that is tested is the one shipped.
  gradle_release :androidApp:clean :androidApp:assembleRelease "-Platentjam.reducedOrtRepo=$repo"
  [ "${LATENTJAM_DRY_RUN:-0}" = 1 ] && return
  rm -f "$out"/*.apk "$out/apks.json" "$out/SHA256SUMS.unsigned"
  local apk abi short
  for apk in "$ROOT"/androidApp/build/outputs/apk/release/*-release-unsigned.apk; do
    abi=$(basename "$apk" | sed -nE 's/.*-(arm64-v8a|armeabi-v7a|x86_64|x86)-release-unsigned\.apk$/\1/p')
    case "$abi" in arm64-v8a) short=arm64;; armeabi-v7a) short=armv7;; x86_64) short=x86_64;; *) die "unexpected $apk";; esac
    cp "$apk" "$out/LatentJam-v$version-$short-unsigned.apk"
  done
  python3 "$ROOT/tools/ci/apk_inventory.py" "$out" --output "$out/apks.json" --abis arm64-v8a armeabi-v7a x86_64
  (cd "$out" && shasum -a 256 ./*-unsigned.apk > SHA256SUMS.unsigned)
  echo "$out"
}

cmd_bundle() {
  local sdk repo out version
  sdk=$(sdk_root); repo=$(reduced_ort_repo "$sdk") || exit 1; version=$(app_version); out=$(out_dir)
  gradle_release :androidApp:bundleRelease -Platentjam.bundle=true "-Platentjam.reducedOrtRepo=$repo"
  [ "${LATENTJAM_DRY_RUN:-0}" = 1 ] && return
  cp "$ROOT/androidApp/build/outputs/bundle/release/androidApp-release.aab" "$out/LatentJam-v$version-unsigned.aab"
  echo "$out"
}

cmd_ios() {
  [ "$(uname)" = Darwin ] || die "ios builds need macOS"
  local out version work
  version=$(app_version); out=$(out_dir); work=${TMPDIR:-/tmp}/latentjam-ios-$version
  # A release IPA comes from a commit: never from uncommitted work (LATENTJAM_IOS_DIRTY=1 for a test build).
  if [ "${LATENTJAM_IOS_DIRTY:-0}" != 1 ] && [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]; then
    die "uncommitted changes: commit them, or LATENTJAM_IOS_DIRTY=1 for a test build"
  fi
  (cd "$ROOT/iosApp" && LANG=en_US.UTF-8 run pod install --deployment)
  run xcodebuild -workspace "$ROOT/iosApp/iosApp.xcworkspace" -scheme iosApp -configuration Release \
    -destination 'generic/platform=iOS' -derivedDataPath "$work/dd" CODE_SIGNING_ALLOWED=NO build
  [ "${LATENTJAM_DRY_RUN:-0}" = 1 ] && return
  rm -rf "$work/ipa"; mkdir -p "$work/ipa/Payload"
  cp -R "$work/dd/Build/Products/Release-iphoneos/LatentJam.app" "$work/ipa/Payload/"
  rm -f "$out/LatentJam-v$version-unsigned.ipa"
  (cd "$work/ipa" && zip -qry "$out/LatentJam-v$version-unsigned.ipa" Payload)
  rm -rf "$work"
  echo "$out"
}

# F-Droid: runs in androidApp/ (the recipe's subdir) after srclibs and before the scanner. Writes only
# build/reduced-ort/maven inside the tree; the ORT work dir and download mirror live in $HOME/.cache (on disk, outside
# the scanned tree) and are deleted afterwards.
cmd_fdroid_prebuild() {
  local abi=${1:-}
  case "$abi" in armeabi-v7a|x86|x86_64) [ $# -eq 1 ] || die "fdroid-prebuild $abi takes no other arguments";;
    arm64-v8a) [ $# -eq 4 ] || die "fdroid-prebuild arm64-v8a ORT_SOURCE NDK SDK";;
    *) die "fdroid-prebuild: unknown ABI '$abi'";; esac
  run sdkmanager 'cmake;3.22.1'
  [ "$abi" = arm64-v8a ] || return 0
  local source=$2 ndk=$3 sdk=$4 work=$HOME/.cache/latentjam-ort
  verify_ort_source "$source"
  run sdkmanager 'cmake;3.31.6'
  PATH="$sdk/cmake/3.31.6/bin:$PATH" LATENTJAM_ORT_WORK="$work/work" LATENTJAM_ORT_MIRROR="$work/mirror" \
    JOBS=${JOBS:-$(ort_jobs)} run bash "$ROOT/tools/runtime/ort_source_build.sh" "$source" "$ndk" "$sdk"
  run rm -rf "$work"
}

cmd_ort_source() {
  [ $# -eq 1 ] && [ -n "$1" ] || die "ort-source DIR"
  [ ! -e "$1" ] || die "$1 exists: name a directory that does not"
  ( clone_ort_source "$1" ) || { rm -rf "$1"; exit 1; }
}

apksigner_bin() { echo "$(sdk_root)/build-tools/$BUILD_TOOLS/apksigner"; }

# Exactly one signer, APK Signature Scheme v2, the release certificate, 16 KiB-aligned native libraries.
cmd_verify_signed() {
  local apk out digest tools
  tools="$(sdk_root)/build-tools/$BUILD_TOOLS"
  for apk in "$@"; do
    out=$("$tools/apksigner" verify --verbose --print-certs "$apk")
    grep -qx 'Number of signers: 1' <<<"$out" || die "$apk: not exactly one signer"
    grep -qx 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$out" || die "$apk: no v2 signature"
    digest=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$out" | tr -d ': ' | tr 'A-F' 'a-f')
    [ "$digest" = "$(tr -d ': ' <<<"$RELEASE_CERT_SHA256" | tr 'A-F' 'a-f')" ] || die "$apk: signed with $digest, not the release key"
    "$tools/zipalign" -c -P 16 -v 4 "$apk" > /dev/null || die "$apk: not 16 KiB aligned"
    echo "$apk: release key, v2, aligned"
  done
}

# Mac only: apksigner prompts for the password itself; it never passes through an argument, a variable or a file.
cmd_sign() {
  local dir=${1:-} resign=0 apk signed count=0 key=${LATENTJAM_RELEASE_KEYSTORE:-$HOME/Documents/LJ/keys/latentjam-release.p12}
  [ -d "$dir" ] || die "sign DIR [--resign]"
  [ "${2:-}" = --resign ] && resign=1
  [ -t 0 ] || die "sign asks for the keystore password: run it in your own terminal"
  [ -f "$key" ] || die "no keystore at $key"
  # The two APKs GitHub releases carry, named as every release so far (LatentJam-v<version>-arm64.apk).
  # --resign also takes a phone test build as Gradle names it (androidApp-arm64-v8a-release.apk).
  local candidates=("$dir"/*-arm64-unsigned.apk "$dir"/*-armv7-unsigned.apk)
  [ $resign = 1 ] && candidates+=("$dir"/*-release.apk)
  for apk in "${candidates[@]}"; do
    [ -f "$apk" ] || continue
    count=$((count + 1))
    if [ $resign = 0 ] && "$(apksigner_bin)" verify "$apk" > /dev/null 2>&1; then
      die "$apk is already signed (--resign replaces a debug signature, for phone test builds only)"
    fi
    signed="${apk%-unsigned.apk}"; signed="${signed%.apk}.apk"
    [ "$signed" != "$apk" ] || signed="${apk%.apk}-signed.apk"
    "$(apksigner_bin)" sign --ks "$key" --ks-type PKCS12 --ks-key-alias release \
      --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false \
      --out "$signed" "$apk"
    cmd_verify_signed "$signed"
  done
  [ $count -gt 0 ] || die "nothing to sign in $dir (expected *-arm64-unsigned.apk / *-armv7-unsigned.apk)"
}

command=${1:-}; [ $# -gt 0 ] && shift
case "$command" in
  android) cmd_android "$@";;
  bundle) cmd_bundle "$@";;
  ios) cmd_ios "$@";;
  fdroid-prebuild) cmd_fdroid_prebuild "$@";;
  ort-source) cmd_ort_source "$@";;
  sign) cmd_sign "$@";;
  verify-signed) cmd_verify_signed "$@";;
  *) sed -n '5,14p' "$0" >&2; exit 2;;
esac
