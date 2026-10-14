#!/usr/bin/env python3
"""Additional disclosed diagnostics for the count-comparable kappa8.5 setting."""
import json,subprocess,sys
from pathlib import Path
import numpy as np
from sklearn.metrics import adjusted_rand_score
from threadpoolctl import threadpool_limits
from directional import fit,unit,mode_labels
from run_experiment import read,write

p=Path(sys.argv[1]);snapshot=Path(sys.argv[2]);g=p/'geometry';src=snapshot/'regions';threadpool_limits(limits=4)
cp=(src/'classpath.txt').read_text().strip()
subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',str(g)+':'+cp,'StabilityProbe',str(src/'audio25-meta75-input.f32'),str(g/'private-baseline-exact-centered.f32'),str(g/'private-baseline-exact-labels.json')],check=True)
assert (g/'private-baseline-exact-centered.f32').read_bytes()==(src/'audio25-meta75-centered.f32').read_bytes()
raw=read(g/'private-regions-baseline.json')['raw_clusters'];reference=np.full(1107,-1)
for i,c in enumerate(raw):reference[c['members']]=i
assert np.array_equal(reference,read(g/'private-baseline-exact-labels.json')['labels'])
x=unit(np.fromfile(src/'audio25-meta75-centered.f32',dtype='<f4').reshape(1107,1344).astype('float64'))
labels=np.array(read(g/'private-dms8p5-labels.json'));stability=[]
for item in read(p/'private-stability.json'):
    seed=item['seed'];sub=np.fromfile(g/f'private-delete{seed}-centered.f32',dtype='<f4').reshape(-1,1344)
    lab,_,info=fit(sub,8.5);assert info['converged']
    stability.append({'seed':seed,'ari':float(adjusted_rand_score(labels[item['rows']],lab)),'modes':len(set(lab))})
y=np.load(g/'private-dms8p5-modes.npy');lab,_,info=fit(x,8.5,initial=y,eps=1e-12)
reverse,_=mode_labels(y[::-1])
extra={'stage':'Additional diagnostics after refinement;8.5 chosen for16final regions near17baseline','exact_kotlin_centering_bytes_equal':True,'exact_kmeans_labels_equal':True,'baseline_desktop_kmeans_seconds':read(g/'private-baseline-exact-labels.json')['seconds'],'kappa8p5_deletion_stability':stability,'kappa8p5_strict_convergence':info,'kappa8p5_strict_ari':float(adjusted_rand_score(labels,lab)),'kappa8p5_reverse_merge_ari':float(adjusted_rand_score(labels,reverse[::-1]))}
write(p/'extra-checks.json',extra)
results=read(p/'results.json');results['extra_checks']=extra;write(p/'results.json',results)
print(json.dumps(extra,indent=2))
