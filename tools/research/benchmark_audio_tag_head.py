#!/usr/bin/env python3
"""Benchmark a semantic tag head over LatentJam's production audio embedding.

The shipped 960-d MNv4 model was distilled from the pre-classifier feature of
EfficientAT MN10.  EfficientAT's original AudioSet MLP therefore provides a
useful, already-trained multi-label head without decoding audio a second time.

This script reconstructs that head from the public MN10 checkpoint and asks the
two questions that matter before it is added to the app:

1. Does its semantic space improve held-out FMA genre neighbourhoods/clusters?
2. Does the same frozen choice generalise to the phone playlist holdout?

All tuning is done on the standard FMA validation split.  FMA test and the phone
library are used only after a single candidate has been selected.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from sklearn.cluster import KMeans
from sklearn.metrics import f1_score
from sklearn.neighbors import KNeighborsClassifier


EPS = 1e-12


@dataclass(frozen=True)
class Candidate:
    name: str
    scale: float
    subset: str
    transform: str
    tag_weight: float


def l2(matrix: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    return (matrix / np.clip(norms, EPS, None)).astype(np.float32, copy=False)


def hardswish(x: np.ndarray) -> np.ndarray:
    return x * np.clip(x + 3.0, 0.0, 6.0) / 6.0


def load_head(checkpoint: Path) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    return tuple(
        state[key].detach().cpu().float().numpy()
        for key in (
            "classifier.2.weight",
            "classifier.2.bias",
            "classifier.5.weight",
            "classifier.5.bias",
        )
    )


def load_labels(path: Path) -> list[str]:
    with path.open(newline="") as handle:
        return [row["display_name"] for row in csv.DictReader(handle)]


def matrix(frame: pd.DataFrame) -> np.ndarray:
    return l2(np.stack(frame["embedding"].map(lambda x: np.asarray(x, dtype=np.float32))))


def align_fma(store: pd.DataFrame, manifest: pd.DataFrame) -> tuple[pd.DataFrame, np.ndarray]:
    metadata = manifest.copy()
    metadata["track_id"] = metadata["fma_track_id"].map(lambda value: f"{int(value):06d}")
    joined = store.assign(track_id=store["track_id"].astype(str)).merge(
        metadata[["track_id", "genre_top", "split"]],
        on="track_id",
        how="inner",
        validate="one_to_one",
    )
    return joined, matrix(joined)


def split_indices(frame: pd.DataFrame, name: str) -> np.ndarray:
    return np.flatnonzero(frame["split"].astype(str).to_numpy() == name)


def genre_knn(
    features: np.ndarray,
    labels: np.ndarray,
    reference: np.ndarray,
    queries: np.ndarray,
    *,
    k: int = 10,
) -> tuple[float, float]:
    model = KNeighborsClassifier(n_neighbors=k, metric="cosine", weights="distance")
    model.fit(features[reference], labels[reference])
    pred = model.predict(features[queries])
    top1 = float(np.mean(pred == labels[queries]))
    macro_f1 = float(f1_score(labels[queries], pred, average="macro"))
    return top1, macro_f1


def cluster_purity(features: np.ndarray, labels: np.ndarray, rows: np.ndarray) -> float:
    k = len(np.unique(labels[rows]))
    assignments = KMeans(n_clusters=k, n_init=20, random_state=0).fit_predict(features[rows])
    correct = 0
    for cluster in range(k):
        cluster_labels = labels[rows][assignments == cluster]
        if cluster_labels.size:
            _, counts = np.unique(cluster_labels, return_counts=True)
            correct += int(counts.max())
    return correct / max(1, len(rows))


def playlist_membership(
    store: pd.DataFrame,
    playlist_manifest: pd.DataFrame,
) -> tuple[list[set[str]], dict[str, int]]:
    by_track: dict[str, set[str]] = defaultdict(set)
    for row in playlist_manifest.itertuples(index=False):
        by_track[str(row.track_id)].add(str(row.playlist_id).removeprefix("phone-"))
    membership = [by_track[str(track_id)] for track_id in store["track_id"].astype(str)]
    counts: dict[str, int] = defaultdict(int)
    for labels in membership:
        for label in labels:
            counts[label] += 1
    return membership, dict(counts)


def playlist_purity(
    features: np.ndarray,
    membership: list[set[str]],
    *,
    k: int = 10,
) -> tuple[float, dict[str, float]]:
    similarities = l2(features) @ l2(features).T
    np.fill_diagonal(similarities, -np.inf)
    neighbours = np.argpartition(-similarities, kth=k - 1, axis=1)[:, :k]
    scores: dict[str, list[float]] = defaultdict(list)
    for row, own in enumerate(membership):
        for playlist in own:
            scores[playlist].append(
                sum(playlist in membership[int(other)] for other in neighbours[row]) / k
            )
    per_playlist = {name: float(np.mean(values)) for name, values in scores.items() if values}
    return (
        float(np.mean(list(per_playlist.values()))) if per_playlist else 0.0,
        per_playlist,
    )


def tag_subset(name: str) -> np.ndarray:
    if name == "genre":
        return np.arange(216, 266)
    if name == "genre_mood":
        return np.arange(216, 283)
    if name == "music":
        return np.arange(137, 283)
    if name == "voice_music":
        return np.concatenate((np.arange(27, 37), np.arange(137, 283)))
    raise ValueError(name)


def tag_features(
    logits: np.ndarray,
    subset: np.ndarray,
    train_rows: np.ndarray,
    transform: str,
) -> np.ndarray:
    selected = logits[:, subset]
    if transform == "probability":
        selected = 1.0 / (1.0 + np.exp(-np.clip(selected, -30.0, 30.0)))
    elif transform != "logit":
        raise ValueError(transform)
    mean = selected[train_rows].mean(axis=0, keepdims=True)
    std = selected[train_rows].std(axis=0, keepdims=True)
    return l2((selected - mean) / np.clip(std, 1e-4, None))


def hybrid(raw: np.ndarray, tags: np.ndarray, tag_weight: float) -> np.ndarray:
    if tag_weight >= 1.0:
        return l2(tags)
    audio_weight = np.sqrt(max(0.0, 1.0 - tag_weight))
    semantic_weight = np.sqrt(max(0.0, tag_weight))
    return l2(np.concatenate((raw * audio_weight, tags * semantic_weight), axis=1))


def head_logits(
    embeddings: np.ndarray,
    first_projection: np.ndarray,
    first_bias: np.ndarray,
    second_projection: np.ndarray,
    second_bias: np.ndarray,
    scale: float,
) -> np.ndarray:
    hidden = hardswish(scale * first_projection + first_bias)
    return (hidden @ second_projection.T + second_bias).astype(np.float32)


def top_tags(
    logits: np.ndarray,
    labels: list[str],
    rows: list[int],
    *,
    count: int = 7,
) -> list[list[tuple[str, float]]]:
    probabilities = 1.0 / (1.0 + np.exp(-np.clip(logits[rows], -30.0, 30.0)))
    result = []
    for row in probabilities:
        order = np.argsort(-row)[:count]
        result.append([(labels[int(index)], float(row[index])) for index in order])
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--research-root",
        type=Path,
        default=Path("/Users/nichitabulgaru/Documents/LJ/latentjam-research"),
    )
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.research_root.resolve()
    fma_store = pd.read_parquet(root / "models/embed/fma_small_mnv4_distilled.parquet")
    fma_manifest = pd.read_csv(root / "data/manifests/fma_small.csv")
    personal_store = pd.read_parquet(root / "models/embed/personal_mnv4_distilled.parquet")
    playlists = pd.read_csv(root / "data/manifests/phone_playlists.csv")
    checkpoint = root / "models/efficientat/mn10_as_mAP_471.pt"
    label_path = root / "EfficientAT/metadata/class_labels_indices.csv"

    fma, raw_fma = align_fma(fma_store, fma_manifest)
    raw_personal = matrix(personal_store)
    labels = fma["genre_top"].astype(str).to_numpy()
    train = split_indices(fma, "training")
    validation = split_indices(fma, "validation")
    test = split_indices(fma, "test")
    membership, playlist_counts = playlist_membership(personal_store, playlists)

    w1, b1, w2, b2 = load_head(checkpoint)
    # This expensive projection is independent of the recovered feature norm.
    projected_fma = raw_fma @ w1.T
    projected_personal = raw_personal @ w1.T

    baseline_val = genre_knn(raw_fma, labels, train, validation)
    baseline_test = genre_knn(raw_fma, labels, train, test)
    baseline_cluster = cluster_purity(raw_fma, labels, test)
    baseline_personal, baseline_per_playlist = playlist_purity(raw_personal, membership)

    candidates: list[tuple[Candidate, float, float]] = []
    cached: dict[tuple[float, str, str], tuple[np.ndarray, np.ndarray, np.ndarray]] = {}
    for scale in (2.5, 2.9, 3.3):
        fma_logits = head_logits(raw_fma, projected_fma, b1, w2, b2, scale)
        personal_logits = head_logits(raw_personal, projected_personal, b1, w2, b2, scale)
        for subset_name in ("genre", "genre_mood", "music", "voice_music"):
            subset = tag_subset(subset_name)
            for transform in ("probability", "logit"):
                fma_tags = tag_features(fma_logits, subset, train, transform)
                # The phone holdout must use training-set statistics, not its own distribution.
                selected_fma = fma_logits[:, subset]
                if transform == "probability":
                    selected_fma = 1.0 / (
                        1.0 + np.exp(-np.clip(selected_fma, -30.0, 30.0))
                    )
                    selected_phone = 1.0 / (
                        1.0 + np.exp(-np.clip(personal_logits[:, subset], -30.0, 30.0))
                    )
                else:
                    selected_phone = personal_logits[:, subset]
                mean = selected_fma[train].mean(axis=0, keepdims=True)
                std = selected_fma[train].std(axis=0, keepdims=True)
                personal_tags = l2((selected_phone - mean) / np.clip(std, 1e-4, None))
                cached[(scale, subset_name, transform)] = (
                    fma_logits,
                    fma_tags,
                    personal_tags,
                )
                for weight in (0.25, 0.5, 0.75, 1.0):
                    features = hybrid(raw_fma, fma_tags, weight)
                    top1, macro_f1 = genre_knn(features, labels, train, validation)
                    candidates.append(
                        (
                            Candidate(
                                name=(
                                    f"audioset-{subset_name}-{transform}"
                                    f"-scale{scale:g}-w{weight:g}"
                                ),
                                scale=scale,
                                subset=subset_name,
                                transform=transform,
                                tag_weight=weight,
                            ),
                            top1,
                            macro_f1,
                        )
                    )

    # F1 breaks accuracy ties; candidate name is the deterministic final tie-break.
    candidates.sort(key=lambda item: (-item[1], -item[2], item[0].name))
    winner, winner_val_top1, winner_val_f1 = candidates[0]
    winner_logits, winner_fma_tags, winner_personal_tags = cached[
        (winner.scale, winner.subset, winner.transform)
    ]
    winner_fma = hybrid(raw_fma, winner_fma_tags, winner.tag_weight)
    winner_personal = hybrid(raw_personal, winner_personal_tags, winner.tag_weight)
    winner_test = genre_knn(winner_fma, labels, train, test)
    winner_cluster = cluster_purity(winner_fma, labels, test)
    winner_personal_purity, winner_per_playlist = playlist_purity(
        winner_personal,
        membership,
    )
    top_candidate_diagnostics = []
    for candidate, val_top1, val_f1 in candidates[:10]:
        _, candidate_fma_tags, candidate_personal_tags = cached[
            (candidate.scale, candidate.subset, candidate.transform)
        ]
        candidate_fma = hybrid(raw_fma, candidate_fma_tags, candidate.tag_weight)
        candidate_personal = hybrid(
            raw_personal,
            candidate_personal_tags,
            candidate.tag_weight,
        )
        test_top1, test_f1 = genre_knn(candidate_fma, labels, train, test)
        phone_purity, _ = playlist_purity(candidate_personal, membership)
        top_candidate_diagnostics.append(
            {
                **candidate.__dict__,
                "validation_knn_top1": val_top1,
                "validation_knn_macro_f1": val_f1,
                "test_knn_top1": test_top1,
                "test_knn_macro_f1": test_f1,
                "test_cluster_purity": cluster_purity(candidate_fma, labels, test),
                "personal_playlist_purity_at_10": phone_purity,
            }
        )

    interesting = []
    for query in (
        "MONTAGEM ALQUIMIA",
        "Vamp Anthem",
        "Bohemian Rhapsody",
        "Ancient Stones",
        "Lose Yourself",
    ):
        hits = np.flatnonzero(
            personal_store["title"].fillna("").str.contains(query, case=False, regex=False)
        )
        if len(hits):
            interesting.append(int(hits[0]))
    tag_names = load_labels(label_path)
    # Recompute phone logits at the winning scale only for qualitative inspection.
    phone_logits = head_logits(
        raw_personal,
        projected_personal,
        b1,
        w2,
        b2,
        winner.scale,
    )
    samples = []
    for row, tags in zip(interesting, top_tags(phone_logits, tag_names, interesting)):
        samples.append(
            {
                "title": str(personal_store.iloc[row].get("title") or ""),
                "artist": str(personal_store.iloc[row].get("artist") or ""),
                "top_tags": tags,
            }
        )

    result = {
        "selection_rule": "max validation 10-NN top1, then macro-F1; FMA train reference",
        "n_fma": len(fma),
        "n_personal": len(personal_store),
        "n_personal_with_playlist": sum(bool(item) for item in membership),
        "playlist_counts": playlist_counts,
        "baseline": {
            "validation_knn_top1": baseline_val[0],
            "validation_knn_macro_f1": baseline_val[1],
            "test_knn_top1": baseline_test[0],
            "test_knn_macro_f1": baseline_test[1],
            "test_cluster_purity": baseline_cluster,
            "personal_playlist_purity_at_10": baseline_personal,
            "personal_per_playlist": baseline_per_playlist,
        },
        "winner": {
            **winner.__dict__,
            "validation_knn_top1": winner_val_top1,
            "validation_knn_macro_f1": winner_val_f1,
            "test_knn_top1": winner_test[0],
            "test_knn_macro_f1": winner_test[1],
            "test_cluster_purity": winner_cluster,
            "personal_playlist_purity_at_10": winner_personal_purity,
            "personal_per_playlist": winner_per_playlist,
        },
        "top_validation_candidates": [
            {**candidate.__dict__, "validation_knn_top1": top1, "validation_knn_macro_f1": f1}
            for candidate, top1, f1 in candidates[:10]
        ],
        "top_candidate_diagnostics": top_candidate_diagnostics,
        "sample_predictions": samples,
    }

    print(json.dumps(result, indent=2, ensure_ascii=False))
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
