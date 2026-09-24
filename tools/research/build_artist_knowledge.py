#!/usr/bin/env python3
"""Build ml/artist_knowledge.bin: one product-quantized teacher descriptor per entity of the entity index.

Run it on the SAME popularity-ranked entity corpus (and --limit) as pack_music_entities.py, so record i
describes entity id i of the index that ships beside it:

1. Read the entity corpus (JSONL rows with at least "name").
2. Describe each entity with the teacher: DeepSeek-V4.1-Flash, prompted with the name alone. Grounding
   the prompt with facts measured worse. Descriptors are cached by normalized name and resumable, and
   only --teacher calls the API (key in LLM_KEY).
3. Embed descriptors with MiniLM (tools/research/minilm; 384-d, mean-pooled, L2-normalized).
4. Fit product quantization on the pack itself and encode: 16 sub-spaces x 256 centroids by default. On
   the full 350k-entity build PQ-16 beat PQ-8 on the listener's library (+1.1 to +1.5 pp P@10) and
   matched it on the MPD libraries, for 21 B per entity instead of 13. (On a 2.9k-artist prototype the
   two measured equal; the codebooks fit a small pack much better.)
5. Write the layout ArtistKnowledgePack.kt reads (version 2). With --attributes the teacher also fills
   structured attributes (languages, country, decades, genres, energy, confidence). The record's
   confidence byte is then the teacher's own confidence, so entries below 0.5 read as absent; its two
   language bytes index LANGUAGES, and its decade byte holds the middle of the teacher's active
   decades (see decade_byte). train_artist_adapter.py reads the same attribute cache. Entities
   without a descriptor get confidence 0 and read as absent.

    python3 tools/research/build_artist_knowledge.py entities.jsonl.gz out/artist_knowledge.bin \\
        --cache out/artist_descriptors.json --attributes out/artist_attributes.json --limit 250000 [--teacher]
"""
import argparse
import asyncio
import gzip
import json
import os
import struct
import time
from pathlib import Path

import numpy as np

from pack_music_entities import fnv1a64, normalize

# The descriptor space is MiniLM's. The app ships a student distilled into that space; the teacher
# itself stays here for building the pack, the adapter and the student.
MINILM = Path(__file__).resolve().parent / "minilm"
SYSTEM = ("You describe music artists for a music recommender. Reply with ONE line of 12 to 25 words covering: "
          "main styles, typical mood and energy, active era, cultural scene or country, and the kind of listener "
          "or moment they suit. Do not repeat the artist name. No quotes, no preamble. If you do not know the "
          "artist, say only what the name itself makes likely and stay generic rather than inventing facts.")
K, DIM, CONFIDENT = 256, 384, 200
ATTRIBUTES_SYSTEM = (
    "You annotate music artists for a recommender. Answer in json with exactly these keys: "
    "\"languages\": list of ISO 639-1 codes of the languages they mainly sing in, or [\"instrumental\"]; "
    "\"country\": ISO 3166-1 alpha-2 code of the country the artist comes from, or null; "
    "\"decades\": list of decades they were most active, like [1970, 1980]; "
    "\"genres\": up to 3 short lowercase genre names; "
    "\"energy\": integer 1 (very calm) to 5 (very intense) for their typical music; "
    "\"confidence\": number 0 to 1, how sure you are that you know this specific artist. "
    "If you do not know the artist, guess only from the name and set confidence below 0.3.")
# Language bytes index this list (0 = unknown); append only, never reorder.
LANGUAGES = ["en", "ru", "ro", "uk", "ja", "ko", "zh", "es", "pt", "fr", "de", "it", "tr", "pl", "ar", "hi",
             "kk", "be", "sr", "hr", "bg", "el", "he", "fa", "nl", "sv", "fi", "no", "da", "cs", "hu", "id",
             "th", "vi", "tl", "ka", "hy", "az", "uz", "la", "instrumental"]


def decade_byte(teacher: dict) -> int:
    """The middle of the teacher's active decades, the later one of an even count, as 1 + (decade - 1000) / 10.

    0 means unknown. The later middle is the rule the 2026-09-24 measurement used: appended to the metadata
    string of a track without a year, it raised P@10 on the MPD libraries by 0.3-2.7 pp."""
    decades = sorted(int(d) - int(d) % 10 for d in teacher.get("decades") or []
                     if isinstance(d, (int, float)) and not isinstance(d, bool) and 1000 <= d < 3550)
    return (decades[len(decades) // 2] - 1000) // 10 + 1 if decades else 0


def pack_records(names: list[str], described: list[int], codes: np.ndarray, attributes: dict) -> np.ndarray:
    """One record per entity: M code bytes, a u16 name fingerprint, two language bytes, a decade byte and a
    confidence byte."""
    M = codes.shape[1]
    records = np.zeros((len(names), M + 6), np.uint8)
    for row, entity in enumerate(described):
        records[entity, :M] = codes[row]
        records[entity, M:M + 2] = np.frombuffer(struct.pack("<H", fnv1a64(normalize(names[entity])) & 0xFFFF), np.uint8)
        teacher = attributes.get(normalize(names[entity])) or {}
        for slot, code in enumerate((teacher.get("languages") or [])[:2]):
            records[entity, M + 2 + slot] = LANGUAGES.index(code) + 1 if code in LANGUAGES else 0
        records[entity, M + 4] = decade_byte(teacher)
        confidence = teacher.get("confidence")
        records[entity, M + 5] = CONFIDENT if not isinstance(confidence, (int, float)) else int(round(255 * min(max(confidence, 0.0), 1.0)))
    return records


def write_pack(path: Path, books: np.ndarray, records: np.ndarray) -> None:
    M = books.shape[0]
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as handle:
        handle.write(struct.pack("<8sIHBHI", b"LJKNOW1\0", 2, DIM, M, K, len(records)))
        handle.write(books.astype(np.float16).tobytes())
        handle.write(records.tobytes())


def read_entities(path: Path, limit: int | None) -> list[str]:
    opener = gzip.open if path.suffix == ".gz" else open
    names = []
    with opener(path, "rt", encoding="utf-8") as handle:
        for index, line in enumerate(handle):
            if limit is not None and index >= limit:
                break
            names.append(json.loads(line)["name"])
    return names


async def describe(names: list[str], cache: dict, cache_path: Path, concurrency: int = 48,
                   attributes: bool = False) -> None:
    import aiohttp
    key = os.environ["LLM_KEY"]
    todo = sorted({normalize(n): n for n in names if normalize(n) and normalize(n) not in cache}.items())
    semaphore = asyncio.Semaphore(concurrency)

    async def one(session, normalized, name):
        body = {"model": "deepseek-flash", "temperature": 0.2, "max_tokens": 80, "thinking": {"type": "disabled"},
                "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": f"Artist: {name}"}]}
        if attributes:
            body.update(temperature=0.1, max_tokens=160, response_format={"type": "json_object"},
                        messages=[{"role": "system", "content": ATTRIBUTES_SYSTEM},
                                  {"role": "user", "content": f"Artist: {name}"}])
        async with semaphore:
            for attempt in range(4):
                try:
                    async with session.post("https://api.deepseek.com/v1/chat/completions", json=body,
                                            headers={"Authorization": f"Bearer {key}"},
                                            timeout=aiohttp.ClientTimeout(total=120)) as response:
                        text = (await response.json())["choices"][0]["message"]["content"].strip()
                        if attributes:
                            cache[normalized] = json.loads(text)
                            return
                        lines = [t.strip().strip('"') for t in text.splitlines() if t.strip()]
                        cache[normalized] = lines[0] if lines else ""
                        return
                except Exception:
                    await asyncio.sleep(2 + 3 * attempt)

    started = time.time()
    async with aiohttp.ClientSession() as session:
        tasks = [asyncio.create_task(one(session, n, name)) for n, name in todo]
        for done, task in enumerate(asyncio.as_completed(tasks), 1):
            await task
            if done % 2000 == 0:
                cache_path.write_text(json.dumps(cache, ensure_ascii=False))
                print(f"described {done}/{len(todo)} in {time.time() - started:.0f}s", flush=True)
    cache_path.write_text(json.dumps(cache, ensure_ascii=False))


def embed(texts: list[str], batch: int = 64) -> np.ndarray:
    import onnxruntime as ort
    from tokenizers import BertWordPieceTokenizer
    options = ort.SessionOptions(); options.log_severity_level = 3
    session = ort.InferenceSession(str(MINILM / "text_encoder_minilm.onnx"), options, providers=["CPUExecutionProvider"])
    tokenizer = BertWordPieceTokenizer(str(MINILM / "text_vocab.txt"), lowercase=True)
    inputs = {i.name for i in session.get_inputs()}
    out = np.zeros((len(texts), DIM), np.float32)
    for start in range(0, len(texts), batch):
        encoded = [tokenizer.encode(t).ids for t in texts[start:start + batch]]
        width = max(len(e) for e in encoded)
        ids = np.zeros((len(encoded), width), np.int64); mask = np.zeros_like(ids)
        for row, tokens in enumerate(encoded):
            ids[row, :len(tokens)] = tokens; mask[row, :len(tokens)] = 1
        feeds = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in inputs:
            feeds["token_type_ids"] = np.zeros_like(ids)
        hidden = session.run(None, feeds)[0]
        pooled = (hidden * mask[..., None]).sum(1) / np.clip(mask.sum(1, keepdims=True), 1, None)
        out[start:start + len(encoded)] = pooled / np.linalg.norm(pooled, axis=1, keepdims=True)
    return out


def kmeans(x: np.ndarray, k: int, iterations: int = 25, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    centroids = x[rng.choice(len(x), size=min(k, len(x)), replace=False)].copy()
    for _ in range(iterations):
        labels = ((x ** 2).sum(1)[:, None] - 2 * x @ centroids.T + (centroids ** 2).sum(1)[None]).argmin(1)
        for c in range(len(centroids)):
            members = labels == c
            centroids[c] = x[members].mean(0) if members.any() else x[rng.integers(len(x))]
    if len(centroids) < k:  # a tiny pack still writes K centroids; the extras are never referenced
        centroids = np.concatenate([centroids, np.repeat(centroids[:1], k - len(centroids), 0)])
    return centroids


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("entities", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--cache", type=Path, required=True, help="descriptor cache, normalized name -> text")
    parser.add_argument("--limit", type=int, help="the same popularity-ranked cap as the entity index")
    parser.add_argument("--teacher", action="store_true", help="describe uncached entities through the API")
    parser.add_argument("--subspaces", type=int, default=16, choices=(8, 16, 32), help="PQ sub-spaces (bytes per code)")
    parser.add_argument("--attributes", type=Path, help="attribute cache, normalized name -> teacher JSON")
    parser.add_argument("--concurrency", type=int, default=48, help="parallel teacher requests")
    args = parser.parse_args()

    names = read_entities(args.entities, args.limit)
    cache = json.loads(args.cache.read_text()) if args.cache.exists() else {}
    attributes = json.loads(args.attributes.read_text()) if args.attributes and args.attributes.exists() else {}
    if args.teacher:
        asyncio.run(describe(names, cache, args.cache, args.concurrency))
        if args.attributes:
            asyncio.run(describe(names, attributes, args.attributes, args.concurrency, attributes=True))
    described = [i for i, n in enumerate(names) if cache.get(normalize(n))]
    print(f"{len(described)} of {len(names)} entities have a descriptor", flush=True)

    vectors = embed([cache[normalize(names[i])] for i in described])
    M = args.subspaces
    width = DIM // M
    books = np.stack([kmeans(vectors[:, j * width:(j + 1) * width], K) for j in range(M)])
    codes = np.stack([((vectors[:, j * width:(j + 1) * width, None] - books[j].T[None]) ** 2).sum(1).argmin(1)
                      for j in range(M)], 1).astype(np.uint8)

    write_pack(args.output, books, pack_records(names, described, codes, attributes))

    decoded = np.concatenate([books[j][codes[:, j]] for j in range(M)], 1)
    decoded /= np.linalg.norm(decoded, axis=1, keepdims=True)
    fidelity = (decoded * vectors).sum(1)
    print(json.dumps({"entities": len(names), "described": len(described), "bytes": args.output.stat().st_size,
                      "pq_cos_median": round(float(np.median(fidelity)), 4)}))


if __name__ == "__main__":
    main()
