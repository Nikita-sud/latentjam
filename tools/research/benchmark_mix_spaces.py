#!/usr/bin/env python3
"""Compare the actual My Mixes embedding spaces on the phone-library holdout.

The production app currently clusters MiniLM vectors made from
``genre; artist; year``.  This benchmark recreates that path with the shipped
ONNX/vocabulary, then compares it with the already-persisted 960-d audio space
and fixed audio/text hybrids.  No labels are used to fit any candidate.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd
from sklearn.cluster import KMeans
from transformers import BertTokenizer


EPS = 1e-12


def l2(rows: np.ndarray) -> np.ndarray:
    return (rows / np.clip(np.linalg.norm(rows, axis=1, keepdims=True), EPS, None)).astype(
        np.float32,
        copy=False,
    )


def metadata_string(row: pd.Series) -> str:
    values = []
    for key in ("genre", "artist"):
        value = row.get(key)
        if value is not None and not pd.isna(value) and str(value).strip():
            values.append(str(value).strip())
    year = row.get("year")
    if year is not None and not pd.isna(year) and int(year) > 0:
        values.append(str(int(year)))
    return "; ".join(values)


def encode_text(
    frame: pd.DataFrame,
    model_path: Path,
    vocab_path: Path,
    *,
    batch_size: int = 32,
) -> np.ndarray:
    tokenizer = BertTokenizer(vocab_file=str(vocab_path), do_lower_case=True)
    session = ort.InferenceSession(
        str(model_path),
        providers=["CPUExecutionProvider"],
        sess_options=ort.SessionOptions(),
    )
    texts = [metadata_string(row) for _, row in frame.iterrows()]
    outputs = []
    for start in range(0, len(texts), batch_size):
        encoded = tokenizer(
            texts[start : start + batch_size],
            padding=True,
            truncation=True,
            max_length=64,
            return_tensors="np",
        )
        ids = encoded["input_ids"].astype(np.int64)
        mask = encoded["attention_mask"].astype(np.int64)
        token_types = encoded.get("token_type_ids", np.zeros_like(ids)).astype(np.int64)
        tokens = session.run(
            None,
            {
                "input_ids": ids,
                "attention_mask": mask,
                "token_type_ids": token_types,
            },
        )[0].astype(np.float32)
        weighted = tokens * mask[:, :, None]
        pooled = weighted.sum(axis=1) / np.clip(mask.sum(axis=1, keepdims=True), 1, None)
        outputs.append(l2(pooled))
    return np.concatenate(outputs, axis=0)


def membership(
    frame: pd.DataFrame,
    playlist_rows: pd.DataFrame,
) -> list[set[str]]:
    by_id: dict[str, set[str]] = defaultdict(set)
    for row in playlist_rows.itertuples(index=False):
        by_id[str(row.track_id)].add(str(row.playlist_id).removeprefix("phone-"))
    return [by_id[str(track_id)] for track_id in frame["track_id"].astype(str)]


def playlist_purity(
    features: np.ndarray,
    memberships: list[set[str]],
    *,
    k: int = 10,
) -> tuple[float, dict[str, float]]:
    similarity = l2(features) @ l2(features).T
    np.fill_diagonal(similarity, -np.inf)
    neighbours = np.argpartition(-similarity, kth=k - 1, axis=1)[:, :k]
    scores: dict[str, list[float]] = defaultdict(list)
    for row, own in enumerate(memberships):
        for playlist in own:
            scores[playlist].append(
                sum(playlist in memberships[int(other)] for other in neighbours[row]) / k
            )
    per_playlist = {label: float(np.mean(values)) for label, values in scores.items()}
    return (
        float(np.mean(list(per_playlist.values()))) if per_playlist else 0.0,
        per_playlist,
    )


def normalize_genre(value: object) -> str | None:
    if value is None or pd.isna(value):
        return None
    raw = str(value).strip().lower()
    if not raw:
        return None
    families = (
        (
            "rap",
            (
                "hip-hop",
                "hip hop",
                "rap",
                "trap",
                "phonk",
                "drill",
                "grime",
                "boom bap",
            ),
        ),
        ("rock", ("rock", "metal", "punk", "grunge", "emo")),
        (
            "electronic",
            (
                "electronic",
                "edm",
                "dance",
                "house",
                "techno",
                "trance",
                "dubstep",
                "drum and bass",
                "ambient",
                "synth",
            ),
        ),
        ("pop", ("pop", "k-pop", "j-pop")),
        ("jazz", ("jazz", "swing")),
        ("classical", ("classical", "orchestral", "opera", "score", "soundtrack", "ost")),
        ("folk", ("folk", "country", "bluegrass", "traditional")),
        ("r&b", ("r&b", "rnb", "rhythm and blues", "soul", "funk")),
        ("reggae", ("reggae", "ska", "dancehall")),
        ("latin", ("latin", "salsa", "bachata", "reggaeton", "bossa")),
    )
    for family, needles in families:
        if any(needle in raw for needle in needles):
            return family
    return raw


def genre_purity(features: np.ndarray, frame: pd.DataFrame, *, k: int = 10) -> float:
    """Mean same-family rate among neighbours for tracks with a known broad genre."""
    labels = np.asarray([normalize_genre(value) for value in frame["genre"]], dtype=object)
    similarity = l2(features) @ l2(features).T
    np.fill_diagonal(similarity, -np.inf)
    neighbours = np.argpartition(-similarity, kth=k - 1, axis=1)[:, :k]
    scores = []
    for row, own in enumerate(labels):
        if own is None:
            continue
        comparable = [int(other) for other in neighbours[row] if labels[int(other)] is not None]
        if comparable:
            scores.append(sum(labels[other] == own for other in comparable) / len(comparable))
    return float(np.mean(scores)) if scores else 0.0


def neighbour_overlap(left: np.ndarray, right: np.ndarray, *, k: int = 10) -> float:
    """Fraction of top-k neighbours shared by two independently encoded spaces."""
    left_similarity = l2(left) @ l2(left).T
    right_similarity = l2(right) @ l2(right).T
    np.fill_diagonal(left_similarity, -np.inf)
    np.fill_diagonal(right_similarity, -np.inf)
    left_neighbours = np.argpartition(-left_similarity, kth=k - 1, axis=1)[:, :k]
    right_neighbours = np.argpartition(-right_similarity, kth=k - 1, axis=1)[:, :k]
    return float(
        np.mean(
            [
                len(set(left_neighbours[row]) & set(right_neighbours[row])) / k
                for row in range(len(left))
            ]
        )
    )


def cluster_summary(
    features: np.ndarray,
    frame: pd.DataFrame,
    memberships: list[set[str]],
    *,
    k: int,
) -> dict:
    normalized = l2(features)
    centered = l2(normalized - normalized.mean(axis=0, keepdims=True))
    assignment = KMeans(n_clusters=k, n_init=20, random_state=0).fit_predict(centered)
    genre = np.asarray([normalize_genre(value) for value in frame["genre"]], dtype=object)
    weighted_genre_correct = 0
    weighted_playlist_correct = 0
    genre_known = 0
    playlist_known = 0
    clusters = []
    for group in range(k):
        rows = np.flatnonzero(assignment == group)
        centroid = l2(centered[rows].mean(axis=0, keepdims=True))[0]
        medoid = int(rows[np.argmax(centered[rows] @ centroid)])
        known_genres = [str(genre[row]) for row in rows if genre[row] is not None]
        genre_counts: dict[str, int] = defaultdict(int)
        for label in known_genres:
            genre_counts[label] += 1
        playlist_counts: dict[str, int] = defaultdict(int)
        for row in rows:
            for label in memberships[int(row)]:
                playlist_counts[label] += 1
        if genre_counts:
            weighted_genre_correct += max(genre_counts.values())
            genre_known += len(known_genres)
        if playlist_counts:
            weighted_playlist_correct += max(playlist_counts.values())
            playlist_known += sum(bool(memberships[int(row)]) for row in rows)
        clusters.append(
            {
                "size": len(rows),
                "medoid": {
                    "artist": str(frame.iloc[medoid].get("artist") or ""),
                    "title": str(frame.iloc[medoid].get("title") or ""),
                    "genre": str(frame.iloc[medoid].get("genre") or ""),
                },
                "top_genres": sorted(genre_counts.items(), key=lambda item: (-item[1], item[0]))[:4],
                "top_playlists": sorted(
                    playlist_counts.items(),
                    key=lambda item: (-item[1], item[0]),
                )[:4],
            }
        )
    return {
        "genre_cluster_purity": weighted_genre_correct / max(1, genre_known),
        "playlist_cluster_purity": weighted_playlist_correct / max(1, playlist_known),
        "clusters": sorted(clusters, key=lambda item: -item["size"]),
    }


def hybrid(audio: np.ndarray, text: np.ndarray, text_weight: float) -> np.ndarray:
    return l2(
        np.concatenate(
            (
                l2(audio) * np.sqrt(1.0 - text_weight),
                l2(text) * np.sqrt(text_weight),
            ),
            axis=1,
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--store",
        type=Path,
        default=Path(
            "/Users/nichitabulgaru/Documents/LJ/synth-data-2026-07-15/"
            "store_dev841.parquet"
        ),
    )
    parser.add_argument(
        "--playlists",
        type=Path,
        default=Path(
            "/Users/nichitabulgaru/Documents/LJ/latentjam-research/"
            "data/manifests/phone_playlists.csv"
        ),
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=Path("androidApp/src/main/assets/ml/text_encoder_minilm.onnx"),
    )
    parser.add_argument(
        "--vocab",
        type=Path,
        default=Path("androidApp/src/main/assets/ml/text_vocab.txt"),
    )
    parser.add_argument(
        "--max-tracks",
        type=int,
        help="Optional deterministic sample size for external-library stress tests.",
    )
    parser.add_argument(
        "--skip-clusters",
        action="store_true",
        help="Run the faster neighbour-only ablation without k-means summaries.",
    )
    parser.add_argument(
        "--only",
        action="append",
        help="Evaluate only the named vector space; may be repeated.",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    frame = pd.read_parquet(args.store)
    if args.max_tracks is not None and len(frame) > args.max_tracks:
        frame = frame.sample(n=args.max_tracks, random_state=0).reset_index(drop=True)
    playlist_rows = pd.read_csv(args.playlists)
    audio = l2(np.stack(frame["embedding"].map(lambda item: np.asarray(item, dtype=np.float32))))
    text = encode_text(frame, args.model, args.vocab)
    memberships = membership(frame, playlist_rows)
    k = min(16, max(8, (len(frame) + 59) // 60))

    spaces = {
        "audio": audio,
        "text": text,
        "audio75_text25": hybrid(audio, text, 0.25),
        "audio50_text50": hybrid(audio, text, 0.50),
        "audio40_text60": hybrid(audio, text, 0.60),
        "audio35_text65": hybrid(audio, text, 0.65),
        "audio30_text70": hybrid(audio, text, 0.70),
        "audio25_text75": hybrid(audio, text, 0.75),
    }
    if args.only:
        unknown = set(args.only) - spaces.keys()
        if unknown:
            parser.error(f"unknown --only space(s): {', '.join(sorted(unknown))}")
        spaces = {name: spaces[name] for name in args.only}
    results = {}
    for name, features in spaces.items():
        purity, per_playlist = playlist_purity(features, memberships)
        results[name] = {
            "playlist_purity_at_10": purity,
            "genre_purity_at_10": genre_purity(features, frame),
            "per_playlist": per_playlist,
        }
        if not args.skip_clusters:
            results[name].update(cluster_summary(features, frame, memberships, k=k))

    output = {
        "n_tracks": len(frame),
        "n_tracks_with_playlist": sum(bool(value) for value in memberships),
        "k": k,
        "audio_text_neighbour_overlap_at_10": neighbour_overlap(audio, text),
        "spaces": results,
    }
    print(json.dumps(output, indent=2, ensure_ascii=False))
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, indent=2, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
