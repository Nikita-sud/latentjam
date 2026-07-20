#!/usr/bin/env python3
"""Evaluate a distilled MusicEncoder checkpoint on the manifest benchmark."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from transformers import BertConfig, BertTokenizerFast

from evaluate_music_search import clean, make_queries, metrics
from train_music_search_student import MusicEncoder


@torch.no_grad()
def encode(model, tokenizer, texts, prefix, device, batch_size, max_length):
    vectors = []
    for start in range(0, len(texts), batch_size):
        batch = [prefix + value for value in texts[start : start + batch_size]]
        tokens = tokenizer(
            batch, padding=True, truncation=True, max_length=max_length, return_tensors="pt"
        )
        tokens = {key: value.to(device) for key, value in tokens.items()}
        vectors.append(model(**tokens).cpu().numpy())
    return np.concatenate(vectors)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--vocab", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--max-length", type=int, default=48)
    args = parser.parse_args()

    frame = pd.read_parquet(args.manifest).reset_index(drop=True)
    documents = [
        "; ".join(
            part for part in (clean(row.genre), clean(row.artist), clean(row.title), clean(row.year)) if part
        )
        for row in frame.itertuples()
    ]
    queries = make_queries(frame)
    payload = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    tokenizer = BertTokenizerFast(
        vocab_file=str(args.vocab), do_lower_case=False, strip_accents=False,
        model_max_length=args.max_length,
    )
    model = MusicEncoder(BertConfig.from_dict(payload["config"]), payload["output_dim"])
    model.load_state_dict(payload["model"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device).eval()

    started = time.perf_counter()
    document_vectors = encode(
        model, tokenizer, documents, "passage: ", device, args.batch_size, args.max_length
    )
    query_vectors = encode(
        model, tokenizer, [query.text for query in queries], "query: ",
        device, args.batch_size, args.max_length,
    )
    ranking = np.argsort(-(query_vectors @ document_vectors.T), axis=1)
    result = {
        "documents": len(documents),
        "queries": len(queries),
        "dimension": int(document_vectors.shape[1]),
        "seconds": round(time.perf_counter() - started, 3),
        "metrics": metrics(ranking, queries),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
