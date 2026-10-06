"""The judge ranks the chain's own candidates at sampled hops: training data for a learned score correction.

    python rank_candidates.py --features <prepared root> --runs LIB=DIR/VARIANT.tsv [...] --out OUT [--hops 2500]

Each DIR/VARIANT.tsv comes from replay_plans.py --trace --trace-candidates. At a sampled hop the judge sees the seed,
the tracks queued last and a shuffled subset of what the chain scored there (its best by score, by style closeness to
the seed, by sound closeness to the previous track, and random ones) and names the three that fit best next, best
first. Rows of OUT/ranked.jsonl: library, key, hop, the shown candidates with the chain's base score and features,
and the judge's ranking. Public libraries only; the key comes from LLM_KEY or --key-file and is never printed;
answers are cached in OUT.
"""
import argparse
import asyncio
import hashlib
import json
import os
import random
from pathlib import Path

from judge_queues import URL

RANK = ("A listener started a radio queue from a seed track in their own library; below are the seed, the tracks "
        "queued last, and candidate tracks for the next slot, as 'artist — title'. Rank the three candidates that fit "
        "best as the next track, best first: each should fit the seed's style, language, era and mood and flow from "
        "the last track. Answer json {\"ranking\": [n, n, n], \"why\": \"one short sentence\"}.")
WIDTH = 15  # replay_plans.py candidates: id, base score, correction, 12 features
STYLE_SEED, AUDIO_PREVIOUS = 5 + 3, 0 + 3  # Rerank feature indexes, offset by id, score and correction


def name(meta, row):
    return f"{meta[row][1]} — {meta[row][0]}" if meta[row][1] else meta[row][0]


async def ask(session, sem, key, cache, user):
    import aiohttp
    body = {"model": "deepseek-flash", "thinking": {"type": "disabled"}, "temperature": 0.0, "max_tokens": 120,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": RANK}, {"role": "user", "content": user}]}
    digest = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()
    if digest in cache:
        return cache[digest]
    async with sem:
        for attempt in range(6):
            try:
                async with session.post(URL, json=body, headers={"Authorization": f"Bearer {key}"},
                                        timeout=aiohttp.ClientTimeout(total=120)) as r:
                    answer = json.loads((await r.json())["choices"][0]["message"]["content"])
                    cache[digest] = answer
                    return answer
            except Exception:
                await asyncio.sleep(3 + 3 * attempt)
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--features", type=Path, required=True, help="the prepared libraries the replays ran on")
    ap.add_argument("--runs", action="append", required=True, help="LIB=DIR/VARIANT.tsv")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--hops", type=int, default=2500, help="sampled hops per library")
    ap.add_argument("--context", type=int, default=4)
    ap.add_argument("--top", type=int, default=6, help="candidates by base score, and as many by style to the seed")
    ap.add_argument("--sound", type=int, default=4, help="candidates by sound to the previous track")
    ap.add_argument("--random", type=int, default=4)
    ap.add_argument("--min-candidates", type=int, default=5)
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--key-file", type=Path, default=Path.home() / ".deepseek_key")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    rng = random.Random(0)
    cases = []
    for spec in a.runs:
        lib, tsv = spec.split("=", 1)
        folder = next(a.features.glob(f"{lib}.*"))
        meta = [line.rstrip("\n").split("\t") for line in open(folder / "meta.tsv")]
        hops = []
        for line in open(tsv + ".trace.jsonl"):
            j = json.loads(line)
            seed = int(j["key"].split("@")[0])  # the harness names tracks by their row
            queued = []
            for p in j["picks"]:
                cands = [c for c in p.get("candidates", []) if len(c) == WIDTH]
                if len(cands) >= a.min_candidates:
                    hops.append((j["key"], seed, list(queued), p["position"], p["plan"], cands))
                queued.append(int(p["track"]))
        for key, seed, queued, position, plan, cands in rng.sample(hops, min(a.hops, len(hops))):
            by = lambda k: sorted(cands, key=lambda c: -c[k])
            shown = {c[0]: c for c in by(1)[:a.top]}
            for c in by(STYLE_SEED)[:a.top] + by(AUDIO_PREVIOUS)[:a.sound]:
                shown.setdefault(c[0], c)
            rest = [c for c in cands if c[0] not in shown]
            for c in rng.sample(rest, min(a.random, len(rest))):
                shown[c[0]] = c
            shown = list(shown.values())
            rng.shuffle(shown)
            cases.append(dict(lib=lib, key=key, seed=seed, position=position, plan=plan, queued=queued,
                              candidates=shown, names=[name(meta, int(c[0])) for c in shown],
                              seed_name=name(meta, seed), last=[name(meta, r) for r in queued[-a.context:]]))
    key = os.environ.get("LLM_KEY") or (a.key_file.read_text().strip() if a.key_file.exists() else "")
    if not key:
        raise SystemExit(f"no key: set LLM_KEY or put it in {a.key_file}")
    cache_path = a.out / "cache.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}

    async def run():
        import aiohttp
        sem = asyncio.Semaphore(a.workers)
        async with aiohttp.ClientSession() as session:
            async def one(c):
                user = (f"Seed: {c['seed_name']}\n\nQueued last:\n" + ("\n".join(c["last"]) or "(nothing yet)") +
                        "\n\nCandidates:\n" + "\n".join(f"{i + 1}. {n}" for i, n in enumerate(c["names"])))
                answer = await ask(session, sem, key, cache, user)
                if answer is not None:
                    try:
                        ranking = [int(x) - 1 for x in answer.get("ranking", [])]
                    except (TypeError, ValueError):
                        ranking = []
                    c["ranking"] = [i for i in dict.fromkeys(ranking) if 0 <= i < len(c["candidates"])][:3]
                    c["why"] = answer.get("why", "")
                return c
            return await asyncio.gather(*(one(c) for c in cases))

    try:
        done = asyncio.run(run())
    finally:
        cache_path.write_text(json.dumps(cache))
    with open(a.out / "ranked.jsonl", "w") as f:
        for c in done:
            if c.get("ranking"):
                f.write(json.dumps({k: v for k, v in c.items() if k not in ("names", "last", "seed_name")}) + "\n")
    ok = sum(bool(c.get("ranking")) for c in done)
    agree = sum(bool(c.get("ranking")) and max(range(len(c["candidates"])), key=lambda i: c["candidates"][i][1]) == c["ranking"][0]
                for c in done)
    print(f"{ok} of {len(done)} hops ranked; the judge's first choice is the chain's best by base score in "
          f"{agree / max(ok, 1) * 100:.0f} %")


if __name__ == "__main__":
    main()
