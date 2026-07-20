#!/usr/bin/env python3
"""Train and evaluate LatentJam's 256-d optional-text SMART scorer.

This is a deliberately self-contained experiment driver.  It reads the app's
point-in-time SQLite export (audio/text vectors plus listening events), distils
the deployed 960-d state/scorer into a 256-d contract, and compares two text
branches:

* ``naive`` sees only correct metadata and demonstrates the leakage/over-trust
  failure mode;
* ``robust`` sees missing, swapped, title-poisoned, and genre-poisoned metadata
  and is trained to fall back to the frozen audio scorer for those cases.

No user audio or inference leaves the device at app runtime.  The RunPod is
only an offline training/evaluation machine.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import sqlite3
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Iterable

import numpy as np
import torch
import torch.nn.functional as F
from torch import nn


AUDIO_DIM = 960
TARGET_DIM = 256
TEXT_DIM = 384
CONTEXT_K = 4
POOL_SIZE = 100
TIME_DIM = 5
SESSION_DIM = 5


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--projector", type=Path, required=True)
    parser.add_argument("--teacher-state", type=Path, required=True)
    parser.add_argument("--teacher-scorer", type=Path, required=True)
    parser.add_argument("--text-model", type=Path, required=True)
    parser.add_argument("--text-vocab", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--seed", type=int, default=19)
    parser.add_argument("--chains", type=int, default=2_500)
    parser.add_argument("--chain-length", type=int, default=8)
    parser.add_argument("--state-epochs", type=int, default=35)
    parser.add_argument("--scorer-epochs", type=int, default=35)
    parser.add_argument("--text-epochs", type=int, default=60)
    parser.add_argument("--folds", type=int, default=4)
    parser.add_argument("--pool-mode", choices=("fused", "round_robin"), default="fused")
    return parser.parse_args()


def normalize_rows(matrix: np.ndarray) -> np.ndarray:
    return matrix / np.clip(np.linalg.norm(matrix, axis=-1, keepdims=True), 1e-12, None)


def blob_vector(blob: bytes | None, dim: int) -> np.ndarray | None:
    if blob is None:
        return None
    value = np.frombuffer(blob, dtype="<f4")
    if value.size != dim:
        return None
    return value.astype(np.float32, copy=True)


@dataclass(frozen=True)
class TrackMeta:
    track_id: str
    artist: str
    title: str
    genre: str
    year: int | None

    def text(self, *, title: str | None = None, genre: str | None = None) -> str:
        fields: list[str] = []
        actual_genre = self.genre if genre is None else genre
        actual_title = self.title if title is None else title
        if actual_genre.strip():
            fields.append(actual_genre.strip())
        if self.artist.strip():
            fields.append(self.artist.strip())
        if actual_title.strip():
            fields.append(actual_title.strip())
        if self.year is not None and self.year > 0:
            fields.append(str(self.year))
        return "; ".join(fields)


@dataclass
class Library:
    ids: list[str]
    audio960: np.ndarray
    text: np.ndarray
    has_text: np.ndarray
    energy: np.ndarray
    meta: list[TrackMeta]

    @property
    def index(self) -> dict[str, int]:
        return {track_id: i for i, track_id in enumerate(self.ids)}


def read_metadata(path: Path) -> dict[str, TrackMeta]:
    rows: dict[str, TrackMeta] = {}
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            raw_year = (row.get("year") or "").strip()
            try:
                year = int(float(raw_year)) if raw_year else None
            except ValueError:
                year = None
            track_id = str(row["track_id"])
            rows[track_id] = TrackMeta(
                track_id=track_id,
                artist=str(row.get("artist") or ""),
                title=str(row.get("title") or ""),
                genre=str(row.get("genre") or ""),
                year=year,
            )
    return rows


def load_library(db: Path, metadata: Path) -> Library:
    labels = read_metadata(metadata)
    ids: list[str] = []
    audio: list[np.ndarray] = []
    text: list[np.ndarray] = []
    mask: list[bool] = []
    energy: list[float] = []
    meta: list[TrackMeta] = []
    connection = sqlite3.connect(db)
    query = (
        "SELECT songUid, embedding, textEmbedding, energy "
        "FROM TrackEmbeddingEntity ORDER BY songUid"
    )
    for track_id, audio_blob, text_blob, track_energy in connection.execute(query):
        audio_vector = blob_vector(audio_blob, AUDIO_DIM)
        if audio_vector is None or np.linalg.norm(audio_vector) < 1e-3:
            continue
        text_vector = blob_vector(text_blob, TEXT_DIM)
        ids.append(str(track_id))
        audio.append(audio_vector)
        if text_vector is None or np.linalg.norm(text_vector) < 1e-6:
            text.append(np.zeros(TEXT_DIM, dtype=np.float32))
            mask.append(False)
        else:
            text.append(text_vector)
            mask.append(True)
        energy.append(float(track_energy) if track_energy is not None else float("nan"))
        meta.append(labels.get(str(track_id), TrackMeta(str(track_id), "", "", "", None)))
    connection.close()
    return Library(
        ids=ids,
        audio960=normalize_rows(np.stack(audio).astype(np.float32)),
        text=normalize_rows(np.stack(text).astype(np.float32)),
        has_text=np.asarray(mask, dtype=bool),
        energy=np.asarray(energy, dtype=np.float32),
        meta=meta,
    )


@dataclass(frozen=True)
class Projector:
    mean: np.ndarray
    components: np.ndarray

    @classmethod
    def load(cls, path: Path) -> "Projector":
        data = np.load(path)
        return cls(
            mean=np.asarray(data["mean"], dtype=np.float32),
            components=np.asarray(data["components"][:TARGET_DIM], dtype=np.float32),
        )

    def transform(self, matrix: np.ndarray) -> np.ndarray:
        projected = (matrix - self.mean) @ self.components.T
        return normalize_rows(projected.astype(np.float32))


def time_features(timestamp_ms: float) -> np.ndarray:
    seconds = timestamp_ms / 1000.0
    hour = (seconds % (24 * 3600)) / 3600.0
    dow = (int(seconds // (24 * 3600)) + 3) % 7
    return np.asarray(
        [
            math.sin(2 * math.pi * hour / 24),
            math.cos(2 * math.pi * hour / 24),
            math.sin(2 * math.pi * dow / 7),
            math.cos(2 * math.pi * dow / 7),
            float(dow >= 5),
        ],
        dtype=np.float32,
    )


HARNESS_TIME = time_features(1_700_064_000_000.0)
DRY_SESSION = np.asarray(
    [math.log(2.0), 0.0, math.log(1.5), 1.0, 1.0], dtype=np.float32
)


@dataclass
class StateRows:
    history: np.ndarray
    medium: np.ndarray
    large: np.ndarray
    time: np.ndarray
    session: np.ndarray
    anchor: np.ndarray
    source: np.ndarray


def random_time(rng: np.random.Generator) -> np.ndarray:
    hour = int(rng.integers(0, 24))
    dow = int(rng.integers(0, 7))
    return np.asarray(
        [
            math.sin(2 * math.pi * hour / 24),
            math.cos(2 * math.pi * hour / 24),
            math.sin(2 * math.pi * dow / 7),
            math.cos(2 * math.pi * dow / 7),
            float(dow >= 5),
        ], dtype=np.float32,
    )


def append_state_row(
    buckets: dict[str, list[np.ndarray | int]],
    audio: np.ndarray,
    rows: Iterable[int],
    time: np.ndarray,
    session: np.ndarray,
    anchor: int,
    source: int,
) -> None:
    recent = list(rows)[-CONTEXT_K:]
    if not recent:
        recent = [anchor]
    while len(recent) < CONTEXT_K:
        recent.insert(0, recent[0])
    history = np.zeros((CONTEXT_K, AUDIO_DIM + 1), dtype=np.float32)
    history[:, :AUDIO_DIM] = audio[recent]
    history[:, AUDIO_DIM] = 1.0
    buckets["history"].append(history)
    buckets["medium"].append(audio[recent[-1]])
    buckets["large"].append(audio[recent[-1]])
    buckets["time"].append(time)
    buckets["session"].append(session)
    buckets["anchor"].append(anchor)
    buckets["source"].append(source)


def build_distill_rows(
    library: Library, rng: np.random.Generator, chains: int, chain_length: int
) -> StateRows:
    buckets: dict[str, list] = {name: [] for name in (
        "history", "medium", "large", "time", "session", "anchor", "source"
    )}
    audio = library.audio960
    n = len(audio)
    for variant in range(3):
        stamp = HARNESS_TIME if variant == 0 else random_time(rng)
        for row in range(n):
            append_state_row(buckets, audio, [row] * CONTEXT_K, stamp, DRY_SESSION, row, 0)

    centered = normalize_rows(audio - audio.mean(axis=0, keepdims=True))
    similarity = centered @ centered.T
    np.fill_diagonal(similarity, -9.0)
    neighbours = np.argpartition(-similarity, kth=16, axis=1)[:, :16]
    for _ in range(chains):
        seed = int(rng.integers(0, n))
        history = [seed] * CONTEXT_K
        used = {seed}
        current = seed
        for _hop in range(chain_length):
            append_state_row(
                buckets, audio, history, HARNESS_TIME, DRY_SESSION, current, 1
            )
            options = [int(x) for x in neighbours[current] if int(x) not in used]
            if not options:
                break
            current = int(rng.choice(options[:8]))
            used.add(current)
            history = history[1:] + [current]

    return StateRows(
        history=np.stack(buckets["history"]).astype(np.float32),
        medium=np.stack(buckets["medium"]).astype(np.float32),
        large=np.stack(buckets["large"]).astype(np.float32),
        time=np.stack(buckets["time"]).astype(np.float32),
        session=np.stack(buckets["session"]).astype(np.float32),
        anchor=np.asarray(buckets["anchor"], dtype=np.int32),
        source=np.asarray(buckets["source"], dtype=np.int8),
    )


def ort_session(path: Path):
    import onnxruntime as ort

    return ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])


def teacher_states(session, rows: StateRows, batch: int = 512) -> np.ndarray:
    output: list[np.ndarray] = []
    for start in range(0, len(rows.history), batch):
        end = min(start + batch, len(rows.history))
        output.append(session.run(None, {
            "history_small": rows.history[start:end],
            "history_medium": rows.medium[start:end],
            "history_large": rows.large[start:end],
            "time_features": rows.time[start:end],
            "session_features": rows.session[start:end],
        })[0])
    return normalize_rows(np.concatenate(output).astype(np.float32))


def project_history(rows: StateRows, projector: Projector) -> StateRows:
    count = len(rows.history)
    flattened = rows.history[:, :, :AUDIO_DIM].reshape(-1, AUDIO_DIM)
    projected = projector.transform(flattened).reshape(count, CONTEXT_K, TARGET_DIM)
    history = np.zeros((count, CONTEXT_K, TARGET_DIM + 1), dtype=np.float32)
    history[:, :, :TARGET_DIM] = projected
    history[:, :, TARGET_DIM] = rows.history[:, :, AUDIO_DIM]
    return StateRows(
        history=history,
        medium=projector.transform(rows.medium),
        large=projector.transform(rows.large),
        time=rows.time,
        session=rows.session,
        anchor=rows.anchor,
        source=rows.source,
    )


@dataclass
class StateConfig:
    dim: int = TARGET_DIM
    hidden: int = 128
    fuse_hidden: int = 384
    aux_hidden: int = 24


class StateStudent(nn.Module):
    def __init__(self, cfg: StateConfig = StateConfig()) -> None:
        super().__init__()
        self.cfg = cfg
        self.token = nn.Linear(cfg.dim + 1, cfg.hidden)
        self.gru = nn.GRU(cfg.hidden, cfg.hidden, batch_first=True)
        self.medium = nn.Sequential(nn.Linear(cfg.dim, cfg.hidden), nn.LayerNorm(cfg.hidden), nn.GELU())
        self.large = nn.Sequential(nn.Linear(cfg.dim, cfg.hidden), nn.LayerNorm(cfg.hidden), nn.GELU())
        self.time = nn.Sequential(nn.Linear(TIME_DIM, cfg.aux_hidden), nn.GELU())
        self.session = nn.Sequential(nn.Linear(SESSION_DIM, cfg.aux_hidden), nn.GELU())
        self.fuse = nn.Sequential(
            nn.Linear(3 * cfg.hidden + 2 * cfg.aux_hidden, cfg.fuse_hidden),
            nn.LayerNorm(cfg.fuse_hidden), nn.GELU(), nn.Linear(cfg.fuse_hidden, cfg.dim),
        )
        self.residual_scale = nn.Parameter(torch.tensor(0.5))

    def forward(self, history, medium, large, time_value, session_value):
        token = self.token(history)
        _, hidden = self.gru(token)
        fused = torch.cat([
            hidden[-1], self.medium(medium), self.large(large),
            self.time(time_value), self.session(session_value),
        ], dim=-1)
        offset = self.fuse(fused)
        emb = history[..., :self.cfg.dim]
        played = history[..., self.cfg.dim:self.cfg.dim + 1].clamp(0, 1)
        weighted = (emb * played).sum(1) / played.sum(1).clamp_min(1e-6)
        plain = emb.mean(1)
        has_weight = (played.sum(1) > 1e-6).float()
        anchor = F.normalize(has_weight * weighted + (1 - has_weight) * plain, dim=-1)
        return F.normalize(anchor + self.residual_scale * offset, dim=-1)


@dataclass
class ScorerConfig:
    dim: int = TARGET_DIM
    projection: int = 192
    hidden: int = 256


class AudioScorer(nn.Module):
    def __init__(self, cfg: ScorerConfig = ScorerConfig()) -> None:
        super().__init__()
        self.cfg = cfg
        self.project = nn.Linear(cfg.dim, cfg.projection)
        feature_dim = 4 * cfg.projection + 1
        self.mlp = nn.Sequential(
            nn.LayerNorm(feature_dim), nn.Linear(feature_dim, cfg.hidden), nn.GELU(),
            nn.Linear(cfg.hidden, cfg.hidden), nn.GELU(), nn.Linear(cfg.hidden, 1),
        )

    def forward(self, state, candidates):
        projected_state = self.project(state).unsqueeze(1)
        projected_candidates = self.project(candidates)
        expanded = projected_state.expand(-1, candidates.shape[1], -1)
        cosine = (state.unsqueeze(1) * candidates).sum(-1, keepdim=True)
        features = torch.cat([
            expanded, projected_candidates, expanded * projected_candidates,
            (expanded - projected_candidates).abs(), cosine,
        ], dim=-1)
        return self.mlp(features).squeeze(-1)


class ConditionedScorer(nn.Module):
    """Frozen audio scorer plus a learned, bounded optional-text residual."""

    def __init__(self, audio: AudioScorer, text_projection: int = 48, residual_cap: float = 0.75):
        super().__init__()
        self.audio = audio
        self.text_project = nn.Linear(TEXT_DIM, text_projection)
        text_features = 4 * text_projection + 3
        self.residual = nn.Sequential(
            nn.LayerNorm(text_features), nn.Linear(text_features, 96), nn.GELU(),
            nn.Linear(96, 48), nn.GELU(), nn.Linear(48, 1),
        )
        self.gate = nn.Sequential(nn.Linear(4, 16), nn.GELU(), nn.Linear(16, 1))
        self.residual_cap = residual_cap
        for parameter in self.audio.parameters():
            parameter.requires_grad_(False)

    def forward(self, state, candidates, text_state, text_candidates, text_mask):
        audio_logits = self.audio(state, candidates)
        state_text = self.text_project(text_state).unsqueeze(1)
        candidate_text = self.text_project(text_candidates)
        expanded = state_text.expand(-1, candidates.shape[1], -1)
        audio_cosine = (state.unsqueeze(1) * candidates).sum(-1, keepdim=True)
        text_cosine = (text_state.unsqueeze(1) * text_candidates).sum(-1, keepdim=True)
        disagreement = (audio_cosine - text_cosine).abs()
        features = torch.cat([
            expanded, candidate_text, expanded * candidate_text,
            (expanded - candidate_text).abs(), audio_cosine, text_cosine, disagreement,
        ], dim=-1)
        raw_residual = self.residual(features).squeeze(-1)
        gate_inputs = torch.cat([
            audio_cosine, text_cosine, disagreement, text_mask.unsqueeze(-1)
        ], dim=-1)
        gate = torch.sigmoid(self.gate(gate_inputs).squeeze(-1)) * text_mask
        return audio_logits + gate * self.residual_cap * torch.tanh(raw_residual)


def tensor_batches(length: int, batch: int, rng: np.random.Generator) -> Iterable[np.ndarray]:
    order = rng.permutation(length)
    for start in range(0, length, batch):
        yield order[start:start + batch]


def state_forward_numpy(model: StateStudent, rows: StateRows, device: torch.device, batch: int = 2048):
    model.eval()
    output: list[np.ndarray] = []
    with torch.no_grad():
        for start in range(0, len(rows.history), batch):
            end = min(start + batch, len(rows.history))
            values = [
                rows.history[start:end], rows.medium[start:end], rows.large[start:end],
                rows.time[start:end], rows.session[start:end],
            ]
            tensors = [torch.from_numpy(x).to(device) for x in values]
            output.append(model(*tensors).cpu().numpy())
    return np.concatenate(output)


def train_state(
    rows: StateRows,
    targets: np.ndarray,
    device: torch.device,
    epochs: int,
    seed: int,
) -> tuple[StateStudent, dict]:
    rng = np.random.default_rng(seed)
    train_indices: list[int] = []
    val_indices: list[int] = []
    for source in np.unique(rows.source):
        indices = np.where(rows.source == source)[0]
        rng.shuffle(indices)
        split = max(1, int(0.1 * len(indices)))
        val_indices.extend(indices[:split])
        train_indices.extend(indices[split:])
    train_indices = np.asarray(train_indices)
    val_indices = np.asarray(val_indices)
    model = StateStudent().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    best_cosine = -1.0
    best_state = None
    history: list[dict] = []
    for epoch in range(epochs):
        model.train()
        losses: list[float] = []
        for batch_indices in tensor_batches(len(train_indices), 1024, rng):
            idx = train_indices[batch_indices]
            values = [rows.history[idx], rows.medium[idx], rows.large[idx], rows.time[idx], rows.session[idx]]
            tensors = [torch.from_numpy(x).to(device) for x in values]
            target = torch.from_numpy(targets[idx]).to(device)
            prediction = model(*tensors)
            cosine = (prediction * target).sum(-1)
            loss = 0.35 * F.mse_loss(prediction, target) + 0.65 * (1 - cosine.mean())
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimizer.step()
            losses.append(float(loss.detach().cpu()))
        scheduler.step()
        prediction = state_forward_numpy(model, subset_rows(rows, val_indices), device)
        cosine = np.sum(prediction * targets[val_indices], axis=1)
        report = {"epoch": epoch + 1, "loss": float(np.mean(losses)), "val_cosine": float(cosine.mean()), "val_min": float(cosine.min())}
        history.append(report)
        print("state", report, flush=True)
        if report["val_cosine"] > best_cosine:
            best_cosine = report["val_cosine"]
            best_state = {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}
    assert best_state is not None
    model.load_state_dict(best_state)
    return model, {"best_val_cosine": best_cosine, "history": history}


def subset_rows(rows: StateRows, indices: np.ndarray) -> StateRows:
    return StateRows(
        history=rows.history[indices], medium=rows.medium[indices], large=rows.large[indices],
        time=rows.time[indices], session=rows.session[indices], anchor=rows.anchor[indices],
        source=rows.source[indices],
    )


def centered_and_hub(audio: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    centered = normalize_rows(audio - audio.mean(0, keepdims=True))
    similarity = centered @ centered.T
    np.fill_diagonal(similarity, -9.0)
    top = np.partition(similarity, -10, axis=1)[:, -10:].mean(1)
    return centered, top - top.mean()


def candidate_pool(
    anchor: int,
    state: np.ndarray,
    audio: np.ndarray,
    centered: np.ndarray,
    hub: np.ndarray,
    text_state: np.ndarray | None = None,
    text: np.ndarray | None = None,
    mode: str = "fused",
) -> np.ndarray:
    state_scores = audio @ state
    has_text_query = text_state is not None and text is not None and np.linalg.norm(text_state) > 1e-6
    if mode == "fused" and has_text_query:
        text_scores = text @ text_state
        state_scores = 0.4 * state_scores + 0.6 * text_scores
    anchor_scores = centered @ centered[anchor] - hub
    state_order = np.argsort(-state_scores)
    anchor_order = np.argsort(-anchor_scores)
    orders = [anchor_order, state_order]
    if mode == "round_robin" and has_text_query:
        orders.append(np.argsort(-(text @ text_state)))
    result: list[int] = []
    seen = {anchor}
    for rank in range(len(audio)):
        for order in orders:
            row = int(order[rank])
            if row not in seen:
                seen.add(row)
                result.append(row)
                if len(result) == POOL_SIZE:
                    return np.asarray(result, dtype=np.int32)
    return np.asarray(result, dtype=np.int32)


def make_scorer_rows(rows: StateRows, states: np.ndarray, audio: np.ndarray) -> np.ndarray:
    """Build distillation pools in the same embedding space the phone will use."""
    centered, hub = centered_and_hub(audio)
    pools = np.empty((len(states), POOL_SIZE), dtype=np.int32)
    for i, state in enumerate(states):
        anchor = int(rows.anchor[i])
        pools[i] = candidate_pool(anchor, state, audio, centered, hub)
    return pools


def teacher_logits(session, states: np.ndarray, candidates: np.ndarray, audio: np.ndarray, batch: int = 256):
    output: list[np.ndarray] = []
    for start in range(0, len(states), batch):
        end = min(start + batch, len(states))
        output.append(session.run(None, {
            "state": states[start:end].astype(np.float32),
            "candidates": audio[candidates[start:end]].astype(np.float32),
        })[0])
    return np.concatenate(output).astype(np.float32)


def scorer_metrics(student: np.ndarray, teacher: np.ndarray) -> dict:
    teacher_order = np.argsort(-teacher, axis=1)
    top1 = student.argmax(1)
    return {
        "top1_agreement": float(np.mean(top1 == teacher_order[:, 0])),
        "top1_in_teacher_top3": float(np.mean([top1[i] in teacher_order[i, :3] for i in range(len(top1))])),
        "squashed_mad": float(np.abs(1.5 * np.tanh(student / 2) - 1.5 * np.tanh(teacher / 2)).mean()),
    }


def audio_scorer_numpy(model: AudioScorer, state: np.ndarray, candidates: np.ndarray, device: torch.device, batch: int = 512):
    output: list[np.ndarray] = []
    model.eval()
    with torch.no_grad():
        for start in range(0, len(state), batch):
            end = min(start + batch, len(state))
            output.append(model(
                torch.from_numpy(state[start:end]).to(device),
                torch.from_numpy(candidates[start:end]).to(device),
            ).cpu().numpy())
    return np.concatenate(output)


def train_audio_scorer(
    states256: np.ndarray,
    candidates256: np.ndarray,
    targets: np.ndarray,
    device: torch.device,
    epochs: int,
    seed: int,
) -> tuple[AudioScorer, dict]:
    rng = np.random.default_rng(seed)
    indices = rng.permutation(len(states256))
    split = max(1, int(0.1 * len(indices)))
    val, train = indices[:split], indices[split:]
    model = AudioScorer().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    best = -1.0
    best_state = None
    history: list[dict] = []
    for epoch in range(epochs):
        model.train()
        losses: list[float] = []
        for batch_indices in tensor_batches(len(train), 512, rng):
            idx = train[batch_indices]
            state_tensor = torch.from_numpy(states256[idx]).to(device)
            candidate_tensor = torch.from_numpy(candidates256[idx]).to(device)
            teacher_tensor = torch.from_numpy(targets[idx]).to(device)
            logits = model(state_tensor, candidate_tensor)
            mse = F.mse_loss(logits, teacher_tensor)
            probability = F.softmax(teacher_tensor / 2.0, dim=1)
            kl = F.kl_div(F.log_softmax(logits / 2.0, dim=1), probability, reduction="batchmean") * 4.0
            loss = mse + kl
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
            losses.append(float(loss.detach().cpu()))
        scheduler.step()
        prediction = audio_scorer_numpy(model, states256[val], candidates256[val], device)
        metrics = scorer_metrics(prediction, targets[val])
        metrics.update({"epoch": epoch + 1, "loss": float(np.mean(losses))})
        history.append(metrics)
        print("audio_scorer", metrics, flush=True)
        if metrics["top1_agreement"] > best:
            best = metrics["top1_agreement"]
            best_state = {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}
    assert best_state is not None
    model.load_state_dict(best_state)
    return model, {"best_top1_agreement": best, "history": history}


class WordPiece:
    def __init__(self, vocab_path: Path, max_length: int = 64):
        self.tokens = [line.rstrip("\n") for line in vocab_path.open(encoding="utf-8")]
        self.vocab = {token: index for index, token in enumerate(self.tokens)}
        self.max_length = max_length
        self.unknown = self.vocab["[UNK]"]
        self.cls = self.vocab["[CLS]"]
        self.sep = self.vocab["[SEP]"]

    @staticmethod
    def basic(text: str) -> list[str]:
        import re
        return re.findall(r"[\w]+|[^\w\s]", text.lower(), flags=re.UNICODE)

    def pieces(self, token: str) -> list[int]:
        if token in self.vocab:
            return [self.vocab[token]]
        if len(token) > 100:
            return [self.unknown]
        result: list[int] = []
        start = 0
        while start < len(token):
            end = len(token)
            found = None
            while start < end:
                piece = token[start:end]
                if start > 0:
                    piece = "##" + piece
                if piece in self.vocab:
                    found = self.vocab[piece]
                    break
                end -= 1
            if found is None:
                return [self.unknown]
            result.append(found)
            start = end
        return result

    def encode(self, text: str) -> list[int]:
        values = [self.cls]
        for token in self.basic(text):
            values.extend(self.pieces(token))
            if len(values) >= self.max_length - 1:
                break
        values.append(self.sep)
        return values


class MiniLm:
    def __init__(self, model: Path, vocab: Path):
        self.session = ort_session(model)
        self.tokenizer = WordPiece(vocab)

    def encode(self, texts: list[str], batch: int = 64) -> np.ndarray:
        output: list[np.ndarray] = []
        for start in range(0, len(texts), batch):
            part = texts[start:start + batch]
            encoded = [self.tokenizer.encode(text) for text in part]
            length = max(len(row) for row in encoded)
            ids = np.zeros((len(part), length), dtype=np.int64)
            mask = np.zeros_like(ids)
            for i, row in enumerate(encoded):
                ids[i, :len(row)] = row
                mask[i, :len(row)] = 1
            tokens = self.session.run(None, {
                "input_ids": ids,
                "attention_mask": mask,
                "token_type_ids": np.zeros_like(ids),
            })[0]
            pooled = (tokens * mask[..., None]).sum(1) / np.clip(mask.sum(1, keepdims=True), 1, None)
            output.append(normalize_rows(pooled.astype(np.float32)))
        return np.concatenate(output)


def conflicting_genre(meta: TrackMeta, index: int) -> str:
    values = ["Jazz", "Heavy Metal", "Classical", "Country", "Techno", "Hip-Hop", "Ambient"]
    current = meta.genre.lower()
    for offset in range(len(values)):
        candidate = values[(index + offset) % len(values)]
        if candidate.lower() not in current:
            return candidate
    return "Jazz"


def text_variants(library: Library, model: MiniLm) -> dict[str, np.ndarray]:
    title_poison: list[str] = []
    genre_poison: list[str] = []
    title_only: list[str] = []
    trusted: list[str] = []
    trusted_genre_poison: list[str] = []
    for i, meta in enumerate(library.meta):
        wrong = conflicting_genre(meta, i)
        injected_title = f"{meta.title} ({wrong} mix)" if meta.title else f"{wrong} mix"
        title_poison.append(meta.text(title=injected_title))
        genre_poison.append(meta.text(genre=wrong))
        title_only.append(meta.title)
        # Titles routinely contain mix labels and uploader tags. Keep them out of the trusted
        # channel so a stray genre word in a title is exactly incapable of moving its embedding.
        trusted.append(meta.text(title=""))
        trusted_genre_poison.append(meta.text(title="", genre=wrong))
    return {
        "correct": library.text,
        "title_poison": model.encode(title_poison),
        "genre_poison": model.encode(genre_poison),
        "title_only": model.encode(title_only),
        "trusted": model.encode(trusted),
        "trusted_genre_poison": model.encode(trusted_genre_poison),
    }


@dataclass
class EventExamples:
    rows960: StateRows
    text_state: np.ndarray
    text_history_rows: np.ndarray
    text_history_weights: np.ndarray
    target: np.ndarray
    sessions: np.ndarray
    timestamp: np.ndarray


def load_events(db: Path) -> list[dict]:
    connection = sqlite3.connect(db)
    connection.row_factory = sqlite3.Row
    rows = [dict(row) for row in connection.execute(
        "SELECT songUid, startedAtMs, playedMs, trackDurationMs, completed, skipped, "
        "sessionId, wasSmartPick FROM ListeningEventEntity ORDER BY startedAtMs"
    )]
    connection.close()
    return rows


def build_event_examples(library: Library, events: list[dict]) -> EventExamples:
    index = library.index
    medium_acc = np.zeros(AUDIO_DIM, dtype=np.float64)
    large_acc = np.zeros(AUDIO_DIM, dtype=np.float64)
    medium_weight = 0.0
    large_weight = 0.0
    last_global_timestamp: float | None = None
    session_history: dict[str, list[tuple[int, float]]] = {}
    session_start: dict[str, float] = {}
    session_percent: dict[str, list[float]] = {}
    global_percent: list[float] = []
    previous_skipped = 0
    buckets: dict[str, list] = {name: [] for name in (
        "history", "medium", "large", "time", "session", "anchor", "source",
        "text_state", "text_history_rows", "text_history_weights",
        "target", "sessions", "timestamp",
    )}
    for event in events:
        track_id = str(event["songUid"])
        if track_id not in index:
            continue
        target = index[track_id]
        timestamp = float(event["startedAtMs"])
        session_id = str(event["sessionId"])
        duration = max(float(event["trackDurationMs"]), 0.0)
        played = max(float(event["playedMs"]), 0.0)
        played_pct = float(np.clip(played / duration, 0, 1)) if duration > 0 else 0.0
        history_values = session_history.setdefault(session_id, [])
        session_start.setdefault(session_id, timestamp)
        recent = history_values[-CONTEXT_K:]
        if recent:
            recent_rows = [row for row, _pct in recent]
            recent_pct = np.asarray([pct for _row, pct in recent], dtype=np.float32)
            padded_rows = recent_rows.copy()
            padded_pct = recent_pct.tolist()
            while len(padded_rows) < CONTEXT_K:
                padded_rows.insert(0, padded_rows[0])
                padded_pct.insert(0, padded_pct[0])
        else:
            padded_rows = [target] * CONTEXT_K
            padded_pct = [0.5] * CONTEXT_K
        history = np.zeros((CONTEXT_K, AUDIO_DIM + 1), dtype=np.float32)
        history[:, :AUDIO_DIM] = library.audio960[padded_rows]
        history[:, AUDIO_DIM] = np.asarray(padded_pct, dtype=np.float32)
        medium = medium_acc / medium_weight if medium_weight > 0 else library.audio960[target]
        large = large_acc / large_weight if large_weight > 0 else library.audio960[target]
        medium = normalize_rows(np.asarray(medium, dtype=np.float32)[None])[0]
        large = normalize_rows(np.asarray(large, dtype=np.float32)[None])[0]
        session_window = session_percent.setdefault(session_id, [])
        reference_window = session_window if session_window else global_percent
        mean_pct = float(np.mean(reference_window)) if reference_window else 0.5
        completion_rate = float(np.mean(np.asarray(reference_window) >= 0.8)) if reference_window else 0.5
        session_value = np.asarray([
            math.log1p(len(history_values)), float(previous_skipped),
            math.log1p(max(0.0, (timestamp - session_start[session_id]) / 60_000)),
            completion_rate, mean_pct,
        ], dtype=np.float32)
        if recent:
            text_rows = np.asarray([row for row, _pct in recent], dtype=np.int32)
            weights = np.asarray([max(pct, 0.05) for _row, pct in recent], dtype=np.float32)
            text_state = (library.text[text_rows] * weights[:, None]).sum(0)
            text_state = normalize_rows(text_state[None])[0]
        else:
            text_state = library.text[target]
        if int(event["completed"]) == 1 and len(history_values) >= 2:
            text_history_rows = np.full(CONTEXT_K, -1, dtype=np.int32)
            text_history_weights = np.zeros(CONTEXT_K, dtype=np.float32)
            text_history_rows[-len(recent):] = np.asarray(
                [row for row, _pct in recent], dtype=np.int32
            )
            text_history_weights[-len(recent):] = np.asarray(
                [max(pct, 0.05) for _row, pct in recent], dtype=np.float32
            )
            buckets["history"].append(history)
            buckets["medium"].append(medium)
            buckets["large"].append(large)
            buckets["time"].append(time_features(timestamp))
            buckets["session"].append(session_value)
            buckets["anchor"].append(padded_rows[-1])
            buckets["source"].append(2)
            buckets["text_state"].append(text_state)
            buckets["text_history_rows"].append(text_history_rows)
            buckets["text_history_weights"].append(text_history_weights)
            buckets["target"].append(target)
            buckets["sessions"].append(session_id)
            buckets["timestamp"].append(timestamp)
        if last_global_timestamp is not None:
            days = max(0.0, (timestamp - last_global_timestamp) / 86_400_000)
            medium_decay = math.exp(-days * math.log(2) / 30.0)
            large_decay = math.exp(-days * math.log(2) / 365.0)
            medium_acc *= medium_decay
            medium_weight *= medium_decay
            large_acc *= large_decay
            large_weight *= large_decay
        last_global_timestamp = timestamp
        reward = float(np.clip((played_pct - 0.2) / 0.6, 0, 1))
        if reward > 0:
            medium_acc += reward * library.audio960[target]
            medium_weight += reward
            large_acc += reward * library.audio960[target]
            large_weight += reward
        history_values.append((target, played_pct))
        session_window.append(played_pct)
        del session_window[:-10]
        global_percent.append(played_pct)
        del global_percent[:-50]
        previous_skipped = int(event["skipped"])
    rows = StateRows(
        history=np.stack(buckets["history"]), medium=np.stack(buckets["medium"]),
        large=np.stack(buckets["large"]), time=np.stack(buckets["time"]),
        session=np.stack(buckets["session"]), anchor=np.asarray(buckets["anchor"], np.int32),
        source=np.asarray(buckets["source"], np.int8),
    )
    return EventExamples(
        rows960=rows, text_state=np.stack(buckets["text_state"]).astype(np.float32),
        text_history_rows=np.stack(buckets["text_history_rows"]),
        text_history_weights=np.stack(buckets["text_history_weights"]),
        target=np.asarray(buckets["target"], np.int32), sessions=np.asarray(buckets["sessions"]),
        timestamp=np.asarray(buckets["timestamp"], np.float64),
    )


def event_text_states(examples: EventExamples, track_text: np.ndarray) -> np.ndarray:
    """Rebuild text history states for a metadata counterfactual."""
    safe_rows = np.maximum(examples.text_history_rows, 0)
    weighted = track_text[safe_rows] * examples.text_history_weights[..., None]
    return normalize_rows(weighted.sum(axis=1).astype(np.float32))


@dataclass
class RankExamples:
    state: np.ndarray
    candidates: np.ndarray
    candidate_rows: np.ndarray
    text_state: np.ndarray
    text_correct: np.ndarray
    target_position: np.ndarray
    sessions: np.ndarray
    pool_hit: np.ndarray


def build_rank_examples(
    examples: EventExamples,
    rows256: StateRows,
    state_model: StateStudent,
    projector: Projector,
    library: Library,
    device: torch.device,
    pool_mode: str = "fused",
    retrieval_text: np.ndarray | None = None,
) -> RankExamples:
    state256 = state_forward_numpy(state_model, rows256, device)
    audio256 = projector.transform(library.audio960)
    # Pool construction is part of the deployed contract. Evaluate in 256-d instead of selecting
    # candidates with the old teacher and projecting only after selection.
    centered, hub = centered_and_hub(audio256)
    pools = np.empty((len(state256), POOL_SIZE), dtype=np.int32)
    target_positions = np.empty(len(state256), dtype=np.int64)
    hits = np.empty(len(state256), dtype=bool)
    for i in range(len(state256)):
        pool = candidate_pool(
            int(examples.rows960.anchor[i]), state256[i], audio256,
            centered, hub,
            (
                retrieval_text[int(examples.rows960.anchor[i])]
                if pool_mode == "round_robin" and retrieval_text is not None
                else examples.text_state[i]
            ),
            retrieval_text if retrieval_text is not None else library.text,
            mode=pool_mode,
        )
        where = np.where(pool == examples.target[i])[0]
        hits[i] = len(where) > 0
        if len(where) == 0:
            pool[-1] = examples.target[i]
            target_positions[i] = POOL_SIZE - 1
        else:
            target_positions[i] = int(where[0])
        pools[i] = pool
    return RankExamples(
        state=state256,
        candidates=audio256[pools],
        candidate_rows=pools,
        text_state=examples.text_state,
        text_correct=library.text[pools],
        target_position=target_positions,
        sessions=examples.sessions,
        pool_hit=hits,
    )


def ranks_from_logits(logits: np.ndarray, target_position: np.ndarray) -> np.ndarray:
    order = np.argsort(-logits, axis=1)
    return np.asarray([int(np.where(order[i] == target_position[i])[0][0]) + 1 for i in range(len(order))])


def rank_metrics(logits: np.ndarray, target_position: np.ndarray, pool_hit: np.ndarray) -> dict:
    ranks = ranks_from_logits(logits, target_position)
    result = {
        "n": int(len(ranks)), "pool_recall": float(pool_hit.mean()),
        "mrr_conditional": float(np.mean(1.0 / ranks)),
        "mrr_end_to_end": float(np.mean((1.0 / ranks) * pool_hit)),
    }
    for k in (1, 5, 10, 20):
        conditional = float(np.mean(ranks <= k))
        result[f"recall@{k}_conditional"] = conditional
        result[f"recall@{k}_end_to_end"] = float(np.mean((ranks <= k) & pool_hit))
    return result


def conditioned_numpy(
    model: ConditionedScorer,
    examples: RankExamples,
    text_candidates: np.ndarray,
    mask: np.ndarray,
    device: torch.device,
    indices: np.ndarray,
    text_state: np.ndarray | None = None,
    batch: int = 256,
) -> np.ndarray:
    output: list[np.ndarray] = []
    model.eval()
    with torch.no_grad():
        for start in range(0, len(indices), batch):
            idx = indices[start:start + batch]
            output.append(model(
                torch.from_numpy(examples.state[idx]).to(device),
                torch.from_numpy(examples.candidates[idx]).to(device),
                torch.from_numpy(
                    examples.text_state[idx] if text_state is None else text_state[idx]
                ).to(device),
                torch.from_numpy(text_candidates[idx]).to(device),
                torch.from_numpy(mask[idx]).to(device),
            ).cpu().numpy())
    return np.concatenate(output)


def session_folds(sessions: np.ndarray, folds: int, seed: int) -> list[tuple[np.ndarray, np.ndarray]]:
    rng = np.random.default_rng(seed)
    unique = np.unique(sessions)
    rng.shuffle(unique)
    chunks = np.array_split(unique, folds)
    result = []
    for chunk in chunks:
        val_mask = np.isin(sessions, chunk)
        result.append((np.where(~val_mask)[0], np.where(val_mask)[0]))
    return result


def train_conditioned(
    audio: AudioScorer,
    examples: RankExamples,
    variants: dict[str, np.ndarray],
    state_variants: dict[str, np.ndarray],
    train_indices: np.ndarray,
    val_indices: np.ndarray,
    device: torch.device,
    epochs: int,
    seed: int,
    robust: bool,
) -> tuple[ConditionedScorer, dict]:
    rng = np.random.default_rng(seed)
    model = ConditionedScorer(audio).to(device)
    parameters = [parameter for parameter in model.parameters() if parameter.requires_grad]
    optimizer = torch.optim.AdamW(parameters, lr=1e-3, weight_decay=2e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    base_candidates = examples.text_correct
    mask_all = np.ones(base_candidates.shape[:2], dtype=np.float32)
    best = -1.0
    best_state = None
    history: list[dict] = []
    for epoch in range(epochs):
        model.train()
        losses: list[float] = []
        for batch_indices in tensor_batches(len(train_indices), 128, rng):
            idx = train_indices[batch_indices]
            state = torch.from_numpy(examples.state[idx]).to(device)
            candidates = torch.from_numpy(examples.candidates[idx]).to(device)
            text_state = torch.from_numpy(examples.text_state[idx]).to(device)
            target = torch.from_numpy(examples.target_position[idx]).to(device)
            correct_text = torch.from_numpy(base_candidates[idx]).to(device)
            mask = torch.ones((len(idx), POOL_SIZE), device=device)
            logits = model(state, candidates, text_state, correct_text, mask)
            # A missed target is inserted into the last slot only so conditional diagnostics can
            # assign it a rank. It is unreachable in the actual phone pool, so do not optimize CE
            # on that synthetic insertion.
            per_example = F.cross_entropy(logits, target, reduction="none")
            hit_weight = torch.from_numpy(
                examples.pool_hit[idx].astype(np.float32)
            ).to(device)
            loss = (per_example * hit_weight).sum() / hit_weight.sum().clamp_min(1.0)
            if robust:
                with torch.no_grad():
                    audio_logits = model.audio(state, candidates)
                rows = examples.candidate_rows[idx]

                # A genre word appended to an otherwise-correct title should not change the
                # decision.  Preserve the correct conditioned output, rather than discarding all
                # useful artist/genre metadata because one title token is suspicious.
                title_text = torch.from_numpy(variants["title_poison"][rows]).to(device)
                title_state = torch.from_numpy(state_variants["title_poison"][idx]).to(device)
                title_logits = model(state, candidates, title_state, title_text, mask)
                loss = loss + 2.0 * F.mse_loss(title_logits, logits.detach())

                # A poisoned genre field, a candidate-level metadata permutation, or a poisoned
                # history description is not locally distinguishable from a bad tag.  Train the
                # bounded branch to abstain and recover the audio scorer in those cases.
                genre_text = torch.from_numpy(variants["genre_poison"][rows]).to(device)
                genre_state = torch.from_numpy(state_variants["genre_poison"][idx]).to(device)
                genre_logits = model(state, candidates, genre_state, genre_text, mask)
                loss = loss + 1.5 * F.mse_loss(genre_logits, audio_logits)

                shuffled_text = correct_text[:, torch.randperm(POOL_SIZE, device=device)]
                shuffled_logits = model(state, candidates, text_state, shuffled_text, mask)
                loss = loss + 1.5 * F.mse_loss(shuffled_logits, audio_logits)

                shuffled_state = text_state[torch.randperm(len(idx), device=device)]
                bad_state_logits = model(state, candidates, shuffled_state, correct_text, mask)
                loss = loss + 1.0 * F.mse_loss(bad_state_logits, audio_logits)

                # In exact missing-text mode the branch must be bit-identical to audio.
                missing = model(state, candidates, text_state, torch.zeros_like(correct_text), torch.zeros_like(mask))
                loss = loss + 2.0 * F.mse_loss(missing, audio_logits)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(parameters, 5.0)
            optimizer.step()
            losses.append(float(loss.detach().cpu()))
        scheduler.step()
        correct = conditioned_numpy(model, examples, base_candidates, mask_all, device, val_indices)
        metrics = rank_metrics(correct, examples.target_position[val_indices], examples.pool_hit[val_indices])
        metrics.update({"epoch": epoch + 1, "loss": float(np.mean(losses))})
        history.append(metrics)
        if metrics["mrr_end_to_end"] > best:
            best = metrics["mrr_end_to_end"]
            best_state = {
                key: value.detach().cpu().clone() for key, value in model.state_dict().items()
                if not key.startswith("audio.")
            }
    assert best_state is not None
    model.load_state_dict(best_state, strict=False)
    evaluation: dict[str, dict] = {}
    conditions = {
        "correct": (base_candidates, mask_all, state_variants["correct"]),
        "missing": (
            np.zeros_like(base_candidates), np.zeros_like(mask_all),
            np.zeros_like(examples.text_state),
        ),
        "title_poison": (
            variants["title_poison"][examples.candidate_rows], mask_all,
            state_variants["title_poison"],
        ),
        "genre_poison": (
            variants["genre_poison"][examples.candidate_rows], mask_all,
            state_variants["genre_poison"],
        ),
        "title_only": (
            variants["title_only"][examples.candidate_rows], mask_all,
            state_variants["title_only"],
        ),
    }
    correct_logits = conditioned_numpy(
        model, examples, base_candidates, mask_all, device, val_indices,
        text_state=state_variants["correct"],
    )
    for name, (candidate_text, mask, condition_state) in conditions.items():
        logits = conditioned_numpy(
            model, examples, candidate_text, mask, device, val_indices,
            text_state=condition_state,
        )
        evaluation[name] = rank_metrics(
            logits, examples.target_position[val_indices], examples.pool_hit[val_indices]
        )
        evaluation[name]["top1_vs_audio"] = float(np.mean(
            logits.argmax(1) == audio_scorer_numpy(
                model.audio, examples.state[val_indices], examples.candidates[val_indices], device
            ).argmax(1)
        ))
        if name != "correct":
            evaluation[name]["top1_vs_correct"] = float(np.mean(
                logits.argmax(1) == correct_logits.argmax(1)
            ))
    return model, {
        "best_mrr_end_to_end": best,
        "evaluation": evaluation,
        "history": history,
    }


def export_models(state: StateStudent, scorer: ConditionedScorer, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    state_path = output / "predictor_state_256.onnx"
    scorer_path = output / "predictor_scorer_text_n100_256.onnx"
    state_cpu = state.cpu().eval()
    scorer_cpu = scorer.cpu().eval()
    torch.onnx.export(
        state_cpu,
        (
            torch.zeros(1, CONTEXT_K, TARGET_DIM + 1), torch.zeros(1, TARGET_DIM),
            torch.zeros(1, TARGET_DIM), torch.zeros(1, TIME_DIM), torch.zeros(1, SESSION_DIM),
        ),
        str(state_path), input_names=["history_small", "history_medium", "history_large", "time_features", "session_features"],
        output_names=["state"], opset_version=17, do_constant_folding=True,
        dynamic_axes={name: {0: "batch"} for name in ["history_small", "history_medium", "history_large", "time_features", "session_features", "state"]},
        dynamo=False,
    )
    torch.onnx.export(
        scorer_cpu,
        (
            torch.zeros(1, TARGET_DIM), torch.zeros(1, POOL_SIZE, TARGET_DIM),
            torch.zeros(1, TEXT_DIM), torch.zeros(1, POOL_SIZE, TEXT_DIM),
            torch.ones(1, POOL_SIZE),
        ),
        str(scorer_path), input_names=["state", "candidates", "text_state", "text_candidates", "text_mask"],
        output_names=["scores"], opset_version=17, do_constant_folding=True,
        dynamic_axes={name: {0: "batch"} for name in ["state", "candidates", "text_state", "text_candidates", "text_mask", "scores"]},
        dynamo=False,
    )
    state_session = ort_session(state_path)
    scorer_session = ort_session(scorer_path)
    return {
        "state": {
            "path": str(state_path), "bytes": state_path.stat().st_size,
            "inputs": [(item.name, item.shape) for item in state_session.get_inputs()],
            "outputs": [(item.name, item.shape) for item in state_session.get_outputs()],
        },
        "scorer": {
            "path": str(scorer_path), "bytes": scorer_path.stat().st_size,
            "inputs": [(item.name, item.shape) for item in scorer_session.get_inputs()],
            "outputs": [(item.name, item.shape) for item in scorer_session.get_outputs()],
        },
    }


def checkpoint(path: Path, model: nn.Module, config: dict, metrics: dict) -> None:
    torch.save({
        "model_state_dict": model.state_dict(), "config": config, "metrics": metrics,
    }, path)


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = torch.device(args.device if args.device != "cuda" or torch.cuda.is_available() else "cpu")
    started = time.time()
    library = load_library(args.db, args.metadata)
    projector = Projector.load(args.projector)
    print(f"library={len(library.ids)} device={device}", flush=True)

    teacher_state_session = ort_session(args.teacher_state)
    teacher_scorer_session = ort_session(args.teacher_scorer)
    rng = np.random.default_rng(args.seed)
    rows960 = build_distill_rows(library, rng, args.chains, args.chain_length)
    states960 = teacher_states(teacher_state_session, rows960)
    rows256 = project_history(rows960, projector)
    state_targets256 = projector.transform(states960)
    state_model, state_report = train_state(
        rows256, state_targets256, device, args.state_epochs, args.seed
    )
    checkpoint(args.output / "state_256.pt", state_model, asdict(state_model.cfg), state_report)

    state256 = state_forward_numpy(state_model, rows256, device)
    audio256 = projector.transform(library.audio960)
    pools = make_scorer_rows(rows256, state256, audio256)
    teacher_score = teacher_logits(teacher_scorer_session, states960, pools, library.audio960)
    candidates256 = audio256[pools]
    audio_scorer, audio_report = train_audio_scorer(
        state256, candidates256, teacher_score, device, args.scorer_epochs, args.seed
    )
    checkpoint(args.output / "audio_scorer_256.pt", audio_scorer, asdict(audio_scorer.cfg), audio_report)

    mini_lm = MiniLm(args.text_model, args.text_vocab)
    variants = text_variants(library, mini_lm)
    events = build_event_examples(library, load_events(args.db))
    event_states960 = teacher_states(teacher_state_session, events.rows960)
    event_rows256 = project_history(events.rows960, projector)
    rank_examples = build_rank_examples(
        events, event_rows256, state_model, projector, library, device,
        pool_mode=args.pool_mode, retrieval_text=variants["trusted"],
    )
    raw_state_variants = {
        name: event_text_states(events, variants[name])
        for name in ("correct", "title_poison", "genre_poison", "title_only")
    }
    trusted_variants = {
        "correct": variants["trusted"],
        # Title poisoning is a strict no-op because the title is excluded from this channel.
        "title_poison": variants["trusted"],
        "genre_poison": variants["trusted_genre_poison"],
        "title_only": variants["title_only"],
    }
    trusted_state_variants = {
        name: event_text_states(events, track_text)
        for name, track_text in trusted_variants.items()
    }
    trusted_examples = replace(
        rank_examples,
        text_state=trusted_state_variants["correct"],
        text_correct=trusted_variants["correct"][rank_examples.candidate_rows],
    )
    audio_logits = audio_scorer_numpy(audio_scorer, rank_examples.state, rank_examples.candidates, device)
    baseline = rank_metrics(audio_logits, rank_examples.target_position, rank_examples.pool_hit)
    teacher_event_logits = teacher_logits(
        teacher_scorer_session, event_states960, rank_examples.candidate_rows, library.audio960
    )
    teacher_baseline = rank_metrics(
        teacher_event_logits, rank_examples.target_position, rank_examples.pool_hit
    )
    folds = session_folds(rank_examples.sessions, args.folds, args.seed)
    fold_reports: list[dict] = []
    for fold, (train_indices, val_indices) in enumerate(folds):
        print(f"text fold={fold} train={len(train_indices)} val={len(val_indices)}", flush=True)
        naive_model, naive_report = train_conditioned(
            audio_scorer, rank_examples, variants, raw_state_variants,
            train_indices, val_indices,
            device, args.text_epochs, args.seed + fold, robust=False,
        )
        robust_model, robust_report = train_conditioned(
            audio_scorer, rank_examples, variants, raw_state_variants,
            train_indices, val_indices,
            device, args.text_epochs, args.seed + 100 + fold, robust=True,
        )
        trusted_model, trusted_report = train_conditioned(
            audio_scorer, trusted_examples, trusted_variants, trusted_state_variants,
            train_indices, val_indices,
            device, args.text_epochs, args.seed + 200 + fold, robust=True,
        )
        fold_reports.append({
            "fold": fold, "naive": naive_report, "robust": robust_report,
            "trusted": trusted_report,
        })

    # Final robust model sees every example; retain a deterministic 10% monitor slice.
    indices = np.arange(len(rank_examples.state))
    rng.shuffle(indices)
    monitor = indices[:max(1, len(indices) // 10)]
    final_model, final_report = train_conditioned(
        audio_scorer, rank_examples, variants, raw_state_variants, indices, monitor,
        device, args.text_epochs, args.seed + 999, robust=True,
    )
    final_trusted_model, final_trusted_report = train_conditioned(
        audio_scorer, trusted_examples, trusted_variants, trusted_state_variants,
        indices, monitor, device, args.text_epochs, args.seed + 1999, robust=True,
    )
    checkpoint(
        args.output / "conditioned_scorer_256.pt", final_model,
        {"text_projection": 48, "residual_cap": 0.75}, final_report,
    )
    checkpoint(
        args.output / "conditioned_scorer_trusted_256.pt", final_trusted_model,
        {"text_projection": 48, "residual_cap": 0.75, "title_included": False},
        final_trusted_report,
    )
    export_report = export_models(state_model, final_trusted_model, args.output / "onnx")
    report = {
        "seed": args.seed,
        "pool_mode": args.pool_mode,
        "library_tracks": len(library.ids),
        "real_events": len(load_events(args.db)),
        "real_positive_examples": len(rank_examples.state),
        "state_distill_examples": len(rows960.history),
        "state": state_report,
        "audio_scorer": audio_report,
        "audio_only_real_baseline": baseline,
        "deployed_teacher_real_baseline": teacher_baseline,
        "folds": fold_reports,
        "final": final_report,
        "final_trusted": final_trusted_report,
        "export": export_report,
        "elapsed_seconds": time.time() - started,
    }
    (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({
        "result": str(args.output / "results.json"),
        "elapsed_seconds": report["elapsed_seconds"],
        "baseline": baseline,
        "export": export_report,
    }, indent=2), flush=True)


if __name__ == "__main__":
    main()
