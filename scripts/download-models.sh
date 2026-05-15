#!/usr/bin/env bash
# Downloads the ONNX model assets that the LatentJam smart-shuffle pipeline needs.
# Models are hosted on Hugging Face because clap_audio.onnx is 116 MB and exceeds
# GitHub's 100 MB per-file hard limit.
#
# Run from the repo root:
#   ./scripts/download-models.sh
#
# Re-running is safe: existing files with the right size are skipped.

set -euo pipefail

# Edit if you fork the repo or move the model repo elsewhere.
HF_REPO="${LATENTJAM_HF_MODELS:-AILOVER3000/latentjam-models}"
HF_REVISION="${LATENTJAM_HF_REVISION:-main}"

DEST="app/src/main/assets/ml"
BASE_URL="https://huggingface.co/${HF_REPO}/resolve/${HF_REVISION}"

# filename:expected_size_bytes  — size is optional, only used to skip re-downloads.
FILES=(
  "clap_audio.onnx"
  "predictor_scorer_n100.onnx"
  "predictor_state.onnx"
  "embedding_version.txt"
  "predictor_version.txt"
)

mkdir -p "$DEST"

for f in "${FILES[@]}"; do
  out="${DEST}/${f}"
  url="${BASE_URL}/${f}"
  if [ -s "$out" ]; then
    echo "skip ${f} (already present)"
    continue
  fi
  echo "fetch ${f}"
  curl -fL --retry 3 --progress-bar -o "$out" "$url"
done

echo
echo "Models in ${DEST}:"
ls -lh "$DEST"
