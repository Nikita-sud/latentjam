#!/usr/bin/env python3
"""Leakage-safe Stage A experiment for LatentJam's mobile audio encoder.

This experiment learns a small residual metric on top of the existing
960-dimensional MNv4 embeddings:

    z = normalize((I + U V^T) x)

The learned dense transform can later be folded into the encoder's existing
bias-free Linear(1280 -> 960) projection.  Stage A therefore tests an
encoder-level change without changing the eventual mobile graph, parameter
count, output shape, or runtime.

Important:
* MPD artists and playlists are deterministically disjoint across train,
  validation, and test.
* Only train-split tracks are used by the objective and regularizers.
* Three fixed seeds are selected on validation; the test split is evaluated
  only for the selected seed.
* MPD/iTunes/Deezer data is marked research-only pending a separate rights
  review.  Passing statistical gates does not authorize shipping.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import random
import sys
import time
import unicodedata
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd
import torch
from torch import nn
from torch.nn import functional as F


DEFAULT_RESEARCH_ROOT = Path("/Users/nichitabulgaru/Documents/LJ/latentjam-research")
DEFAULT_OUT_DIR = Path(__file__).resolve().parent / "results" / "encoder_metric_stage_a"
SPLIT_NAMES = np.asarray(["train", "val", "test"], dtype=object)
METRIC_NAMES = ("recall_at_1", "recall_at_10", "recall_at_100", "ndcg_at_10", "mrr")


@dataclass(frozen=True)
class PairSet:
    query: np.ndarray
    target: np.ndarray
    playlist: np.ndarray
    cross_artist: np.ndarray

    def subset(self, mask: np.ndarray) -> "PairSet":
        return PairSet(
            query=self.query[mask],
            target=self.target[mask],
            playlist=self.playlist[mask],
            cross_artist=self.cross_artist[mask],
        )

    def __len__(self) -> int:
        return int(self.query.shape[0])


@dataclass(frozen=True)
class SplitBundle:
    track_ids: np.ndarray
    artists: np.ndarray
    embeddings: np.ndarray
    train_pairs: PairSet
    val_pairs: PairSet
    test_pairs: PairSet
    positive_codes: np.ndarray
    train_frequency: np.ndarray
    split_counts: dict[str, dict[str, int]]
    covered_adjacent_pairs: int
    repeated_pair_counts: dict[str, int]


@dataclass(frozen=True)
class EvalResult:
    summary: dict[str, Any]
    per_query: pd.DataFrame


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-root", type=Path, default=DEFAULT_RESEARCH_ROOT)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--seeds", default="17,29,43")
    parser.add_argument("--rank", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=2)
    parser.add_argument("--max-train-pairs", type=int, default=60_000)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--cross-artist-fraction", type=float, default=0.75)
    parser.add_argument("--temperature", type=float, default=0.07)
    parser.add_argument("--geometry-weight", type=float, default=0.5)
    parser.add_argument("--delta-weight", type=float, default=1e-4)
    parser.add_argument("--learning-rate", type=float, default=3e-4)
    parser.add_argument("--bootstrap-reps", type=int, default=2_000)
    parser.add_argument("--fma-sample", type=int, default=3_000)
    parser.add_argument("--threads", type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument(
        "--validation-only",
        action="store_true",
        help="Stop after train/validation selection; never read test metrics or guards.",
    )
    parser.add_argument(
        "--smoke",
        action="store_true",
        help=(
            "Fast train/validation plumbing check: 1 seed, 2k pairs, 1 epoch, "
            "200 bootstrap reps. Implies --validation-only."
        ),
    )
    return parser.parse_args()


def sha256_file(path: Path, chunk_size: int = 1 << 20) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_rows(matrix: np.ndarray) -> np.ndarray:
    matrix = np.asarray(matrix, dtype=np.float32)
    if not np.isfinite(matrix).all():
        raise FloatingPointError("embedding matrix contains NaN or infinity")
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    normalized = matrix / np.clip(norms, 1e-12, None)
    if not np.isfinite(normalized).all():
        raise FloatingPointError("row normalization produced NaN or infinity")
    return normalized


def checked_matmul(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    # NumPy 2.2 + Apple's Accelerate can emit spurious floating-point warnings
    # for finite SGEMM output. Suppress only those flags, then validate output
    # explicitly so a real numerical failure still stops the experiment.
    with np.errstate(divide="ignore", over="ignore", invalid="ignore"):
        result = left @ right
    if not np.isfinite(result).all():
        raise FloatingPointError("matrix multiplication produced NaN or infinity")
    return result


def stack_embeddings(series: pd.Series) -> np.ndarray:
    return normalize_rows(np.stack(series.to_numpy()).astype(np.float32, copy=False))


def normalize_artist(value: Any, track_id: str) -> str:
    text = "" if pd.isna(value) else str(value)
    text = unicodedata.normalize("NFKC", text).casefold()
    text = " ".join(text.split())
    return text if text else f"__track__{track_id}"


def stable_bucket(value: str) -> int:
    raw = hashlib.sha256(value.encode("utf-8")).digest()
    return int.from_bytes(raw[:8], "big") % 20


def bucket_to_split(bucket: int) -> int:
    if bucket < 14:
        return 0
    if bucket < 17:
        return 1
    return 2


def split_array(values: Iterable[str]) -> np.ndarray:
    return np.fromiter(
        (bucket_to_split(stable_bucket(str(value))) for value in values),
        dtype=np.int8,
    )


def load_embedding_frame(path: Path, metadata: bool = False) -> tuple[pd.DataFrame, np.ndarray]:
    columns = ["track_id", "embedding"]
    if metadata:
        columns.append("artist")
    frame = pd.read_parquet(path, columns=columns)
    frame["track_id"] = frame["track_id"].astype(str)
    if frame["track_id"].duplicated().any():
        raise ValueError(f"{path} contains duplicate track_id rows")
    return frame, stack_embeddings(frame["embedding"])


def make_pair_set(frame: pd.DataFrame) -> PairSet:
    return PairSet(
        query=frame["track_row"].to_numpy(dtype=np.int32, copy=True),
        target=frame["target_row"].to_numpy(dtype=np.int32, copy=True),
        playlist=frame["playlist_id"].astype(str).to_numpy(copy=True),
        cross_artist=frame["artist_key"].ne(frame["target_artist"]).to_numpy(dtype=bool),
    )


def pair_set_counts(pairs: PairSet, artists: np.ndarray) -> dict[str, int]:
    rows = np.unique(np.concatenate([pairs.query, pairs.target]))
    return {
        "pairs": len(pairs),
        "playlists": int(np.unique(pairs.playlist).size),
        "tracks": int(rows.size),
        "artists": int(np.unique(artists[rows]).size),
        "cross_artist_pairs": int(pairs.cross_artist.sum()),
    }


def build_strict_splits(session_path: Path, embedding_path: Path) -> SplitBundle:
    embedding_frame, embeddings = load_embedding_frame(embedding_path, metadata=True)
    track_ids = embedding_frame["track_id"].to_numpy(dtype=str)
    artists = np.asarray(
        [
            normalize_artist(artist, track_id)
            for artist, track_id in zip(embedding_frame["artist"], track_ids)
        ],
        dtype=object,
    )
    id_to_row = {track_id: row for row, track_id in enumerate(track_ids)}
    artist_split_by_row = split_array(artists)

    sessions = pd.read_csv(
        session_path,
        dtype={"playlist_id": "string", "track_id": "string", "position": "int32"},
    ).sort_values(["playlist_id", "position"], kind="stable")
    sessions["track_row"] = sessions["track_id"].map(id_to_row).fillna(-1).astype(np.int32)
    sessions["artist_key"] = sessions["track_id"].map(
        dict(zip(track_ids, artists, strict=True))
    )
    sessions["artist_split"] = -1
    available = sessions["track_row"].ge(0)
    sessions.loc[available, "artist_split"] = artist_split_by_row[
        sessions.loc[available, "track_row"].to_numpy(dtype=np.int32)
    ]
    unique_playlists = sessions["playlist_id"].astype(str).unique()
    playlist_split = dict(zip(unique_playlists, split_array(unique_playlists), strict=True))
    sessions["playlist_split"] = (
        sessions["playlist_id"].astype(str).map(playlist_split).astype(np.int8)
    )

    grouped = sessions.groupby("playlist_id", sort=False)
    adjacent = sessions[
        [
            "playlist_id",
            "position",
            "track_row",
            "artist_key",
            "artist_split",
            "playlist_split",
        ]
    ].copy()
    adjacent["target_row"] = grouped["track_row"].shift(-1)
    adjacent["target_position"] = grouped["position"].shift(-1)
    adjacent["target_artist"] = grouped["artist_key"].shift(-1)
    adjacent["target_artist_split"] = grouped["artist_split"].shift(-1)
    covered = (
        adjacent["track_row"].ge(0)
        & adjacent["target_row"].notna()
        & adjacent["target_row"].ge(0)
        & adjacent["target_position"].eq(adjacent["position"] + 1)
    )
    adjacent = adjacent[covered].copy()
    adjacent["target_row"] = adjacent["target_row"].astype(np.int32)
    adjacent["target_artist_split"] = adjacent["target_artist_split"].astype(np.int8)
    strict = (
        adjacent["playlist_split"].eq(adjacent["artist_split"])
        & adjacent["playlist_split"].eq(adjacent["target_artist_split"])
    )
    adjacent = adjacent[strict].copy()

    repeated_pair_counts: dict[str, int] = {}
    pairs_by_split: dict[str, PairSet] = {}
    for split_id, name in enumerate(SPLIT_NAMES):
        selected = adjacent[adjacent["playlist_split"].eq(split_id)].copy()
        repeated = selected["track_row"].eq(selected["target_row"])
        repeated_pair_counts[str(name)] = int(repeated.sum())
        selected = selected[~repeated]
        pairs_by_split[str(name)] = make_pair_set(selected)

    # Known train positives within an exact +/-3-position window.  Shifts are
    # taken on the original sequence, and the numeric position delta is
    # checked, so filtering never bridges missing or held-out rows.
    train_window_q: list[np.ndarray] = []
    train_window_t: list[np.ndarray] = []
    for distance in (1, 2, 3):
        window = sessions[["position", "track_row", "artist_split", "playlist_split"]].copy()
        window["target_row"] = grouped["track_row"].shift(-distance)
        window["target_position"] = grouped["position"].shift(-distance)
        window["target_artist_split"] = grouped["artist_split"].shift(-distance)
        keep = (
            window["playlist_split"].eq(0)
            & window["artist_split"].eq(0)
            & window["target_artist_split"].eq(0)
            & window["track_row"].ge(0)
            & window["target_row"].notna()
            & window["target_row"].ge(0)
            & window["target_position"].eq(window["position"] + distance)
            & window["track_row"].ne(window["target_row"])
        )
        kept = window[keep]
        train_window_q.append(kept["track_row"].to_numpy(dtype=np.int64))
        train_window_t.append(kept["target_row"].to_numpy(dtype=np.int64))
    window_q = np.concatenate(train_window_q)
    window_t = np.concatenate(train_window_t)
    n_tracks = len(track_ids)
    positive_codes = np.concatenate(
        [window_q * n_tracks + window_t, window_t * n_tracks + window_q]
    )
    positive_codes = np.unique(positive_codes.astype(np.int64, copy=False))

    train_rows = sessions[
        sessions["playlist_split"].eq(0)
        & sessions["artist_split"].eq(0)
        & sessions["track_row"].ge(0)
    ]["track_row"].to_numpy(dtype=np.int32)
    train_frequency = np.bincount(train_rows, minlength=n_tracks).astype(np.int64)

    split_counts = {
        name: pair_set_counts(pairs_by_split[name], artists) for name in SPLIT_NAMES
    }
    return SplitBundle(
        track_ids=track_ids,
        artists=artists,
        embeddings=embeddings,
        train_pairs=pairs_by_split["train"],
        val_pairs=pairs_by_split["val"],
        test_pairs=pairs_by_split["test"],
        positive_codes=positive_codes,
        train_frequency=train_frequency,
        split_counts=split_counts,
        covered_adjacent_pairs=int(covered.sum()),
        repeated_pair_counts=repeated_pair_counts,
    )


class ResidualMetric(nn.Module):
    def __init__(self, dim: int, rank: int, seed: int):
        super().__init__()
        generator = torch.Generator(device="cpu")
        generator.manual_seed(seed)
        scale = 0.01 / math.sqrt(rank)
        self.u = nn.Parameter(torch.randn(dim, rank, generator=generator) * scale)
        self.v = nn.Parameter(torch.zeros(dim, rank))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        transformed = x + (x @ self.u) @ self.v.T
        return F.normalize(transformed, dim=-1)

    def delta_frobenius_squared(self) -> torch.Tensor:
        # ||UV^T||_F^2 = trace((U^T U)(V^T V)).
        utu = self.u.T @ self.u
        vtv = self.v.T @ self.v
        return torch.sum(utu * vtv)


def known_positive_mask(
    query: np.ndarray,
    target: np.ndarray,
    positive_codes: np.ndarray,
    n_tracks: int,
) -> np.ndarray:
    codes = (
        query.astype(np.int64, copy=False)[:, None] * n_tracks
        + target.astype(np.int64, copy=False)[None, :]
    )
    flat = codes.ravel()
    offsets = np.searchsorted(positive_codes, flat)
    safe = np.minimum(offsets, positive_codes.size - 1)
    return (positive_codes[safe] == flat).reshape(codes.shape)


def select_epoch_pairs(
    pairs: PairSet,
    count: int,
    cross_fraction: float,
    rng: np.random.Generator,
) -> np.ndarray:
    cross = np.flatnonzero(pairs.cross_artist)
    same = np.flatnonzero(~pairs.cross_artist)
    if count <= 0 or count >= len(pairs):
        selected = np.arange(len(pairs), dtype=np.int64)
        rng.shuffle(selected)
        return selected
    wanted_cross = min(int(round(count * cross_fraction)), cross.size)
    wanted_same = min(count - wanted_cross, same.size)
    remaining = count - wanted_cross - wanted_same
    if remaining:
        extra_cross = min(remaining, cross.size - wanted_cross)
        wanted_cross += extra_cross
        wanted_same += remaining - extra_cross
    selected = np.concatenate(
        [
            rng.choice(cross, size=wanted_cross, replace=False),
            rng.choice(same, size=wanted_same, replace=False),
        ]
    )
    rng.shuffle(selected)
    return selected


def multi_positive_loss(
    logits: torch.Tensor,
    positive: torch.Tensor,
    valid: torch.Tensor,
    weight: torch.Tensor,
) -> torch.Tensor:
    floor = torch.finfo(logits.dtype).min
    all_lse = torch.logsumexp(logits.masked_fill(~valid, floor), dim=1)
    pos_lse = torch.logsumexp(logits.masked_fill(~positive, floor), dim=1)
    row_loss = all_lse - pos_lse
    finite = torch.isfinite(row_loss)
    return torch.sum(row_loss[finite] * weight[finite]) / torch.clamp(
        weight[finite].sum(), min=1e-12
    )


def train_one_seed(
    bundle: SplitBundle,
    *,
    seed: int,
    rank: int,
    epochs: int,
    max_train_pairs: int,
    batch_size: int,
    cross_fraction: float,
    temperature: float,
    geometry_weight: float,
    delta_weight: float,
    learning_rate: float,
) -> tuple[np.ndarray, np.ndarray, list[dict[str, float]]]:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    model = ResidualMetric(bundle.embeddings.shape[1], rank, seed)
    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate, weight_decay=0.0)
    x_all = torch.from_numpy(bundle.embeddings)
    frequency = bundle.train_frequency.astype(np.float64)
    history: list[dict[str, float]] = []
    n_tracks = bundle.embeddings.shape[0]

    model.train()
    for epoch in range(epochs):
        selected = select_epoch_pairs(
            bundle.train_pairs,
            max_train_pairs,
            cross_fraction,
            rng,
        )
        totals = {"loss": 0.0, "info_nce": 0.0, "geometry": 0.0, "steps": 0.0}
        for start in range(0, selected.size, batch_size):
            batch = selected[start : start + batch_size]
            if batch.size < 2:
                continue
            query_np = bundle.train_pairs.query[batch]
            target_np = bundle.train_pairs.target[batch]
            query = torch.from_numpy(query_np.astype(np.int64, copy=False))
            target = torch.from_numpy(target_np.astype(np.int64, copy=False))
            x_query = x_all[query]
            x_target = x_all[target]
            z_query = model(x_query)
            z_target = model(x_target)
            logits = (z_query @ z_target.T) / temperature

            positive_np = known_positive_mask(
                query_np, target_np, bundle.positive_codes, n_tracks
            )
            valid_np = query_np[:, None] != target_np[None, :]
            # The sampled diagonal is always a known positive by construction.
            positive_np[np.arange(batch.size), np.arange(batch.size)] = True
            positive_np &= valid_np
            positive = torch.from_numpy(positive_np)
            valid = torch.from_numpy(valid_np)

            pair_weight_np = 1.0 / np.sqrt(
                np.maximum(frequency[query_np], 1.0)
                * np.maximum(frequency[target_np], 1.0)
            )
            pair_weight_np /= max(float(pair_weight_np.mean()), 1e-12)
            pair_weight_np = np.clip(pair_weight_np, 0.25, 4.0).astype(np.float32)
            pair_weight = torch.from_numpy(pair_weight_np)

            row_loss = multi_positive_loss(logits, positive, valid, pair_weight)
            col_loss = multi_positive_loss(
                logits.T, positive.T, valid.T, pair_weight
            )
            info_nce = 0.5 * (row_loss + col_loss)

            permutation = torch.randperm(batch.size)
            baseline_cosine = torch.sum(x_query * x_query[permutation], dim=1)
            candidate_cosine = torch.sum(z_query * z_query[permutation], dim=1)
            geometry = F.mse_loss(candidate_cosine, baseline_cosine)
            delta = model.delta_frobenius_squared()
            loss = info_nce + geometry_weight * geometry + delta_weight * delta

            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            optimizer.step()

            totals["loss"] += float(loss.detach())
            totals["info_nce"] += float(info_nce.detach())
            totals["geometry"] += float(geometry.detach())
            totals["steps"] += 1.0
        steps = max(totals.pop("steps"), 1.0)
        epoch_result = {key: value / steps for key, value in totals.items()}
        epoch_result["epoch"] = float(epoch + 1)
        epoch_result["sampled_pairs"] = float(selected.size)
        history.append(epoch_result)
        print(
            f"seed={seed} epoch={epoch + 1}/{epochs} "
            f"loss={epoch_result['loss']:.5f} "
            f"nce={epoch_result['info_nce']:.5f} "
            f"geom={epoch_result['geometry']:.7f}",
            flush=True,
        )
    return (
        model.u.detach().cpu().numpy().astype(np.float32),
        model.v.detach().cpu().numpy().astype(np.float32),
        history,
    )


def transform_embeddings(matrix: np.ndarray, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    projected = checked_matmul(matrix, u)
    residual = checked_matmul(projected, v.T)
    return normalize_rows(matrix + residual)


def summarize_query_metrics(frame: pd.DataFrame) -> dict[str, Any]:
    micro = {name: float(frame[name].mean()) for name in METRIC_NAMES}
    per_playlist = frame.groupby("playlist_id", sort=False)[list(METRIC_NAMES)].mean()
    macro = {name: float(per_playlist[name].mean()) for name in METRIC_NAMES}
    return {
        "n_queries": int(len(frame)),
        "n_playlists": int(frame["playlist_id"].nunique()),
        "micro": micro,
        "macro": macro,
    }


def evaluate_pairs(
    pairs: PairSet,
    query_embeddings: np.ndarray,
    catalog_embeddings: np.ndarray,
    *,
    query_catalog_rows: np.ndarray | None = None,
    target_catalog_rows: np.ndarray | None = None,
) -> EvalResult:
    if query_catalog_rows is None:
        query_catalog_rows = pairs.query
    if target_catalog_rows is None:
        target_catalog_rows = pairs.target
    query_catalog_rows = np.asarray(query_catalog_rows, dtype=np.int32)
    target_catalog_rows = np.asarray(target_catalog_rows, dtype=np.int32)
    ranks = np.empty(len(pairs), dtype=np.int32)

    order = np.argsort(query_catalog_rows, kind="stable")
    sorted_query = query_catalog_rows[order]
    boundaries = np.r_[0, np.flatnonzero(np.diff(sorted_query)) + 1, len(order)]
    unique_query_rows = sorted_query[boundaries[:-1]]
    groups = [order[boundaries[i] : boundaries[i + 1]] for i in range(len(boundaries) - 1)]

    for block_start in range(0, unique_query_rows.size, 64):
        block_rows = unique_query_rows[block_start : block_start + 64]
        scores_block = checked_matmul(
            query_embeddings[block_rows], catalog_embeddings.T
        )
        for local, query_row in enumerate(block_rows):
            pair_indices = groups[block_start + local]
            scores = scores_block[local]
            scores[int(query_row)] = -np.inf
            targets = target_catalog_rows[pair_indices]
            target_scores = scores[targets]
            for query_target_start in range(0, targets.size, 64):
                section = slice(query_target_start, query_target_start + 64)
                section_targets = targets[section]
                section_scores = target_scores[section]
                better = np.sum(scores[None, :] > section_scores[:, None], axis=1)
                # Deterministic catalog-row tie break.
                equal_before = np.sum(
                    (scores[None, :] == section_scores[:, None])
                    & (
                        np.arange(scores.size, dtype=np.int32)[None, :]
                        < section_targets[:, None]
                    ),
                    axis=1,
                )
                ranks[pair_indices[section]] = 1 + better + equal_before

    result = pd.DataFrame(
        {
            "playlist_id": pairs.playlist,
            "cross_artist": pairs.cross_artist,
            "rank": ranks,
            "recall_at_1": ranks <= 1,
            "recall_at_10": ranks <= 10,
            "recall_at_100": ranks <= 100,
            "ndcg_at_10": np.where(ranks <= 10, 1.0 / np.log2(ranks + 1.0), 0.0),
            "mrr": 1.0 / ranks.astype(np.float64),
        }
    )
    summary = summarize_query_metrics(result)
    cross = result[result["cross_artist"]]
    same = result[~result["cross_artist"]]
    summary["cross_artist"] = summarize_query_metrics(cross) if len(cross) else None
    summary["same_artist"] = summarize_query_metrics(same) if len(same) else None
    return EvalResult(summary=summary, per_query=result)


def paired_bootstrap(
    baseline: pd.DataFrame,
    candidate: pd.DataFrame,
    *,
    reps: int,
    seed: int,
) -> dict[str, dict[str, float]]:
    if len(baseline) != len(candidate):
        raise ValueError("paired bootstrap inputs must align")
    if not np.array_equal(
        baseline["playlist_id"].to_numpy(), candidate["playlist_id"].to_numpy()
    ):
        raise ValueError("paired bootstrap playlist order differs")
    base_group = baseline.groupby("playlist_id", sort=True)[list(METRIC_NAMES)].mean()
    cand_group = candidate.groupby("playlist_id", sort=True)[list(METRIC_NAMES)].mean()
    if not base_group.index.equals(cand_group.index):
        raise ValueError("paired bootstrap playlist groups differ")
    differences = (cand_group - base_group).to_numpy(dtype=np.float64)
    rng = np.random.default_rng(seed)
    n_groups = differences.shape[0]
    samples = rng.integers(0, n_groups, size=(reps, n_groups), dtype=np.int32)
    output: dict[str, dict[str, float]] = {}
    for metric_index, metric in enumerate(METRIC_NAMES):
        draws = differences[samples, metric_index].mean(axis=1)
        output[metric] = {
            "delta": float(differences[:, metric_index].mean()),
            "ci95_low": float(np.quantile(draws, 0.025)),
            "ci95_high": float(np.quantile(draws, 0.975)),
        }
    return output


def paired_comparison(
    baseline: EvalResult,
    candidate: EvalResult,
    *,
    reps: int,
    seed: int,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "all": paired_bootstrap(
            baseline.per_query, candidate.per_query, reps=reps, seed=seed
        )
    }
    for label, mask in (
        ("cross_artist", baseline.per_query["cross_artist"].to_numpy(dtype=bool)),
        ("same_artist", ~baseline.per_query["cross_artist"].to_numpy(dtype=bool)),
    ):
        result[label] = paired_bootstrap(
            baseline.per_query[mask].reset_index(drop=True),
            candidate.per_query[mask].reset_index(drop=True),
            reps=reps,
            seed=seed + (1 if label == "cross_artist" else 2),
        )
    return result


def gini(values: np.ndarray) -> float:
    values = np.sort(np.asarray(values, dtype=np.float64))
    if values.size == 0 or values.sum() == 0:
        return 0.0
    index = np.arange(1, values.size + 1, dtype=np.float64)
    return float(
        (np.sum((2.0 * index - values.size - 1.0) * values))
        / (values.size * values.sum())
    )


def effective_rank(matrix: np.ndarray) -> float:
    centered = matrix - matrix.mean(axis=0, keepdims=True)
    covariance = checked_matmul(centered.T, centered) / max(matrix.shape[0] - 1, 1)
    eigenvalues = np.linalg.eigvalsh(covariance).clip(min=0.0)
    total = float(eigenvalues.sum())
    if total <= 0.0:
        return 0.0
    probabilities = eigenvalues[eigenvalues > 0.0] / total
    return float(np.exp(-np.sum(probabilities * np.log(probabilities))))


def geometry_guard(
    baseline: np.ndarray,
    candidate: np.ndarray,
    *,
    sample_size: int,
    seed: int,
) -> dict[str, Any]:
    rng = np.random.default_rng(seed)
    size = min(sample_size, baseline.shape[0])
    selected = np.sort(rng.choice(baseline.shape[0], size=size, replace=False))
    base = normalize_rows(baseline[selected])
    cand = normalize_rows(candidate[selected])

    base_scores = checked_matmul(base, base.T)
    cand_scores = checked_matmul(cand, cand.T)
    np.fill_diagonal(base_scores, -np.inf)
    np.fill_diagonal(cand_scores, -np.inf)
    base_neighbors = np.argpartition(-base_scores, kth=9, axis=1)[:, :10]
    cand_neighbors = np.argpartition(-cand_scores, kth=9, axis=1)[:, :10]
    overlap = np.mean(
        [
            len(set(base_neighbors[row]).intersection(cand_neighbors[row])) / 10.0
            for row in range(size)
        ]
    )
    base_occurrence = np.bincount(base_neighbors.ravel(), minlength=size)
    cand_occurrence = np.bincount(cand_neighbors.ravel(), minlength=size)

    pair_count = min(100_000, size * 40)
    pair_a = rng.integers(0, size, size=pair_count)
    pair_b = rng.integers(0, size, size=pair_count)
    unequal = pair_a != pair_b
    pair_a = pair_a[unequal]
    pair_b = pair_b[unequal]
    base_pair_cosine = np.sum(base[pair_a] * base[pair_b], axis=1)
    cand_pair_cosine = np.sum(cand[pair_a] * cand[pair_b], axis=1)

    baseline_rank = effective_rank(base)
    candidate_rank = effective_rank(cand)
    baseline_gini = gini(base_occurrence)
    candidate_gini = gini(cand_occurrence)
    baseline_p99 = float(np.quantile(base_occurrence, 0.99))
    candidate_p99 = float(np.quantile(cand_occurrence, 0.99))
    return {
        "sample_size": size,
        "effective_rank": {
            "baseline": baseline_rank,
            "candidate": candidate_rank,
            "ratio": candidate_rank / max(baseline_rank, 1e-12),
        },
        "pair_cosine": {
            "baseline_mean": float(base_pair_cosine.mean()),
            "candidate_mean": float(cand_pair_cosine.mean()),
            "mean_shift": float(cand_pair_cosine.mean() - base_pair_cosine.mean()),
            "baseline_std": float(base_pair_cosine.std()),
            "candidate_std": float(cand_pair_cosine.std()),
            "std_ratio": float(
                cand_pair_cosine.std() / max(base_pair_cosine.std(), 1e-12)
            ),
        },
        "hubness_top10": {
            "baseline_gini": baseline_gini,
            "candidate_gini": candidate_gini,
            "gini_ratio": candidate_gini / max(baseline_gini, 1e-12),
            "baseline_p99": baseline_p99,
            "candidate_p99": candidate_p99,
            "p99_ratio": candidate_p99 / max(baseline_p99, 1e-12),
        },
        "mean_top10_neighbor_overlap": float(overlap),
    }


def phone_pair_set(
    playlist_path: Path,
    catalog_ids: np.ndarray,
    artists: np.ndarray,
) -> PairSet:
    id_to_row = {track_id: row for row, track_id in enumerate(catalog_ids)}
    frame = pd.read_csv(
        playlist_path,
        dtype={"playlist_id": "string", "track_id": "string", "position": "int32"},
    ).sort_values(["playlist_id", "position"], kind="stable")
    frame["track_row"] = frame["track_id"].map(id_to_row)
    grouped = frame.groupby("playlist_id", sort=False)
    frame["target_row"] = grouped["track_row"].shift(-1)
    frame["target_artist"] = grouped["track_id"].shift(-1).map(
        dict(zip(catalog_ids, artists, strict=True))
    )
    frame["artist_key"] = frame["track_id"].map(
        dict(zip(catalog_ids, artists, strict=True))
    )
    kept = frame[
        frame["track_row"].notna() & frame["target_row"].notna()
    ].copy()
    kept["track_row"] = kept["track_row"].astype(np.int32)
    kept["target_row"] = kept["target_row"].astype(np.int32)
    return make_pair_set(kept)


def per_playlist_worst_regression(
    baseline: pd.DataFrame, candidate: pd.DataFrame, min_queries: int
) -> dict[str, Any]:
    base = baseline.groupby("playlist_id")["recall_at_100"].agg(["mean", "size"])
    cand = candidate.groupby("playlist_id")["recall_at_100"].mean()
    eligible = base[base["size"] >= min_queries].copy()
    eligible["candidate"] = cand.reindex(eligible.index)
    eligible["delta"] = eligible["candidate"] - eligible["mean"]
    if eligible.empty:
        return {"min_queries": min_queries, "eligible_playlists": 0, "worst": None}
    worst_name = str(eligible["delta"].idxmin())
    return {
        "min_queries": min_queries,
        "eligible_playlists": int(len(eligible)),
        "worst": {
            "playlist_id": worst_name,
            "delta": float(eligible.loc[worst_name, "delta"]),
            "queries": int(eligible.loc[worst_name, "size"]),
        },
    }


def relative_gain(baseline: float, candidate: float) -> float:
    return (candidate - baseline) / max(abs(baseline), 1e-12)


def apply_stage_a_gates(
    baseline_test: EvalResult,
    candidate_test: EvalResult,
    test_comparison: dict[str, Any],
    phone_comparison: dict[str, Any],
    phone_worst: dict[str, Any],
    fma_guard: dict[str, Any],
) -> dict[str, Any]:
    base_macro = baseline_test.summary["macro"]
    cand_macro = candidate_test.summary["macro"]
    base_cross = baseline_test.summary["cross_artist"]["macro"]
    cand_cross = candidate_test.summary["cross_artist"]["macro"]
    same_delta = test_comparison["same_artist"]["recall_at_100"]["delta"]
    worst_delta = (
        0.0 if phone_worst["worst"] is None else phone_worst["worst"]["delta"]
    )
    checks = {
        "strict_macro_r100_relative_gain_ge_5pct": relative_gain(
            base_macro["recall_at_100"], cand_macro["recall_at_100"]
        )
        >= 0.05,
        "strict_macro_r100_ci_low_gt_0": test_comparison["all"]["recall_at_100"][
            "ci95_low"
        ]
        > 0.0,
        "cross_artist_r100_relative_gain_ge_5pct": relative_gain(
            base_cross["recall_at_100"], cand_cross["recall_at_100"]
        )
        >= 0.05,
        "cross_artist_r100_ci_low_ge_0": test_comparison["cross_artist"][
            "recall_at_100"
        ]["ci95_low"]
        >= 0.0,
        "cross_artist_mrr_relative_gain_ge_3pct": relative_gain(
            base_cross["mrr"], cand_cross["mrr"]
        )
        >= 0.03,
        "cross_artist_mrr_ci_low_ge_0": test_comparison["cross_artist"]["mrr"][
            "ci95_low"
        ]
        >= 0.0,
        "r10_ci_low_gt_minus_0_002": test_comparison["all"]["recall_at_10"][
            "ci95_low"
        ]
        > -0.002,
        "same_artist_r100_delta_ge_minus_0_01": same_delta >= -0.01,
        "phone_r100_ci_low_gt_minus_0_02": phone_comparison["all"][
            "recall_at_100"
        ]["ci95_low"]
        > -0.02,
        "phone_mrr_ci_low_gt_minus_0_02": phone_comparison["all"]["mrr"][
            "ci95_low"
        ]
        > -0.02,
        "phone_large_playlist_worst_delta_gt_minus_0_05": worst_delta > -0.05,
        "effective_rank_ratio_ge_0_95": fma_guard["effective_rank"]["ratio"] >= 0.95,
        "cosine_mean_shift_abs_le_0_02": abs(
            fma_guard["pair_cosine"]["mean_shift"]
        )
        <= 0.02,
        "cosine_std_ratio_ge_0_90": fma_guard["pair_cosine"]["std_ratio"] >= 0.90,
        "hubness_gini_ratio_le_1_10": fma_guard["hubness_top10"]["gini_ratio"]
        <= 1.10,
        "hubness_p99_ratio_le_1_10": fma_guard["hubness_top10"]["p99_ratio"]
        <= 1.10,
        "top10_neighbor_overlap_ge_0_70": fma_guard[
            "mean_top10_neighbor_overlap"
        ]
        >= 0.70,
    }
    return {
        "checks": checks,
        "statistical_pass": bool(all(checks.values())),
        "rights_approved": False,
        "ship_allowed": False,
        "ship_block_reason": (
            "Research-only MPD/iTunes/Deezer lineage has not received a "
            "documented rights review; Stage B mobile/end-to-end gates are also pending."
        ),
    }


def json_ready(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): json_ready(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_ready(item) for item in value]
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return float(value)
    if isinstance(value, Path):
        return str(value)
    return value


def write_summary(report: dict[str, Any], path: Path) -> None:
    selected = report["selection"]["selected_seed"]
    base = report["test"]["baseline"]["macro"]
    candidate = report["test"]["candidate"]["macro"]
    gates = report["stage_a_gates"]
    failed = [name for name, passed in gates["checks"].items() if not passed]
    lines = [
        "# Encoder metric Stage A",
        "",
        f"- Selected seed: `{selected}` (validation only)",
        f"- Strict test queries: `{report['test']['candidate']['n_queries']:,}`",
        (
            "- Macro R@100: "
            f"`{base['recall_at_100']:.6f}` → `{candidate['recall_at_100']:.6f}` "
            f"({relative_gain(base['recall_at_100'], candidate['recall_at_100']):+.2%})"
        ),
        (
            "- Macro exact MRR: "
            f"`{base['mrr']:.6f}` → `{candidate['mrr']:.6f}` "
            f"({relative_gain(base['mrr'], candidate['mrr']):+.2%})"
        ),
        f"- Statistical Stage-A gate: `{'PASS' if gates['statistical_pass'] else 'FAIL'}`",
        "- Shipping status: `BLOCKED / RESEARCH ONLY`",
        "",
        "The MPD split is artist-, track-, and playlist-disjoint. Test was evaluated "
        "only after selecting one of three fixed seeds on validation.",
        "",
        "## Failed gates",
        "",
    ]
    lines.extend([f"- `{name}`" for name in failed] or ["- None"])
    lines.extend(
        [
            "",
            "## Rights boundary",
            "",
            gates["ship_block_reason"],
            "",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    args = parse_args()
    if args.smoke:
        args.seeds = "17"
        args.epochs = 1
        args.max_train_pairs = 2_000
        args.bootstrap_reps = 200
        args.fma_sample = 500
        args.validation_only = True
    seeds = [int(value.strip()) for value in args.seeds.split(",") if value.strip()]
    if not seeds:
        raise ValueError("at least one seed is required")
    torch.set_num_threads(max(1, args.threads))
    torch.set_num_interop_threads(1)

    root = args.research_root.resolve()
    out_dir = args.out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {
        "mpd_sessions": root / "data/manifests/mpd_sessions.csv",
        "mpd_embeddings": root / "models/embed/mpd_mnv4_distilled.parquet",
        "combined_embeddings": root / "models/embed/combined_mnv4_distilled.parquet",
        "fma_embeddings": root / "models/embed/fma_small_mnv4_distilled.parquet",
        "phone_playlists": root / "data/manifests/phone_playlists.csv",
        "preview_manifest": root / "data/mpd/preview_urls.parquet",
    }
    missing = [str(path) for path in paths.values() if not path.exists()]
    if missing:
        raise FileNotFoundError(f"missing experiment inputs: {missing}")

    started = time.time()
    print("building strict artist+playlist split", flush=True)
    bundle = build_strict_splits(paths["mpd_sessions"], paths["mpd_embeddings"])
    print(json.dumps(bundle.split_counts, indent=2), flush=True)

    # Validation is the only held-out label set used for seed selection.
    baseline_val = evaluate_pairs(
        bundle.val_pairs, bundle.embeddings, bundle.embeddings
    )
    seed_runs: list[dict[str, Any]] = []
    learned: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    for seed in seeds:
        seed_started = time.time()
        u, v, history = train_one_seed(
            bundle,
            seed=seed,
            rank=args.rank,
            epochs=args.epochs,
            max_train_pairs=args.max_train_pairs,
            batch_size=args.batch_size,
            cross_fraction=args.cross_artist_fraction,
            temperature=args.temperature,
            geometry_weight=args.geometry_weight,
            delta_weight=args.delta_weight,
            learning_rate=args.learning_rate,
        )
        learned[seed] = (u, v)
        candidate_mpd = transform_embeddings(bundle.embeddings, u, v)
        candidate_val = evaluate_pairs(
            bundle.val_pairs, candidate_mpd, candidate_mpd
        )
        comparison = paired_comparison(
            baseline_val,
            candidate_val,
            reps=args.bootstrap_reps,
            seed=10_000 + seed,
        )
        seed_run = {
            "seed": seed,
            "training_history": history,
            "validation": candidate_val.summary,
            "validation_vs_baseline": comparison,
            "runtime_seconds": time.time() - seed_started,
        }
        seed_runs.append(seed_run)
        print(
            f"seed={seed} val macro MRR={candidate_val.summary['macro']['mrr']:.7f} "
            f"R@100={candidate_val.summary['macro']['recall_at_100']:.7f}",
            flush=True,
        )

    selected_run = max(
        seed_runs,
        key=lambda run: (
            run["validation"]["macro"]["mrr"],
            run["validation"]["macro"]["recall_at_100"],
        ),
    )
    selected_seed = int(selected_run["seed"])
    selected_u, selected_v = learned[selected_seed]
    selected_w = (
        np.eye(bundle.embeddings.shape[1], dtype=np.float32)
        + checked_matmul(selected_u, selected_v.T)
    )

    if args.validation_only:
        validation_report = json_ready(
            {
                "experiment": "encoder_metric_stage_a",
                "status": "validation_only_no_test_evaluation",
                "data": {
                    "split_counts": bundle.split_counts,
                    "covered_true_adjacent_pairs_before_strict_split": (
                        bundle.covered_adjacent_pairs
                    ),
                    "repeated_same_track_pairs_excluded": bundle.repeated_pair_counts,
                },
                "configuration": {
                    **vars(args),
                    "research_root": str(root),
                    "out_dir": str(out_dir),
                    "seeds": seeds,
                },
                "baseline_validation": baseline_val.summary,
                "seed_runs": seed_runs,
                "selection": {
                    "metric": "validation.macro.mrr",
                    "tie_breaker": "validation.macro.recall_at_100",
                    "selected_seed": selected_seed,
                },
                "test_evaluated": False,
                "provenance": {
                    "script_sha256": sha256_file(Path(__file__).resolve()),
                    "runtime_seconds": time.time() - started,
                },
            }
        )
        np.savez_compressed(
            out_dir / "validation_metric_head.npz",
            u=selected_u,
            v=selected_v,
            w=selected_w,
            selected_seed=np.asarray([selected_seed], dtype=np.int32),
        )
        (out_dir / "validation_report.json").write_text(
            json.dumps(validation_report, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        print(
            f"wrote {out_dir / 'validation_report.json'}; "
            "validation-only run stopped before sealed test/phone/FMA evaluation",
            flush=True,
        )
        return 0

    # Sealed test: first and only model-dependent test evaluation happens here.
    print(f"selected seed={selected_seed}; evaluating sealed test once", flush=True)
    candidate_mpd = transform_embeddings(bundle.embeddings, selected_u, selected_v)
    baseline_test = evaluate_pairs(
        bundle.test_pairs, bundle.embeddings, bundle.embeddings
    )
    candidate_test = evaluate_pairs(
        bundle.test_pairs, candidate_mpd, candidate_mpd
    )
    test_comparison = paired_comparison(
        baseline_test,
        candidate_test,
        reps=args.bootstrap_reps,
        seed=20_260_723,
    )

    combined_frame, combined_embeddings = load_embedding_frame(
        paths["combined_embeddings"], metadata=False
    )
    combined_ids = combined_frame["track_id"].to_numpy(dtype=str)
    combined_meta = pd.read_parquet(
        paths["combined_embeddings"], columns=["track_id", "artist"]
    )
    combined_artists = np.asarray(
        [
            normalize_artist(artist, track_id)
            for artist, track_id in zip(
                combined_meta["artist"], combined_meta["track_id"].astype(str)
            )
        ],
        dtype=object,
    )
    phone_pairs = phone_pair_set(
        paths["phone_playlists"], combined_ids, combined_artists
    )
    candidate_combined = transform_embeddings(
        combined_embeddings, selected_u, selected_v
    )
    baseline_phone = evaluate_pairs(
        phone_pairs, combined_embeddings, combined_embeddings
    )
    candidate_phone = evaluate_pairs(
        phone_pairs, candidate_combined, candidate_combined
    )
    phone_comparison = paired_comparison(
        baseline_phone,
        candidate_phone,
        reps=args.bootstrap_reps,
        seed=30_260_723,
    )
    phone_worst = per_playlist_worst_regression(
        baseline_phone.per_query, candidate_phone.per_query, min_queries=20
    )

    _, fma_embeddings = load_embedding_frame(paths["fma_embeddings"], metadata=False)
    candidate_fma = transform_embeddings(fma_embeddings, selected_u, selected_v)
    fma_guard = geometry_guard(
        fma_embeddings,
        candidate_fma,
        sample_size=args.fma_sample,
        seed=40_260_723,
    )
    gates = apply_stage_a_gates(
        baseline_test,
        candidate_test,
        test_comparison,
        phone_comparison,
        phone_worst,
        fma_guard,
    )

    preview_sources = (
        pd.read_parquet(paths["preview_manifest"], columns=["source"])["source"]
        .value_counts(dropna=False)
        .to_dict()
    )
    input_hashes = {name: sha256_file(path) for name, path in paths.items()}
    report: dict[str, Any] = {
        "experiment": "encoder_metric_stage_a",
        "status": "research_only",
        "created_unix_seconds": time.time(),
        "protocol": {
            "split": (
                "SHA256 first 8 bytes mod 20; train=0..13, val=14..16, "
                "test=17..19; keep an adjacent pair only when playlist, "
                "source artist, and target artist all map to the same split"
            ),
            "artist_normalization": "Unicode NFKC, casefold, collapse whitespace",
            "catalog": "full 18,386-track MPD catalog for strict val/test ranking",
            "test_policy": (
                "three fixed seeds selected by validation macro exact MRR; "
                "test evaluated once for selected seed"
            ),
            "mrr": "exact full-catalog reciprocal rank (not truncated at 100)",
        },
        "data": {
            "split_counts": bundle.split_counts,
            "covered_true_adjacent_pairs_before_strict_split": bundle.covered_adjacent_pairs,
            "repeated_same_track_pairs_excluded": bundle.repeated_pair_counts,
            "track_count": int(bundle.embeddings.shape[0]),
            "artist_count": int(np.unique(bundle.artists).size),
            "embedding_dim": int(bundle.embeddings.shape[1]),
            "phone_queries": len(phone_pairs),
            "phone_playlists": int(np.unique(phone_pairs.playlist).size),
        },
        "configuration": {
            **vars(args),
            "research_root": str(root),
            "out_dir": str(out_dir),
            "seeds": seeds,
        },
        "baseline_validation": baseline_val.summary,
        "seed_runs": seed_runs,
        "selection": {
            "metric": "validation.macro.mrr",
            "tie_breaker": "validation.macro.recall_at_100",
            "selected_seed": selected_seed,
        },
        "test": {
            "baseline": baseline_test.summary,
            "candidate": candidate_test.summary,
            "paired_playlist_bootstrap": test_comparison,
        },
        "phone_holdout": {
            "baseline": baseline_phone.summary,
            "candidate": candidate_phone.summary,
            "paired_playlist_bootstrap": phone_comparison,
            "large_playlist_worst_regression": phone_worst,
        },
        "fma_geometry_guard": fma_guard,
        "stage_a_gates": gates,
        "deployment_equivalence": {
            "formula": (
                "normalize(W normalize(P h)) == normalize((W P) h), "
                "for W=I+UV^T and bias-free P"
            ),
            "output_dim": int(bundle.embeddings.shape[1]),
            "added_mobile_ops_after_fold": 0,
            "added_mobile_parameters_after_fold": 0,
            "asset_mutated": False,
            "mobile_export_and_latency": "deferred_to_stage_b",
        },
        "rights": {
            "approved": False,
            "classification": "research_only",
            "preview_source_counts": preview_sources,
            "warning": (
                "Statistical validity is separate from data rights. Spotify MPD "
                "playlist lineage plus iTunes/Deezer previews requires documented "
                "permission before derived weights can be shipped. FMA tracks have "
                "per-track licenses and require a commercial-compatible allowlist."
            ),
        },
        "provenance": {
            "input_sha256": input_hashes,
            "script_sha256": sha256_file(Path(__file__).resolve()),
            "python": sys.version,
            "platform": platform.platform(),
            "torch": torch.__version__,
            "numpy": np.__version__,
            "pandas": pd.__version__,
            "runtime_seconds": time.time() - started,
        },
    }
    report = json_ready(report)

    np.savez_compressed(
        out_dir / "selected_metric_head.npz",
        u=selected_u,
        v=selected_v,
        w=selected_w,
        selected_seed=np.asarray([selected_seed], dtype=np.int32),
    )
    (out_dir / "report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True), encoding="utf-8"
    )
    write_summary(report, out_dir / "summary.md")
    print(
        f"wrote {out_dir / 'report.json'}; "
        f"Stage-A statistical gate={'PASS' if gates['statistical_pass'] else 'FAIL'}; "
        "shipping remains BLOCKED (research-only rights status)",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
