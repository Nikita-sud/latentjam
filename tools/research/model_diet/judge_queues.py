"""A blind LLM judge (DeepSeek Flash) for two sets of SMART queues on the same seeds.

    LLM_KEY=... python judge_queues.py --features <features root> --old NAME=DIR/FILE.tsv ... --out DIR

Every pair is judged twice, once in each order, and the two verdicts are averaged: judged once in a random order,
28 of 163 identical pairs came back different (smart-bench exp_debiased.py). Only public libraries belong here; the
owner's library never leaves the machine. The key comes from LLM_KEY or --key-file and is never printed. Answers are
cached in --out, so an interrupted run resumes.

Pairs: --pair LIB=OLD.tsv,NEW.tsv (repeatable); LIB names a folder under --features for titles, artists, genres and
years. --tracks sets how many leading tracks of each queue the judge sees.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

import numpy as np

JUDGE = ("You compare two automatic radio queues that a music app built after the listener picked a seed track from "
         "their own library; every track comes from that library. Judge as a listener: which queue is the better "
         "continuation of the seed — fits its style, language, era and mood, flows well from track to track, does not "
         "keep repeating one artist, and would be enjoyable to hear next. Tracks are 'artist — title [genre, year]' "
         "when known. Rate each queue 1-5 and name the better one, or 'tie' only when they are genuinely equal. "
         "Answer json {\"A\": n, \"B\": n, \"better\": \"A\"|\"B\"|\"tie\", \"why\": \"one short sentence\"}.")
# The owner's criterion (2026-10-06): tracks are what is compared; a run of one artist is fine when its tracks fit,
# consecutive tracks that sound unrelated are not.
JUDGE_FIT = ("You compare two automatic radio queues that a music app built after the listener picked a seed track "
             "from their own library; every track comes from that library. Judge as a listener: which queue is the "
             "better continuation of the seed — fits its style, language, era and mood, and flows well from track to "
             "track, so that each track sounds like it belongs after the one before it. Several tracks by one artist "
             "in a row are fine when they fit; consecutive tracks that sound unrelated are not. Tracks are "
             "'artist — title [genre, year]' when known. Rate each queue 1-5 and name the better one, or 'tie' only "
             "when they are genuinely equal. Answer json {\"A\": n, \"B\": n, \"better\": \"A\"|\"B\"|\"tie\", "
             "\"why\": \"one short sentence\"}.")
PROMPTS = {"default": JUDGE, "fit": JUDGE_FIT}
URL = "https://api.deepseek.com/chat/completions"


def label(meta, row):
    title, artist = meta[row][0], meta[row][1]
    genre = meta[row][3] if len(meta[row]) > 3 else ""
    year = meta[row][4] if len(meta[row]) > 4 else ""
    extra = ", ".join(x for x in (genre.split(";")[0].strip(), year.strip()[:4]) if x)
    text = f"{artist} — {title}" if artist else title
    return f"{text} [{extra}]" if extra else text


def read(path):
    out = {}
    for line in Path(path).read_text().splitlines():
        if line and not line.startswith("#"):
            f = line.split("\t")
            out[f[0]] = [int(x) for x in f[1].split(",") if x]
    return out


async def ask(session, sem, key, cache, seed, first, second, prompt=JUDGE):
    body = {"model": "deepseek-flash", "thinking": {"type": "disabled"}, "temperature": 0.0, "max_tokens": 160,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": prompt},
                         {"role": "user", "content": f"Seed: {seed}\n\n" + "\n\n".join(
                             f"Queue {k}:\n" + "\n".join(f"{i + 1}. {x}" for i, x in enumerate(q))
                             for k, q in (("A", first), ("B", second)))}]}
    digest = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()
    if digest in cache:
        return cache[digest]
    import aiohttp
    async with sem:
        for attempt in range(6):
            try:
                async with session.post(URL, json=body, headers={"Authorization": f"Bearer {key}"},
                                        timeout=aiohttp.ClientTimeout(total=120)) as r:
                    j = json.loads((await r.json())["choices"][0]["message"]["content"])
                    answer = [float(j["A"]), float(j["B"]), j.get("better", "tie"), j.get("why", "")]
                    cache[digest] = answer
                    return answer
            except Exception:
                await asyncio.sleep(3 + 3 * attempt)
    return None


async def run(cases, key, cache, workers, prompt=JUDGE):
    import aiohttp
    sem = asyncio.Semaphore(workers)
    async with aiohttp.ClientSession() as session:
        async def both(case):
            one = await ask(session, sem, key, cache, case["seed"], case["old"], case["new"], prompt)
            two = await ask(session, sem, key, cache, case["seed"], case["new"], case["old"], prompt)
            if one and two:
                case["old_score"] = (one[0] + two[1]) / 2
                case["new_score"] = (one[1] + two[0]) / 2
                case["votes"] = [{"A": "old", "B": "new"}.get(one[2], "tie"), {"A": "new", "B": "old"}.get(two[2], "tie")]
                case["why"] = [one[3], two[3]]
            return case
        return await asyncio.gather(*(both(c) for c in cases))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--features", type=Path, required=True)
    ap.add_argument("--pair", action="append", required=True, help="LIB=OLD.tsv,NEW.tsv")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--tracks", type=int, default=24)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--key-file", type=Path, default=Path.home() / ".deepseek_key")
    ap.add_argument("--dry-run", action="store_true", help="print one prompt and the call count, call nothing")
    ap.add_argument("--prompt", choices=sorted(PROMPTS), default="default",
                    help="default: the earlier judge (it also asks not to repeat an artist); fit: tracks only")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    cases = []
    for spec in a.pair:
        lib, files = spec.split("=", 1)
        old_path, new_path = files.split(",")
        meta = [l.rstrip("\n").split("\t") for l in open(a.features / lib / "meta.tsv")]
        old, new = read(old_path), read(new_path)
        for k in old:
            if k in new and old[k] and new[k]:
                s = int(k.split("@")[0])
                cases.append(dict(lib=lib, key=k, seed=label(meta, s),
                                  old=[label(meta, r) for r in old[k][:a.tracks]],
                                  new=[label(meta, r) for r in new[k][:a.tracks]],
                                  identical=old[k][:a.tracks] == new[k][:a.tracks]))
    if a.dry_run:
        c = cases[0]
        print(PROMPTS[a.prompt], "\n\nSeed:", c["seed"], "\nQueue A:", *c["old"][:5], "...", sep="\n")
        print(f"\n{len(cases)} pairs, {2 * len(cases)} calls")
        return
    key = os.environ.get("LLM_KEY") or (a.key_file.read_text().strip() if a.key_file.exists() else "")
    if not key:
        raise SystemExit(f"no key: set LLM_KEY or put it in {a.key_file}")
    cache_path = a.out / "cache.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}
    try:
        done = asyncio.run(run(cases, key, cache, a.workers, PROMPTS[a.prompt]))
    finally:
        cache_path.write_text(json.dumps(cache))
    judged = [c for c in done if "new_score" in c]
    (a.out / "judged.json").write_text(json.dumps(judged, ensure_ascii=False, indent=1))
    rng = np.random.default_rng(0)
    for lib in sorted({c["lib"] for c in judged}) + ["all"]:
        sub = [c for c in judged if lib in ("all", c["lib"])]
        d = np.array([c["new_score"] - c["old_score"] for c in sub])
        boot = d[rng.integers(0, len(d), (5000, len(d)))].mean(1)
        wins = sum(c["new_score"] > c["old_score"] for c in sub)
        losses = sum(c["new_score"] < c["old_score"] for c in sub)
        agree = np.mean([c["votes"][0] == c["votes"][1] for c in sub])
        print(f"{lib:12s} {len(sub):4d} pairs: old {np.mean([c['old_score'] for c in sub]):.2f}, "
              f"new {np.mean([c['new_score'] for c in sub]):.2f}, Δ {d.mean():+.2f} [{np.percentile(boot, 2.5):+.2f}, "
              f"{np.percentile(boot, 97.5):+.2f}]; new better {wins}, old better {losses}, "
              f"ties {len(sub) - wins - losses}; both orders agree {agree * 100:.0f} %")
    print(f"{len(cases) - len(judged)} pairs failed")


if __name__ == "__main__":
    main()
