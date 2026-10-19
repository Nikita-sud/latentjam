"""Golden queues for the app's §8 D0 decoder (RankerQueue) from proto_ranker4.py's own decode loop (b4117980).

    python3 export_decoder_golden.py RESEARCH_DIR OUT.tsv [N]

RQ.rollout4 runs unchanged on a stand-in query object: synthetic hop scores per anchor (seeded), artist ids with
runs, song ids with versions, track-id byte ranks; D0 = argmax + song-version dedup over min(50, n // 2) + the run
penalty (lambda_run 1.75, sd of the raw scores left after dedup) + the -8 third-in-a-row cap + the eps = 0.01 tie set
(sd of the penalised scores) with §1.6 step 5's uniform draw (SHA-256(salt|query|press|0) -> SplitMix64, h-th output).
Per case: n, anchors' scores, artist ids, song ids, id ranks, query key, press, the 20 picks and the hops whose eps
boundary had a score within 1e-9.
"""
import json, sys
from pathlib import Path
import numpy as np

research, out = Path(sys.argv[1]), Path(sys.argv[2]); N = int(sys.argv[3]) if len(sys.argv) > 3 else 12
sys.path.insert(0, str(research))
import proto_ranker4 as P4  # noqa: E402

class L: pass
lines = []
rng = np.random.default_rng(20261019)
for c in range(N):
    n = int(rng.integers(25, 120)); lib = n + 1
    Lx = L(); Lx.n = lib
    Lx.aid = rng.integers(-1, max(2, n // 6), lib)                       # few artists: runs and the cap fire
    Lx.song = np.where(rng.random(lib) < 0.25, rng.integers(0, 8, lib), np.arange(100, 100 + lib))  # versions
    seed = 0; cand = np.arange(1, lib)
    scores = {a: (rng.standard_normal(n) * (0.3 if c % 3 == 0 else 1.0)).round(2 if c % 4 == 0 else 6) for a in range(lib)}  # rounding makes ties
    tidr = rng.permutation(n)
    class Q: pass
    q = Q(); q.L = Lx; q.cand = cand; q.models = {'D0': {}}; q.dur = {'D0': 0.0}
    q.S = lambda a, scores=scores: {'D0': scores[a]}
    q.songc = Lx.song[cand]; q.ac = Lx.aid[cand]; q.tidr = tidr; q.q = {'seed': seed, 'key': f'golden-{c}'}
    press = int(rng.integers(0, 4))
    picks, st = P4.RQ.rollout4(q, {'model': 'D0', 'mode': 'pen', 'par': 1.75, 'eps': 0.01}, press, 0)
    lines.append(json.dumps({'n': n, 'key': f'golden-{c}', 'press': press, 'aid': Lx.aid.tolist(), 'song': Lx.song.tolist(),
                             'tidr': tidr.tolist(), 'scores': {str(a): scores[a].tolist() for a in range(lib)},
                             'picks': picks, 'edge_hops': [i for i, e in enumerate(st['edge']) if e], 'salt': P4.DECODE_SALT,
                             'cap': P4.CAP, 'K': P4.K}))
Path(out).write_text('\n'.join(lines) + '\n')
print(len(lines), 'cases', 'edge hops', sum(len(json.loads(l)['edge_hops']) for l in lines))
