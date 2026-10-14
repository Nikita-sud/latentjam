"""Deletion sensitivity for development-selected family winners only."""
import argparse,subprocess,json,time
from pathlib import Path
import numpy as np
from sklearn.cluster import HDBSCAN
from sklearn.metrics import adjusted_rand_score
from scipy.cluster.hierarchy import linkage,fcluster
from scipy.spatial.distance import squareform
from threadpoolctl import threadpool_limits
from search import read,write,compact
from directional import fit,unit

def at_least_four(y):
 z=y.copy();v,c=np.unique(z[z>=0],return_counts=True);z[~np.isin(z,v[c>=4])]=-1;return z
def singletons(y):
 z=y.copy();m=z.max(initial=-1)+1;mask=z<0;z[mask]=m+np.arange(mask.sum());return z
def main():
 p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--snapshot',type=Path,required=True);a=p.parse_args();root=a.root;g=root/'geometry';out=root/'stability';out.mkdir(exist_ok=True)
 cp=(a.snapshot/'regions/classpath.txt').read_text().strip();java='/opt/homebrew/opt/openjdk@21/bin/java'
 chosen=read(root/'selection.json')['selected'];configs={v['key']:v for k,v in chosen.items() if v}
 base=next(v for v in read(root/'development.json') if v['name']=='a25-kmeans-16-product');configs[base['key']]=base
 results=[]
 with threadpool_limits(limits=4):
  for key,v in configs.items():
   tag=f'a{round(v["audio_weight"]*100)}';full=at_least_four(np.array(read(g/f'private-{key}-labels.json')));raw=np.fromfile(g/f'private-{tag}-k16-used-input.f32',dtype='<f4').reshape(1107,1344)
   for seed in [3,17,41]:
    keep=np.sort(np.random.default_rng(seed).choice(1107,size=996,replace=False));rp=out/'private-input.f32';raw[keep].tofile(rp);cx=out/'private-centered.f32';lp=out/'private-kotlin-labels.json'
    subprocess.run([java,'-cp',str(g)+':'+cp,'StabilityProbe',str(rp),str(cx),str(lp),str(v['params'].get('k',16))],check=True)
    x=unit(np.fromfile(cx,dtype='<f4').reshape(len(keep),1344).astype(float));params=v['params'];alg=v['algorithm'];converged=True
    if alg=='kmeans':labels=np.array(read(lp)['labels'])
    elif alg=='dms':labels,_,info=fit(x,params['kappa']);converged=info['converged']
    elif alg=='compact':labels,info=compact(x,params['radius']);converged=info['converged']
    else:
     cos=np.clip(1-x@x.T,0,2);np.fill_diagonal(cos,0)
     if alg=='hdbscan':labels=HDBSCAN(min_cluster_size=params['size'],min_samples=params['samples'],cluster_selection_method=params['method'],metric='precomputed',copy=True).fit_predict(np.sqrt(2*cos))
     else:labels=fcluster(linkage(squareform(cos,checks=False),method='average'),params['cut'],criterion='distance')-1
    labels=at_least_four(labels);old=full[keep];common=(labels>=0)&(old>=0);both=common[:,None]&common[None,:];tri=np.triu(np.ones((len(old),len(old)),bool),1);oldpairs=(old[:,None]==old[None,:])&(old[:,None]>=0)&tri;newpairs=(labels[:,None]==labels[None,:])&(labels[:,None]>=0)&tri
    entry={'key':key,'seed':seed,'converged':converged,'common_retained':int(common.sum()),'old_retained':int((old>=0).sum()),'new_retained':int((labels>=0).sum()),'ari_common_retained':float(adjusted_rand_score(old[common],labels[common])),'ari_noise_as_singletons':float(adjusted_rand_score(singletons(old),singletons(labels))),'old_pairs_retained':float((oldpairs&newpairs).sum()/max(1,oldpairs.sum()))}
    results.append(entry);write(root/'stability.json',results);print(json.dumps(entry),flush=True)

if __name__=='__main__':main()
