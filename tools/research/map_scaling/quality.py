#!/usr/bin/env python3
"""Compare f32 embeddings against the same input, without exposing any input rows.

Usage: quality.py --rows 1107 --dims 50 --input reduced.f32 exact.f32 bh.f32
Needs numpy and scipy. Offline only. O(n^2) time; block-bounded working memory.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from scipy.spatial.distance import cdist


def matrix(path, rows, dims):
    value = np.fromfile(path, dtype="<f4")
    if value.size != rows * dims or not np.isfinite(value).all():
        raise ValueError("Invalid matrix: " + str(path))
    return value.reshape(rows, dims)


def nearest(value, k):
    result = []
    for start in range(0, len(value), 128):
        stop = min(start + 128, len(value))
        distances = cdist(value[start:stop], value, "sqeuclidean")
        distances[np.arange(stop - start), np.arange(start, stop)] = np.inf
        result.append(np.argsort(distances, axis=1, kind="stable")[:, :k])
    return np.concatenate(result)


def compare(source, embedding, k, reference_neighbors):
    n = len(source)
    low = nearest(embedding, k)
    penalty = 0
    overlap = 0
    for start in range(0, n, 128):
        stop = min(start + 128, n)
        distances = cdist(source[start:stop], source, "sqeuclidean")
        distances[np.arange(stop - start), np.arange(start, stop)] = np.inf
        order = np.argsort(distances, axis=1, kind="stable")
        ranks = np.empty_like(order)
        np.put_along_axis(ranks, order, np.arange(1, n + 1)[None, :], axis=1)
        selected = np.take_along_axis(ranks, low[start:stop], axis=1)
        penalty += np.maximum(selected - k, 0).sum()
        overlap += (selected <= k).sum()
    return {
        "trustworthiness_at_15": float(1 - 2 * penalty / (n * k * (2 * n - 3 * k - 1))),
        "neighbor_recall_at_15": float(overlap / (n * k)),
        "neighbor_overlap_with_first_embedding_at_15": float(np.mean([
            len(set(row).intersection(ref)) / k for row, ref in zip(low, reference_neighbors)
        ])),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, required=True)
    parser.add_argument("--dims", type=int, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("embeddings", type=Path, nargs="+")
    args = parser.parse_args()
    if args.rows <= 31:
        parser.error("At least 32 rows are required for k=15 trustworthiness")
    source = matrix(args.input, args.rows, args.dims)
    reference_neighbors = nearest(matrix(args.embeddings[0], args.rows, 2), 15)
    result = {path.name: compare(source, matrix(path, args.rows, 2), 15, reference_neighbors)
              for path in args.embeddings}
    print(json.dumps({"rows": args.rows, "reference": "input matrix before 2D projection", "metrics": result}, indent=2))


if __name__ == "__main__":
    main()
