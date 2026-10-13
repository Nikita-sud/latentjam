#!/usr/bin/env python3
"""Validate pairing, blindness and frozen source/output provenance."""
import json,hashlib,sys,wave,struct
from pathlib import Path
from collections import Counter
import numpy as np
from scipy.spatial.distance import cdist

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
p=Path(sys.argv[1]);read=lambda q:json.loads(q.read_text())
plan=read(p/'private-plan.json');prep=read(p/'preparation-summary.json');mapping=read(p/'private-clips.json')
blind=[json.loads(l) for l in (p/'upload/tasks.jsonl').read_text().splitlines()]
assert len(plan)==len(blind)==200 and len({t['task_id'] for t in plan})==200
lookup={(r['row'],r['position']):r['clip_id']+'.wav' for r in mapping};assert len(lookup)==len(mapping)==509
for t,b in zip(plan,blind):
    assert set(b)=={'task_id','kind','clips'} and b['task_id']==t['task_id'] and b['kind']==t['kind']
    assert b['clips']==[lookup[tuple(r)] for r in t['refs']]
assert prep['plan_sha256']==sha(p/'private-plan.json') and prep['tasks_sha256']==sha(p/'upload/tasks.jsonl') and prep['protocol_sha256']==sha(p/'protocol.json')
for name,expected in prep['clip_sha256'].items():
    f=p/'upload/clips'/name;assert sha(f)==expected;b=f.read_bytes();at=12;chunks=[]
    while at+8<=len(b):
        tag=b[at:at+4];n=struct.unpack_from('<I',b,at+4)[0];chunks.append(tag);at+=8+n+n%2
    assert set(chunks)<=set([b'fmt ',b'data'])
    with wave.open(str(f),'rb') as w:assert w.getnchannels()==1 and w.getsampwidth()==2 and w.getframerate()==16000 and 11.8<=w.getnframes()/16000<=12
nn={}
for v in ['a25','a50','a75']:
    x=np.fromfile(p/'geometry'/f'private-{v}-used-input.f32',dtype='<f4').reshape(1107,1344);x=x-x.mean(0);x=x/np.linalg.norm(x,axis=1,keepdims=True)
    d=cdist(x,x,'sqeuclidean');np.fill_diagonal(d,np.inf);nn[v]=np.argsort(d,axis=1,kind='stable')[:,:15]
counts=Counter()
for t in plan:
    if t['role']=='main_neighbor':
        assert t['refs'][0]==[t['anchor'],t['position']]
        for ref,v in zip(t['refs'][1:],t['variant_order']):assert ref==[int(nn[v][t['anchor'],t['rank']-1]),t['position']]
        counts[t['anchor']]+=1
assert len(counts)==36 and set(counts.values())=={2}
summary={'tasks':len(plan),'clips':len(mapping),'all_wavs_hashed_and_metadata_free':True,'anonymous_upload_schema_verified':True,'true_full_library_neighbor_ranks_verified':True,'paired_anchors':36,'two_positions_each':True,'frozen_allocation_hashes_verified':True,'baseline_exact_replay':read(p/'geometry-summary.json')['exact_baseline_replay'],'neighbor_overlap_at15_vs25':{v:float(np.mean([len(set(a)&set(b))/15 for a,b in zip(nn['a25'],nn[v])])) for v in ['a50','a75']}}
(p/'verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
