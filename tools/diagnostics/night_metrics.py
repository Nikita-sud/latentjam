#!/usr/bin/env python3
"""Overnight 2026-10-21 auto metrics on stand queue dirs (L1 product check only; never evidence).

    python3 night_metrics.py DIR [DIR ...]        (DIR = a stand output dir with queues.jsonl + facts.tsv)

Press 0, first 10 picks, CORE and FULL, warm (stand/seeds.tsv) and cold (stand/cold_seeds.tsv) seed sets.
"""
import collections, json, re, sys
from pathlib import Path

STAND = Path.home() / 'Documents/LJ/phone-state/stand'
LIB = STAND / 'night-input/library.tsv'
GEN = STAND / 'night-input/track_genres.txt'
CYR = re.compile('[Ѐ-ӿ]')
CLASSES = {
    'kids': re.compile(r"children|kids|nursery|lullab|детск|animation ost|мультфильм|смешарик|smeshariki", re.I),
    'meme': re.compile(r"meme|sound effect|tiktok|voice recording|ringtone|game audio|\bмем\b|-мем", re.I),
    'comedy': re.compile(r"(^|;\s*)(comedy|comedy rock|parody)(\s*;|$)", re.I),
    'chanson': re.compile(r"chanson|шансон", re.I),
}


def load_lib():
    lib = {l.split('\t')[0]: l.split('\t') for l in LIB.read_text(encoding='utf-8').splitlines() if l}
    eg = {}
    for l in GEN.read_text().split():
        f = l.split('|')
        if len(f) >= 4 and f[0] == 'v5':
            try: eg[bytes.fromhex(f[1]).decode()] = bytes.fromhex(f[3]).decode()
            except ValueError: pass
    return lib, eg


def classes_of(r, g):
    out = set()
    if r is None: return out
    genre = g or r[5] or ''
    hay = ' | '.join([r[1], r[2], r[3], genre])
    if CLASSES['kids'].search(hay): out.add('kids')
    if CLASSES['meme'].search(genre) or re.search(r"\bmeme\b|\bмем\b|-мем|sound effect", r[1] + ' ' + r[2], re.I): out.add('meme')
    if CLASSES['comedy'].search(genre): out.add('comedy')
    if CLASSES['chanson'].search(genre): out.add('chanson')
    return out


def artist(r):
    return (r[2] or '').lower().split(' feat')[0].split(' ft.')[0].split(',')[0].split(' & ')[0].strip() if r else ''


def metrics(d, lib, eg, seedsets):
    d = Path(d)
    facts = {}
    lines = (d / 'facts.tsv').read_text().splitlines()
    head = lines[0].split('\t')
    for l in lines[1:]:
        if l: facts[l.split('\t')[0]] = dict(zip(head, l.split('\t')))
    fam = lambda i: (facts.get(i) or {}).get('family') or None
    qs = [json.loads(l) for l in (d / 'queues.jsonl').read_text().splitlines() if l]
    res = {}
    for set_name, seeds in seedsets.items():
        for mode in ('CORE', 'FULL'):
            rows = [q for q in qs if q['mode'] == mode and q['press'] == 0 and q['seed'] in seeds]
            if not rows: continue
            agg = collections.defaultdict(list); hub = collections.Counter()
            for q in rows:
                s = q['seed']; sr = lib.get(s); sf = fam(s); scl = classes_of(sr, eg.get(s))
                picks = [p['id'] for p in q['picks']][:10]
                srcs = [p['source'] for p in q['picks']][:10]
                pr = [lib.get(i) for i in picks]
                if sf: agg['fam'].append(sum(fam(i) == sf for i in picks) / max(1, len(picks)))
                if sr and CYR.search(sr[1] + sr[2]): agg['cyr'].append(sum(bool(r and CYR.search(r[1] + r[2])) for r in pr) / max(1, len(pr)))
                agg['fill'].append(sum(x == 'fill' for x in srcs))
                for a in {artist(r) for r in pr if artist(r)}: hub[a] += 1
                pcl = [classes_of(r, eg.get(i)) for r, i in zip(pr, picks)]
                for c in ('kids', 'meme', 'comedy'):
                    agg[c].append(0 if c in scl else sum(c in x for x in pcl))
                agg['chansonAfterMeme'].append(sum('chanson' in x for x in pcl) if ('meme' in scl or 'comedy' in scl) else 0)
                sd = int(sr[6]) if sr and sr[6] else None
                agg['short'].append(0 if (sd is not None and sd < 90_000) else sum(1 for r in pr if r and r[6] and int(r[6]) < 90_000))
                fs = [fam(i) for i in picks]
                agg['jumps'].append(sum(1 for k in range(1, len(fs) - 1) if fs[k] and fs[k - 1] and fs[k - 1] == fs[k + 1] and fs[k] != fs[k - 1] and fs[k] != sf))
                seq = [artist(sr)] + [artist(r) for r in pr]; best = cur = 1
                for k in range(1, len(seq)):
                    cur = cur + 1 if seq[k] and seq[k] == seq[k - 1] else 1; best = max(best, cur)
                agg['maxRun'].append(best)
                import re as _re
                def _sk(t):
                    t = _re.sub(r'[\(\[].*?[\)\]]', '', (t or '').lower()); t = _re.split(r' [-–—] ', t)[0]
                    return ' '.join(''.join(c if c.isalnum() else ' ' for c in t).split())
                ks = [_sk(sr[1] if sr else '')] + [_sk(r[1]) if r else '' for r in pr]
                ks = [x for x in ks if len(x) >= 6]
                agg['sameSong'].append(len(ks) - len(set(ks)))
                ac = collections.Counter(artist(r) for r in pr if artist(r))
                agg['artist4'].append(int(bool(ac) and max(ac.values()) >= 4))
            out = {k: round(sum(v) / len(v), 3) for k, v in agg.items() if v}
            out['hub'] = hub.most_common(1)[0][1] if hub else 0
            out['hubArtist'] = hub.most_common(1)[0][0] if hub else ''
            out['n'] = len(rows)
            res[f'{set_name}/{mode}'] = out
    return res


def main():
    lib, eg = load_lib()
    warm = set((STAND / 'seeds.tsv').read_text().split()); cold = set((STAND / 'cold_seeds.tsv').read_text().split())
    cols = ['n', 'fam', 'cyr', 'fill', 'hub', 'kids', 'meme', 'comedy', 'chansonAfterMeme', 'short', 'jumps', 'maxRun', 'sameSong', 'artist4']
    print('| run | set | ' + ' | '.join(cols) + ' | hubArtist |'); print('|' + '---|' * (len(cols) + 3))
    for d in sys.argv[1:]:
        r = metrics(d, lib, eg, {'warm': warm, 'cold': cold})
        (Path(d) / 'night_metrics.json').write_text(json.dumps(r, indent=1, ensure_ascii=False) + '\n')
        for k, v in r.items():
            print(f'| {Path(d).name} | {k} | ' + ' | '.join(str(v.get(c, '')) for c in cols) + f" | {v.get('hubArtist', '')} |")


if __name__ == '__main__':
    main()
