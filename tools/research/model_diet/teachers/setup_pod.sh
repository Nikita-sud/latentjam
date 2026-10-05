#!/bin/bash
# On the pod: ffmpeg and the teachers' packages, without letting pip replace the image's torch.
set -e
apt-get update -qq > /dev/null && apt-get install -y -qq ffmpeg > /dev/null
pip freeze | grep -iE "^(torch|torchaudio|torchvision)==" > /workspace/constraints.txt
cat /workspace/constraints.txt
pip install -q --break-system-packages -c /workspace/constraints.txt "transformers==4.46.3" muq dasheng nnAudio einops huggingface_hub soundfile 2>&1 | grep -v "^\s*$" | tail -3
python3 -c "import torch, torchaudio, transformers; print('torch', torch.__version__, 'torchaudio', torchaudio.__version__, 'transformers', transformers.__version__, 'cuda', torch.cuda.is_available())"
