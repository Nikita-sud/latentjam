#!/usr/bin/env python3
"""Overnight 2026-10-21 stand driver (L1 product check only): one candidate = a CORE + FULL model pair.

    python3 night_stand.py LABEL CORE.json FULL.json [--engine chain]

Copies the pair into this checkout's assets, runs SmartStandRunner on stand/night-input (139 warm + 42 cold seeds, its
own input dir so the port session's stand/input is untouched), writes stand/night-LABEL/ (queues.md, metrics, and
night_metrics.json).
"""
import argparse, os, shutil, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
STAND = Path.home() / 'Documents/LJ/phone-state/stand'
WORK = STAND / 'night-input'
sys.path.insert(0, str(HERE))
import smart_stand as SS, night_metrics as NM


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('label'); ap.add_argument('core'); ap.add_argument('full')
    ap.add_argument('--engine', default='ranker'); ap.add_argument('--habit', action='store_true'); ap.add_argument('--work', default=str(WORK)); a = ap.parse_args()
    ml = REPO / 'androidApp/src/main/assets/ml'
    import hashlib, json
    for d in (ml, REPO / 'iosApp/iosApp/ml'):      # both copies: the asset tests compare them byte for byte
        shutil.copyfile(a.core, d / 'smart_ranker.json'); shutil.copyfile(a.full, d / 'smart_ranker_full.json')
        man = json.loads((d / 'smart_manifest.json').read_text())
        for n in ('smart_ranker.json', 'smart_ranker_full.json'):
            raw = (d / n).read_bytes(); man['assets'][n] = {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
        (d / 'smart_manifest.json').write_text(json.dumps(man, indent=1) + '\n')
    out = STAND / (f'night-{a.label}' + ('-habit' if a.habit else '') + ('-fresh' if 'fresh' in a.work else ''))
    if out.exists(): shutil.rmtree(out)
    env = dict(os.environ, LJ_STAND_DIR=str(a.work), LJ_STAND_OUT=str(out), LJ_STAND_ENGINE=a.engine)
    if a.habit: env['LJ_STAND_HABIT'] = '1'
    r = subprocess.run(['./gradlew', '--no-daemon', '--offline', '-q', ':composeApp:testAndroidHostTest',
                        '--tests', '*SmartStandRunner*', '--rerun'], cwd=REPO, env=env)
    if r.returncode != 0: raise SystemExit('stand runner failed')
    if a.habit:
        rows = [l.split('\t') for l in (out / 'habit.tsv').read_text().splitlines()[1:] if l]
        def share(col, k): return sum(1 for r in rows if 0 < int(r[col]) <= k) / max(1, len(rows))
        print(f"habit {a.label}: pairs {len(rows)} excluded {sum(r[3] == 'true' for r in rows)} | FULL top3 {share(4, 3):.2f} top10 {share(4, 10):.2f} | CORE top3 {share(5, 3):.2f} top10 {share(5, 10):.2f}")
        return
    (out / 'state.txt').write_text(f'night {a.label}\ncore {a.core}\nfull {a.full}\n')
    lib = {l.split('\t')[0]: l.split('\t') for l in (Path(a.work) / 'library.tsv').read_text(encoding='utf-8').splitlines() if l}
    SS.report(out, lib)
    sys.argv = ['night_metrics.py', str(out)]; NM.main()


if __name__ == '__main__':
    main()
