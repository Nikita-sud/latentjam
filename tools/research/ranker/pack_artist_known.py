"""Write artist_sets.py's known-artist list as the app asset read by core/smart text/ArtistSets.kt (KnownArtistTable).

    python3 pack_artist_known.py ARTIST_KNOWN.tsv OUT.bin

ARTIST_KNOWN.tsv is `python3 artist_sets.py export` on claude/smart-stage0-eval-f2a727 (230a6214): `n<TAB>match<TAB>canonical`
rows (the `m` MBID rows are not shipped: the device reads no artist MBIDs). Keys are artist_sets.key_hash (FNV-1a 64 of
the UTF-8 key). Layout "LJARTK1": u32 version | u32 canonical count | u32 alias count | canonical keys (sorted unsigned
u64: every match key that is its own canonical key) | aliases (sorted u64 key + u32 index of its canonical key).
"""
import hashlib, struct, sys
from pathlib import Path


def key_hash(k):
    if not k: return 0
    h = 0xCBF29CE484222325
    for b in k.encode('utf-8'): h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


canon, alias = set(), {}
for line in Path(sys.argv[1]).read_text(encoding='utf-8').splitlines():
    t, m, c = line.split('\t')
    if t != 'n': continue
    if m == c: canon.add(key_hash(m))
    else: alias[key_hash(m)] = key_hash(c)
for c in alias.values(): canon.add(c)  # a canonical key reached only through aliases still resolves to itself
keys = sorted(canon); index = {k: i for i, k in enumerate(keys)}
pairs = sorted((a, index[c]) for a, c in alias.items() if a not in canon)
raw = b'LJARTK1\0' + struct.pack('<III', 1, len(keys), len(pairs)) + b''.join(struct.pack('<Q', k) for k in keys) \
    + b''.join(struct.pack('<QI', a, i) for a, i in pairs)
Path(sys.argv[2]).write_bytes(raw)
print(f'{sys.argv[2]}: {len(keys)} canonical keys, {len(pairs)} aliases, {len(raw):,} B, sha256 {hashlib.sha256(raw).hexdigest()}, '
      f'source sha256 {hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()}')
