"""Write the ranker's artist co-listening table as the app asset read by core/smart text/ArtistNeighbours.kt.

    python3 pack_colisten.py POP_K10.npz OUT.bin

POP_K10.npz is the research branch's output/listenbrainz-eval/v6/pop_k10.npz (claude/smart-stage0-eval-f2a727, v6_pop.py):
per artist entity of the knowledge pack's entity space, its 10 nearest artists by co-listening among FIT listeners
(ListenBrainz CC0, the fitsample listeners left out) with a u8 similarity q (q / 255 is proto_ranker.py's `colisten`).
Entity ids are the pack's own, so no remapping is needed. Layout "LJNBRS1": see ArtistNeighbours.kt. Entries keep the
npz order (best first); neighbours < 0 or q == 0 are dropped, as proto_ranker.py's lookup drops them.
"""
import gzip, hashlib, struct, sys
from pathlib import Path
import numpy as np

z = np.load(sys.argv[1])
ent, nb, q = z['entity'].astype(np.int64), z['neighbours'].astype(np.int64), z['q']
order = np.argsort(ent, kind='stable'); ent, nb, q = ent[order], nb[order], q[order]
assert (np.diff(ent) > 0).all(), 'duplicate entity'
ok = (nb >= 0) & (q > 0)
space = int(max(ent.max(), nb[ok].max())) + 1
assert space < 1 << 24
counts = ok.sum(1)
listed = counts > 0
ent, nb, q, ok, counts = ent[listed], nb[listed], q[listed], ok[listed], counts[listed]
words = -(-space // 64)
bits = np.zeros(words * 64, np.uint8); bits[ent] = 1
presence = np.packbits(bits, bitorder='little').tobytes()
blocks = -(-space // 512) + 1
csum = np.r_[0, np.cumsum(bits)]
ranks = np.array([int(csum[min(b * 512, space)]) for b in range(blocks)], '<u4')
offsets = np.r_[0, np.cumsum(counts)].astype('<u4')
e = nb[ok].astype('<u4'); s = q[ok].astype(np.uint8)
entries = np.zeros((len(e), 4), np.uint8)
entries[:, 0], entries[:, 1], entries[:, 2], entries[:, 3] = e & 255, (e >> 8) & 255, (e >> 16) & 255, s
raw = b'LJNBRS1\0' + struct.pack('<IIIH', 1, space, len(ent), 4) + presence + ranks.tobytes() + offsets.tobytes() + entries.tobytes()
Path(sys.argv[2]).write_bytes(raw)
print(f'{sys.argv[2]}: {len(ent)} artists, {len(e)} entries, space {space}, {len(raw):,} B raw, gzip {len(gzip.compress(raw, 9)):,} B, '
      f'sha256 {hashlib.sha256(raw).hexdigest()}, source sha256 {hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()}')
