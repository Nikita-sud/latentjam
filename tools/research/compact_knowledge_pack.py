"""Writes an artist knowledge pack in format version 3: only the entities the app reads.

    python tools/research/compact_knowledge_pack.py IN_V2.bin OUT.bin
        same codebooks and codes, without the records the app never decodes: answers exactly as IN does
    python tools/research/compact_knowledge_pack.py IN_V2.bin OUT.bin --subspaces 8 --build-dir DIR
        re-quantizes the confident entities' float descriptors (DIR/out/descriptor_vectors.npy, keyed as
        build_pack_v2.py keys them) with M product-quantization sub-spaces fitted on those entities only

Version 2 keeps a record for every entity of the index, but ArtistKnowledgePack only ever decodes the
confident ones (confidence >= 128) and never reads the second language byte. Version 3 (read by
core/smart ArtistKnowledgePack) keeps a presence bit per entity id, a u32 running count per 512 ids, and
for each present entity its M codes, u16 fingerprint, language and decade.
"""
import argparse
import gzip
import json
import struct
import sys
from pathlib import Path

import numpy as np

HEADER = 21
CONFIDENT = 128


def read_v2(raw):
    magic, version, dim, M, K, n = struct.unpack_from("<8sIHBHI", raw, 0)
    assert magic == b"LJKNOW1\0" and version == 2, "not a version-2 pack"
    nb = M * K * (dim // M)
    books = np.frombuffer(raw, "<f2", count=nb, offset=HEADER).reshape(M, K, dim // M)
    records = np.frombuffer(raw, np.uint8, offset=HEADER + nb * 2).reshape(n, M + 6)
    return dim, M, K, books, records


def write_v3(path, dim, books, present, codes, fingerprints, languages, decades):
    """present: bool[n]; the per-entity arrays hold one row per PRESENT entity, in id order."""
    M, K = books.shape[0], books.shape[1]
    n = len(present)
    words = -(-n // 64)
    bits = np.zeros(words * 64, bool)
    bits[:n] = present
    presence = np.packbits(bits, bitorder="little")  # id i is bit i % 8 of byte i // 8, so of u64 word i // 64
    blocks = -(-n // 512) + 1
    ranks = np.zeros(blocks, np.uint32)
    counts = np.add.reduceat(bits.astype(np.int64), np.arange(0, words * 64, 512)) if n else np.zeros(0)
    ranks[1:len(counts) + 1] = np.cumsum(counts)[:blocks - 1]
    records = np.zeros((int(present.sum()), M + 4), np.uint8)
    records[:, :M] = codes
    records[:, M:M + 2] = fingerprints.astype("<u2").view(np.uint8).reshape(-1, 2)
    records[:, M + 2] = languages
    records[:, M + 3] = decades
    with open(path, "wb") as handle:
        handle.write(struct.pack("<8sIHBHI", b"LJKNOW1\0", 3, dim, M, K, n))
        handle.write(books.astype(np.float16).tobytes())
        handle.write(presence.tobytes())
        handle.write(ranks.astype("<u4").tobytes())
        handle.write(records.tobytes())


def requantize(build_dir, present, M):
    """PQ-M codebooks and codes for the present entities' float descriptors."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build_artist_knowledge as bak
    from pack_music_entities import normalize
    names = bak.read_entities(build_dir / "entities_final.jsonl.gz", None)
    cache = json.load(open(build_dir / "out" / "descriptors_all.json"))
    described = [i for i, name in enumerate(names) if cache.get(normalize(name))]
    vectors = np.load(build_dir / "out" / "descriptor_vectors.npy")
    assert len(vectors) == len(described)
    row_of = {entity: row for row, entity in enumerate(described)}
    rows = [row_of[e] for e in np.flatnonzero(present)]
    X = vectors[rows].astype(np.float32)
    width = X.shape[1] // M
    with np.errstate(all="ignore"):
        books = np.stack([bak.kmeans(X[:, j * width:(j + 1) * width], bak.K) for j in range(M)])
        codes = np.stack([((X[:, j * width:(j + 1) * width, None] - books[j].T[None]) ** 2).sum(1).argmin(1)
                          for j in range(M)], 1).astype(np.uint8)
    decoded = np.concatenate([books[j][codes[:, j]] for j in range(M)], 1)
    cos = (decoded * X).sum(1) / np.linalg.norm(decoded, axis=1) / np.linalg.norm(X, axis=1)
    print(f"PQ-{M} on {len(X)} confident entities: decoded-to-float cos median {np.median(cos):.3f}, p10 "
          f"{np.percentile(cos, 10):.3f}")
    return books, codes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--subspaces", type=int)
    ap.add_argument("--build-dir", type=Path)
    args = ap.parse_args()
    raw = open(args.src, "rb").read()
    dim, M, K, books, records = read_v2(raw)
    present = records[:, M + 5] >= CONFIDENT
    kept = records[present]
    codes = kept[:, :M]
    if args.subspaces:
        books, codes = requantize(args.build_dir, present, args.subspaces)
    write_v3(args.dst, dim, books, present, codes, kept[:, M:M + 2].copy().view("<u2").ravel(), kept[:, M + 2],
             kept[:, M + 4])
    size = Path(args.dst).stat().st_size
    print(f"{len(records)} entities, {present.sum()} confident: {len(raw) / 1e6:.2f} MB -> {size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
