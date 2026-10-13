#!/usr/bin/env python3
"""Offline geometry diagnostic; all private inputs/arrays stay outside the repo.

Requires NumPy. This is an exploratory neighbor benchmark, not a listening test,
2D map evaluation, or a reproduction of WNN. Uses only rows with both modalities.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def unit(x):
    lengths = np.linalg.norm(x, axis=1, keepdims=True)
    if np.any(lengths < 1e-12):
        raise ValueError("Cannot normalize a degenerate row")
    return x / lengths


def neighbors(x, k=15):
    scores = unit(x) @ unit(x).T
    np.fill_diagonal(scores, -np.inf)
    return np.argsort(-scores, axis=1, kind="stable")[:, :k]


def playlist_scores(nn, groups):
    return np.array([np.isin(nn[g], g).mean() for g in groups])


def agreement(first, second):
    return float(np.mean([len(set(a) & set(b)) / len(a) for a, b in zip(first, second)]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    p, out = args.input_dir, args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    audio = np.fromfile(p / "audio.f32", dtype="<f4").reshape(-1, 960).astype(float)
    meta = np.fromfile(p / "metadata.f32", dtype="<f4").reshape(-1, 384).astype(float)
    if len(audio) != len(meta) or not np.isfinite(audio).all() or not np.isfinite(meta).all():
        raise ValueError("Invalid input matrices")
    complete = (np.linalg.norm(audio, axis=1) > 1e-3) & (np.linalg.norm(meta, axis=1) > 1e-3)
    rows = np.flatnonzero(complete)
    index = {int(old): new for new, old in enumerate(rows)}
    groups = [[index[i] for i in group["members"] if i in index]
              for group in json.loads((p / "private-playlists.json").read_text())]
    groups = [g for g in groups if len(g) >= 4]
    weights = np.array(list(map(len, groups)))
    a, m = unit(audio[complete]), unit(meta[complete])
    ac, mc = a - a.mean(0), m - m.mean(0)
    au, mu = unit(ac), unit(mc)
    an, mn = neighbors(au), neighbors(mu)
    cases, scores = [], {}
    # Three named variants also get production PCA-50 probes. All other variants
    # stay full-dimensional and are not mislabeled as 2D-map measurements.
    probes = {"joint-a25", "joint-a50", "separate-a25"}
    for method in ("joint", "separate"):
        for aw in (0, .1, .25, .35, .5, .65, .8, 1):
            left, right = (ac, mc) if method == "joint" else (au, mu)
            x = unit(np.concatenate([np.sqrt(aw) * left, np.sqrt(1 - aw) * right], axis=1))
            name = f"{method}-a{int(round(100 * aw))}"
            nn = neighbors(x)
            per = playlist_scores(nn, groups)
            scores[name] = per
            item = {"variant": name, "audio_weight": aw,
                    "playlist_at15_membership_weighted": float(np.average(per, weights=weights)),
                    "playlist_at15_macro": float(per.mean()),
                    "audio_neighbor_recall_at15": agreement(nn, an),
                    "metadata_neighbor_recall_at15": agreement(nn, mn),
                    "audio_block_squared_norm_p10_p50_p90": np.quantile(np.sum(x[:, :960] ** 2, axis=1), [.1, .5, .9]).tolist()}
            if name in probes:
                # Actual production preprocessing starts from raw unit fusion for
                # joint cases. Separate-normalization candidates additionally pass
                # through the existing common preprocessing (including re-centering).
                raw = unit(np.concatenate([np.sqrt(aw) * a, np.sqrt(1 - aw) * m], axis=1)) if method == "joint" else x
                raw.astype("<f4").tofile(out / f"{name}-input.f32")
                reduced = out / f"{name}-pca50.f32"
                if reduced.exists():
                    input_sha = hashlib.sha256((out / f"{name}-input.f32").read_bytes()).hexdigest()
                    if (out / f"{name}-pca50.input-sha256").read_text().strip() != input_sha:
                        raise ValueError("PCA output belongs to different input; rerun FusionProjectionProbe")
                    z = np.fromfile(reduced, dtype="<f4").reshape(len(rows), 50)
                    if not np.isfinite(z).all():
                        raise ValueError("Invalid PCA output")
                    zn = neighbors(z)
                    item["production_pca50"] = {
                        "playlist_at15_membership_weighted": float(np.average(playlist_scores(zn, groups), weights=weights)),
                        "input_neighbor_recall_at15": agreement(nn, zn),
                        "audio_neighbor_recall_at15": agreement(zn, an),
                        "metadata_neighbor_recall_at15": agreement(zn, mn)}
            cases.append(item)
    # Check the algebra, independently of the neighbor rankings.
    fused = np.concatenate([.5 * a, np.sqrt(.75) * m], axis=1)
    cosine_identity_error = float(np.max(np.abs(fused @ fused.T - (.25 * (a @ a.T) + .75 * (m @ m.T)))))
    assert cosine_identity_error < 1e-12
    rng = np.random.default_rng(3)
    draws = rng.integers(0, len(groups), size=(4000, len(groups)))
    for item in cases:
        delta = scores[item["variant"]] - scores["joint-a25"]
        boot = (delta[draws] * weights[draws]).sum(1) / weights[draws].sum(1)
        item["versus_joint_a25"] = {"delta": float(np.average(delta, weights=weights)),
                                  "exploratory_playlist_bootstrap_95ci": np.quantile(boot, [.025, .975]).tolist()}
    result = {"scope": "Complete-case, full-space nearest neighbors; production PCA-50 where present; no t-SNE or region reassignment.",
              "source_sha256": {name: hashlib.sha256((p / name).read_bytes()).hexdigest()
                                for name in ("audio.f32", "metadata.f32", "private-playlists.json")},
              "rows": len(rows), "excluded_missing_modality": int(len(audio) - len(rows)),
              "eligible_playlists": len(groups), "playlist_memberships": int(weights.sum()),
              "cosine_identity_max_abs_error": cosine_identity_error, "variants": cases,
              "limits": ["One library; exploratory grid, no held-out selection or independent listening labels.",
                         "Playlists overlap and reflect themes/artists as well as sound; bootstrap is descriptive.",
                         "Block energy measures geometry, not semantic importance or fixed post-centering pair weights.",
                         "Neither region coverage nor 2D layout/performance is tested here.",
                         "Complete-case population differs from the full 1107-row production replay.",
                         "Separate normalization can amplify weak/noisy residuals; it is a candidate, not an automatic correction."]}
    # The exact full-library production replay also records the geometry before
    # and after joint centering. Report it separately from the complete-case grid.
    raw_path = p / "audio25-meta75-input.f32"
    centered_path = p / "audio25-meta75-centered.f32"
    if raw_path.exists() and centered_path.exists():
        raw = np.fromfile(raw_path, dtype="<f4").reshape(len(audio), 1344).astype(float)
        centered = np.fromfile(centered_path, dtype="<f4").reshape(len(audio), 1344).astype(float)
        error = float(np.max(np.abs(unit(raw - raw.mean(0)) - centered)))
        assert error < 1e-5
        share = np.sum(centered[:, :960] ** 2, axis=1) / np.sum(centered ** 2, axis=1)
        result["full_library_production_geometry"] = {
            "rows": len(audio), "complete_rows": len(rows),
            "audio_energy_complete_p10_p50_p90": np.quantile(share[complete], [.1, .5, .9]).tolist(),
            "joint_center_reproduction_max_abs_error": error,
            "source_sha256": {q.name: hashlib.sha256(q.read_bytes()).hexdigest() for q in (raw_path, centered_path)}}
    (out / "fusion-geometry-aggregates.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"rows": len(rows), "variants": [{k: v for k, v in item.items() if k in ("variant", "playlist_at15_membership_weighted", "production_pca50", "versus_joint_a25")} for item in cases]}, indent=2))


if __name__ == "__main__":
    main()
