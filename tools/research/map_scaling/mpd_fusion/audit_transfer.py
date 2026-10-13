#!/usr/bin/env python3
"""Independent, descriptive personal-library map/region transfer and query-swap audit.

No personal row trains or selects a fusion checkpoint. The saved library was
inspected previously, so this is diagnostic transfer context, not a new blind test.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess
import numpy as np
from scipy.spatial.distance import cdist
from sklearn.manifold import trustworthiness
from threadpoolctl import threadpool_limits
import torch
from refresh_mpd import sha,write_json
from train_fusion import unit,Fusion,encode,evaluate


def neighbors(features,k=15):
    d=cdist(features,features,'sqeuclidean');np.fill_diagonal(d,np.inf)
    return np.argsort(d,axis=1,kind='stable')[:,:k]

def playlist_score(near,playlists):
    values=[]
    for p in playlists:
        if len(p)<4:continue
        values.extend(np.isin(near[p],p).mean(1))
    return float(np.mean(values)),len(values)

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--data',type=Path,required=True);ap.add_argument('--experiment',type=Path,required=True)
    ap.add_argument('--personal-snapshot',type=Path,required=True);ap.add_argument('--classpath-file',type=Path,required=True)
    ap.add_argument('--java-bin',type=Path,default=Path('/opt/homebrew/opt/openjdk@21/bin'))
    a=ap.parse_args();p=a.personal_snapshot/'regions';out=a.experiment/'personal-transfer';out.mkdir(exist_ok=True)
    torch.set_num_threads(4);threadpool_limits(limits=4)
    selection=json.loads((a.experiment/'frozen-selection.json').read_text())['models']
    audio=np.fromfile(p/'audio.f32',dtype='<f4').reshape(-1,960);meta=np.fromfile(p/'metadata.f32',dtype='<f4').reshape(-1,384)
    complete=(np.linalg.norm(audio,axis=1)>1e-3)&(np.linalg.norm(meta,axis=1)>1e-3)
    original=np.flatnonzero(complete);remap={int(old):i for i,old in enumerate(original)}
    rows=[r for i,r in enumerate(json.loads((p/'private-cooked-metadata.json').read_text())) if complete[i]]
    audio,meta=unit(audio[complete]),unit(meta[complete]);x=np.concatenate((audio,meta),axis=1)
    raw=np.concatenate((np.sqrt(.25)*audio,np.sqrt(.75)*meta),axis=1)
    # Exact Kotlin-produced fused rows for the production control (same complete population).
    production_raw=np.fromfile(p/'audio25-meta75-input.f32',dtype='<f4').reshape(-1,1344)[complete]
    assert np.max(np.abs(production_raw-raw))<2e-6
    source={'current_1344':production_raw}
    mean=np.load(a.experiment/'private-preprocessing.npz')['fusion_mean'];reference=unit(raw-mean)
    for dim in [64,128]:
        pc=np.load(a.experiment/f'private-pca{dim}.npz');source[f'pca{dim}']=unit((reference-pc['mean'])@pc['components'].T)
    for label,name in selection.items():
        ck=torch.load(a.experiment/'checkpoints'/f'{name}.pt',map_location='cpu',weights_only=True)
        model=Fusion(ck['dim'],ck['family']);model.load_state_dict(ck['state_dict']);source[label]=encode(model,x)
    cp=a.classpath_file.read_text().strip()
    src=Path(__file__).parent
    for cls in ['MapProjectionProbe','FusionRegionProbe']:
        subprocess.run([str(a.java_bin/'javac'),'-cp',cp,'-d',str(out),str(src/f'{cls}.java')],check=True)
    plan={'ids':[r['id'] for r in rows],'cases':[]}
    for name,features in source.items():
        inp=out/f'private-{name}-input.f32';features.astype('<f4').tofile(inp)
        plan['cases'].append({'name':name,'dim':features.shape[1],'input':str(inp)})
    write_json(out/'private-region-plan.json',plan)
    subprocess.run([str(a.java_bin/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'FusionRegionProbe',str(a.personal_snapshot),str(out),str(out/'private-region-plan.json')],check=True)
    playlists=[[remap[i] for i in r['members'] if i in remap] for r in json.loads((p/'private-playlists.json').read_text())]
    genres=[set(r.get('genreFamilies',[])) for r in rows]
    artists=np.array([(r.get('artist') or '').strip().casefold() for r in rows])
    results={}
    for case in plan['cases']:
        name=case['name'];features=source[name];centered=unit(features-features.mean(0))
        projected=out/f'private-{name}-2d.f32'
        run=subprocess.run([str(a.java_bin/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'MapProjectionProbe',case['input'],str(projected),str(case['dim'])],check=True,capture_output=True,text=True)
        xy=np.fromfile(projected,dtype='<f4').reshape(len(rows),2);assert np.isfinite(xy).all()
        near=neighbors(centered);near2=neighbors(xy);score,count=playlist_score(near,playlists)
        world=json.loads((out/f'private-regions-{name}.json').read_text())
        sizes=[len(w['members']) for w in world['worlds']];known=0;matching=0;region_memberships=[]
        for w in world['worlds']:
            mem=w['members'];freq=Counter(g for i in mem for g in genres[i]);known+=sum(bool(genres[i]) for i in mem)
            matching+=max(freq.values(),default=0)
            for pl in playlists:
                queries=set(mem)&set(pl)
                if queries and len(pl)>=4:region_memberships.extend([(len(queries)-1)/(len(mem)-1)]*len(queries))
        results[name]={'dimensions':case['dim'],'playlist_at15':score,'playlist_query_memberships':count,
            'same_artist_at15':float((artists[near]==artists[:,None]).mean()),
            'map_playlist_at15':playlist_score(near2,playlists)[0],
            'map_neighbor_recall_at15':float(np.mean([len(set(near[i])&set(near2[i]))/15 for i in range(len(rows))])),
            'map_trustworthiness_at15':float(trustworthiness(centered,xy,n_neighbors=15)),
            'projection_seconds':json.loads(run.stdout)['seconds'],
            'regions':len(sizes),'assigned_tracks':sum(sizes),'largest_region':max(sizes,default=0),
            'assigned_genre_known':known,'dominant_genre_coverage':matching/known if known else None,
            'within_region_playlist_agreement':float(np.mean(region_memberships)) if region_memberships else None,
            'within_region_playlist_memberships':len(region_memberships),
            'mean_coordinate_std':float(features.std(0).mean())}
        print(json.dumps({'transfer':name,**results[name]}),flush=True)
    write_json(out/'transfer-summary.json',{'rows':len(rows),'complete_modalities_only':True,'metrics':results,
        'selection':selection,'limits':['Previously inspected personal library; descriptive audit, not independent blind assessment.','Primary artists present here excluded from MPD head fitting/validation/test; aliases and collaboration credits not exhaustive.','Kotlin production clustering, routing, naming and PCA/t-SNE applied unchanged to each candidate representation.','Playlist and genre agreement are metadata proxies, not listening judgments.','MPD has preview audio and artist-only text with facts; personal library has fuller metadata and full-song window sampling.']})
    # Query swaps diagnose whether the representation meaningfully conditions recommendations.
    mpd=np.load(a.data/'vectors.npz');ma,mt=unit(mpd['audio']),unit(mpd['metadata'])
    full=unit(np.concatenate((np.sqrt(.25)*ma,np.sqrt(.75)*mt),axis=1)-mean)
    split=np.load(a.experiment/'private-splits.npz');train=split['train']
    test_cases=json.loads((a.experiment/'private-evaluation.json').read_text())['test']
    queries=np.array(sorted({q for r in test_cases for q in r['queries']}));rng=np.random.default_rng(101313)
    permutations=[rng.permutation(len(queries)) for _ in range(5)]
    audit={}
    for label,feature in [('current_1344',full)]+[(label,np.load(a.experiment/f'private-{name}.npy')) for label,name in selection.items()]:
        actual=evaluate(feature,train,test_cases)[0]
        shuffled=[]
        for permutation in permutations:
            swapped=feature.copy();swapped[queries]=feature[queries[permutation]]
            shuffled.append(evaluate(swapped,train,test_cases)[0]['ndcg_at20'])
        audit[label]={'actual_ndcg20':actual['ndcg_at20'],'query_swapped_ndcg20_mean':float(np.mean(shuffled)),
                      'query_swapped_ndcg20_range':[min(shuffled),max(shuffled)],'permutations':5}
    write_json(a.experiment/'query-swap-audit.json',audit)
    print(json.dumps({'query_swap_audit':audit}),flush=True)

if __name__=='__main__':main()
