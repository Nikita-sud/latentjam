"""Learned-projection probe: how much playlist information a space holds, beyond its raw cosine geometry.

    python probe_proj.py SPACE [SPACE ...]       # e.g. mnv4_071 muq mnv4_071+muq

For each space (its chosen layer from layers.json, centred per library and unit-length; "a+b" concatenates two
spaces), a linear map to 256 dimensions is trained with InfoNCE on playlist co-membership: anchor tracks, a random
co-member as the positive, the batch's other positives as negatives (co-members masked). Fold A trains on r1k_a,
r1k_b and s1k_a and tests on r1k_c, s1k_b and r3k; fold B the reverse, so every library is tested once by a map
that never saw it. The map trained on all six is then applied to the owner's library (playlists, same artist,
next track), which no space has ever trained on. Every space gets the same map size, steps and early stopping
(10 % of the training playlists held out). Paired bootstrap against the first space.
"""
import sys

import numpy as np
import torch
import torch.nn.functional as F

import probe

FOLDS = [(["r1k_a", "r1k_b", "s1k_a"], ["r1k_c", "s1k_b", "r3k"]),
         (["r1k_c", "s1k_b", "r3k"], ["r1k_a", "r1k_b", "s1k_a"])]
DIM, STEPS, BATCH, TAU = 256, 3000, 256, 0.05


def space(name, lib):
    """name: a space ("a+b" concatenated at chosen layers), "x@L" for layer L, "x@avg" for the mean of all layers."""
    base, _, pick = name.partition("@")
    data = probe.load(base, lib)
    if data is None:
        return None, None
    ids = list(data.keys())
    chosen = probe.json.loads((probe.HERE / "layers.json").read_text()).get(base, {}).get("layer", 0)
    if pick == "avg":
        X = np.stack([data[i].mean(0) for i in ids]).astype(np.float32)
    else:
        layer = 0 if "+" in base else int(pick) if pick else chosen
        X = np.stack([data[i][layer] for i in ids]).astype(np.float32)
    X = X - X.mean(0, keepdims=True)
    return ids, X / (np.linalg.norm(X, axis=1, keepdims=True) + 1e-12)


def groups_of(lib, ids):
    doc = probe.library(lib)
    index = {rid: i for i, rid in enumerate(ids)}
    return [[index[doc["rows"][r]["id"]] for r in g if doc["rows"][r]["id"] in index] for g in doc["groups"]]


def train(parts, seed=0):
    """parts: [(X, groups)] -> linear map (torch) trained with InfoNCE; early stopping on held-out playlists."""
    rng = np.random.default_rng(seed)
    torch.manual_seed(seed)
    train_sets, val_sets = [], []
    for X, groups in parts:
        order = rng.permutation(len(groups))
        cut = max(1, len(groups) // 10)
        val_sets.append((X, [groups[i] for i in order[:cut]]))
        train_sets.append((X, [groups[i] for i in order[cut:]]))
    proj = torch.nn.Linear(parts[0][0].shape[1], DIM, bias=False)
    opt = torch.optim.AdamW(proj.parameters(), lr=1e-3, weight_decay=1e-2)
    tensors = [torch.from_numpy(X) for X, _ in parts]
    member_sets, matrices = [], []
    for X, groups in train_sets:
        m = {}
        M = np.zeros((len(X), len(groups)), np.float32)
        for g, members in enumerate(groups):
            for i in members:
                m.setdefault(i, set()).add(g)
                M[i, g] = 1
        member_sets.append(m)
        matrices.append(M)

    def validate():
        with torch.no_grad():
            scores = []
            for (X, groups), T in zip(val_sets, tensors):
                Y = F.normalize(proj(T), dim=1).numpy()
                for members in groups:  # each held-out playlist's tracks retrieve each other among the library
                    if len(members) < 2:
                        continue
                    S = Y[members] @ Y.T
                    S[np.arange(len(members)), members] = -np.inf
                    top = np.argpartition(-S, 10, axis=1)[:, :10]
                    scores.append(np.isin(top, members).mean())
            return float(np.mean(scores))

    best, best_state, since = -1.0, None, 0
    for step in range(STEPS):
        k = rng.integers(len(train_sets))
        X, groups = train_sets[k]
        members = member_sets[k]
        anchors = rng.choice(list(members.keys()), min(BATCH, len(members)), replace=False)
        positives = []
        for a in anchors:
            g = groups[rng.choice(sorted(members[a]))]
            choices = [i for i in g if i != a]
            positives.append(rng.choice(choices) if choices else a)
        A = F.normalize(proj(tensors[k][anchors]), dim=1)
        P = F.normalize(proj(tensors[k][positives]), dim=1)
        logits = A @ P.T / TAU
        shared = (matrices[k][anchors] @ matrices[k][positives].T) > 0  # a batch positive that is also a co-member
        np.fill_diagonal(shared, False)
        mask = torch.from_numpy(shared)
        logits = logits.masked_fill(mask, -1e4)
        target = torch.arange(len(anchors))
        loss = (F.cross_entropy(logits, target) + F.cross_entropy(logits.T, target)) / 2
        opt.zero_grad()
        loss.backward()
        opt.step()
        if step % 100 == 99:
            v = validate()
            if v > best:
                best, best_state, since = v, {k2: t.clone() for k2, t in proj.state_dict().items()}, 0
            else:
                since += 1
                if since >= 5:
                    break
    proj.load_state_dict(best_state)
    return proj, best


def project(proj, X):
    with torch.no_grad():
        return F.normalize(proj(torch.from_numpy(X)), dim=1).numpy()


def main():
    names = sys.argv[1:]
    for lib in probe.MPD + ["listener"]:
        probe.rows_by_id[lib] = {r["id"]: r for r in probe.library(lib)["rows"]}
    results = {}
    for name in names:
        mpd = {}
        for train_libs, test_libs in FOLDS:
            parts = []
            for lib in train_libs:
                ids, X = space(name, lib)
                parts.append((X, groups_of(lib, ids)))
            proj, _ = train(parts)
            for lib in test_libs:
                ids, X = space(name, lib)
                p = probe.comembership(probe.library(lib), project(proj, X), ids)
                mpd.update({f"{lib}:{k}": v for k, v in p.items()})
        parts = []
        for lib in probe.MPD:
            ids, X = space(name, lib)
            parts.append((X, groups_of(lib, ids)))
        proj, val = train(parts)
        ids, X = space(name, "listener")
        owner = probe.owner_probes(project(proj, X), ids)
        owner["mrr"] = {k: 1.0 / v for k, v in owner["next"].items()}
        results[name] = {"mpd": mpd, **owner}
        print(f"  {name}: trained (validation P@10 {val:.3f})", flush=True)
    base = results[names[0]]

    def cell(r, key):
        a = r[key]
        mean = np.mean(list(a.values()))
        if r is base:
            return f"{mean:.3f}".rjust(30)
        d, lo, hi = probe.boot(a, base[key])
        return f"{mean:.3f} ({d:+.3f} [{lo:+.3f},{hi:+.3f}])".rjust(30)

    print(f"{'space + 256-d map':24s} | {'MPD P@10 (unseen libraries)':>30s} | {'owner playlists P@10':>30s} | "
          f"{'owner same artist':>30s} | {'owner next MRR':>30s}")
    for name, r in results.items():
        ranks = np.array(list(r["next"].values()))
        print(f"{name:24s} | {cell(r, 'mpd')} | {cell(r, 'playlists')} | {cell(r, 'artist')} | {cell(r, 'mrr')}"
              f"   R@50 {np.mean(ranks <= 50):.3f}")


if __name__ == "__main__":
    main()
