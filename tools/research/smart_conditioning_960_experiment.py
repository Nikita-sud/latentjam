#!/usr/bin/env python3
"""Cross-validate a learned optional-text residual over LatentJam's 960-d scorer.

The deployed acoustic state and scorer remain frozen.  The only trainable component is a small
bounded residual that consumes the frozen scorer logits, audio cosine, trusted MiniLM metadata,
and an explicit availability mask.  Missing text is therefore an exact audio-only fallback.
"""

from __future__ import annotations

import argparse
import json
import random
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from torch import nn

import smart_conditioning_experiment as base


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--teacher-state", type=Path, required=True)
    parser.add_argument("--teacher-scorer", type=Path, required=True)
    parser.add_argument("--text-model", type=Path, required=True)
    parser.add_argument("--text-vocab", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--seed", type=int, default=19)
    parser.add_argument("--epochs", type=int, default=70)
    parser.add_argument("--folds", type=int, default=4)
    return parser.parse_args()


@dataclass
class Examples:
    state: np.ndarray
    candidates: np.ndarray
    candidate_rows: np.ndarray
    base_logits: np.ndarray
    text_state: np.ndarray
    text_candidates: np.ndarray
    target_position: np.ndarray
    sessions: np.ndarray
    pool_hit: np.ndarray
    context_mode: np.ndarray


class TextResidual(nn.Module):
    """A learned bounded correction; no hand-tuned text/audio interpolation weight."""

    def __init__(self, projection: int = 48, residual_cap: float = 0.75) -> None:
        super().__init__()
        self.text_project = nn.Linear(base.TEXT_DIM, projection)
        feature_dim = 4 * projection + 4
        self.residual = nn.Sequential(
            nn.LayerNorm(feature_dim), nn.Linear(feature_dim, 96), nn.GELU(),
            nn.Linear(96, 48), nn.GELU(), nn.Linear(48, 1),
        )
        self.gate = nn.Sequential(nn.Linear(5, 16), nn.GELU(), nn.Linear(16, 1))
        self.residual_cap = residual_cap

    def forward(self, base_logits, state, candidates, text_state, text_candidates, text_mask):
        state_text = self.text_project(text_state).unsqueeze(1)
        candidate_text = self.text_project(text_candidates)
        expanded = state_text.expand(-1, candidates.shape[1], -1)
        audio_cosine = (state.unsqueeze(1) * candidates).sum(-1, keepdim=True)
        text_cosine = (text_state.unsqueeze(1) * text_candidates).sum(-1, keepdim=True)
        disagreement = (audio_cosine - text_cosine).abs()
        base_feature = torch.tanh(base_logits).unsqueeze(-1)
        features = torch.cat([
            expanded, candidate_text, expanded * candidate_text,
            (expanded - candidate_text).abs(), audio_cosine, text_cosine,
            disagreement, base_feature,
        ], dim=-1)
        raw = self.residual(features).squeeze(-1)
        gate_features = torch.cat([
            audio_cosine, text_cosine, disagreement, base_feature,
            text_mask.unsqueeze(-1),
        ], dim=-1)
        gate = torch.sigmoid(self.gate(gate_features).squeeze(-1)) * text_mask
        return base_logits + gate * self.residual_cap * torch.tanh(raw)


def phone_cold_rows(library, events):
    """Reproduce SmartChain's first-hop state inputs for every observed anchor/target pair."""
    anchors = events.rows960.anchor
    history = np.zeros((len(anchors), base.CONTEXT_K, base.AUDIO_DIM + 1), dtype=np.float32)
    history[:, :, :base.AUDIO_DIM] = library.audio960[anchors, None, :]
    history[:, :, base.AUDIO_DIM] = 1.0
    recent = library.audio960[anchors]
    session = np.tile(
        np.asarray([np.log(2.0), 0.0, np.log(1.5), 1.0, 1.0], dtype=np.float32),
        (len(anchors), 1),
    )
    return base.StateRows(
        history=history,
        medium=recent.copy(),
        large=recent.copy(),
        time=events.rows960.time.copy(),
        session=session,
        anchor=anchors.copy(),
        source=np.full(len(anchors), 3, dtype=np.int8),
    )


def build_examples(library, events, state_session, scorer_session, trusted_text, mode) -> Examples:
    rows = phone_cold_rows(library, events) if mode == "cold" else events.rows960
    states = base.teacher_states(state_session, rows)
    centered, hub = base.centered_and_hub(library.audio960)
    pools = np.empty((len(states), base.POOL_SIZE), dtype=np.int32)
    positions = np.empty(len(states), dtype=np.int64)
    hits = np.empty(len(states), dtype=bool)
    for i, state in enumerate(states):
        anchor = int(events.rows960.anchor[i])
        pool = base.candidate_pool(
            anchor, state, library.audio960, centered, hub,
            trusted_text[anchor], trusted_text, mode="round_robin",
        )
        where = np.where(pool == events.target[i])[0]
        hits[i] = len(where) > 0
        if len(where) == 0:
            pool[-1] = events.target[i]
            positions[i] = base.POOL_SIZE - 1
        else:
            positions[i] = int(where[0])
        pools[i] = pool
    logits = base.teacher_logits(scorer_session, states, pools, library.audio960)
    return Examples(
        state=states,
        candidates=library.audio960[pools],
        candidate_rows=pools,
        base_logits=logits,
        text_state=(
            trusted_text[events.rows960.anchor]
            if mode == "cold"
            else base.event_text_states(events, trusted_text)
        ),
        text_candidates=trusted_text[pools],
        target_position=positions,
        sessions=events.sessions,
        pool_hit=hits,
        context_mode=np.full(len(states), mode),
    )


def combine_examples(*parts: Examples) -> Examples:
    return Examples(**{
        field: np.concatenate([getattr(part, field) for part in parts], axis=0)
        for field in Examples.__dataclass_fields__
    })


def predict(model, examples, indices, candidate_text, state_text, mask, device, batch=256):
    result = []
    model.eval()
    with torch.no_grad():
        for start in range(0, len(indices), batch):
            idx = indices[start:start + batch]
            result.append(model(
                torch.from_numpy(examples.base_logits[idx]).to(device),
                torch.from_numpy(examples.state[idx]).to(device),
                torch.from_numpy(examples.candidates[idx]).to(device),
                torch.from_numpy(state_text[idx]).to(device),
                torch.from_numpy(candidate_text[idx]).to(device),
                torch.from_numpy(mask[idx]).to(device),
            ).cpu().numpy())
    return np.concatenate(result)


def train(examples, variants, state_variants, train_indices, val_indices, device, epochs, seed):
    rng = np.random.default_rng(seed)
    torch.manual_seed(seed)
    model = TextResidual().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=2e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    mask_all = np.ones(examples.base_logits.shape, dtype=np.float32)
    best = -1.0
    best_state = None
    history = []
    for epoch in range(epochs):
        model.train()
        losses = []
        for shuffled in base.tensor_batches(len(train_indices), 128, rng):
            idx = train_indices[shuffled]
            baseline = torch.from_numpy(examples.base_logits[idx]).to(device)
            state = torch.from_numpy(examples.state[idx]).to(device)
            candidates = torch.from_numpy(examples.candidates[idx]).to(device)
            text_state = torch.from_numpy(examples.text_state[idx]).to(device)
            correct_text = torch.from_numpy(examples.text_candidates[idx]).to(device)
            mask = torch.ones_like(baseline)
            target = torch.from_numpy(examples.target_position[idx]).to(device)
            logits = model(baseline, state, candidates, text_state, correct_text, mask)
            per_example = F.cross_entropy(logits, target, reduction="none")
            hit = torch.from_numpy(examples.pool_hit[idx].astype(np.float32)).to(device)
            loss = (per_example * hit).sum() / hit.sum().clamp_min(1.0)

            rows = examples.candidate_rows[idx]
            # Title words are excluded by contract, so the injected-title condition is exact.
            title = torch.from_numpy(variants["title_poison"][rows]).to(device)
            title_state = torch.from_numpy(state_variants["title_poison"][idx]).to(device)
            title_logits = model(baseline, state, candidates, title_state, title, mask)
            loss = loss + 2.0 * F.mse_loss(title_logits, logits.detach())

            # Explicitly wrong tags and swapped metadata teach the branch to abstain.
            genre = torch.from_numpy(variants["genre_poison"][rows]).to(device)
            genre_state = torch.from_numpy(state_variants["genre_poison"][idx]).to(device)
            genre_logits = model(baseline, state, candidates, genre_state, genre, mask)
            loss = loss + 1.5 * F.mse_loss(genre_logits, baseline)
            shuffled_text = correct_text[:, torch.randperm(base.POOL_SIZE, device=device)]
            shuffled_logits = model(baseline, state, candidates, text_state, shuffled_text, mask)
            loss = loss + 1.5 * F.mse_loss(shuffled_logits, baseline)
            bad_state = text_state[torch.randperm(len(idx), device=device)]
            bad_state_logits = model(baseline, state, candidates, bad_state, correct_text, mask)
            loss = loss + F.mse_loss(bad_state_logits, baseline)
            missing = model(
                baseline, state, candidates, torch.zeros_like(text_state),
                torch.zeros_like(correct_text), torch.zeros_like(mask),
            )
            loss = loss + 2.0 * F.mse_loss(missing, baseline)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimizer.step()
            losses.append(float(loss.detach().cpu()))
        scheduler.step()
        logits = predict(
            model, examples, val_indices, examples.text_candidates, examples.text_state,
            mask_all, device,
        )
        metrics = base.rank_metrics(
            logits, examples.target_position[val_indices], examples.pool_hit[val_indices]
        )
        metrics.update({"epoch": epoch + 1, "loss": float(np.mean(losses))})
        history.append(metrics)
        if metrics["mrr_end_to_end"] > best:
            best = metrics["mrr_end_to_end"]
            best_state = {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}
    assert best_state is not None
    model.load_state_dict(best_state)

    correct = predict(
        model, examples, val_indices, examples.text_candidates, examples.text_state,
        mask_all, device,
    )
    evaluations = {}
    conditions = {
        "correct": (examples.text_candidates, examples.text_state, mask_all),
        "missing": (
            np.zeros_like(examples.text_candidates), np.zeros_like(examples.text_state),
            np.zeros_like(mask_all),
        ),
        "title_poison": (
            variants["title_poison"][examples.candidate_rows],
            state_variants["title_poison"], mask_all,
        ),
        "genre_poison": (
            variants["genre_poison"][examples.candidate_rows],
            state_variants["genre_poison"], mask_all,
        ),
    }
    baseline = examples.base_logits[val_indices]
    for name, (candidate_text, state_text, mask) in conditions.items():
        logits = predict(model, examples, val_indices, candidate_text, state_text, mask, device)
        values = base.rank_metrics(
            logits, examples.target_position[val_indices], examples.pool_hit[val_indices]
        )
        values["top1_vs_audio"] = float(np.mean(logits.argmax(1) == baseline.argmax(1)))
        values["top1_vs_correct"] = float(np.mean(logits.argmax(1) == correct.argmax(1)))
        values["max_abs_delta_vs_audio"] = float(np.max(np.abs(logits - baseline)))
        evaluations[name] = values
        for mode in np.unique(examples.context_mode):
            selected = val_indices[examples.context_mode[val_indices] == mode]
            mode_logits = predict(
                model, examples, selected, candidate_text, state_text, mask, device
            )
            mode_base = examples.base_logits[selected]
            mode_correct = correct[examples.context_mode[val_indices] == mode]
            mode_values = base.rank_metrics(
                mode_logits, examples.target_position[selected], examples.pool_hit[selected]
            )
            mode_values["top1_vs_audio"] = float(np.mean(
                mode_logits.argmax(1) == mode_base.argmax(1)
            ))
            mode_values["top1_vs_correct"] = float(np.mean(
                mode_logits.argmax(1) == mode_correct.argmax(1)
            ))
            mode_values["max_abs_delta_vs_audio"] = float(
                np.max(np.abs(mode_logits - mode_base))
            )
            evaluations[f"{name}_{mode}"] = mode_values
    return model, {"best_mrr_end_to_end": best, "evaluation": evaluations, "history": history}


def weighted_fold_metrics(fold_reports, condition):
    metrics = [fold["report"]["evaluation"][condition] for fold in fold_reports]
    total = sum(item["n"] for item in metrics)
    keys = [key for key in metrics[0] if key not in {"n", "max_abs_delta_vs_audio"}]
    result = {"n": total}
    for key in keys:
        result[key] = sum(item[key] * item["n"] for item in metrics) / total
    result["max_abs_delta_vs_audio"] = max(item["max_abs_delta_vs_audio"] for item in metrics)
    return result


def export_model(model, output):
    output.mkdir(parents=True, exist_ok=True)
    path = output / "predictor_text_residual_n100_960.onnx"
    cpu = model.cpu().eval()
    torch.onnx.export(
        cpu,
        (
            torch.zeros(1, base.POOL_SIZE),
            torch.zeros(1, base.AUDIO_DIM),
            torch.zeros(1, base.POOL_SIZE, base.AUDIO_DIM),
            torch.zeros(1, base.TEXT_DIM),
            torch.zeros(1, base.POOL_SIZE, base.TEXT_DIM),
            torch.ones(1, base.POOL_SIZE),
        ),
        str(path),
        input_names=[
            "base_scores", "state", "candidates", "text_state", "text_candidates", "text_mask",
        ],
        output_names=["scores"], opset_version=17, do_constant_folding=True,
        dynamic_axes={name: {0: "batch"} for name in [
            "base_scores", "state", "candidates", "text_state", "text_candidates",
            "text_mask", "scores",
        ]},
        dynamo=False,
    )
    session = base.ort_session(path)
    # ONNX contract check: missing text must preserve the supplied baseline exactly.
    rng = np.random.default_rng(7)
    base_scores = rng.normal(size=(1, base.POOL_SIZE)).astype(np.float32)
    zeros_text = np.zeros((1, base.TEXT_DIM), dtype=np.float32)
    zeros_candidates = np.zeros((1, base.POOL_SIZE, base.TEXT_DIM), dtype=np.float32)
    output_scores = session.run(None, {
        "base_scores": base_scores,
        "state": rng.normal(size=(1, base.AUDIO_DIM)).astype(np.float32),
        "candidates": rng.normal(size=(1, base.POOL_SIZE, base.AUDIO_DIM)).astype(np.float32),
        "text_state": zeros_text,
        "text_candidates": zeros_candidates,
        "text_mask": np.zeros((1, base.POOL_SIZE), dtype=np.float32),
    })[0]
    return {
        "path": str(path), "bytes": path.stat().st_size,
        "inputs": [(item.name, item.shape) for item in session.get_inputs()],
        "outputs": [(item.name, item.shape) for item in session.get_outputs()],
        "missing_text_max_abs_delta": float(np.max(np.abs(output_scores - base_scores))),
    }


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = torch.device(args.device if args.device != "cuda" or torch.cuda.is_available() else "cpu")
    started = time.time()
    library = base.load_library(args.db, args.metadata)
    events = base.build_event_examples(library, base.load_events(args.db))
    mini_lm = base.MiniLm(args.text_model, args.text_vocab)
    all_variants = base.text_variants(library, mini_lm)
    variants = {
        "correct": all_variants["trusted"],
        "title_poison": all_variants["trusted"],
        "genre_poison": all_variants["trusted_genre_poison"],
    }
    state_session = base.ort_session(args.teacher_state)
    scorer_session = base.ort_session(args.teacher_scorer)
    full_examples = build_examples(
        library, events, state_session, scorer_session, variants["correct"], "full",
    )
    cold_examples = build_examples(
        library, events, state_session, scorer_session, variants["correct"], "cold",
    )
    examples = combine_examples(full_examples, cold_examples)
    state_variants = {
        name: np.concatenate([
            base.event_text_states(events, text),
            text[events.rows960.anchor],
        ], axis=0)
        for name, text in variants.items()
    }
    baseline = {
        "full": base.rank_metrics(
            full_examples.base_logits,
            full_examples.target_position,
            full_examples.pool_hit,
        ),
        "cold": base.rank_metrics(
            cold_examples.base_logits,
            cold_examples.target_position,
            cold_examples.pool_hit,
        ),
    }
    folds = base.session_folds(examples.sessions, args.folds, args.seed)
    fold_reports = []
    for fold, (train_indices, val_indices) in enumerate(folds):
        print(f"fold={fold} train={len(train_indices)} val={len(val_indices)}", flush=True)
        _, report = train(
            examples, variants, state_variants, train_indices, val_indices,
            device, args.epochs, args.seed + fold,
        )
        fold_reports.append({
            "fold": fold, "train_n": int(len(train_indices)), "val_n": int(len(val_indices)),
            "report": report,
        })
        print(json.dumps(report["evaluation"]["correct"]), flush=True)

    indices = np.arange(len(examples.state))
    rng = np.random.default_rng(args.seed)
    rng.shuffle(indices)
    monitor = indices[:max(1, len(indices) // 10)]
    final_model, final_report = train(
        examples, variants, state_variants, indices, monitor,
        device, args.epochs, args.seed + 999,
    )
    torch.save({
        "model_state_dict": final_model.state_dict(),
        "config": {"text_projection": 48, "residual_cap": 0.75, "title_included": False},
        "metrics": final_report,
    }, args.output / "text_residual_960.pt")
    export = export_model(final_model, args.output / "onnx")
    aggregate = {
        condition: weighted_fold_metrics(fold_reports, condition)
        for condition in tuple(
            f"{base_condition}_{mode}"
            for base_condition in ("correct", "missing", "title_poison", "genre_poison")
            for mode in ("full", "cold")
        )
    }
    result = {
        "seed": args.seed,
        "library_tracks": len(library.ids),
        "real_events": len(base.load_events(args.db)),
        "real_positive_examples": len(full_examples.state),
        "training_context_examples": len(examples.state),
        "session_folds": args.folds,
        "pool_contract": "mixed 960-d context: observed local listening history plus exact cold-start phone contract, each with trusted-text round-robin and 100 candidates",
        "audio_only_baseline": baseline,
        "aggregate": aggregate,
        "folds": fold_reports,
        "final": final_report,
        "export": export,
        "elapsed_seconds": time.time() - started,
    }
    (args.output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({
        "result": str(args.output / "results.json"), "baseline": baseline,
        "aggregate": aggregate, "export": export,
        "elapsed_seconds": result["elapsed_seconds"],
    }, indent=2), flush=True)


if __name__ == "__main__":
    main()
