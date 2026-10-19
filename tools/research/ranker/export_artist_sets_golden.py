"""Golden cases for the app's artist-set port (ArtistSets.kt) from artist_sets.py (230a6214).

    python3 export_artist_sets_golden.py RESEARCH_DIR FIT_DIR OUT.tsv.gz [N_REAL]

Three blocks, keys as artist_sets.key_hash (signed 64-bit decimal):
  list   — test_artist_sets.TEST_LIST's known mapping (match key hash, canonical key hash)
  case   — test_artist_sets.CASES without MBIDs (the device reads none), parsed against TEST_LIST
  real   — N_REAL distinct FIT library credits (artist, title; every credit with a separator or feat. token first, then
           a salted sample of the rest) parsed against the shipped known list (artist_sets.default_known())
  norm   — normalize() of tricky strings (diacritics, Cyrillic, CJK, Arabic, Turkish İ, full-width, emoji)
"""
import gzip, hashlib, json, sys
from pathlib import Path

research, fit, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]); n_real = int(sys.argv[4]) if len(sys.argv) > 4 else 3000
sys.path.insert(0, str(research))
import artist_sets as A, test_artist_sets as T  # noqa: E402

def s64(h): return h - (1 << 64) if h >= 1 << 63 else h
def keys(xs): return ','.join(str(s64(A.key_hash(k))) for k in xs)
def esc(s): return s.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n')

lines = []
tl = A.KnownArtists.from_list(T.TEST_LIST)
for k, v in sorted(tl.names.items()):
    lines.append(f'list\t{s64(A.key_hash(k))}\t{s64(A.key_hash(v))}')
for c in T.CASES:
    artist, title, mbids = c[0], c[1], c[2]
    if mbids: continue
    s = A.parse(artist, title, known=tl)
    lines.append('\t'.join(['case', esc(artist), esc(title), keys(s.artists), keys(sorted(s.featured, key=s.artists.index))]))
known = A.default_known()
credits = {}
for ld in sorted(Path(l) for l in (fit / 'manifest.txt').read_text().split() if l):
    for row in (ld / 'meta.tsv').read_text(encoding='utf-8').split('\n'):
        c = row.split('\t')
        if len(c) > 1: credits.setdefault((c[1], c[0]), None)
    if len(credits) > 400_000: break
hard = [k for k in credits if A.FEAT.search(k[0]) or A.COND.search(k[0]) or A.TITLE.search(k[1])]
hard_set = set(hard)
rest = sorted((k for k in credits if k not in hard_set), key=lambda k: hashlib.sha256(('as-golden|' + k[0] + '|' + k[1]).encode()).digest())
hard = sorted(hard, key=lambda k: hashlib.sha256(('as-golden|' + k[0] + '|' + k[1]).encode()).digest())
chosen = hard[: n_real * 2 // 3] + rest[: n_real - min(len(hard), n_real * 2 // 3)]
for artist, title in chosen:
    s = A.parse(artist, title, known=known)
    lines.append('\t'.join(['real', esc(artist), esc(title), keys(s.artists), keys(sorted(s.featured, key=s.artists.index))]))
tricky = ['Beyoncé', 'Ёлка', 'Mötley Crüe', 'Sigur Rós', 'The Beatles', 'the', 'The The', '米津玄師', 'ＹＯＡＳＯＢＩ',
          'Fairuz فيروز', 'İzel', 'DIŞ', 'Ölüm', 'Straße', 'Æon', 'Øystein', 'Łódź', 'Đorđe', 'Þór', 'AC/DC', 'Jay‐Z',
          'Ninho 🎵', 'B\u0308jörk', 'Kendrick  Lamar\t', 'हनी सिंह', 'Ψυχή', 'ΣΟΦΟΣ', 'Rǫnd', '𠀀𠀁 artist', 'ℌ𝔢𝔩𝔩𝔬', '①②']
for t in tricky:
    lines.append('\t'.join(['norm', esc(t), esc(A.normalize(t))]))
with gzip.open(out, 'wt', encoding='utf-8', compresslevel=9) as f: f.write('\n'.join(lines) + '\n')
print(json.dumps({'list': len(tl.names), 'cases': sum(l.startswith('case') for l in lines), 'real': len(chosen),
                  'real_hard': min(len(hard), n_real * 2 // 3), 'norm': len(tricky), 'bytes': out.stat().st_size}))
