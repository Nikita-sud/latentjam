import argparse,subprocess
from pathlib import Path
import numpy as np
from threadpoolctl import threadpool_limits
from search import read,write
from directional import fit,unit
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--snapshot',type=Path,required=True);a=p.parse_args();root=a.root;g=root/'geometry';runs=read(root/'runs.json');protocol=read(root/'refinement-protocol.json')
assert (root/'grid-verification.json').exists() and not (root/'selection.json').exists()
cases=[]
with threadpool_limits(limits=4):
 for w in protocol['audio_weights']:
  tag=f'a{round(w*100)}';raw=g/f'private-{tag}-k16-used-input.f32';x=unit(np.fromfile(g/f'private-{tag}-centered.f32',dtype='<f4').reshape(1107,1344).astype(float))
  for k in protocol['kappa']:
   key=tag+'-dms-'+str(k).replace('.','p');labels,_,info=fit(x,k);lp=g/f'private-{key}-labels.json';write(lp,labels.tolist())
   entry={'key':key,'algorithm':'dms','audio_weight':w,'params':{'kappa':k},'raw_groups':len(set(labels)),'raw_noise':0,**{z:info[z] for z in ['seconds','converged','iterations']}};runs.append(entry);write(root/'runs.json',runs)
   for admission in ['product','own']:cases.append({'name':key+'-'+admission,'dim':1344,'input':str(raw),'assignments':str(lp),'without_confidence':admission=='own'})
   print(entry,flush=True)
plan=g/'private-refinement-plan.json';write(plan,{'ids':[r['id'] for r in read(g/'private-cooked-metadata.json')],'cases':cases})
cp=(a.snapshot/'regions/classpath.txt').read_text().strip();subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-Xmx2g','-cp',str(g)+':'+cp,'DmsRegionProbe',str(a.snapshot),str(g),str(plan)],check=True)
write(root/'refinement-complete.json',{'raw_settings':len(runs),'output_settings':len(runs)*2})
