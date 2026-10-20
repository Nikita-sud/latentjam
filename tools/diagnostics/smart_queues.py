#!/usr/bin/env python3
"""Turn the phone's SMART impression log into readable queues (product checks and mechanism diagnostics only).

The owner's data stays on this machine: it is never training data or evaluation evidence.

    python3 tools/diagnostics/smart_queues.py report STATE_DIR [--out FILE]
    python3 tools/diagnostics/smart_queues.py refresh [--serial R5CWC1VJ12R] [--out FILE]

STATE_DIR holds the impression log and the phone's MediaStore listing. Either form works:
  - files/smart_impressions.log, files/track-identities-v1.txt and mediastore.txt (a copy of the app's private files);
  - smart_log.txt (the app's "Export SMART log (test)" file) and mediastore.txt (with _size: see `refresh`).

`refresh` needs no debuggable build. It
  1. reads MediaStore over adb (`content query`, including _size, so the content keys can be rebuilt);
  2. takes the newest latentjam-smart-log-*.txt the owner exported to Download (Settings → SMART engine → Export SMART
     log (test)); without one it reuses the last copy and says so;
  3. writes ~/Documents/LJ/phone-state/<date-time>/ and the report.

Tracks are named in the log by SaltedKey.contentHash: the first 8 bytes of SHA-256 of
"v1|<volume>|<relative path>|<size bytes>|<duration ms>" (SaltedKey.contentKey).
"""
import argparse, datetime, hashlib, os, re, subprocess, sys, unicodedata
from collections import OrderedDict, defaultdict
from pathlib import Path

HOME_STATE = Path.home() / 'Documents/LJ/phone-state'
PACKAGE = 'io.github.nikitasud.latentjam.kmp'
STORAGE_ROOT = '/storage/emulated/0/'
PROJECTION = ['_id', 'title', 'artist', 'album', 'album_artist', 'genre', 'duration', 'year', '_size', '_data',
              'volume_name', 'is_music']
SHOWN = 20

# The ranker's L2 families: the asset's taxonomy, when the repo is next to this tool (shown beside each pick).
REPO = Path(__file__).resolve().parents[2]


def parse_mediastore(path):
    """`adb shell content query` rows → {media id: {column: value}}. Splits on the known column names only, so commas
    inside titles survive."""
    rows = {}
    text = Path(path).read_text(encoding='utf-8', errors='replace')
    for line in text.splitlines():
        if not line.startswith('Row: '): continue
        body = line.split(' ', 2)[2] if line.count(' ') >= 2 else ''
        parts = re.split(r', (?=(?:' + '|'.join(PROJECTION) + r')=)', body)
        r = {}
        for p in parts:
            k, _, v = p.partition('=')
            r[k] = None if v == 'NULL' else v
        if r.get('_id'): rows[r['_id']] = r
    return rows


def sizes_from_identities(path):
    """track-identities-v1.txt (hex fields): media id → size bytes, from its android-mediastore-v1:<size>:… identity."""
    out = {}
    p = Path(path)
    if not p.is_file(): return out
    for line in p.read_text().splitlines()[2:]:
        f = line.split('|')
        try:
            mid = bytes.fromhex(f[0]).decode()
            ident = bytes.fromhex(f[5]).decode()
        except (ValueError, IndexError):
            continue
        m = re.match(r'android-mediastore-v1:(\d+):', ident)
        if m: out[mid] = int(m.group(1))
    return out


def content_key(r, size):
    data = r.get('_data') or ''
    rel = data[len(STORAGE_ROOT):] if data.startswith(STORAGE_ROOT) else data.lstrip('/')
    rel = unicodedata.normalize('NFC', rel.replace('\\', '/'))
    volume = r.get('volume_name') or 'external_primary'
    duration = int(r['duration']) if (r.get('duration') or '').isdigit() and int(r['duration']) > 0 else 0
    return f"v1|{volume}|{rel}|{max(0, size or 0)}|{duration}"


def content_hash(key):
    return hashlib.sha256(key.encode('utf-8')).digest()[:8].hex()


def taxonomy():
    """The L2 track family (taxonomy v2.1) of a genre tag, or None when the asset or its module is not at hand."""
    sys.path.insert(0, str(REPO / 'tools/diagnostics'))
    try:
        import json
        g = json.loads((REPO / 'androidApp/src/main/assets/ml/genre_families.json').read_text())
    except OSError:
        return lambda tag: None
    phrases = [(p.split(), f) for p, f in g['phrases']]
    def tokens(s):
        s = ''.join(c for c in unicodedata.normalize('NFKD', s or '') if not unicodedata.combining(c))
        s = s.lower().replace('&', ' and ').replace("'", '')
        return ''.join(c if c.isalnum() else ' ' for c in s).split()
    def family(genre):
        s = (genre or '').strip()
        m = re.fullmatch(r'\((\d{1,3})\)(.*)', s) or re.fullmatch(r'(\d{1,3})()', s)
        if m and int(m.group(1)) < len(g['id3v1']): s = m.group(2).strip() or g['id3v1'][int(m.group(1))]
        t = tokens(s)
        if not t: return None
        key = ' '.join(t)
        if key in g['overrides']: return g['overrides'][key]
        if key in g['mb_family']: return g['mb_family'][key]
        best = None
        for p, f in phrases:
            n = len(p)
            for i in range(len(t) - n + 1):
                hit = (t[i:i + n - 1] == p[:-1] and t[i + n - 1].startswith('symphon')) if p[-1].endswith('symphon') else t[i:i + n] == p
                if hit and (best is None or (n, i + n) > best[:2]): best = (n, i + n, f)
        if best: return best[2]
        for w in t:
            for suf, f in g['suffixes']:
                if w.endswith(suf) and len(w) > len(suf): return f
        return g['tree'].get(key)
    def of_tag(tag):
        for part in re.split(r'[;/,|]', tag or ''):
            f = family(part.strip())
            if f is not None: return f
        return None
    return of_tag


def read_log(state):
    for name in ('files/smart_impressions.log', 'smart_log.txt'):
        p = Path(state) / name
        if p.is_file(): return p.read_text(encoding='utf-8', errors='replace').splitlines(), p
    raise SystemExit(f'no impression log in {state} (files/smart_impressions.log or smart_log.txt)')


def report(state, out):
    state = Path(state)
    media = parse_mediastore(state / 'mediastore.txt')
    sizes = sizes_from_identities(state / 'files/track-identities-v1.txt')
    by_hash = {}
    for mid, r in media.items():
        size = int(r['_size']) if (r.get('_size') or '').isdigit() else sizes.get(mid)
        if size is None: continue
        by_hash[content_hash(content_key(r, size))] = r
    fam = taxonomy()
    lines, source = read_log(state)
    queues = OrderedDict()
    outcomes = {}
    for line in lines:
        p = line.split('|')
        if p[0] == 'si1' and len(p) == 12:
            q = queues.setdefault(p[2], {'rows': [], 'model': p[11], 'at': int(p[1])})
            q['rows'].append({'plan': int(p[3]), 'hop': int(p[4]), 'track': p[5], 'rank': int(p[6]), 'score': p[7],
                              'p': p[8], 'tie': p[9] == '1', 'source': p[10]})
        elif p[0] == 'so1' and len(p) == 9:
            outcomes[(p[2], int(p[3]), int(p[4]), p[5])] = (p[6], p[7], p[8])
    seed_by_prefix = {h[:12]: r for h, r in by_hash.items()}

    def name(r):
        if r is None: return '(not in the MediaStore listing)'
        return f"{r.get('artist') or '?'} – {r.get('title') or '?'}"

    known = sum(1 for q in queues.values() for row in q['rows'] if row['track'] in by_hash)
    total = sum(len(q['rows']) for q in queues.values())
    md = [f"# SMART queues from the phone's log",
          '',
          f"Source: `{source}` · MediaStore rows {len(media)} · tracks named {known}/{total} · generated "
          f"{datetime.datetime.now().strftime('%Y-%m-%d %H:%M')}.",
          '',
          'Product checks and mechanism diagnostics only: never training data or evidence. Local file.',
          '',
          '*family* = the L2 taxonomy on MediaStore\'s genre (the app reads the file\'s full genre tag, so it can differ); '
          '*from* = the retriever (R1 audio, R2 co-listening, R4 artist / album, R5 family, R6 favourites, R7 your '
          'transitions, fill / fill-family / topup, ladder:…); *p* = the draw\'s propensity (tie = shared near-best); '
          '*outcome* — = none logged yet (still queued, or history off).',
          '']
    for qid, q in sorted(queues.items(), key=lambda kv: kv[1]['at']):
        seed_hash, press, start = (qid.split('.') + ['', '', ''])[:3]
        rows = sorted([r for r in q['rows'] if r['plan'] == 0], key=lambda r: r['hop'])[:SHOWN]
        topups = len({r['plan'] for r in q['rows']}) - 1
        when = datetime.datetime.fromtimestamp(int(start) / 1000 if start.isdigit() else q['at'] / 1000)
        mode = 'chain' if q['model'] == 'chain' else ('FULL' if q['model'].startswith('FULL') else
                                                      'rule-only' if q['model'] == 'rule-only' else 'CORE')
        seed = seed_by_prefix.get(seed_hash)
        md += [f"## {when.strftime('%Y-%m-%d %H:%M:%S')} · {mode} · press {press} · {name(seed)}",
               '', f"Model `{q['model']}` · queue `{qid}` · {len(q['rows'])} rows logged ({topups} top-ups).", '',
               '| # | track | family | genre tag | from | p | outcome |', '|---|---|---|---|---|---|---|']
        for r in rows:
            t = by_hash.get(r['track'])
            o = outcomes.get((qid, r['plan'], r['hop'], r['track']))
            if o is None: oc = '—'
            elif o[0] == 'SKIPPED' and o[2]: oc = f"skipped at {float(o[2]) * 100:.0f} %"
            else: oc = o[0].lower()
            genre = (t or {}).get('genre') or ''
            md.append(f"| {r['hop']} | {name(t)} | {fam(genre) or ''} | {genre[:40]} | {r['source']} | "
                      f"{float(r['p']):.2f}{' tie' if r['tie'] else ''} | {oc} |")
        md.append('')
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_text('\n'.join(md) + '\n', encoding='utf-8')
    print(f"{len(queues)} queues, {known}/{total} rows named -> {out}")


def adb(serial, *args, binary=False):
    r = subprocess.run(['adb', '-s', serial, *args], capture_output=True)
    if r.returncode != 0: raise SystemExit(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout if binary else r.stdout.decode('utf-8', errors='replace')


def refresh(serial, out):
    state = HOME_STATE / datetime.datetime.now().strftime('%Y-%m-%d-%H%M')
    state.mkdir(parents=True, exist_ok=True)
    (state / 'mediastore.txt').write_text(
        adb(serial, 'shell', 'content', 'query', '--uri', 'content://media/external/audio/media',
            '--projection', ':'.join(PROJECTION)), encoding='utf-8')
    exports = [l.strip() for l in adb(serial, 'shell', 'ls', '-t', '/sdcard/Download/').splitlines()
               if l.strip().startswith('latentjam-smart-log-')]
    if exports:
        (state / 'smart_log.txt').write_bytes(adb(serial, 'exec-out', 'cat', f'/sdcard/Download/{exports[0]}', binary=True))
        print(f"log: /sdcard/Download/{exports[0]}")
    else:
        previous = sorted([p for p in HOME_STATE.glob('*/') if p != state and (
            (p / 'smart_log.txt').is_file() or (p / 'files/smart_impressions.log').is_file())])
        if not previous: raise SystemExit('no exported log on the phone: Settings → SMART engine → Export SMART log (test), save to Download')
        src = previous[-1] / 'smart_log.txt'
        if not src.is_file(): src = previous[-1] / 'files/smart_impressions.log'
        (state / 'smart_log.txt').write_bytes(src.read_bytes())
        print(f"no new export on the phone: reusing {src} (export again for presses after it)")
    diag = f'/sdcard/Android/data/{PACKAGE}/files/smart_diagnostics.jsonl'
    try:
        (state / 'smart_diagnostics.jsonl').write_bytes(adb(serial, 'exec-out', 'cat', diag, binary=True))
    except SystemExit:
        pass
    report(state, out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('report'); r.add_argument('state'); r.add_argument('--out', default=str(HOME_STATE / 'latest-queues.md'))
    f = sub.add_parser('refresh'); f.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'R5CWC1VJ12R'))
    f.add_argument('--out', default=str(HOME_STATE / 'latest-queues.md'))
    a = ap.parse_args()
    if a.cmd == 'report': report(a.state, a.out)
    else: refresh(a.serial, a.out)


if __name__ == '__main__':
    main()
