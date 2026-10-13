#!/usr/bin/env python3
"""Geometry-only local MLP pilot, not a semantically trained/release-ready fusion model.

Keeps all personal vectors, split indices, models and coordinates in a private output directory.
Public output is summary.json and the generic script. Primary artists and exact audio duplicates
are grouped before splitting. Test tracks are excluded from training, preprocessing fits, and
checkpoint selection. Playlists are only an evaluation proxy, never a training target.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import unicodedata

import numpy as np
from scipy.spatial.distance import cdist
from sklearn.model_selection import GroupShuffleSplit
from sklearn.decomposition import PCA
import torch
from torch import nn
from torch.nn import functional as F


def unit(x):
    norm = np.linalg.norm(x, axis=1, keepdims=True)
    if np.any(norm < 1e-10):
        raise ValueError("Degenerate feature row")
    return x / norm


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def groups_for(rows, audio):
    parent = list(range(len(rows)))
    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    seen_artist, seen_audio = {}, {}
    for i, row in enumerate(rows):
        artist = unicodedata.normalize("NFKC", row.get("artist") or "").strip().casefold()
        keys = [(seen_audio, hashlib.sha256(audio[i].tobytes()).digest())]
        if artist not in ("", "unknown", "<unknown>", "various artists"):
            keys.append((seen_artist, artist))
        for seen, key in keys:
            if key in seen:
                parent[root(i)] = root(seen[key])
            else:
                seen[key] = i
    return np.array([root(i) for i in range(len(rows))])


def reference(x, query, train, k=15):
    distances = cdist(x[query], x[train], "sqeuclidean")
    distances[query[:, None] == train[None, :]] = np.inf
    order = np.argsort(distances, axis=1, kind="stable")
    ranks = np.empty_like(order)
    np.put_along_axis(ranks, order, np.arange(1, len(train) + 1)[None, :], axis=1)
    return ranks, order[:, :k]


def quality(y, query, train, ref, playlists, k=15):
    distances = cdist(y[query], y[train], "sqeuclidean")
    distances[query[:, None] == train[None, :]] = np.inf
    near = np.argsort(distances, axis=1, kind="stable")[:, :k]
    ranks, _ = ref
    selected = np.take_along_axis(ranks, near, axis=1)
    penalties = np.maximum(selected - k, 0).sum(axis=1)
    available = len(train) - np.isin(query, train).astype(int)
    trustworthy = 1 - 2 * penalties / (k * (2 * available - 3 * k + 1))
    hits, slots = 0, 0
    for group in playlists:
        queries = np.isin(query, group)
        if not np.any(queries) or not np.any(np.isin(train, group)):
            continue
        hits += np.isin(train[near[queries]], group).sum()
        slots += int(queries.sum())
    return {"neighbor_recall_at15": float(np.mean(selected <= k)),
            "cross_reference_trustworthiness_at15": float(trustworthy.mean()),
            "playlist_at15": float(hits / (slots * k)) if slots else None,
            "playlist_query_memberships": slots, "query_tracks": len(query),
            "candidate_tracks": len(train), "dimensions": int(y.shape[1])}


def interpolate(features, train, coords, k=5):
    distance = cdist(features, features[train], "sqeuclidean")
    near = np.argsort(distance, axis=1, kind="stable")[:, :k]
    weights = 1 / np.maximum(np.take_along_axis(distance, near, axis=1), 1e-8)
    weights /= weights.sum(axis=1, keepdims=True)
    y = (coords[near] * weights[:, :, None]).sum(axis=1)
    y[train] = coords
    return y


def affinities(x, perplexity=20):
    d = cdist(x, x, "sqeuclidean")
    n = len(x)
    result = np.zeros((n, n))
    target = np.log(perplexity)
    for i in range(n):
        keep = np.arange(n) != i
        di = d[i, keep]
        di = di - di.min()
        beta, low, high = 1., 0., np.inf
        for _ in range(60):
            p = np.exp(-beta * di)
            total = p.sum()
            entropy = np.log(total) + beta * np.sum(di * p) / total
            if abs(entropy - target) < 1e-5:
                break
            if entropy > target:
                low = beta
                beta = beta * 2 if not np.isfinite(high) else (beta + high) / 2
            else:
                high = beta
                beta = beta / 2 if low == 0 else (beta + low) / 2
        result[i, keep] = p / total
    result = (result + result.T) / (2 * n)
    assert np.isfinite(result).all() and abs(result.sum() - 1) < 1e-9
    return result.astype(np.float32)


class FusionMLP(nn.Module):
    def __init__(self, output_dim):
        super().__init__()
        self.audio = nn.Sequential(nn.Linear(960, 128), nn.GELU())
        self.meta = nn.Sequential(nn.Linear(384, 64), nn.GELU())
        self.joint = nn.Sequential(nn.Linear(192, 128), nn.GELU(), nn.Linear(128, output_dim))
        self.output_dim = output_dim

    def forward(self, x):
        # The blocks have independent coordinate meanings; projections are trained jointly.
        z = self.joint(torch.cat((self.audio(x[:, :960]), self.meta(x[:, 960:])), dim=1))
        return F.normalize(z, dim=1) if self.output_dim > 2 else z


def fit(x, train, val, ref_val, output_dim, seed, output, epochs):
    torch.manual_seed(seed)
    model = FusionMLP(output_dim)
    optimizer = torch.optim.AdamW(model.parameters(), lr=.002, weight_decay=1e-4)
    xt = torch.from_numpy(x[train].astype(np.float32))
    if output_dim == 2:
        target = torch.from_numpy(affinities(x[train]))
        entropy = (target * torch.log(target.clamp_min(1e-12))).sum()
        mask = 1 - torch.eye(len(train))
    else:
        target = xt @ xt.T
    # Only train and validation rows are evaluated while selecting the checkpoint.
    known = np.concatenate((train, val))
    known_t = torch.from_numpy(x[known].astype(np.float32))
    best, best_score, last_improved = None, -np.inf, 0
    history = []
    started = time.perf_counter()
    for epoch in range(1, epochs + 1):
        model.train()
        z = model(xt)
        if output_dim == 2:
            distances = (z.square().sum(1)[:, None] + z.square().sum(1)[None, :] - 2 * z @ z.T).clamp_min(0)
            q = mask / (1 + distances)
            q = q / q.sum().clamp_min(1e-12)
            loss = entropy - (target * torch.log(q.clamp_min(1e-12))).sum()
        else:
            loss = F.mse_loss(z @ z.T, target)
        if not torch.isfinite(loss):
            raise ValueError("Non-finite training loss")
        optimizer.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 5)
        optimizer.step()
        if epoch == 1 or epoch % 25 == 0:
            model.eval()
            with torch.no_grad():
                y = np.zeros((len(x), output_dim))
                y[known] = model(known_t).numpy()
            score = quality(y, val, train, ref_val, [])['neighbor_recall_at15']
            history.append({"epoch": epoch, "loss": float(loss.detach()), "validation_neighbor_recall_at15": score})
            if score > best_score:
                best_score, last_improved = score, epoch
                best = copy.deepcopy(model.state_dict())
            if epoch % 100 == 0:
                print(json.dumps({"seed": seed, "dims": output_dim, **history[-1]}), flush=True)
            if epoch >= 300 and epoch - last_improved >= 200:
                break
    model.load_state_dict(best)
    model.eval()
    with torch.no_grad():
        y = model(torch.from_numpy(x.astype(np.float32))).numpy()
    if not np.isfinite(y).all() or np.linalg.matrix_rank(y[train] - y[train].mean(0)) < 2:
        raise ValueError("Collapsed or invalid embedding")
    name = f'mlp{output_dim}-seed{seed}'
    torch.save({"state_dict": best, "dims": output_dim, "seed": seed}, output / f'{name}.pt')
    (output / f'{name}-history.json').write_text(json.dumps(history, indent=2) + '\n')
    np.save(output / f'{name}.npy', y)
    timing = []
    all_t = torch.from_numpy(x.astype(np.float32))
    with torch.no_grad():
        for _ in range(5):
            model(all_t)
        for _ in range(30):
            t = time.perf_counter(); model(all_t); timing.append(time.perf_counter() - t)
    return y, {"seed": seed, "parameters": sum(p.numel() for p in model.parameters()),
               "output_dim": output_dim, "selected_epoch": last_improved, "last_epoch": epoch,
               "training_wall_seconds": time.perf_counter() - started,
               "forward_all_rows_median_ms": float(np.median(timing) * 1000),
               "checkpoint_sha256": digest(output / f'{name}.pt')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--java-bin', type=Path, default=Path('/opt/homebrew/opt/openjdk@21/bin'))
    parser.add_argument('--seeds', type=int, nargs='+', default=[3, 17, 41])
    args = parser.parse_args()
    torch.set_num_threads(4)
    torch.use_deterministic_algorithms(True)
    p, out = args.input_dir, args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    audio = np.fromfile(p / 'audio.f32', dtype='<f4').reshape(-1, 960)
    meta = np.fromfile(p / 'metadata.f32', dtype='<f4').reshape(-1, 384)
    assert len(audio) == len(meta) and np.isfinite(audio).all() and np.isfinite(meta).all()
    complete = (np.linalg.norm(audio, axis=1) > 1e-3) & (np.linalg.norm(meta, axis=1) > 1e-3)
    old_rows = np.flatnonzero(complete)
    original = json.loads((p / 'private-cooked-metadata.json').read_text())
    assert len(original) == len(audio)
    rows = [original[i] for i in old_rows]
    audio, meta = audio[complete], meta[complete]
    raw = np.concatenate((.5 * unit(audio.astype(float)), np.sqrt(.75) * unit(meta.astype(float))), axis=1)
    groups = groups_for(rows, audio)
    train, remaining = next(GroupShuffleSplit(n_splits=1, test_size=.35, random_state=1729).split(raw, groups=groups))
    vr, tr = next(GroupShuffleSplit(n_splits=1, test_size=.6, random_state=1730).split(raw[remaining], groups=groups[remaining]))
    val, test = remaining[vr], remaining[tr]
    assert not (set(groups[train]) & set(groups[val]) or set(groups[train]) & set(groups[test]) or set(groups[val]) & set(groups[test]))
    assert min(len(train), len(val), len(test)) > 31
    mean = raw[train].mean(0)
    x = unit(raw - mean).astype(np.float32)
    np.savez(out / 'private-input-and-splits.npz', raw=raw, x=x, mean=mean, train=train, val=val, test=test, original_rows=old_rows)
    remap = {int(old): i for i, old in enumerate(old_rows)}
    playlists = [[remap[i] for i in pl['members'] if i in remap] for pl in json.loads((p / 'private-playlists.json').read_text())]
    playlists = [g for g in playlists if len(g) >= 4]
    refs = {key: reference(x, query, train) for key, query in [('train', train), ('validation', val), ('test', test)]}
    sets = {'train': train, 'validation': val, 'test': test}
    def evaluate(y):
        return {key: quality(y, query, train, refs[key], playlists) for key, query in sets.items()}
    cp = args.classpath_file.read_text().strip()
    source = Path(__file__).with_name('MapProjectionProbe.java')
    subprocess.run([str(args.java_bin / 'javac'), '-d', str(out), '-cp', cp, str(source)], check=True)
    def project(value, name):
        inp, dest = out / f'{name}-input.f32', out / f'{name}-2d.f32'
        value.astype('<f4').tofile(inp)
        result = subprocess.run([str(args.java_bin / 'java'), '-Xmx1g', '-cp', str(out) + ':' + cp,
                                 'MapProjectionProbe', str(inp), str(dest), str(value.shape[1])], check=True, capture_output=True, text=True)
        coords = np.fromfile(dest, dtype='<f4').reshape(len(value), 2)
        assert np.isfinite(coords).all()
        return coords, json.loads(result.stdout)
    coords, projection_time = project(raw[train], 'production-train')
    baseline = interpolate(x, train, coords)
    np.save(out / 'production-train-knn5.npy', baseline)
    pca = PCA(n_components=2, svd_solver='full').fit(x[train])
    linear = pca.transform(x)
    # A linear map to the same training t-SNE targets is a cheap inductive baseline.
    centered = coords - coords.mean(0)
    coefficients = x[train].T @ np.linalg.solve(x[train] @ x[train].T + np.eye(len(train)), centered)
    ridge = x @ coefficients
    report = {
        'scope': 'Primary-artist/identical-audio grouped inductive geometry pilot; no semantic supervision or release model.',
        'rows': len(x), 'excluded_missing_modality': int(len(complete) - complete.sum()),
        'splits': {k: {'tracks': len(v), 'groups': len(set(groups[v]))} for k, v in sets.items()},
        'split_seed': [1729, 1730], 'model_seeds': args.seeds,
        'preprocessing': 'Unit modalities, sqrt(.25/.75) concat, training-only corpus mean, L2; no test-fitted preprocessing.',
        'source_sha256': {n: digest(p / n) for n in ['audio.f32', 'metadata.f32', 'private-cooked-metadata.json', 'private-playlists.json']},
        'runtime': {'torch': torch.__version__, 'numpy': np.__version__, 'threads': 4, 'device': 'CPU'},
        'production_train_projection': projection_time,
        'baselines': {'full1344_reference': evaluate(x), 'pca2_train_fit': evaluate(linear),
                      'production_tsne_train_plus_knn5': evaluate(baseline), 'ridge_to_training_tsne': evaluate(ridge)},
        'models': [],
        'limits': ['Fixed 25/75 geometry is the training target; no claim of learning better musical semantics or better modality weights.',
                   'One personal library; primary artists and exact audio duplicates grouped, but aliases and all collaboration credits are not fully resolved.',
                   'Playlist labels never enter training or checkpoint selection; playlist agreement is still a weak, overlapping proxy.',
                   'Inductive metrics query the fixed training catalogue; they are not full-library transductive scores.',
                   'Timings are host CPU only, no phone benchmark. Training and projection costs are separate from forward latency.',
                   '2D compression is for display; region membership should remain in a richer space.',
                   'Personal-library pilot checkpoints are not release assets. No ListenBrainz or MPD training was performed.']}
    print(json.dumps({'split': report['splits'], 'baseline': report['baselines']['production_tsne_train_plus_knn5']['test']}), flush=True)
    for dims in [2, 64]:
        for seed in args.seeds:
            y, details = fit(x, train, val, refs['validation'], dims, seed, out, 800 if dims == 2 else 600)
            details['objective'] = 'Parametric t-SNE KL, train-only perplexity20 affinities' if dims == 2 else 'MSE of training fused cosine Gram matrix'
            details['representation'] = evaluate(y)
            if dims == 64:
                coords, timing = project(y[train], f'mlp64-seed{seed}-train')
                final = interpolate(y, train, coords)
                details['projection'] = timing
                details['final_2d'] = evaluate(final)
            else:
                final = y
                details['final_2d'] = details['representation']
            np.save(out / f'mlp{dims}-seed{seed}-final2d.npy', final)
            report['models'].append(details)
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps({'complete': f'mlp{dims}-seed{seed}', 'test': details['final_2d']['test'], 'seconds': details['training_wall_seconds']}), flush=True)
    # Full-corpus app projection is context only; it was not available to any model/checkpoint.
    full_coords, full_timing = project(raw, 'production-full')
    full_x = unit(raw - raw.mean(0))
    all_rows = np.arange(len(x))
    report['transductive_context_only'] = {'projection': full_timing,
        'metrics': quality(full_coords, all_rows, all_rows, reference(full_x, all_rows, all_rows), playlists),
        'warning': 'Uses every row in fitting, unlike the held-out experiment. Do not compare directly with test-to-training recall.'}
    (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print('DONE', flush=True)


if __name__ == '__main__':
    main()
