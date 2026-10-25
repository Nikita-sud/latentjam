#!/usr/bin/env python3
"""Scores MapRefreshCheck logs against docs/map-refresh-checks.md, piece 1 (written before the run).

    python3 -I tools/research/map_refresh_piece1_score.py --expect LIB,... LOG [LOG ...]
"""
import re, sys, collections

ROWS = ['trust', 'recall', 'class', 'RF', 'lost', 'p90move', 'knnExact', 'overlapCaseBand', 'cfCaseBand']


def main(args):
    expect = args[args.index('--expect') + 1].split(',') if '--expect' in args else None
    paths = [a for i, a in enumerate(args) if a != '--expect' and (i == 0 or args[i - 1] != '--expect')]
    lines = [l.strip() for p in paths for l in open(p, encoding='utf-8') if l.startswith('P1 ')]
    libs, verdict, decision, chain = set(), {}, {}, {}
    for l in lines:
        m = re.match(r'P1 lib=(\S+) n=', l)
        if m: libs.add(m[1]); continue
        m = re.match(r'P1 lib=(\S+) (\S+) decision=(\S+)(.*)', l)
        if m:
            d = dict(re.findall(r'(\w+)=([-\w.]+)', m[4])); d['decision'] = m[3]; decision[m[1], m[2]] = d; continue
        m = re.match(r'P1 lib=(\S+) (\S+) verdict (.*)', l)
        if m: verdict[m[1], m[2]] = {k: v == 'PASS' for k, v in re.findall(r'(\w+)=(PASS|FAIL)', m[3])}; continue
        m = re.match(r'P1 chain lib=(\S+) rebuilds=(\d+) verdict=(\S+)', l)
        if m: chain[m[1]] = (m[3] == 'PASS', int(m[2]))
    problems = []
    if expect is None: problems.append('no --expect')
    else: problems += [f'{x}: missing' for x in expect if x not in libs]
    for lib in libs:
        need = ['add0.5', 'add1.0', 'add2.0', 'change0.5', 'change1.0', 'change2.0', 'album1'] + (['heldout1'] if lib.startswith('fma') else [])
        problems += [f'{lib} {c}: missing' for c in need if (lib, c) not in decision]
        if lib not in chain: problems.append(f'{lib}: no chain')
    for k, d in decision.items():
        if d['decision'] != 'refreshed': problems.append(f'{k[0]} {k[1]}: declined')
        elif k not in verdict: problems.append(f'{k[0]} {k[1]}: no verdict')
    if problems: print('INCOMPLETE / FAILING:'); [print('  ' + p) for p in problems]
    ok = not problems
    print('| Row | Pass | Failing cases | Verdict |\n| --- | --- | --- | --- |')
    for r in ROWS:
        cases = sorted(verdict)
        fails = [f'{a} {b}' for a, b in cases if not verdict[a, b].get(r, False)]
        print(f'| {r} | {len(cases) - len(fails)} / {len(cases)} | {"; ".join(fails) or "—"} | {"PASS" if ok and not fails else "FAIL"} |')
    small = [k for k, d in decision.items() if re.match(r'(add|change)(0\.5|1\.0)$', k[1])]
    stale_small = [f'{a} {b}' for a, b in small if decision[a, b].get('stale') == 'true']
    print(f'| small changes not stale (0.5 / 1 %) | {len(small) - len(stale_small)} / {len(small)} | {"; ".join(stale_small) or "—"} | reported (a hint may fire) |')
    ov = [float(d['overlap']) - float(d['bandOverlapMean']) for d in decision.values() if 'overlap' in d]
    cf = [float(d['cf']) - float(d['bandCfMean']) for d in decision.values() if 'cf' in d and d['cf'] != 'NaN']
    mo = sum(ov) / len(ov) if ov else float('nan'); mc = sum(cf) / len(cf) if cf else float('nan')
    print(f'| overlap mean − band mean ≥ −0.05 | {mo:.4f} over {len(ov)} cases | | {"PASS" if ok and ov and mo >= -0.05 else "FAIL"} |')
    print(f'| CF mean − band mean ≥ −0.05 | {mc:.4f} over {len(cf)} cases | | {"PASS" if ok and cf and mc >= -0.05 else "FAIL"} |')
    for lib, (p, r) in sorted(chain.items()): print(f'| chain {lib} | {r} rebuilds | | {"PASS" if p and ok else "FAIL"} |')


if __name__ == '__main__':
    main(sys.argv[1:])
