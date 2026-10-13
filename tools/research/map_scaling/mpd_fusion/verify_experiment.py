#!/usr/bin/env python3
"""Verify actual split isolation, saved vectors/checkpoints and ranking arithmetic."""
import argparse
import json
from pathlib import Path
import numpy as np
from sklearn.metrics import ndcg_score
from threadpoolctl import threadpool_limits
import torch
from prepare_experiment import key
from train_fusion import unit,evaluate,Fusion,encode,multi_positive_loss
from refresh_mpd import sha,write_json


def main():
    ap=argparse.ArgumentParser();ap.add_argument('data',type=Path);ap.add_argument('experiment',type=Path);ap.add_argument('personal_metadata',type=Path)
    a=ap.parse_args();threadpool_limits(limits=4);torch.set_num_threads(4)
    rows=json.loads((a.data/'private-rows.json').read_text());personal=json.loads(a.personal_metadata.read_text())
    split=np.load(a.experiment/'private-splits.npz');sets=[split[n] for n in ['train','validation','test']]
    for field in ['artist','sha256']:
        keys=[{key(rows[i][field]) for i in indices} for indices in sets]
        assert not (keys[0]&keys[1] or keys[0]&keys[2] or keys[1]&keys[2]),field
    personal_artists={key(r.get('artist')) for r in personal if r.get('artist')}
    assert not {key(rows[i]['artist']) for indices in sets for i in indices}&personal_artists
    train=set(sets[0]);train_playlists=json.loads((a.experiment/'private-training-playlists.json').read_text())
    assert all(set(p)<=train for p in train_playlists)
    evaluation=json.loads((a.experiment/'private-evaluation.json').read_text())
    for name,allowed in [('validation',set(sets[1])),('test',set(sets[2]))]:
        assert all(set(p['queries'])<=allowed and set(p['positives'])<=train for p in evaluation[name])
    assert not {p['playlist'] for p in evaluation['validation']}&{p['playlist'] for p in evaluation['test']}
    v=np.load(a.data/'vectors.npz');assert v['valid'].all()
    assert np.isfinite(v['audio']).all() and np.isfinite(v['metadata']).all()
    # The audio cache preserves Kotlin's sequential float32 sum of 960 squares.
    # Validate its rounding envelope in float64; training independently unitizes rows.
    audio_norm_error=float(np.max(np.abs(np.linalg.norm(v['audio'].astype(float),axis=1)-1)))
    assert audio_norm_error<1e-5
    assert np.max(np.abs(np.linalg.norm(v['metadata'],axis=1)-1))<2e-6
    results=json.loads((a.experiment/'test-summary.json').read_text());training=json.loads((a.experiment/'training-summary.json').read_text())
    selection=json.loads((a.experiment/'frozen-selection.json').read_text());assert selection['training_summary_sha256']==sha(a.experiment/'training-summary.json')
    assert len(training['models'])==12
    x=np.concatenate((unit(v['audio']),unit(v['metadata'])),axis=1)
    max_replay_error=0.
    for m in training['models']:
        path=a.experiment/'checkpoints'/f"{m['name']}.pt";assert sha(path)==m['checkpoint_sha256']
        ck=torch.load(path,map_location='cpu',weights_only=True);model=Fusion(ck['dim'],ck['family']);model.load_state_dict(ck['state_dict'])
        # Same batch size as the original inference; sample the first batch.
        actual=encode(model,x[:1024]);saved=np.load(a.experiment/f"private-{m['name']}.npy")
        error=float(np.max(np.abs(actual-saved[:1024])));max_replay_error=max(max_replay_error,error)
        assert error<1e-6 and saved[sets[0]].std(0).mean()>1e-4
    # Independent scikit-learn nDCG cross-check on actual test queries with no top-20 score ties.
    name=selection['models']['two_branch_MLP-64'];feat=np.load(a.experiment/f'private-{name}.npy')
    max_difference=0.;checked=0
    for case in evaluation['test']:
        for query in case['queries']:
            scores=feat[query]@feat[sets[0]].T
            ordered=np.sort(scores)[-21:]
            if np.any(np.diff(ordered)==0):continue
            relevance=np.isin(sets[0],case['positives']).astype(float)
            expected=ndcg_score(relevance[None],scores[None],k=20,ignore_ties=True)
            measured=evaluate(feat,sets[0],[dict(case,queries=[query])])[0]['ndcg_at20']
            max_difference=max(max_difference,abs(expected-measured));assert abs(expected-measured)<1e-12
            checked+=1
            if checked==25:break
        if checked==25:break
    assert checked==25
    # A multi-positive objective must remain finite when positives share labels,
    # and exactly zero if every allowed target is positive.
    emb=torch.eye(3,requires_grad=True);mask=torch.ones((3,3),dtype=torch.bool)
    zero=multi_positive_loss(emb,emb,mask,mask);assert abs(float(zero.detach()))<1e-7
    loss=multi_positive_loss(emb,emb,torch.eye(3,dtype=torch.bool),mask);loss.backward();assert torch.isfinite(emb.grad).all()
    check={'split_isolation':'Passed for normalized primary artists and exact audio SHA-256; personal primary artists excluded.',
           'training_playlist_rows_only':True,'all_vectors_finite_and_unit':True,'checkpoints_verified':12,
           'audio_norm_max_error_float64':audio_norm_error,'audio_norm_tolerance':1e-5,
           'checkpoint_replay_max_absolute_error':max_replay_error,'ndcg_sklearn_actual_queries':checked,
           'ndcg_max_absolute_difference':max_difference,'multi_positive_loss_gradient_checks':True,
           'limits':'Artist aliases and all collaboration credits remain an explicit limitation.'}
    write_json(a.experiment/'verification.json',check);print(json.dumps(check,indent=2))

if __name__=='__main__':main()
