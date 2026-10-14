"""Frozen exhaustive local algorithm/weight comparison, with actual Kotlin output."""
import argparse,json,time,subprocess,hashlib,itertools
from pathlib import Path
import numpy as np
from sklearn.cluster import HDBSCAN
from scipy.cluster.hierarchy import linkage,fcluster
from scipy.spatial.distance import squareform
from threadpoolctl import threadpool_limits
from directional import fit,unit,mode_labels

def read(p):return json.loads(p.read_text())
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def compact(x,radius):
 t=time.monotonic();y=x.copy();converged=False
 for it in range(1,501):
  weights=((y@x.T)>=(1-radius)).astype(float)
  new=unit(weights@x);delta=np.maximum(0,1-(new*y).sum(1));y=new
  if delta.max()<1e-9:converged=True;break
 labels,_=mode_labels(y)
 return labels,{'seconds':time.monotonic()-t,'iterations':it,'converged':converged}

def main():
 p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--snapshot',type=Path,required=True);p.add_argument('--baseline',type=Path,required=True);a=p.parse_args()
 root=a.root;out=root/'geometry';out.mkdir(exist_ok=True);src=a.snapshot/'regions';n=1107
 protocol=read(root/'protocol.json');rows=read(src/'private-cooked-metadata.json');ids=[r['id'] for r in rows]
 cp=(src/'classpath.txt').read_text().strip();java=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(out),str(root/'src/DmsRegionProbe.java'),str(root/'src/StabilityProbe.java')],check=True)
 def probe(cases):
  plan=out/'private-plan.json';write(plan,{'ids':ids,'cases':cases})
  subprocess.run([str(java/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'DmsRegionProbe',str(a.snapshot),str(out),str(plan)],check=True)
 initial=[{'name':f'a{round(w*100)}-k16','dim':1344,'input':str(src/'audio25-meta75-input.f32'),'metadata_weight':1-w} for w in protocol['audio_weights']]
 probe(initial);runs=[]
 with threadpool_limits(limits=4):
  for w in protocol['audio_weights']:
   tag=f'a{round(w*100)}';raw=out/f'private-{tag}-k16-used-input.f32';centered=out/f'private-{tag}-centered.f32'
   subprocess.run([str(java/'java'),'-cp',str(out)+':'+cp,'StabilityProbe',str(raw),str(centered),str(out/f'private-{tag}-k16-labels-kotlin.json')],check=True)
   x=unit(np.fromfile(centered,dtype='<f4').reshape(n,1344).astype(float));cos=np.clip(1-x@x.T,0,2);np.fill_diagonal(cos,0)
   cases=[]
   def add(alg,params,labels,info):
    key=tag+'-'+alg+'-'+('-'.join(str(v) for v in params.values())).replace('.','p')
    path=out/f'private-{key}-labels.json';write(path,labels.tolist())
    counts=np.bincount(labels[labels>=0]);entry={'key':key,'algorithm':alg,'audio_weight':w,'params':params,'raw_groups':len(counts),'raw_noise':int((labels<0).sum()),**info};runs.append(entry)
    for admission in ['product','own']:
     cases.append({'name':key+'-'+admission,'dim':1344,'input':str(raw),'assignments':str(path),'without_confidence':admission=='own'})
    write(root/'runs.json',runs);print(json.dumps(entry),flush=True)
   for k in protocol['algorithms']['spherical_kmeans']['k']:
    lp=out/f'private-{tag}-k{k}-labels-kotlin.json'
    if not lp.exists():subprocess.run([str(java/'java'),'-cp',str(out)+':'+cp,'StabilityProbe',str(raw),str(centered),str(lp),str(k)],check=True)
    v=read(lp);add('kmeans',{'k':k},np.array(v['labels']),{'seconds':v['seconds'],'converged':True})
   for k in protocol['algorithms']['directional_von_mises']['kappa']:
    labels,_,info=fit(x,k,max_iter=1000);add('dms',{'kappa':k},labels,{kk:info[kk] for kk in ['seconds','converged','iterations']})
   for radius in protocol['algorithms']['directional_compact']['cosine_radius']:
    labels,info=compact(x,radius);add('compact',{'radius':radius},labels,info)
   hp=protocol['algorithms']['hdbscan']
   chord=np.sqrt(2*cos)
   for size,samples,method in itertools.product(hp['min_cluster_size'],hp['min_samples'],hp['selection']):
    t=time.monotonic();m=HDBSCAN(min_cluster_size=size,min_samples=samples,cluster_selection_method=method,metric='precomputed',n_jobs=4,copy=True).fit(chord)
    add('hdbscan',{'size':size,'samples':samples,'method':method},m.labels_,{'seconds':time.monotonic()-t,'converged':True})
   t=time.monotonic();tree=linkage(squareform(cos,checks=False),method='average')
   for cut in protocol['algorithms']['average_linkage']['cosine_distance_cut']:
    labels=fcluster(tree,cut,criterion='distance')-1
    add('average',{'cut':cut},labels,{'seconds':time.monotonic()-t,'converged':True})
   probe(cases)
 expected=read(a.baseline)['worlds']
 assert read(out/'private-regions-a25-kmeans-16-product.json')['worlds']==expected,'Baseline differs'
 write(root/'grid-verification.json',{'baseline_exact':True,'raw_settings':len(runs),'output_settings':2*len(runs),'protocol_sha256':hashlib.sha256((root/'protocol.json').read_bytes()).hexdigest()})

if __name__=='__main__':main()
