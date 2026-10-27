"""PLAN_SIZE: smaller versions of the shipped SMART artist tables, written from the shipped files themselves.

    python3 shrink_tables.py lfm ARTIST_LFM.bin OUT.bin [--floor N --pop ARTIST_POP.bin]     # int8 + f32 scale per vector
    python3 shrink_tables.py colisten ARTIST_COLISTEN.bin OUT.bin [--floor N --pop ARTIST_POP.bin] [--k K]

Formats are the ones core/smart reads (text/ArtistVectors.kt element width 1, text/ArtistNeighbours.kt unchanged).
Shipped in 0.9.3 (PLAN_SIZE, tools/research/listenbrainz_eval/PLAN_SIZE.md on the research branch): `lfm` with no floor,
`colisten --floor 50 --pop artist_pop.bin`. A floor of N removes the artists with fewer than N public listeners
(artist_pop.bin's ln(1 + listeners); an artist missing there has fewer than its own floor and goes too).
"""
import argparse, hashlib, struct
from pathlib import Path
import numpy as np

def presence(raw, at, entities):
    words = (entities + 63) // 64
    bits = np.unpackbits(np.frombuffer(raw, np.uint8, words * 8, at), bitorder='little')[:entities]
    return np.nonzero(bits)[0], at + words * 8 + ((entities + 511) // 512 + 1) * 4


def bitmap(ids, entities):
    """Presence words + running counts per 512 ids plus the total, as the readers check them."""
    words = (entities + 63) // 64
    bits = np.zeros(words * 64, np.uint8); bits[ids] = 1
    packed = np.packbits(bits, bitorder='little')
    per_word = np.unpackbits(packed).reshape(words, 64).sum(1)
    blocks = (entities + 511) // 512 + 1
    ranks = np.array([per_word[:b * 8].sum() for b in range(blocks)], np.uint32)
    ranks[-1] = len(ids)
    return packed.tobytes() + ranks.astype('<u4').tobytes()


def read_vectors(path, magic=b'LJVECS1\0'):
    raw = Path(path).read_bytes(); assert raw[:8] == magic
    _, entities, count = struct.unpack_from('<III', raw, 8); dim, width = struct.unpack_from('<HH', raw, 20)
    assert width == 4
    ids, at = presence(raw, 24, entities)
    return entities, ids, np.frombuffer(raw, '<f4', count * dim, at).reshape(count, dim)


def pop_floor(pop_path, floor):
    entities, ids, v = read_vectors(pop_path, b'LJPOPS1\0')
    return set(ids[v[:, 0] >= np.log1p(floor)].tolist())


def lfm(a):
    entities, ids, v = read_vectors(a.src)
    if a.floor:
        keep = pop_floor(a.pop, a.floor); m = np.array([i in keep for i in ids]); ids, v = ids[m], v[m]
    scale = np.abs(v).max(1) / 127.0
    scale[scale == 0] = 1.0
    q = np.clip(np.rint(v / scale[:, None]), -127, 127).astype(np.int8)
    raw = b'LJVECS1\0' + struct.pack('<IIIHH', 1, entities, len(ids), v.shape[1], 1) + bitmap(ids, entities) \
        + q.tobytes() + scale.astype('<f4').tobytes()
    deq = q.astype(np.float32) * scale.astype(np.float32)[:, None]
    cos = (deq * v).sum(1) / np.linalg.norm(deq, axis=1) / np.linalg.norm(v, axis=1)
    return raw, f'{len(ids)} vectors, int8; reconstruction cosine min {cos.min():.5f} median {np.median(cos):.6f}'


def colisten(a):
    raw = Path(a.src).read_bytes(); assert raw[:8] == b'LJNBRS1\0'
    _, entities, lists = struct.unpack_from('<III', raw, 8)
    ids, at = presence(raw, 22, entities)
    off = np.frombuffer(raw, '<u4', lists + 1, at); ent = np.frombuffer(raw, np.uint8, offset=at + (lists + 1) * 4).reshape(-1, 4)
    keep = pop_floor(a.pop, a.floor) if a.floor else None
    out_ids, offsets, entries = [], [0], []
    for i, e in enumerate(ids.tolist()):
        if keep is not None and e not in keep: continue
        rows = ent[off[i]:off[i + 1]]
        if keep is not None:
            rows = rows[[int(r[0]) | int(r[1]) << 8 | int(r[2]) << 16 in keep for r in rows]] if len(rows) else rows
        rows = rows[:a.k]
        if not len(rows): continue
        out_ids.append(e); entries.append(rows.tobytes()); offsets.append(offsets[-1] + len(rows))
    raw2 = b'LJNBRS1\0' + struct.pack('<IIIH', 1, entities, len(out_ids), 4) + bitmap(np.array(out_ids, np.int64), entities) \
        + np.array(offsets, '<u4').tobytes() + b''.join(entries)
    return raw2, f'{len(out_ids)} lists, {offsets[-1]} neighbours (floor {a.floor}, k {a.k})'


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('kind', choices=('lfm', 'colisten')); ap.add_argument('src'); ap.add_argument('out')
    ap.add_argument('--floor', type=int, default=0); ap.add_argument('--pop'); ap.add_argument('--k', type=int, default=1 << 30)
    a = ap.parse_args()
    raw, note = {'lfm': lfm, 'colisten': colisten}[a.kind](a)
    Path(a.out).write_bytes(raw)
    print(f'{a.out}: {len(raw):,} B, sha256 {hashlib.sha256(raw).hexdigest()}; {note}; '
          f'source sha256 {hashlib.sha256(Path(a.src).read_bytes()).hexdigest()}')
