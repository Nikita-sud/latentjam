#!/usr/bin/env python3
"""Build a compact, CC0-only artist entity corpus from a MusicBrainz dump.

The output is research/training data, not a hand-authored alias list.  It contains
canonical artist names, localized aliases, and artist-to-group membership edges.
Popularity is derived from MusicBrainz artist-credit reference counts so the
result can be size-swept before deciding what (if anything) belongs in the app.
"""

from __future__ import annotations

import argparse
import gzip
import json
from collections import defaultdict
from pathlib import Path


NULL = r"\N"


def rows(path: Path):
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            yield line.rstrip("\n").split("\t")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("dump", type=Path, help="Directory containing mbdump/* tables")
    parser.add_argument("output", type=Path)
    parser.add_argument("--limit", type=int, default=250_000)
    parser.add_argument("--min-credit-refs", type=int, default=2)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    root = args.dump / "mbdump" if (args.dump / "mbdump").is_dir() else args.dump

    credit_refs: dict[int, int] = {}
    for row in rows(root / "artist_credit"):
        # id, name, artist_count, ref_count, created
        credit_refs[int(row[0])] = int(row[3])

    popularity: dict[int, int] = defaultdict(int)
    for row in rows(root / "artist_credit_name"):
        # artist_credit, position, artist, name, join_phrase
        popularity[int(row[2])] += credit_refs.get(int(row[0]), 0)
    credit_refs.clear()

    ranked = sorted(popularity.items(), key=lambda item: (-item[1], item[0]))
    selected = {
        artist_id
        for artist_id, score in ranked[: args.limit]
        if score >= args.min_credit_refs
    }

    artists: dict[int, dict] = {}
    for row in rows(root / "artist"):
        artist_id = int(row[0])
        if artist_id in selected:
            artists[artist_id] = {
                "mbid": row[1],
                "name": row[2],
                "sort": row[3],
                "score": popularity.get(artist_id, 0),
                "aliases": [],
                "members": [],
                "groups": [],
            }

    for row in rows(root / "artist_alias"):
        # id, artist, name, locale, edits_pending, last_updated, type,
        # sort_name, begin/end dates, primary_for_locale, ended
        artist_id = int(row[1])
        entity = artists.get(artist_id)
        if entity is None:
            continue
        alias = row[2].strip()
        if alias and alias.casefold() != entity["name"].casefold():
            entity["aliases"].append(alias)

    member_type_ids: set[int] = set()
    for row in rows(root / "link_type"):
        # id, parent, child_order, gid, entity_type0, entity_type1,
        # name, description, link_phrase, reverse_link_phrase, ...
        if len(row) > 6 and row[4] == "artist" and row[5] == "artist":
            name = row[6].casefold()
            if "member of band" in name:
                member_type_ids.add(int(row[0]))

    link_types: dict[int, int] = {}
    for row in rows(root / "link"):
        # id, link_type, begin/end dates, attribute_count, created, ended
        link_type = int(row[1])
        if link_type in member_type_ids:
            link_types[int(row[0])] = link_type

    edges: list[tuple[int, int]] = []
    for row in rows(root / "l_artist_artist"):
        # id, link, entity0, entity1, edits_pending, last_updated, link_order
        if int(row[1]) in link_types:
            person, group = int(row[2]), int(row[3])
            if person in artists or group in artists:
                edges.append((person, group))
                selected.add(person)
                selected.add(group)

    # Relationships can pull a lower-ranked member/group into the corpus. Load
    # their names in a second pass without retaining the full MusicBrainz table.
    missing = selected.difference(artists)
    if missing:
        for row in rows(root / "artist"):
            artist_id = int(row[0])
            if artist_id in missing:
                artists[artist_id] = {
                    "mbid": row[1],
                    "name": row[2],
                    "sort": row[3],
                    "score": popularity.get(artist_id, 0),
                    "aliases": [],
                    "members": [],
                    "groups": [],
                }

        for row in rows(root / "artist_alias"):
            artist_id = int(row[1])
            if artist_id not in missing:
                continue
            entity = artists[artist_id]
            alias = row[2].strip()
            if alias and alias.casefold() != entity["name"].casefold():
                entity["aliases"].append(alias)

    for person, group in edges:
        if person in artists and group in artists:
            artists[group]["members"].append(artists[person]["name"])
            artists[person]["groups"].append(artists[group]["name"])

    args.output.parent.mkdir(parents=True, exist_ok=True)
    opener = gzip.open if args.output.suffix == ".gz" else open
    with opener(args.output, "wt", encoding="utf-8") as handle:
        for artist_id, entity in sorted(
            artists.items(), key=lambda item: (-item[1]["score"], item[0])
        ):
            entity["aliases"] = sorted(set(entity["aliases"]), key=str.casefold)
            entity["members"] = sorted(set(entity["members"]), key=str.casefold)
            entity["groups"] = sorted(set(entity["groups"]), key=str.casefold)
            json.dump(entity, handle, ensure_ascii=False, separators=(",", ":"))
            handle.write("\n")

    print(
        json.dumps(
            {
                "artists": len(artists),
                "membership_edges": len(edges),
                "output_bytes": args.output.stat().st_size,
            }
        )
    )


if __name__ == "__main__":
    main()
