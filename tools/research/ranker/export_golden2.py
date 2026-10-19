"""Golden queues for the app's CORE+LFM ranker (SmartRanker.kt) from proto_ranker2.py, and the int8 LightFM check.

    python3 export_golden2.py RESEARCH_DIR STATS.json OUT.tsv.gz [N_QUERIES] [CHECK_QUERIES]

RESEARCH_DIR: tools/research/listenbrainz_eval on claude/smart-stage0-eval-f2a727 (proto_ranker2.py, 6e2fd6ce).
STATS.json: CORE+LFM mean/sd/weights (refit of proto_ranker2's own fit, identical to its published weights).
Per FIT query (libraries of at most 400 rows): every candidate's anchor-free primitives (track key, family id, pack
language, pack decade, artist-key id, log popularity) and, for the seed and each pick but the last, the anchor-relative
ones exactly as the reference's Query.X computed them (float32 descriptor cosine or NaN, co-listening, LightFM cosine or
NaN), then the 20-row queue proto_ranker2's Query.queue builds with CORE+LFM and p = 2.129, and each hop's score
margin between its pick and the best other open row (a test may let the app differ only where that margin is tiny). CHECK_QUERIES more queries are queued with float and with the stored
precision (LFM_STORAGE=f16, what ships; int8 changed 24 of 300 top-10s and was rejected); differing queues are counted.
"""
import gzip, json, sys
from pathlib import Path
import numpy as np

research, stats, out = Path(sys.argv[1]), json.loads(Path(sys.argv[2]).read_text()), Path(sys.argv[3])
want = int(sys.argv[4]) if len(sys.argv) > 4 else 4; check = int(sys.argv[5]) if len(sys.argv) > 5 else 300
sys.path.insert(0, str(research)); sys.path.insert(0, str(Path(__file__).parent))
import proto_ranker as P, proto_ranker2 as P2  # noqa: E402
from pack_lfm import quantise  # noqa: E402

P2.winit()
raw = dict(zip(stats['features'], (np.array(stats['weights_refit']) / np.array(stats['sd'])).tolist()))
model = P2.lin_model(P2.CORE_LFM, raw); p = 2.129
li, le = P2.W['lfm']
import os
if os.environ.get('LFM_STORAGE', 'f16') == 'int8':
    q8 = quantise(le.astype(np.float64)).astype(np.float64)
else:  # what pack_lfm.py ships: float16 of the normalised vector
    q8 = (le / np.linalg.norm(le, axis=1, keepdims=True)).astype(np.float16).astype(np.float64)
le8 = (q8 / np.linalg.norm(q8, axis=1, keepdims=True)).astype(np.float32)
f = lambda v: 'nan' if not np.isfinite(v) else repr(float(np.float32(v)))
lines = []; n_q = 0; checked = differ = differ10 = 0
for ld in P.libs_of(P.SETS[0]):
    if n_q >= want and checked >= check: break
    L = P2.Lib(ld, P2.W['ctx'], (li, le))
    small = L.n <= 400 and n_q < want
    if checked >= check and not small: continue
    L8 = P2.Lib(ld, P2.W['ctx'], (li, le8)) if checked < check else None
    for q in L.lib['queries'][:1]:
        Q = P2.Query(L, q); rows = Q.queue('CORE+LFM', model, p)
        if L8 is not None:
            r8 = P2.Query(L8, q).queue('CORE+LFM', model, p); checked += 1
            differ += rows != r8; differ10 += rows[:10] != r8[:10]
        if not small: continue
        cand = Q.cand; s = q['seed']; F = P2.FEATS
        ci, di, coi, li_, lki = (F.index(x) for x in ('cos', 'desc_known', 'colisten', 'lfm_cos', 'lfm_known'))
        lines.append('\t'.join(['case', q['key'], str(len(cand)), str(int(L.tkey[s])), str(int(L.fid[s])), str(int(L.lang[s])), str(int(L.dec[s])), str(int(L.aid[s]))]))
        for i in cand:
            lines.append('\t'.join(['row', str(int(L.tkey[i])), str(int(L.fid[i])), str(int(L.lang[i])), str(int(L.dec[i])), str(int(L.aid[i])), f(L.logpop[i])]))
        for a in [s] + rows[:-1]:
            X = Q.X(a)  # the reference's own float32 feature rows for this anchor
            cos = np.where(X[:, di] > 0, X[:, ci], np.nan); lf = np.where(X[:, lki] > 0, X[:, li_], np.nan)
            lines.append('\t'.join(['anchor', str(int(L.tkey[a])), ','.join(map(f, cos)), ','.join(map(f, X[:, coi])), ','.join(map(f, lf))]))
        # Query.queue again, recording each hop's margin between the pick and the best other open row
        s0 = Q.S('CORE+LFM', model, s); avail = np.ones(len(cand), bool); tk = L.tkey[cand]; ac = L.aid[cand]
        ra = L.aid[s]; rl = 1 if ra >= 0 else 0; picks = []; gaps = []
        for k in range(min(20, len(cand))):
            sc = s0 if not picks else (2 * s0 + Q.S('CORE+LFM', model, picks[-1])) / 3
            t = sc.copy()
            if p > 0 and rl > 0: t = t - p * rl * sc[avail].std() * (ac == ra)
            t[~avail] = -np.inf
            m = t.max(); idx = np.flatnonzero(t == m); j = idx[np.argmin(tk[idx])]
            rest = t.copy(); rest[j] = -np.inf; gaps.append(float(m - rest.max()))
            avail[j] = False; r = int(cand[j]); picks.append(r)
            if ra >= 0 and L.aid[r] == ra: rl += 1
            else: ra = L.aid[r]; rl = 1 if ra >= 0 else 0
        assert picks == rows
        lines.append('queue\t' + ','.join(str(int(L.tkey[r])) for r in rows))
        lines.append('gaps\t' + ','.join(repr(g) for g in gaps))
        n_q += 1
with gzip.open(out, 'wt', compresslevel=9) as fh: fh.write('\n'.join(lines) + '\n')
print(json.dumps({'golden_queries': n_q, 'bytes': out.stat().st_size, 'storage': os.environ.get('LFM_STORAGE', 'f16'), 'checked_queries': checked,
                  'queues_differing_20': differ, 'queues_differing_top10': differ10}))
