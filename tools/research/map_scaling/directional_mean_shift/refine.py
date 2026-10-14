#!/usr/bin/env python3
import json,subprocess,sys,shutil
from pathlib import Path
import numpy as np
from sklearn.metrics import adjusted_rand_score
from threadpoolctl import threadpool_limits
from directional import fit,unit,mode_labels
from run_experiment import read,write

p=Path(sys.argv[1]);src=Path(sys.argv[2])/'regions';g=p/'geometry';threadpool_limits(limits=4)
coarse=p/'coarse';coarse.mkdir(exist_ok=True)
for name in ['runs.json','results.json','selection.json','metrics.json','private-stability.json','verification-final.json','analysis.log','private-region-details.json']:
    if (p/name).exists():shutil.copy2(p/name,coarse/name)
shutil.copy2(p/'src/analyze.py',coarse/'analyze.py')
protocol=read(p/'refinement-protocol.json');runs=read(p/'runs.json');x=unit(np.fromfile(src/'audio25-meta75-centered.f32',dtype='<f4').reshape(1107,1344).astype('float64'));cases=[]
for k in protocol['kappa_grid']:
    name='dms'+str(k).replace('.','p');lab,y,info=fit(x,k)
    assert info['converged'];np.save(g/f'private-{name}-modes.npy',y);write(g/f'private-{name}-labels.json',lab.tolist())
    counts=np.bincount(lab);info['mode_sizes']=sorted(counts.tolist(),reverse=True)
    info['merge_sensitivity']={str(t):{'modes':len(set(mode_labels(y,t)[0])),'ari_to_default':float(adjusted_rand_score(lab,mode_labels(y,t)[0]))} for t in [1e-5,1e-4,1e-3]}
    runs[name]=info
    cases.append({'name':name,'dim':1344,'input':str(src/'audio25-meta75-input.f32'),'assignments':str(g/f'private-{name}-labels.json')})
    cases.append({**cases[-1],'name':name+'-untrimmed','without_confidence':True})
    print(json.dumps({'name':name,'modes':info['modes'],'seconds':info['seconds'],'largest':int(counts.max())}),flush=True)
write(p/'runs.json',dict(sorted(runs.items(),key=lambda z:z[1]['kappa'])))
write(g/'private-refinement-plan.json',{'ids':[r['id'] for r in read(src/'private-cooked-metadata.json')],'cases':cases})
cp=(src/'classpath.txt').read_text().strip()
subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-Xmx2g','-cp',str(g)+':'+cp,'DmsRegionProbe',str(src.parent),str(g),str(g/'private-refinement-plan.json')],check=True)
