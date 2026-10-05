"""How good is each teacher's space for what LatentJam does with audio vectors, before any distillation.

    python probe.py [teacher ...]          # default: every out/<teacher> plus the shipped encoders

Spaces: out/<teacher>/<library>.npz from embed.py (one vector per layer), and the app's own encoders from the
bundle report's cache (mnv4_071 = 0.7.1, mnv4_branch = the encoder this checkout ships).

Probes, all on cosine nearest neighbours of a track's vector:
  MPD      six evaluation libraries (30 s previews): P@10 = share of the 10 nearest tracks that share a playlist
           with the seed, every track of a playlist as a seed; also with the seed's own artist removed from the
           ranking (style beyond artist identity). A layer is chosen on r1k_a + r1k_b, reported on the other four.
  owner    the owner's library (full tracks): P@10 over the 21 playlists, same-artist P@10, and next-track rank:
           for consecutive plays (under 30 minutes apart, the next one not skipped) the rank of the next track
           among every library track by cosine to the previous one (MRR, R@10, R@50).
  FMA      the 800 FMA-small test tracks, eight genres: 5-fold logistic-regression accuracy and 10-NN accuracy.
Paired bootstrap intervals (2,000 resamples over seeds) against mnv4_071.
"""
import json
import os
import sys
from pathlib import Path

import numpy as np

HERE = Path(os.environ.get("TEACHER_BENCH", "~/Documents/LJ/teacher-bench-2026-10-05")).expanduser()  # out, layers.json
WORK = Path(os.environ.get("MODEL_DIET_WORK", "~/Documents/LJ/model-diet-2026-10-04")).expanduser()
LIBS = WORK / "libraries"
MPD = ["r1k_a", "r1k_b", "r1k_c", "s1k_a", "s1k_b", "r3k"]
SELECT, HELD = MPD[:2], MPD[2:]
SHIPPED = {"mnv4_071": WORK / "baseline/ml/mnv4_audio.onnx",
           "mnv4_branch": Path(__file__).resolve().parents[4] / "androidApp/src/main/assets/ml/mnv4_audio.onnx"}
if (HERE / "encoders.json").exists():  # more app-format encoders to measure: {name: path to mnv4_audio.onnx}
    SHIPPED.update({k: Path(v).expanduser() for k, v in json.loads((HERE / "encoders.json").read_text()).items()})
RNG = np.random.default_rng(0)


def sha16(path):
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()[:16]


CENTER = False  # --center: subtract each library's mean vector first (SSL spaces are anisotropic)


def load(teacher, lib):
    """{id: [layers, dim] float32} or None. "a+b" concatenates two spaces at their chosen layers (layers.json),
    each centred and unit-length, so each part weighs the same."""
    if "+" in teacher:
        chosen = json.loads((HERE / "layers.json").read_text())
        parts = []
        for part in teacher.split("+"):
            data = load(part, lib)
            if data is None:
                return None
            layer = chosen.get(part, {}).get("layer", 0)
            ids = list(data.keys())
            X = np.stack([data[i][layer] for i in ids])
            parts.append(dict(zip(ids, unit(X - X.mean(0)))))
        ids = [i for i in parts[0] if all(i in p for p in parts)]
        return {i: np.concatenate([p[i] for p in parts])[None] for i in ids}
    data = _load(teacher, lib)
    if data is None or not CENTER:
        return data
    ids = list(data.keys())
    X = np.stack([data[i] for i in ids])
    X = X - X.mean(0, keepdims=True)
    return dict(zip(ids, X))


def _load(teacher, lib):
    if teacher in SHIPPED:
        path = WORK / "cache/audio" / sha16(SHIPPED[teacher]) / f"{lib}.npz"
        if not path.exists():
            return None
        z = np.load(path)
        return dict(zip(z["ids"].tolist(), z["vectors"][:, None, :].astype(np.float32)))
    path = HERE / "out" / teacher / f"{lib}.npz"
    if not path.exists():
        return None
    z = np.load(path)
    return dict(zip(z["ids"].tolist(), z["vectors"].astype(np.float32)))


def unit(x):
    return x / (np.linalg.norm(x, axis=-1, keepdims=True) + 1e-12)


def library(lib):
    return json.loads((LIBS / f"{lib}.json").read_text())


def artist_key(a):
    return (a or "").strip().lower()


def comembership(doc, vec, ids, exclude_artist=False):
    """Per-seed P@10 over playlist co-membership; seeds = rows sharing a playlist with another row with audio."""
    rows = rows_by_id[doc["name"]]
    index = {rid: i for i, rid in enumerate(ids)}
    member = np.zeros((len(ids), len(doc["groups"])), np.float32)
    for g, group in enumerate(doc["groups"]):
        for r in group:
            i = index.get(doc["rows"][r]["id"])
            if i is not None:
                member[i, g] = 1
    rel = (member @ member.T) > 0
    np.fill_diagonal(rel, False)
    seeds = np.where(rel.any(1))[0]
    X = unit(vec)
    S = X @ X.T
    np.fill_diagonal(S, -np.inf)
    if exclude_artist:
        artists = np.array([artist_key(rows[rid]["artist"]) for rid in ids])
        S = np.where(artists[:, None] == artists[None, :], -np.inf, S)
    top = np.argpartition(-S[seeds], 10, axis=1)[:, :10]
    hits = rel[seeds[:, None], top].mean(1)
    return {ids[i]: float(h) for i, h in zip(seeds, hits)}


rows_by_id = {}


def owner_probes(vec, ids):
    doc = library("listener")
    rows = {r["id"]: r for r in doc["rows"]}
    index = {rid: i for i, rid in enumerate(ids)}
    X = unit(vec)
    S = X @ X.T
    np.fill_diagonal(S, -np.inf)
    out = {}
    out["playlists"] = comembership(doc, vec, ids)
    artists = np.array([artist_key(rows[rid]["artist"]) for rid in ids])
    same = {}
    for i, rid in enumerate(ids):
        others = (artists == artists[i]).sum() - 1
        if not artists[i] or others < 1:
            continue
        top = np.argpartition(-S[i], 10)[:10]
        same[rid] = (artists[top] == artists[i]).mean()
    out["artist"] = same
    hist = sorted(doc["history"], key=lambda e: e["ts"])
    ranks = {}
    for a, b in zip(hist, hist[1:]):
        if b["ts"] - a["ts"] > 30 * 60 * 1000 or a["id"] == b["id"] or b.get("skipped"):
            continue
        if a["id"] not in index or b["id"] not in index:
            continue
        s = S[index[a["id"]]]
        rank = int((s > s[index[b["id"]]]).sum()) + 1
        ranks[f"{a['ts']}"] = rank
    out["next"] = ranks
    return out


def fma_probes(vec, ids):
    from sklearn.linear_model import LogisticRegression
    from sklearn.model_selection import StratifiedKFold, cross_val_predict
    doc = library("fma_small_test")
    genre = {r["id"]: r["genre"] for r in doc["rows"]}
    y = np.array([genre[i] for i in ids])
    X = unit(vec)
    pred = cross_val_predict(LogisticRegression(max_iter=2000, C=1.0), X, y,
                             cv=StratifiedKFold(5, shuffle=True, random_state=0))
    lin = (pred == y).astype(float)
    S = X @ X.T
    np.fill_diagonal(S, -np.inf)
    knn = []
    for i in range(len(ids)):
        top = np.argpartition(-S[i], 10)[:10]
        values, counts = np.unique(y[top], return_counts=True)
        knn.append(float(values[counts.argmax()] == y[i]))
    return dict(zip(ids, lin)), dict(zip(ids, knn))


def boot(a, b):
    """mean(a - b) over shared keys with a 95 % paired bootstrap interval."""
    keys = sorted(set(a) & set(b))
    d = np.array([a[k] - b[k] for k in keys], float)
    if len(d) == 0:
        return float("nan"), float("nan"), float("nan")
    means = d[RNG.integers(0, len(d), (2000, len(d)))].mean(1)
    return d.mean(), np.percentile(means, 2.5), np.percentile(means, 97.5)


def main():
    global CENTER
    if "--center" in sys.argv:
        sys.argv.remove("--center")
        CENTER = True
    teachers = sys.argv[1:] or (list(SHIPPED) + sorted(p.name for p in (HERE / "out").iterdir() if p.is_dir()))
    for lib in MPD + ["listener", "fma_small_test"]:
        rows_by_id[lib] = {r["id"]: r for r in library(lib)["rows"]}
    results = {}
    for t in teachers:
        data = {lib: load(t, lib) for lib in MPD}
        if any(v is None for v in data.values()):
            print(f"{t}: missing MPD libraries, skipped")
            continue
        layers = next(iter(data["r1k_a"].values())).shape[0]
        best, best_layer = -1, 0
        per_layer = []
        for layer in range(layers):
            score = np.mean([np.mean(list(comembership(library(lib), np.stack([v[layer] for v in data[lib].values()]),
                                                       list(data[lib].keys())).values())) for lib in SELECT])
            per_layer.append(score)
            if score > best:
                best, best_layer = score, layer
        r = {"layer": best_layer, "layers": layers, "select": best, "per_layer": per_layer}
        for name, excl in (("mpd", False), ("mpd_xa", True)):
            seeds = {}
            for lib in HELD:
                ids = list(data[lib].keys())
                p = comembership(library(lib), np.stack([data[lib][i][best_layer] for i in ids]), ids, excl)
                seeds.update({f"{lib}:{k}": v for k, v in p.items()})
            r[name] = seeds
        own = load(t, "listener")
        if own:
            ids = list(own.keys())
            r["owner"] = owner_probes(np.stack([own[i][best_layer] for i in ids]), ids)
        fma = load(t, "fma_small_test")
        if fma:
            ids = list(fma.keys())
            r["fma_lin"], r["fma_knn"] = fma_probes(np.stack([fma[i][best_layer] for i in ids]), ids)
        results[t] = r
    base = results.get("mnv4_071")
    print(f"{'space':18s} {'layer':>7s} {'sel P@10':>8s} | {'MPD P@10 (held-out)':>28s} | {'MPD P@10, other artists':>28s}"
          f" | {'owner pl P@10':>22s} | {'owner artist':>22s} | {'owner next MRR':>22s} | {'FMA lin':>20s} | {'FMA 10-NN':>20s}")

    def cell(r, key, sub=None, fmt="{:.3f}", scale=1.0):
        a = r.get(key) if sub is None else (r.get(key) or {}).get(sub)
        if not a:
            return f"{'—':>22s}"
        mean = np.mean(list(a.values())) * scale
        if base is None or r is base:
            return f"{fmt.format(mean):>22s}"
        b = base.get(key) if sub is None else (base.get(key) or {}).get(sub)
        if not b:
            return f"{fmt.format(mean):>22s}"
        d, lo, hi = boot(a, b)
        return f"{fmt.format(mean)} ({d * scale:+.3f} [{lo * scale:+.3f},{hi * scale:+.3f}])"

    for r in results.values():
        nxt = r.get("owner", {}).get("next")
        if nxt:
            r["owner"]["mrr"] = {k: 1.0 / v for k, v in nxt.items()}
    for t, r in results.items():
        nxt = r.get("owner", {}).get("next")
        print(f"{t:18s} {r['layer']:>3d}/{r['layers']:<3d} {r['select']:8.3f} | {cell(r, 'mpd'):>28s} | "
              f"{cell(r, 'mpd_xa'):>28s} | {cell(r, 'owner', 'playlists')} | {cell(r, 'owner', 'artist')} | "
              f"{cell(r, 'owner', 'mrr')} | {cell(r, 'fma_lin')} | {cell(r, 'fma_knn')}")
        if nxt:
            ranks = np.array(list(nxt.values()))
            print(f"{'':18s} owner next-track: {len(ranks)} transitions, R@10 {np.mean(ranks <= 10):.3f}, "
                  f"R@50 {np.mean(ranks <= 50):.3f}, median rank {np.median(ranks):.0f}")
    chosen = json.loads((HERE / "layers.json").read_text()) if (HERE / "layers.json").exists() else {}
    if not CENTER:  # the layer each space is read at, for "a+b" combinations
        chosen.update({t: {"layer": r["layer"], "per_layer": r["per_layer"]} for t, r in results.items() if "+" not in t})
        json.dump(chosen, open(HERE / "layers.json", "w"), indent=1)


if __name__ == "__main__":
    main()
