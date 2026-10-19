"""Golden cases for the app's "Learn from my habits" port (PersonalTransitions.kt, HabitSlots.kt).

    python3 export_golden.py RESEARCH_DIR FIT_DIR OUT.tsv.gz

RESEARCH_DIR is tools/research/listenbrainz_eval on branch claude/smart-stage0-eval-f2a727 (score.py, slots.py);
FIT_DIR its output/listenbrainz-eval/fit set (read-only). Each case is one FIT query in scenario A, exactly as
slots.py builds it: the visible history, score.py's `transition` baseline (top 20) and the SLOTS5 queue
(slots.interleave with m = 5) over the chain arm at pen 0.5. The salted random key is exported as its rank among
the library rows, which is all a sort reads from it (the app draws its own key; documented deviation).
"""
import gzip, json, sys
from pathlib import Path

research, fit, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
sys.path.insert(0, str(research))
import score, slots  # noqa: E402

CHAIN_ARM = 'chain~pen=0.5'
SMALL, LARGE = 30, 6  # queries from libraries of at most 800 rows, and from larger ones

chains = {}
for line in (fit / 'queues.integ-head.A.tsv').read_text().splitlines():
    k, arm, rows = line.split('\t')
    if arm == CHAIN_ARM: chains[k] = [int(x) for x in rows.split(',') if x]

libs = [Path(l) for l in (fit / 'manifest.txt').read_text().split() if l]
picked, small, large, stats = [], 0, 0, {'track': 0, 'artist_only': 0, 'none': 0, 'novelty': 0, 'large': 0}
for ld in libs:
    if small >= SMALL and large >= LARGE: break
    lib = json.loads((ld / 'truth.json').read_text())
    n = len(lib['track'])
    is_small = n <= 800
    if (is_small and small >= SMALL) or (not is_small and large >= LARGE): continue
    meta = [l.split('\t') for l in (ld / 'meta.tsv').read_text().split('\n') if l]
    ek = [score.engine_artist_key(m[1]) if len(m) > 1 else '' for m in meta]
    allev = [(int(t.split(':')[0]), int(t.split(':')[1])) for t in (ld / 'events.tsv').read_text().split()]
    for q in lib['queries'][:2]:  # at most two queries per listener
        ev = score.visible('A', [], allev, q['seed_ts'] * 1000)
        if len(ev) > 4000: continue  # keeps the fixture small
        base = score.baselines(lib, q, ev, {}, set(), ek, engine_key=ek)
        personal = base['transition']
        chain = [r for r in chains[q['key']] if r != q['seed']]
        n_track, n_artist = score.transitions(ev, q['seed'], ek)
        rand = score.rng(q['key']).random(n)  # the first draw of baselines(), as it is made there
        order = sorted(range(n), key=lambda i: rand[i])
        rank = [0] * n
        for r, i in enumerate(order): rank[i] = r
        novel = score.novelty(ev, q['seed_ts'] * 1000)
        if any(n_track[r] for r in personal[:5]): stats['track'] += 1
        elif any(n_artist[r] for r in personal[:5]): stats['artist_only'] += 1
        else: stats['none'] += 1
        stats['novelty'] += bool(novel - {q['seed']}); stats['large'] += not is_small
        picked.append('\n'.join([
            f"case\t{q['key']}\t{n}\t{q['seed']}\t{q['seed_ts'] * 1000}",
            'artists\t' + '\t'.join(ek),
            'ranks\t' + ','.join(map(str, rank)),
            'events\t' + ','.join(f'{r}:{t}' for r, t in ev),
            'chain\t' + ','.join(map(str, chain)),
            'transition\t' + ','.join(map(str, personal)),
            'slots5\t' + ','.join(map(str, slots.interleave(personal, chain, 5))),
        ]))
        if is_small: small += 1
        else: large += 1
        if (is_small and small >= SMALL) or (not is_small and large >= LARGE): break

with gzip.open(out, 'wt', compresslevel=9) as f:
    f.write('\n'.join(picked) + '\n')
print(len(picked), 'cases', stats, out.stat().st_size, 'bytes')
