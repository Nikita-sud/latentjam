"""Write proto_ranker2's LightFM artist vectors as the app asset read by core/smart text/ArtistVectors.kt.

    python3 pack_lfm.py LFM_ARTIST.npz OUT.bin

LFM_ARTIST.npz is the research's output/listenbrainz-eval/proto_ranker/lfm_artist.npz (claude/smart-stage0-eval-f2a727,
proto_ranker2.py `lfm`): 32-dimensional LightFM (WARP, hybrid) item vectors of the FIT library artists, trained on
ListenBrainz (CC0) listeners disjoint from the FIT queries. Only the artists keyed by a knowledge-pack entity ('e<id>')
ship: the app resolves artists to pack entities; credit-keyed artists ('c:<credit>') are left out. Each vector is
L2-normalised (as proto_ranker2 does before its cosine) and stored as little-endian float32: on 300 FIT queues int8
changed 24 top-10s and float16 7 (export_golden2.py), float32 none — near-ties tip over at lower precision.

Layout "LJVECS1": u32 version | u32 entity id space | u32 vectors | u16 dimension | u16 element width (4 = f32) |
presence bits (u64 words) | running counts (u32 per 512 ids, plus the total) | vectors in entity order.
"""
import gzip, hashlib, struct, sys
from pathlib import Path
import numpy as np


def quantise(e):
    e = e / np.maximum(np.linalg.norm(e, axis=1, keepdims=True), 1e-9)
    return np.clip(np.rint(e * (127 / np.abs(e).max(1, keepdims=True))), -127, 127).astype(np.int8)


if __name__ == '__main__':
    z = np.load(sys.argv[1]); keys = z['artist'].tolist(); emb = z['emb'].astype(np.float64)
    rows = [(int(k[1:]), i) for i, k in enumerate(keys) if k.startswith('e')]
    rows.sort()
    ent = np.array([e for e, _ in rows]); idx = np.array([i for _, i in rows])
    assert (np.diff(ent) > 0).all()
    v = emb[idx]; v = (v / np.linalg.norm(v, axis=1, keepdims=True)).astype('<f4'); dim = v.shape[1]
    space = int(ent.max()) + 1
    words = -(-space // 64)
    bits = np.zeros(words * 64, np.uint8); bits[ent] = 1
    presence = np.packbits(bits, bitorder='little').tobytes()
    blocks = -(-space // 512) + 1
    csum = np.r_[0, np.cumsum(bits)]
    ranks = np.array([int(csum[min(b * 512, space)]) for b in range(blocks)], '<u4')
    raw = b'LJVECS1\0' + struct.pack('<IIIHH', 1, space, len(ent), dim, 4) + presence + ranks.tobytes() + v.tobytes()
    Path(sys.argv[2]).write_bytes(raw)
    print(f'{sys.argv[2]}: {len(ent)} entity vectors (of {len(keys)} artists; {len(keys) - len(ent)} credit-keyed left out), dim {dim}, '
          f'{len(raw):,} B raw, gzip {len(gzip.compress(raw, 9)):,} B, sha256 {hashlib.sha256(raw).hexdigest()}, '
          f'source sha256 {hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()}')
