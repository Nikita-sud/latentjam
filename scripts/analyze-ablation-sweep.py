#!/usr/bin/env python3
"""
Analyze the LatentJam SMART recommendation ablation sweep.

Reads events.jsonl (decisions emitted by RecommendationEngine.runPipeline under
each FilterConfig in the ablation matrix), groups by config, and computes:

1. Per-config aggregates: pool/head/tail sizes, top-1 cosine score, scorer use rate.
2. Per-seed cross-config agreement (Jaccard of top-K head sets).
3. Diversity (unique artists / unique titles in head pool).
4. Predictor delta: where the scorer reranks picks vs the cosine fallback.
5. Bug candidates: identical-head signatures across seeds, tiny pools, non-shifting picks.

Run:  python3 lj-ablation-analyze.py path/to/events.jsonl
"""
from __future__ import annotations

import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


CONFIGS_OF_INTEREST = [
    "ablation/production",
    "ablation/no-predictor",
    "ablation/no-bpm",
    "ablation/with-energy",
    "ablation/no-cooldown",
    "ablation/no-dedup",
    "ablation/raw-cosine",
]
TOP_K = 5  # head depth for stability + diversity metrics


def load_decisions(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if row.get("type") != "decision":
                continue
            label = row.get("filter_label", "")
            if not label.startswith("ablation/"):
                continue
            rows.append(row)
    return rows


def jaccard(a: set, b: set) -> float:
    if not a and not b:
        return 1.0
    u = a | b
    if not u:
        return 0.0
    return len(a & b) / len(u)


def head_uids(row: dict[str, Any], k: int = TOP_K) -> list[str]:
    return [pick.get("uid", "") for pick in (row.get("head") or [])[:k]]


def head_titles(row: dict[str, Any], k: int = TOP_K) -> list[str]:
    return [pick.get("name") or "" for pick in (row.get("head") or [])[:k]]


def head_artists(row: dict[str, Any], k: int = TOP_K) -> list[str]:
    return [pick.get("artists") or "" for pick in (row.get("head") or [])[:k]]


def fmt(x: float) -> str:
    if x != x:  # NaN
        return "n/a"
    return f"{x:.3f}"


def section(title: str) -> None:
    bar = "─" * len(title)
    print(f"\n{title}\n{bar}")


def per_config_aggregates(rows: list[dict[str, Any]]) -> None:
    section("1. Per-config aggregates (n decisions, mean ± stdev)")
    by_config: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in rows:
        by_config[r["filter_label"]].append(r)

    header = (
        f"{'config':<28} {'n':>5} {'pool':>10} {'head':>10} {'tail':>10} "
        f"{'cooldown':>10} {'top1_cos':>10} {'scorer_use':>11}"
    )
    print(header)
    print("-" * len(header))
    for label in CONFIGS_OF_INTEREST:
        rs = by_config.get(label, [])
        if not rs:
            print(f"{label:<28} {0:>5}  (no rows)")
            continue
        pool = [r["pool_size"] for r in rs]
        # head_size = number of head picks emitted (capped at TEMP_TOP_K=20)
        heads = [len(r.get("head") or []) for r in rs]
        tails = [r["tail_size"] for r in rs]
        cooldowns = [r["cooldown_artist_count"] for r in rs]
        top1_cos = [
            (r.get("head") or [{}])[0].get("score") for r in rs
            if (r.get("head") or [{}])[0].get("score") is not None
        ]
        scorer_used = sum(
            1 for r in rs if any(p.get("scorer_score") is not None for p in (r.get("head") or []))
        )

        def m(xs):
            xs = [x for x in xs if x is not None]
            return statistics.mean(xs) if xs else float("nan")

        print(
            f"{label:<28} {len(rs):>5} "
            f"{m(pool):>10.1f} {m(heads):>10.1f} {m(tails):>10.1f} "
            f"{m(cooldowns):>10.1f} {fmt(m(top1_cos)):>10} "
            f"{scorer_used:>4}/{len(rs):<5}"
        )


def cross_config_jaccard(rows: list[dict[str, Any]]) -> None:
    section(f"2. Cross-config agreement (Jaccard of top-{TOP_K} head sets per seed)")
    # For each seed_uid, group decisions by config_label.
    by_seed: dict[str, dict[str, dict[str, Any]]] = defaultdict(dict)
    for r in rows:
        by_seed[r["seed_uid"]][r["filter_label"]] = r

    # All pairs (config_a, config_b) — show the upper-triangle.
    configs = [c for c in CONFIGS_OF_INTEREST if any(c in s for s in by_seed.values())]
    print(f"{'':<28}  " + " ".join(f"{c.removeprefix('ablation/')[:10]:>10}" for c in configs))
    for a in configs:
        row_str = f"{a:<28}  "
        for b in configs:
            if b == a:
                row_str += f"{'-':>10} "
                continue
            scores = []
            for seed, by_cfg in by_seed.items():
                ra = by_cfg.get(a)
                rb = by_cfg.get(b)
                if not (ra and rb):
                    continue
                scores.append(jaccard(set(head_uids(ra)), set(head_uids(rb))))
            if scores:
                row_str += f"{statistics.mean(scores):>10.3f} "
            else:
                row_str += f"{'-':>10} "
        print(row_str)
    print(
        f"\n  (1.0 = identical top-{TOP_K} sets per seed; 0.0 = no overlap. "
        f"Reads as 'how much does removing this component change SMART's output?')"
    )


def diversity(rows: list[dict[str, Any]]) -> None:
    section("3. Head diversity (unique artists / titles per decision, mean across seeds)")
    by_config: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in rows:
        by_config[r["filter_label"]].append(r)
    print(f"{'config':<28} {'unique_artists':>16} {'unique_titles':>15}")
    print("-" * 60)
    for label in CONFIGS_OF_INTEREST:
        rs = by_config.get(label, [])
        if not rs:
            continue
        uart = [len(set(head_artists(r))) for r in rs]
        utit = [len(set(head_titles(r))) for r in rs]
        print(f"{label:<28} {statistics.mean(uart):>16.2f} {statistics.mean(utit):>15.2f}")


def predictor_delta(rows: list[dict[str, Any]]) -> None:
    section("4. Predictor effect — head pick stability vs no-predictor")
    by_seed_cfg: dict[tuple, dict] = {}
    for r in rows:
        by_seed_cfg[(r["seed_uid"], r["filter_label"])] = r

    seeds = {r["seed_uid"] for r in rows}
    overlaps = []
    rerank_top1_changes = 0
    total_pairs = 0
    for seed in seeds:
        prod = by_seed_cfg.get((seed, "ablation/production"))
        nopred = by_seed_cfg.get((seed, "ablation/no-predictor"))
        if not (prod and nopred):
            continue
        total_pairs += 1
        p_uids = head_uids(prod)
        n_uids = head_uids(nopred)
        overlaps.append(jaccard(set(p_uids), set(n_uids)))
        if p_uids and n_uids and p_uids[0] != n_uids[0]:
            rerank_top1_changes += 1

    if total_pairs == 0:
        print("  (no production/no-predictor pairs found)")
        return
    print(f"  Pairs compared: {total_pairs}")
    print(
        f"  Mean Jaccard top-{TOP_K} (production ↔ no-predictor): "
        f"{statistics.mean(overlaps):.3f}"
    )
    print(
        f"  Top-1 picks differ in {rerank_top1_changes}/{total_pairs} seeds "
        f"({100*rerank_top1_changes/total_pairs:.0f} %)"
    )

    # Scorer score range when present
    scorer_vals = [
        p["scorer_score"]
        for r in rows
        if r["filter_label"] == "ablation/production"
        for p in (r.get("head") or [])
        if p.get("scorer_score") is not None
    ]
    if scorer_vals:
        print(
            f"  Scorer logit range (production head picks): "
            f"min={min(scorer_vals):.3f} max={max(scorer_vals):.3f} "
            f"mean={statistics.mean(scorer_vals):.3f}"
        )


def bug_candidates(rows: list[dict[str, Any]]) -> None:
    section("5. Bug candidates")
    by_config: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in rows:
        by_config[r["filter_label"]].append(r)

    # 5a — same top-1 across many different seeds → broken seed influence
    print("\n  (a) Top-1 pick concentration per config")
    print(f"      {'config':<28} {'most_common_top1_pct':>22} {'unique_top1_uids':>18}")
    for label in CONFIGS_OF_INTEREST:
        rs = by_config.get(label, [])
        if not rs:
            continue
        top1 = [
            (r.get("head") or [{}])[0].get("uid")
            for r in rs
            if (r.get("head") or [{}])[0].get("uid")
        ]
        if not top1:
            continue
        c = Counter(top1)
        most_common_uid, most_common_count = c.most_common(1)[0]
        unique = len(c)
        pct = 100 * most_common_count / len(top1)
        print(f"      {label:<28} {pct:>20.1f} % {unique:>18}")
    print("      ↑ if any config has ≥30 % concentration, the seed is barely influencing picks.")

    # 5b — pool_size small relative to POOL_SIZE=100 → over-aggressive cooldown/dedup
    print("\n  (b) Pool exhaustion (smaller = filters cut more candidates)")
    print(f"      {'config':<28} {'mean_pool':>10} {'min_pool':>10} {'< POOL_SIZE pct':>18}")
    POOL_TARGET = 100
    for label in CONFIGS_OF_INTEREST:
        rs = by_config.get(label, [])
        if not rs:
            continue
        pools = [r["pool_size"] for r in rs]
        below = sum(1 for p in pools if p < POOL_TARGET)
        print(
            f"      {label:<28} {statistics.mean(pools):>10.1f} {min(pools):>10} "
            f"{100*below/len(pools):>16.1f} %"
        )

    # 5c — same head across consecutive seeds (within a config). If the seed
    # genuinely doesn't influence picks, the head sequence is constant.
    print("\n  (c) Head signature stability per config")
    print(f"      {'config':<28} {'unique_heads':>14} {'top_head_pct':>14}")
    for label in CONFIGS_OF_INTEREST:
        rs = by_config.get(label, [])
        if not rs:
            continue
        sigs = [tuple(head_uids(r)) for r in rs]
        c = Counter(sigs)
        unique = len(c)
        top_pct = 100 * c.most_common(1)[0][1] / len(sigs)
        print(f"      {label:<28} {unique:>14} {top_pct:>13.1f} %")
    print("      ↑ a high top_head_pct AND low unique_heads means SMART returns ≈the same queue every time.")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.exists():
        print(f"file not found: {path}", file=sys.stderr)
        return 1
    rows = load_decisions(path)
    if not rows:
        print(f"no ablation/* decisions in {path}", file=sys.stderr)
        return 1
    print(f"Loaded {len(rows)} ablation decisions from {path}")
    print(f"Configs present: {sorted({r['filter_label'] for r in rows})}")
    print(f"Unique seeds: {len({r['seed_uid'] for r in rows})}")

    per_config_aggregates(rows)
    cross_config_jaccard(rows)
    diversity(rows)
    predictor_delta(rows)
    bug_candidates(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
