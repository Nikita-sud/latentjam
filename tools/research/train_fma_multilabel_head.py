#!/usr/bin/env python3
"""Train and export a calibrated hierarchical tag head for frozen FMA embeddings.

This is a deliberately small research experiment.  It does not retrain the
audio encoder.  Instead, it:

1. joins a frozen embedding parquet to the official FMA metadata splits;
2. builds one-hot broad labels from ``track.genre_top`` and multi-hot child
   labels from ``track.genres_all``;
3. selects child labels using training/validation support only;
4. tunes one L2 logistic classifier per label on validation average precision;
5. Platt-calibrates every label on validation logits;
6. chooses per-label abstention thresholds on validation data only;
7. evaluates once on the untouched official test split; and
8. exports a compact ONNX head plus machine-readable metadata.

The ONNX model accepts L2-normalized or unnormalized 960-dimensional
embeddings.  It normalizes internally, emits calibrated probabilities, and
caps every child probability at its broad parent's probability.

The FMA audio and metadata have track-level licenses.  This script and its
outputs are for research evaluation; model distribution requires a separate
training-data provenance review.

Example:

    /path/to/python tools/research/train_fma_multilabel_head.py \
      --store /path/to/models/embed/fma_small_mnv4_distilled.parquet \
      --tracks /path/to/data/raw/fma_metadata/tracks.csv \
      --genres /path/to/data/raw/fma_metadata/genres.csv \
      --output-dir /private/tmp/latentjam-fma-tagger-v1
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import math
import platform
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np
import pandas as pd


SPLIT_NAMES = ("training", "validation", "test")


@dataclass(frozen=True)
class LabelSpec:
    """A stable output-label definition."""

    index: int
    genre_id: int
    name: str
    kind: str
    parent_index: int
    parent_genre_id: int
    parent_name: str
    train_positives: int
    validation_positives: int


@dataclass
class FittedLabel:
    """Parameters and validation decisions for one binary label."""

    weight: np.ndarray
    bias: float
    calibration_scale: float
    calibration_bias: float
    selected_c: float
    validation_ap: float
    threshold: float
    threshold_target_precision: float
    threshold_met_target: bool
    threshold_validation_predictions: int
    threshold_validation_precision: float
    threshold_validation_recall: float
    best_f1_threshold: float
    best_f1: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", type=Path, required=True)
    parser.add_argument("--tracks", type=Path, required=True)
    parser.add_argument("--genres", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--model-name",
        default="mnv4_fma_hierarchical_v1",
        help="Filename stem and metadata model identifier.",
    )
    parser.add_argument(
        "--min-child-train-positives",
        type=int,
        default=150,
        help="Minimum training positives for a child label.",
    )
    parser.add_argument(
        "--min-child-validation-positives",
        type=int,
        default=20,
        help="Minimum validation positives for a child label.",
    )
    parser.add_argument(
        "--target-precision",
        type=float,
        default=0.80,
        help="Validation precision required by an exported naming threshold.",
    )
    parser.add_argument(
        "--min-threshold-predictions",
        type=int,
        default=10,
        help="Minimum validation predictions when selecting a threshold.",
    )
    parser.add_argument(
        "--c-grid",
        default="0.03,0.1,0.3,1.0,3.0,10.0",
        help="Comma-separated inverse L2 regularization values.",
    )
    parser.add_argument("--max-iter", type=int, default=2_000)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--opset", type=int, default=17)
    return parser.parse_args()


def require_file(path: Path, description: str) -> Path:
    path = path.expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(f"{description} does not exist: {path}")
    return path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_genre_ids(value: Any) -> tuple[int, ...]:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ()
    if isinstance(value, np.ndarray):
        value = value.tolist()
    if isinstance(value, (list, tuple, set)):
        return tuple(sorted({int(item) for item in value}))
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return ()
        parsed = ast.literal_eval(stripped)
        if isinstance(parsed, (list, tuple, set)):
            return tuple(sorted({int(item) for item in parsed}))
        return (int(parsed),)
    return (int(value),)


def load_joined_data(store_path: Path, tracks_path: Path) -> tuple[pd.DataFrame, int, str]:
    store = pd.read_parquet(store_path)
    required_store = {"track_id", "embedding"}
    missing_store = sorted(required_store.difference(store.columns))
    if missing_store:
        raise ValueError(f"embedding store is missing columns: {missing_store}")
    if store["track_id"].duplicated().any():
        raise ValueError("embedding store contains duplicate track_id values")

    dimensions = sorted({len(np.asarray(item)) for item in store["embedding"]})
    if len(dimensions) != 1:
        raise ValueError(f"embedding dimensions are inconsistent: {dimensions}")
    embedding_dim = int(dimensions[0])
    model_versions = sorted(
        {str(item) for item in store.get("model_version", pd.Series(["unknown"])).dropna()}
    )
    model_version = ",".join(model_versions) if model_versions else "unknown"

    tracks = pd.read_csv(tracks_path, header=[0, 1], index_col=0)
    required_tracks = [
        ("set", "split"),
        ("set", "subset"),
        ("track", "genre_top"),
        ("track", "genres_all"),
        ("artist", "id"),
    ]
    missing_tracks = [column for column in required_tracks if column not in tracks.columns]
    if missing_tracks:
        raise ValueError(f"FMA metadata is missing columns: {missing_tracks}")

    metadata = pd.DataFrame(index=tracks.index.astype(int))
    metadata.index.name = "track_id_int"
    metadata["split"] = tracks[("set", "split")].astype(str)
    metadata["subset"] = tracks[("set", "subset")].astype(str)
    metadata["genre_top"] = tracks[("track", "genre_top")]
    metadata["genres_all"] = tracks[("track", "genres_all")].map(parse_genre_ids)
    metadata["artist_id"] = tracks[("artist", "id")]
    metadata = metadata[metadata["subset"] == "small"].copy()

    aligned = store.copy()
    aligned["track_id_int"] = pd.to_numeric(aligned["track_id"], errors="raise").astype(int)
    aligned = aligned.merge(
        metadata.reset_index(), on="track_id_int", how="inner", validate="one_to_one"
    )
    aligned = aligned[aligned["split"].isin(SPLIT_NAMES)].copy()
    if aligned.empty:
        raise ValueError("no FMA-small embeddings aligned to official split metadata")

    matrix = np.stack(
        [np.asarray(item, dtype=np.float32) for item in aligned["embedding"]], axis=0
    )
    if not np.isfinite(matrix).all():
        raise ValueError("embedding matrix contains NaN or infinite values")
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    matrix = matrix / np.maximum(norms, 1e-12)
    aligned["embedding"] = list(matrix)
    return aligned, embedding_dim, model_version


def load_taxonomy(genres_path: Path) -> pd.DataFrame:
    taxonomy = pd.read_csv(genres_path, index_col=0)
    required = {"title", "parent", "top_level"}
    missing = sorted(required.difference(taxonomy.columns))
    if missing:
        raise ValueError(f"genre taxonomy is missing columns: {missing}")
    taxonomy.index = taxonomy.index.astype(int)
    taxonomy["parent"] = taxonomy["parent"].astype(int)
    taxonomy["top_level"] = taxonomy["top_level"].astype(int)
    return taxonomy


def count_ids(values: Iterable[tuple[int, ...]]) -> dict[int, int]:
    counts: dict[int, int] = {}
    for ids in values:
        for genre_id in ids:
            counts[genre_id] = counts.get(genre_id, 0) + 1
    return counts


def resolve_broad_ids(frame: pd.DataFrame, taxonomy: pd.DataFrame) -> dict[str, int]:
    title_to_ids: dict[str, list[int]] = {}
    for genre_id, row in taxonomy.iterrows():
        title_to_ids.setdefault(str(row["title"]), []).append(int(genre_id))

    result: dict[str, int] = {}
    for title in sorted(frame["genre_top"].dropna().astype(str).unique()):
        candidates = title_to_ids.get(title, [])
        top_level_candidates = [
            genre_id
            for genre_id in candidates
            if int(taxonomy.loc[genre_id, "top_level"]) == genre_id
        ]
        if len(top_level_candidates) != 1:
            raise ValueError(
                f"cannot uniquely map broad FMA genre {title!r} to taxonomy: "
                f"{top_level_candidates}"
            )
        result[title] = top_level_candidates[0]
    return result


def build_label_specs(
    frame: pd.DataFrame,
    taxonomy: pd.DataFrame,
    min_child_train_positives: int,
    min_child_validation_positives: int,
) -> list[LabelSpec]:
    train = frame[frame["split"] == "training"]
    validation = frame[frame["split"] == "validation"]
    broad_ids = resolve_broad_ids(frame, taxonomy)
    broad_id_set = set(broad_ids.values())

    specs: list[LabelSpec] = []
    for title, genre_id in sorted(broad_ids.items(), key=lambda item: item[1]):
        specs.append(
            LabelSpec(
                index=len(specs),
                genre_id=genre_id,
                name=title,
                kind="broad",
                parent_index=len(specs),
                parent_genre_id=genre_id,
                parent_name=title,
                train_positives=int((train["genre_top"].astype(str) == title).sum()),
                validation_positives=int(
                    (validation["genre_top"].astype(str) == title).sum()
                ),
            )
        )

    train_counts = count_ids(train["genres_all"])
    validation_counts = count_ids(validation["genres_all"])
    candidate_ids = sorted(
        genre_id
        for genre_id, count in train_counts.items()
        if genre_id not in broad_id_set
        and genre_id in taxonomy.index
        and count >= min_child_train_positives
        and validation_counts.get(genre_id, 0) >= min_child_validation_positives
        and int(taxonomy.loc[genre_id, "top_level"]) in broad_id_set
    )
    broad_index_by_id = {spec.genre_id: spec.index for spec in specs}
    broad_name_by_id = {spec.genre_id: spec.name for spec in specs}
    for genre_id in candidate_ids:
        parent_id = int(taxonomy.loc[genre_id, "top_level"])
        specs.append(
            LabelSpec(
                index=len(specs),
                genre_id=genre_id,
                name=str(taxonomy.loc[genre_id, "title"]),
                kind="child",
                parent_index=broad_index_by_id[parent_id],
                parent_genre_id=parent_id,
                parent_name=broad_name_by_id[parent_id],
                train_positives=int(train_counts[genre_id]),
                validation_positives=int(validation_counts[genre_id]),
            )
        )
    return specs


def make_targets(frame: pd.DataFrame, specs: Sequence[LabelSpec]) -> np.ndarray:
    target = np.zeros((len(frame), len(specs)), dtype=np.int64)
    broad_specs = [spec for spec in specs if spec.kind == "broad"]
    child_specs = [spec for spec in specs if spec.kind == "child"]
    broad_index_by_name = {spec.name: spec.index for spec in broad_specs}

    for row_index, row in enumerate(frame.itertuples(index=False)):
        broad_name = str(row.genre_top)
        if broad_name not in broad_index_by_name:
            raise ValueError(f"unknown broad genre in aligned data: {broad_name!r}")
        target[row_index, broad_index_by_name[broad_name]] = 1
        genre_ids = set(row.genres_all)
        for spec in child_specs:
            if spec.genre_id in genre_ids:
                target[row_index, spec.index] = 1
    return target


def sigmoid(values: np.ndarray) -> np.ndarray:
    clipped = np.clip(values, -40.0, 40.0)
    return 1.0 / (1.0 + np.exp(-clipped))


def fit_platt_scaling(logits: np.ndarray, target: np.ndarray, seed: int) -> tuple[float, float]:
    """Fit monotonic Platt scaling, falling back to prevalence correction."""

    from sklearn.linear_model import LogisticRegression

    calibrator = LogisticRegression(
        C=1_000_000.0,
        solver="lbfgs",
        max_iter=2_000,
        random_state=seed,
    )
    calibrator.fit(logits.reshape(-1, 1), target)
    scale = float(calibrator.coef_[0, 0])
    bias = float(calibrator.intercept_[0])
    if np.isfinite(scale) and np.isfinite(bias) and scale > 1e-6:
        return scale, bias

    prevalence = float(np.clip(target.mean(), 1e-6, 1.0 - 1e-6))
    mean_logit = float(np.mean(logits))
    return 1.0, math.log(prevalence / (1.0 - prevalence)) - mean_logit


def binary_threshold_metrics(
    target: np.ndarray, probability: np.ndarray, threshold: float
) -> dict[str, float | int]:
    prediction = probability >= threshold
    true_positive = int(np.logical_and(prediction, target == 1).sum())
    false_positive = int(np.logical_and(prediction, target == 0).sum())
    false_negative = int(np.logical_and(~prediction, target == 1).sum())
    predicted = true_positive + false_positive
    precision = true_positive / predicted if predicted else 0.0
    recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
    f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "predictions": predicted,
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
    }


def select_threshold(
    target: np.ndarray,
    probability: np.ndarray,
    target_precision: float,
    min_predictions: int,
) -> dict[str, float | int | bool]:
    """Choose a validation-only abstention threshold.

    The exported threshold maximizes recall among thresholds meeting the target
    precision and minimum prediction count.  If no threshold qualifies, 1.0
    disables the label for threshold-based naming.  A separate best-F1
    threshold is reported for analysis but is not exported.
    """

    candidates = np.unique(np.concatenate(([0.0], probability, [1.0])))
    best_qualified: tuple[tuple[float, float, float], float, dict[str, Any]] | None = None
    best_f1: tuple[tuple[float, float, float], float, dict[str, Any]] | None = None
    for threshold in candidates:
        metrics = binary_threshold_metrics(target, probability, float(threshold))
        f1_key = (
            float(metrics["f1"]),
            float(metrics["precision"]),
            float(threshold),
        )
        if best_f1 is None or f1_key > best_f1[0]:
            best_f1 = (f1_key, float(threshold), metrics)
        if (
            int(metrics["predictions"]) >= min_predictions
            and float(metrics["precision"]) >= target_precision
        ):
            qualified_key = (
                float(metrics["recall"]),
                float(metrics["precision"]),
                float(threshold),
            )
            if best_qualified is None or qualified_key > best_qualified[0]:
                best_qualified = (qualified_key, float(threshold), metrics)

    assert best_f1 is not None
    if best_qualified is None:
        selected_threshold = 1.0
        selected_metrics = binary_threshold_metrics(target, probability, 1.0)
        met_target = False
    else:
        _, selected_threshold, selected_metrics = best_qualified
        met_target = True
    return {
        "threshold": selected_threshold,
        "met_target": met_target,
        "predictions": int(selected_metrics["predictions"]),
        "precision": float(selected_metrics["precision"]),
        "recall": float(selected_metrics["recall"]),
        "best_f1_threshold": float(best_f1[1]),
        "best_f1": float(best_f1[2]["f1"]),
    }


def fit_label(
    x_train: np.ndarray,
    y_train: np.ndarray,
    x_validation: np.ndarray,
    y_validation: np.ndarray,
    c_grid: Sequence[float],
    max_iter: int,
    seed: int,
    target_precision: float,
    min_threshold_predictions: int,
) -> FittedLabel:
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import average_precision_score

    candidates: list[tuple[float, float, LogisticRegression]] = []
    for c_value in c_grid:
        classifier = LogisticRegression(
            C=float(c_value),
            class_weight="balanced",
            solver="liblinear",
            max_iter=max_iter,
            random_state=seed,
        )
        classifier.fit(x_train, y_train)
        validation_logits = classifier.decision_function(x_validation)
        validation_ap = float(average_precision_score(y_validation, validation_logits))
        candidates.append((validation_ap, -float(c_value), classifier))

    validation_ap, negative_c, classifier = max(candidates, key=lambda item: item[:2])
    selected_c = -negative_c
    validation_logits = np.asarray(
        classifier.decision_function(x_validation), dtype=np.float64
    )
    calibration_scale, calibration_bias = fit_platt_scaling(
        validation_logits, y_validation, seed
    )
    calibrated = sigmoid(calibration_scale * validation_logits + calibration_bias)
    threshold = select_threshold(
        y_validation,
        calibrated,
        target_precision=target_precision,
        min_predictions=min_threshold_predictions,
    )
    return FittedLabel(
        weight=np.asarray(classifier.coef_[0], dtype=np.float32),
        bias=float(classifier.intercept_[0]),
        calibration_scale=calibration_scale,
        calibration_bias=calibration_bias,
        selected_c=selected_c,
        validation_ap=validation_ap,
        threshold=float(threshold["threshold"]),
        threshold_target_precision=target_precision,
        threshold_met_target=bool(threshold["met_target"]),
        threshold_validation_predictions=int(threshold["predictions"]),
        threshold_validation_precision=float(threshold["precision"]),
        threshold_validation_recall=float(threshold["recall"]),
        best_f1_threshold=float(threshold["best_f1_threshold"]),
        best_f1=float(threshold["best_f1"]),
    )


def apply_hierarchy(probabilities: np.ndarray, specs: Sequence[LabelSpec]) -> np.ndarray:
    result = probabilities.copy()
    for spec in specs:
        if spec.kind == "child":
            result[:, spec.index] = np.minimum(
                result[:, spec.index], result[:, spec.parent_index]
            )
    return result


def expected_calibration_error(
    target: np.ndarray, probability: np.ndarray, bins: int = 10
) -> float:
    edges = np.linspace(0.0, 1.0, bins + 1)
    total = len(target)
    value = 0.0
    for bin_index in range(bins):
        lower = edges[bin_index]
        upper = edges[bin_index + 1]
        if bin_index == bins - 1:
            mask = np.logical_and(probability >= lower, probability <= upper)
        else:
            mask = np.logical_and(probability >= lower, probability < upper)
        count = int(mask.sum())
        if count:
            value += (count / total) * abs(
                float(target[mask].mean()) - float(probability[mask].mean())
            )
    return float(value)


def safe_auc(target: np.ndarray, probability: np.ndarray) -> float | None:
    from sklearn.metrics import roc_auc_score

    if np.unique(target).size < 2:
        return None
    return float(roc_auc_score(target, probability))


def safe_ap(target: np.ndarray, probability: np.ndarray) -> float | None:
    from sklearn.metrics import average_precision_score

    if int(target.sum()) == 0:
        return None
    return float(average_precision_score(target, probability))


def finite_mean(values: Iterable[float | None]) -> float | None:
    filtered = [float(value) for value in values if value is not None and np.isfinite(value)]
    return float(np.mean(filtered)) if filtered else None


def evaluate(
    target: np.ndarray,
    calibrated_probability: np.ndarray,
    uncalibrated_probability: np.ndarray,
    specs: Sequence[LabelSpec],
    fitted: Sequence[FittedLabel],
) -> dict[str, Any]:
    label_metrics: list[dict[str, Any]] = []
    for spec, fit in zip(specs, fitted):
        y_column = target[:, spec.index]
        p_column = calibrated_probability[:, spec.index]
        threshold_metrics = binary_threshold_metrics(y_column, p_column, fit.threshold)
        label_metrics.append(
            {
                "index": spec.index,
                "genre_id": spec.genre_id,
                "name": spec.name,
                "kind": spec.kind,
                "parent_name": spec.parent_name,
                "positives": int(y_column.sum()),
                "negatives": int(len(y_column) - y_column.sum()),
                "average_precision": safe_ap(y_column, p_column),
                "roc_auc": safe_auc(y_column, p_column),
                "brier": float(np.mean(np.square(p_column - y_column))),
                "brier_uncalibrated": float(
                    np.mean(
                        np.square(
                            uncalibrated_probability[:, spec.index] - y_column
                        )
                    )
                ),
                "ece_10_bin": expected_calibration_error(y_column, p_column),
                "threshold": fit.threshold,
                "threshold_enabled": fit.threshold_met_target,
                **threshold_metrics,
            }
        )

    broad_indices = [spec.index for spec in specs if spec.kind == "broad"]
    child_indices = [spec.index for spec in specs if spec.kind == "child"]
    true_broad = np.argmax(target[:, broad_indices], axis=1)
    predicted_broad = np.argmax(calibrated_probability[:, broad_indices], axis=1)
    broad_confidence = np.max(calibrated_probability[:, broad_indices], axis=1)
    broad_correct = predicted_broad == true_broad
    selective_curve = []
    for threshold in (0.50, 0.60, 0.70, 0.80, 0.90):
        accepted = broad_confidence >= threshold
        count = int(accepted.sum())
        selective_curve.append(
            {
                "confidence_threshold": threshold,
                "coverage": float(accepted.mean()),
                "accepted": count,
                "accuracy": float(broad_correct[accepted].mean()) if count else None,
            }
        )

    thresholds = np.asarray([fit.threshold for fit in fitted], dtype=np.float64)
    enabled = np.asarray(
        [fit.threshold_met_target for fit in fitted], dtype=bool
    )
    prediction = calibrated_probability >= thresholds[None, :]
    prediction[:, ~enabled] = False
    true_positive = int(np.logical_and(prediction, target == 1).sum())
    false_positive = int(np.logical_and(prediction, target == 0).sum())
    false_negative = int(np.logical_and(~prediction, target == 1).sum())
    micro_precision = (
        true_positive / (true_positive + false_positive)
        if true_positive + false_positive
        else 0.0
    )
    micro_recall = (
        true_positive / (true_positive + false_negative)
        if true_positive + false_negative
        else 0.0
    )
    micro_f1 = (
        2.0 * micro_precision * micro_recall / (micro_precision + micro_recall)
        if micro_precision + micro_recall
        else 0.0
    )

    def group_summary(indices: Sequence[int]) -> dict[str, Any]:
        selected = [label_metrics[index] for index in indices]
        return {
            "label_count": len(indices),
            "macro_average_precision": finite_mean(
                item["average_precision"] for item in selected
            ),
            "macro_roc_auc": finite_mean(item["roc_auc"] for item in selected),
            "macro_brier": finite_mean(item["brier"] for item in selected),
            "macro_brier_uncalibrated": finite_mean(
                item["brier_uncalibrated"] for item in selected
            ),
            "macro_ece_10_bin": finite_mean(
                item["ece_10_bin"] for item in selected
            ),
            "threshold_enabled_labels": int(
                sum(bool(item["threshold_enabled"]) for item in selected)
            ),
            "threshold_macro_precision": finite_mean(
                item["precision"]
                for item in selected
                if bool(item["threshold_enabled"])
            ),
            "threshold_macro_recall": finite_mean(
                item["recall"]
                for item in selected
                if bool(item["threshold_enabled"])
            ),
        }

    return {
        "n_examples": int(len(target)),
        "broad_top1_accuracy": float(broad_correct.mean()),
        "broad_selective_accuracy": selective_curve,
        "broad": group_summary(broad_indices),
        "children": group_summary(child_indices),
        "all_labels": group_summary(list(range(len(specs)))),
        "exported_thresholds": {
            "enabled_labels": int(enabled.sum()),
            "micro_precision": float(micro_precision),
            "micro_recall": float(micro_recall),
            "micro_f1": float(micro_f1),
            "track_coverage": float(prediction.any(axis=1).mean()),
            "mean_predictions_per_track": float(prediction.sum(axis=1).mean()),
        },
        "per_label": label_metrics,
    }


def predict_probabilities(
    matrix: np.ndarray,
    fitted: Sequence[FittedLabel],
    specs: Sequence[LabelSpec],
) -> tuple[np.ndarray, np.ndarray]:
    weights = np.stack([fit.weight for fit in fitted], axis=0)
    biases = np.asarray([fit.bias for fit in fitted], dtype=np.float64)
    scales = np.asarray(
        [fit.calibration_scale for fit in fitted], dtype=np.float64
    )
    calibration_biases = np.asarray(
        [fit.calibration_bias for fit in fitted], dtype=np.float64
    )
    logits = matrix.astype(np.float64) @ weights.astype(np.float64).T + biases
    uncalibrated = apply_hierarchy(sigmoid(logits), specs)
    calibrated = apply_hierarchy(
        sigmoid(logits * scales[None, :] + calibration_biases[None, :]), specs
    )
    return calibrated, uncalibrated


def export_onnx(
    output_path: Path,
    embedding_dim: int,
    specs: Sequence[LabelSpec],
    fitted: Sequence[FittedLabel],
    opset: int,
) -> float:
    import onnx
    import onnxruntime as ort
    import torch
    import torch.nn.functional as torch_functional

    class CalibratedHierarchicalHead(torch.nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.register_buffer(
                "weights",
                torch.from_numpy(np.stack([fit.weight for fit in fitted], axis=0)),
            )
            self.register_buffer(
                "biases",
                torch.tensor([fit.bias for fit in fitted], dtype=torch.float32),
            )
            self.register_buffer(
                "calibration_scales",
                torch.tensor(
                    [fit.calibration_scale for fit in fitted], dtype=torch.float32
                ),
            )
            self.register_buffer(
                "calibration_biases",
                torch.tensor(
                    [fit.calibration_bias for fit in fitted], dtype=torch.float32
                ),
            )
            self.register_buffer(
                "parent_indices",
                torch.tensor(
                    [spec.parent_index for spec in specs], dtype=torch.int64
                ),
            )
            self.register_buffer(
                "child_mask",
                torch.tensor(
                    [spec.kind == "child" for spec in specs], dtype=torch.bool
                ),
            )

        def forward(self, embedding: Any) -> Any:
            normalized = torch_functional.normalize(
                embedding, p=2.0, dim=1, eps=1e-12
            )
            logits = torch_functional.linear(normalized, self.weights, self.biases)
            probabilities = torch.sigmoid(
                logits * self.calibration_scales + self.calibration_biases
            )
            parent_probabilities = torch.index_select(
                probabilities, 1, self.parent_indices
            )
            return torch.where(
                self.child_mask.unsqueeze(0),
                torch.minimum(probabilities, parent_probabilities),
                probabilities,
            )

    model = CalibratedHierarchicalHead().eval()
    example = torch.zeros((2, embedding_dim), dtype=torch.float32)
    example[0, 0] = 1.0
    example[1, 1] = 1.0
    torch.onnx.export(
        model,
        example,
        output_path,
        input_names=["embedding"],
        output_names=["probabilities"],
        dynamic_axes={
            "embedding": {0: "batch"},
            "probabilities": {0: "batch"},
        },
        opset_version=opset,
        do_constant_folding=True,
        dynamo=False,
    )
    onnx_model = onnx.load(output_path)
    onnx.checker.check_model(onnx_model)

    rng = np.random.default_rng(12345)
    test_input = rng.normal(size=(17, embedding_dim)).astype(np.float32)
    with torch.no_grad():
        torch_output = model(torch.from_numpy(test_input)).numpy()
    session = ort.InferenceSession(
        str(output_path), providers=["CPUExecutionProvider"]
    )
    onnx_output = session.run(
        ["probabilities"], {"embedding": test_input}
    )[0]
    maximum_error = float(np.max(np.abs(torch_output - onnx_output)))
    if maximum_error > 1e-5:
        raise RuntimeError(
            f"ONNX parity check failed: maximum absolute error {maximum_error}"
        )
    return maximum_error


def json_safe(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return float(value)
    if isinstance(value, Path):
        return str(value)
    return value


def main() -> None:
    started = time.perf_counter()
    args = parse_args()
    if not 0.0 < args.target_precision <= 1.0:
        raise ValueError("--target-precision must be in (0, 1]")
    if args.min_child_train_positives <= 0:
        raise ValueError("--min-child-train-positives must be positive")
    if args.min_child_validation_positives <= 0:
        raise ValueError("--min-child-validation-positives must be positive")
    c_grid = tuple(
        float(item.strip()) for item in args.c_grid.split(",") if item.strip()
    )
    if not c_grid or any(item <= 0.0 for item in c_grid):
        raise ValueError("--c-grid must contain positive values")

    store_path = require_file(args.store, "embedding store")
    tracks_path = require_file(args.tracks, "FMA tracks metadata")
    genres_path = require_file(args.genres, "FMA genre taxonomy")
    output_dir = args.output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = output_dir / f"{args.model_name}.onnx"
    metadata_path = output_dir / f"{args.model_name}.metadata.json"

    print("Loading and aligning frozen embeddings to official FMA-small splits...")
    frame, embedding_dim, encoder_model_version = load_joined_data(
        store_path, tracks_path
    )
    taxonomy = load_taxonomy(genres_path)
    specs = build_label_specs(
        frame,
        taxonomy,
        min_child_train_positives=args.min_child_train_positives,
        min_child_validation_positives=args.min_child_validation_positives,
    )
    if not any(spec.kind == "child" for spec in specs):
        raise ValueError("child selection policy selected no labels")
    targets = make_targets(frame, specs)

    matrices: dict[str, np.ndarray] = {}
    split_targets: dict[str, np.ndarray] = {}
    split_track_ids: dict[str, np.ndarray] = {}
    for split_name in SPLIT_NAMES:
        mask = frame["split"].to_numpy() == split_name
        matrices[split_name] = np.stack(frame.loc[mask, "embedding"].to_list()).astype(
            np.float32
        )
        split_targets[split_name] = targets[mask]
        split_track_ids[split_name] = frame.loc[mask, "track_id_int"].to_numpy()
        print(
            f"  {split_name:10s}: {mask.sum():4d} tracks, "
            f"{len(specs)} labels, {embedding_dim}-d"
        )

    print(
        f"Training {sum(spec.kind == 'broad' for spec in specs)} broad and "
        f"{sum(spec.kind == 'child' for spec in specs)} child classifiers..."
    )
    fitted: list[FittedLabel] = []
    for spec in specs:
        fit = fit_label(
            matrices["training"],
            split_targets["training"][:, spec.index],
            matrices["validation"],
            split_targets["validation"][:, spec.index],
            c_grid=c_grid,
            max_iter=args.max_iter,
            seed=args.seed,
            target_precision=args.target_precision,
            min_threshold_predictions=args.min_threshold_predictions,
        )
        fitted.append(fit)
        print(
            f"  [{spec.kind:5s}] {spec.name:22s} "
            f"train+={spec.train_positives:4d} val+={spec.validation_positives:3d} "
            f"AP={fit.validation_ap:.3f} C={fit.selected_c:g} "
            f"threshold={fit.threshold:.3f} enabled={fit.threshold_met_target}"
        )

    split_metrics: dict[str, Any] = {}
    for split_name in ("validation", "test"):
        calibrated, uncalibrated = predict_probabilities(
            matrices[split_name], fitted, specs
        )
        split_metrics[split_name] = evaluate(
            split_targets[split_name],
            calibrated,
            uncalibrated,
            specs,
            fitted,
        )

    split_artist_ids = {
        split_name: set(
            pd.to_numeric(
                frame.loc[frame["split"] == split_name, "artist_id"],
                errors="coerce",
            )
            .dropna()
            .astype(int)
            .tolist()
        )
        for split_name in SPLIT_NAMES
    }
    artist_overlap_counts = {
        f"{left}__{right}": len(split_artist_ids[left].intersection(split_artist_ids[right]))
        for left, right in (
            ("training", "validation"),
            ("training", "test"),
            ("validation", "test"),
        )
    }
    if any(artist_overlap_counts.values()):
        raise ValueError(
            "official split validation failed; artist IDs overlap: "
            f"{artist_overlap_counts}"
        )

    print(f"Exporting {onnx_path}...")
    onnx_maximum_error = export_onnx(
        onnx_path,
        embedding_dim=embedding_dim,
        specs=specs,
        fitted=fitted,
        opset=args.opset,
    )

    import onnx
    import onnxruntime
    import sklearn
    import torch

    label_metadata = []
    test_by_index = {
        item["index"]: item for item in split_metrics["test"]["per_label"]
    }
    for spec, fit in zip(specs, fitted):
        item = asdict(spec)
        item.update(
            {
                "selected_c": fit.selected_c,
                "validation_average_precision": fit.validation_ap,
                "calibration": {
                    "type": "platt",
                    "scale": fit.calibration_scale,
                    "bias": fit.calibration_bias,
                    "fit_split": "validation",
                },
                "abstention": {
                    "threshold": fit.threshold,
                    "target_precision": fit.threshold_target_precision,
                    "met_target_on_validation": fit.threshold_met_target,
                    "validation_predictions": fit.threshold_validation_predictions,
                    "validation_precision": fit.threshold_validation_precision,
                    "validation_recall": fit.threshold_validation_recall,
                    "analysis_only_best_f1_threshold": fit.best_f1_threshold,
                    "analysis_only_best_f1": fit.best_f1,
                },
                "test": test_by_index[spec.index],
            }
        )
        label_metadata.append(item)

    elapsed = time.perf_counter() - started
    metadata = {
        "schema_version": 1,
        "model_name": args.model_name,
        "intended_use": "research_only",
        "provenance_warning": (
            "FMA audio has track-level licenses. Review every training-data and "
            "derived-model distribution right before shipping."
        ),
        "created_utc_unix_seconds": int(time.time()),
        "elapsed_seconds": elapsed,
        "model": {
            "format": "onnx",
            "path": str(onnx_path),
            "sha256": sha256_file(onnx_path),
            "size_bytes": onnx_path.stat().st_size,
            "opset": args.opset,
            "input": {
                "name": "embedding",
                "dtype": "float32",
                "shape": ["batch", embedding_dim],
            },
            "output": {
                "name": "probabilities",
                "dtype": "float32",
                "shape": ["batch", len(specs)],
                "semantics": (
                    "Platt-calibrated probabilities; child values are capped at "
                    "their broad-parent probability."
                ),
            },
            "internal_preprocessing": "row-wise L2 normalization, epsilon=1e-12",
            "onnx_parity_maximum_absolute_error": onnx_maximum_error,
        },
        "encoder": {
            "model_version_from_store": encoder_model_version,
            "embedding_dim": embedding_dim,
        },
        "data": {
            "store": {
                "path": str(store_path),
                "sha256": sha256_file(store_path),
            },
            "tracks": {
                "path": str(tracks_path),
                "sha256": sha256_file(tracks_path),
            },
            "genres": {
                "path": str(genres_path),
                "sha256": sha256_file(genres_path),
            },
            "aligned_tracks": int(len(frame)),
            "split_counts": {
                split_name: int(len(split_track_ids[split_name]))
                for split_name in SPLIT_NAMES
            },
            "split_track_id_sha256": {
                split_name: hashlib.sha256(
                    np.sort(split_track_ids[split_name]).astype("<i8").tobytes()
                ).hexdigest()
                for split_name in SPLIT_NAMES
            },
            "split_artist_counts": {
                split_name: len(split_artist_ids[split_name])
                for split_name in SPLIT_NAMES
            },
            "artist_overlap_counts": artist_overlap_counts,
            "artist_disjoint": not any(artist_overlap_counts.values()),
        },
        "selection_policy": {
            "broad_labels": "all FMA-small track.genre_top labels",
            "child_source": "track.genres_all",
            "child_parent_rule": "top_level must be one of the selected broad labels",
            "minimum_child_training_positives": args.min_child_train_positives,
            "minimum_child_validation_positives": (
                args.min_child_validation_positives
            ),
            "selection_uses_test_labels": False,
        },
        "training": {
            "classifier": "one L2 logistic regression per label",
            "class_weight": "balanced",
            "solver": "liblinear",
            "c_grid": list(c_grid),
            "c_selection_metric": "validation average precision",
            "calibration": "per-label Platt scaling on validation logits",
            "threshold_policy": (
                "maximize validation recall subject to target precision and "
                "minimum prediction count; otherwise abstain"
            ),
            "target_precision": args.target_precision,
            "minimum_threshold_predictions": args.min_threshold_predictions,
            "seed": args.seed,
            "test_used_for_training_selection_or_calibration": False,
        },
        "labels": label_metadata,
        "metrics": split_metrics,
        "runtime": {
            "python": platform.python_version(),
            "platform": platform.platform(),
            "numpy": np.__version__,
            "pandas": pd.__version__,
            "scikit_learn": sklearn.__version__,
            "torch": torch.__version__,
            "onnx": onnx.__version__,
            "onnxruntime": onnxruntime.__version__,
        },
        "command": {
            "store": str(store_path),
            "tracks": str(tracks_path),
            "genres": str(genres_path),
            "output_dir": str(output_dir),
            "model_name": args.model_name,
        },
    }
    metadata_path.write_text(
        json.dumps(json_safe(metadata), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    test = split_metrics["test"]
    print("")
    print("Held-out official FMA-small test results")
    print(f"  broad top-1 accuracy: {test['broad_top1_accuracy']:.4f}")
    print(
        "  broad macro AP / ROC-AUC: "
        f"{test['broad']['macro_average_precision']:.4f} / "
        f"{test['broad']['macro_roc_auc']:.4f}"
    )
    print(
        "  child macro AP / ROC-AUC: "
        f"{test['children']['macro_average_precision']:.4f} / "
        f"{test['children']['macro_roc_auc']:.4f}"
    )
    print(
        "  threshold micro precision / recall / coverage: "
        f"{test['exported_thresholds']['micro_precision']:.4f} / "
        f"{test['exported_thresholds']['micro_recall']:.4f} / "
        f"{test['exported_thresholds']['track_coverage']:.4f}"
    )
    print(f"  ONNX size: {onnx_path.stat().st_size / 1024.0:.1f} KiB")
    print(f"  ONNX parity max abs error: {onnx_maximum_error:.3g}")
    print(f"  metadata: {metadata_path}")
    print(f"  elapsed: {elapsed:.1f}s")


if __name__ == "__main__":
    main()
