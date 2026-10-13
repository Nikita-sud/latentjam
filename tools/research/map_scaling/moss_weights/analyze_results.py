#!/usr/bin/env python3
"""Local-only unblinding, judge qualification and descriptive paired comparisons."""
import argparse,json,hashlib
from pathlib import Path
from collections import Counter
import numpy as np

def read(p):return json.loads(p.read_text())
def write(p,x):p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def main():
    ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);ap.add_argument('--qa-only',action='store_true');ap.add_argument('--judgements',default='judgements.jsonl');ap.add_argument('--output-prefix',default='');a=ap.parse_args();p=a.root
    plan=read(p/'private-plan.json');results=[json.loads(l) for l in (p/'results'/a.judgements).read_text().splitlines()];byid={r['task_id']:r for r in results};assert len(byid)==len(results)
    checks=[]
    for t in plan:
        if not t['role'].startswith('qa_'):continue
        r=byid.get(t['task_id']);answer=r.get('parsed') if r else None;passed=False
        if answer and not r['hit_token_limit']:
            if t['role']=='qa_exact':passed=answer['scores'][t['exact_index']]>=3 and answer['scores'][t['exact_index']]>answer['scores'][t['silent_index']]
            if t['role']=='qa_equal':passed=max(answer['scores'])-min(answer['scores'])<=1
            if t['role']=='qa_identical':passed=answer['assessable'] and answer['coherence']>=3
            if t['role']=='qa_silence':passed=not answer['assessable']
        checks.append({'task_id':t['task_id'],'check':t['role'],'pass':bool(passed),'response':answer,'complete':r is not None})
    qa={'controls':len(checks),'completed':sum(x['complete'] for x in checks),'passed':sum(x['pass'] for x in checks),'gate_passed':len(checks)==12 and all(x['pass'] for x in checks),'checks':checks}
    write(p/'results'/(a.output_prefix+'qa-summary.json'),qa)
    if a.qa_only:print(json.dumps(qa,indent=2));return
    valid={k:r['parsed'] for k,r in byid.items() if r.get('parsed') and not r['hit_token_limit']}
    variants=['a25','a50','a75'];unit_scores={};identical=[];slot_scores=[]
    for t in plan:
        if t['role']!='main_neighbor' or t['task_id'] not in valid:continue
        scores=valid[t['task_id']]['scores'];slot_scores.append(scores);mapped={v:s for v,s in zip(t['variant_order'],scores)}
        unit_scores.setdefault(t['anchor'],[]).append([mapped[v] for v in variants])
        for j in range(3):
            for k in range(j+1,3):
                if t['refs'][j+1]==t['refs'][k+1]:identical.append(abs(scores[j]-scores[k]))
    matrix=np.array([np.mean(v,axis=0) for v in unit_scores.values() if len(v)==2]);rng=np.random.default_rng(1321)
    neighbors={'paired_anchors_complete':len(matrix),'planned_anchors':len({t['anchor'] for t in plan if t['role']=='main_neighbor'}),'means':dict(zip(variants,matrix.mean(0).tolist())) if len(matrix) else {},'same_candidate_score_differences':{'pairs':len(identical),'max':max(identical,default=None),'above_one':sum(d>1 for d in identical)}}
    neighbors['presentation_slot_means_ABC']=np.mean(slot_scores,axis=0).tolist() if slot_scores else []
    neighbors['distinct_candidate_counts_per_anchor']=dict(Counter(len({tuple(r) for r in t['refs'][1:]}) for t in plan if t['role']=='main_neighbor' and t['position']==0))
    if len(matrix):
        boot=rng.integers(0,len(matrix),(4000,len(matrix)));neighbors['comparisons']={}
        for j in [1,2]:
            d=matrix[:,j]-matrix[:,0]
            neighbors['comparisons'][variants[j]+'_minus_a25']={'mean_difference':float(d.mean()),'descriptive_anchor_bootstrap95':np.quantile(d[boot].mean(1),[.025,.975]).tolist(),'anchors_win_tie_loss':[int((d>0).sum()),int((d==0).sum()),int((d<0).sum())]}
    region={};main_groups={}
    for t in plan:
        if t['role'] not in ['main_region','random_region'] or t['task_id'] not in valid:continue
        r=valid[t['task_id']]
        if not r['assessable']:continue
        content=t.get('content','MUSIC');index=t.get('region',t.get('control_index'));key=(t['role'],t['variant'],content,index)
        main_groups.setdefault(key,[]).append(r['coherence'])
    for v in variants:
        region[v]={}
        for role in ['main_region','random_region']:
            values=[float(np.mean(vals)) for (r,w,c,i),vals in main_groups.items() if r==role and w==v and c=='MUSIC' and len(vals)==2]
            region[v][role]={'complete_music_groups':len(values),'mean':float(np.mean(values)) if values else None,'scores':values}
    repeats=[];pby={t['task_id']:t for t in plan}
    for t in plan:
        if 'repeat_of' not in t or t['task_id'] not in valid or t['repeat_of'] not in valid:continue
        first,second=valid[t['repeat_of']],valid[t['task_id']]
        if t['kind']=='neighbor':
            one=dict(zip(pby[t['repeat_of']]['variant_order'],first['scores']));two=dict(zip(t['variant_order'],second['scores']));delta=[abs(one[v]-two[v]) for v in variants]
        elif first['assessable'] and second['assessable']:delta=[abs(first['coherence']-second['coherence'])]
        else:delta=[]
        repeats.append({'kind':t['kind'],'task_id':t['task_id'],'absolute_score_changes':delta})
    result={'qa':qa,'completed_tasks':len(results),'planned_tasks':len(plan),'valid_normalized':len(valid),'strict_schema':sum(r['strict_schema'] for r in results),'truncated':sum(r['hit_token_limit'] for r in results),'neighbors':neighbors,'regions':region,'order_repeats':repeats,'winner_claim_permitted_by_qa':qa['gate_passed'],'limits':read(p/'protocol.json')['limits']+['0..4 scores are ordinal model opinions, not a percentage of musical correctness.','Region samples differ between variants; region means are unpaired and selected-member coverage must accompany them.']}
    write(p/'results'/(a.output_prefix+'analysis-summary.json'),result);print(json.dumps(result,indent=2))
if __name__=='__main__':main()
