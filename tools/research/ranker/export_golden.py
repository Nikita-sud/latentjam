"""Golden cases for the app's SMART ranker (RankerFeatures.kt / RankerModel.kt) from proto_ranker.py.

    python3 export_golden.py RESEARCH_DIR OUT.tsv.gz [N_QUERIES]

RESEARCH_DIR is tools/research/listenbrainz_eval on claude/smart-stage0-eval-f2a727 (proto_ranker.py, commit a5747d63,
and its proto_ranker.json CORE weights). Per FIT query (libraries of at most 800 rows): the seed's and every candidate's
primitives (descriptor cosine to the seed or NaN when either is unknown, genre-family id, pack language code, pack
decade, engine artist-key id, co-listening q/255, log popularity), proto_ranker's CORE feature rows (float32, as it
builds them) and the top 10 by the CORE weights in raw units (ranking-equivalent to the standardised ones: a constant
shift), ties by the track key, as proto_ranker.ranked().
"""
import gzip, json, sys
from pathlib import Path
import numpy as np

research, out = Path(sys.argv[1]), Path(sys.argv[2]); want = int(sys.argv[3]) if len(sys.argv) > 3 else 8
sys.path.insert(0, str(research))
import proto_ranker as pr  # noqa: E402

model = json.loads((research / 'proto_ranker.json').read_text())['models']['CORE']
w = np.array([model['weights_raw_units'][f] for f in pr.CORE])
cols = [pr.FULL.index(f) for f in pr.CORE]
ctx = pr.Ctx(); lines = []; n_q = 0
for ld in pr.libs_of(pr.SETS[0]):
    if n_q >= want: break
    lib, ek, desc, facts, fam, ev_all = pr.read_lib(ld, ctx)
    if len(lib['track']) > 800: continue
    ents = lib['entity']; dim = next((len(d) for d in desc if d is not None), 1)
    fams = {}; fid = [fams.setdefault(f, len(fams)) if f else -1 for f in fam]
    aks = {}; aid = [aks.setdefault(k, len(aks)) if k else -1 for k in ek]
    for x in pr.lib_features(lib, ek, desc, facts, fam, ev_all, ctx)[:1]:  # one query per listener
        q = x['q']; s = q['seed']; cand = x['cand']
        nb = ctx.nbr.get(int(ents[s]), {}) if ents[s] >= 2 else {}
        X = x['X'][:, cols]
        sc = X.astype(np.float64) @ w
        top = cand[np.lexsort((x['tkey'], -sc))[:10]].tolist()
        def prim(i):
            c = float(desc[i] @ desc[s]) if desc[i] is not None and desc[s] is not None else float('nan')
            return [int(lib['track'][i]), repr(c), fid[i], int(facts[i][0] or 0), int(facts[i][1] or 0), aid[i],
                    repr(nb.get(int(ents[i]), 0) / 255 if ents[i] >= 2 else 0.0), repr(float(np.log1p(ctx.pop.get(int(lib['track'][i]), 0))))]
        lines.append('\t'.join(['case', q['key'], str(len(cand))] + [str(v) for v in prim(s)]))
        for j, i in enumerate(cand):
            lines.append('\t'.join(['row'] + [str(v) for v in prim(i)] + [','.join(repr(float(v)) for v in X[j])]))
        lines.append('top\t' + ','.join(str(int(lib['track'][i])) for i in top))
        n_q += 1
with gzip.open(out, 'wt', compresslevel=9) as f: f.write('\n'.join(lines) + '\n')
print(n_q, 'queries', out.stat().st_size, 'bytes', 'features', pr.CORE)
