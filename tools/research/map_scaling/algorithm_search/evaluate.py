"""Selection on development artists only; sealed holdout is separate command."""
import argparse,json,hashlib
from pathlib import Path
import numpy as np
from scipy.stats import rankdata
from search import read,write

def audit(root,clips,meta):
 items=[v for v in read(clips/'private-clips.json') if v['row']>=0]
 by={}
 for v in items:
  by.setdefault(v['row'],{})[v['position']]=np.load(root/'clap'/(v['clip_id']+'.npy'))
 rows=np.array(sorted(r for r in by if set(by[r])=={0,1}));X=[np.stack([by[r][p] for r in rows]) for p in [0,1]]
 artists=np.array([(meta[r].get('artist') or 'unknown-'+str(r)).strip().casefold() for r in rows])
 cross=X[0]@X[1].T;percentiles=(cross<=cross.diagonal()[:,None]).mean(1)
 checks={'tracks':len(rows),'clips':len(items),'same_track_other_excerpt_median_percentile':float(np.median(percentiles)),'same_track_other_excerpt_top1':float((cross.argmax(1)==np.arange(len(rows))).mean()),'median_cosine_same_track':float(np.median(cross.diagonal())),'median_cosine_all':float(np.median(cross)),'embedding_std_mean':float(X[0].std(0).mean())}
 write(root/'audit-checks.json',checks)
 assert checks['same_track_other_excerpt_median_percentile']>=.8,checks
 dev=np.array([hashlib.sha256(s.encode()).digest()[0]<128 for s in artists])
 return rows,X,artists,dev

def score(worlds,rows,X,artists):
 lab=np.full(1107,-1,dtype=int)
 for k,w in enumerate(worlds):
  assert (lab[w['members']]<0).all(),'Overlap'
  lab[w['members']]=k
 labels=lab[rows];C=X@X.T;different=artists[:,None]!=artists[None,:]
 values=np.full(len(rows),np.nan);allvalues=np.full(len(rows),np.nan);tail=[]
 for i in range(len(rows)):
  if labels[i]<0:continue
  ref=np.sort(C[i,different[i]]);peer=(labels==labels[i])&different[i]
  if peer.sum()>=2:
   pct=np.searchsorted(ref,C[i,peer],side='right')/len(ref);values[i]=pct.mean();tail.append(float(np.mean(pct<.5)))
  other=np.arange(len(rows))!=i;p2=(labels==labels[i])&other
  if p2.sum()>=2:allvalues[i]=(np.searchsorted(np.sort(C[i,other]),C[i,p2],side='right')/other.sum()).mean()
 sizes=[len(w['members']) for w in worlds];assigned=sum(sizes)
 return {'regions':len(worlds),'assigned':assigned,'coverage':assigned/1107,'largest':max(sizes,default=0),'audit_assigned':int((labels>=0).sum()),'audit_total':len(rows),'audit_scored':int(np.isfinite(values).sum()),'audit_coverage':float(np.isfinite(values).mean()),'score':float(np.nanmean(values)) if np.isfinite(values).any() else None,'q25':float(np.nanquantile(values,.25)) if np.isfinite(values).any() else None,'below_median_peer_fraction':float(np.mean(tail)) if tail else None,'including_same_artist_score':float(np.nanmean(allvalues)) if np.isfinite(allvalues).any() else None},values

def eligible(m,floor=.55):return m['score'] is not None and m['coverage']>=floor and m['audit_coverage']>=.45 and 8<=m['regions']<=45 and m['largest']<=.3*1107 and m['converged']
def choose(candidates,floor=.55):
 valid=[m for m in candidates if eligible(m,floor)]
 if not valid:return None
 best=max(m['score'] for m in valid)
 return max((m for m in valid if m['score']>=best-.01),key=lambda m:(m['coverage'],m['score']))

def main():
 p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--clips',type=Path,required=True);p.add_argument('--holdout',action='store_true');a=p.parse_args();root=a.root
 meta=read(root/'geometry/private-cooked-metadata.json');rows,X,artists,dev=audit(root,a.clips,meta)
 mask=~dev if a.holdout else dev;pos=int(a.holdout);rows=rows[mask];x=X[pos][mask];artists=artists[mask]
 if not a.holdout:
  results=[]
  for run in read(root/'runs.json'):
   for admission in ['product','own']:
    name=run['key']+'-'+admission;path=root/'geometry'/f'private-regions-{name}.json'
    if not path.exists():raise RuntimeError('Grid not complete: '+name)
    m,values=score(read(path)['worlds'],rows,x,artists);results.append({**run,'name':name,'admission':admission,**m})
  selected={alg:choose([m for m in results if m['algorithm']==alg]) for alg in sorted(set(r['algorithm'] for r in results))}
  selected['global']=choose(results)
  selected['coverage35']=choose(results,.35);selected['coverage70']=choose(results,.70)
  write(root/'development.json',results);write(root/'selection.json',{'frozen_before_holdout':True,'development_tracks':len(rows),'selected':selected})
  print(json.dumps({'audit':read(root/'audit-checks.json'),'selected':selected},indent=2))
 else:
  selection=read(root/'selection.json');names={v['name'] for v in selection['selected'].values() if v};baseline='a25-kmeans-16-product';names.add(baseline)
  # Fixed family/ratio winners selected only on development, useful for ratio comparison.
  devresults=read(root/'development.json')
  for alg in sorted(set(r['algorithm'] for r in devresults)):
   for w in [.25,.4,.5,.6,.75]:
    v=choose([m for m in devresults if m['algorithm']==alg and m['audio_weight']==w])
    if v:names.add(v['name'])
  bm,bv=score(read(root/'geometry'/f'private-regions-{baseline}.json')['worlds'],rows,x,artists)
  rng=np.random.default_rng(20261014);results=[]
  for name in sorted(names):
   m,v=score(read(root/'geometry'/f'private-regions-{name}.json')['worlds'],rows,x,artists);common=np.isfinite(v)&np.isfinite(bv);d=v[common]-bv[common]
   ci=np.quantile(rng.choice(d,(2000,len(d)),replace=True).mean(1),[.025,.975]).tolist() if len(d) else None
   results.append({'name':name,**m,'common_baseline_anchors':int(common.sum()),'paired_difference':float(d.mean()) if len(d) else None,'paired_bootstrap_ci95':ci})
  write(root/'holdout.json',{'holdout_tracks':len(rows),'results':results,'selection_sha256':hashlib.sha256((root/'selection.json').read_bytes()).hexdigest()});print(json.dumps(results,indent=2))

if __name__=='__main__':main()
