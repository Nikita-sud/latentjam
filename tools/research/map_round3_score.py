#!/usr/bin/env python3
"""Round-3 Map scorer (docs/map-cost-research-2026-10-25.md, "Round 3"), written before the gate run.

Frozen aggregation over MapRound3Replay gate logs:
  * refresh rows: every non-novel case (same, add1/2/3/5, change1/2/3, album1, heldout1) passes each row;
  * waiting rows: every novel case passes waitTrust, waitRecall, waitClass, waitLost, waitRF;
  * recovery rows: every novel case passes recoverRF, recoverCF, recoverTrust, recoverRecall;
  * region rows (every case): churn of settled tracks <= 0.001; purityAdditive >= purityDiscovered - 0.01;
    coverageAdditive >= coverageDiscovered - 0.02; novel cases: ownRegionAdditive >= ownRegionDiscovered - 0.10;
  * chain rows, per budget: every library PASS (waiting map at each stale step and the final step vs a cold build,
    knnExact at every step, and the chain's final additive regions vs a fresh discovery: purity >= -0.01,
    coverage >= -0.02); adopted budget = the larger of 5 / 3 % that passes;
  * hint rows (latency, reported, not deciding): stale fires (hint or budget at 5 %) on >= 2/3 of novel cases of
    size >= 1 %; the hint fires on <= 20 % of non-novel cases of 1-3 %.
Completeness: every expected library present with mode=gate; per library 9 novel cases, add1..5, change1..3,
album1, same, heldout1 (FMA), chains at both budgets, a verdict and regions line per case. Anything missing fails.

    python3 -I tools/research/map_round3_score.py --expect LIB,LIB,... LOG [LOG ...]
"""
import re, sys, collections

REFRESH = ['trust', 'recall', 'class', 'RF', 'CF', 'lost', 'p90move', 'overlapCold', 'staleBasis', 'knnExact']
WAIT = ['waitTrust', 'waitRecall', 'waitClass', 'waitLost', 'waitRF']
RECOVER = ['recoverRF', 'recoverCF', 'recoverTrust', 'recoverRecall']
MIN_CLUSTER = 4  # TrackClustering.MIN_CLUSTER_SIZE (core/smart TrackClustering.kt)
BASE = ['same', 'add1', 'add2', 'add3', 'add5', 'change1', 'change2', 'change3', 'album1']


def main(args):
    expect = args[args.index('--expect') + 1].split(',') if '--expect' in args else None
    paths = [a for i, a in enumerate(args) if a != '--expect' and (i == 0 or args[i - 1] != '--expect')]
    lines = [l.strip() for p in paths for l in open(p, encoding='utf-8') if l.startswith('R3 ')]
    headers = {}
    case = {}      # (lib, sc) -> dict
    chain = collections.defaultdict(dict)
    for l in lines:
        m = re.match(r'R3 lib=(\S+) n=\d+ dim=\d+ family=(\S+) artists=\S+ mode=(\S+)', l)
        if m: headers[m[1]] = (m[3], m[2] == 'true'); continue
        m = re.match(r'R3 lib=(\S+) (\S+) share=(\S+) refreshMs=\S+ hint=(\S+) staleAt5=(\S+) staleAt3=\S+ novel=(\S+)', l)
        if m: case.setdefault((m[1], m[2]), {}).update(share=float(m[3]), hint=m[4] != 'none', stale5=m[5] == 'true', novel=m[6] == 'true'); continue
        m = re.match(r'R3 lib=(\S+) (\S+) regions (.*)', l)
        if m: case.setdefault((m[1], m[2]), {})['regions'] = {k: float(v) for k, v in re.findall(r'(\w+)=([-\d.NaN]+)', m[3]) if v != 'NaN' and not k.startswith('newTracks')}; continue
        m = re.match(r'R3 lib=(\S+) (\S+) verdict (.*)', l)
        if m: case.setdefault((m[1], m[2]), {})['verdict'] = {k: v == 'PASS' for k, v in re.findall(r'(\w+)=(PASS|FAIL)', m[3])}; continue
        m = re.match(r'R3 chain lib=(\S+) B=(\S+) rebuilds=(\d+) verdict=(\S+)', l)
        if m: chain[m[2]][m[1]] = (m[4] == 'PASS', int(m[3]))
    libs = sorted(headers)
    problems = []
    if expect is None: problems.append('no --expect list')
    else: problems += [f'{lib}: missing' for lib in expect if lib not in libs]
    for lib, (mode, fam) in headers.items():
        if mode != 'gate': problems.append(f'{lib}: mode={mode}')
        need = BASE + (['heldout1'] if fam else [])
        problems += [f'{lib}: no {sc}' for sc in need if (lib, sc) not in case]
        nov = [k for k in case if k[0] == lib and k[1].startswith('novel-')]
        if len(nov) != 9: problems.append(f'{lib}: {len(nov)} novel cases, expected 9')
        for b in ['0.05', '0.03']:
            if lib not in chain.get(b, {}): problems.append(f'{lib}: no chain at {b}')
    for k, v in case.items():
        if 'verdict' not in v or 'regions' not in v or 'share' not in v: problems.append(f'{k[0]} {k[1]}: incomplete lines')
    if problems: print('INCOMPLETE — every row fails:'); [print('  ' + p) for p in problems]
    ok_all = not problems
    print('| Row | Pass | Failing cases | Verdict |\n| --- | --- | --- | --- |')

    def row(name, keys, pred):
        fails = [f'{k[0]} {k[1]}' for k in keys if not pred(case[k])]
        print(f'| {name} | {len(keys) - len(fails)} / {len(keys)} | {"; ".join(fails[:12]) + (" …" if len(fails) > 12 else "") or "—"} | {"PASS" if not fails and ok_all and keys else "FAIL"} |')
        return not fails and ok_all and bool(keys)

    base = [k for k, v in case.items() if not v.get('novel')]
    novel = [k for k, v in case.items() if v.get('novel')]
    verdicts = []
    for r in REFRESH:
        keys = [k for k in base if not (k[1] == 'same' and r in ('RF', 'CF'))]
        verdicts.append(row(f'refresh: {r}', keys, lambda v, r=r: v.get('verdict', {}).get(r, False)))
    for r in WAIT + RECOVER:
        verdicts.append(row(f'new style: {r}', novel, lambda v, r=r: v.get('verdict', {}).get(r, False)))
    allk = list(case)
    verdicts.append(row('regions: settled churn <= 0.001', allk, lambda v: v.get('regions', {}).get('churnAdditive', 1) <= 0.001))
    verdicts.append(row('regions: purity >= discovered - 0.01', allk, lambda v: v.get('regions', {}).get('purityAdditive', 0) >= v.get('regions', {}).get('purityDiscovered', 9) - 0.01))
    verdicts.append(row('regions: coverage >= discovered - 0.02', allk, lambda v: v.get('regions', {}).get('coverageAdditive', 0) >= v.get('regions', {}).get('coverageDiscovered', 9) - 0.02))
    verdicts.append(row('regions: ordinary changes create <= 1 new region, none below MIN_CLUSTER_SIZE', base,
        lambda v: v.get('regions', {}).get('newRegions', 9) <= 1 and (v['regions'].get('newRegions', 0) == 0 or v['regions'].get('newRegionMinSize', 0) >= MIN_CLUSTER)))
    owned = [k for k in novel if case[k].get('regions', {}).get('ownRegionDiscovered', 0) > 0]
    print(f'| (new styles where a fresh rediscovery gives them no region: {len(novel) - len(owned)} of {len(novel)}; excluded from the next row) | | | |')
    verdicts.append(row('regions: new style own region >= discovered - 0.10', owned, lambda v: v.get('regions', {}).get('ownRegionAdditive', 0) >= v.get('regions', {}).get('ownRegionDiscovered', 9) - 0.10))
    adopted = None
    for b in ['0.05', '0.03']:
        res = chain.get(b, {})
        ok = ok_all and len(res) == len(libs) and all(v[0] for v in res.values())
        print(f'| chain, budget {float(b):.0%} | {sum(v[0] for v in res.values())} / {len(res)} | {", ".join(f"{l} {v[1]} rebuilds" for l, v in sorted(res.items()))} | {"PASS" if ok else "FAIL"} |')
        if ok and adopted is None: adopted = b
    verdicts.append(adopted is not None)
    # Latency rows (reported, not deciding).
    big = [k for k in novel if case[k]['share'] >= 0.0095]
    fired = [k for k in big if case[k]['stale5']]
    small = [k for k in base if k[1] in ('add1', 'add2', 'add3', 'change1', 'change2', 'change3', 'album1', 'heldout1')]
    false = [k for k in small if case[k]['hint']]
    print(f'| hint (latency): new styles >= 1 % marked stale | {len(fired)} / {len(big)} | {"; ".join(f"{k[0]} {k[1]}" for k in big if k not in fired) or "—"} | {"PASS" if big and len(fired) * 3 >= 2 * len(big) else "FAIL"} (not deciding) |')
    print(f'| hint (latency): false hints on 1–3 % ordinary changes | {len(false)} / {len(small)} | {"; ".join(f"{k[0]} {k[1]}" for k in false) or "—"} | {"PASS" if small and len(false) <= 0.2 * len(small) else "FAIL"} (not deciding) |')
    print(f'\nadopted budget: {adopted}; host rows all pass: {all(verdicts)} (device rows are scored from the device log)')


if __name__ == '__main__':
    main(sys.argv[1:])
