"""Loads the recorded SMART-net inputs (record.py) with the library features they refer to."""
import glob
import json
from pathlib import Path

import numpy as np

from paths import WORK as ROOT
FEATURES = ROOT / "runs" / "features" / "2ad669b58191522a"  # the 0.7.1 baseline bundle's features


def library_matrix(name):
    """[n, 1344] candidate vectors as ScorerPacking builds them: audio ⊕ text (zeros when absent)."""
    folder = next(FEATURES.glob(f"{name}.*"))
    audio = np.fromfile(folder / "audio.f32", "<f4").reshape(-1, 960)
    text = np.fromfile(folder / "text.f32", "<f4").reshape(-1, 384)
    text = np.where(np.isfinite(text).all(1, keepdims=True), text, 0)
    return np.concatenate([audio, text], 1).astype(np.float32)


def load(folder, libraries=None):
    """Concatenated recordings: e_in, e_out, s_state, s_rows (global row ids into `matrix`), s_out,
    plus which library each sample came from and the stacked candidate matrix of all libraries."""
    parts, mats, offset, libs = [], [], 0, []
    for path in sorted(glob.glob(str(Path(folder) / "*.npz"))):
        name = Path(path).name.split(".")[0]
        if libraries and name not in libraries:
            continue
        z = np.load(path)
        if name not in libs:
            mats.append(library_matrix(name))
            libs.append(name)
            base = offset
            offset += len(mats[-1])
        else:
            base = sum(len(m) for m in mats[:libs.index(name)])
        rows = z["s_rows"].astype(np.int64)
        parts.append(dict(e_in=z["e_in"], e_out=z["e_out"], s_state=z["s_state"],
                          s_rows=np.where(rows >= 0, rows + base, -1), s_out=z["s_out"],
                          lib=np.full(len(rows), libs.index(name), np.int16)))
    out = {k: np.concatenate([p[k] for p in parts]) for k in parts[0]}
    out["matrix"] = np.concatenate(mats)
    out["libraries"] = libs
    return out
