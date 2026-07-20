#!/usr/bin/env python3
"""Pack MusicBrainz aliases and group relations into a compact hash index.

No artist strings or special cases are embedded. Both a search query and the
artists present in a user's library are resolved to anonymous entity ids; search
matches the intersection. Hash collisions merely behave like an ambiguous name.
"""

from __future__ import annotations

import argparse
import gzip
import json
import struct
from collections import defaultdict
from pathlib import Path


MAGIC = b"LJENT1\0\0"


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
                mapping[fnv1a64(key)].update(targets)

    ordered = sorted(mapping.items())
    value_count = sum(len(values) for _, values in ordered)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as handle:
        handle.write(struct.pack("<8sIII", MAGIC, len(ordered), value_count, len(entities)))
        value_offset = 0
        flattened = []
        for hashed, values in ordered:
            sorted_values = sorted(values)
            if len(sorted_values) > 0xFFFF:
                raise ValueError(f"Entity hash {hashed} is too ambiguous")
            handle.write(struct.pack("<QIHH", hashed, value_offset, len(sorted_values), 0))
            flattened.extend(sorted_values)
            value_offset += len(sorted_values)
        handle.write(struct.pack(f"<{len(flattened)}I", *flattened))

    print(json.dumps({
        "entities": len(entities),
        "keys": len(ordered),
        "values": value_count,
        "bytes": args.output.stat().st_size,
    }))


if __name__ == "__main__":
    main()
