"""Golden cases for the app's audio tagger (AudioTagger.kt): the head's own graph (audio_tagger_head.json) in numpy on
seeded random A0-like vectors, from the int8 export the app ships.

    python3 export_tagger_golden.py HEAD_DIR OUT.tsv
"""
import json, sys
from pathlib import Path
import numpy as np

d = Path(sys.argv[1]); meta = json.loads((d / 'audio_tagger_head.json').read_text()); raw = (d / 'audio_tagger_int8.bin').read_bytes()
T = {t['name']: t for t in meta['tensors_int8']}
def tensor(n):
    t = T[n]; shape = t['shape']; size = int(np.prod(shape))
    if t['dtype'] == 'int8':
        q = np.frombuffer(raw, np.int8, size, t['offset']).reshape(shape).astype(np.float64)
        s = np.frombuffer(raw, '<f4', shape[0], t['scale_offset']).astype(np.float64)
        return q * s[:, None]
    return np.frombuffer(raw, '<f4', size, t['offset']).reshape(shape).astype(np.float64)
mean, std, W, b, M, R, rb = (tensor(n) for n in ('input_mean', 'input_std', 'fc0.weight', 'fc0.bias', 'family_descriptor_means', 'ridge.weight', 'ridge.bias'))
T_ = meta['family']['temperature']; unit = lambda v: v / np.linalg.norm(v)
rng = np.random.default_rng(20261019); lines = []
for i in range(6):
    x = (rng.standard_normal(960) * (1 + i)).astype(np.float32).astype(np.float64)
    z = (unit(x) - mean) / std; lg = W @ z + b; e = np.exp((lg - lg.max()) / T_); p = e / e.sum()
    desc = unit(unit(p @ M) + unit(R @ z + rb))
    lines.append('\t'.join([','.join(repr(float(v)) for v in x.astype(np.float32)), ','.join(repr(float(v)) for v in p),
                            ','.join(repr(float(v)) for v in desc)]))
Path(sys.argv[2]).write_text('\n'.join(lines) + '\n')
print(len(lines), 'cases')
