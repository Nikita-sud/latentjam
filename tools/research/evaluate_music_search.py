#!/usr/bin/env python3
"""Reproducible retrieval benchmark for LatentJam's on-device text encoder.

The benchmark is built from the existing 862-track manifest and never assigns a
label from a model prediction.  Artist/title/genre tags define positives; query
forms (natural phrasing and transliteration) are generated independently.
"""

from __future__ import annotations

import argparse
import json
import math
import time
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from sentence_transformers import SentenceTransformer
from unidecode import unidecode


@dataclass(frozen=True)
class Query:
    text: str
    positives: frozenset[int]
    kind: str


def clean(value: object) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ""
    return " ".join(str(value).split())


def normalized(value: str) -> str:
    return "".join(
        character.casefold()
        for character in unicodedata.normalize("NFKD", value)
        if character.isalnum() or character.isspace()
    ).strip()


def make_queries(frame: pd.DataFrame) -> list[Query]:
    by_artist: dict[str, set[int]] = defaultdict(set)
    by_title: dict[str, set[int]] = defaultdict(set)
    by_genre: dict[str, set[int]] = defaultdict(set)
    display: dict[str, str] = {}
    for index, row in frame.iterrows():
        for field, target in (
            ("artist", by_artist), ("title", by_title), ("genre", by_genre)
        ):
            value = clean(row[field])
            key = normalized(value)
            if value and key:
                target[key].add(index)
                display.setdefault(key, value)

    queries: list[Query] = []
    for key, positives in by_artist.items():
        if len(key) < 2:
            continue
        artist = display[key]
        queries.append(Query(artist, frozenset(positives), "artist"))
        transliterated = unidecode(artist).strip()
        if normalized(transliterated) != key and len(transliterated) >= 2:
            queries.append(Query(transliterated, frozenset(positives), "transliteration"))
        queries.extend(
            Query(template.format(artist), frozenset(positives), "natural_artist")
            for template in (
                "songs by {}",
                "music by {}",
                "песни {}",
                "музыка {}",
                "música de {}",
                "Musik von {}",
                "musique de {}",
            )
        )

    # Titles provide a large exact-name control without making every one-word
    # title a semantic benchmark.
    for key, positives in by_title.items():
        title = display[key]
        if len(key) >= 5 and len(key.split()) >= 2:
            queries.append(Query(title, frozenset(positives), "title"))
            transliterated = unidecode(title).strip()
            if normalized(transliterated) != key:
                queries.append(Query(transliterated, frozenset(positives), "transliteration"))

    for key, positives in by_genre.items():
        if len(positives) >= 2:
            queries.append(Query(display[key], frozenset(positives), "genre"))

    # Hand-written query wording, but labels still come only from manifest genres.
    concepts = {
        "русский рок": ("russian rock",),
        "Russian rock music": ("russian rock",),
        "японская поп-музыка": ("j-pop", "japanese pop"),
        "anime music": ("anime", "anime ost"),
        "музыка из аниме": ("anime", "anime ost"),
        "heavy metal": ("heavy metal", "metal"),
        "тяжёлый металл": ("heavy metal", "metal"),
        "music for sleeping": ("ambient", "dark ambient"),
        "спокойная фоновая музыка": ("ambient", "dark ambient"),
        "танцевальная электроника": ("dance", "electronic", "edm"),
    }
    for text, genre_fragments in concepts.items():
        positives = {
            index
            for index, value in enumerate(frame["genre"].map(clean))
            if any(fragment in normalized(value) for fragment in genre_fragments)
        }
        if positives:
            queries.append(Query(text, frozenset(positives), "concept"))

    # Stable de-duplication keeps a query in its first (most specific) bucket.
    unique: dict[tuple[str, frozenset[int]], Query] = {}
    for query in queries:
        unique.setdefault((query.text, query.positives), query)
    return list(unique.values())


def metrics(ranking: np.ndarray, queries: list[Query]) -> dict:
    totals: dict[str, list[tuple[float, float, float]]] = defaultdict(list)
    for row, query in zip(ranking, queries):
        first = next(
            (rank for rank, candidate in enumerate(row, start=1) if candidate in query.positives),
            len(row) + 1,
        )
        value = (float(first == 1), float(first <= 5), 1.0 / first)
        totals[query.kind].append(value)
        totals["all"].append(value)
    return {
        kind: {
            "count": len(values),
            "recall@1": round(float(np.mean([v[0] for v in values])), 4),
            "recall@5": round(float(np.mean([v[1] for v in values])), 4),
            "mrr": round(float(np.mean([v[2] for v in values])), 4),
        }
        for kind, values in sorted(totals.items())
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--model", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=128)
    args = parser.parse_args()

    frame = pd.read_parquet(args.manifest).reset_index(drop=True)
    documents = [
        "; ".join(
            part
            for part in (
                clean(row.genre), clean(row.artist), clean(row.title), clean(row.year)
            )
            if part
        )
        for row in frame.itertuples()
    ]
    queries = make_queries(frame)
    results = {"documents": len(documents), "queries": len(queries), "models": {}}

    for model_name in args.model:
        model = SentenceTransformer(model_name, device="cuda" if torch.cuda.is_available() else "cpu")
        is_e5 = "e5" in model_name.casefold()
        query_texts = [("query: " + query.text) if is_e5 else query.text for query in queries]
        document_texts = [("passage: " + doc) if is_e5 else doc for doc in documents]

        started = time.perf_counter()
        document_vectors = model.encode(
            document_texts,
            batch_size=args.batch_size,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=True,
        )
        query_vectors = model.encode(
            query_texts,
            batch_size=args.batch_size,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=True,
        )
        elapsed = time.perf_counter() - started
        similarities = query_vectors @ document_vectors.T
        ranking = np.argsort(-similarities, axis=1)
        results["models"][model_name] = {
            "dimension": int(document_vectors.shape[1]),
            "seconds": round(elapsed, 3),
            "metrics": metrics(ranking, queries),
        }
        del model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
