#!/usr/bin/env python3
"""Pack MusicBrainz aliases and group relations into a compact hash index.

No artist strings or special cases are embedded. Both a search query and the
artists present in a user's library are resolved to anonymous entity ids; search
matches the intersection. Hash collisions merely behave like an ambiguous name.

Layout LJENT2 (MusicEntityIndex.kt reads it), little endian:
- magic, u32 key count, u32 value count, u32 entity count, 4 reserved bytes;
- 65,537 u32 key positions: where the keys whose 48-bit hash has each top-16-bit
  value start, then the key count;
- per key, sorted by hash: the hash's low 32 bits and a u24 offset of its first
  value (a key's values run to the next key's offset);
- u24 entity ids, sorted within each key.

A key hash is the low 48 bits of FNV-1a 64. On the 2026-09-24 corpus (350k
entities, 889,581 keys) the file is 11.9 MB against 21.5 MB for the first
layout's 64-bit hashes and 32-bit ids, with no collision at 48 bits.
"""

from __future__ import annotations

import argparse
import gzip
import json
import struct
from collections import defaultdict
from pathlib import Path


MAGIC = b"LJENT2\0\0"
HASH_BITS = 48
BUCKETS = 1 << 16


def normalize(value: str) -> str:
    result = []
    previous_space = True
    for character in value.lower():
        if character == "ё":
            character = "е"
        if character.isalnum():
            result.append(character)
            previous_space = False
        elif not previous_space:
            result.append(" ")
            previous_space = True
    return "".join(result).strip()


def keys(value: str):
    whole = normalize(value)
    if not whole:
        return
    yield whole
    tokens = whole.split()
    if len(tokens) > 1:
        for token in tokens:
            if len(token) >= 3:
                yield token


def fnv1a64(value: str) -> int:
    result = 0xCBF29CE484222325
    for byte in value.encode("utf-8"):
        result ^= byte
        result = (result * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("entities", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--limit", type=int, help="Optional popularity-ranked row cap")
    args = parser.parse_args()

    opener = gzip.open if args.entities.suffix == ".gz" else open
    entities = []
    with opener(args.entities, "rt", encoding="utf-8") as handle:
        for index, line in enumerate(handle):
            if args.limit is not None and index >= args.limit:
                break
            entities.append(json.loads(line))

    canonical_ids: dict[str, int] = {}
    mbid_ids: dict[str, int] = {}
    for entity_id, entity in enumerate(entities):
        canonical_ids.setdefault(normalize(entity["name"]), entity_id)
        mbid_ids[entity["mbid"]] = entity_id

    mapping: dict[int, set[int]] = defaultdict(set)
    for entity_id, entity in enumerate(entities):
        targets = {entity_id}
        for group in entity.get("groups", []):
            group_id = canonical_ids.get(normalize(group))
            if group_id is not None:
                targets.add(group_id)
        for name in (entity["name"], entity.get("sort", ""), *entity.get("aliases", [])):
            for key in keys(name):
                mapping[fnv1a64(key) & ((1 << HASH_BITS) - 1)].update(targets)

    ordered = sorted(mapping.items())
    value_count = sum(len(values) for _, values in ordered)
    if len(entities) > 1 << 24 or value_count > 1 << 24:
        raise ValueError("entity ids and value offsets must fit in 24 bits")
    directory = [0] * (BUCKETS + 1)
    for hashed, _ in ordered:
        directory[(hashed >> 32) + 1] += 1
    for bucket in range(BUCKETS):
        directory[bucket + 1] += directory[bucket]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as handle:
        handle.write(struct.pack("<8sIIII", MAGIC, len(ordered), value_count, len(entities), 0))
        handle.write(struct.pack(f"<{BUCKETS + 1}I", *directory))
        entries, flattened = bytearray(), []
        for hashed, values in ordered:
            entries += struct.pack("<I", hashed & 0xFFFFFFFF) + len(flattened).to_bytes(3, "little")
            flattened.extend(sorted(values))
        handle.write(entries)
        handle.write(b"".join(value.to_bytes(3, "little") for value in flattened))

    print(json.dumps({
        "entities": len(entities),
        "keys": len(ordered),
        "values": value_count,
        "bytes": args.output.stat().st_size,
    }))


if __name__ == "__main__":
    main()
