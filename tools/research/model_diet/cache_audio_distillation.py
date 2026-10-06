"""Cache one deterministic 10 s training crop, log-mel and deployed-teacher target per public track.
No evaluation-library audio or official FMA test tracks enter the cache. Provenance is retained.
"""

import argparse
import hashlib
import json
import multiprocessing as mp
import subprocess
import time
from pathlib import Path
import numpy as np

S = None
FRONTEND = None


def init(model, frontend, lib):
    import onnxruntime as ort

    global S, FRONTEND
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    so.log_severity_level = 3
    so.register_custom_ops_library(lib)
    S = ort.InferenceSession(model, so, providers=["CPUExecutionProvider"])
    FRONTEND = ort.InferenceSession(frontend, so, providers=["CPUExecutionProvider"])


def work(item):
    i, row = item
    try:
        p = subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-threads",
                "1",
                "-i",
                row["path"],
                "-f",
                "f32le",
                "-ac",
                "1",
                "-ar",
                "32000",
                "-",
            ],
            capture_output=True,
            check=True,
        )
        y = np.frombuffer(p.stdout, np.float32)
        if len(y) < 320000:
            raise ValueError("shorter than 10 seconds")
        # Different fixed positions across tracks; reproducible and independent of validation scores.
        fraction = (0.2, 0.5, 0.8)[
            int(hashlib.sha256(row["id"].encode()).hexdigest()[:8], 16) % 3
        ]
        offset = int((len(y) - 320000) * fraction)
        w = y[offset : offset + 320000].copy()[None]
        e = S.run(None, {"waveform": w})[0]
        mel = FRONTEND.run(None, {"waveform": w})[0]
        e = e[0] / np.linalg.norm(e[0])
        mel = mel[0]
        if not (np.isfinite(mel).all() and np.isfinite(e).all()):
            raise ValueError("nonfinite output")
        return i, mel.astype(np.float16), e.astype(np.float32), None
    except Exception as e:
        return i, None, None, str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", required=True)
    ap.add_argument("--frontend", required=True)
    ap.add_argument("--head-manifest", required=True)
    ap.add_argument("--mpd-list", required=True)
    ap.add_argument("--libraries", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--lib", required=True)
    ap.add_argument("--pool", type=int, default=4)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    excluded = set()
    for name in ["r1k_a", "r1k_b", "r1k_c", "s1k_a", "s1k_b", "r3k", "listener"]:
        doc = json.loads((Path(a.libraries) / (name + ".json")).read_text())
        excluded.update(
            str(Path(r["audio"]).resolve()) for r in doc["rows"] if r.get("audio")
        )
    rows = []
    for r in json.loads(Path(a.head_manifest).read_text())["rows"]:
        if r["path"] and str(Path(r["path"]).resolve()) not in excluded:
            rows.append(
                {"id": r["id"], "path": r["path"], "validation": r["validation"]}
            )
    mpd = json.loads(Path(a.mpd_list).read_text())
    for r in mpd:
        p = Path(r["path"])
        if str(p.resolve()) in excluded:
            raise ValueError("evaluation MPD track in training list: " + p.name)
        if not p.exists():
            continue
        ident = "mpd:" + p.stem
        rows.append(
            {
                "id": ident,
                "path": str(p),
                "validation": int(hashlib.sha256(ident.encode()).hexdigest()[:8], 16)
                % 20
                == 0,
            }
        )
    manifest = {
        "teacher_sha256": hashlib.sha256(Path(a.teacher).read_bytes()).hexdigest(),
        "frontend_sha256": hashlib.sha256(Path(a.frontend).read_bytes()).hexdigest(),
        "crop_version": 1,
        "rows": rows,
    }
    dest = out / "manifest.json"
    if dest.exists() and json.loads(dest.read_text()) != manifest:
        raise ValueError("cache provenance changed")
    dest.write_text(json.dumps(manifest, indent=1))
    # Validate sessions in the parent; a failing initializer would repeatedly respawn workers.
    init(a.teacher, a.frontend, a.lib)
    n = len(rows)
    exists = (out / "mel.npy").exists()
    mel = np.lib.format.open_memmap(
        out / "mel.npy",
        mode="r+" if exists else "w+",
        dtype=np.float16,
        shape=(n, 1, 128, 1001),
    )
    target = np.lib.format.open_memmap(
        out / "teacher.npy",
        mode="r+" if exists else "w+",
        dtype=np.float32,
        shape=(n, 960),
    )
    done = (
        np.load(out / "done.npy") if (out / "done.npy").exists() else np.zeros(n, bool)
    )
    todo = [(i, r) for i, r in enumerate(rows) if not done[i]]
    start = time.time()
    print(
        f"{n} total, {sum(r['validation'] for r in rows)} validation, {len(todo)} uncached",
        flush=True,
    )
    with mp.get_context("spawn").Pool(
        a.pool, initializer=init, initargs=(a.teacher, a.frontend, a.lib)
    ) as pool:
        for j, (i, x, y, error) in enumerate(
            pool.imap_unordered(work, todo, chunksize=8), 1
        ):
            if error:
                print(f"skipped {rows[i]['id']}: {error}", flush=True)
            else:
                mel[i] = x
                target[i] = y
                done[i] = True
            if j % 500 == 0:
                np.save(out / "done.npy", done)
                print(f"{j}/{len(todo)} {time.time() - start:.0f}s", flush=True)
    mel.flush()
    target.flush()
    np.save(out / "done.npy", done)
    print("finished", int(done.sum()), flush=True)


if __name__ == "__main__":
    main()
