#!/usr/bin/env python3
"""Recompute frozen embeddings and private library labels with the shipped encoder.

All stores, manifests, labels and failure details stay below semantic-head-clean,
never in a source repository. The two FMA stores can share freshly computed rows
only when encoder, extraction protocol and source file fingerprints agree.

Example:
    /path/to/research/.venv/bin/python tools/research/embed_with_shipped_encoder.py \
        --workers 4 --ort-threads 4
"""
from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
import hashlib
import json
import math
import multiprocessing
import os
from pathlib import Path
import random
import re
import subprocess
import time
import unicodedata
from typing import Any, Iterator

import numpy as np
import pandas as pd

SAMPLE_RATE = 32_000
WINDOW_SAMPLES = 320_000
WINDOW_MS = 10_000
EMBEDDING_DIM = 960
MODEL_VERSION = "mnv4-960-retrieval-distill-v1"
PROTOCOL_VERSION = "android-ms-three-windows-gain-retry-v2"
PRIVATE_ROOT = Path.home() / "Documents/LJ/semantic-head-clean"
BROAD_GENRES = ("Electronic", "Experimental", "Folk", "Hip-Hop", "Instrumental", "International", "Pop", "Rock")
AUDIO_SUFFIXES = {".mp3", ".m4a", ".mp4", ".aac", ".flac", ".ogg", ".opus", ".wav", ".aif", ".aiff", ".wma", ".alac"}

# Exact tag aliases; unlisted tags are unknown. Estrada is mapped to Pop.
# Chanson/shanson, jazz, R&B and soul remain unknown because the eight broad
# classes do not provide an unambiguous home for these tags.
GENRE_ALIASES = {
    "Hip-Hop": ["hip hop", "hiphop", "rap", "trap", "phonk", "gangsta rap", "gangster rap", "russian rap", "русский рэп", "рэп", "хип хоп", "drill", "boom bap", "cloud rap", "conscious hip hop", "alternative hip hop", "underground hip hop", "east coast rap", "west coast rap", "southern rap", "hardcore hip hop", "political hip hop", "rap & hip hop", "rap and hip hop"],
    "Rock": ["rock", "alternative rock", "alternative", "indie rock", "hard rock", "classic rock", "progressive rock", "psychedelic rock", "garage rock", "post rock", "art rock", "southern rock", "punk", "punk rock", "post punk", "pop punk", "hardcore punk", "grunge", "post grunge", "metal", "heavy metal", "alternative metal", "nu metal", "thrash metal", "death metal", "black metal", "power metal", "progressive metal", "symphonic metal", "doom metal", "gothic metal", "folk metal", "metalcore", "deathcore", "industrial rock", "industrial metal", "русский рок", "рок", "рок н ролл", "rock and roll", "rock & roll", "rock n roll", "shoegaze", "emo"],
    "Pop": ["pop", "europop", "euro pop", "k pop", "kpop", "j pop", "jpop", "c pop", "dance pop", "synth pop", "synthpop", "electropop", "electro pop", "indie pop", "art pop", "dream pop", "chamber pop", "teen pop", "baroque pop", "traditional pop", "russian pop", "russischer pop", "русская поп музыка", "русская поп", "русский поп", "поп", "поп музыка", "эстрада", "estrada", "estradă", "pop rock", "poprock"],
    "Electronic": ["electronic", "electronica", "electro", "house", "deep house", "tech house", "progressive house", "electro house", "future house", "acid house", "tropical house", "techno", "minimal techno", "acid techno", "trance", "progressive trance", "psytrance", "psy trance", "goa trance", "edm", "dance", "eurodance", "euro dance", "drum and bass", "drum & bass", "drum n bass", "dnb", "d&b", "jungle", "dubstep", "brostep", "future bass", "breakbeat", "breaks", "big beat", "garage", "uk garage", "hardstyle", "hardcore techno", "gabber", "idm", "downtempo", "trip hop", "synthwave", "retrowave", "vaporwave", "chillwave", "chillout", "chill out", "disco", "nu disco", "italo disco", "ebm", "electro swing", "электронная", "электронная музыка", "танцевальная"],
    "Instrumental": ["instrumental", "classical", "orchestral", "orchestra", "soundtrack", "soundtracks", "score", "film score", "movie score", "original score", "original soundtrack", "game soundtrack", "video game music", "ambient instrumental", "neoclassical", "neo classical", "piano", "solo piano", "chamber music", "symphony", "baroque", "классика", "классическая", "классическая музыка", "инструментальная", "саундтрек"],
    "Folk": ["folk", "contemporary folk", "traditional folk", "folk rock", "indie folk", "singer songwriter", "singer & songwriter", "singer and songwriter", "bard", "acoustic folk", "americana", "country", "country folk", "bluegrass", "celtic folk", "бард", "барды", "бардовская", "авторская песня", "фолк"],
    "International": ["international", "world", "world music", "latin", "latin music", "latin pop", "reggae", "roots reggae", "dancehall", "ska", "dub", "afrobeat", "afrobeats", "afro pop", "afropop", "bossa nova", "samba", "salsa", "merengue", "bachata", "cumbia", "tango", "reggaeton", "flamenco", "fado", "klezmer", "traditional", "traditional music", "regional", "regional traditional", "balkan", "balkan folk", "romanian folk", "moldovan folk", "russian folk", "irish folk", "scottish folk", "indian classical", "hindustani", "carnatic", "qawwali", "arabic", "arabic folk", "turkish folk", "persian traditional", "african", "african traditional", "народная", "народная музыка", "румынская народная", "молдавская народная", "регги"],
    "Experimental": ["experimental", "avant garde", "avantgarde", "noise", "harsh noise", "noise music", "musique concrete", "electroacoustic", "sound art", "free improvisation", "экспериментальная", "авангард"],
}
GENRE_ALIASES["Instrumental"] += ["anime ost", "game ost", "epic orchestral", "tv score"]
GENRE_ALIASES["Rock"] += ["russian rock", "soft rock", "arena rock"]
GENRE_ALIASES["Electronic"] += ["euro disco"]
GENRE_ALIASES["Hip-Hop"] += ["brazilian phonk"]


def normalize_tag(value: str) -> str:
    value = unicodedata.normalize("NFKD", str(value).casefold())
    value = "".join(char for char in value if not unicodedata.combining(char))
    return " ".join(re.sub(r"[-_–—]+", " ", value).split())


GENRE_MAPPING = {normalize_tag(alias): genre for genre, aliases in GENRE_ALIASES.items() for alias in aliases}


def split_genre_tags(values: list[str]) -> list[str]:
    return sorted({part.strip() for value in values for part in re.split(r"[;/,]", str(value)) if part.strip()})


def map_genres(tags: list[str]) -> tuple[str | None, str]:
    if not tags:
        return None, "no_genre_tags"
    mapped = {GENRE_MAPPING.get(normalize_tag(tag)) for tag in tags}
    if None in mapped:
        return None, "unmapped_tag"
    if len(mapped) != 1:
        return None, "multiple_broad_genres"
    return next(iter(mapped)), ""


def permissive_license(value: Any) -> bool:
    text = str(value).casefold()
    return bool(re.search(r"attribution|public domain|cc0|zero|free art", text)) and not bool(re.search(r"noncommercial|no[ -]?deriv|no derivative", text))


def license_family(value: Any) -> str:
    text = re.sub(r"[ -]+", "", str(value).casefold())
    if not permissive_license(value):
        return "restricted_or_unknown"
    if "cc0" in text or "zero" in text:
        return "CC0"
    if "publicdomain" in text:
        return "Public Domain"
    if "sharealike" in text:
        return "CC BY-SA"
    if "attribution" in text:
        return "CC BY"
    return "Free Art"


def window_starts_ms(duration_ms: int | None) -> list[int]:
    if duration_ms is None or duration_ms <= WINDOW_MS:
        return [0]
    return [int((duration_ms - WINDOW_MS) * fraction) for fraction in (0.2, 0.5, 0.8)]


def cut_windows(waveform: np.ndarray, duration_ms: int | None) -> list[np.ndarray]:
    result = []
    for start_ms in window_starts_ms(duration_ms):
        start = start_ms * SAMPLE_RATE // 1000
        available = waveform[start:start + WINDOW_SAMPLES]
        if available.size == 0:
            continue
        window = np.zeros(WINDOW_SAMPLES, dtype=np.float32)
        window[:len(available)] = available
        result.append(window)
    return result


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fingerprint(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def stable_track_id(relative_path: str) -> str:
    return hashlib.sha256(relative_path.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Source:
    track_id: str
    path: str
    relative_path: str
    duration_ms: int | None
    split: str = ""
    license_family: str = ""

    def file_fingerprint(self) -> str:
        stat = Path(self.path).stat()
        return fingerprint([self.track_id, self.relative_path, stat.st_size, stat.st_mtime_ns])


def tag_values(tags: Any, names: tuple[str, ...]) -> list[str]:
    values: list[str] = []
    if tags is None:
        return values
    # Vorbis rejects invalid key syntax even on get(); look up only present keys.
    keys = {str(key).casefold(): key for key in tags.keys()}
    visited: set[str] = set()
    for name in names:
        normalized = name.casefold()
        if normalized in visited or normalized not in keys:
            continue
        visited.add(normalized)
        value = tags.get(keys[normalized])
        if value is None:
            continue
        if name == "TCON" and hasattr(value, "genres"):
            value = value.genres
        elif hasattr(value, "text"):
            value = value.text
        if not isinstance(value, (list, tuple)):
            value = [value]
        values.extend(str(item) for item in value if str(item).strip())
    return values


def read_library_tags(path: Path) -> tuple[list[str], list[str], int | None]:
    import mutagen
    media = mutagen.File(path)
    if media is None:
        return [], [], None
    tags = media.tags
    genres = split_genre_tags(tag_values(tags, ("TCON", "genre", "GENRE", "©gen", "WM/Genre")))
    artists = tag_values(tags, ("TPE1", "TPE2", "artist", "ARTIST", "albumartist", "ALBUMARTIST", "©ART", "aART", "Author", "WM/AlbumArtist"))
    length = getattr(media.info, "length", None)
    duration = int(float(length) * 1000) if length is not None and math.isfinite(length) and length > 0 else None
    return genres, artists, duration


def artist_keys(values: list[str]) -> set[str]:
    keys = set()
    for value in values:
        for part in re.split(r"\s+(?:feat\.?|ft\.?|featuring|with|x)\s+|[;,/&]", value, flags=re.IGNORECASE):
            normalized = normalize_tag(part)
            if normalized:
                keys.add(normalized)
    return keys or {"__unknown_artist__"}


def artist_disjoint_splits(credits: list[set[str]], seed: int) -> tuple[list[str], list[str]]:
    """Keep shared credited artists connected; balance whole groups by row count."""
    parents = list(range(len(credits)))

    def root(index: int) -> int:
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index

    first: dict[str, int] = {}
    for index, artists in enumerate(credits):
        for artist in artists:
            if artist in first:
                parents[root(index)] = root(first[artist])
            else:
                first[artist] = index
    groups: dict[int, list[int]] = {}
    for index in range(len(credits)):
        groups.setdefault(root(index), []).append(index)
    group_rows = list(groups.values())
    random.Random(seed).shuffle(group_rows)
    group_rows.sort(key=len, reverse=True)
    names = ("training", "validation", "test")
    targets = np.asarray([0.8, 0.1, 0.1]) * len(credits)
    counts = np.zeros(3, dtype=np.int64)
    splits, identifiers = [""] * len(credits), [""] * len(credits)
    for rows in group_rows:
        # Assign to the split with the largest remaining absolute capacity.
        destination = int(np.argmax(targets - counts))
        counts[destination] += len(rows)
        identifier = fingerprint(sorted(set().union(*(credits[row] for row in rows))))
        for row in rows:
            splits[row], identifiers[row] = names[destination], identifier
    return splits, identifiers


def library_sources(root: Path, seed: int) -> tuple[list[Source], pd.DataFrame]:
    paths = sorted(path for path in root.rglob("*") if path.is_file() and path.suffix.casefold() in AUDIO_SUFFIXES)
    sources, rows, credits = [], [], []
    for path in paths:
        relative_path = path.relative_to(root).as_posix()
        track_id = stable_track_id(relative_path)
        try:
            tags, artists, duration_ms = read_library_tags(path)
            broad_genre, exclusion_reason = map_genres(tags)
        except Exception:
            tags, artists, duration_ms = [], [], None
            broad_genre, exclusion_reason = None, "unreadable_tags"
        sources.append(Source(track_id, str(path), relative_path, duration_ms))
        credits.append(artist_keys(artists))
        rows.append({"track_id": track_id, "relative_path": relative_path, "genre_tags": tags, "broad_genre": broad_genre, "eligible": broad_genre is not None, "exclusion_reason": exclusion_reason, "child_labels_known": False})
    splits, groups = artist_disjoint_splits(credits, seed)
    labels = pd.DataFrame(rows)
    labels["split"], labels["artist_group"] = splits, groups
    return sources, labels


def fma_sources(args: argparse.Namespace, dataset: str) -> list[Source]:
    tracks = pd.read_csv(args.tracks, header=[0, 1], index_col=0)
    if dataset == "fma_small_all":
        selected = tracks[tracks[("set", "subset")].eq("small")]
    else:
        selected = tracks[tracks[("set", "subset")].isin(["small", "medium"]) & tracks[("track", "genre_top")].isin(BROAD_GENRES) & tracks[("track", "license")].map(permissive_license)]
        if any(selected[("track", "license")].map(license_family).eq("Free Art")):
            raise ValueError("Clean definition permits only CC BY, CC BY-SA, CC0 and Public Domain")
    sources = []
    for track_id, row in selected.sort_index().iterrows():
        relative = f"{int(track_id):06d}"[:3] + f"/{int(track_id):06d}.mp3"
        path = args.fma_small / relative
        if not path.is_file():
            path = args.fma_medium / relative
        if not path.is_file():
            raise FileNotFoundError(f"FMA source missing for numeric id {int(track_id)}")
        sources.append(Source(str(int(track_id)), str(path), relative, None, str(row[("set", "split")]), license_family(row[("track", "license")])))
    return sources


def decode_source(task: tuple[Source, str, str]) -> tuple[str, list[np.ndarray] | None, np.ndarray | None, str | None]:
    source, ffmpeg, ffprobe = task
    try:
        duration_ms = source.duration_ms
        if duration_ms is None:
            try:
                _, _, duration_ms = read_library_tags(Path(source.path))
            except Exception:
                pass
        if duration_ms is None:
            probe = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", source.path], capture_output=True, timeout=60)
            if probe.returncode == 0:
                seconds = float(probe.stdout.decode().strip())
                duration_ms = int(seconds * 1000) if math.isfinite(seconds) and seconds > 0 else None
        decoded = subprocess.run([ffmpeg, "-v", "error", "-nostdin", "-threads", "1", "-i", source.path, "-map", "0:a:0", "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", "pipe:1"], capture_output=True, timeout=600)
        if decoded.returncode != 0:
            raise RuntimeError(f"decoder exited with status {decoded.returncode}: " + decoded.stderr.decode(errors="replace"))
        waveform = np.frombuffer(decoded.stdout, dtype="<f4")
        if waveform.size == 0 or not np.isfinite(waveform).all():
            raise RuntimeError("decoder produced no finite audio: " + decoded.stderr.decode(errors="replace"))
        if duration_ms is None:
            duration_ms = waveform.size * 1000 // SAMPLE_RATE
        windows = cut_windows(waveform, duration_ms)
        fallback = cut_windows(waveform, 0)[0] if duration_ms > WINDOW_MS else None
        return source.track_id, windows, fallback, None
    except Exception as error:
        # The caller writes this detail only to the private failure log.
        return source.track_id, None, None, f"{type(error).__name__}: {error}"


def usable_embedding(vector: np.ndarray) -> bool:
    squared_norm = np.sum(vector * vector, dtype=np.float32)
    return vector.shape == (EMBEDDING_DIM,) and np.isfinite(vector).all() and np.isfinite(squared_norm) and squared_norm > 0


def infer_track(session: Any, windows: list[np.ndarray], fallback: np.ndarray | None) -> np.ndarray:
    pooled = np.zeros(EMBEDDING_DIM, dtype=np.float32)
    successes = 0

    def infer_window(window: np.ndarray) -> np.ndarray | None:
        for gain in (1.0, 0.5, 0.25):
            vector = np.asarray(session.run(["embedding"], {"waveform": (window * np.float32(gain)).reshape(1, WINDOW_SAMPLES)})[0][0], dtype=np.float32)
            if vector.shape != (EMBEDDING_DIM,):
                raise ValueError("Encoder output dimension differs from shipped contract")
            if usable_embedding(vector):
                return vector
        return None

    for window in windows:
        vector = infer_window(window)
        if vector is not None:
            pooled += vector
            successes += 1
    if successes == 0 and fallback is not None:
        vector = infer_window(fallback)
        if vector is not None:
            pooled += vector
            successes += 1
    if successes == 0 or not usable_embedding(pooled):
        raise ValueError("No usable window embeddings")
    # Kotlin accumulates float32 values in order; preserve that final rounding.
    squared = np.float32(0)
    for component in pooled:
        squared = np.float32(squared + np.float32(component * component))
    return pooled / np.float32(np.sqrt(squared))


def bounded_decodes(executor: ProcessPoolExecutor, sources: list[Source], args: argparse.Namespace) -> Iterator[tuple[str, list[np.ndarray] | None, np.ndarray | None, str | None]]:
    pending = []
    iterator = iter(sources)
    for _ in range(args.workers * 2):
        source = next(iterator, None)
        if source is not None:
            pending.append(executor.submit(decode_source, (source, str(args.ffmpeg), str(args.ffprobe))))
    while pending:
        future = pending.pop(0)
        yield future.result()
        source = next(iterator, None)
        if source is not None:
            pending.append(executor.submit(decode_source, (source, str(args.ffmpeg), str(args.ffprobe))))


def write_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)


def write_parquet(path: Path, frame: pd.DataFrame) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    frame.to_parquet(temporary, index=False)
    temporary.replace(path)


def extraction_identity(args: argparse.Namespace) -> dict[str, Any]:
    return {"encoder_sha256": sha256_file(args.encoder), "model_version": MODEL_VERSION, "protocol": PROTOCOL_VERSION, "input": [1, WINDOW_SAMPLES], "output": [1, EMBEDDING_DIM], "sample_rate": SAMPLE_RATE, "duration": "mutagen metadata milliseconds truncated; ffprobe fallback; decoded length last fallback", "window_starts": "int((duration_ms - 10000) * fraction) for fraction in [0.2, 0.5, 0.8]; [0] when <=10000", "pooling": "sum successful windows and sequential float32 L2 normalization", "gain_retries": [1.0, 0.5, 0.25], "providers": ["CPUExecutionProvider"], "ffmpeg_sha256": sha256_file(args.ffmpeg)}


def run_dataset(dataset: str, sources: list[Source], args: argparse.Namespace, session: Any, identity: dict[str, Any], executor: ProcessPoolExecutor) -> dict[str, Any]:
    output = args.output_dir / f"{dataset}.parquet"
    sidecar = output.with_suffix(".metadata.json")
    manifest = {source.track_id: source.file_fingerprint() for source in sources}
    expected = {"identity": identity, "dataset": dataset, "source_manifest": manifest, "seed": args.seed if dataset == "library" else None}
    rows: dict[str, dict[str, Any]] = {}
    if output.exists() or sidecar.exists():
        if not (output.exists() and sidecar.exists()):
            raise ValueError(f"Incomplete checkpoint for {dataset}; inspect private output directory")
        previous = json.loads(sidecar.read_text())
        if any(previous.get(key) != value for key, value in expected.items()):
            raise ValueError(f"Provenance mismatch for {dataset}; use a separate private output directory")
        existing = pd.read_parquet(output)
        if existing.track_id.astype(str).duplicated().any() or not set(existing.track_id.astype(str)).issubset(manifest):
            raise ValueError(f"Invalid checkpoint ids for {dataset}")
        for row in existing.to_dict("records"):
            if row["model_version"] != MODEL_VERSION or not usable_embedding(np.asarray(row["embedding"], dtype=np.float32)):
                raise ValueError(f"Invalid checkpoint embedding for {dataset}")
            rows[str(row["track_id"])] = row
    reused = 0
    if dataset == "fma_clean":
        small = args.output_dir / "fma_small_all.parquet"
        small_meta = small.with_suffix(".metadata.json")
        if small.exists() and small_meta.exists():
            previous = json.loads(small_meta.read_text())
            if previous.get("identity") == identity:
                for row in pd.read_parquet(small).to_dict("records"):
                    track_id = str(row["track_id"])
                    if track_id not in rows and previous["source_manifest"].get(track_id) == manifest.get(track_id) and track_id in manifest:
                        rows[track_id] = row
                        reused += 1
    todo = [source for source in sources if source.track_id not in rows]
    started, processed, failures = time.monotonic(), 0, 0
    counts = pd.DataFrame([{"split": source.split, "license_family": source.license_family} for source in sources])
    metadata = {**expected, "requested_rows": len(sources), "data_counts": counts.groupby(["split", "license_family"]).size().rename("count").reset_index().to_dict("records") if dataset != "library" else [], "reused_fresh_small_rows": reused}

    def checkpoint() -> None:
        ordered = [rows[source.track_id] for source in sources if source.track_id in rows]
        frame = pd.DataFrame(ordered, columns=["track_id", "embedding", "model_version"])
        write_parquet(output, frame)
        metadata.update({"completed_rows": len(rows), "failure_count": failures, "complete": len(rows) == len(sources), "elapsed_seconds_this_run": time.monotonic() - started})
        write_json(sidecar, metadata)
        elapsed = max(time.monotonic() - started, 1e-6)
        print(f"{dataset}: {len(rows)}/{len(sources)} complete; {processed / elapsed:.2f} tracks/s; {failures} failures; checkpoint saved", flush=True)

    print(f"{dataset}: {len(sources)} requested, {len(rows)} resumed/reused, {len(todo)} to compute", flush=True)
    try:
        for track_id, windows, fallback, error in bounded_decodes(executor, todo, args):
            if error is None:
                try:
                    embedding = infer_track(session, windows, fallback)
                    rows[track_id] = {"track_id": track_id, "embedding": embedding, "model_version": MODEL_VERSION}
                except Exception as failure:
                    error = f"{type(failure).__name__}: {failure}"
            if error is not None:
                failures += 1
                with (args.output_dir / f"{dataset}.failures.jsonl").open("a") as handle:
                    handle.write(json.dumps({"track_id": track_id, "error": error}) + "\n")
            processed += 1
            if processed % args.checkpoint_every == 0:
                checkpoint()
    finally:
        checkpoint()
    return metadata


def parse_args() -> argparse.Namespace:
    base = Path.home() / "Documents/LJ"
    repo = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--encoder", type=Path, default=repo / "androidApp/src/main/assets/ml/mnv4_audio.onnx")
    parser.add_argument("--tracks", type=Path, default=base / "latentjam-research/data/raw/fma_metadata/tracks.csv")
    parser.add_argument("--fma-small", type=Path, default=base / "latentjam-research/data/raw/fma_small")
    parser.add_argument("--fma-medium", type=Path, default=base / "latentjam-research/data/raw/fma_medium")
    parser.add_argument("--library-root", type=Path, help="Private source directory; required when library is selected")
    parser.add_argument("--output-dir", type=Path, default=PRIVATE_ROOT)
    parser.add_argument("--ffmpeg", type=Path, default=Path("/opt/homebrew/bin/ffmpeg"))
    parser.add_argument("--ffprobe", type=Path, default=Path("/opt/homebrew/bin/ffprobe"))
    parser.add_argument("--datasets", default="fma_small_all,fma_clean,library")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--ort-threads", type=int, default=4)
    parser.add_argument("--checkpoint-every", type=int, default=500)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--limit", type=int, help="Benchmark limit; use a separate private output directory")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.output_dir = args.output_dir.expanduser().resolve()
    if not args.output_dir.is_relative_to(PRIVATE_ROOT.resolve()):
        raise ValueError("Derived files must stay below the private semantic-head-clean directory")
    if min(args.workers, args.ort_threads, args.checkpoint_every) < 1:
        raise ValueError("Workers, ORT threads and checkpoint interval must be positive")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    datasets = args.datasets.split(",")
    if len(set(datasets)) != len(datasets) or set(datasets) - {"fma_small_all", "fma_clean", "library"}:
        raise ValueError("Datasets must be unique members of fma_small_all,fma_clean,library")
    if "library" in datasets and args.library_root is None:
        raise ValueError("--library-root is required for the library dataset")
    identity = extraction_identity(args)
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.ort_threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(args.encoder), sess_options=options, providers=["CPUExecutionProvider"])
    inputs, outputs = session.get_inputs(), session.get_outputs()
    if len(inputs) != 1 or inputs[0].name != "waveform" or inputs[0].shape != [1, WINDOW_SAMPLES] or inputs[0].type != "tensor(float)":
        raise ValueError("Encoder input differs from shipped contract")
    if len(outputs) != 1 or outputs[0].name != "embedding" or outputs[0].shape != [1, EMBEDDING_DIM] or outputs[0].type != "tensor(float)":
        raise ValueError("Encoder output differs from shipped contract")
    all_complete = True
    with ProcessPoolExecutor(max_workers=args.workers, mp_context=multiprocessing.get_context("spawn")) as executor:
        for dataset in datasets:
            if dataset == "library":
                sources, labels = library_sources(args.library_root, args.seed)
                if args.limit:
                    sources = sources[:args.limit]
                    labels = labels[labels.track_id.isin([source.track_id for source in sources])]
                write_parquet(args.output_dir / "library_labels.parquet", labels)
                summary = {"total_rows": len(labels), "eligible_rows": int(labels.eligible.sum()), "counts_by_split": labels.groupby("split").size().to_dict(), "eligible_counts": labels[labels.eligible].groupby(["split", "broad_genre"]).size().rename("count").reset_index().to_dict("records"), "exclusions": labels[~labels.eligible].groupby("exclusion_reason").size().to_dict(), "mapping": GENRE_ALIASES, "mapping_policy": "Exact normalized aliases only; any unmapped tag or multiple broad genres excludes row. Chanson, jazz, R&B and soul excluded; estrada maps to Pop.", "split_policy": "Shared artist and album-artist credits form connected groups; seeded groups assigned intact towards 80/10/10 row counts; unknown artists one group", "seed": args.seed}
                write_json(args.output_dir / "library_labels.metadata.json", summary)
                print(f"library labels: {len(labels)} rows, {int(labels.eligible.sum())} eligible; artist-disjoint split complete", flush=True)
            else:
                sources = fma_sources(args, dataset)
                if args.limit:
                    sources = sources[:args.limit]
            result = run_dataset(dataset, sources, args, session, identity, executor)
            all_complete = all_complete and result["complete"]
    return 0 if all_complete else 2


if __name__ == "__main__":
    raise SystemExit(main())
