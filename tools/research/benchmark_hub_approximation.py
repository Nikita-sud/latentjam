#!/usr/bin/env python3
"""Measure bounded hub-density estimates against the exact SMART candidate pool.

The phone previously recomputed an N x N cosine matrix for every queue.  This
benchmark keeps the query and ranking contract fixed while replacing only the
hub-density reference set with a deterministic sample selected from track ids.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import smart_conditioning_experiment as base  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--teacher-state", required=True, type=Path)
    parser.add_argument("--teacher-scorer", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--samples", default="32,64,96,128,192")
    return parser.parse_args()


def stable_hash(value: str) -> int:
    """Unsigned FNV-1a/64; trivial to reproduce in common Kotlin."""
    result = 0xCBF29CE484222325
    for byte in value.encode("utf-8"):
        result ^= byte
        result = (result * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return result


def load_library(db: Path) -> base.Library:
    ids: list[str] = []
    audio: list[np.ndarray] = []
    text: list[np.ndarray] = []
    has_text: list[bool] = []
    connection = sqlite3.connect(db)
    for track_id, audio_blob, text_blob in connection.execute(
        "SELECT songUid, embedding, textEmbedding FROM TrackEmbeddingEntity ORDER BY songUid"
    ):
        audio_vector = base.blob_vector(audio_blob, base.AUDIO_DIM)
        if audio_vector is None or np.linalg.norm(audio_vector) < 1e-3:
            continue
        text_vector = base.blob_vector(text_blob, base.TEXT_DIM)
        ids.append(str(track_id))
        audio.append(audio_vector)
        if text_vector is None or np.linalg.norm(text_vector) < 1e-6:
            text.append(np.zeros(base.TEXT_DIM, dtype=np.float32))
            has_text.append(False)
        else:
            text.append(text_vector)
            has_text.append(True)
    connection.close()
    count = len(ids)
    return base.Library(
        ids=ids,
        audio960=base.normalize_rows(np.stack(audio).astype(np.float32)),
        text=base.normalize_rows(np.stack(text).astype(np.float32)),
        has_text=np.asarray(has_text, dtype=bool),
        energy=np.full(count, np.nan, dtype=np.float32),
        meta=[base.TrackMeta(track_id, "", "", "", None) for track_id in ids],
    )


def approximate_hub(
    centered: np.ndarray,
    ids: list[str],
    references: int,
) -> tuple[np.ndarray, float]:
    started = time.perf_counter()
    order = np.argsort(np.asarray([stable_hash(value) for value in ids], dtype=np.uint64))
    reference_rows = order[: min(references, len(order))]
    similarities = centered @ centered[reference_rows].T
    inverse = {int(row): column for column, row in enumerate(reference_rows)}
    for row, column in inverse.items():
        similarities[row, column] = -9.0
    k = min(base.HUB_TOPK if hasattr(base, "HUB_TOPK") else 10, similarities.shape[1] - 1)
    top = np.partition(similarities, -k, axis=1)[:, -k:].mean(1)
    result = top - top.mean()
    return result.astype(np.float32), (time.perf_counter() - started) * 1000


def pools_for(
    library: base.Library,
    examples: base.EventExamples,
    states: np.ndarray,
    centered: np.ndarray,
    hub: np.ndarray,
) -> np.ndarray:
    pools = np.empty((len(states), base.POOL_SIZE), dtype=np.int32)
    for index, state in enumerate(states):
        anchor = int(examples.rows960.anchor[index])
        pools[index] = base.candidate_pool(
            anchor,
            state,
            library.audio960,
            centered,
            hub,
            examples.text_state[index],
            library.text,
            mode="round_robin",
        )
    return pools


def summarize(
    pools: np.ndarray,
    exact: np.ndarray,
    targets: np.ndarray,
    logits: np.ndarray,
) -> dict[str, float]:
    target_positions = np.full(len(targets), -1, dtype=np.int32)
    for index, (target, pool) in enumerate(zip(targets, pools, strict=True)):
        matches = np.flatnonzero(pool == target)
        if len(matches):
            target_positions[index] = int(matches[0])
    hits = target_positions >= 0
    ranks = np.argsort(np.argsort(-logits, axis=1), axis=1) + 1
    reciprocal = np.zeros(len(targets), dtype=np.float32)
    reciprocal[hits] = 1.0 / ranks[np.flatnonzero(hits), target_positions[hits]]
    recall = hits.mean()
    overlap = np.mean([
        len(set(pool).intersection(reference)) / len(reference)
        for pool, reference in zip(pools, exact, strict=True)
    ])
    anchor_lane_overlap = np.mean([
        len(set(pool[:34]).intersection(reference[:34])) / 34
        for pool, reference in zip(pools, exact, strict=True)
    ])
    return {
        "target_pool_recall": float(recall),
        "acoustic_scorer_mrr_end_to_end": float(reciprocal.mean()),
        "pool_overlap": float(overlap),
        "head_34_overlap": float(anchor_lane_overlap),
    }


def main() -> None:
    args = parse_args()
    library = load_library(args.db)
    examples = base.build_event_examples(library, base.load_events(args.db))
    states = base.teacher_states(base.ort_session(args.teacher_state), examples.rows960)
    scorer = base.ort_session(args.teacher_scorer)

    exact_started = time.perf_counter()
    centered, exact_hub = base.centered_and_hub(library.audio960)
    exact_hub_ms = (time.perf_counter() - exact_started) * 1000
    exact_pools = pools_for(library, examples, states, centered, exact_hub)
    exact_logits = base.teacher_logits(scorer, states, exact_pools, library.audio960)

    report: dict[str, object] = {
        "tracks": len(library.ids),
        "examples": len(states),
        "exact": {
            "hub_ms": exact_hub_ms,
            **summarize(exact_pools, exact_pools, examples.target, exact_logits),
        },
        "approximations": {},
    }
    for sample in [int(value) for value in args.samples.split(",")]:
        hub, hub_ms = approximate_hub(centered, library.ids, sample)
        pools = pools_for(library, examples, states, centered, hub)
        logits = base.teacher_logits(scorer, states, pools, library.audio960)
        report["approximations"][str(sample)] = {
            "hub_ms": hub_ms,
            "hub_correlation": float(np.corrcoef(exact_hub, hub)[0, 1]),
            **summarize(pools, exact_pools, examples.target, logits),
        }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
