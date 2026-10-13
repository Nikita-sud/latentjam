#!/usr/bin/env bash
# Run only on the dedicated MOSS audit pod. Downloads public code and weights.
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
AUDIT_ROOT="${1:-/workspace/moss-region-audit}"
REPO_REVISION=ad107c7ddaa06de168a0dfbc18d3e1e6a40c0e5e
MODEL_REVISION=fce7f8304e96cc2d3398b8106456cbb2ecec3139
mkdir -p "$AUDIT_ROOT"
export HF_HUB_DISABLE_TELEMETRY=1 HF_HUB_DISABLE_XET=1
export PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1

if [[ ! -e "$AUDIT_ROOT/venv/bin/python" ]]; then
  python3 -m venv "$AUDIT_ROOT/venv"
fi
PYTHON="$AUDIT_ROOT/venv/bin/python"
"$PYTHON" -m pip install 'pip==25.3'
"$PYTHON" -m pip install --index-url https://download.pytorch.org/whl/cu128 'torch==2.9.1+cu128' 'torchaudio==2.9.1+cu128'
"$PYTHON" -m pip install -r "$SCRIPT_DIR/requirements-runtime.txt"
"$PYTHON" -m pip check
"$PYTHON" -m pip freeze --all > "$AUDIT_ROOT/environment.freeze.txt"

if [[ ! -d "$AUDIT_ROOT/MOSS-Music/.git" ]]; then
  if [[ -e "$AUDIT_ROOT/MOSS-Music" ]]; then
    echo 'Refusing existing non-git source destination' >&2; exit 1
  fi
  git init "$AUDIT_ROOT/MOSS-Music"
  git -C "$AUDIT_ROOT/MOSS-Music" remote add origin https://github.com/OpenMOSS/MOSS-Music.git
  git -C "$AUDIT_ROOT/MOSS-Music" fetch --depth 1 origin "$REPO_REVISION"
  git -C "$AUDIT_ROOT/MOSS-Music" checkout --detach FETCH_HEAD
fi
[[ "$(git -C "$AUDIT_ROOT/MOSS-Music" rev-parse HEAD)" == "$REPO_REVISION" ]]
[[ -z "$(git -C "$AUDIT_ROOT/MOSS-Music" status --porcelain -- src)" ]]

"$PYTHON" - "$AUDIT_ROOT" "$SCRIPT_DIR/source/hf-model.json" "$MODEL_REVISION" <<'PY'
import hashlib, json, pathlib, sys
from huggingface_hub import snapshot_download
root, metadata, revision = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
api = json.loads(metadata.read_text())
assert api['sha'] == revision
weights = root / 'weights'
# Explicit local dir avoids a second full copy of the 18.1 GB model.
snapshot_download('OpenMOSS-Team/MOSS-Music-8B-Instruct', revision=revision,
                  local_dir=str(weights), max_workers=4,
                  allow_patterns=['*.json', '*.txt', '*.jinja', '*.safetensors'])
for entry in api['siblings']:
    if not entry['rfilename'].endswith('.safetensors'):
        continue
    path, expected = weights / entry['rfilename'], entry['lfs']
    assert path.stat().st_size == expected['size'], path.name
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            digest.update(block)
    assert digest.hexdigest() == expected['sha256'], path.name
print('Pinned four-shard model download verified', flush=True)
PY
"$PYTHON" - "$AUDIT_ROOT/MOSS-Music" <<'PY'
import json, sys
sys.path.insert(0, sys.argv[1])
import torch, torchaudio, transformers
from src.configuration_moss_music import MossMusicConfig
from src.modeling_moss_music import MossMusicModel
from src.processing_moss_music import MossMusicProcessor
assert torch.cuda.is_available() and torch.cuda.is_bf16_supported()
print(json.dumps({'torch': torch.__version__, 'torchaudio': torchaudio.__version__,
                  'transformers': transformers.__version__, 'gpu': torch.cuda.get_device_name(0),
                  'vram_bytes': torch.cuda.get_device_properties(0).total_memory,
                  'status': 'bootstrap_ready'}), flush=True)
PY
