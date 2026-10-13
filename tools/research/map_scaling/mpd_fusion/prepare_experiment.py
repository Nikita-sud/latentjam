#!/usr/bin/env python3
"""Freeze artist/audio-group and deduplicated-playlist splits before model training.

MPD's cached event table is used ONLY for playlist membership. Its synthetic
play times, completions and users are not treated as observed listening behavior.
"""
import argparse
import hashlib
import json
from pathlib import Path
import unicodedata
import numpy as np
import pandas as pd
from sklearn.model_selection import GroupShuffleSplit
from refresh_mpd import sha, write_json


def key(x):
    return ' '.join(unicodedata.normalize('NFKC',x or '').casefold().split())

def grouped(rows):
    parent=list(range(len(rows)));seen={}
    def root(i):
        while parent[i]!=i:
            parent[i]=parent[parent[i]];i=parent[i]
        return i
    for i,r in enumerate(rows):
        for token in [('artist',key(r['artist'])),('audio',r['sha256'])]:
            if token in seen:parent[root(i)]=root(seen[token])
            else:seen[token]=i
    return np.array([root(i) for i in range(len(rows))])

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--data',type=Path,required=True)
    ap.add_argument('--events',type=Path,required=True)
    ap.add_argument('--personal-metadata',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True)
    a=ap.parse_args();out=a.output;out.mkdir(parents=True,exist_ok=True)
    rows=json.loads((a.data/'private-rows.json').read_text())
    personal=json.loads(a.personal_metadata.read_text());personal_artists={key(r.get('artist')) for r in personal if key(r.get('artist'))}
    n=len(rows);groups=grouped(rows)
    blocked_groups={groups[i] for i,r in enumerate(rows) if key(r['artist']) in personal_artists}
    eligible=np.array([i for i in range(n) if groups[i] not in blocked_groups])
    tri, remi=next(GroupShuffleSplit(n_splits=1,test_size=.2,random_state=20261013).split(eligible,groups=groups[eligible]))
    train,rem=eligible[tri],eligible[remi]
    vi,ti=next(GroupShuffleSplit(n_splits=1,test_size=.5,random_state=20261014).split(rem,groups=groups[rem]))
    val,test=rem[vi],rem[ti]
    split=np.full(n,-1,dtype=np.int8)
    for j,ind in enumerate([train,val,test]):split[ind]=j
    assert not (set(groups[train])&set(groups[val]) or set(groups[train])&set(groups[test]) or set(groups[val])&set(groups[test]))
    assert not {key(rows[i]['artist']) for i in np.concatenate([train,val,test])}&personal_artists
    id_to_row={r['track_id']:i for i,r in enumerate(rows)}
    events=pd.read_parquet(a.events,columns=['session_id','track_id'])
    events['row']=events.track_id.map(id_to_row)
    missing=int(events.row.isna().sum());events=events.dropna(subset=['row']);events['row']=events.row.astype(int)
    playlists=[];seen=set();empty=0
    for sid,g in events.groupby('session_id',sort=True):
        all_members=tuple(sorted(set(g.row.tolist())))
        if len(all_members)<4:empty+=1;continue
        # Hash the complete available membership, BEFORE artist filtering: duplicate playlists
        # necessarily get the same split and are retained once.
        identity=hashlib.sha256(','.join(map(str,all_members)).encode()).hexdigest()
        if identity in seen:continue
        seen.add(identity)
        bucket=int(identity[:8],16)%10
        section=0 if bucket<8 else 1 if bucket==8 else 2
        members=[i for i in all_members if split[i]>=0]
        if len(members)>=4:playlists.append(dict(id=identity,split=section,members=members))
    training=[]
    for p in playlists:
        mem=[i for i in p['members'] if split[i]==0]
        if p['split']==0 and len(mem)>=2 and len(set(groups[mem]))>=2:training.append(mem)
    # Each held-out playlist contributes at most two cold-artist query tracks, each ranked
    # against the SAME training catalogue. Other available training members are relevant.
    # We sample playlists uniformly, never via metrics, and cap runtime before any fitting.
    rng=np.random.default_rng(20261015)
    evals={}
    for section,name in [(1,'validation'),(2,'test')]:
        candidates=[]
        for p in playlists:
            if p['split']!=section:continue
            positives=sorted(i for i in p['members'] if split[i]==0)
            queries=sorted(i for i in p['members'] if split[i]==section)
            if len(positives)<3 or not queries:continue
            selected=rng.choice(queries,size=min(2,len(queries)),replace=False).tolist()
            candidates.append({'playlist':p['id'],'queries':selected,'positives':positives})
        if len(candidates)>1500:
            candidates=[candidates[i] for i in sorted(rng.choice(len(candidates),1500,replace=False))]
        evals[name]=candidates
    assert training and len(evals['validation'])>100 and len(evals['test'])>100
    assert set(p['playlist'] for p in evals['validation']).isdisjoint(p['playlist'] for p in evals['test'])
    np.savez(out/'private-splits.npz',train=train,validation=val,test=test,groups=groups,split=split)
    write_json(out/'private-training-playlists.json',training)
    write_json(out/'private-evaluation.json',evals)
    policy={'frozen_before_training':True,'seeds':[20261013,20261014,20261015],
        'track_split':'Approximate 80/10/10 grouped primary artist names and identical audio-file SHA-256; all groups matching personal-library primary artists excluded.',
        'playlist_split':'Deduplicated complete available membership SHA-256, buckets 0-7 train,8 validation,9 test; disjoint.',
        'train_signal':'Only train playlist memberships intersected with train tracks; different-artist positive pairs, uniform playlist sampling.',
        'evaluation':'Up to 1500 held-out playlists per split; up to 2 held-out-artist queries per playlist, >=3 relevant training tracks, rank entire training catalogue. Macro-average within playlist then across playlists.',
        'selection':'Checkpoint and family selection by validation macro nDCG@20. Test evaluated after all model fits and selection are frozen.',
        'model_grid':{'output_dimensions':[64,128],'model_seeds':[3,17,41],'families':['two_branch_MLP','supervised_linear'],'epochs':40,'steps_per_epoch':80,'batch_pairs':128,'lr':.001,'weight_decay':.001,'temperature':.12,'modality_dropout_each':.1,'early_stop_validation_checks':4,'validation_every_epochs':5},
        'baselines':['fixed_audio25_metadata75_train_centered','PCA64_train_fit','PCA128_train_fit','training_playlist_popularity','audio_only','metadata_only'],
        'limits':['Existing local MPD subset and preview audio, not the full million-playlist corpus.','Artist aliases and all featured credits not exhaustively resolved.','Frozen encoders have their prior training history; only the new fusion-head split is controlled here.','Playlist co-occurrence is weak supervision, not judged perceptual similarity.','Personal-library audit is previously inspected descriptive context; no model selection on it.']}
    write_json(out/'protocol.json',policy)
    summary={'catalogue_rows':n,'excluded_personal_artist_rows':int((split<0).sum()),
        'splits':{name:{'tracks':len(ind),'groups':len(set(groups[ind]))} for name,ind in [('train',train),('validation',val),('test',test)]},
        'source_event_rows':len(events)+missing,'unmatched_event_rows':missing,'unique_source_playlists':int(events.session_id.nunique()),'deduplicated_eligible_playlists':len(playlists),
        'train_playlists':len(training),'train_memberships':sum(map(len,training)),
        'evaluation':{name:{'playlists':len(v),'query_memberships':sum(len(p['queries']) for p in v),'unique_queries':len(set(q for p in v for q in p['queries']))} for name,v in evals.items()},
        'source_sha256':{'events':sha(a.events),'personal_metadata':sha(a.personal_metadata),'private_rows':sha(a.data/'private-rows.json')},
        'split_sha256':sha(out/'private-splits.npz'),'protocol_sha256':sha(out/'protocol.json')}
    write_json(out/'split-summary.json',summary);print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
