#!/bin/bash
# Fetches the ONNX Runtime iOS xcframework that :core:smart binds to via cinterop.
#
# Not vendored into the repository: it is 52 MB of prebuilt binary, and a public
# Apache-2.0 tree should not carry one. The version is pinned to the SAME
# release as `onnxruntime` in gradle/libs.versions.toml — the audio embeddings
# have to agree across platforms, and an operator implementation that changed
# between releases is exactly the kind of drift that is invisible until the
# equivalence gate fails.
#
# This is the FULL build, not "onnxruntime-mobile". The reduced build silently
# strips operators the audio model needs; libs.versions.toml records the same
# warning for the Android AAR.
set -euo pipefail

VERSION="${1:-1.26.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/onnxruntime"
URL="https://download.onnxruntime.ai/pod-archive-onnxruntime-c-${VERSION}.zip"

if [ -d "$DEST/onnxruntime.xcframework" ] && [ "${FORCE:-0}" != "1" ]; then
    echo "already present: $DEST/onnxruntime.xcframework  (FORCE=1 to refetch)"
    exit 0
fi

echo "fetching ONNX Runtime $VERSION for iOS"
echo "  from $URL"
mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fL --progress-bar -o "$TMP/ort.zip" "$URL"
unzip -q -o "$TMP/ort.zip" -d "$TMP/unpacked"

rm -rf "$DEST/onnxruntime.xcframework" "$DEST/Headers" "$DEST/LICENSE"
mv "$TMP/unpacked/onnxruntime.xcframework" "$DEST/"
mv "$TMP/unpacked/Headers" "$DEST/"
[ -f "$TMP/unpacked/LICENSE" ] && mv "$TMP/unpacked/LICENSE" "$DEST/LICENSE-onnxruntime.txt"

echo
echo "installed:"
echo "  $DEST/onnxruntime.xcframework   ($(du -sh "$DEST/onnxruntime.xcframework" | cut -f1))"
echo "  $DEST/Headers                   ($(ls "$DEST/Headers" | wc -l | tr -d ' ') headers)"
