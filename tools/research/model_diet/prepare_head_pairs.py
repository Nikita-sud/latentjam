"""Build paired head-training embeddings from licensed FMA training/validation tracks.

Official test tracks are never included. Each output NPZ has teacher/student [N,960], ids,
validation mask and genre names. Models use the app's three-window aggregation.
"""

import argparse
import hashlib
import json
import multiprocessing as mp
import subprocess
import time
import zipfile
from pathlib import Path
import numpy as np

_SESSIONS = {}
_ZIP = None


def init(models, archive, custom_lib):
    import onnxruntime as ort

    global _ZIP
    _ZIP = zipfile.ZipFile(archive) if archive else None
    for name, path in models.items():
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        so.inter_op_num_threads = 1
        so.log_severity_level = 3
        if custom_lib:
            so.register_custom_ops_library(custom_lib)
        _SESSIONS[name] = ort.InferenceSession(
            path, so, providers=["CPUExecutionProvider"]
        )


def embed(row):
    try:
        if row["member"]:
            data = _ZIP.read(row["member"])
            source = "pipe:0"
        else:
            data = None
            source = row["path"]
        p = subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-threads",
                "1",
                "-i",
                source,
                "-f",
                "f32le",
                "-ac",
                "1",
                "-ar",
                "32000",
                "-",
            ],
            input=data,
            capture_output=True,
            check=True,
        )
        w = np.frombuffer(p.stdout, np.float32)
        win = 320000
        if len(w) < win:
            w = np.pad(w, (0, win - len(w)))
        windows = [
            w[int((len(w) - win) * f) : int((len(w) - win) * f) + win]
            for f in [0.2, 0.5, 0.8]
        ]
        out = {}
        for name, s in _SESSIONS.items():
            v = sum(s.run(None, {"waveform": x[None].copy()})[0][0] for x in windows)
            v = v / np.linalg.norm(v)
            if not np.isfinite(v).all():
                raise ValueError("nonfinite vector")
            out[name] = v.astype(np.float32)
        return row["id"], out, None
    except Exception as e:
        return row["id"], None, str(e)


def permitted(text):
    s = str(text).lower()
    return not any(
        v in s for v in ["noncommercial", "non-commercial", "share", "deriv"]
    ) and s.startswith(
        ("attribution", "creative commons attribution", "cc0", "public domain")
    )


def main():
    import pandas as pd

    ap = argparse.ArgumentParser()
    ap.add_argument("--fma-root", required=True)
    ap.add_argument("--archive")
    ap.add_argument("--teacher", required=True)
    ap.add_argument("--student", action="append", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--pool", type=int, default=4)
    ap.add_argument("--lib")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    models = {"teacher": a.teacher, **dict(s.split("=", 1) for s in a.student)}
    root = Path(a.fma_root)
    tracks = pd.read_csv(
        root / "fma_metadata/tracks.csv", index_col=0, header=[0, 1], low_memory=False
    )
    members = set(zipfile.ZipFile(a.archive).namelist()) if a.archive else set()
    rows = []
    for tid, r in tracks.iterrows():
        split = str(r["set", "split"])
        if split not in ("training", "validation") or not permitted(
            r["track", "license"]
        ):
            continue
        stem = f"{tid:06d}"
        member = f"fma_large/{stem[:3]}/{stem}.mp3"
        paths = [
            root / d / stem[:3] / (stem + ".mp3") for d in ["fma_medium", "fma_small"]
        ]
        local = next((p for p in paths if p.exists()), None)
        if not local and member not in members:
            continue
        rows.append(
            dict(
                id=f"fma:{tid}",
                path=str(local) if local else None,
                member=None if local else member,
                validation=split == "validation",
                genre=str(r["track", "genre_top"]),
                license=str(r["track", "license"]),
            )
        )
    manifest = {
        "models": {
            k: hashlib.sha256(Path(v).read_bytes()).hexdigest()
            for k, v in models.items()
        },
        "rows": rows,
    }
    manifest_path = out / "manifest.json"
    if manifest_path.exists() and json.loads(manifest_path.read_text()) != manifest:
        raise ValueError("cache manifest changed")
    manifest_path.write_text(json.dumps(manifest, indent=1))
    known = {}
    cache = out / "vectors.npz"
    if cache.exists():
        z = np.load(cache)
        known = {
            tid: {n: z[n][i] for n in models} for i, tid in enumerate(z["ids"].tolist())
        }

    def save():
        ids = sorted(known)
        if not ids:
            raise ValueError("No tracks were embedded successfully")
        np.savez(
            cache, ids=ids, **{n: np.stack([known[i][n] for i in ids]) for n in models}
        )

    todo = [r for r in rows if r["id"] not in known]
    start = time.time()
    print(
        f"{len(rows)} tracks, {sum(r['validation'] for r in rows)} validation, {len(todo)} uncached",
        flush=True,
    )
    # Fail once in the parent if a model/library is invalid, instead of respawning workers indefinitely.
    init(models, a.archive, a.lib)
    with mp.get_context("spawn").Pool(
        a.pool, initializer=init, initargs=(models, a.archive, a.lib)
    ) as pool:
        for i, (tid, vec, error) in enumerate(
            pool.imap_unordered(embed, todo, chunksize=8), 1
        ):
            if vec is not None:
                known[tid] = vec
            else:
                print(f"skipped {tid}: {error}", flush=True)
            if i % 250 == 0:
                save()
                print(f"{i}/{len(todo)} {time.time() - start:.0f}s", flush=True)
    save()
    rows = [r for r in rows if r["id"] in known]
    for name in models:
        if name == "teacher":
            continue
        np.savez(
            out / f"{name}.npz",
            teacher=np.stack([known[r["id"]]["teacher"] for r in rows]),
            student=np.stack([known[r["id"]][name] for r in rows]),
            ids=[r["id"] for r in rows],
            validation=np.array([r["validation"] for r in rows]),
            genres=[r["genre"] for r in rows],
        )
    print("finished", len(rows), flush=True)


if __name__ == "__main__":
    main()
