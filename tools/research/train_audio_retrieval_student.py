#!/usr/bin/env python3
"""Retrain LatentJam's shipped 960-D MNv4 audio encoder for music retrieval.

This is the encoder-level experiment runner, not another classifier head.  It
keeps the app's waveform -> 960-D, L2-normalized contract while optimizing:

* per-window cosine preservation against the EfficientAT MN10 teacher;
* optional, dimension-independent relational distillation from MN20/DyMN20;
* low-weight symmetric InfoNCE on public MPD playlist-adjacent pairs.

The data boundary is deliberate.  FMA uses its official split, iTunes is split
by artist group, and MPD is split by whole playlist/session.  Personal library
audio and phone events are never accepted as training or checkpoint-selection
sources.  Decode failures and effectively silent audio raise errors instead of
being replaced with zero waveforms.

Examples (run with latentjam-research's Python environment):

    RESEARCH=/Users/me/Documents/LJ/latentjam-research
    "$RESEARCH/.venv/bin/python" tools/research/train_audio_retrieval_student.py \
      train --research-root "$RESEARCH" --run-name retrieval-distill-v1

    "$RESEARCH/.venv/bin/python" tools/research/train_audio_retrieval_student.py \
      embed-manifest --research-root "$RESEARCH" \
      --checkpoint tools/research/output/audio-retrieval/retrieval-distill-v1/best.pt \
      --manifest "$RESEARCH/models/embed/fma_small_mnv4_distilled.parquet" \
      --output tools/research/output/audio-retrieval/retrieval-distill-v1/fma.parquet

    "$RESEARCH/.venv/bin/python" tools/research/train_audio_retrieval_student.py \
      evaluate --candidate-store candidate.parquet --incumbent-store incumbent.parquet \
      --fma-tracks "$RESEARCH/data/raw/fma_metadata/tracks.csv"
"""

from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import hashlib
import json
import math
import os
import random
import sys
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence

# The research environment can be mounted read-only on a GPU pod.  Keep
# librosa/numba's generated cache out of site-packages.
os.environ.setdefault("NUMBA_CACHE_DIR", "/tmp/latentjam-numba-cache")

import numpy as np
import pandas as pd
import torch
from torch import Tensor, nn
from torch.nn import functional as F
from torch.utils.data import DataLoader, Dataset


APP_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_ROOT = APP_ROOT / "tools/research/output/audio-retrieval"
SAMPLE_RATE = 32_000
WINDOW_SECONDS = 10.0
WINDOW_SAMPLES = int(SAMPLE_RATE * WINDOW_SECONDS)
EMBEDDING_DIM = 960
MODEL_VERSION = "mnv4-960-retrieval-distill-v1"
INCUMBENT_MODEL_VERSION = "mnv4-conv-m-distill-mw-ep4+v3"
ALLOWED_TRAIN_SOURCES = frozenset({"fma", "itunes", "mpd"})
SPLITS = ("training", "validation", "test")


@dataclass(frozen=True)
class AudioItem:
    """One distillation item, optionally with a public-session positive."""

    anchor_path: str
    anchor_id: str
    source: str
    positive_path: str | None = None
    positive_id: str | None = None
    session_id: str | None = None

    @property
    def is_pair(self) -> bool:
        return self.positive_path is not None


@dataclass(frozen=True)
class AudioBatch:
    waveforms: Tensor
    source_ids: tuple[str, ...]
    track_ids: tuple[str, ...]
    pair_positions: tuple[tuple[int, int], ...]
    pair_track_ids: tuple[tuple[str, str], ...]
    pair_session_ids: tuple[str, ...]


class AudioDecodeError(RuntimeError):
    """Raised when training audio cannot produce a real, non-silent window."""


def json_default(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, Tensor):
        return value.detach().cpu().tolist()
    raise TypeError(f"cannot serialize {type(value).__name__}")


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True, default=json_default) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stable_fraction(value: str, seed: int) -> float:
    payload = f"{seed}\0{value}".encode("utf-8")
    integer = int.from_bytes(hashlib.sha256(payload).digest()[:8], "big")
    return integer / float(2**64)


def grouped_split(
    values: Iterable[Any],
    *,
    seed: int,
    validation_fraction: float,
    test_fraction: float,
) -> list[str]:
    """Assign stable group-level train/validation/test labels."""

    if validation_fraction < 0 or test_fraction < 0:
        raise ValueError("split fractions must be non-negative")
    if validation_fraction + test_fraction >= 1:
        raise ValueError("validation_fraction + test_fraction must be < 1")
    result: list[str] = []
    train_cut = 1.0 - validation_fraction - test_fraction
    validation_cut = 1.0 - test_fraction
    for raw in values:
        group = str(raw).strip().casefold()
        if not group or group in {"nan", "none", "null"}:
            raise ValueError("group split received a missing group identifier")
        fraction = stable_fraction(group, seed)
        if fraction < train_cut:
            result.append("training")
        elif fraction < validation_cut:
            result.append("validation")
        else:
            result.append("test")
    return result


def grouped_split_series(
    values: pd.Series,
    *,
    seed: int,
    validation_fraction: float,
    test_fraction: float,
) -> pd.Series:
    """Vectorized wrapper that hashes each unique group exactly once."""

    normalized = values.map(lambda value: _clean_text(value).casefold())
    if normalized.eq("").any():
        raise ValueError("group split received a missing group identifier")
    unique = sorted(normalized.unique())
    labels = grouped_split(
        unique,
        seed=seed,
        validation_fraction=validation_fraction,
        test_fraction=test_fraction,
    )
    return normalized.map(dict(zip(unique, labels, strict=True)))


def resolve_device(requested: str) -> torch.device:
    requested = requested.lower()
    if requested == "auto":
        if torch.cuda.is_available():
            return torch.device("cuda")
        if torch.backends.mps.is_available():
            return torch.device("mps")
        return torch.device("cpu")
    device = torch.device(requested)
    if device.type == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but is unavailable")
    if device.type == "mps" and not torch.backends.mps.is_available():
        raise RuntimeError("MPS was requested but is unavailable")
    return device


def resolve_cuda_amp(
    device: torch.device,
    *,
    disabled: bool,
    requested_dtype: str,
) -> tuple[bool, torch.dtype, str]:
    if disabled or device.type != "cuda":
        return False, torch.float32, "disabled"
    if requested_dtype == "auto":
        requested_dtype = (
            "bf16" if torch.cuda.is_bf16_supported() else "fp16"
        )
    if requested_dtype == "bf16":
        if not torch.cuda.is_bf16_supported():
            raise RuntimeError("BF16 AMP was requested but this CUDA device lacks support")
        return True, torch.bfloat16, "bfloat16"
    if requested_dtype == "fp16":
        return True, torch.float16, "float16+GradScaler"
    raise ValueError(f"unknown AMP dtype: {requested_dtype}")


def build_grad_scaler(enabled: bool) -> Any:
    try:
        return torch.amp.GradScaler("cuda", enabled=enabled)
    except (AttributeError, TypeError):
        return torch.cuda.amp.GradScaler(enabled=enabled)


def batch_norm_modules(model: nn.Module) -> list[nn.Module]:
    """Return every BatchNorm module, including subclasses such as SyncBatchNorm."""

    batch_norm_base = nn.modules.batchnorm._BatchNorm
    return [
        module
        for module in model.modules()
        if isinstance(module, batch_norm_base)
    ]


def freeze_batch_norm_running_stats(model: nn.Module) -> int:
    """Freeze BatchNorm buffers without disabling gradients for affine parameters."""

    modules = batch_norm_modules(model)
    for module in modules:
        # eval() only changes how BatchNorm obtains its normalization statistics.
        # It intentionally leaves weight/bias requires_grad flags untouched.
        module.eval()
    return len(modules)


def seed_everything(seed: int, deterministic: bool = True) -> None:
    os.environ.setdefault("PYTHONHASHSEED", str(seed))
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    if deterministic:
        torch.use_deterministic_algorithms(True, warn_only=True)
        if torch.backends.cudnn.is_available():
            torch.backends.cudnn.benchmark = False
            torch.backends.cudnn.deterministic = True


def worker_seed(worker_id: int) -> None:
    seed = torch.initial_seed() % (2**32)
    np.random.seed(seed)
    random.seed(seed + worker_id)


def ensure_output_inside_app(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    try:
        resolved.relative_to(APP_ROOT)
    except ValueError as exc:
        raise ValueError(
            f"experiment outputs must stay inside the active app worktree {APP_ROOT}; got {resolved}"
        ) from exc
    return resolved


def _clean_text(value: Any) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ""
    return " ".join(str(value).split())


def normalize_identity(value: Any) -> str:
    return unicodedata.normalize("NFKC", _clean_text(value)).casefold()


def canonical_track_key(artist: Any, title: Any) -> str:
    artist_key = normalize_identity(artist)
    title_key = normalize_identity(title)
    if not artist_key or not title_key:
        return ""
    return f"{artist_key}\0{title_key}"


def _manifest_audio_path(row: pd.Series, dataset_root: Path) -> Path:
    raw = Path(str(row["local_path"])).expanduser()
    if raw.is_absolute():
        return raw.resolve()
    source = _clean_text(row.get("source")).casefold()
    candidates = (
        dataset_root / raw,
        dataset_root / source / raw,
        dataset_root / source / "audio" / raw,
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    return candidates[0].resolve()


def _fma_track_number(value: Any) -> int:
    number = int(str(value))
    return number - 10_000_000_000 if number >= 10_000_000_000 else number


def source_stratified_catalog_subset(
    catalog: pd.DataFrame,
    *,
    split: str,
    limit: int,
    seed: int,
) -> pd.DataFrame:
    """Select a stable, source-balanced subset from one existing split.

    Smoke-test limits must not inherit the catalog's source ordering (FMA rows
    precede iTunes rows after concatenation).  Each source is independently
    hash-shuffled, then rows are drawn round-robin across sources.  This keeps
    the two public corpora within one row of each other while both have
    capacity, and deterministically reallocates quota if a source is exhausted.
    A zero limit deliberately preserves the full split and its original order.
    """

    if split not in SPLITS:
        raise ValueError(f"unknown catalog split: {split}")
    if limit < 0:
        raise ValueError("catalog subset limit must be non-negative")
    required = {"source", "track_id", "split"}
    missing = sorted(required.difference(catalog.columns))
    if missing:
        raise ValueError(f"catalog subset is missing columns: {missing}")

    split_frame = catalog[catalog["split"] == split].copy()
    if limit == 0 or limit >= len(split_frame):
        return split_frame
    if split_frame.empty:
        return split_frame
    duplicate_ids = split_frame.duplicated(["source", "track_id"], keep=False)
    if duplicate_ids.any():
        raise ValueError(
            "catalog subset requires unique (source, track_id) rows; "
            f"found {int(duplicate_ids.sum())} duplicates"
        )

    working = split_frame.reset_index(drop=True)
    sources = sorted(
        working["source"].astype(str).unique(),
        key=lambda source: hashlib.sha256(
            f"{seed}\0{split}\0source\0{source}".encode("utf-8")
        ).hexdigest(),
    )
    positions_by_source: dict[str, list[int]] = {}
    for source in sources:
        source_rows = working[working["source"].astype(str) == source].copy()
        source_rows["_subset_hash"] = [
            hashlib.sha256(
                f"{seed}\0{split}\0{source}\0{track_id}".encode("utf-8")
            ).hexdigest()
            for track_id in source_rows["track_id"].astype(str)
        ]
        source_rows = source_rows.sort_values(
            ["_subset_hash", "track_id"], kind="stable"
        )
        positions_by_source[source] = [int(index) for index in source_rows.index]

    selected_positions: list[int] = []
    cursors = {source: 0 for source in sources}
    while len(selected_positions) < limit:
        added = False
        for source in sources:
            cursor = cursors[source]
            positions = positions_by_source[source]
            if cursor >= len(positions):
                continue
            selected_positions.append(positions[cursor])
            cursors[source] = cursor + 1
            added = True
            if len(selected_positions) == limit:
                break
        if not added:
            break
    return working.iloc[selected_positions].reset_index(drop=True)


def audio_item_source_counts(items: Sequence[AudioItem]) -> dict[str, int]:
    """Return deterministic source counts for experiment provenance."""

    counts: dict[str, int] = {}
    for item in items:
        counts[item.source] = counts.get(item.source, 0) + 1
    return {source: counts[source] for source in sorted(counts)}


def catalog_source_counts(catalog: pd.DataFrame) -> dict[str, int]:
    """Return deterministic source counts for a catalog frame."""

    counts = catalog["source"].astype(str).value_counts()
    return {source: int(counts[source]) for source in sorted(counts.index)}


def load_universal_catalog(
    *,
    research_root: Path,
    manifest_path: Path,
    fma_tracks_path: Path,
    seed: int,
    itunes_validation_fraction: float,
    itunes_test_fraction: float,
) -> pd.DataFrame:
    """Load only public FMA/iTunes audio with leakage-safe split labels."""

    required_manifest = {
        "track_id",
        "source",
        "local_path",
        "artist_name",
        "artist_id",
    }
    manifest = pd.read_parquet(manifest_path).reset_index(drop=True)
    missing = sorted(required_manifest.difference(manifest.columns))
    if missing:
        raise ValueError(f"global manifest is missing columns: {missing}")
    manifest["source"] = manifest["source"].astype(str).str.casefold()
    unexpected = sorted(set(manifest["source"]).difference({"fma", "itunes"}))
    if unexpected:
        raise ValueError(
            "training manifest contains sources outside the public allowlist "
            f"(fma, itunes): {unexpected}"
        )

    dataset_root = manifest_path.parent
    manifest["path"] = manifest.apply(
        lambda row: str(_manifest_audio_path(row, dataset_root)), axis=1
    )
    exists = manifest["path"].map(lambda value: Path(value).is_file())
    manifest["audio_exists"] = exists

    fma = manifest[manifest["source"] == "fma"].copy()
    itunes = manifest[manifest["source"] == "itunes"].copy()
    if fma.empty or itunes.empty:
        raise ValueError(
            f"expected both FMA and iTunes rows; found fma={len(fma)}, itunes={len(itunes)}"
        )

    tracks = pd.read_csv(fma_tracks_path, header=[0, 1], index_col=0, low_memory=False)
    needed_fma = [
        ("set", "split"),
        ("set", "subset"),
        ("artist", "id"),
        ("artist", "name"),
        ("track", "genre_top"),
    ]
    missing_fma = [column for column in needed_fma if column not in tracks.columns]
    if missing_fma:
        raise ValueError(f"FMA tracks.csv is missing columns: {missing_fma}")
    fma_meta = pd.DataFrame(
        {
            "fma_id": tracks.index.astype(int),
            "split": tracks[("set", "split")].astype(str),
            "fma_subset": tracks[("set", "subset")].astype(str),
            "artist_group": tracks[("artist", "id")].astype(str),
            "artist_name_official": tracks[("artist", "name")].astype(str),
            "genre": tracks[("track", "genre_top")],
        }
    )
    fma["fma_id"] = fma["track_id"].map(_fma_track_number)
    fma = fma.merge(fma_meta, on="fma_id", how="inner", validate="one_to_one")
    fma = fma[fma["split"].isin(SPLITS)].copy()
    fma["artist_name_key"] = fma["artist_name_official"].map(normalize_identity)
    fma["canonical_track_key"] = [
        canonical_track_key(artist, title)
        for artist, title in zip(
            fma["artist_name_official"], fma["track_name"], strict=True
        )
    ]
    # FMA's official split is artist-ID-disjoint, but a handful of duplicate
    # artist names are represented by different IDs in different splits.  Drop
    # those ambiguous identities rather than let the same apparent artist
    # leak through a name-based downstream lookup.
    fma_name_split_counts = fma.groupby("artist_name_key")["split"].nunique()
    ambiguous_fma_names = set(
        fma_name_split_counts[fma_name_split_counts > 1].index
    )
    ambiguous_fma_names.discard("")
    if ambiguous_fma_names:
        fma = fma[~fma["artist_name_key"].isin(ambiguous_fma_names)].copy()
    fma_track_split_counts = fma[fma["canonical_track_key"] != ""].groupby(
        "canonical_track_key"
    )["split"].nunique()
    ambiguous_fma_tracks = set(
        fma_track_split_counts[fma_track_split_counts > 1].index
    )
    if ambiguous_fma_tracks:
        fma = fma[~fma["canonical_track_key"].isin(ambiguous_fma_tracks)].copy()
    if fma.empty:
        raise ValueError("no FMA rows aligned to the official train/validation/test split")

    itunes["artist_name_key"] = itunes["artist_name"].map(normalize_identity)
    itunes["canonical_track_key"] = [
        canonical_track_key(artist, title)
        for artist, title in zip(
            itunes["artist_name"], itunes["track_name"], strict=True
        )
    ]
    artist_key = itunes["artist_name_key"].copy()
    missing_artist_name = artist_key.eq("")
    artist_key.loc[missing_artist_name] = itunes.loc[
        missing_artist_name, "artist_id"
    ].map(lambda value: "artist-id:" + _clean_text(value))
    if artist_key.eq("").any():
        bad = int(artist_key.eq("").sum())
        raise ValueError(f"{bad} iTunes rows have no artist ID or artist name")
    itunes["artist_group"] = artist_key
    itunes["split"] = grouped_split(
        artist_key,
        seed=seed,
        validation_fraction=itunes_validation_fraction,
        test_fraction=itunes_test_fraction,
    )
    # If an iTunes artist also exists in FMA, inherit FMA's official split so
    # the combined public corpus remains artist-disjoint across sources.
    fma_name_to_split = dict(
        fma[fma["artist_name_key"] != ""]
        .drop_duplicates("artist_name_key")
        .set_index("artist_name_key")["split"]
    )
    inherited_split = itunes["artist_name_key"].map(fma_name_to_split)
    itunes.loc[inherited_split.notna(), "split"] = inherited_split.dropna()
    fma_track_to_split = dict(
        fma[fma["canonical_track_key"] != ""]
        .drop_duplicates("canonical_track_key")
        .set_index("canonical_track_key")["split"]
    )
    inherited_track_split = itunes["canonical_track_key"].map(fma_track_to_split)
    track_artist_conflict = (
        inherited_track_split.notna()
        & inherited_split.notna()
        & (inherited_track_split != inherited_split)
    )
    if track_artist_conflict.any():
        # Conflicting canonical evidence is ambiguous identity data, not a
        # reason to let a track cross a held-out boundary.
        itunes = itunes[~track_artist_conflict].copy()
        inherited_track_split = inherited_track_split[~track_artist_conflict]
    itunes.loc[inherited_track_split.notna(), "split"] = (
        inherited_track_split.dropna()
    )
    itunes["genre"] = itunes.get("primary_genre", "")
    itunes["fma_subset"] = ""

    keep_columns = [
        "track_id",
        "source",
        "path",
        "audio_exists",
        "split",
        "artist_group",
        "artist_name_key",
        "canonical_track_key",
        "artist_name",
        "track_name",
        "collection_name",
        "genre",
        "fma_subset",
    ]
    combined = pd.concat([fma[keep_columns], itunes[keep_columns]], ignore_index=True)
    combined["track_id"] = combined["track_id"].astype(str)
    combined = combined.drop_duplicates(["source", "track_id"], keep=False)
    combined = combined[combined["audio_exists"]].reset_index(drop=True)
    if combined.empty:
        raise ValueError("no public catalog audio files exist on disk")

    for source in ("fma", "itunes"):
        for split in SPLITS:
            groups = set(
                combined.loc[
                    (combined["source"] == source) & (combined["split"] == split),
                    "artist_group",
                ]
            )
            others = set(
                combined.loc[
                    (combined["source"] == source) & (combined["split"] != split),
                    "artist_group",
                ]
            )
            overlap = groups.intersection(others)
            if overlap:
                raise AssertionError(
                    f"{source} artist leakage in split {split}: {len(overlap)} groups"
                )
    for split in SPLITS:
        names = set(combined.loc[combined["split"] == split, "artist_name_key"])
        other_names = set(combined.loc[combined["split"] != split, "artist_name_key"])
        names.discard("")
        other_names.discard("")
        overlap = names.intersection(other_names)
        if overlap:
            raise AssertionError(
                f"cross-source artist-name leakage in split {split}: {len(overlap)} groups"
            )
        track_keys = set(
            combined.loc[combined["split"] == split, "canonical_track_key"]
        )
        other_track_keys = set(
            combined.loc[combined["split"] != split, "canonical_track_key"]
        )
        track_keys.discard("")
        other_track_keys.discard("")
        track_overlap = track_keys.intersection(other_track_keys)
        if track_overlap:
            raise AssertionError(
                f"cross-source canonical-track leakage in split {split}: "
                f"{len(track_overlap)} tracks"
            )
    if set(combined["source"]).difference(ALLOWED_TRAIN_SOURCES):
        raise AssertionError("catalog contains a disallowed training source")
    return combined


def prepare_mpd_store(
    *,
    store_path: Path,
    seed: int,
    validation_fraction: float,
    test_fraction: float,
    artist_split_overrides: dict[str, str] | None = None,
    track_split_overrides: dict[str, str] | None = None,
) -> pd.DataFrame:
    """Assign every MPD track one canonical public-corpus split."""

    store = pd.read_parquet(
        store_path, columns=["track_id", "path", "artist", "title"]
    )
    store["track_id"] = store["track_id"].astype(str)
    store = store.drop_duplicates("track_id", keep=False)
    store["artist_group"] = store["artist"].map(normalize_identity)
    store["canonical_track_key"] = [
        canonical_track_key(artist, title)
        for artist, title in zip(store["artist"], store["title"], strict=True)
    ]
    store = store[
        (store["artist_group"] != "") & (store["canonical_track_key"] != "")
    ].copy()
    store["artist_split"] = grouped_split_series(
        store["artist_group"],
        seed=seed,
        validation_fraction=validation_fraction,
        test_fraction=test_fraction,
    )
    if artist_split_overrides:
        inherited = store["artist_group"].map(artist_split_overrides)
        store.loc[inherited.notna(), "artist_split"] = inherited.dropna()

    store["track_split"] = store["artist_split"]
    if track_split_overrides:
        inherited_track = store["canonical_track_key"].map(track_split_overrides)
        conflict = inherited_track.notna() & (
            inherited_track != store["artist_split"]
        )
        store = store[~conflict].copy()
        inherited_track = inherited_track[~conflict]
        store.loc[inherited_track.notna(), "track_split"] = inherited_track.dropna()
    store["path"] = store["path"].map(
        lambda value: str(Path(value).expanduser().resolve())
    )
    store = store[store["path"].map(lambda value: Path(value).is_file())].copy()
    if not (store["artist_split"] == store["track_split"]).all():
        raise AssertionError("MPD artist and canonical-track split assignments diverged")
    return store.reset_index(drop=True)


def load_mpd_catalog_items(
    *,
    store_path: Path,
    split: str,
    seed: int,
    validation_fraction: float,
    test_fraction: float,
    artist_split_overrides: dict[str, str] | None,
    track_split_overrides: dict[str, str] | None,
) -> list[AudioItem]:
    store = prepare_mpd_store(
        store_path=store_path,
        seed=seed,
        validation_fraction=validation_fraction,
        test_fraction=test_fraction,
        artist_split_overrides=artist_split_overrides,
        track_split_overrides=track_split_overrides,
    )
    store = store[store["artist_split"] == split]
    return [
        AudioItem(
            anchor_path=str(row.path),
            anchor_id=str(row.track_id),
            source="mpd",
        )
        for row in store.itertuples(index=False)
    ]


def audit_cross_source_splits(
    catalog: pd.DataFrame,
    mpd_store: pd.DataFrame,
) -> dict[str, Any]:
    mpd = mpd_store.rename(columns={"artist_split": "split"})
    audit: dict[str, Any] = {"splits": {}}
    for split in SPLITS:
        artists = (
            set(catalog.loc[catalog["split"] == split, "artist_name_key"])
            | set(mpd.loc[mpd["split"] == split, "artist_group"])
        )
        other_artists = (
            set(catalog.loc[catalog["split"] != split, "artist_name_key"])
            | set(mpd.loc[mpd["split"] != split, "artist_group"])
        )
        tracks = (
            set(catalog.loc[catalog["split"] == split, "canonical_track_key"])
            | set(mpd.loc[mpd["split"] == split, "canonical_track_key"])
        )
        other_tracks = (
            set(catalog.loc[catalog["split"] != split, "canonical_track_key"])
            | set(mpd.loc[mpd["split"] != split, "canonical_track_key"])
        )
        artists.discard("")
        other_artists.discard("")
        tracks.discard("")
        other_tracks.discard("")
        artist_overlap = artists.intersection(other_artists)
        track_overlap = tracks.intersection(other_tracks)
        audit["splits"][split] = {
            "canonical_artists": len(artists),
            "canonical_tracks": len(tracks),
            "cross_split_artist_overlap": len(artist_overlap),
            "cross_split_exact_track_overlap": len(track_overlap),
        }
        if artist_overlap or track_overlap:
            raise AssertionError(
                f"global {split} split leakage: artists={len(artist_overlap)}, "
                f"exact_tracks={len(track_overlap)}"
            )
    audit["passed"] = True
    return audit


def load_mpd_pairs(
    *,
    events_path: Path,
    store_path: Path,
    research_root: Path,
    split: str,
    seed: int,
    validation_fraction: float,
    test_fraction: float,
    max_pairs: int,
    artist_split_overrides: dict[str, str] | None = None,
    track_split_overrides: dict[str, str] | None = None,
) -> list[AudioItem]:
    """Build strict artist- and whole-session-disjoint public MPD pairs.

    MPD tracks recur across many playlists, so playlist splitting by itself is
    not a meaningful encoder holdout.  Adjacency is formed first, then a pair
    is retained only when the playlist, anchor artist, and positive artist all
    hash to the requested split.
    """

    store = prepare_mpd_store(
        store_path=store_path,
        seed=seed,
        validation_fraction=validation_fraction,
        test_fraction=test_fraction,
        artist_split_overrides=artist_split_overrides,
        track_split_overrides=track_split_overrides,
    )
    path_by_id = {
        row.track_id: str(Path(row.path).expanduser().resolve())
        for row in store.itertuples(index=False)
        if Path(row.path).expanduser().is_file()
    }
    artist_split_by_id = dict(
        zip(store["track_id"], store["artist_split"], strict=True)
    )
    events = pd.read_parquet(
        events_path,
        columns=["session_id", "ts_unix_ms", "track_id"],
    )
    events["session_id"] = events["session_id"].astype(str)
    events["track_id"] = events["track_id"].astype(str)
    events["session_split"] = grouped_split_series(
        events["session_id"],
        seed=seed,
        validation_fraction=validation_fraction,
        test_fraction=test_fraction,
    )
    events = events.sort_values(["session_id", "ts_unix_ms"], kind="stable")
    events["positive_id"] = events.groupby("session_id", sort=False)["track_id"].shift(-1)
    pairs = events.dropna(subset=["positive_id"]).copy()
    pairs["anchor_artist_split"] = pairs["track_id"].map(artist_split_by_id)
    pairs["positive_artist_split"] = pairs["positive_id"].map(artist_split_by_id)
    pairs = pairs[
        pairs["track_id"].isin(path_by_id) & pairs["positive_id"].isin(path_by_id)
    ]
    pairs = pairs[
        (pairs["session_split"] == split)
        & (pairs["anchor_artist_split"] == split)
        & (pairs["positive_artist_split"] == split)
    ]
    pairs = pairs[pairs["track_id"] != pairs["positive_id"]]
    if max_pairs > 0 and len(pairs) > max_pairs:
        rng = np.random.default_rng(seed + SPLITS.index(split) * 1009)
        chosen = np.sort(rng.choice(len(pairs), size=max_pairs, replace=False))
        pairs = pairs.iloc[chosen]
    return [
        AudioItem(
            anchor_path=path_by_id[str(row.track_id)],
            anchor_id=str(row.track_id),
            positive_path=path_by_id[str(row.positive_id)],
            positive_id=str(row.positive_id),
            session_id=str(row.session_id),
            source="mpd",
        )
        for row in pairs.itertuples(index=False)
    ]


def sample_session_items(
    base: Sequence[AudioItem],
    pairs: Sequence[AudioItem],
    *,
    pair_fraction: float,
    seed: int,
) -> list[AudioItem]:
    if not 0.0 <= pair_fraction < 1.0:
        raise ValueError("pair_fraction must be in [0, 1)")
    if not pairs or pair_fraction == 0:
        return list(base)
    target = int(round(len(base) * pair_fraction / max(1e-12, 1.0 - pair_fraction)))
    target = min(target, len(pairs))
    rng = np.random.default_rng(seed)
    indices = np.sort(rng.choice(len(pairs), size=target, replace=False))
    result = list(base) + [pairs[int(index)] for index in indices]
    random.Random(seed).shuffle(result)
    return result


def deterministic_window_starts(
    sample_count: int,
    *,
    window_samples: int = WINDOW_SAMPLES,
    windows: int = 1,
    policy: str = "center",
) -> tuple[int, ...]:
    """Return fixed evaluation windows without padding-induced fake silence."""

    if sample_count <= 0:
        raise ValueError("sample_count must be positive")
    if windows <= 0:
        raise ValueError("windows must be positive")
    max_start = max(0, sample_count - window_samples)
    if policy == "center":
        if windows == 1 or max_start == 0:
            return (max_start // 2,)
        span = min(max_start, (windows - 1) * window_samples)
        first = max(0, (max_start - span) // 2)
        return tuple(
            min(max_start, first + index * window_samples) for index in range(windows)
        )
    if policy == "contiguous":
        return tuple(min(max_start, index * window_samples) for index in range(windows))
    if policy == "uniform":
        return tuple(
            int(round(value))
            for value in np.linspace(0, max_start, num=windows, dtype=np.float64)
        )
    raise ValueError(f"unknown window policy: {policy}")


def decode_audio(
    path: str,
    *,
    rng: np.random.Generator | None,
    windows: int = 1,
    policy: str = "random",
    min_rms: float = 1e-6,
) -> np.ndarray:
    """Decode real audio; never substitute a zero waveform on failure."""

    import librosa

    resolved = Path(path).expanduser().resolve()
    if not resolved.is_file():
        raise AudioDecodeError(f"audio file does not exist: {resolved}")
    try:
        duration_seconds = float(librosa.get_duration(path=resolved))
    except Exception as exc:
        raise AudioDecodeError(f"failed to inspect {resolved}: {exc}") from exc
    if not math.isfinite(duration_seconds) or duration_seconds <= 0:
        raise AudioDecodeError(f"invalid audio duration {duration_seconds}: {resolved}")
    sample_count = max(1, int(round(duration_seconds * SAMPLE_RATE)))
    max_start = max(0, sample_count - WINDOW_SAMPLES)
    if policy == "random":
        if rng is None:
            raise ValueError("random window selection requires an RNG")
        starts = tuple(
            int(rng.integers(0, max_start + 1)) if max_start else 0
            for _ in range(windows)
        )
    else:
        starts = deterministic_window_starts(
            sample_count,
            window_samples=WINDOW_SAMPLES,
            windows=windows,
            policy=policy,
        )
    result = np.zeros((windows, WINDOW_SAMPLES), dtype=np.float32)
    for index, start in enumerate(starts):
        try:
            available, _ = librosa.load(
                resolved,
                sr=SAMPLE_RATE,
                mono=True,
                dtype=np.float32,
                offset=start / SAMPLE_RATE,
                duration=WINDOW_SECONDS,
            )
        except Exception as exc:
            raise AudioDecodeError(
                f"failed to decode window at {start / SAMPLE_RATE:.3f}s from {resolved}: {exc}"
            ) from exc
        available = np.asarray(available, dtype=np.float32)
        if available.ndim != 1 or available.size == 0:
            raise AudioDecodeError(f"decoded empty/non-mono window: {resolved}")
        if not np.isfinite(available).all():
            raise AudioDecodeError(f"decoded non-finite window: {resolved}")
        available = available[:WINDOW_SAMPLES]
        result[index, : available.size] = available
        window_rms = float(np.sqrt(np.mean(np.square(available, dtype=np.float64))))
        peak = float(np.max(np.abs(available)))
        if window_rms < min_rms or peak < min_rms * 4:
            raise AudioDecodeError(
                "selected window is effectively silent "
                f"(rms={window_rms:.3g}, peak={peak:.3g}): {resolved}"
            )
    return result


def _probe_audio_path(path: str) -> dict[str, Any]:
    resolved = Path(path).expanduser().resolve()
    try:
        stat = resolved.stat()
        decode_audio(str(resolved), rng=None, policy="center")
        return {
            "path": str(resolved),
            "size": int(stat.st_size),
            "mtime_ns": int(stat.st_mtime_ns),
            "valid": True,
            "error": "",
        }
    except Exception as exc:
        try:
            stat = resolved.stat()
            size, mtime_ns = int(stat.st_size), int(stat.st_mtime_ns)
        except OSError:
            size, mtime_ns = -1, -1
        return {
            "path": str(resolved),
            "size": size,
            "mtime_ns": mtime_ns,
            "valid": False,
            "error": f"{type(exc).__name__}: {exc}",
        }


def preflight_audio_paths(
    paths: Iterable[str],
    *,
    cache_path: Path,
    workers: int,
) -> tuple[set[str], dict[str, Any]]:
    """Probe deterministic center windows and cache results by file signature."""

    if workers <= 0:
        raise ValueError("preflight workers must be positive")
    requested = sorted({str(Path(path).expanduser().resolve()) for path in paths})
    cache_path = ensure_output_inside_app(cache_path)
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    columns = ["path", "size", "mtime_ns", "valid", "error"]
    if cache_path.is_file():
        cached = pd.read_parquet(cache_path)
        missing_columns = sorted(set(columns).difference(cached.columns))
        if missing_columns:
            raise ValueError(
                f"audio preflight cache is missing columns: {missing_columns}"
            )
        cached = cached[columns].drop_duplicates("path", keep="last")
    else:
        cached = pd.DataFrame(columns=columns)
    cached_by_path = {
        str(row.path): row
        for row in cached.itertuples(index=False)
    }

    reused: list[dict[str, Any]] = []
    pending: list[str] = []
    for path in requested:
        row = cached_by_path.get(path)
        try:
            stat = Path(path).stat()
            unchanged = (
                row is not None
                and int(row.size) == int(stat.st_size)
                and int(row.mtime_ns) == int(stat.st_mtime_ns)
            )
        except OSError:
            unchanged = False
        if unchanged:
            reused.append(
                {
                    "path": path,
                    "size": int(row.size),
                    "mtime_ns": int(row.mtime_ns),
                    "valid": bool(row.valid),
                    "error": str(row.error),
                }
            )
        else:
            pending.append(path)

    if pending and workers == 1:
        probed = [_probe_audio_path(path) for path in pending]
    elif pending:
        # librosa/audioread's lazy decoder initialization is not thread-safe.
        # Separate processes provide parallel probing without shared decoder
        # state; use one worker on memory-constrained/macOS environments.
        with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as executor:
            probed = list(executor.map(_probe_audio_path, pending))
    else:
        probed = []
    requested_results = reused + probed
    updated_by_path = {
        str(row.path): {
            "path": str(row.path),
            "size": int(row.size),
            "mtime_ns": int(row.mtime_ns),
            "valid": bool(row.valid),
            "error": str(row.error),
        }
        for row in cached.itertuples(index=False)
    }
    updated_by_path.update({row["path"]: row for row in probed})
    updated = pd.DataFrame(
        [updated_by_path[path] for path in sorted(updated_by_path)],
        columns=columns,
    )
    temporary = cache_path.with_suffix(cache_path.suffix + ".tmp")
    updated.to_parquet(temporary, index=False)
    temporary.replace(cache_path)

    valid = {row["path"] for row in requested_results if row["valid"]}
    invalid = [
        {"path": row["path"], "error": row["error"]}
        for row in requested_results
        if not row["valid"]
    ]
    report = {
        "cache_path": str(cache_path),
        "requested_paths": len(requested),
        "reused_cache_entries": len(reused),
        "newly_probed": len(probed),
        "valid_paths": len(valid),
        "invalid_paths": len(invalid),
        "invalid": invalid,
        "zero_substitutions": 0,
    }
    return valid, report


def filter_preflight_items(
    items: Sequence[AudioItem],
    valid_paths: set[str],
) -> tuple[list[AudioItem], int]:
    kept: list[AudioItem] = []
    dropped = 0
    for item in items:
        required = {str(Path(item.anchor_path).expanduser().resolve())}
        if item.positive_path is not None:
            required.add(str(Path(item.positive_path).expanduser().resolve()))
        if required.issubset(valid_paths):
            kept.append(item)
        else:
            dropped += 1
    return kept, dropped


class DistillationDataset(Dataset[AudioItem]):
    def __init__(self, items: Sequence[AudioItem], *, seed: int, training: bool):
        self.items = list(items)
        self.seed = int(seed)
        self.training = bool(training)
        self.epoch = 0

    def set_epoch(self, epoch: int) -> None:
        self.epoch = int(epoch)

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, index: int) -> dict[str, Any]:
        item = self.items[index]
        local_seed = self.seed + self.epoch * 1_000_003 + index * 97
        rng = np.random.default_rng(local_seed)
        policy = "random" if self.training else "center"
        try:
            anchor = decode_audio(item.anchor_path, rng=rng, policy=policy)[0]
        except AudioDecodeError:
            if not self.training:
                raise
            # A valid song can contain a silent intro/outro.  Retry the
            # preflight-validated center; still raise if no real audio exists.
            anchor = decode_audio(item.anchor_path, rng=None, policy="center")[0]
        positive = None
        if item.positive_path is not None:
            try:
                positive = decode_audio(
                    item.positive_path, rng=rng, policy=policy
                )[0]
            except AudioDecodeError:
                if not self.training:
                    raise
                positive = decode_audio(
                    item.positive_path, rng=None, policy="center"
                )[0]
        return {"item": item, "anchor": anchor, "positive": positive}


def collate_distillation(rows: Sequence[dict[str, Any]]) -> AudioBatch:
    waveforms: list[np.ndarray] = []
    sources: list[str] = []
    track_ids: list[str] = []
    pair_positions: list[tuple[int, int]] = []
    pair_track_ids: list[tuple[str, str]] = []
    pair_session_ids: list[str] = []
    for row in rows:
        item: AudioItem = row["item"]
        anchor_position = len(waveforms)
        waveforms.append(np.asarray(row["anchor"], dtype=np.float32))
        sources.append(item.source)
        track_ids.append(item.anchor_id)
        if row["positive"] is not None:
            positive_position = len(waveforms)
            waveforms.append(np.asarray(row["positive"], dtype=np.float32))
            sources.append(item.source)
            track_ids.append(str(item.positive_id))
            pair_positions.append((anchor_position, positive_position))
            pair_track_ids.append((item.anchor_id, str(item.positive_id)))
            pair_session_ids.append(str(item.session_id))
    if not waveforms:
        raise ValueError("cannot collate an empty audio batch")
    return AudioBatch(
        waveforms=torch.from_numpy(np.stack(waveforms)),
        source_ids=tuple(sources),
        track_ids=tuple(track_ids),
        pair_positions=tuple(pair_positions),
        pair_track_ids=tuple(pair_track_ids),
        pair_session_ids=tuple(pair_session_ids),
    )


@contextlib.contextmanager
def pushd(path: Path) -> Iterator[None]:
    previous = Path.cwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(previous)


def import_student_class(research_root: Path) -> tuple[type[nn.Module], Any]:
    src = str((research_root / "src").resolve())
    if src not in sys.path:
        sys.path.insert(0, src)
    from student.mnv4_model import MNv4DistillStudent, load_distill_checkpoint

    return MNv4DistillStudent, load_distill_checkpoint


def build_student(research_root: Path, checkpoint: Path, device: torch.device) -> nn.Module:
    student_class, load_checkpoint = import_student_class(research_root)
    model = student_class(
        project_dim=EMBEDDING_DIM,
        pretrained_backbone=False,
    )
    info = load_checkpoint(model, str(checkpoint), map_location="cpu")
    missing = [
        key for key in info["missing_keys"] if not key.endswith("num_batches_tracked")
    ]
    if missing or info["unexpected_keys"]:
        raise ValueError(
            f"checkpoint is not the shipped MNv4 contract; missing={missing}, "
            f"unexpected={info['unexpected_keys']}"
        )
    if getattr(model, "project_dim", None) != EMBEDDING_DIM:
        raise ValueError(f"student output is not {EMBEDDING_DIM}-dimensional")
    return model.to(device)


class EfficientATTeachers(nn.Module):
    """Frozen MN10 plus an optional stronger EfficientAT geometry teacher."""

    def __init__(
        self,
        *,
        research_root: Path,
        strong_name: str,
        device: torch.device,
    ):
        super().__init__()
        efficientat_root = (research_root / "EfficientAT").resolve()
        weights_root = (research_root / "models/efficientat").resolve()
        if str(efficientat_root) not in sys.path:
            sys.path.insert(0, str(efficientat_root))
        with open(os.devnull, "w") as null_stdout, pushd(
            efficientat_root
        ), contextlib.redirect_stdout(null_stdout):
            from helpers.utils import NAME_TO_WIDTH
            from models.dymn.model import get_model as get_dymn
            from models.mn.model import get_model as get_mn
            from models.preprocess import AugmentMelSTFT

            self.mel = AugmentMelSTFT(
                n_mels=128,
                sr=SAMPLE_RATE,
                win_length=800,
                hopsize=320,
                n_fft=1024,
                freqm=0,
                timem=0,
                fmin=0.0,
                fmax=None,
                fmin_aug_range=1,
                fmax_aug_range=1,
            )
            self.mn10 = get_mn(
                width_mult=NAME_TO_WIDTH("mn10_as"),
                pretrained_name=None,
                strides=[2, 2, 2, 2],
                head_type="mlp",
            )
            self.mn10.load_state_dict(
                torch.load(
                    weights_root / "mn10_as_mAP_471.pt",
                    map_location="cpu",
                    weights_only=True,
                )
            )
            self.strong: nn.Module | None = None
            if strong_name == "mn20":
                self.strong = get_mn(
                    width_mult=NAME_TO_WIDTH("mn20_as"),
                    pretrained_name=None,
                    strides=[2, 2, 2, 2],
                    head_type="mlp",
                )
                self.strong.load_state_dict(
                    torch.load(
                        weights_root / "mn20_as_mAP_478.pt",
                        map_location="cpu",
                        weights_only=True,
                    )
                )
            elif strong_name == "dymn20":
                self.strong = get_dymn(
                    width_mult=NAME_TO_WIDTH("dymn20_as"),
                    pretrained_name=None,
                    strides=[2, 2, 2, 2],
                    # Loading the AudioSet checkpoint manually avoids a network
                    # fetch, so explicitly reproduce get_model's pretrained
                    # inference temperature instead of its scratch-training 30.
                    T_max=1.0,
                    T_min=1.0,
                )
                self.strong.load_state_dict(
                    torch.load(
                        weights_root / "dymn20_as_mAP_493.pt",
                        map_location="cpu",
                        weights_only=True,
                    )
                )
            elif strong_name != "none":
                raise ValueError(f"unknown stronger teacher: {strong_name}")
        for parameter in self.parameters():
            parameter.requires_grad_(False)
        self.to(device).eval()

    @torch.inference_mode()
    def forward(self, waveforms: Tensor) -> tuple[Tensor, Tensor | None]:
        spectrogram = self.mel(waveforms)
        if spectrogram.ndim == 3:
            spectrogram = spectrogram.unsqueeze(1)
        _, mn10_features = self.mn10(spectrogram)
        mn10_features = F.normalize(mn10_features.float(), dim=-1)
        if mn10_features.shape[-1] != EMBEDDING_DIM:
            raise RuntimeError(
                f"MN10 feature dimension changed: {mn10_features.shape[-1]} != {EMBEDDING_DIM}"
            )
        strong_features = None
        if self.strong is not None:
            _, strong_features = self.strong(spectrogram)
            strong_features = F.normalize(strong_features.float(), dim=-1)
        return mn10_features, strong_features


def relational_similarity_loss(student: Tensor, teacher: Tensor) -> Tensor:
    """Match pairwise cosine geometry without requiring equal dimensions."""

    if student.ndim != 2 or teacher.ndim != 2:
        raise ValueError("relational loss expects two matrices")
    if student.shape[0] != teacher.shape[0]:
        raise ValueError("student and teacher batch sizes differ")
    if student.shape[0] < 2:
        return student.sum() * 0.0
    student_similarity = F.normalize(student, dim=-1) @ F.normalize(student, dim=-1).T
    teacher_similarity = F.normalize(teacher, dim=-1) @ F.normalize(teacher, dim=-1).T
    upper = torch.triu(
        torch.ones_like(student_similarity, dtype=torch.bool),
        diagonal=1,
    )
    return F.mse_loss(student_similarity[upper], teacher_similarity[upper])


def symmetric_info_nce(
    anchors: Tensor,
    positives: Tensor,
    *,
    temperature: float,
    track_ids: Sequence[tuple[str, str]],
    session_ids: Sequence[str],
) -> Tensor:
    """Symmetric pair loss with known MPD false negatives masked out."""

    if anchors.shape != positives.shape:
        raise ValueError("anchor and positive embedding shapes differ")
    count = anchors.shape[0]
    if count < 2:
        return anchors.sum() * 0.0
    if len(track_ids) != count or len(session_ids) != count:
        raise ValueError("pair metadata does not match embedding count")
    logits = F.normalize(anchors, dim=-1) @ F.normalize(positives, dim=-1).T
    logits = logits / temperature
    invalid = torch.zeros((count, count), dtype=torch.bool, device=logits.device)
    for i in range(count):
        anchor_i, positive_i = track_ids[i]
        for j in range(count):
            if i == j:
                continue
            anchor_j, positive_j = track_ids[j]
            if (
                session_ids[i] == session_ids[j]
                or anchor_i == positive_j
                or positive_i == anchor_j
                or anchor_i == anchor_j
                or positive_i == positive_j
            ):
                invalid[i, j] = True
    logits = logits.masked_fill(invalid, torch.finfo(logits.dtype).min)
    labels = torch.arange(count, device=logits.device)
    return 0.5 * (
        F.cross_entropy(logits, labels) + F.cross_entropy(logits.T, labels)
    )


def loss_components(
    student_embeddings: Tensor,
    mn10_embeddings: Tensor,
    strong_embeddings: Tensor | None,
    batch: AudioBatch,
    *,
    strong_relational_weight: float,
    session_weight: float,
    temperature: float,
) -> dict[str, Tensor]:
    cosine = (1.0 - F.cosine_similarity(student_embeddings, mn10_embeddings)).mean()
    relational = (
        relational_similarity_loss(student_embeddings, strong_embeddings)
        if strong_embeddings is not None
        else student_embeddings.sum() * 0.0
    )
    if batch.pair_positions:
        anchor_positions = torch.tensor(
            [pair[0] for pair in batch.pair_positions],
            device=student_embeddings.device,
        )
        positive_positions = torch.tensor(
            [pair[1] for pair in batch.pair_positions],
            device=student_embeddings.device,
        )
        session = symmetric_info_nce(
            student_embeddings[anchor_positions],
            student_embeddings[positive_positions],
            temperature=temperature,
            track_ids=batch.pair_track_ids,
            session_ids=batch.pair_session_ids,
        )
    else:
        session = student_embeddings.sum() * 0.0
    total = cosine + strong_relational_weight * relational + session_weight * session
    return {
        "total": total,
        "mn10_cosine_loss": cosine,
        "strong_relational_loss": relational,
        "session_info_nce": session,
    }


def retrieval_ranks(
    anchors: np.ndarray,
    positives: np.ndarray,
    *,
    query_ids: Sequence[str] | None = None,
    candidate_ids: Sequence[str] | None = None,
    block_size: int = 256,
) -> np.ndarray:
    anchors = np.asarray(anchors, dtype=np.float32)
    positives = np.asarray(positives, dtype=np.float32)
    anchors /= np.maximum(np.linalg.norm(anchors, axis=1, keepdims=True), 1e-12)
    positives /= np.maximum(np.linalg.norm(positives, axis=1, keepdims=True), 1e-12)
    ranks = np.empty(len(anchors), dtype=np.float64)
    for start in range(0, len(anchors), block_size):
        stop = min(len(anchors), start + block_size)
        scores = anchors[start:stop] @ positives.T
        for local, row in enumerate(range(start, stop)):
            target_score = scores[local, row]
            eligible = np.ones(scores.shape[1], dtype=bool)
            if query_ids is not None and candidate_ids is not None:
                eligible &= np.asarray(candidate_ids) != str(query_ids[row])
            greater = eligible & (scores[local] > target_score)
            tied = eligible & (scores[local] == target_score)
            tied_others = max(0, int(tied.sum()) - 1)
            ranks[row] = 1.0 + float(greater.sum()) + 0.5 * tied_others
    return ranks


def catalog_retrieval_ranks(
    queries: np.ndarray,
    catalog: np.ndarray,
    *,
    target_ids: Sequence[str],
    query_ids: Sequence[str],
    candidate_ids: Sequence[str],
    block_size: int = 256,
) -> np.ndarray:
    """Average-tie ranks against a full eligible candidate catalog."""

    queries = np.asarray(queries, dtype=np.float32)
    catalog = np.asarray(catalog, dtype=np.float32)
    queries /= np.maximum(np.linalg.norm(queries, axis=1, keepdims=True), 1e-12)
    catalog /= np.maximum(np.linalg.norm(catalog, axis=1, keepdims=True), 1e-12)
    candidate_ids_array = np.asarray(candidate_ids, dtype=str)
    candidate_index = {
        str(track_id): index for index, track_id in enumerate(candidate_ids_array)
    }
    missing = sorted(set(map(str, target_ids)).difference(candidate_index))
    if missing:
        raise ValueError(f"{len(missing)} retrieval targets are absent from the catalog")
    target_indices = np.asarray(
        [candidate_index[str(track_id)] for track_id in target_ids],
        dtype=np.int64,
    )
    ranks = np.empty(len(queries), dtype=np.float64)
    for start in range(0, len(queries), block_size):
        stop = min(len(queries), start + block_size)
        scores = queries[start:stop] @ catalog.T
        for local, row in enumerate(range(start, stop)):
            target_score = scores[local, target_indices[row]]
            eligible = candidate_ids_array != str(query_ids[row])
            greater = eligible & (scores[local] > target_score)
            tied = eligible & (scores[local] == target_score)
            tied_others = max(0, int(tied.sum()) - 1)
            ranks[row] = 1.0 + float(greater.sum()) + 0.5 * tied_others
    return ranks


def rank_metrics(ranks: np.ndarray) -> dict[str, float]:
    ranks = np.asarray(ranks, dtype=np.float64)
    if ranks.size == 0:
        return {}
    return {
        "mrr": float(np.mean(1.0 / ranks)),
        "recall_at_1": float(np.mean(ranks <= 1)),
        "recall_at_5": float(np.mean(ranks <= 5)),
        "recall_at_10": float(np.mean(ranks <= 10)),
        "median_rank": float(np.median(ranks)),
        "pairs": int(ranks.size),
    }


@torch.inference_mode()
def evaluate_validation(
    model: nn.Module,
    teachers: EfficientATTeachers,
    loader: DataLoader,
    *,
    device: torch.device,
    strong_relational_weight: float,
    session_weight: float,
    temperature: float,
    max_batches: int,
    max_pairs: int,
    full_catalog: bool,
) -> dict[str, Any]:
    model.eval()
    aggregates: dict[str, list[float]] = {
        "total": [],
        "mn10_cosine_loss": [],
        "strong_relational_loss": [],
        "session_info_nce": [],
    }
    pair_anchors: list[np.ndarray] = []
    pair_anchor_ids: list[str] = []
    pair_positive_ids: list[str] = []
    mpd_catalog: dict[str, np.ndarray] = {}
    examples = 0
    for batch_index, batch in enumerate(loader):
        if max_batches > 0 and batch_index >= max_batches:
            break
        waveforms = batch.waveforms.to(device)
        student = model(waveforms)
        mn10, strong = teachers(waveforms)
        components = loss_components(
            student,
            mn10,
            strong,
            batch,
            strong_relational_weight=strong_relational_weight,
            session_weight=session_weight,
            temperature=temperature,
        )
        for key, value in components.items():
            aggregates[key].append(float(value.detach().cpu()))
        examples += int(waveforms.shape[0])
        student_cpu = student.cpu().numpy()
        for position, (source, track_id) in enumerate(
            zip(batch.source_ids, batch.track_ids, strict=True)
        ):
            if source == "mpd" and track_id not in mpd_catalog:
                mpd_catalog[track_id] = student_cpu[position].astype(
                    np.float32, copy=True
                )
        for pair_index, (anchor_pos, positive_pos) in enumerate(batch.pair_positions):
            if len(pair_anchors) >= max_pairs:
                break
            pair_anchors.append(student_cpu[anchor_pos : anchor_pos + 1].copy())
            pair_anchor_ids.append(batch.pair_track_ids[pair_index][0])
            pair_positive_ids.append(batch.pair_track_ids[pair_index][1])
    result: dict[str, Any] = {
        key: float(np.mean(values)) if values else 0.0
        for key, values in aggregates.items()
    }
    result["mn10_cosine"] = 1.0 - result["mn10_cosine_loss"]
    result["examples"] = examples
    if pair_anchors:
        anchors = np.concatenate(pair_anchors, axis=0)
        if max_batches > 0 or not full_catalog:
            positives = np.stack(
                [mpd_catalog[track_id] for track_id in pair_positive_ids]
            )
            ranks = retrieval_ranks(
                anchors,
                positives,
                query_ids=pair_anchor_ids,
                candidate_ids=pair_positive_ids,
            )
            result["session_retrieval_proxy"] = rank_metrics(ranks)
        else:
            candidate_ids = sorted(mpd_catalog)
            candidate_matrix = np.stack(
                [mpd_catalog[track_id] for track_id in candidate_ids]
            )
            ranks = catalog_retrieval_ranks(
                anchors,
                candidate_matrix,
                target_ids=pair_positive_ids,
                query_ids=pair_anchor_ids,
                candidate_ids=candidate_ids,
            )
            result["session_retrieval"] = {
                **rank_metrics(ranks),
                "eligible_catalog_tracks": len(candidate_ids),
            }
    return result


def capture_rng_state() -> dict[str, Any]:
    return {
        "python": random.getstate(),
        "numpy": np.random.get_state(),
        "torch": torch.get_rng_state(),
        "cuda": torch.cuda.get_rng_state_all() if torch.cuda.is_available() else None,
    }


def restore_rng_state(state: dict[str, Any]) -> None:
    random.setstate(state["python"])
    np.random.set_state(state["numpy"])
    torch.set_rng_state(state["torch"])
    if torch.cuda.is_available() and state.get("cuda") is not None:
        torch.cuda.set_rng_state_all(state["cuda"])


def checkpoint_payload(
    *,
    model: nn.Module,
    optimizer: torch.optim.Optimizer,
    scheduler: torch.optim.lr_scheduler.LRScheduler,
    epoch: int,
    best_score: float,
    baseline: dict[str, Any],
    validation: dict[str, Any],
    config: dict[str, Any],
    scaler: Any,
    model_version: str = MODEL_VERSION,
) -> dict[str, Any]:
    model_config = model.config_dict() if hasattr(model, "config_dict") else {}
    if int(model_config.get("embedding_dim", EMBEDDING_DIM)) != EMBEDDING_DIM:
        raise RuntimeError("refusing to save a checkpoint that breaks the 960-D app contract")
    return {
        "format_version": 1,
        "model_version": model_version,
        "model": model.state_dict(),
        "model_state_dict": model.state_dict(),
        "optimizer": optimizer.state_dict(),
        "scheduler": scheduler.state_dict(),
        "grad_scaler": scaler.state_dict(),
        "epoch": epoch,
        "best_score": best_score,
        "baseline": baseline,
        "validation": validation,
        "train_config": config,
        "model_config": model_config,
        "app_contract": {
            "input_name": "waveform",
            "input_shape": [1, WINDOW_SAMPLES],
            "input_dtype": "float32",
            "output_name": "embedding",
            "output_shape": [1, EMBEDDING_DIM],
            "output_dtype": "float32",
            "l2_normalized": True,
        },
        "rng_state": capture_rng_state(),
    }


def build_loader(
    dataset: DistillationDataset,
    *,
    batch_size: int,
    shuffle: bool,
    num_workers: int,
    seed: int,
) -> DataLoader:
    generator = torch.Generator()
    generator.manual_seed(seed)
    return DataLoader(
        dataset,
        batch_size=batch_size,
        shuffle=shuffle,
        num_workers=num_workers,
        collate_fn=collate_distillation,
        worker_init_fn=worker_seed,
        generator=generator,
        persistent_workers=False,
        pin_memory=torch.cuda.is_available(),
        drop_last=shuffle,
    )


def selection_score(metrics: dict[str, Any]) -> float:
    session = metrics.get("session_retrieval", {})
    if session:
        return float(session["mrr"])
    return float(metrics["mn10_cosine"])


def validate_train_args(args: argparse.Namespace) -> None:
    positive = {
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "eval_batch_size": args.eval_batch_size,
        "learning_rate": args.learning_rate,
        "temperature": args.temperature,
        "max_grad_norm": args.max_grad_norm,
        "log_every": args.log_every,
        "preflight_workers": args.preflight_workers,
    }
    invalid = {key: value for key, value in positive.items() if value <= 0}
    if invalid:
        raise ValueError(f"training arguments must be positive: {invalid}")
    nonnegative = {
        "weight_decay": args.weight_decay,
        "strong_relational_weight": args.strong_relational_weight,
        "session_weight": args.session_weight,
        "max_mn10_cosine_regression": args.max_mn10_cosine_regression,
        "min_selection_improvement": args.min_selection_improvement,
        "limit_train": args.limit_train,
        "limit_validation": args.limit_validation,
    }
    invalid = {key: value for key, value in nonnegative.items() if value < 0}
    if invalid:
        raise ValueError(f"training arguments must be non-negative: {invalid}")
    if not 0 <= args.warmup_fraction < 1:
        raise ValueError("warmup_fraction must be in [0, 1)")
    if not 0 < args.min_lr_ratio <= 1:
        raise ValueError("min_lr_ratio must be in (0, 1]")
    if args.eval_max_pairs <= 0:
        raise ValueError("eval_max_pairs must be positive")


def train_command(args: argparse.Namespace) -> int:
    validate_train_args(args)
    research_root = args.research_root.expanduser().resolve()
    incumbent = (
        args.incumbent
        or research_root / "models/distill/ckpts_mw/best_mw_purity_0.2422_ep4.pt"
    ).expanduser().resolve()
    if incumbent.name == "latest.pt":
        raise ValueError(
            "latest.pt is epoch 6 and is not the shipped incumbent; use "
            "best_mw_purity_0.2422_ep4.pt explicitly"
        )
    output_dir = ensure_output_inside_app(
        args.output_dir or DEFAULT_OUTPUT_ROOT / args.run_name
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    seed_everything(args.seed, deterministic=not args.allow_nondeterministic)
    device = resolve_device(args.device)
    autocast_enabled, autocast_dtype, autocast_name = resolve_cuda_amp(
        device,
        disabled=args.no_amp,
        requested_dtype=args.amp_dtype,
    )
    scaler = build_grad_scaler(
        autocast_enabled and autocast_dtype == torch.float16
    )

    manifest_path = (
        args.manifest or research_root / "data/global_music/manifest.parquet"
    ).expanduser().resolve()
    fma_tracks = (
        args.fma_tracks or research_root / "data/raw/fma_metadata/tracks.csv"
    ).expanduser().resolve()
    catalog = load_universal_catalog(
        research_root=research_root,
        manifest_path=manifest_path,
        fma_tracks_path=fma_tracks,
        seed=args.seed,
        itunes_validation_fraction=args.validation_fraction,
        itunes_test_fraction=args.test_fraction,
    )
    catalog.to_parquet(output_dir / "universal_split_manifest.parquet", index=False)
    artist_split_overrides = dict(
        catalog[catalog["artist_name_key"] != ""]
        .drop_duplicates("artist_name_key")
        .set_index("artist_name_key")["split"]
    )
    track_split_overrides = dict(
        catalog[catalog["canonical_track_key"] != ""]
        .drop_duplicates("canonical_track_key")
        .set_index("canonical_track_key")["split"]
    )
    available_train = catalog[catalog["split"] == "training"]
    available_validation = catalog[catalog["split"] == "validation"]
    selected_train = source_stratified_catalog_subset(
        catalog,
        split="training",
        limit=args.limit_train,
        seed=args.seed,
    )
    selected_validation = source_stratified_catalog_subset(
        catalog,
        split="validation",
        limit=args.limit_validation,
        seed=args.seed,
    )
    base_train = [
        AudioItem(str(row.path), str(row.track_id), str(row.source))
        for row in selected_train.itertuples(index=False)
    ]
    base_validation = [
        AudioItem(str(row.path), str(row.track_id), str(row.source))
        for row in selected_validation.itertuples(index=False)
    ]
    if not base_train or not base_validation:
        raise ValueError(
            f"empty public split: training={len(base_train)}, validation={len(base_validation)}"
        )

    train_pairs: list[AudioItem] = []
    validation_pairs: list[AudioItem] = []
    validation_mpd_catalog: list[AudioItem] = []
    split_audit: dict[str, Any] | None = None
    if not args.no_mpd:
        mpd_events = (
            args.mpd_events or research_root / "data/mpd/mpd_events.parquet"
        ).expanduser().resolve()
        mpd_store = (
            args.mpd_store or research_root / "models/embed/mpd_mnv4_distilled.parquet"
        ).expanduser().resolve()
        mpd_split_frame = prepare_mpd_store(
            store_path=mpd_store,
            seed=args.seed,
            validation_fraction=args.validation_fraction,
            test_fraction=args.test_fraction,
            artist_split_overrides=artist_split_overrides,
            track_split_overrides=track_split_overrides,
        )
        split_audit = audit_cross_source_splits(catalog, mpd_split_frame)
        mpd_split_frame[
            [
                "track_id",
                "path",
                "artist",
                "title",
                "artist_group",
                "canonical_track_key",
                "artist_split",
                "track_split",
            ]
        ].to_parquet(output_dir / "mpd_split_manifest.parquet", index=False)
        write_json(output_dir / "public_split_audit.json", split_audit)
        train_pairs = load_mpd_pairs(
            events_path=mpd_events,
            store_path=mpd_store,
            research_root=research_root,
            split="training",
            seed=args.seed,
            validation_fraction=args.validation_fraction,
            test_fraction=args.test_fraction,
            max_pairs=args.max_mpd_train_pairs,
            artist_split_overrides=artist_split_overrides,
            track_split_overrides=track_split_overrides,
        )
        validation_pairs = load_mpd_pairs(
            events_path=mpd_events,
            store_path=mpd_store,
            research_root=research_root,
            split="validation",
            seed=args.seed,
            validation_fraction=args.validation_fraction,
            test_fraction=args.test_fraction,
            max_pairs=args.max_mpd_validation_pairs,
            artist_split_overrides=artist_split_overrides,
            track_split_overrides=track_split_overrides,
        )
        validation_mpd_catalog = load_mpd_catalog_items(
            store_path=mpd_store,
            split="validation",
            seed=args.seed,
            validation_fraction=args.validation_fraction,
            test_fraction=args.test_fraction,
            artist_split_overrides=artist_split_overrides,
            track_split_overrides=track_split_overrides,
        )
        if args.limit_mpd_validation_catalog > 0:
            validation_mpd_catalog = validation_mpd_catalog[
                : args.limit_mpd_validation_catalog
            ]
    train_items = sample_session_items(
        base_train,
        train_pairs,
        pair_fraction=args.session_pair_fraction,
        seed=args.seed,
    )
    validation_items = (
        list(base_validation) + validation_mpd_catalog + validation_pairs
    )
    preflight_paths = [
        path
        for item in (*train_items, *validation_items)
        for path in (item.anchor_path, item.positive_path)
        if path is not None
    ]
    preflight_cache = (
        args.preflight_cache
        or DEFAULT_OUTPUT_ROOT / "_cache/public_audio_preflight.parquet"
    )
    valid_audio_paths, preflight_report = preflight_audio_paths(
        preflight_paths,
        cache_path=preflight_cache,
        workers=args.preflight_workers,
    )
    train_items, dropped_train_items = filter_preflight_items(
        train_items, valid_audio_paths
    )
    validation_items, dropped_validation_items = filter_preflight_items(
        validation_items, valid_audio_paths
    )
    preflight_report.update(
        {
            "dropped_train_items": dropped_train_items,
            "dropped_validation_items": dropped_validation_items,
            "remaining_train_items": len(train_items),
            "remaining_validation_items": len(validation_items),
        }
    )
    write_json(output_dir / "audio_preflight_report.json", preflight_report)
    if not train_items or not validation_items:
        raise ValueError(
            "audio preflight left an empty split: "
            f"training={len(train_items)}, validation={len(validation_items)}"
        )

    train_dataset = DistillationDataset(train_items, seed=args.seed, training=True)
    validation_dataset = DistillationDataset(
        validation_items, seed=args.seed + 17, training=False
    )
    train_loader = build_loader(
        train_dataset,
        batch_size=args.batch_size,
        shuffle=True,
        num_workers=args.num_workers,
        seed=args.seed,
    )
    validation_loader = build_loader(
        validation_dataset,
        batch_size=args.eval_batch_size,
        shuffle=False,
        num_workers=args.num_workers,
        seed=args.seed + 1,
    )

    model = build_student(research_root, incumbent, device)
    batch_norm_module_count = len(batch_norm_modules(model))
    teachers = EfficientATTeachers(
        research_root=research_root,
        strong_name=args.strong_teacher,
        device=device,
    )
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=args.learning_rate,
        weight_decay=args.weight_decay,
    )
    steps_per_epoch = len(train_loader)
    if args.max_steps_per_epoch > 0:
        steps_per_epoch = min(steps_per_epoch, args.max_steps_per_epoch)
    total_steps = max(1, args.epochs * steps_per_epoch)
    warmup_steps = max(1, int(round(total_steps * args.warmup_fraction)))

    def lr_lambda(step: int) -> float:
        if step < warmup_steps:
            return max(1e-3, (step + 1) / warmup_steps)
        progress = (step - warmup_steps) / max(1, total_steps - warmup_steps)
        return args.min_lr_ratio + (1.0 - args.min_lr_ratio) * 0.5 * (
            1.0 + math.cos(math.pi * min(1.0, progress))
        )

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, lr_lambda)
    config = {
        **{
            key: value
            for key, value in vars(args).items()
            if key != "func" and not callable(value)
        },
        "research_root": str(research_root),
        "manifest": str(manifest_path),
        "fma_tracks": str(fma_tracks),
        "incumbent": str(incumbent),
        "incumbent_sha256": sha256_file(incumbent),
        "output_dir": str(output_dir),
        "device_resolved": str(device),
        "student_autocast": autocast_name,
        "model_version": MODEL_VERSION,
        "freeze_batch_norm_stats": args.freeze_batch_norm_stats,
        "batch_norm_modules": batch_norm_module_count,
        "train_items": len(train_items),
        "validation_items": len(validation_items),
        "train_base_items": len(base_train),
        "train_base_items_by_source": audio_item_source_counts(base_train),
        "train_base_available_items_by_source": catalog_source_counts(available_train),
        "validation_base_items": len(base_validation),
        "validation_base_items_by_source": audio_item_source_counts(base_validation),
        "validation_base_available_items_by_source": catalog_source_counts(
            available_validation
        ),
        "train_session_pairs": len(train_pairs),
        "validation_session_pairs": len(validation_pairs),
        "validation_mpd_catalog": len(validation_mpd_catalog),
        "preflight_cache": str(ensure_output_inside_app(preflight_cache)),
        "preflight_invalid_paths": preflight_report["invalid_paths"],
        "preflight_dropped_train_items": dropped_train_items,
        "preflight_dropped_validation_items": dropped_validation_items,
        "personal_data_used": False,
        "public_split_audit": split_audit,
    }
    write_json(output_dir / "config.json", config)
    print(json.dumps({key: config[key] for key in (
        "device_resolved",
        "train_items",
        "validation_items",
        "train_session_pairs",
        "validation_session_pairs",
        "strong_teacher",
    )}, indent=2), flush=True)

    start_epoch = 1
    baseline: dict[str, Any]
    best_score: float
    history: list[dict[str, Any]] = []
    if args.resume is not None:
        resume_path = args.resume.expanduser().resolve()
        payload = torch.load(resume_path, map_location=device, weights_only=False)
        model.load_state_dict(payload.get("model_state_dict", payload["model"]))
        optimizer.load_state_dict(payload["optimizer"])
        scheduler.load_state_dict(payload["scheduler"])
        if payload.get("grad_scaler"):
            scaler.load_state_dict(payload["grad_scaler"])
        start_epoch = int(payload["epoch"]) + 1
        best_score = float(payload["best_score"])
        baseline = payload["baseline"]
        if payload.get("rng_state"):
            restore_rng_state(payload["rng_state"])
        history_path = output_dir / "history.json"
        if history_path.is_file():
            history = json.loads(history_path.read_text(encoding="utf-8"))
    else:
        baseline = evaluate_validation(
            model,
            teachers,
            validation_loader,
            device=device,
            strong_relational_weight=args.strong_relational_weight,
            session_weight=args.session_weight,
            temperature=args.temperature,
            max_batches=args.eval_max_batches,
            max_pairs=args.eval_max_pairs,
            full_catalog=args.limit_mpd_validation_catalog == 0,
        )
        best_score = selection_score(baseline)
        write_json(output_dir / "incumbent_validation.json", baseline)
        torch.save(
            checkpoint_payload(
                model=model,
                optimizer=optimizer,
                scheduler=scheduler,
                epoch=0,
                best_score=best_score,
                baseline=baseline,
                validation=baseline,
                config=config,
                scaler=scaler,
                model_version=INCUMBENT_MODEL_VERSION,
            ),
            output_dir / "best.pt",
        )
        print(
            f"incumbent validation: cosine={baseline['mn10_cosine']:.6f}, "
            f"selection_score={best_score:.6f}",
            flush=True,
        )

    for epoch in range(start_epoch, args.epochs + 1):
        train_dataset.set_epoch(epoch)
        # Epoch-addressed shuffle seeds make a resumed epoch byte-for-byte
        # reproducible without relying on a DataLoader generator's hidden state.
        train_loader = build_loader(
            train_dataset,
            batch_size=args.batch_size,
            shuffle=True,
            num_workers=args.num_workers,
            seed=args.seed + epoch * 10_007,
        )
        model.train()
        if args.freeze_batch_norm_stats:
            frozen_batch_norm_modules = freeze_batch_norm_running_stats(model)
            if frozen_batch_norm_modules != batch_norm_module_count:
                raise RuntimeError(
                    "BatchNorm module count changed after model construction: "
                    f"expected={batch_norm_module_count}, "
                    f"observed={frozen_batch_norm_modules}"
                )
        epoch_values: dict[str, list[float]] = {
            "total": [],
            "mn10_cosine_loss": [],
            "strong_relational_loss": [],
            "session_info_nce": [],
        }
        started = time.perf_counter()
        for step, batch in enumerate(train_loader, start=1):
            if args.max_steps_per_epoch > 0 and step > args.max_steps_per_epoch:
                break
            waveforms = batch.waveforms.to(device, non_blocking=True)
            with torch.no_grad():
                mn10, strong = teachers(waveforms)
            with torch.autocast(
                device_type="cuda",
                dtype=autocast_dtype,
                enabled=autocast_enabled,
            ):
                student = model(waveforms)
                components = loss_components(
                    student.float(),
                    mn10,
                    strong,
                    batch,
                    strong_relational_weight=args.strong_relational_weight,
                    session_weight=args.session_weight,
                    temperature=args.temperature,
                )
            optimizer.zero_grad(set_to_none=True)
            if scaler.is_enabled():
                previous_scale = float(scaler.get_scale())
                scaler.scale(components["total"]).backward()
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(
                    model.parameters(), args.max_grad_norm
                )
                scaler.step(optimizer)
                scaler.update()
                optimizer_stepped = float(scaler.get_scale()) >= previous_scale
            else:
                components["total"].backward()
                torch.nn.utils.clip_grad_norm_(
                    model.parameters(), args.max_grad_norm
                )
                optimizer.step()
                optimizer_stepped = True
            if optimizer_stepped:
                scheduler.step()
            for key, value in components.items():
                epoch_values[key].append(float(value.detach().cpu()))
            if step % args.log_every == 0:
                print(
                    f"epoch={epoch} step={step}/{steps_per_epoch} "
                    f"loss={epoch_values['total'][-1]:.5f} "
                    f"cos={1.0 - epoch_values['mn10_cosine_loss'][-1]:.5f}",
                    flush=True,
                )

        validation_dataset.set_epoch(epoch)
        validation = evaluate_validation(
            model,
            teachers,
            validation_loader,
            device=device,
            strong_relational_weight=args.strong_relational_weight,
            session_weight=args.session_weight,
            temperature=args.temperature,
            max_batches=args.eval_max_batches,
            max_pairs=args.eval_max_pairs,
            full_catalog=args.limit_mpd_validation_catalog == 0,
        )
        score = selection_score(validation)
        guardrail = (
            validation["mn10_cosine"]
            >= baseline["mn10_cosine"] - args.max_mn10_cosine_regression
        )
        improved = guardrail and score > best_score + args.min_selection_improvement
        if improved:
            best_score = score
        record = {
            "epoch": epoch,
            "seconds": time.perf_counter() - started,
            "learning_rate": scheduler.get_last_lr()[0],
            "train": {
                key: float(np.mean(values)) if values else None
                for key, values in epoch_values.items()
            },
            "validation": validation,
            "selection_score": score,
            "mn10_guardrail_passed": guardrail,
            "selected": improved,
        }
        history.append(record)
        write_json(output_dir / "history.json", history)
        payload = checkpoint_payload(
            model=model,
            optimizer=optimizer,
            scheduler=scheduler,
            epoch=epoch,
            best_score=best_score,
            baseline=baseline,
            validation=validation,
            config=config,
            scaler=scaler,
        )
        torch.save(payload, output_dir / "latest.pt")
        if improved:
            torch.save(payload, output_dir / "best.pt")
        print(
            f"epoch={epoch} val_cosine={validation['mn10_cosine']:.6f} "
            f"score={score:.6f} selected={improved} guardrail={guardrail}",
            flush=True,
        )

    candidate_selected = any(bool(record.get("selected")) for record in history)
    final_report = {
        "model_version": (
            MODEL_VERSION if candidate_selected else INCUMBENT_MODEL_VERSION
        ),
        "baseline": baseline,
        "best_score": best_score,
        "epochs_completed": max(0, args.epochs - start_epoch + 1),
        "best_checkpoint": str(output_dir / "best.pt"),
        "latest_checkpoint": str(output_dir / "latest.pt"),
        "candidate_selected": candidate_selected,
        "personal_data_used": False,
        "sources": (
            ["FMA", "iTunes previews"]
            if args.no_mpd
            else ["FMA", "iTunes previews", "Million Playlist Dataset"]
        ),
        "app_contract_preserved": True,
    }
    write_json(output_dir / "report.json", final_report)
    print(json.dumps(final_report, indent=2), flush=True)
    return 0


def _checkpoint_state(payload: dict[str, Any]) -> dict[str, Tensor]:
    return payload.get("model_state_dict", payload.get("model", payload))


def load_candidate_model(
    research_root: Path,
    checkpoint: Path,
    device: torch.device,
) -> tuple[nn.Module, str]:
    student_class, _ = import_student_class(research_root)
    model = student_class(project_dim=EMBEDDING_DIM, pretrained_backbone=False)
    payload = torch.load(checkpoint, map_location="cpu", weights_only=False)
    state = _checkpoint_state(payload)
    if any(key.startswith("backbone.") or key.startswith("mel.") for key in state):
        remapped: dict[str, Tensor] = {}
        for key, value in state.items():
            if key.startswith(("backbone.", "mel.")):
                remapped[f"encoder.{key}"] = value
            else:
                remapped[key] = value
        state = remapped
    missing, unexpected = model.load_state_dict(state, strict=False)
    missing = [key for key in missing if not key.endswith("num_batches_tracked")]
    if missing or unexpected:
        raise ValueError(f"checkpoint mismatch: missing={missing}, unexpected={unexpected}")
    version = str(payload.get("model_version", INCUMBENT_MODEL_VERSION))
    return model.to(device).eval(), version


class TrackWindowsDataset(Dataset[dict[str, Any]]):
    def __init__(self, frame: pd.DataFrame, *, windows: int, policy: str):
        self.frame = frame.reset_index(drop=True)
        self.windows = windows
        self.policy = policy

    def __len__(self) -> int:
        return len(self.frame)

    def __getitem__(self, index: int) -> dict[str, Any]:
        row = self.frame.iloc[index]
        waveforms = decode_audio(
            str(row["path"]),
            rng=None,
            windows=self.windows,
            policy=self.policy,
        )
        return {
            "index": index,
            "waveforms": torch.from_numpy(waveforms),
        }


def collate_track_windows(rows: Sequence[dict[str, Any]]) -> tuple[Tensor, list[int]]:
    return (
        torch.stack([row["waveforms"] for row in rows]),
        [int(row["index"]) for row in rows],
    )


def prepare_embedding_manifest(
    manifest_path: Path,
    *,
    research_root: Path,
    source: str | None,
    split: str | None,
) -> pd.DataFrame:
    frame = pd.read_parquet(manifest_path).reset_index(drop=True)
    if source is not None and "source" in frame.columns:
        frame = frame[frame["source"].astype(str).str.casefold() == source.casefold()]
    if split is not None and "split" in frame.columns:
        frame = frame[frame["split"].astype(str) == split]
    if "path" not in frame.columns:
        if "local_path" not in frame.columns:
            raise ValueError("embedding manifest needs path or local_path")
        frame["path"] = frame.apply(
            lambda row: str(_manifest_audio_path(row, manifest_path.parent)), axis=1
        )
    frame["path"] = frame["path"].map(lambda value: str(Path(value).expanduser().resolve()))
    exists = frame["path"].map(lambda value: Path(value).is_file())
    missing = int((~exists).sum())
    if missing:
        raise FileNotFoundError(f"{missing} embedding-manifest audio files are missing")
    if "track_id" not in frame.columns:
        frame["track_id"] = frame.index.astype(str)
    frame["track_id"] = frame["track_id"].astype(str)
    if frame["track_id"].duplicated().any():
        raise ValueError("embedding manifest has duplicate track_id values")
    return frame.reset_index(drop=True)


@torch.inference_mode()
def embed_manifest_command(args: argparse.Namespace) -> int:
    research_root = args.research_root.expanduser().resolve()
    checkpoint = args.checkpoint.expanduser().resolve()
    output = ensure_output_inside_app(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    device = resolve_device(args.device)
    seed_everything(args.seed)
    model, model_version = load_candidate_model(research_root, checkpoint, device)
    frame = prepare_embedding_manifest(
        args.manifest.expanduser().resolve(),
        research_root=research_root,
        source=args.source,
        split=args.split,
    )
    if args.limit > 0:
        frame = frame.head(args.limit).reset_index(drop=True)
    dataset = TrackWindowsDataset(frame, windows=args.windows, policy=args.window_policy)
    loader = DataLoader(
        dataset,
        batch_size=args.batch_size,
        shuffle=False,
        num_workers=args.num_workers,
        collate_fn=collate_track_windows,
        worker_init_fn=worker_seed,
    )
    embeddings = np.empty((len(frame), EMBEDDING_DIM), dtype=np.float32)
    for batch_index, (waveforms, indices) in enumerate(loader, start=1):
        batch, windows, samples = waveforms.shape
        if samples != WINDOW_SAMPLES:
            raise RuntimeError("embedding loader broke the fixed waveform contract")
        encoded = model(waveforms.reshape(batch * windows, samples).to(device))
        encoded = encoded.reshape(batch, windows, EMBEDDING_DIM).mean(dim=1)
        encoded = F.normalize(encoded, dim=-1).cpu().numpy().astype(np.float32)
        embeddings[np.asarray(indices)] = encoded
        if batch_index % args.log_every == 0:
            print(f"embedded {min(len(frame), batch_index * args.batch_size)}/{len(frame)}")

    aliases = {
        "title": ("title", "track_name"),
        "artist": ("artist", "artist_name"),
        "album": ("album", "collection_name"),
        "genre": ("genre", "primary_genre", "bucket"),
        "year": ("year", "release_date"),
    }
    result = pd.DataFrame(
        {
            "track_id": frame["track_id"].astype(str),
            "path": frame["path"].astype(str),
        }
    )
    for output_name, candidates in aliases.items():
        column = next((name for name in candidates if name in frame.columns), None)
        result[output_name] = frame[column] if column else None
    result["model_version"] = model_version
    result["embedding_dim"] = EMBEDDING_DIM
    result["embedding"] = list(embeddings)
    if output.suffix == ".npz":
        np.savez_compressed(
            output,
            track_id=result["track_id"].to_numpy(dtype=str),
            embedding=embeddings,
            model_version=np.asarray([model_version]),
        )
    else:
        result.to_parquet(output, index=False)
    write_json(
        output.with_suffix(output.suffix + ".report.json"),
        {
            "checkpoint": str(checkpoint),
            "checkpoint_sha256": sha256_file(checkpoint),
            "model_version": model_version,
            "tracks": len(frame),
            "embedding_dim": EMBEDDING_DIM,
            "windows": args.windows,
            "window_policy": args.window_policy,
            "output": str(output),
        },
    )
    return 0


def load_embedding_store(path: Path) -> tuple[pd.DataFrame, np.ndarray]:
    frame = pd.read_parquet(path).reset_index(drop=True)
    required = {"track_id", "embedding"}
    missing = sorted(required.difference(frame.columns))
    if missing:
        raise ValueError(f"{path} is missing columns: {missing}")
    frame["track_id"] = frame["track_id"].astype(str)
    if frame["track_id"].duplicated().any():
        raise ValueError(f"{path} contains duplicate track IDs")
    matrix = np.stack(
        [np.asarray(value, dtype=np.float32) for value in frame["embedding"]],
        axis=0,
    )
    if matrix.shape[1] != EMBEDDING_DIM:
        raise ValueError(f"{path} has embedding shape {matrix.shape}, expected (*, 960)")
    if not np.isfinite(matrix).all():
        raise ValueError(f"{path} contains non-finite embeddings")
    matrix /= np.maximum(np.linalg.norm(matrix, axis=1, keepdims=True), 1e-12)
    return frame, matrix


def fma_retrieval_metrics(
    ids: Sequence[str],
    embeddings: np.ndarray,
    tracks_path: Path,
    *,
    split: str,
    k: int = 10,
) -> dict[str, Any]:
    tracks = pd.read_csv(tracks_path, header=[0, 1], index_col=0, low_memory=False)
    metadata = pd.DataFrame(
        {
            "fma_id": tracks.index.astype(int),
            "split": tracks[("set", "split")].astype(str),
            "genre": tracks[("track", "genre_top")].astype(str),
        }
    )
    metadata = metadata[metadata["split"] == split].set_index("fma_id")
    selected: list[int] = []
    labels: list[str] = []
    for index, raw in enumerate(ids):
        try:
            fma_id = _fma_track_number(raw)
        except ValueError:
            continue
        if fma_id in metadata.index:
            selected.append(index)
            labels.append(str(metadata.loc[fma_id, "genre"]))
    if len(selected) <= k:
        raise ValueError(f"not enough FMA {split} tracks for retrieval: {len(selected)}")
    matrix = embeddings[np.asarray(selected)]
    similarity = matrix @ matrix.T
    np.fill_diagonal(similarity, -np.inf)
    neighbors = np.argpartition(-similarity, kth=k - 1, axis=1)[:, :k]
    label_array = np.asarray(labels)
    matches = label_array[neighbors] == label_array[:, None]
    return {
        "tracks": len(selected),
        "genre_purity_at_10": float(matches.mean()),
        "same_genre_recall_at_10": float(matches.any(axis=1).mean()),
    }


def mpd_retrieval_metrics(
    ids: Sequence[str],
    embeddings: np.ndarray,
    events_path: Path,
    *,
    artist_by_id: dict[str, str],
    title_by_id: dict[str, str],
    artist_split_overrides: dict[str, str],
    track_split_overrides: dict[str, str],
    split: str,
    seed: int,
    validation_fraction: float,
    test_fraction: float,
    max_pairs: int,
    block_size: int,
) -> dict[str, Any]:
    id_to_index = {str(track_id): index for index, track_id in enumerate(ids)}
    normalized_artist = {
        str(track_id): normalize_identity(artist)
        for track_id, artist in artist_by_id.items()
        if _clean_text(artist)
    }
    artist_groups = pd.Series(sorted(set(normalized_artist.values())), dtype=str)
    artist_group_splits = grouped_split_series(
        artist_groups,
        seed=seed,
        validation_fraction=validation_fraction,
        test_fraction=test_fraction,
    )
    artist_split = dict(zip(artist_groups, artist_group_splits, strict=True))
    artist_split_by_id = {
        track_id: artist_split[artist]
        for track_id, artist in normalized_artist.items()
    }
    for track_id, artist in normalized_artist.items():
        inherited = artist_split_overrides.get(artist)
        if inherited is not None:
            artist_split_by_id[track_id] = inherited
    track_key_by_id = {
        str(track_id): canonical_track_key(artist_by_id.get(str(track_id)), title)
        for track_id, title in title_by_id.items()
    }
    track_split_by_id = dict(artist_split_by_id)
    conflicting_ids: set[str] = set()
    for track_id, track_key in track_key_by_id.items():
        inherited = track_split_overrides.get(track_key)
        if inherited is None:
            continue
        if inherited != artist_split_by_id.get(track_id):
            conflicting_ids.add(track_id)
        else:
            track_split_by_id[track_id] = inherited
    eligible_ids = [
        track_id
        for track_id in map(str, ids)
        if track_id not in conflicting_ids
        and artist_split_by_id.get(track_id) == split
        and track_split_by_id.get(track_id) == split
    ]
    if not eligible_ids:
        raise ValueError(f"no MPD {split} tracks remain in the strict catalog")
    eligible_indices = np.asarray([id_to_index[track_id] for track_id in eligible_ids])
    eligible_embeddings = embeddings[eligible_indices]
    events = pd.read_parquet(
        events_path, columns=["session_id", "ts_unix_ms", "track_id"]
    )
    events["session_id"] = events["session_id"].astype(str)
    events["track_id"] = events["track_id"].astype(str)
    events["session_split"] = grouped_split_series(
        events["session_id"],
        seed=seed,
        validation_fraction=validation_fraction,
        test_fraction=test_fraction,
    )
    events = events.sort_values(["session_id", "ts_unix_ms"], kind="stable")
    events["positive_id"] = events.groupby("session_id", sort=False)["track_id"].shift(-1)
    pairs = events.dropna(subset=["positive_id"]).copy()
    pairs["anchor_artist_split"] = pairs["track_id"].map(artist_split_by_id)
    pairs["positive_artist_split"] = pairs["positive_id"].map(artist_split_by_id)
    pairs = pairs[
        pairs["track_id"].isin(id_to_index) & pairs["positive_id"].isin(id_to_index)
    ]
    pairs = pairs[pairs["track_id"] != pairs["positive_id"]]
    pairs = pairs[
        (pairs["session_split"] == split)
        & (pairs["anchor_artist_split"] == split)
        & (pairs["positive_artist_split"] == split)
        & pairs["track_id"].isin(eligible_ids)
        & pairs["positive_id"].isin(eligible_ids)
    ]
    if max_pairs > 0 and len(pairs) > max_pairs:
        rng = np.random.default_rng(seed + 8189)
        pairs = pairs.iloc[np.sort(rng.choice(len(pairs), max_pairs, replace=False))]
    if pairs.empty:
        raise ValueError(f"no MPD {split} pairs align to the embedding store")

    query_indices = np.asarray([id_to_index[value] for value in pairs["track_id"]])
    eligible_index_by_id = {
        track_id: index for index, track_id in enumerate(eligible_ids)
    }
    target_indices = np.asarray(
        [eligible_index_by_id[value] for value in pairs["positive_id"]]
    )
    ranks = np.empty(len(pairs), dtype=np.float64)
    candidate_ids = np.asarray(eligible_ids, dtype=str)
    for start in range(0, len(pairs), block_size):
        stop = min(len(pairs), start + block_size)
        scores = embeddings[query_indices[start:stop]] @ eligible_embeddings.T
        target_scores = scores[np.arange(stop - start), target_indices[start:stop]]
        for local, row in enumerate(range(start, stop)):
            eligible = candidate_ids != str(pairs.iloc[row]["track_id"])
            greater = eligible & (scores[local] > target_scores[local])
            tied = eligible & (scores[local] == target_scores[local])
            tied_others = max(0, int(tied.sum()) - 1)
            ranks[row] = 1.0 + float(greater.sum()) + 0.5 * tied_others
    return {
        **rank_metrics(ranks),
        "eligible_catalog_tracks": len(eligible_ids),
    }


def load_public_split_overrides(
    manifest_path: Path,
) -> tuple[dict[str, str], dict[str, str]]:
    frame = pd.read_parquet(
        manifest_path,
        columns=["artist_name_key", "canonical_track_key", "split"],
    )
    if not set(frame["split"]).issubset(SPLITS):
        raise ValueError("public split manifest contains unknown split labels")
    for column in ("artist_name_key", "canonical_track_key"):
        conflicts = (
            frame[frame[column].astype(str) != ""]
            .groupby(column)["split"]
            .nunique()
        )
        if (conflicts > 1).any():
            raise ValueError(f"public split manifest leaks {column} across splits")
    artists = dict(
        frame[frame["artist_name_key"].astype(str) != ""]
        .drop_duplicates("artist_name_key")
        .set_index("artist_name_key")["split"]
    )
    tracks = dict(
        frame[frame["canonical_track_key"].astype(str) != ""]
        .drop_duplicates("canonical_track_key")
        .set_index("canonical_track_key")["split"]
    )
    return artists, tracks


def evaluate_command(args: argparse.Namespace) -> int:
    candidate_frame, candidate_matrix = load_embedding_store(
        args.candidate_store.expanduser().resolve()
    )
    incumbent_frame, incumbent_matrix = load_embedding_store(
        args.incumbent_store.expanduser().resolve()
    )
    shared = sorted(
        set(candidate_frame["track_id"]).intersection(incumbent_frame["track_id"])
    )
    if not shared:
        raise ValueError("candidate and incumbent stores have no shared track IDs")
    candidate_by_id = {
        track_id: candidate_matrix[index]
        for index, track_id in enumerate(candidate_frame["track_id"])
    }
    incumbent_by_id = {
        track_id: incumbent_matrix[index]
        for index, track_id in enumerate(incumbent_frame["track_id"])
    }
    candidate = np.stack([candidate_by_id[track_id] for track_id in shared])
    incumbent = np.stack([incumbent_by_id[track_id] for track_id in shared])
    report: dict[str, Any] = {
        "shared_tracks": len(shared),
        "candidate_store": str(args.candidate_store),
        "incumbent_store": str(args.incumbent_store),
        "personal_data_used": False,
    }
    if args.fma_tracks is not None:
        fma_path = args.fma_tracks.expanduser().resolve()
        candidate_fma = fma_retrieval_metrics(
            shared, candidate, fma_path, split=args.fma_split
        )
        incumbent_fma = fma_retrieval_metrics(
            shared, incumbent, fma_path, split=args.fma_split
        )
        report["fma"] = {
            "candidate": candidate_fma,
            "incumbent": incumbent_fma,
            "genre_purity_at_10_delta": (
                candidate_fma["genre_purity_at_10"]
                - incumbent_fma["genre_purity_at_10"]
            ),
        }
    if args.mpd_events is not None:
        events = args.mpd_events.expanduser().resolve()
        if args.public_split_manifest is None:
            raise ValueError(
                "MPD evaluation requires --public-split-manifest from the "
                "training run to enforce FMA/iTunes/MPD cross-source holdouts"
            )
        artist_split_overrides, track_split_overrides = (
            load_public_split_overrides(
                args.public_split_manifest.expanduser().resolve()
            )
        )
        artist_column = next(
            (
                column
                for column in ("artist", "artist_name")
                if column in candidate_frame.columns
            ),
            None,
        )
        if artist_column is None:
            raise ValueError(
                "MPD evaluation requires an artist or artist_name column so the "
                "holdout can be artist-disjoint"
            )
        title_column = next(
            (
                column
                for column in ("title", "track_name")
                if column in candidate_frame.columns
            ),
            None,
        )
        if title_column is None:
            raise ValueError(
                "MPD evaluation requires a title or track_name column so exact "
                "cross-source track duplicates can be excluded"
            )
        candidate_artists = dict(
            zip(
                candidate_frame["track_id"].astype(str),
                candidate_frame[artist_column].map(_clean_text),
                strict=True,
            )
        )
        shared_artist_by_id = {
            track_id: candidate_artists[track_id]
            for track_id in shared
            if _clean_text(candidate_artists.get(track_id))
        }
        candidate_titles = dict(
            zip(
                candidate_frame["track_id"].astype(str),
                candidate_frame[title_column].map(_clean_text),
                strict=True,
            )
        )
        shared_title_by_id = {
            track_id: candidate_titles[track_id]
            for track_id in shared
            if _clean_text(candidate_titles.get(track_id))
        }
        candidate_mpd = mpd_retrieval_metrics(
            shared,
            candidate,
            events,
            artist_by_id=shared_artist_by_id,
            title_by_id=shared_title_by_id,
            artist_split_overrides=artist_split_overrides,
            track_split_overrides=track_split_overrides,
            split=args.mpd_split,
            seed=args.seed,
            validation_fraction=args.validation_fraction,
            test_fraction=args.test_fraction,
            max_pairs=args.max_pairs,
            block_size=args.block_size,
        )
        incumbent_mpd = mpd_retrieval_metrics(
            shared,
            incumbent,
            events,
            artist_by_id=shared_artist_by_id,
            title_by_id=shared_title_by_id,
            artist_split_overrides=artist_split_overrides,
            track_split_overrides=track_split_overrides,
            split=args.mpd_split,
            seed=args.seed,
            validation_fraction=args.validation_fraction,
            test_fraction=args.test_fraction,
            max_pairs=args.max_pairs,
            block_size=args.block_size,
        )
        report["mpd"] = {
            "candidate": candidate_mpd,
            "incumbent": incumbent_mpd,
            "mrr_delta": candidate_mpd["mrr"] - incumbent_mpd["mrr"],
            "recall_at_10_delta": (
                candidate_mpd["recall_at_10"] - incumbent_mpd["recall_at_10"]
            ),
        }
    output = ensure_output_inside_app(args.output)
    write_json(output, report)
    print(json.dumps(report, indent=2), flush=True)
    return 0


def fp16_without_zero_underflow(values: np.ndarray) -> np.ndarray:
    """Cast to FP16 without turning a non-zero numerical floor into exact zero."""

    converted = values.astype(np.float16)
    underflowed = (values != 0) & (converted == 0)
    if np.any(underflowed):
        # 1e-7 rounds to the same positive FP16 subnormal used by the incumbent
        # graph's mel and L2 floors. Exact zero before Log or Div is not a small
        # approximation error: it produces -Inf/NaN for sparse real-world audio.
        floor = np.float16(1e-7)
        converted = np.where(
            underflowed,
            np.copysign(floor, values),
            converted,
        ).astype(np.float16)
    return converted


def convert_onnx_initializers_to_fp16(model: Any) -> Any:
    """Convert internal float tensors to FP16 while retaining float32 app I/O."""

    import onnx
    from onnx import TensorProto, numpy_helper

    for index, initializer in enumerate(model.graph.initializer):
        if initializer.data_type == TensorProto.FLOAT:
            values = fp16_without_zero_underflow(
                numpy_helper.to_array(initializer)
            )
            model.graph.initializer[index].CopyFrom(
                numpy_helper.from_array(values, initializer.name)
            )
    for node in model.graph.node:
        for attribute in node.attribute:
            if (
                attribute.type == onnx.AttributeProto.TENSOR
                and attribute.t.data_type == TensorProto.FLOAT
            ):
                values = fp16_without_zero_underflow(
                    numpy_helper.to_array(attribute.t)
                )
                attribute.t.CopyFrom(
                    numpy_helper.from_array(values, attribute.t.name)
                )
    original_input = model.graph.input[0].name
    internal_input = original_input + "_fp16"
    for node in model.graph.node:
        for index, name in enumerate(node.input):
            if name == original_input:
                node.input[index] = internal_input
    cast_input = onnx.helper.make_node(
        "Cast",
        [original_input],
        [internal_input],
        name="cast_waveform_to_fp16",
        to=TensorProto.FLOAT16,
    )
    model.graph.node.insert(0, cast_input)

    original_output = model.graph.output[0].name
    internal_output = original_output + "_fp16"
    producer_found = False
    for node in model.graph.node:
        for index, name in enumerate(node.output):
            if name == original_output:
                node.output[index] = internal_output
                producer_found = True
    if not producer_found:
        raise ValueError(f"could not find ONNX producer for output {original_output}")
    model.graph.node.append(
        onnx.helper.make_node(
            "Cast",
            [internal_output],
            [original_output],
            name="cast_embedding_to_fp32",
            to=TensorProto.FLOAT,
        )
    )
    return model


@torch.inference_mode()
def export_command(args: argparse.Namespace) -> int:
    import onnx

    research_root = args.research_root.expanduser().resolve()
    checkpoint = args.checkpoint.expanduser().resolve()
    output = ensure_output_inside_app(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    model, version = load_candidate_model(
        research_root, checkpoint, torch.device("cpu")
    )
    dummy = torch.randn(1, WINDOW_SAMPLES, dtype=torch.float32)
    expected = model(dummy).cpu().numpy()
    temporary = output.with_suffix(".fp32.onnx") if args.fp16 else output
    torch.onnx.export(
        model,
        dummy,
        str(temporary),
        input_names=["waveform"],
        output_names=["embedding"],
        opset_version=args.opset,
        do_constant_folding=True,
        dynamic_axes=None,
        dynamo=False,
    )
    if args.fp16:
        graph = onnx.load(str(temporary))
        graph = convert_onnx_initializers_to_fp16(graph)
        graph.graph.output[0].CopyFrom(
            onnx.helper.make_tensor_value_info(
                "embedding",
                onnx.TensorProto.FLOAT,
                [1, EMBEDDING_DIM],
            )
        )
        onnx.checker.check_model(graph)
        onnx.save(graph, str(output), save_as_external_data=False)
        temporary.unlink()
    graph = onnx.load(str(output))
    onnx.checker.check_model(graph)
    input_shape = [
        dim.dim_value for dim in graph.graph.input[0].type.tensor_type.shape.dim
    ]
    output_shape = [
        dim.dim_value for dim in graph.graph.output[0].type.tensor_type.shape.dim
    ]
    if (
        graph.graph.input[0].name != "waveform"
        or graph.graph.output[0].name != "embedding"
        or input_shape != [1, WINDOW_SAMPLES]
        or output_shape != [1, EMBEDDING_DIM]
    ):
        raise RuntimeError("exported ONNX does not preserve the app contract")

    validation: dict[str, Any] = {}
    try:
        import onnxruntime as ort

        session = ort.InferenceSession(
            str(output), providers=["CPUExecutionProvider"]
        )
        actual = session.run(["embedding"], {"waveform": dummy.numpy()})[0]
        cosine = float(
            np.dot(expected.ravel(), actual.ravel())
            / max(1e-12, np.linalg.norm(expected) * np.linalg.norm(actual))
        )
        validation = {
            "pytorch_onnx_cosine": cosine,
            "max_absolute_error": float(np.max(np.abs(expected - actual))),
            "output_norm": float(np.linalg.norm(actual)),
        }
        minimum = 0.999 if args.fp16 else 0.99999
        if cosine < minimum:
            raise RuntimeError(
                f"ONNX parity failed: cosine={cosine:.8f} < {minimum}"
            )
    except ImportError:
        validation = {"onnxruntime_available": False}
    version_file = output.with_name("embedding_version.txt")
    version_file.write_text(version + "\n", encoding="utf-8")
    report = {
        "checkpoint": str(checkpoint),
        "checkpoint_sha256": sha256_file(checkpoint),
        "output": str(output),
        "output_sha256": sha256_file(output),
        "size_bytes": output.stat().st_size,
        "fp16_internal": args.fp16,
        "model_version": version,
        "app_contract": {
            "waveform": [1, WINDOW_SAMPLES],
            "embedding": [1, EMBEDDING_DIM],
            "io_dtype": "float32",
        },
        "validation": validation,
    }
    write_json(output.with_suffix(".report.json"), report)
    print(json.dumps(report, indent=2), flush=True)
    return 0


def add_research_root(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--research-root", type=Path, required=True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    train = subparsers.add_parser("train", help="Retrain the actual MNv4 encoder")
    add_research_root(train)
    train.add_argument("--run-name", default="retrieval-distill-v1")
    train.add_argument("--output-dir", type=Path)
    train.add_argument("--manifest", type=Path)
    train.add_argument("--fma-tracks", type=Path)
    train.add_argument("--incumbent", type=Path)
    train.add_argument("--mpd-events", type=Path)
    train.add_argument("--mpd-store", type=Path)
    train.add_argument("--no-mpd", action="store_true")
    train.add_argument("--strong-teacher", choices=("none", "mn20", "dymn20"), default="dymn20")
    train.add_argument("--epochs", type=int, default=3)
    train.add_argument("--batch-size", type=int, default=16)
    train.add_argument("--eval-batch-size", type=int, default=16)
    train.add_argument("--learning-rate", type=float, default=3e-5)
    train.add_argument("--weight-decay", type=float, default=1e-4)
    train.add_argument("--strong-relational-weight", type=float, default=0.10)
    train.add_argument("--session-weight", type=float, default=0.05)
    train.add_argument("--session-pair-fraction", type=float, default=0.25)
    train.add_argument("--temperature", type=float, default=0.07)
    train.add_argument("--validation-fraction", type=float, default=0.10)
    train.add_argument("--test-fraction", type=float, default=0.10)
    train.add_argument("--max-mpd-train-pairs", type=int, default=50_000)
    train.add_argument("--max-mpd-validation-pairs", type=int, default=4_096)
    train.add_argument(
        "--limit-mpd-validation-catalog",
        type=int,
        default=0,
        help="Smoke-test only; 0 keeps the full strict validation catalog.",
    )
    train.add_argument("--eval-max-pairs", type=int, default=2_048)
    train.add_argument("--eval-max-batches", type=int, default=0)
    train.add_argument("--max-steps-per-epoch", type=int, default=0)
    train.add_argument("--limit-train", type=int, default=0)
    train.add_argument("--limit-validation", type=int, default=0)
    train.add_argument("--warmup-fraction", type=float, default=0.05)
    train.add_argument("--min-lr-ratio", type=float, default=0.05)
    train.add_argument("--max-grad-norm", type=float, default=1.0)
    train.add_argument("--max-mn10-cosine-regression", type=float, default=0.01)
    train.add_argument("--min-selection-improvement", type=float, default=1e-5)
    train.add_argument("--device", default="auto")
    train.add_argument("--num-workers", type=int, default=0)
    train.add_argument("--preflight-workers", type=int, default=1)
    train.add_argument("--preflight-cache", type=Path)
    train.add_argument("--seed", type=int, default=42)
    train.add_argument("--resume", type=Path)
    train.add_argument("--no-amp", action="store_true")
    train.add_argument(
        "--freeze-batch-norm-stats",
        action=argparse.BooleanOptionalAction,
        default=True,
        help=(
            "Keep pretrained BatchNorm running statistics fixed while fine-tuning "
            "its affine parameters (default: enabled)."
        ),
    )
    train.add_argument(
        "--amp-dtype",
        choices=("auto", "bf16", "fp16"),
        default="auto",
        help="CUDA autocast type; FP16 automatically enables GradScaler.",
    )
    train.add_argument("--allow-nondeterministic", action="store_true")
    train.add_argument("--log-every", type=int, default=25)
    train.set_defaults(func=train_command)

    embed = subparsers.add_parser(
        "embed-manifest", help="Batch-embed center/contiguous windows into a standard store"
    )
    add_research_root(embed)
    embed.add_argument("--checkpoint", type=Path, required=True)
    embed.add_argument("--manifest", type=Path, required=True)
    embed.add_argument("--output", type=Path, required=True)
    embed.add_argument("--source")
    embed.add_argument("--split")
    embed.add_argument("--windows", type=int, default=3)
    embed.add_argument("--limit", type=int, default=0)
    embed.add_argument(
        "--window-policy", choices=("center", "contiguous", "uniform"), default="uniform"
    )
    embed.add_argument("--batch-size", type=int, default=4)
    embed.add_argument("--num-workers", type=int, default=0)
    embed.add_argument("--device", default="auto")
    embed.add_argument("--seed", type=int, default=42)
    embed.add_argument("--log-every", type=int, default=25)
    embed.set_defaults(func=embed_manifest_command)

    evaluate = subparsers.add_parser(
        "evaluate", help="Compare candidate/incumbent stores on untouched public splits"
    )
    evaluate.add_argument("--candidate-store", type=Path, required=True)
    evaluate.add_argument("--incumbent-store", type=Path, required=True)
    evaluate.add_argument("--fma-tracks", type=Path)
    evaluate.add_argument("--fma-split", choices=SPLITS, default="test")
    evaluate.add_argument("--mpd-events", type=Path)
    evaluate.add_argument(
        "--public-split-manifest",
        type=Path,
        help="universal_split_manifest.parquet emitted by train; required with MPD",
    )
    evaluate.add_argument("--mpd-split", choices=SPLITS, default="test")
    evaluate.add_argument("--output", type=Path, required=True)
    evaluate.add_argument("--validation-fraction", type=float, default=0.10)
    evaluate.add_argument("--test-fraction", type=float, default=0.10)
    evaluate.add_argument("--max-pairs", type=int, default=10_000)
    evaluate.add_argument("--block-size", type=int, default=256)
    evaluate.add_argument("--seed", type=int, default=42)
    evaluate.set_defaults(func=evaluate_command)

    export = subparsers.add_parser(
        "export", help="Export a fixed-shape app-contract ONNX model"
    )
    add_research_root(export)
    export.add_argument("--checkpoint", type=Path, required=True)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--opset", type=int, default=17)
    export.add_argument("--fp16", action=argparse.BooleanOptionalAction, default=True)
    export.set_defaults(func=export_command)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
