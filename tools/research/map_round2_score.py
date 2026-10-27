#!/usr/bin/env python3
"""Round-2 Map refresh gate scorer (docs/map-cost-research-2026-10-25.md, "Round 2"), written before the gate run.

Reads MapRound2Replay gate logs and applies the frozen aggregation:
  * quality rows: every UNION-refreshed case on every library must pass each row;
  * router new styles: every novel-* case is a UNION full build, or a UNION refresh that passes every quality row;
  * router keeps: >= 90 % of add1, album1 and heldout1 cases are UNION refreshes;
  * chain: per budget, every library's chain verdict PASS and every refreshed step knnExact=true;
    adopted budget = the largest of 10/5/3 % that passes.
Device rows (time, heap, cancellation) come from the device log and are scored in the report.

    python3 -I tools/research/map_round2_score.py --expect LIB,LIB,... LOG [LOG ...]
    (--expect: the library names that must all be present, e.g. synthetic-s23,...,fma-5000-fma-gate-s101)
"""
import re, sys, collections

QUALITY = ['trust', 'recall', 'class', 'RF', 'CF', 'overlapCold', 'lost', 'p90move', 'staleBasis', 'knnExact']


def main(args):
    expect = args[args.index('--expect') + 1].split(',') if '--expect' in args else None
    paths = [a for i, a in enumerate(args) if a != '--expect' and (i == 0 or args[i - 1] != '--expect')]
    lines = [l.strip() for p in paths for l in open(p, encoding='utf-8') if l.startswith('R2 ')]
    headers = {m[1]: m[2] for m in (re.match(r'R2 lib=(\S+) n=\d+ dim=\d+ family=\S+ mode=(\S+)', l) for l in lines) if m}
    decision = {}   # (lib, scenario, router) -> (refresh?, reason)
    verdict = {}    # (lib, scenario, router) -> {row: bool}
    ratio = {}
    chain = collections.defaultdict(dict)  # budget -> lib -> (pass, knn ok)
    knn = collections.defaultdict(lambda: True)
    for l in lines:
        m = re.match(r'R2 lib=(\S+) (\S+) router=(\S+) share=\S+ decision=(\S+) reason=(\S+)', l)
        if m: decision[m[1], m[2], m[3]] = (m[4] == 'refresh', m[5]); continue
        m = re.match(r'R2 lib=(\S+) (\S+) router=(\S+) verdict (.*)', l)
        if m: verdict[m[1], m[2], m[3]] = {k: v == 'PASS' for k, v in re.findall(r'(\w+)=(PASS|FAIL)', m[4])}; continue
        m = re.match(r'R2 lib=(\S+) (\S+) router=(\S+) stability .* ratio=(\S+)', l)
        if m: ratio[m[1], m[2], m[3]] = float(m[4]); continue
        m = re.match(r'R2 chain lib=(\S+) router=UNION B=(\S+) step=\d+ .*knnExact=(\S+)', l)
        if m and m[3] == 'false': knn[m[1], m[2]] = False
        m = re.match(r'R2 chain lib=(\S+) router=UNION B=(\S+) fullBuilds=(\d+) verdict=(\S+)', l)
        if m: chain[m[2]][m[1]] = (m[4] == 'PASS' and knn[m[1], m[2]], int(m[3]))
    libs = sorted({k[0] for k in decision})
    print(f'libraries: {", ".join(libs)}\n')
    union = {k: v for k, v in decision.items() if k[2] == 'UNION'}
    refreshed = [k for k, v in union.items() if v[0]]
    # Completeness (a missing line must fail, never pass vacuously).
    problems = []
    if expect is None: problems.append('no --expect list given')
    else:
        for lib in expect:
            if lib not in libs: problems.append(f'{lib}: missing from the logs')
    for lib, mode in headers.items():
        if mode != 'gate': problems.append(f'{lib}: mode={mode}, not gate')
    for lib in libs:
        for sc in ('add1', 'album1'):
            if (lib, sc, 'UNION') not in union: problems.append(f'{lib}: no {sc}')
        fma = lib.startswith('fma')
        novel = [k for k in union if k[0] == lib and k[1].startswith('novel')]
        if len(novel) != 9: problems.append(f'{lib}: {len(novel)} new-style cases, expected 9')
        if fma and (lib, 'heldout1', 'UNION') not in union: problems.append(f'{lib}: no heldout1')
        for b in ['0.1', '0.05', '0.03']:
            if lib not in chain.get(b, {}): problems.append(f'{lib}: no chain at budget {b}')
    for k in refreshed:
        if k not in verdict: problems.append(f'{k[0]} {k[1]}: refreshed but no verdict line'); continue
        need = [r for r in QUALITY if not (k[1] == 'same' and r in ('RF', 'CF'))]
        missing = [r for r in need if r not in verdict[k]]
        if missing: problems.append(f'{k[0]} {k[1]}: verdict lacks {",".join(missing)}')
    if problems:
        print('INCOMPLETE — every affected row fails:'); [print('  ' + x) for x in problems]
    print('| Row | Pass | Failing cases | Verdict |\n| --- | --- | --- | --- |')
    for row in QUALITY:
        cases = [k for k in refreshed if k not in verdict or row in verdict[k]]
        fails = [f'{k[0]} {k[1]}' for k in cases if not verdict.get(k, {}).get(row, False)]
        print(f'| {row} | {len(cases) - len(fails)} / {len(cases)} | {"; ".join(fails) or "—"} | {"PASS" if not fails and not problems else "FAIL"} |')
    times = [ratio[k] for k in refreshed if k in ratio]
    if times: print(f'| time (host, report only) | {min(times):.3f}–{max(times):.3f}× today | | — |')
    novel = [k for k in union if k[1].startswith('novel')]
    bad = [f'{k[0]} {k[1]}' for k in novel if union[k][0] and not all(verdict.get(k, {}).get(r, r in ('RF', 'CF') and k in verdict) for r in QUALITY)]
    ok_novel = bool(novel) and not bad and not problems
    print(f'| router: new styles (full, or a refresh passing every quality row) | {len(novel) - len(bad)} / {len(novel)} | {"; ".join(bad) or "—"} | {"PASS" if ok_novel else "FAIL"} |')
    keeps = [k for k in union if k[1] in ('add1', 'album1', 'heldout1')]
    kept = [k for k in keeps if union[k][0]]
    share = len(kept) / len(keeps) if keeps else float('nan')
    rejected = [f'{k[0]} {k[1]} ({union[k][1]})' for k in keeps if not union[k][0]]
    print(f'| router: keeps (add1, album1, heldout1 → refresh) | {len(kept)} / {len(keeps)} = {share:.0%} | {"; ".join(rejected) or "—"} | {"PASS" if share >= 0.9 and not problems else "FAIL"} |')
    adopted = None
    for b in ['0.1', '0.05', '0.03']:
        res = chain.get(b, {})
        ok = bool(res) and len(res) == len(libs) and all(v[0] for v in res.values())
        fulls = ', '.join(f'{lib} {v[1]} full' for lib, v in sorted(res.items()))
        print(f'| chain, budget {float(b):.0%} | {sum(v[0] for v in res.values())} / {len(res)} | {fulls} | {"PASS" if ok else "FAIL"} |')
        if ok and adopted is None: adopted = b
    print(f'\nadopted budget: {adopted}')
    print('\nRouter decisions per variant (refresh / cases):')
    for r in ['UNION', 'GEOMETRY', 'GEOMETRY_FAMILY', 'CHECK']:
        ks = [k for k in decision if k[2] == r]
        by = collections.defaultdict(lambda: [0, 0])
        for k in ks:
            kind = 'novel' if k[1].startswith('novel') else k[1]
            by[kind][0] += decision[k][0]; by[kind][1] += 1
        print(f'  {r}: ' + ', '.join(f'{kind} {a}/{b}' for kind, (a, b) in sorted(by.items())))


if __name__ == '__main__':
    main(sys.argv[1:])
