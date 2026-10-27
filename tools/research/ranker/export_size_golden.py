"""PLAN_SIZE golden: what the int8 LightFM table must still answer, written from the float32 original.

    python3 export_size_golden.py ARTIST_LFM_F32.bin ARTIST_POP.bin OUT.tsv.gz

Rows: `v<TAB>entity<TAB>floor50<TAB>32 float32 values` (1,000 salted entities with a vector + 200 without: values empty);
Read by core/smart's ArtistLfmInt8GoldenTest.
"""
import gzip, hashlib, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
from shrink_tables import read_vectors, pop_floor

lfm, pop, out = sys.argv[1:4]
rng = np.random.default_rng(int.from_bytes(hashlib.sha256(b'plan-size-golden').digest()[:8], 'little'))
entities, ids, v = read_vectors(lfm)
keep = pop_floor(pop, 50)
rows = []
for i in sorted(rng.choice(len(ids), 1000, replace=False)):
    rows.append(f'v\t{ids[i]}\t{int(ids[i] in keep)}\t' + ' '.join(repr(float(x)) for x in v[i]))
absent = np.setdiff1d(np.arange(entities), ids)
for e in sorted(rng.choice(absent, 200, replace=False)): rows.append(f'v\t{e}\t0\t')
with gzip.open(out, 'wt', encoding='utf-8') as f: f.write('\n'.join(rows) + '\n')
print(out, len(rows), 'rows')
