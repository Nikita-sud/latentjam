#!/usr/bin/env python3
"""Train experimental playlist-supervised fusion heads; no app/release changes.

Validation chooses checkpoints. A separate --evaluate-test invocation is required
AFTER every fit completes, preventing accidental test-based checkpoint selection.
"""
import argparse
import copy
import json
from pathlib import Path
import time
import numpy as np
from sklearn.decomposition import PCA
from threadpoolctl import threadpool_limits
import torch
from torch import nn
from torch.nn import functional as F
from refresh_mpd import sha, write_json


def unit(x):
    norm=np.linalg.norm(x,axis=1,keepdims=True)
    if not np.isfinite(x).all() or (norm<1e-10).any():raise ValueError('Invalid feature row')
    return (x/norm).astype(np.float32)

class Fusion(nn.Module):
    def __init__(self,dim,family):
        super().__init__();self.dim=dim;self.family=family
        if family=='two_branch_MLP':
            self.audio=nn.Sequential(nn.Linear(960,256),nn.LayerNorm(256),nn.GELU())
            self.meta=nn.Sequential(nn.Linear(384,128),nn.LayerNorm(128),nn.GELU())
            self.joint=nn.Sequential(nn.Linear(386,256),nn.GELU(),nn.Dropout(.1),nn.Linear(256,dim))
        else:self.joint=nn.Linear(1346,dim)
    def forward(self,x):
        audio,meta=x[:,:960],x[:,960:]
        mask=torch.ones((len(x),2),dtype=x.dtype,device=x.device)
        if self.training:
            r=torch.rand(len(x),device=x.device)
            mask[:,0]=(r>=.1);mask[:,1]=((r<.1)|(r>=.2))
        if self.family=='two_branch_MLP':audio,meta=self.audio(audio),self.meta(meta)
        z=self.joint(torch.cat((audio*mask[:,:1],meta*mask[:,1:],mask),dim=1))
        return F.normalize(z,dim=1)


def multi_positive_loss(a,b,positive,allowed,temperature=.12):
    assert torch.all(torch.diag(positive)) and torch.all(positive<=allowed)
    logits=(a@b.T)/temperature
    denom=logits.masked_fill(~allowed,-1e9)
    numer=logits.masked_fill(~positive,-1e9)
    return .5*((torch.logsumexp(denom,dim=1)-torch.logsumexp(numer,dim=1)).mean()+
               (torch.logsumexp(denom,dim=0)-torch.logsumexp(numer,dim=0)).mean())


def encode(model,x):
    model.eval()
    with torch.no_grad():
        result=np.concatenate([model(torch.from_numpy(x[i:i+1024])).numpy() for i in range(0,len(x),1024)])
    assert np.isfinite(result).all() and np.max(np.abs(np.linalg.norm(result,axis=1)-1))<1e-4
    return result


def evaluate(features,train,cases,popularity=None):
    queries=np.array(sorted({q for p in cases for q in p['queries']}))
    train_set=set(train)
    assert not set(queries)&train_set
    qlookup={int(q):i for i,q in enumerate(queries)}
    recommendations=[]
    for start in range(0,len(queries),256):
        if popularity is None:sim=features[queries[start:start+256]]@features[train].T
        else:sim=np.broadcast_to(popularity[train],(len(queries[start:start+256]),len(train)))
        # Stable ties are especially important for identical artist text vectors.
        order=np.argsort(-sim,axis=1,kind='stable')[:,:20]
        recommendations.extend(train[order])
    recommendations=np.array(recommendations)
    k=min(20,len(train))
    discounts=1/np.log2(np.arange(2,k+2))
    playlist_metrics=[]
    for p in cases:
        scores=[];positives=np.array(p['positives'])
        assert set(positives)<=train_set
        for q in p['queries']:
            hits=np.isin(recommendations[qlookup[q]],positives)
            scores.append([hits.mean(),hits.sum()/len(positives),np.sum(hits*discounts)/discounts[:min(k,len(positives))].sum()])
        playlist_metrics.append(np.mean(scores,axis=0))
    per=np.array(playlist_metrics)
    return {'precision_at20':float(per[:,0].mean()),'recall_at20':float(per[:,1].mean()),
            'ndcg_at20':float(per[:,2].mean()),'playlists':len(cases),'unique_queries':len(queries)},per


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--data',type=Path,required=True);ap.add_argument('--experiment',type=Path,required=True)
    ap.add_argument('--evaluate-test',action='store_true')
    a=ap.parse_args();out=a.experiment
    torch.set_num_threads(4);torch.use_deterministic_algorithms(True)
    threadpool_limits(limits=4)
    protocol=json.loads((out/'protocol.json').read_text()); cfg=protocol['model_grid']
    z=np.load(a.data/'vectors.npz');assert z['valid'].all()
    audio,meta=unit(z['audio']),unit(z['metadata']); x=np.concatenate((audio,meta),axis=1)
    s=np.load(out/'private-splits.npz');train=s['train'];val=s['validation'];test=s['test'];groups=s['groups']
    cases=json.loads((out/'private-evaluation.json').read_text()); pls=json.loads((out/'private-training-playlists.json').read_text())
    current_raw=np.concatenate((np.sqrt(.25)*audio,np.sqrt(.75)*meta),axis=1)
    mean=current_raw[train].mean(0);current=unit(current_raw-mean)
    np.savez(out/'private-preprocessing.npz',fusion_mean=mean)
    baselines={'current_1344':current,'audio_only':unit(audio-audio[train].mean(0)),
               'metadata_only':unit(meta-meta[train].mean(0))}
    for dim in [64,128]:
        cache=out/f'private-pca{dim}.npz'
        if not cache.exists():
            pca=PCA(n_components=dim,svd_solver='full').fit(current[train])
            np.savez(cache,components=pca.components_,mean=pca.mean_,explained=pca.explained_variance_ratio_)
        p=np.load(cache);baselines[f'pca{dim}']=unit((current-p['mean'])@p['components'].T)
    popularity=np.zeros(len(x),dtype=np.float32)
    for p in pls:popularity[p]+=1
    checkpoint_dir=out/'checkpoints';checkpoint_dir.mkdir(exist_ok=True)
    summary_path=out/'training-summary.json'
    summary=json.loads(summary_path.read_text()) if summary_path.exists() else {
        'protocol_sha256':sha(out/'protocol.json'),'vectors_sha256':sha(a.data/'vectors.npz'),
        'trainer_source_sha256':sha(Path(__file__)),
        'runtime':{'torch':torch.__version__,'numpy':np.__version__,'threads':4,'device':'CPU'},
        'objective':'Symmetric multi-positive InfoNCE (temperature .12); positives share a training playlist and differ in primary-artist/audio group. Same-group candidates excluded from denominator.',
        'validation_baselines':{},'models':[],
        'limits':protocol['limits']}
    assert summary['protocol_sha256']==sha(out/'protocol.json') and summary['vectors_sha256']==sha(a.data/'vectors.npz')
    assert summary['trainer_source_sha256']==sha(Path(__file__))
    if not a.evaluate_test:
        if not summary['validation_baselines']:
            for name,feat in baselines.items():summary['validation_baselines'][name]=evaluate(feat,train,cases['validation'])[0]
            summary['validation_baselines']['popularity']=evaluate(None,train,cases['validation'],popularity)[0]
            write_json(summary_path,summary)
            print(json.dumps({'validation_baselines':summary['validation_baselines']}),flush=True)
        # Build co-occurrence only from training labels; no validation/test playlist is read here.
        local=np.full(len(x),-1);local[train]=np.arange(len(train))
        cooccur=np.zeros((len(train),len(train)),dtype=bool)
        train_playlists=[np.array(p,dtype=np.int64) for p in pls]
        for p in train_playlists:
            lp=local[p];assert (lp>=0).all();cooccur[np.ix_(lp,lp)]=True
        print(json.dumps({'training_graph_edges':int(cooccur.sum()),'train_tracks':len(train),'train_playlists':len(pls)}),flush=True)
        for family in cfg['families']:
            for dim in cfg['output_dimensions']:
                for seed in cfg['model_seeds']:
                    name=f'{family}-{dim}-seed{seed}'
                    if any(m['name']==name for m in summary['models']):continue
                    torch.manual_seed(seed);rng=np.random.default_rng(seed)
                    model=Fusion(dim,family)
                    optimizer=torch.optim.AdamW(model.parameters(),lr=cfg['lr'],weight_decay=cfg['weight_decay'])
                    xt=torch.from_numpy(x);best=None;best_score=-1.;best_epoch=0;stale=0;history=[];started=time.perf_counter()
                    for epoch in range(1,cfg['epochs']+1):
                        model.train();losses=[]
                        for _ in range(cfg['steps_per_epoch']):
                            left=[];right=[]
                            for pi in rng.integers(0,len(train_playlists),size=cfg['batch_pairs']):
                                p=train_playlists[pi];anchor=int(rng.choice(p));other=p[groups[p]!=groups[anchor]]
                                left.append(anchor);right.append(int(rng.choice(other)))
                            left=np.array(left);right=np.array(right)
                            allowed=groups[left,None]!=groups[right][None,:]
                            positive=cooccur[local[left,None],local[right][None,:]] & allowed
                            emb=model(xt[np.concatenate((left,right))]);za,zb=emb.chunk(2)
                            loss=multi_positive_loss(za,zb,torch.from_numpy(positive),torch.from_numpy(allowed),cfg['temperature'])
                            if not torch.isfinite(loss):raise RuntimeError('Non-finite loss')
                            optimizer.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),5);optimizer.step();losses.append(float(loss.detach()))
                        if epoch==1 or epoch%cfg['validation_every_epochs']==0:
                            feat=encode(model,x);metrics,_=evaluate(feat,train,cases['validation'])
                            entry={'epoch':epoch,'loss':float(np.mean(losses)),'validation':metrics};history.append(entry)
                            if metrics['ndcg_at20']>best_score:
                                best_score=metrics['ndcg_at20'];best_epoch=epoch;best=copy.deepcopy(model.state_dict());stale=0
                            else:stale+=1
                            print(json.dumps({'model':name,**entry,'seconds':round(time.perf_counter()-started,1)}),flush=True)
                            if stale>=cfg['early_stop_validation_checks']:break
                    model.load_state_dict(best);model.eval();features=encode(model,x)
                    checkpoint=checkpoint_dir/f'{name}.pt'
                    torch.save({'state_dict':best,'dim':dim,'family':family,'seed':seed,'epoch':best_epoch,'protocol_sha256':summary['protocol_sha256']},checkpoint)
                    np.save(out/f'private-{name}.npy',features)
                    record={'name':name,'family':family,'dimensions':dim,'seed':seed,'selected_epoch':best_epoch,'last_epoch':epoch,
                            'parameters':sum(p.numel() for p in model.parameters()),'seconds':time.perf_counter()-started,
                            'checkpoint_sha256':sha(checkpoint),'validation':evaluate(features,train,cases['validation'])[0],'history':history}
                    summary['models'].append(record);write_json(summary_path,summary)
        assert len(summary['models'])==len(cfg['families'])*len(cfg['output_dimensions'])*len(cfg['model_seeds'])
        selected={f'{family}-{dim}':max((m for m in summary['models'] if m['family']==family and m['dimensions']==dim),key=lambda m:m['validation']['ndcg_at20'])['name'] for family in cfg['families'] for dim in cfg['output_dimensions']}
        write_json(out/'frozen-selection.json',{'criterion':'validation macro nDCG@20','models':selected,'training_summary_sha256':sha(summary_path),'protocol_sha256':sha(out/'protocol.json')})
        print('TRAINING COMPLETE; selection frozen, test not evaluated.',flush=True)
    else:
        selection=json.loads((out/'frozen-selection.json').read_text())
        assert selection['training_summary_sha256']==sha(summary_path)
        metrics={};pers={}
        for name,feat in baselines.items():metrics[name],pers[name]=evaluate(feat,train,cases['test'])
        metrics['popularity'],pers['popularity']=evaluate(None,train,cases['test'],popularity)
        for m in summary['models']:
            feat=np.load(out/f"private-{m['name']}.npy")
            metrics[m['name']],pers[m['name']]=evaluate(feat,train,cases['test'])
        np.savez(out/'private-test-per-playlist.npz',**pers)
        rng=np.random.default_rng(11013);bootstrap=rng.integers(0,len(cases['test']),(2000,len(cases['test'])))
        comparisons={}
        for label,name in selection['models'].items():
            dim=int(label.rsplit('-',1)[1])
            comparisons[label]={}
            for baseline in ['current_1344',f'pca{dim}']:
                delta=pers[name][:,2]-pers[baseline][:,2]
                interval=np.quantile(delta[bootstrap].mean(1),[.025,.975])
                comparisons[label][baseline]={'delta_ndcg20':float(delta.mean()),'playlist_bootstrap_95_percent_interval':interval.tolist()}
        result={'metrics':metrics,'validation_selected_models':selection['models'],'comparisons':comparisons,
                'interval_note':'Descriptive paired playlist bootstrap; playlists share tracks/artists, so not independent population confidence. Fixed one split and small seed grid.',
                'frozen_selection_sha256':sha(out/'frozen-selection.json'),'test_evaluated_after_all_training':True}
        write_json(out/'test-summary.json',result);print(json.dumps(result,indent=2),flush=True)

if __name__=='__main__':main()
