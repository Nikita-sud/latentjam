#!/usr/bin/env python3
"""Append Wikidata musical artists outside the MusicBrainz top 250k to the entity corpus.

The MusicBrainz rows behind music_entities_250k.bin stop at a popularity cut, so artists a listener
may well own fall below it (Ion Suruceanu has a MusicBrainz id, but ranks below 250,000). Wikidata
adds them back:

1. Ask QLever's Wikidata endpoint for musicians and singers (occupation subtree) and musical groups
   (class subtree) with at least one Wikipedia article, their article count and MusicBrainz id.
   (`wikibase:sitelinks` is sparse on QLever, so articles are counted through `schema:about`.)
2. Keep the ones whose MusicBrainz id is missing or not among the corpus's first 250,000 rows, rank
   them by article count, and cap them (100,000 in the 2026-09-24 build; the last kept had 2).
3. Fetch their labels from the Wikidata API (wbgetentities, 50 ids per call, one call at a time).
4. Each tail row keeps its primary label (English first) and ONE native-script alias. Cyrillic
   labels are rewritten into the form tags use: "Фамилия, Имя Отчество" -> "Имя Фамилия", and a
   three-word "Имя Отчество Фамилия" drops the patronymic. All aliases would add 8 MB to the index
   for two more artists of the test library; with no alias it would miss Виктор Логинов and
   Константин Меладзе.

Both sources are CC0. Rows follow the corpus schema; `mbid` holds "wd:Q…".

    python3 tools/research/build_wikidata_tail.py mb_entities.jsonl.gz entities.jsonl.gz --cap 100000
    python3 tools/research/pack_music_entities.py entities.jsonl.gz androidApp/src/main/assets/ml/music_entities_250k.bin
"""
import argparse
import csv
import gzip
import io
import json
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from pack_music_entities import normalize

MB_ROWS = 250_000
QLEVER = "https://qlever.dev/api/wikidata"
USER_AGENT = "LatentJamResearch/0.1 (https://github.com/Nikita-sud/latentjam; artist index build)"
ARTISTS_QUERY = """PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX wikibase: <http://wikiba.se/ontology#>
PREFIX schema: <http://schema.org/>
SELECT ?item (COUNT(DISTINCT ?a) AS ?n) (SAMPLE(?m) AS ?mb) WHERE {
  { ?item wdt:P31 wd:Q5 . ?item wdt:P106/wdt:P279* wd:Q639669 }
  UNION { ?item wdt:P31 wd:Q5 . ?item wdt:P106/wdt:P279* wd:Q177220 }
  UNION { ?item wdt:P31/wdt:P279* wd:Q215380 }
  ?a schema:about ?item . ?a schema:isPartOf ?site . ?site wikibase:wikiGroup "wikipedia" .
  OPTIONAL { ?item wdt:P434 ?m }
} GROUP BY ?item"""
# The primary label is the first present in LANGUAGES; a native alias comes from NATIVE, in order.
LANGUAGES = ["en", "mul", "ru", "ro", "uk", "be", "kk", "ja", "ko", "zh", "es", "pt", "fr", "de", "it", "pl", "tr", "cs",
             "sv", "fi", "nl", "hu", "el", "he", "ar", "fa", "hi", "sr", "hr", "bg", "ka", "hy", "az", "uz", "id", "th", "vi"]
NATIVE = ["ru", "uk", "be", "kk", "bg", "sr", "ja", "ko", "zh", "el", "ka", "hy", "he", "ar", "fa", "hi", "th"]
CYRILLIC = {"ru", "uk", "be", "kk", "bg", "sr"}
PATRONYMIC = re.compile(r"(ович|евич|ич|овна|евна|ична|инична|ївна|івна)$", re.I)


def wikidata_artists() -> list[tuple[str, int, str]]:
    data = urllib.parse.urlencode({"query": ARTISTS_QUERY}).encode()
    request = urllib.request.Request(QLEVER, data=data, headers={"Accept": "text/tab-separated-values"})
    with urllib.request.urlopen(request, timeout=900) as response:
        text = response.read().decode("utf-8")
    rows = []
    for row in list(csv.reader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE))[1:]:
        if len(row) >= 3:
            rows.append((row[0].rsplit("/", 1)[-1].rstrip(">"), int(row[1].split("^")[0].strip('"')),
                         row[2].strip('"')))
    return rows


def labels(qids: list[str]) -> dict[str, dict]:
    out = {}
    for start in range(0, len(qids), 50):
        url = "https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({
            "action": "wbgetentities", "ids": "|".join(qids[start:start + 50]), "props": "labels|aliases",
            "languages": "|".join(LANGUAGES), "format": "json", "maxlag": 5})
        for attempt in range(6):
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}),
                                            timeout=60) as response:
                    data = json.load(response)
                if "error" not in data:
                    break
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(5 + 5 * attempt)
        else:
            continue
        for qid, entity in data.get("entities", {}).items():
            out[qid] = {lang: value["value"] for lang, value in (entity.get("labels") or {}).items()}
        if start % 10_000 == 0:
            print(f"labels {len(out)}/{len(qids)}", flush=True)
    return out


def latin(text: str) -> bool:
    return all(not ch.isalpha() or "LATIN" in unicodedata.name(ch, "") for ch in text)


def native_alias(by_language: dict[str, str], name: str) -> str | None:
    for language in NATIVE:
        text = by_language.get(language)
        if not text or latin(text) or normalize(text) == normalize(name):
            continue
        if language in CYRILLIC:
            text = re.sub(r"\s*\(.*?\)\s*", " ", text).strip()
            if ", " in text:
                family, rest = text.split(", ", 1)
                given = rest.split()
                text = f"{given[0]} {family}" if given else family
            words = text.split()
            if len(words) == 3 and PATRONYMIC.search(words[1]):
                text = f"{words[0]} {words[2]}"
        return text
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("musicbrainz", type=Path, help="build_musicbrainz_entities.py output, popularity-ranked")
    parser.add_argument("output", type=Path, help="the first 250,000 MusicBrainz rows, then the Wikidata tail")
    parser.add_argument("--cap", type=int, default=100_000)
    args = parser.parse_args()

    with gzip.open(args.musicbrainz, "rt", encoding="utf-8") as handle:
        mb_rows = [json.loads(line) for _, line in zip(range(MB_ROWS), handle)]
    top = {row["mbid"] for row in mb_rows}
    items = wikidata_artists()
    tail = sorted((x for x in items if not x[2] or x[2] not in top), key=lambda x: (-x[1], int(x[0][1:])))
    print(f"{len(items)} Wikidata artists with an article; {len(items) - len(tail)} already in the "
          f"MusicBrainz rows; keeping {min(args.cap, len(tail))} of {len(tail)}", flush=True)
    tail = tail[:args.cap]
    by_item = labels([qid for qid, _, _ in tail])

    rows = []
    for qid, articles, _ in tail:
        by_language = by_item.get(qid, {})
        name = next((by_language[l] for l in LANGUAGES if by_language.get(l)), None)
        if not name:
            continue
        alias = native_alias(by_language, name)
        rows.append({"mbid": "wd:" + qid, "name": name, "sort": "", "score": articles,
                     "aliases": [alias] if alias else [], "members": [], "groups": [], "wikidata": qid})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(args.output, "wt", encoding="utf-8") as handle:
        for row in mb_rows + rows:
            json.dump(row, handle, ensure_ascii=False, separators=(",", ":"))
            handle.write("\n")
    print(json.dumps({"musicbrainz": len(mb_rows), "wikidata": len(rows),
                      "native_aliases": sum(1 for r in rows if r["aliases"])}))


if __name__ == "__main__":
    main()
