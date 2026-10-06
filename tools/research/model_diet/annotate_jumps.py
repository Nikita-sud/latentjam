"""Ask DeepSeek Flash where a SMART queue jumps: which tracks break the flow from what precedes them and the seed.

    python annotate_jumps.py --features <features root> --queues LIB=FILE.tsv [...] --out DIR [--tracks 24]

One call per queue; answers cached in DIR (resumable). The key comes from LLM_KEY or --key-file and is never
printed. Only public libraries belong here; the owner's library never leaves the machine.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path

from judge_queues import URL, label, read

PROMPT = ("Below are the seed track a listener picked and the radio queue a music app built after it, as "
          "'artist — title'. List the positions of the tracks that break the flow: a clear jump in genre, style, "
          "era, mood or language from the tracks right before them and from the seed's character. Usually none or "
          "a few tracks are jumps; do not list tracks that fit. Answer json {\"jumps\": [positions], \"why\": "
          "\"one short sentence\"}.")


async def ask(session, sem, key, cache, seed, queue):
    import aiohttp
    body = {"model": "deepseek-flash", "thinking": {"type": "disabled"}, "temperature": 0.0, "max_tokens": 160,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": PROMPT},
                         {"role": "user", "content": f"Seed: {seed}\n\nQueue:\n" +
                          "\n".join(f"{i + 1}. {x}" for i, x in enumerate(queue))}]}
    digest = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()
    if digest in cache:
        return cache[digest]
    async with sem:
        for attempt in range(6):
            try:
                async with session.post(URL, json=body, headers={"Authorization": f"Bearer {key}"},
                                        timeout=aiohttp.ClientTimeout(total=120)) as r:
                    j = json.loads((await r.json())["choices"][0]["message"]["content"])
                    answer = {"jumps": [int(p) for p in j.get("jumps", []) if str(p).isdigit()], "why": j.get("why", "")}
                    cache[digest] = answer
                    return answer
            except Exception:
                await asyncio.sleep(3 + 3 * attempt)
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--features", type=Path, required=True)
    ap.add_argument("--queues", action="append", required=True, help="LIB=FILE.tsv")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--tracks", type=int, default=24)
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--key-file", type=Path, default=Path.home() / ".deepseek_key")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    jobs = []
    for spec in a.queues:
        lib, path = spec.split("=", 1)
        meta = [l.rstrip("\n").split("\t") for l in open(a.features / lib / "meta.tsv")]
        for k, q in read(path).items():
            jobs.append((lib, k, label(meta, int(k.split("@")[0])), [label(meta, r) for r in q[:a.tracks]]))
    key = os.environ.get("LLM_KEY") or (a.key_file.read_text().strip() if a.key_file.exists() else "")
    if not key:
        raise SystemExit(f"no key: set LLM_KEY or put it in {a.key_file}")
    cache_path = a.out / "cache.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}

    async def run():
        import aiohttp
        sem = asyncio.Semaphore(a.workers)
        async with aiohttp.ClientSession() as session:
            return await asyncio.gather(*(ask(session, sem, key, cache, seed, queue) for _, _, seed, queue in jobs))

    try:
        answers = asyncio.run(run())
    finally:
        cache_path.write_text(json.dumps(cache))
    out = {f"{lib}|{k}": ans for (lib, k, _, _), ans in zip(jobs, answers) if ans is not None}
    (a.out / "jumps.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    n = len(out)
    with_jump = sum(bool(v["jumps"]) for v in out.values())
    total = sum(len(v["jumps"]) for v in out.values())
    print(f"{n} queues annotated, {len(jobs) - n} failed; {with_jump} with at least one jump, {total} jumps in all")


if __name__ == "__main__":
    main()
