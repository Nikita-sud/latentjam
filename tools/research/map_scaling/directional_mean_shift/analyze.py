#!/usr/bin/env python3
"""Descriptive comparison, fixed before inspecting track names or selecting kappa."""
import argparse,json,subprocess
from collections import Counter
from pathlib import Path
import numpy as np
from scipy.optimize import linear_sum_assignment
from sklearn.metrics import adjusted_rand_score,silhouette_score
from threadpoolctl import threadpool_limits
from directional import fit,unit,mode_labels,step,density

def read(p):return json.loads(p.read_text())
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def labels_from(groups,n):
    lab=np.full(n,-1,dtype=int)
    for j,w in enumerate(groups):
        assert np.all(lab[w['members']]==-1);lab[w['members']]=j
    return lab

def main():
    ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);ap.add_argument('--snapshot',type=Path,required=True);a=ap.parse_args();threadpool_limits(limits=4)
    p=a.root;g=p/'geometry';src=a.snapshot/'regions';runs=read(p/'runs.json')
    rows=read(g/'private-cooked-metadata.json');n=len(rows)
    x=unit(np.fromfile(src/'audio25-meta75-centered.f32',dtype='<f4').reshape(n,1344).astype('float64'))
    d=np.maximum(0,1-x@x.T);np.fill_diagonal(d,0)
    audio=unit(np.fromfile(src/'audio.f32',dtype='<f4').reshape(n,960).astype('float64'));audio_sim=audio@audio.T
    genres=[set(r.get('genreFamilies') or []) for r in rows]
    artists=[(r.get('artist') or '').casefold().strip() for r in rows]
    playlists=read(g/'private-playlists.json');shared=np.zeros((n,n),dtype=bool)
    for pl in playlists:
        if len(pl['members'])>=4:shared[np.ix_(pl['members'],pl['members'])]=True
    baseline=read(g/'private-regions-baseline.json');base_lab=labels_from(baseline['worlds'],n)
    results={};details={}
    for name in ['baseline']+list(runs):
        obj=read(g/f'private-regions-{name}.json');worlds=obj['worlds'];lab=labels_from(worlds,n)
        raw_lab=labels_from(obj['raw_clusters'],n) if name=='baseline' else np.asarray(read(g/f'private-{name}-labels.json'))
        mem=np.flatnonzero(lab>=0);rmem=np.flatnonzero(raw_lab>=0);common=(lab>=0)&(base_lab>=0)
        sizes=[len(w['members']) for w in worlds]
        genres_known=sum(bool(genres[i]) for i in mem);dominant=0;pairs=0;plpairs=0;artistpairs=0;cosines=[];asims=[]
        for w in worlds:
            ix=np.array(w['members']);c=Counter(gg for i in ix for gg in genres[i]);dominant+=max(c.values(),default=0)
            ii,jj=np.triu_indices(len(ix),1);left,right=ix[ii],ix[jj];pairs+=len(ii);plpairs+=int(shared[left,right].sum());artistpairs+=sum(artists[i]==artists[j] and bool(artists[i]) for i,j in zip(left,right))
            cosines.extend((1-d[left,right]).tolist());asims.extend(audio_sim[left,right].tolist())
        results[name]={'regions':len(worlds),'assigned':len(mem),'outside':n-len(mem),'largest':max(sizes,default=0),'median_size':float(np.median(sizes)) if sizes else None,'raw_modes':len(set(raw_lab[raw_lab>=0])),'raw_small_cluster_tracks':int(sum(c for c in Counter(raw_lab[raw_lab>=0]).values() if c<4)),
           'raw_silhouette_cosine':float(silhouette_score(d[np.ix_(rmem,rmem)],raw_lab[rmem],metric='precomputed')) if 1<len(set(raw_lab[rmem]))<len(rmem) else None,
           'retained_silhouette_cosine':float(silhouette_score(d[np.ix_(mem,mem)],lab[mem],metric='precomputed')) if 1<len(worlds)<len(mem) else None,
           'dominant_genre_coverage':dominant/genres_known if genres_known else None,'genre_known_assigned':genres_known,
           'pair_weighted_shared_playlist':plpairs/pairs if pairs else None,'pair_weighted_same_artist':artistpairs/pairs if pairs else None,'pair_weighted_fused_cosine':float(np.mean(cosines)) if pairs else None,'pair_weighted_audio_cosine':float(np.mean(asims)) if pairs else None,
           'common_with_baseline':int(common.sum()),'ari_to_baseline_common_retained':float(adjusted_rand_score(base_lab[common],lab[common])) if common.any() else None,'processing_seconds':obj['processing_nanos']/1e9}
        if name!='baseline':
            u=read(g/f'private-regions-{name}-untrimmed.json')['worlds'];results[name]['without_confidence_regions']=len(u);results[name]['without_confidence_assigned']=sum(len(w['members']) for w in u)
            results[name].update({k:runs[name][k] for k in ['kappa','h','seconds','iterations','converged','merge_sensitivity']})
        descriptions=[]
        for j,w in enumerate(worlds):
            members=w['members'];centroid=unit(x[members].mean(0,keepdims=True))[0];ordered=sorted(members,key=lambda i:(-float(x[i]@centroid),i))
            overlap=sorted([{'region':b['name'],'members':len(set(members)&set(b['members'])),'baseline_size':len(b['members'])} for b in baseline['worlds']],key=lambda z:-z['members'])
            descriptions.append({**w,'size':len(members),'top_artists':Counter(rows[i].get('artist') or 'Без исполнителя' for i in members).most_common(5),'top_genres':Counter(gg for i in members for gg in genres[i]).most_common(5),'central_rows':ordered[:5],'boundary_rows':ordered[-3:],'baseline_overlap':[v for v in overlap if v['members']>0][:4]})
        details[name]=descriptions
    main_name=min(runs,key=lambda name:(abs(results[name]['regions']-results['baseline']['regions']),-results[name]['assigned'],runs[name]['kappa']))
    ordered=list(runs);j=ordered.index(main_name);shown=list(dict.fromkeys(['baseline']+ordered[max(0,j-1):min(len(ordered),j+2)]))
    selection={'main':main_name,'shown':shown,'criterion':'Closest final region count to baseline; ties highest coverage then smallest kappa; not musical-quality optimization'}
    if (p/'refinement-protocol.json').exists() and 'dms9' in runs:
        refinement=read(p/'refinement-protocol.json');main_name='dms'+str(refinement['main_for_detailed_display']);shown=refinement['shown']
        selection={'main':main_name,'shown':shown,'criterion':refinement['selection'],'coarse_count_selector':selection['main'],'stage':'Exploratory transition refinement; coarse results preserved'}
    write(p/'selection.json',selection);write(p/'metrics.json',results);write(p/'private-region-details.json',details)
    print(json.dumps({'selection':selection,'grid':{k:{f:v[f] for f in ['regions','assigned','largest','raw_modes']} for k,v in results.items()}},indent=2),flush=True)
    # Actual production centering per subset and exact Kotlin k-means baseline.
    cp=(src/'classpath.txt').read_text().strip();java=Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(g),str(p/'src/StabilityProbe.java')],check=True)
    original=np.fromfile(src/'audio25-meta75-input.f32',dtype='<f4').reshape(n,1344)
    stability=[];main_lab=np.array(read(g/f'private-{main_name}-labels.json'));base_raw=labels_from(baseline['raw_clusters'],n)
    for seed in [3,17,41]:
        ix=np.sort(np.random.default_rng(seed).choice(n,size=round(n*.9),replace=False));original[ix].tofile(g/f'private-delete{seed}-input.f32')
        subprocess.run([str(java/'java'),'-cp',str(g)+':'+cp,'StabilityProbe',str(g/f'private-delete{seed}-input.f32'),str(g/f'private-delete{seed}-centered.f32'),str(g/f'private-delete{seed}-kmeans.json')],check=True)
        sub=np.fromfile(g/f'private-delete{seed}-centered.f32',dtype='<f4').reshape(len(ix),1344)
        labs,_,inf=fit(sub,runs[main_name]['kappa']);assert inf['converged']
        base=read(g/f'private-delete{seed}-kmeans.json');bl=np.asarray(base['labels']);valid=(bl>=0)&(base_raw[ix]>=0)
        stability.append({'seed':seed,'retained':len(ix),'dms_raw_ari':float(adjusted_rand_score(main_lab[ix],labs)),'kmeans_raw_ari':float(adjusted_rand_score(base_raw[ix][valid],bl[valid])),'dms_modes':len(set(labs)),'kmeans_modes':len(set(bl[bl>=0])),'dms_seconds':inf['seconds'],'kmeans_seconds':base['seconds'],'rows':ix.tolist(),'dms_labels':labs.tolist(),'kmeans_labels':base['labels']})
        print(json.dumps({k:v for k,v in stability[-1].items() if k not in ['rows','dms_labels','kmeans_labels']}),flush=True)
    write(p/'private-stability.json',stability)
    # Stricter convergence and reversed input-order merge diagnostics for main.
    y=np.load(g/f'private-{main_name}-modes.npy');lab,yy,inf=fit(x,runs[main_name]['kappa'],initial=y,eps=1e-12)
    rev,_=mode_labels(y[::-1]);check={'strict_convergence':inf,'strict_vs_main_raw_ari':float(adjusted_rand_score(main_lab,lab)), 'reverse_mode_merge_ari':float(adjusted_rand_score(main_lab,rev[::-1]))}
    write(p/'verification-final.json',check)
    write(p/'results.json',{'population':n,'weights':[.25,.75],'selection':selection,'metrics':results,'stability':[{k:v for k,v in z.items() if k not in ['rows','dms_labels','kmeans_labels']} for z in stability],'verification':{**read(p/'verification-initial.json'),**check},'map_coordinates_unchanged':True,'app_changes':False})

if __name__=='__main__':main()
