"""Were the judge's jumps avoidable? For a flagged track, the judge picks the best next track blind from the app's
pick and other tracks not yet played; the queue with that pick swapped in is annotated again.

    python avoidable_jumps.py --features <0.7.1 features root> --runs DIR --jumps DIR/annotations/jumps.json --out OUT

A jump counts as avoidable when the swapped queue no longer flags its position. Non-jump picks go through the same
steps as a control for the judge's noise. Candidates: the app's pick, the unused tracks closest to the seed and to
the previous track on 0.7.1's rulers, and random unused tracks, shuffled. Public libraries only; the key comes from
LLM_KEY or --key-file and is never printed; answers are cached in OUT.
"""
import argparse
import asyncio
import hashlib
import json
import os
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import compare_plans as cp  # noqa: E402
from annotate_jumps import PROMPT as JUMPS  # noqa: E402
from judge_queues import URL  # noqa: E402

CHOOSE = ("A listener started a radio queue from a seed track in their own library; below are the seed, the tracks "
          "played last, and tracks from the library not played yet, as 'artist — title'. Choose the one that fits "
          "best as the next track: it fits the seed's style, language, era and mood and flows from the last track. "
          "Answer json {\"pick\": number, \"why\": \"one short sentence\"}.")
LIBS = ("r1k_a", "r1k_b", "r1k_c", "s1k_a", "s1k_b", "r3k")


def name(meta, row):
    return f"{meta[row][1]} — {meta[row][0]}" if meta[row][1] else meta[row][0]


async def call(session, sem, key, cache, system, user, max_tokens=120):
    import aiohttp
    body = {"model": "deepseek-flash", "thinking": {"type": "disabled"}, "temperature": 0.0, "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
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
    ap.add_argument("--features", type=Path, required=True)
    ap.add_argument("--runs", type=Path, required=True, help="folder with <lib>/join.tsv")
    ap.add_argument("--jumps", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--per-lib", type=int, default=60, help="jumps sampled per library (as many controls)")
    ap.add_argument("--context", type=int, default=4)
    ap.add_argument("--close", type=int, default=15, help="candidates closest to the seed, and as many to the last track")
    ap.add_argument("--random", type=int, default=20)
    ap.add_argument("--tracks", type=int, default=24)
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--key-file", type=Path, default=Path.home() / ".deepseek_key")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    jumps = json.loads(a.jumps.read_text())
    rng = random.Random(0)
    cases = []
    for lib in LIBS:
        folder = next(a.features.glob(f"{lib}.*"))
        audio, desc, meta, _ = cp.ruler(folder)
        audio, desc = np.nan_to_num(audio), np.nan_to_num(desc)
        queues = {k: v["played"][:a.tracks] for k, v in cp.read(a.runs / lib / "join.tsv").items()}
        flagged, fitting = [], []
        for k, played in queues.items():
            marks = {p - 1 for p in jumps.get(f"{folder.name}|{k}", {"jumps": []})["jumps"]}
            for i in range(len(played)):
                (flagged if i in marks else fitting).append((k, i))
        for kind, pool in (("jump", flagged), ("control", fitting)):
            for k, i in rng.sample(pool, min(a.per_lib, len(pool))):
                played, seed = queues[k], int(k.split("@")[0])
                prev, pick = (played[i - 1] if i else seed), played[i]
                unused = np.ones(len(audio), bool)
                unused[[seed, *played[:i]]] = False
                unused[pick] = False
                to_seed = np.where(unused, desc @ desc[seed] + 0.5 * (audio @ audio[seed]), -np.inf)
                to_prev = np.where(unused, 0.5 * (audio @ audio[prev] + desc @ desc[prev]), -np.inf)
                chosen = list(dict.fromkeys([*np.argsort(-to_seed)[:a.close].tolist(), *np.argsort(-to_prev)[:a.close].tolist()]))
                rest = [r for r in np.flatnonzero(unused).tolist() if r not in chosen]
                chosen += rng.sample(rest, min(a.random, len(rest)))
                candidates = [pick, *chosen]
                rng.shuffle(candidates)
                cases.append(dict(lib=lib, folder=folder.name, key=k, i=i, kind=kind, seed=seed, pick=pick,
                                  candidates=candidates, played=played, names={r: name(meta, r) for r in
                                                                              {seed, *played, *candidates}}))
    key = os.environ.get("LLM_KEY") or (a.key_file.read_text().strip() if a.key_file.exists() else "")
    if not key:
        raise SystemExit(f"no key: set LLM_KEY or put it in {a.key_file}")
    cache_path = a.out / "cache.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}

    rng_cases = random.Random(1)

    async def run():
        import aiohttp
        sem = asyncio.Semaphore(a.workers)
        async with aiohttp.ClientSession() as session:
            async def one(c):
                n = c["names"]
                last = c["played"][max(0, c["i"] - a.context):c["i"]]
                user = (f"Seed: {n[c['seed']]}\n\nPlayed last:\n" + ("\n".join(n[r] for r in last) or "(nothing yet)") +
                        "\n\nNot played yet:\n" + "\n".join(f"{j + 1}. {n[r]}" for j, r in enumerate(c["candidates"])))
                choice = await call(session, sem, key, cache, CHOOSE, user)
                try:
                    chosen = c["candidates"][int(choice["pick"]) - 1]
                except (TypeError, KeyError, ValueError, IndexError):
                    return c
                c["chosen"], c["why"] = chosen, choice.get("why", "")
                queue = list(c["played"])
                if chosen in queue:  # a later track of the same queue: the two swap places
                    queue[queue.index(chosen)] = c["pick"]
                queue[c["i"]] = chosen
                c["swapped"] = queue
                # Controls for the judge's noise: the unchanged queue asked again in other words, and the queue with a
                # random candidate swapped in instead of the judge's choice.
                others = [r for r in c["candidates"] if r not in (c["pick"], chosen)]
                placebo = list(c["played"])
                c["placebo_track"] = rng_cases.choice(others)
                if c["placebo_track"] in placebo:
                    placebo[placebo.index(c["placebo_track"])] = c["pick"]
                placebo[c["i"]] = c["placebo_track"]
                for field, q, heading in (("jumps_after", queue, "Queue:"), ("jumps_retest", c["played"], "Queue, in play order:"),
                                          ("jumps_placebo", placebo, "Queue:")):
                    if field == "jumps_after" and chosen == c["pick"]:
                        continue
                    again = await call(session, sem, key, cache, JUMPS, f"Seed: {n[c['seed']]}\n\n{heading}\n" +
                                       "\n".join(f"{j + 1}. {n[r]}" for j, r in enumerate(q)), max_tokens=160)
                    if again is not None:
                        c[field] = [int(p) for p in again.get("jumps", []) if str(p).isdigit()]
                return c
            return await asyncio.gather(*(one(c) for c in cases))

    try:
        done = asyncio.run(run())
    finally:
        cache_path.write_text(json.dumps(cache))
    for c in done:
        c["jumps_before"] = jumps[f"{c['folder']}|{c['key']}"]["jumps"]
        del c["names"]
    (a.out / "cases.json").write_text(json.dumps(done, ensure_ascii=False, indent=1))
    for kind in ("jump", "control"):
        sub = [c for c in done if c["kind"] == kind and "chosen" in c]
        kept = sum(c["chosen"] == c["pick"] for c in sub)
        full = [c for c in sub if all(f in c for f in ("jumps_after", "jumps_retest", "jumps_placebo"))]
        rate = lambda f: np.mean([c["i"] + 1 in c[f] for c in full]) * 100
        print(f"{kind:8s} {len(sub):4d} judged, app's pick chosen {kept}; {len(full)} swapped. Position flagged: "
              f"asked again unchanged {rate('jumps_retest'):.0f} %, judge's choice swapped in {rate('jumps_after'):.0f} %, "
              f"a random candidate swapped in {rate('jumps_placebo'):.0f} %")
        if kind == "jump":
            stable = [c for c in full if c["i"] + 1 in c["jumps_retest"]]
            fixed = [c for c in stable if c["i"] + 1 not in c["jumps_after"]]
            print(f"         flagged again when asked unchanged: {len(stable)}; of those, gone with the judge's choice "
                  f"{len(fixed)} ({len(fixed) / max(len(stable), 1) * 100:.0f} %)")


if __name__ == "__main__":
    main()
