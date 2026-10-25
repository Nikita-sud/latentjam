#!/usr/bin/env python3
"""Map refresh gate (docs/map-cost-research-2026-10-25.md): public FMA / cc-audio libraries for MapGateReplay.

Pools the 16 general-stand libraries (~/Documents/LJ/genstand-2026-10-22/libs, built from the licence-clean
P2 FMA list plus cc-audio-v1; never the owner's library), drops duplicate source tracks, and writes random
libraries of the requested sizes in the layout MapScaleReplay.readNpy reads:

    <out>/fma-<n>/{smart_index_vecs.npy, smart_index_ids.txt, smart_text_vecs.npy, smart_text_ids.txt, labels.tsv}

labels.tsv: id, FMA top genre (empty when unknown), first sub-genre path element after the top (or the top).
Audio is the stand's A0 vector (960-d), text the shipped text encoder's vector from files/smart_text_index.bin.

    python3 -I tools/research/map_gate_fma_dump.py OUT_DIR 1000 5000 7989   (the pool has 7,989 tracks)
"""
import struct, sys, random
from pathlib import Path
import numpy as np

LIBS = Path.home() / 'Documents/LJ/genstand-2026-10-22/libs'


def read_ljix(path):
    b = Path(path).read_bytes()
    o = 0
    def i32():
        nonlocal o
        v = struct.unpack_from('>i', b, o)[0]; o += 4; return v
    def sized():
        nonlocal o
        n = i32()
        if n < 0: return None
        s = b[o:o + n].decode('utf-8'); o += n; return s
    magic, version = i32(), i32()
    assert magic == 0x4C4A4958 and version == 3, (magic, version)
    sized()
    dim, count = i32(), i32()
    out = {}
    for _ in range(count):
        tid = sized()
        v = np.frombuffer(b, dtype='>f4', count=dim, offset=o).astype(np.float32); o += 4 * dim
        sized()
        out[tid] = v
    return dim, out


def main():
    out_dir = Path(sys.argv[1]); sizes = [int(s) for s in sys.argv[2:]]
    pool = {}  # source key -> (audio, text or None, top, sub)
    for d in sorted(LIBS.glob('L*')):
        lib = [l.split('\t') for l in (d / 'library.tsv').read_text(encoding='utf-8').splitlines() if l]
        meta = {l.split('\t')[0]: l.split('\t') for l in (d / 'judge_meta.tsv').read_text(encoding='utf-8').splitlines() if l}
        V = np.load(d / 'vec.npy').astype(np.float32)
        assert len(V) == len(lib)
        _, text = read_ljix(d / 'files/smart_text_index.bin')
        for row, cells in enumerate(lib):
            tid = cells[0]
            m = meta[tid]
            key = m[1]
            if key in pool: continue
            path = m[3].split('/') if len(m) > 3 else []
            top = m[4] if len(m) > 4 else ''
            sub = path[1] if len(path) > 1 and top else top
            pool[key] = (V[row], text.get(tid), top, sub)
    keys = sorted(pool)
    print(f'pool {len(keys)} unique tracks, {sum(1 for k in keys if pool[k][1] is not None)} with text')
    for n in sizes:
        pick = sorted(random.Random(20261025 + n).sample(keys, n))
        d = out_dir / f'fma-{n}'; d.mkdir(parents=True, exist_ok=True)
        ids = [f'fma-{k}' for k in pick]
        np.save(d / 'smart_index_vecs.npy', np.stack([pool[k][0] for k in pick]))
        (d / 'smart_index_ids.txt').write_text('\n'.join(ids) + '\n')
        tk = [k for k in pick if pool[k][1] is not None]
        np.save(d / 'smart_text_vecs.npy', np.stack([pool[k][1] for k in tk]))
        (d / 'smart_text_ids.txt').write_text('\n'.join(f'fma-{k}' for k in tk) + '\n')
        (d / 'labels.tsv').write_text(''.join(f'fma-{k}\t{pool[k][2]}\t{pool[k][3]}\n' for k in pick))
        print(f'{d.name}: {n} tracks, text {len(tk)}, genres {len({pool[k][2] for k in pick if pool[k][2]})}')


if __name__ == '__main__':
    main()
