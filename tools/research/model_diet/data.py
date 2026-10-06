"""Loads the recorded SMART-net inputs (record.py) with the library features they refer to."""
import glob
import json
from pathlib import Path

import numpy as np

from paths import WORK as ROOT
FEATURES = ROOT / "runs" / "features" / "2ad669b58191522a"  # the 0.7.1 baseline bundle's features


def library_matrix(name, features=FEATURES):
    """[n, 1344] candidate vectors as ScorerPacking builds them: audio ⊕ text (zeros when absent)."""
    folder = next(Path(features).glob(f"{name}.*"))
    audio = np.fromfile(folder / "audio.f32", "<f4").reshape(-1, 960)
    text = np.fromfile(folder / "text.f32", "<f4").reshape(-1, 384)
    text = np.where(np.isfinite(text).all(1, keepdims=True), text, 0)
    return np.concatenate([audio, text], 1).astype(np.float32)


def assert_paired_rows(name, student):
    old = next(Path(FEATURES).glob(f"{name}.*"))
    new = next(Path(student).glob(f"{name}.*"))
    if (old / "ids.txt").read_bytes() != (new / "ids.txt").read_bytes():
        raise ValueError(f"{name}: student and teacher track rows differ")


def split_audit(libraries, evaluation_libraries=None):
    """Record catalog overlap; held-out playlists do not imply held-out track identities."""
    evaluation_libraries = evaluation_libraries or ["r1k_a", "r1k_b", "r1k_c", "s1k_a", "s1k_b", "r3k", "listener"]
    training = set()
    for name in libraries:
        folder = next(Path(FEATURES).glob(f"{name}.*"))
        training.update((folder / "ids.txt").read_text().splitlines())
    overlap, missing = {}, []
    for name in evaluation_libraries:
        path = ROOT / "libraries" / f"{name}.json"
        if not path.exists():
            missing.append(name)
            continue
        ids = {row["id"] for row in json.loads(path.read_text())["rows"]}
        overlap[name] = len(training & ids)
    return {"training_catalog_unique_tracks": len(training), "evaluation_catalog_overlap": overlap,
            "missing_evaluation_libraries": missing,
            "note": "Catalog counts include internal validation rows; shared tracks invalidate a track-disjoint claim."}


def load(folder, libraries=None, student=None, max_samples=None):
    """Concatenated recordings: e_in, e_out, s_state, s_rows (global row ids into `matrix`), s_out,
    plus which library each sample came from and the stacked candidate matrix of all libraries; with
    `student` (another bundle's features root), `student_matrix` holds that bundle's vectors, row for row."""
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
    if max_samples and len(out["s_rows"]) > max_samples:
        take = np.sort(np.random.default_rng(12).choice(len(out["s_rows"]), max_samples, replace=False))
        for key in parts[0]:
            out[key] = out[key][take]
    if student:
        for name in libs:
            assert_paired_rows(name, student)
        out["student_matrix"] = np.concatenate([library_matrix(n, student) for n in libs])
        out["offsets"] = np.cumsum([0] + [len(m) for m in mats])
    return out
