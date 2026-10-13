#!/usr/bin/env python3
"""Freeze a full-library weight comparison and anonymous direct-audio MOSS tasks."""
import argparse, hashlib, json, random, subprocess, wave
from pathlib import Path
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from scipy.spatial.distance import cdist
from threadpoolctl import threadpool_limits

SEED=2026101321
def read(p): return json.loads(p.read_text())
def write(p,x): p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def unit(x): return x/np.maximum(np.linalg.norm(x,axis=1,keepdims=True),1e-12)
def nn(x):
    d=cdist(x,x,'sqeuclidean');np.fill_diagonal(d,np.inf)
    return np.argsort(d,axis=1,kind='stable')[:,:15]
def plscore(n,pls): return float(np.mean([np.isin(n[p],p).mean(1) for p in pls if len(p)>=4])) if len({len(p) for p in pls if len(p)>=4})==1 else float(np.mean(np.concatenate([np.isin(n[p],p).mean(1) for p in pls if len(p)>=4])))

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--snapshot',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--classpath-file',type=Path,required=True);ap.add_argument('--resume',action='store_true')
    a=ap.parse_args();threadpool_limits(limits=4);root=a.output;p=a.snapshot/'regions';out=root/'geometry';out.mkdir(parents=True,exist_ok=True)
    previous_plan=read(root/'private-plan.json') if (root/'private-plan.json').exists() else None
    if previous_plan is not None and not a.resume: raise ValueError('Frozen plan exists; use --resume only for identical allocation')
    rows=read(p/'private-cooked-metadata.json');ids=[r['id'] for r in rows];n=len(rows)
    variants=['a25','a50','a75'];rng=random.Random(SEED)
    plan={'ids':ids,'cases':[{'name':v,'dim':1344,'input':str(p/'audio25-meta75-input.f32'),'metadata_weight':1-int(v[1:])/100} for v in variants]}
    write(out/'private-input-plan.json',plan);cp=a.classpath_file.read_text().strip();java=Path('/opt/homebrew/opt/openjdk@21/bin')
    for cls in ['FusionRegionProbe','MapProjectionProbe']:
        subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(out),str(Path(__file__).with_name(cls+'.java'))],check=True)
    subprocess.run([str(java/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'FusionRegionProbe',str(a.snapshot),str(out),str(out/'private-input-plan.json')],check=True)
    old=read(p/'private-regions-audio25-meta75.json')['worlds'];control=read(out/'private-regions-a25.json')['worlds']
    assert {frozenset(w['members']) for w in old}=={frozenset(w['members']) for w in control}
    assert (out/'private-a25-used-input.f32').read_bytes()==(p/'audio25-meta75-input.f32').read_bytes()
    pls=[r['members'] for r in read(p/'private-playlists.json')];genres=[set(r.get('genreFamilies',[])) for r in rows]
    artists=np.array([(r.get('artist') or '').strip().casefold() for r in rows])
    matches=read(a.snapshot/'local-audio/private-matches.json');probes=read(a.snapshot/'local-audio/private-probes.json')
    assert len(matches)==n and all(m['row']==i and m['id']==ids[i] for i,m in enumerate(matches))
    available={i for i,m in enumerate(matches) if m.get('chosen') and m['chosen']['tier'].startswith('A') and Path(m['chosen']['path']).is_file() and probes[m['chosen']['path']]['duration_ms']>=12000}
    nonmusic=set(i for w in control if w['content']!='MUSIC' for i in w['members'])
    features={};neighbors={};worlds={};metrics={};coverage=[]
    for v in variants:
        f=out/f'private-{v}-used-input.f32';raw=np.fromfile(f,dtype='<f4').reshape(n,1344);x=unit(raw-raw.mean(0));features[v]=x;neighbors[v]=nn(x)
        worlds[v]=read(out/f'private-regions-{v}.json')['worlds']
        dest=out/f'private-{v}-2d.f32'
        subprocess.run([str(java/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'MapProjectionProbe',str(f),str(dest),'1344'],check=True,capture_output=True)
        xy=np.fromfile(dest,dtype='<f4').reshape(n,2);assert np.isfinite(xy).all()
        mem=[i for w in worlds[v] for i in w['members']];assert len(mem)==len(set(mem))
        known=sum(bool(genres[i]) for i in mem);dominant=sum(max(Counter(g for i in w['members'] for g in genres[i]).values(),default=0) for w in worlds[v])
        metrics[v]={'tracks':n,'regions':len(worlds[v]),'assigned':len(mem),'outside':n-len(mem),'playlist_at15':plscore(neighbors[v],pls),'map_playlist_at15':plscore(nn(xy),pls),'same_artist_at15':float((artists[neighbors[v]]==artists[:,None]).mean()),'dominant_genre_coverage':dominant/known}
        coverage.extend({'variant':v,'region':j,'content':w['content'],'population':len(w['members']),'available':len(set(w['members'])&available)} for j,w in enumerate(worlds[v]))
    write(root/'geometry-summary.json',{'metrics':metrics,'exact_baseline_replay':True,'strong_available_at_least12s':len(available),'region_audio_coverage':coverage})
    tasks=[];qa=[]
    def add(kind,refs,**meta):
        t={'kind':kind,'refs':refs,**meta};tasks.append(t);return t
    # Equal anchor populations, original full-catalogue ranks: never fill a missing top neighbor with a lower available one.
    eligibility={};anchors=[]
    for rank in [1,3,5]:
        eligible=sorted(i for i in available-nonmusic if all(int(neighbors[v][i,rank-1]) in available-nonmusic for v in variants))
        eligibility[str(rank)]={'eligible_anchors':len(eligible),'available_music_anchors':len(available-nonmusic)}
        chosen=rng.sample([i for i in eligible if i not in anchors],min(12,len(set(eligible)-set(anchors))))
        anchors.extend(chosen)
        for i in chosen:
            cand=[int(neighbors[v][i,rank-1]) for v in variants]
            for position in [0,1]:
                order=list(range(3));rng.shuffle(order)
                add('neighbor',[(i,position)]+[(cand[j],position) for j in order],role='main_neighbor',anchor=i,rank=rank,position=position,variant_order=[variants[j] for j in order])
    # Every supported region: 1 central, 1 boundary, 3 uniform other available members. Identical track set at two song positions.
    region_samples=[]
    for v in variants:
        centroids=unit(np.array([features[v][w['members']].mean(0) for w in worlds[v]]));sim=features[v]@centroids.T
        for j,w in enumerate(worlds[v]):
            eligible=sorted(set(w['members'])&available)
            if len(eligible)<5:continue
            center=max(eligible,key=lambda i:(sim[i,j],-i))
            boundary=min((i for i in eligible if i!=center),key=lambda i:(sim[i,j]-np.max(np.delete(sim[i],j)),i))
            chosen=[center,boundary]+rng.sample([i for i in eligible if i not in [center,boundary]],3)
            rng.shuffle(chosen);region_samples.append({'variant':v,'region':j,'content':w['content'],'members':chosen})
            for position in [0,1]:add('region',[(i,position) for i in chosen],role='main_region',variant=v,region=j,content=w['content'],position=position)
        # Three seeded random partitions of the same sampled MUSIC-track pool, diagnostic controls only.
        pool=[i for s in region_samples if s['variant']==v and s['content']=='MUSIC' for i in s['members']]
        for k in range(3):
            chosen=rng.sample(sorted(set(pool)),5)
            for position in [0,1]:add('region',[(i,position) for i in chosen],role='random_region',variant=v,control_index=k,position=position)
    # Mirror a fixed random subset, preserving prompts and recordings, to expose position sensitivity.
    for source in rng.sample([t for t in tasks if t['role']=='main_neighbor' and t['position']==0],6):
        add('neighbor',[source['refs'][0]]+list(reversed(source['refs'][1:])),role='neighbor_order_repeat',source_ref=source,variant_order=list(reversed(source['variant_order'])))
    for source in rng.sample([t for t in tasks if t['role']=='main_region' and t['position']==0],6):
        add('region',list(reversed(source['refs'])),role='region_order_repeat',source_ref=source)
    # Judge qualification: exact match must beat silence, duplicate candidates must tie, identical groups must be coherent.
    audio=unit(np.fromfile(p/'audio.f32',dtype='<f4').reshape(n,960));controls=rng.sample(sorted(available-nonmusic),3)
    for i in controls:
        far=min(available-nonmusic,key=lambda j:float(audio[i]@audio[j]))
        order=[0,1,2];rng.shuffle(order);cand=[(i,0),(-1,0),(far,0)]
        qa.append({'kind':'neighbor','refs':[(i,0)]+[cand[j] for j in order],'role':'qa_exact','exact_index':order.index(0),'silent_index':order.index(1)})
        qa.append({'kind':'neighbor','refs':[(i,0)]+[(far,0)]*3,'role':'qa_equal'})
        qa.append({'kind':'region','refs':[(i,0)]*5,'role':'qa_identical'})
        qa.append({'kind':'region','refs':[(-1,0)]*5,'role':'qa_silence'})
    rng.shuffle(tasks);rng.shuffle(qa);alltasks=qa+tasks
    for j,t in enumerate(alltasks):t['task_id']=f't{j+1:04d}'
    for t in alltasks:
        if 'source_ref' in t:t['repeat_of']=t.pop('source_ref')['task_id']
    refs=sorted({tuple(r) for t in alltasks for r in t['refs']});rng.shuffle(refs)
    clipmap={r:f'c{j+1:04d}' for j,r in enumerate(refs)}
    protocol={'seed':SEED,'weights_audio':[.25,.5,.75],'library_rows':n,'geometry':'Exact Kotlin fusion, regions and PCA/t-SNE; fixed source snapshot, unchanged thresholds.','neighbor_sampling':'12 uniform distinct anchors for each rank 1/3/5, among strong local matches with true same-rank neighbors available in all three variants. No reranking to available catalogue. Two 12s positions per track.','neighbor_eligibility':eligibility,'region_sampling':'Every region with >=5 strong available members: 1 central + 1 lowest final-world cosine margin + 3 uniform; same five at two positions. MUSIC primary, special content separate.','judge':'Direct 4-audio neighbor and 5-audio region prompts; greedy BF16 pinned MOSS-Music-8B-Instruct. No captions, names, genres, weights or conditions supplied.','audio':'Mono16k PCM16, stripped metadata; 12s centered at30%/65% of song; preserve original gain.','qa_gate':'All12 control answers parse, 3/3 exact scores >=3 and >silence, 3/3 equal candidate spreads <=1, 3/3 identical groups >=3, 3/3 silent groups not assessable. Failure blocks winner claims; retain all outputs.','analysis':'Average repeated positions within anchor/region first. Neighbor matched differences and per-anchor bootstrap descriptive. Regions equal-region macro plus coverage; random groups diagnostic only, regions unpaired between variants. Order repeats reported. No population accuracy or significance claim.','task_counts':dict(Counter(t['role'] for t in alltasks)),'limits':['One personal library; audio only where strong local matches exist. Missing areas excluded and counted, not assumed successful.','Local matches use filename,size,duration; phone audio hashes unavailable.','Model opinion is not human listening; multi-audio qualification and order checks required.','Repeated tracks/regions/anchors not independent. 12s excerpts do not represent full songs.']}
    if previous_plan is not None: assert previous_plan==json.loads(json.dumps(alltasks)) and read(root/'protocol.json')==protocol,'Frozen allocation mismatch'
    write(root/'protocol.json',protocol);write(root/'private-plan.json',alltasks);write(root/'private-region-samples.json',region_samples)
    clips=root/'upload/clips';clips.mkdir(parents=True,exist_ok=True)
    def render(ref):
        i,position=ref;cid=clipmap[ref];dest=clips/(cid+'.wav')
        if i<0:
            with wave.open(str(dest),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(bytes(12*16000*2))
            return {'row':i,'position':position,'clip_id':cid,'sha256':sha(dest)}
        chosen=matches[i]['chosen'];path=Path(chosen['path']);assert sha(path)==chosen['local_sha256']
        duration=probes[str(path)]['duration_ms']/1000;start=max(0,min(duration-12,duration*[.3,.65][position]-6))
        subprocess.run(['/opt/homebrew/bin/ffmpeg','-nostdin','-hide_banner','-loglevel','error','-y','-ss',str(start),'-i',str(path),'-t','12','-map','0:a:0','-vn','-sn','-dn','-map_metadata','-1','-map_chapters','-1','-ac','1','-ar','16000','-c:a','pcm_s16le','-threads','1','-bitexact',str(dest)],check=True,timeout=60,capture_output=True)
        with wave.open(str(dest),'rb') as w:
            frames=w.getnframes();assert w.getnchannels()==1 and w.getframerate()==16000 and abs(frames-192000)<=3200
        return {'row':i,'position':position,'clip_id':cid,'sha256':sha(dest),'source_sha256':chosen['local_sha256'],'offset_seconds':start,'actual_seconds':frames/16000}
    with ThreadPoolExecutor(max_workers=6) as pool:mapping=list(pool.map(render,refs))
    write(root/'private-clips.json',mapping)
    blind=[{'task_id':t['task_id'],'kind':t['kind'],'clips':[clipmap[tuple(r)]+'.wav' for r in t['refs']]} for t in alltasks]
    (root/'upload/tasks.jsonl').write_text(''.join(json.dumps(t)+'\n' for t in blind))
    write(root/'preparation-summary.json',{'protocol_sha256':sha(root/'protocol.json'),'plan_sha256':sha(root/'private-plan.json'),'tasks_sha256':sha(root/'upload/tasks.jsonl'),'tracks':len({r[0] for r in refs if r[0]>=0}),'clips':len(refs),'tasks':len(alltasks),'qa_tasks':len(qa),'counts':protocol['task_counts'],'clip_sha256':{r['clip_id']+'.wav':r['sha256'] for r in mapping}})
    print(json.dumps({'metrics':metrics,'task_counts':protocol['task_counts'],'clips':len(refs),'neighbor_eligibility':eligibility},indent=2))
if __name__=='__main__':main()
