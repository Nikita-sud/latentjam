#!/usr/bin/env python3
"""Map refresh gate (docs/map-cost-research-2026-10-25.md): public FMA / cc-audio libraries for MapGateReplay.

Pools the 16 general-stand libraries (~/Documents/LJ/genstand-2026-10-22/libs, built from the licence-clean
P2 FMA list plus cc-audio-v1; never the owner's library), drops duplicate source tracks, and writes random
libraries of the requested sizes in the layout MapScaleReplay.readNpy reads:

    <out>/fma-<n>/{smart_index_vecs.npy, smart_index_ids.txt, smart_text_vecs.npy, smart_text_ids.txt, labels.tsv}

labels.tsv: id, FMA top genre (empty when unknown), first sub-genre path element after the top (or the top),
FMA official split (training / validation / test / cc), and the LW2 audio tagger's PREDICTED family (argmax of the
int8 head from branch claude/vc28-lw2-build, "other" = empty). The router may read only the predicted family; the FMA
genre columns are evaluation labels. The tagger was trained on the FMA training split.
Audio is the stand's A0 vector (960-d, mnv4-960-clean-a0-fma-v1, the LW2 encoder, not the release
retrieval-distill-v1), text the shipped text encoder's vector from files/smart_text_index.bin.

    python3 -I tools/research/map_gate_fma_dump.py OUT_DIR [--seed S] [--tagger DIR] 1000 5000 7989
    (the pool has 7,989 tracks; --seed changes the random draw; --tagger holds audio_tagger.json/.bin)
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


def tagger(directory):
    """LW2 AudioTagger family head (int8, per-row scales), as core/smart AudioTagger.parse(int8 = true)."""
    import json
    meta = json.loads((directory / 'audio_tagger.json').read_text())
    raw = (directory / 'audio_tagger.bin').read_bytes()
    t = {x['name']: x for x in meta['tensors_int8']}
    def vec(name):
        x = t[name]; n = int(np.prod(x['shape']))
        return np.frombuffer(raw, dtype='<f4', count=n, offset=x['offset']).astype(np.float64)
    x = t['fc0.weight']; rows, cols = x['shape']
    q = np.frombuffer(raw, dtype=np.int8, count=rows * cols, offset=x['offset']).reshape(rows, cols).astype(np.float64)
    scale = np.frombuffer(raw, dtype='<f4', count=rows, offset=x['scale_offset']).astype(np.float64)
    W = q * scale[:, None]
    mean, std, bias = vec('input_mean'), vec('input_std'), vec('fc0.bias')
    temp = meta['family']['temperature']; classes = meta['family']['classes']
    def predict(v):
        v = np.asarray(v, np.float64); v = v / np.linalg.norm(v)
        logits = W @ ((v - mean) / std) + bias
        e = np.exp((logits - logits.max()) / temp); p = e / e.sum()
        return classes[int(p.argmax())], p
    return predict


def main():
    args = sys.argv[1:]
    seed = 0; tag_dir = None
    if '--seed' in args: i = args.index('--seed'); seed = int(args[i + 1]); del args[i:i + 2]
    if '--tagger' in args: i = args.index('--tagger'); tag_dir = Path(args[i + 1]); del args[i:i + 2]
    out_dir = Path(args[0]); sizes = [int(s) for s in args[1:]]
    predict = tagger(tag_dir) if tag_dir else None
    if predict:
        # Required: the golden must be present and agree (argmax everywhere, |dp| <= 1e-9).
        # Agreement with the Kotlin AudioTagger golden (branch claude/vc28-lw2-build, androidHostTest resources).
        worst, same = 0.0, 0
        rows = [l.split('\t') for l in (tag_dir / 'audio_tagger_golden.tsv').read_text().splitlines() if l]
        for e, p_ref, _ in rows:
            fam, p = predict(np.array(e.split(','), float))
            p_ref = np.array(p_ref.split(','), float)
            worst = max(worst, float(np.abs(p - p_ref).max())); same += int(p.argmax() == p_ref.argmax())
        print(f'tagger golden: {same}/{len(rows)} argmax agree, max |dp| = {worst:.3g}')
        if same != len(rows) or worst > 1e-9: sys.exit('tagger golden check FAILED')
    split = {}
    if predict:
        import pandas as pd
        t = pd.read_csv(Path.home() / 'Documents/LJ/latentjam-research/data/raw/fma_metadata/tracks.csv',
                        index_col=0, header=[0, 1], low_memory=False)
        split = {str(k): v for k, v in t[('set', 'split')].items()}
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
            pool[key] = (V[row], text.get(tid), top, sub, cells[1:4] + [cells[5], cells[7] if len(cells) > 7 else ''])
    keys = sorted(pool)
    print(f'pool {len(keys)} unique tracks, {sum(1 for k in keys if pool[k][1] is not None)} with text')
    for n in sizes:
        pick = sorted(random.Random(20261025 + n + seed).sample(keys, n))
        d = out_dir / f'fma-{n}'; d.mkdir(parents=True, exist_ok=True)
        ids = [f'fma-{k}' for k in pick]
        np.save(d / 'smart_index_vecs.npy', np.stack([pool[k][0] for k in pick]))
        (d / 'smart_index_ids.txt').write_text('\n'.join(ids) + '\n')
        tk = [k for k in pick if pool[k][1] is not None]
        np.save(d / 'smart_text_vecs.npy', np.stack([pool[k][1] for k in tk]))
        (d / 'smart_text_ids.txt').write_text('\n'.join(f'fma-{k}' for k in tk) + '\n')
        def extra(k):
            if not predict: return ''
            family = predict(pool[k][0])[0]
            return f"\t{split.get(k, 'cc')}\t{'' if family == 'other' else family}"
        # descriptors.tsv: id, title, artist, album, genre TAG as the messy library has it, year (the app's inputs).
        (d / 'descriptors.tsv').write_text(''.join('fma-' + k + '\t' + '\t'.join(c.replace('\t', ' ') for c in pool[k][4]) + '\n' for k in pick), encoding='utf-8')
        (d / 'labels.tsv').write_text(''.join(f'fma-{k}\t{pool[k][2]}\t{pool[k][3]}{extra(k)}\n' for k in pick))
        print(f'{d.name}: {n} tracks, text {len(tk)}, genres {len({pool[k][2] for k in pick if pool[k][2]})}')


if __name__ == '__main__':
    main()
