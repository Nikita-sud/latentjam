"""Supplementary matched-anchor and artist-bootstrap sensitivity checks."""
import argparse
from pathlib import Path
import numpy as np
from evaluate import audit,read,write,score

p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--clips',type=Path,required=True);a=p.parse_args();root=a.root
r,X,artists,dev=audit(root,a.clips,read(root/'geometry/private-cooked-metadata.json'));r=r[~dev];X=X[1][~dev];artists=artists[~dev]
sel=read(root/'selection.json')['selected'];names=['a25-kmeans-16-product']+list(dict.fromkeys(v['name'] for k,v in sel.items() if k in ['average','dms','compact','kmeans'] and v))
def values_for(n):return score(read(root/'geometry'/f'private-regions-{n}.json')['worlds'],r,X,artists)[1]
values={n:values_for(n) for n in names};mask=np.logical_and.reduce([np.isfinite(v) for v in values.values()]);out={'common_all_count':int(sum(mask)),'common_all_scores':{n:float(v[mask].mean()) for n,v in values.items()},'family_vs_baseline':[]}
def bootdiff(a,b,rng,draws):
 ok=np.isfinite(a)&np.isfinite(b);d=a[ok]-b[ok];art=artists[ok];groups=np.unique(art);s=np.array([d[art==g].sum() for g in groups]);c=np.array([(art==g).sum() for g in groups]);ix=rng.integers(len(groups),size=(draws,len(groups)));boot=s[ix].sum(1)/c[ix].sum(1)
 return ok,d,groups,boot
rng=np.random.default_rng(20261014)
for n in names[1:]:
 a0,b=values[n],values[names[0]];ok,d,groups,boot=bootdiff(a0,b,rng,10000)
 out['family_vs_baseline'].append({'name':n,'anchors':int(ok.sum()),'artists':len(groups),'difference':float(d.mean()),'artist_bootstrap_ci95':np.quantile(boot,[.025,.975]).tolist(),'artist_bootstrap_ci98p75_four_comparisons':np.quantile(boot,[.00625,.99375]).tolist(),'q25_candidate_common':float(np.quantile(a0[ok],.25)),'q25_baseline_common':float(np.quantile(b[ok],.25))})
write(root/'matched-comparisons.json',out)
comparators=['a25-kmeans-16-product','a40-dms-9-own','a75-average-0p75-own','a75-kmeans-24-own'];rng=np.random.default_rng(20261014);out=[]
for n in comparators:
 ok,d,groups,boot=bootdiff(values_for('a75-dms-10-own'),values_for(n),rng,2000)
 out.append({'winner':'a75-dms-10-own','comparator':n,'common_anchors':int(ok.sum()),'artists':len(groups),'difference':float(d.mean()),'artist_bootstrap_ci95':np.quantile(boot,[.025,.975]).tolist()})
write(root/'supplementary-comparisons.json',out)
print('Wrote matched and supplementary comparisons')
