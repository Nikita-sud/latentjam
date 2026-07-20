#!/usr/bin/env python3
"""Sweep a seed-genre intent guard over LatentJam's real next-track examples.

The learned scorer is intentionally robust to missing and misleading metadata, but a queue also
needs to remain recognisably anchored to the track the listener explicitly chose. This benchmark
measures a small, support-gated *seed* genre penalty on top of the shipped scorer and geometric
chain terms. It reports the trade-off between held-out next-track ranking and genre continuity,
plus the incremental harm when only the seed's genre field is corrupted.

Titles never enter this rule. A title such as ``"Jazz mix"`` is therefore incapable of activating
or changing the guard.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import benchmark_hub_approximation as hub_bench  # noqa: E402
import smart_conditioning_960_experiment as conditioned  # noqa: E402
import smart_conditioning_experiment as base  # noqa: E402


ALIASES = (
    ("hip", "rap"), ("rap", "rap"), ("trap", "rap"), ("phonk", "rap"),
    ("rock", "rock"), ("metal", "rock"), ("punk", "rock"), ("grunge", "rock"),
    ("pop", "pop"), ("dance", "dance"), ("electronic", "dance"), ("edm", "dance"),
    ("house", "dance"), ("techno", "dance"),
    ("classical", "classical"), ("orchestral", "classical"), ("baroque", "classical"),
    ("soundtrack", "soundtrack"), ("score", "soundtrack"),
)
HUB_TOKENS = {
    "ost", "soundtrack", "score", "anime", "cinematic", "orchestral", "game", "ambient",
    "library", "western",
}
HUB_SPLIT = re.compile(r"[^a-zа-яё]+")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--scorer", required=True, type=Path)
    parser.add_argument("--residual", required=True, type=Path)
    parser.add_argument("--text-model", required=True, type=Path)
    parser.add_argument("--text-vocab", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--penalties", default="1.0,0.9,0.8,0.75,0.7,0.6")
    parser.add_argument("--minimum-support", type=int, default=6)
    return parser.parse_args()


def genre_family(raw: str) -> str | None:
    value = raw.lower().strip()
    if value in ("", "<unknown>", "unknown", "other"):
        return None
    for needle, family in ALIASES:
        if needle in value:
            return family
    return value


def language(meta: base.TrackMeta) -> str:
    for character in meta.title + meta.artist:
        code = ord(character)
        if 0x0400 <= code <= 0x04FF:
            return "ru"
        if 0x3040 <= code <= 0x30FF or 0x4E00 <= code <= 0x9FFF:
            return "ja"
    return "en"


def is_hub(meta: base.TrackMeta) -> bool:
    return any(token in HUB_TOKENS for token in HUB_SPLIT.split(meta.genre.lower()))


def pairwise_multiplier(
    anchor: base.TrackMeta,
    candidate: base.TrackMeta,
    anchor_energy: float,
    candidate_energy: float,
) -> float:
    value = 1.0
    anchor_genre = genre_family(anchor.genre)
    candidate_genre = genre_family(candidate.genre)
    if anchor_genre is not None and candidate_genre is not None:
        value *= 1.20 if anchor_genre == candidate_genre else 0.90
    if language(anchor) != language(candidate):
        value *= 0.75
    if anchor.year is not None and candidate.year is not None:
        value *= 1.0 - 0.04 * abs(anchor.year - candidate.year) / 10.0
    if is_hub(candidate) and not is_hub(anchor):
        value *= 0.60
    if np.isfinite(anchor_energy) and np.isfinite(candidate_energy):
        over = abs(anchor_energy - candidate_energy) - 0.20
        if over > 0:
            value *= max(1.0 - over, 0.70)
    return float(np.clip(value, 0.05, 2.0))


def corrupted_family(meta: base.TrackMeta, index: int) -> str:
    return genre_family(base.conflicting_genre(meta, index)) or "__wrong__"


@dataclass
class Examples:
    states: np.ndarray
    pools: np.ndarray
    logits: np.ndarray
    targets: np.ndarray
    target_positions: np.ndarray
    pool_hit: np.ndarray
    anchors: np.ndarray


def residual_logits(
    session,
    base_logits: np.ndarray,
    states: np.ndarray,
    candidates: np.ndarray,
    text_state: np.ndarray,
    text_candidates: np.ndarray,
    mask: np.ndarray,
    batch: int = 256,
) -> np.ndarray:
    output: list[np.ndarray] = []
    for start in range(0, len(states), batch):
        end = min(start + batch, len(states))
        output.append(session.run(None, {
            "base_scores": base_logits[start:end].astype(np.float32),
            "state": states[start:end].astype(np.float32),
            "candidates": candidates[start:end].astype(np.float32),
            "text_state": text_state[start:end].astype(np.float32),
            "text_candidates": text_candidates[start:end].astype(np.float32),
            "text_mask": mask[start:end].astype(np.float32),
        })[0])
    return np.concatenate(output).astype(np.float32)


def make_examples(
    library: base.Library,
    events: base.EventExamples,
    trusted_text: np.ndarray,
    state_session,
    scorer_session,
    residual_session,
    mode: str,
) -> Examples:
    rows = conditioned.phone_cold_rows(library, events) if mode == "cold" else events.rows960
    states = base.teacher_states(state_session, rows)
    centered = base.normalize_rows(library.audio960 - library.audio960.mean(0, keepdims=True))
    hub, _ = hub_bench.approximate_hub(centered, library.ids, 64)
    pools = np.empty((len(states), base.POOL_SIZE), dtype=np.int32)
    positions = np.zeros(len(states), dtype=np.int32)
    hits = np.zeros(len(states), dtype=bool)
    for index, state in enumerate(states):
        anchor = int(events.rows960.anchor[index])
        pool = base.candidate_pool(
            anchor, state, library.audio960, centered, hub,
            trusted_text[anchor], trusted_text, mode="round_robin",
        )
        match = np.flatnonzero(pool == events.target[index])
        hits[index] = len(match) > 0
        if len(match):
            positions[index] = int(match[0])
        else:
            # Preserve the training/evaluation contract: score a forced target, but give it zero
            # end-to-end credit because production retrieval would never have exposed it.
            pool[-1] = events.target[index]
            positions[index] = base.POOL_SIZE - 1
        pools[index] = pool

    candidates = library.audio960[pools]
    base_logits = base.teacher_logits(scorer_session, states, pools, library.audio960)
    text_state = (
        trusted_text[events.rows960.anchor]
        if mode == "cold"
        else base.event_text_states(events, trusted_text)
    )
    text_candidates = trusted_text[pools]
    mask = np.ones((len(states), base.POOL_SIZE), dtype=np.float32)
    logits = residual_logits(
        residual_session, base_logits, states, candidates, text_state, text_candidates, mask,
    )
    return Examples(
        states=states,
        pools=pools,
        logits=logits,
        targets=events.target,
        target_positions=positions,
        pool_hit=hits,
        anchors=events.rows960.anchor,
    )


def score_examples(
    library: base.Library,
    examples: Examples,
    centered: np.ndarray,
    mismatch_penalty: float,
    minimum_support: int,
    corrupt_seed: bool = False,
) -> dict[str, float | int]:
    count = len(examples.pools)
    scores = 1.5 * np.tanh(examples.logits / 2.0)
    known_seed = np.zeros(count, dtype=bool)
    same_target = np.zeros(count, dtype=bool)
    activated = np.zeros(count, dtype=bool)

    for index, pool in enumerate(examples.pools):
        anchor_row = int(examples.anchors[index])
        anchor = library.meta[anchor_row]
        actual_seed_family = genre_family(anchor.genre)
        seed_family = (
            corrupted_family(anchor, index) if corrupt_seed and actual_seed_family is not None
            else actual_seed_family
        )
        known_seed[index] = seed_family is not None
        target_family = genre_family(library.meta[int(examples.targets[index])].genre)
        same_target[index] = actual_seed_family is not None and actual_seed_family == target_family

        families = [genre_family(library.meta[int(row)].genre) for row in pool]
        support = sum(family == seed_family for family in families) if seed_family is not None else 0
        activated[index] = support >= minimum_support
        anchor_energy = float(library.energy[anchor_row])
        for position, candidate_row_value in enumerate(pool):
            candidate_row = int(candidate_row_value)
            cosine = float(centered[anchor_row] @ centered[candidate_row])
            multiplier = pairwise_multiplier(
                anchor,
                library.meta[candidate_row],
                anchor_energy,
                float(library.energy[candidate_row]),
            )
            if activated[index] and families[position] is not None and families[position] != seed_family:
                multiplier *= mismatch_penalty
            scores[index, position] += 5.5 * cosine + math.log(max(multiplier, 0.05))

    order = np.argsort(-scores, axis=1)
    ranks = np.asarray([
        int(np.flatnonzero(order[index] == examples.target_positions[index])[0]) + 1
        for index in range(count)
    ])
    reciprocal = np.where(examples.pool_hit, 1.0 / ranks, 0.0)
    top_rows = examples.pools[np.arange(count), order[:, 0]]
    top_same = np.asarray([
        genre_family(library.meta[int(anchor)].genre)
        == genre_family(library.meta[int(top)].genre)
        for anchor, top in zip(examples.anchors, top_rows, strict=True)
    ])

    def mean_or_zero(values: np.ndarray) -> float:
        return float(values.mean()) if len(values) else 0.0

    return {
        "mrr_end_to_end": float(reciprocal.mean()),
        "recall_at_1_end_to_end": float(np.mean((ranks <= 1) & examples.pool_hit)),
        "recall_at_5_end_to_end": float(np.mean((ranks <= 5) & examples.pool_hit)),
        "top1_same_seed_family": mean_or_zero(top_same[known_seed]),
        "same_family_target_mrr": mean_or_zero(reciprocal[same_target]),
        "cross_family_target_mrr": mean_or_zero(reciprocal[known_seed & ~same_target]),
        "guard_activation_rate": float(activated.mean()),
        "known_seed_examples": int(known_seed.sum()),
    }


def main() -> None:
    args = parse_args()
    library = base.load_library(args.db, args.metadata)
    events = base.build_event_examples(library, base.load_events(args.db))
    mini_lm = base.MiniLm(args.text_model, args.text_vocab)
    trusted_text = mini_lm.encode([meta.text(title="") for meta in library.meta])
    state_session = base.ort_session(args.state)
    scorer_session = base.ort_session(args.scorer)
    residual_session = base.ort_session(args.residual)
    centered = base.normalize_rows(library.audio960 - library.audio960.mean(0, keepdims=True))

    penalties = [float(value) for value in args.penalties.split(",")]
    report: dict[str, object] = {
        "tracks": len(library.ids),
        "positive_examples": len(events.target),
        "candidate_pool": "production 3-lane round-robin with deterministic 64-row hub reference",
        "minimum_same_family_support": args.minimum_support,
        "title_contract": "titles are not read by the seed-intent guard",
        "modes": {},
    }
    for mode in ("full", "cold"):
        examples = make_examples(
            library, events, trusted_text, state_session, scorer_session, residual_session, mode,
        )
        values = {}
        for penalty in penalties:
            values[str(penalty)] = {
                "correct_metadata": score_examples(
                    library, examples, centered, penalty, args.minimum_support,
                ),
                "seed_genre_corrupted_only": score_examples(
                    library, examples, centered, penalty, args.minimum_support, corrupt_seed=True,
                ),
            }
        report["modes"][mode] = {
            "pool_recall": float(examples.pool_hit.mean()),
            "penalties": values,
        }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
