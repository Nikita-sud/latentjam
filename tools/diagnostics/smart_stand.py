#!/usr/bin/env python3
"""Offline PRODUCT-CHECK stand: the phone's own SMART chooser + ranker (composeApp SmartStandRunner, the assets this
checkout ships) planning queues from a copy of the owner's phone state.

L1 product check only: it shows whether the owner would HEAR a problem. Its numbers never feed fitting, choosing or
gates (fixes are chosen on FIT / FMA). The owner's data stays on this machine; nothing of it goes into git.

    python3 tools/diagnostics/smart_stand.py run  [--state DIR] [--build-id ID]
    python3 tools/diagnostics/smart_stand.py diff BUILD_A BUILD_B
    python3 tools/diagnostics/smart_stand.py fidelity [--since EPOCH_MS]

run:  prepares the library from STATE (default: the newest ~/Documents/LJ/phone-state/<copy> with files/), fixes the seed
      set once (~/Documents/LJ/phone-state/stand/seeds.tsv, ~150 stratified seeds + the known problem seeds; never
      re-drawn, so builds compare on the same seeds), runs the JVM stand (CORE and FULL, three presses 10 min apart,
      40-row queues with the controllers' top-ups) and writes ~/Documents/LJ/phone-state/stand/<build-id>/
      queues.md, metrics.md and metrics.tsv.
diff: side-by-side metrics and queues of two builds on the same seeds -> stand/diff-<A>-vs-<B>.md.
fidelity: replays the copy's logged presses of the shipped models (phone log, history and press count at that moment)
      and counts exact positions -> stand/fidelity.txt. The stand is only trusted when this is exact.
"""
import argparse, collections, datetime, hashlib, json, math, os, random, re, subprocess, sys, unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STATE_HOME = Path.home() / 'Documents/LJ/phone-state'
STAND = STATE_HOME / 'stand'
SEEDS = STAND / 'seeds.tsv'
PROBLEM_SEEDS = [('Nelly', 'Dilemma'), ('Mountain Realm', ''), ('Lady Gaga', 'Bad Romance'), ('Nightcrawlers', 'Push the Feeling On')]
FAMILY_BUCKETS = ['rap', 'rnb', 'pop', 'rock', 'dance', 'soundtrack', 'classical', 'other']
KIDS = re.compile(r'kids|children|детск|nursery|lullab|chipmunk|disney|cartoon|мульт', re.I)
OST = re.compile(r'soundtrack|\bost\b|score|game|anime|film|video game|musical', re.I)
sys.path.insert(0, str(Path(__file__).resolve().parent))
import smart_queues as SQ    # MediaStore parsing, content keys


def newest_state():
    copies = sorted(p for p in STATE_HOME.glob('*/') if (p / 'files/smart_index.bin').is_file())
    if not copies: raise SystemExit(f'no phone-state copy with files/ under {STATE_HOME}')
    return copies[-1]


def clean(v):
    if v is None or v == '<unknown>' or not v.strip(): return ''
    return v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')


def prepare(state, work):
    """library.tsv in SmartStandRunner's columns: what the phone's MediaStore scan gives (IS_MUSIC, minus hidden and
    SMART-excluded tracks)."""
    media = SQ.parse_mediastore(state / 'mediastore.txt')
    sizes = SQ.sizes_from_identities(state / 'files/track-identities-v1.txt')
    revisions = revisions_from_identities(state / 'files/track-identities-v1.txt')
    hidden = set()
    for name in ('hidden_tracks.txt',):
        p = state / 'files' / name
        if p.is_file(): hidden |= {l.strip() for l in p.read_text().splitlines() if l.strip()}
    p = state / 'files/smart_exclusions.txt'
    if p.is_file():
        for l in p.read_text().splitlines():
            if l.startswith('T:'):
                try: hidden.add(bytes.fromhex(l[2:].strip()).decode())
                except ValueError: pass
    rows = []
    for mid, r in media.items():
        if (r.get('is_music') or '0') == '0' or mid in hidden: continue
        data = r.get('_data') or ''
        rel = data[len(SQ.STORAGE_ROOT):] if data.startswith(SQ.STORAGE_ROOT) else data.lstrip('/')
        folder, _, name = rel.rpartition('/')
        size = int(r['_size']) if (r.get('_size') or '').isdigit() else sizes.get(mid)
        year = r.get('year') if (r.get('year') or '').isdigit() and int(r['year']) > 0 else ''
        dur = r.get('duration') if (r.get('duration') or '').isdigit() and int(r['duration']) > 0 else ''
        rows.append('\t'.join([mid, clean(r.get('title')), clean(r.get('artist')), clean(r.get('album')),
                               clean(r.get('album_artist')), clean(r.get('genre')), dur, year, str(size or ''),
                               folder, name, (r.get('volume_name') or 'external_primary').lower(), revisions.get(mid, '')]))
    work.mkdir(parents=True, exist_ok=True)
    (work / 'library.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8')
    # GenreEnrichment's cache (the tag facts MediaStore loses), as the app stored it in its settings.
    import xml.etree.ElementTree as ET
    prefs = state / 'shared_prefs/app_settings.xml'
    payload = ''
    if prefs.is_file():
        for e in ET.parse(prefs).getroot():
            if e.get('name') == 'track_genres_v1': payload = e.text or ''
    (work / 'track_genres.txt').write_text(payload, encoding='utf-8')
    link = work / 'files'
    if link.is_symlink() or link.exists(): link.unlink()
    link.symlink_to((state / 'files').resolve())
    return {r.split('\t')[0]: r.split('\t') for r in rows}


def revisions_from_identities(path):
    """media id → the android-mediastore-v1:<size>:<mtime>:<generation> source revision the app's descriptors carry."""
    out = {}
    p = Path(path)
    if not p.is_file(): return out
    for line in p.read_text().splitlines()[2:]:
        f = line.split('|')
        try: out[bytes.fromhex(f[0]).decode()] = bytes.fromhex(f[5]).decode()
        except (ValueError, IndexError): pass
    return out


def script(s):
    s = s or ''
    if re.search(r'[Ѐ-ӿ]', s): return 'cyrillic'
    if re.search(r'[぀-ヿ一-鿿가-힯]', s): return 'cjk'
    if re.search(r'[A-Za-z]', s): return 'latin'
    return 'other'


def choose_seeds(lib, facts):
    """~150 seeds, stratified by family bucket × energy tercile, with script and tagged/untagged spread; plus the
    problem seeds. Salted, fixed once (seeds.tsv), so every build is compared on the same list."""
    rng = random.Random(20261020)
    es = sorted(float(f['e']) for f in facts.values() if f['e'])
    t1, t2 = (es[len(es) // 3], es[2 * len(es) // 3]) if es else (0, 0)
    cells = collections.defaultdict(list)
    for tid, row in lib.items():
        f = facts.get(tid)
        if not f or f['hasVector'] != 'true': continue
        fam = f['family'] if f['family'] in FAMILY_BUCKETS else 'other'
        e = float(f['e']) if f['e'] else None
        terc = 'unknown' if e is None else ('low' if e < t1 else 'mid' if e < t2 else 'high')
        cells[(fam, terc)].append(tid)
    chosen = []
    for key in sorted(cells):
        ids = sorted(cells[key])
        rng.shuffle(ids)
        # Spread inside the cell: alternate scripts and tagged / untagged first.
        ids.sort(key=lambda t: (script(lib[t][1] + lib[t][2]) == 'latin', bool(lib[t][5])))
        chosen += ids[:6]
    rng.shuffle(chosen)
    chosen = chosen[:150]
    for artist, title in PROBLEM_SEEDS:
        for tid, row in sorted(lib.items()):
            if artist.lower() in row[2].lower() and title.lower() in row[1].lower():
                if tid not in chosen: chosen.append(tid)
                break
    return chosen


def build_id():
    sha = subprocess.run(['git', 'rev-parse', '--short=8', 'HEAD'], cwd=REPO, capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(['git', 'status', '--porcelain', '--', 'core', 'composeApp', 'androidApp/src/main/assets'],
                           cwd=REPO, capture_output=True, text=True).stdout.strip()
    ml = REPO / 'androidApp/src/main/assets/ml'
    models = ''.join(hashlib.sha256((ml / n).read_bytes()).hexdigest()[:4] for n in ('smart_ranker.json', 'smart_ranker_full.json'))
    return f"{sha}{'-dirty' if dirty else ''}-m{models}"


def read_facts(path):
    lines = Path(path).read_text().splitlines()
    head = lines[0].split('\t')
    return {l.split('\t')[0]: dict(zip(head, l.split('\t'))) for l in lines[1:] if l}


def run(args):
    state = Path(args.state) if args.state else newest_state()
    bid = args.build_id or build_id()
    out = STAND / bid
    work = STAND / 'input'
    lib = prepare(state, work)
    if not SEEDS.is_file():
        # A facts-only pass (no seeds) gives the families and energies the seed set is stratified by.
        (work / 'seeds.tsv').write_text('')
        gradle(work, STAND / '_facts')
        facts = read_facts(STAND / '_facts/facts.tsv')
        SEEDS.write_text('\n'.join(choose_seeds(lib, facts)) + '\n')
        print(f'seed set fixed: {SEEDS} ({len(SEEDS.read_text().split())} seeds)')
    (work / 'seeds.tsv').write_text(SEEDS.read_text())
    gradle(work, out)
    (out / 'state.txt').write_text(f'state {state}\nbuild {bid}\nran {datetime.datetime.now().isoformat(timespec="seconds")}\n')
    report(out, lib)
    print(f'stand -> {out}')


def gradle(work, out):
    env = dict(os.environ, LJ_STAND_DIR=str(work), LJ_STAND_OUT=str(out))
    r = subprocess.run(['./gradlew', '--no-daemon', '--offline', '-q', ':composeApp:testAndroidHostTest',
                        '--tests', '*SmartStandRunner*', '--rerun'], cwd=REPO, env=env)
    if r.returncode != 0: raise SystemExit('stand runner failed (see the gradle output above)')


def metrics_of(q, lib, facts, seed):
    picks = [p['id'] for p in q['picks']]
    rows = [lib.get(i) for i in picks]
    artist = lambda r: (r[2] or '').lower().split(' feat')[0].split(' ft.')[0].split(',')[0].split(' & ')[0].strip() if r else ''
    arts = [artist(r) for r in rows]
    cnt = collections.Counter(a for a in arts if a)
    share = max(cnt.values()) / len(arts) if cnt and arts else 0.0
    axa = sum(1 for i in range(2, len(arts)) if arts[i] and arts[i] == arts[i - 2] and arts[i - 1] != arts[i])
    sf = (facts.get(seed) or {}).get('family') or ''
    def keep(a, b):
        seg = picks[a:b]
        if not seg or not sf: return float('nan')
        return sum(1 for i in seg if (facts.get(i) or {}).get('family') == sf) / len(seg)
    se = (facts.get(seed) or {}).get('e')
    es = [float((facts.get(i) or {}).get('e')) for i in picks if (facts.get(i) or {}).get('e')]
    drift = (sum(es[-10:]) / len(es[-10:]) - float(se)) if (se and len(es) >= 10) else float('nan')
    titles = [re.sub(r'\s*[\(\[].*?[\)\]]', '', (r[1] if r else '')).strip().lower() for r in rows]
    same_song = len(titles) - len(set(t for t in titles if t)) - sum(1 for t in titles if not t)
    durs = [int(r[6]) for r in rows if r and r[6]]
    short = sum(1 for d in durs if d < 90_000)
    long_ = sum(1 for d in durs if d > 10 * 60_000)
    seed_row = lib.get(seed)
    seed_ost = bool(seed_row and (OST.search(seed_row[5] or '') or KIDS.search((seed_row[3] or '') + (seed_row[5] or ''))))
    ost = 0 if seed_ost else sum(1 for r in rows if r and (OST.search(r[5] or '') or KIDS.search((r[3] or '') + ' ' + (r[5] or ''))))
    return {'n': len(picks), 'maxArtistShare': share, 'AxA': axa, 'keep1_5': keep(0, 5), 'keep8_20': keep(7, 20),
            'keep21_40': keep(20, 40), 'energyDrift': drift, 'sameSong': same_song, 'short': short, 'long': long_,
            'ostInNonOst': ost}


def report(out, lib):
    facts = read_facts(out / 'facts.tsv')
    queues = [json.loads(l) for l in (out / 'queues.jsonl').read_text().splitlines() if l]
    by = collections.defaultdict(dict)
    for q in queues: by[(q['seed'], q['mode'])][q['press']] = q
    def name(i):
        r = lib.get(i); return f"{r[2] or '?'} – {r[1] or '?'}" if r else i
    md = [f'# SMART stand — {out.name}', '', 'L1 product check only (what the owner would hear); never fitting / choosing /',
          'gate input. Local; not in git.', '', (out / 'build.txt').read_text().replace('\n', ' · '), '']
    tsv = ['seed\tmode\tpress\t' + '\t'.join(['n', 'maxArtistShare', 'AxA', 'keep1_5', 'keep8_20', 'keep21_40',
                                                'energyDrift', 'sameSong', 'short', 'long', 'ostInNonOst', 'repressOverlap'])]
    agg = collections.defaultdict(lambda: collections.defaultdict(list))
    for (seed, mode), presses in sorted(by.items(), key=lambda kv: (name(kv[0][0]), kv[0][1])):
        sf = (facts.get(seed) or {}).get('family') or '—'
        md += [f'## {name(seed)} · {mode} · family {sf} · e {(facts.get(seed) or {}).get("e") or "?"}', '']
        first = [p['id'] for p in presses.get(0, {'picks': []})['picks']][:20]
        for k in sorted(presses):
            q = presses[k]
            m = metrics_of(q, lib, facts, seed)
            ids = [p['id'] for p in q['picks']]
            m['repressOverlap'] = (len(set(ids[:20]) & set(first)) / 20) if k > 0 else float('nan')
            for key, v in m.items():
                if not (isinstance(v, float) and math.isnan(v)): agg[(mode, k)][key].append(v)
            tsv.append(f'{seed}\t{mode}\t{k}\t' + '\t'.join(f'{m[c]:.3f}' if isinstance(m[c], float) else str(m[c])
                                                          for c in tsv[0].split('\t')[3:]))
            md.append(f"**press {k}** (`{q['model']}`) · artist share {m['maxArtistShare']:.2f} · A-x-A {m['AxA']} · "
                      f"keep {m['keep1_5']:.2f}/{m['keep8_20']:.2f}/{m['keep21_40']:.2f} · e drift {m['energyDrift']:+.3f} · "
                      f"same song {m['sameSong']} · short {m['short']} long {m['long']} · OST {m['ostInNonOst']}"
                      + (f" · re-press overlap {m['repressOverlap']:.2f}" if k else ''))
            md.append('')
            md += [f"{i + 1}. {name(p['id'])} · {(facts.get(p['id']) or {}).get('family') or '—'} · {p['source']}"
                   + (' · top-up' if str(p.get('plan')) not in ('0', '?') else '') for i, p in enumerate(q['picks'])]
            md.append('')
    (out / 'queues.md').write_text('\n'.join(md) + '\n', encoding='utf-8')
    (out / 'metrics.tsv').write_text('\n'.join(tsv) + '\n')
    cols = ['maxArtistShare', 'AxA', 'keep1_5', 'keep8_20', 'keep21_40', 'energyDrift', 'sameSong', 'short', 'long',
            'ostInNonOst', 'repressOverlap']
    mm = [f'# Stand metrics — {out.name}', '', 'Means over seeds (L1 product check only).', '',
          '| mode | press | seeds | ' + ' | '.join(cols) + ' |', '|' + '---|' * (3 + len(cols))]
    for (mode, k), d in sorted(agg.items()):
        mm.append(f'| {mode} | {k} | {len(d["n"])} | ' + ' | '.join(
            (f'{sum(d[c]) / len(d[c]):.3f}' if d.get(c) else '—') for c in cols) + ' |')
    (out / 'metrics.md').write_text('\n'.join(mm) + '\n')
    print('\n'.join(mm))


def fidelity(args):
    """Replays the copy's logged presses planned by the models this checkout ships (same name), each with the phone's
    log, history and press count at that moment, and counts exact positions: the stand is trusted only when they match."""
    state = Path(args.state) if args.state else newest_state()
    work = STAND / 'input'
    lib = prepare(state, work)
    media = SQ.parse_mediastore(state / 'mediastore.txt')
    sizes = SQ.sizes_from_identities(state / 'files/track-identities-v1.txt')
    h2id = {SQ.content_hash(SQ.content_key(r, sizes.get(m))): m for m, r in media.items() if sizes.get(m)}
    seed_of = {h[:12]: m for h, m in h2id.items()}
    ml = REPO / 'androidApp/src/main/assets/ml'
    names = {json.loads((ml / n).read_text())['name'].replace('§', '_'): n for n in ('smart_ranker.json', 'smart_ranker_full.json')}
    queues = collections.OrderedDict()
    for l in (state / 'files/smart_impressions.log').read_text().splitlines():
        p = l.split('|')
        if p[0] != 'si1' or len(p) != 12 or p[3] != '0' or p[11] not in names: continue
        queues.setdefault(p[2], (p[11], []))[1].append((int(p[4]), h2id.get(p[5])))
    specs, logged = [], []
    for qid, (model, rows) in queues.items():
        seed_hash, press, start = qid.split('.')
        if seed_hash not in seed_of or int(start) < args.since: continue
        specs.append(f"{seed_of[seed_hash]}:{start}:{press}:{1 if names[model] == 'smart_ranker_full.json' else 0}")
        logged.append((qid, [t for _, t in sorted(rows)]))
    if not specs: raise SystemExit('no logged press of the shipped models in this copy')
    out = STAND / '_replay'
    for f in out.glob('replay-*.txt'): f.unlink()
    env = dict(os.environ, LJ_STAND_DIR=str(work), LJ_STAND_OUT=str(out), LJ_STAND_REPLAY=','.join(specs))
    subprocess.run(['./gradlew', '--no-daemon', '--offline', '-q', ':composeApp:testAndroidHostTest', '--tests',
                    '*SmartStandRunner*', '--rerun'], cwd=REPO, env=env, check=True)
    total = exact = 0
    lines = []
    for i, (qid, rows) in enumerate(logged):
        got = (out / f'replay-{i}.txt').read_text().split()
        same = sum(a == b for a, b in zip(rows, got))
        total += len(rows); exact += same
        lines.append(f'{qid}\t{same}/{len(rows)}')
    report_ = f'fidelity: {exact}/{total} positions over {len(logged)} logged presses of the shipped models'
    (STAND / 'fidelity.txt').write_text(report_ + '\n' + '\n'.join(lines) + '\n')
    print(report_)
    return exact, total


def diff(args):
    a, b = STAND / args.a, STAND / args.b
    lib = {l.split('\t')[0]: l.split('\t') for l in (STAND / 'input/library.tsv').read_text().splitlines() if l}
    def load(d): return {(q['seed'], q['mode'], q['press']): q for q in (json.loads(l) for l in (d / 'queues.jsonl').read_text().splitlines() if l)}
    qa, qb = load(a), load(b)
    name = lambda i: (f"{lib[i][2]} – {lib[i][1]}" if i in lib else i)
    md = [f'# Stand diff: {args.a} vs {args.b}', '', (a / 'metrics.md').read_text(), (b / 'metrics.md').read_text(), '## Queues that changed', '']
    changed = 0
    for key in sorted(set(qa) & set(qb)):
        ia = [p['id'] for p in qa[key]['picks']]; ib = [p['id'] for p in qb[key]['picks']]
        if ia == ib: continue
        changed += 1
        md += [f'### {name(key[0])} · {key[1]} · press {key[2]} (same {sum(x == y for x, y in zip(ia, ib))}/{len(ia)})', '',
               '| # | ' + args.a + ' | ' + args.b + ' |', '|---|---|---|']
        md += [f'| {i + 1} | {name(x)} | {name(y)} |' for i, (x, y) in enumerate(zip(ia, ib))]
        md.append('')
    md.insert(2, f'{changed} of {len(set(qa) & set(qb))} queues differ.')
    out = STAND / f'diff-{args.a}-vs-{args.b}.md'
    out.write_text('\n'.join(md) + '\n', encoding='utf-8')
    print(f'{changed} queues differ -> {out}')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('run'); r.add_argument('--state'); r.add_argument('--build-id')
    d = sub.add_parser('diff'); d.add_argument('a'); d.add_argument('b')
    f = sub.add_parser('fidelity'); f.add_argument('--state')
    f.add_argument('--since', type=int, default=0, help='only presses at or after this epoch ms (the build install time: '
                   'earlier builds may share the model name)')
    a = ap.parse_args()
    {'run': run, 'diff': diff, 'fidelity': fidelity}[a.cmd](a)


if __name__ == '__main__':
    main()
