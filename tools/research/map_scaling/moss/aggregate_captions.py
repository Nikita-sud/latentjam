#!/usr/bin/env python3
"""Local frozen-caption audit. No model calls, downloads, uploads or production edits."""
import argparse,hashlib,itertools,json,math,re
from pathlib import Path
from collections import Counter,defaultdict
import numpy as np
from caption_features import draft,validate,distance,FIELDS
P=Path(__file__).resolve().parent
KEYS={'broad_style','instruments','vocals','rhythm_and_tempo','energy','production_texture','changes_within_excerpt','uncertain_details'}
STOP=set('a an the and or but of to in on at with without for from by as is are was were be been being it its this that these those there here has have had very more most some any only also throughout overall feature features featuring sound sounds music musical audio clip excerpt recording track song hear heard audible appears seems'.split())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def stats(values):
 a=np.array([v for v in values if v is not None and math.isfinite(v)],dtype=float)
 return {'n':len(a),'mean':float(a.mean()) if len(a) else None,'median':float(np.median(a)) if len(a) else None,'q25':float(np.percentile(a,25)) if len(a) else None,'q75':float(np.percentile(a,75)) if len(a) else None,'min':float(a.min()) if len(a) else None,'max':float(a.max()) if len(a) else None}
def tokens(s):
 # Exclude exact BPM/numbers and unsupported stereo/mono/spatial claims from lexical sensitivity.
 s=re.sub(r'\b\d+(?:\.\d+)?\s*(?:bpm|beats?\s+per\s+minute)?\b',' ',s.lower());s=re.sub(r'\b(?:stereo|mono|monophonic|stereophonic|panning|panned|panoramic)\b',' ',s)
 t=[x for x in re.findall(r'[a-z]+',s) if len(x)>1 and x not in STOP];return t+[a+' '+b for a,b in zip(t,t[1:])]
def lexical(captions,real_ids,all_ids):
 # Secondary only: each of5 descriptive text fields contributes equally; style/genre omitted.
 fields=['instruments','vocals','rhythm_and_tempo','energy','production_texture'];all_mats=[]
 for f in fields:
  counts={cid:Counter(tokens(captions[cid].get(f,''))) for cid in all_ids};df=Counter(t for cid in real_ids for t in counts[cid]);vocab=sorted(t for t,n in df.items() if n>=2 and n/len(real_ids)<.95);idx={t:i for i,t in enumerate(vocab)};mat=np.zeros((len(all_ids),len(vocab)))
  for j,cid in enumerate(all_ids):
   for t,n in counts[cid].items():
    if t in idx:mat[j,idx[t]]=(1+math.log(n))*(math.log((1+len(real_ids))/(1+df[t]))+1)
  norms=np.linalg.norm(mat,axis=1);valid=norms>0;mat/=np.maximum(norms[:,None],1e-12)
  assert np.isfinite(mat).all() and np.max(np.linalg.norm(mat,axis=1),initial=0)<=1+1e-12
  gram=np.einsum('ik,jk->ij',mat,mat,optimize=False)
  assert np.isfinite(gram).all() and gram.min(initial=0)>=-1e-12 and gram.max(initial=0)<=1+1e-12
  d=np.clip(1-gram,0,2);d[~valid,:]=np.nan;d[:,~valid]=np.nan;all_mats.append(d)
 stack=np.array(all_mats);number=np.isfinite(stack).sum(0);total=np.nansum(stack,axis=0);out=np.divide(total,number,out=np.full_like(total,np.nan),where=number>=3);return out

def analyze(method,clipdistance,ids,plan,output):
 ix={c:i for i,c in enumerate(ids)};tracks={t['track']:t for t in plan['tracks']};pairvalue={};pairdetail=[]
 for p in plan['all_pairs']:
  ac=[c for c in tracks[p['a']]['clip_ids'] if c in ix];bc=[c for c in tracks[p['b']]['clip_ids'] if c in ix];v=[clipdistance[ix[a],ix[b]] for a in ac for b in bc];good=[float(x) for x in v if np.isfinite(x)];expected=len(tracks[p['a']]['clip_ids'])*len(tracks[p['b']]['clip_ids'])
  # Require every expected cross-clip comparison; never cherry-pick the easiest excerpt.
  value=float(np.mean(good)) if len(good)==expected else None;pairvalue[p['pair_id']]=value;pairdetail.append({**p,'distance':value,'valid_clip_pairs':len(good),'expected_clip_pairs':expected,'clip_pair_range':[min(good),max(good)] if good else None})
 pd={p['pair_id']:p for p in pairdetail};withintrack=[]
 for t in tracks.values():
  if len(t['clip_ids'])!=2 or not all(c in ix for c in t['clip_ids']):continue
  a,b=t['clip_ids'];v=clipdistance[ix[a],ix[b]];withintrack.append({'track':t['track'],'distance':float(v) if np.isfinite(v) else None})
 comparisons=[]
 for c in plan['matched_music_controls']:
  w=pairvalue[c['within_pair']];a=tracks[c['anchor']];rec={'region_index':a['baseline_region'],'anchor':c['anchor'],'within_pair':c['within_pair'],'within_distance':w}
  for kind in ['hard','random']:
   v=pairvalue[c[kind+'_control_pair']];rec[kind+'_control_pair']=c[kind+'_control_pair'];rec[kind+'_delta']=v-w if v is not None and w is not None else None
  comparisons.append(rec)
 global_controls={}
 for kind in ['hard','random']:
  bygroup=defaultdict(list)
  for c in comparisons:
   if c[kind+'_delta'] is not None:bygroup[c['region_index']].append(c[kind+'_delta'])
  per=[float(np.mean(v)) for v in bygroup.values()];global_controls[kind]={'matched_delta_control_minus_within':stats([c[kind+'_delta'] for c in comparisons]),'equal_region_mean_delta':float(np.mean(per)) if per else None,'evaluable_regions':len(bygroup),'regions_positive_mean_delta':sum(v>0 for v in per),'matched_positive_fraction':float(np.mean([c[kind+'_delta']>0 for c in comparisons if c[kind+'_delta'] is not None])) if any(c[kind+'_delta'] is not None for c in comparisons) else None}
 regions=[]
 for wi in sorted(set(t['baseline_region'] for t in tracks.values())):
  ts=[t for t in tracks.values() if t['baseline_region']==wi];tset={t['track'] for t in ts};ps=[p for p in pairdetail if p['a'] in tset and p['b'] in tset];cr=[c for c in comparisons if c['region_index']==wi]
  central={t['track'] for t in ts if t['sampling_role']=='central'};boundary={t['track'] for t in ts if t['sampling_role']=='boundary'}
  regions.append({'baseline_region_index':wi,'content':ts[0]['baseline_content'],'sampled_tracks':len(ts),'within_pairs':stats([p['distance'] for p in ps]),'central_pair':stats([p['distance'] for p in ps if p['a'] in central and p['b'] in central]),'boundary_containing_pairs':stats([p['distance'] for p in ps if p['a'] in boundary or p['b'] in boundary]),'hard_matched_delta':stats([c['hard_delta'] for c in cr]),'random_matched_delta':stats([c['random_delta'] for c in cr])})
 strata={}
 for subset in ['all','music_only']:
  data=[p for p in pairdetail if subset=='all' or p['music_only']];strata[subset]={k:stats([p['distance'] for p in data if p['comparison_stratum']==k]) for k in ['same_both','split_in_50','merged_in_50','different_both','unassigned_endpoint_in_50']}
 # Same68assignment cohort for both variants: no replacing hard unassigned tracks with easier ones.
 common=[p for p in pairdetail if p['same_region50'] is not None and p['music_only']];common_scores={}
 for key in ['same_region25','same_region50']:
  common_scores[key]={'within_distance':stats([p['distance'] for p in common if p[key]]),'between_distance':stats([p['distance'] for p in common if not p[key]])}
 report={'method':method,'distance_meaning':'Lower means more similar model descriptions in this chosen representation,not a direct audio distance or ground truth.','pair_coverage':{'total':len(pairdetail),'complete_evaluable':sum(p['distance'] is not None for p in pairdetail)},'within_track_excerpt_distance':stats([r['distance'] for r in withintrack]),'matched_controls':global_controls,'regions':regions,'weight_transition_pair_distances':strata,'common_assigned_music_cohort':common_scores,'dependent_observations':'Clips share tracks and pairs share endpoints; no independent-pair significance test or population confidence interval is provided.'}
 (output/('private-pairs-'+method+'.json')).write_text(json.dumps({'pairs':pairdetail,'within_tracks':withintrack,'matched':comparisons},indent=2)+'\n');return report

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--captions',type=Path,required=True);ap.add_argument('--features',type=Path);ap.add_argument('--output',type=Path,default=P/'analysis');ap.add_argument('--allow-partial',action='store_true');ap.add_argument('--prepare-only',action='store_true');a=ap.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 manifest=[json.loads(x) for x in (P/'upload/manifest.jsonl').read_text().splitlines()];expected={x['clip_id'] for x in manifest};mapping=json.loads((P/'private-mapping.json').read_text());controls=json.loads((P/'private-control-mapping.json').read_text());hashes={x['clip_id']:x['wav_sha256'] for x in mapping+controls};raw={};parsed={}
 for line in a.captions.read_text().splitlines():
  r=json.loads(line);cid=r['clip_id'];assert cid in expected and cid not in raw;assert r['audio_sha256']==hashes[cid];raw[cid]=r
  try:c=json.loads(r['caption'].strip())
  except json.JSONDecodeError:continue
  if isinstance(c,dict) and set(c)==KEYS and all(isinstance(v,str) for v in c.values()):parsed[cid]=c
 if not a.allow_partial and set(raw)!=expected:raise ValueError('Frozen complete149caption set required; use --allow-partial only for preparation/QA')
 real={x['clip_id'] for x in mapping};real_ready=sorted(real&set(parsed));all_ready=sorted(parsed);ci={c:i for i,c in enumerate(all_ready)}
 work=[{'clip_id':cid,'caption_sha256':hashlib.sha256(raw[cid]['caption'].encode()).hexdigest(),'parsed_caption':parsed[cid],**draft(parsed[cid])} for cid in sorted(parsed) if cid in real]
 (a.output/'blind-feature-review-worklist.json').write_text(json.dumps(work,ensure_ascii=False,indent=2)+'\n')
 if a.prepare_only:
  print(json.dumps({'stage':'blind_feature_preparation_only','caption_rows':len(raw),'real_worklist_rows':len(work),'no_group_metrics_or_dossier_produced':True}));return
 plan=json.loads((P/'private-pair-plan.json').read_text())
 quality={'expected_clips':len(expected),'completed_clips':len(raw),'valid_schema_clips':len(parsed),'real_valid_schema_clips':len(real_ready),'truncated_at_token_limit':sum(bool(r.get('hit_token_limit')) for r in raw.values()),'exact_bpm_mentions_excluded':sum(bool(re.search(r'\b\d+(?:\.\d+)?\s*(?:bpm|beats? per minute)\b',r['caption'],re.I)) for r in raw.values()),'stereo_mentions_excluded':sum(bool(re.search(r'\bstereo\b',r['caption'],re.I)) for r in raw.values()),'controls':[]}
 for c in controls:
  cid=c['clip_id'];q={'role':c['control_role'],'available':cid in parsed}
  if cid not in parsed:quality['controls'].append(q);continue
  if c['control_role']=='hidden_duplicate':
   src=c['duplicate_of_clip_id'];q['original_available']=src in parsed;q['verbatim_caption_equal']=raw[cid]['caption']==raw[src]['caption'] if src in raw else None
  else:
   dr=draft(parsed[cid]);q['described_instrument_families']=dr['features']['instruments'];q['described_voice']=dr['features']['voice'];q['described_tempo']=dr['features']['tempo'];q['manual_silence_review_required']=True
  quality['controls'].append(q)
 reports=[];primary_status='Not available: blind-reviewed7-field features were not supplied. Do not label heuristic draft or lexical similarity primary evidence.'
 if a.features:
  feature_rows=json.loads(a.features.read_text());features={}
  for r in feature_rows:
   cid=r['clip_id'];assert cid in parsed and cid not in features;assert r['caption_sha256']==hashlib.sha256(raw[cid]['caption'].encode()).hexdigest();features[cid]=validate(r)
  assert set(features)==set(real_ready), 'Review must cover every schema-valid real caption; do not omit difficult clips'
  ids=sorted(features);d=np.full((len(ids),len(ids)),np.nan)
  for i,x in enumerate(ids):
   for j in range(i,len(ids)):
    value,_=distance(features[x],features[ids[j]]);d[i,j]=d[j,i]=value if value is not None else np.nan
  reports.append(analyze('reviewed_acoustic_rubric',d,ids,plan,a.output));primary_status='Computed from explicit blinded text review by an agent of model-generated captions,not human listening or audio ground truth.'
 if real_ready:
  d=lexical(parsed,real_ready,all_ready);reports.append(analyze('caption_lexical_sensitivity',d,all_ready,plan,a.output))
  for q,c in zip(quality['controls'],controls):
   if c['control_role']=='hidden_duplicate' and c['clip_id'] in ci and c['duplicate_of_clip_id'] in ci:
    v=d[ci[c['clip_id']],ci[c['duplicate_of_clip_id']]];q['lexical_distance']=float(v) if np.isfinite(v) else None
 # Grouped qualitative evidence dossier is private; never input to the blind extractor.
 dossier=[]
 for wi in sorted(set(t['baseline_region'] for t in plan['tracks'])):
  rows=[]
  for t in plan['tracks']:
   if t['baseline_region']!=wi:continue
   rows.append({'track':t['track'],'role':t['sampling_role'],'assigned50':t['fifty_region'] is not None,'clips':[{'clip_id':cid,'caption':parsed.get(cid),'raw_caption':raw.get(cid,{}).get('caption')} for cid in t['clip_ids']]})
  dossier.append({'baseline_region_index':wi,'content':next(t['baseline_content'] for t in plan['tracks'] if t['baseline_region']==wi),'tracks':rows,'review_questions':['Do both central tracks share concrete instrumentation/voice/rhythm beyond a vague style label?','Are boundary disagreements expected variation or a contrasting recording type?','Are two excerpts from a track consistent or does the arrangement change?','Are differences supported by explicit words rather than exactBPM/stereo/guessedsource?','Would split/merged pairs favor50/50 on the same tracks,and at what assignment cost?']})
 (a.output/'private-qualitative-region-dossier.json').write_text(json.dumps(dossier,ensure_ascii=False,indent=2)+'\n')
 output={'status':'partial' if set(raw)!=expected else 'complete_captions','captions_sha256':sha(a.captions),'aggregator_sha256':sha(Path(__file__)),'feature_parser_sha256':sha(P/'caption_features.py'),'pair_plan_sha256':sha(P/'private-pair-plan.json'),'features_sha256':sha(a.features) if a.features else None,'primary_status':primary_status,'quality':quality,'metrics':reports,'limits':['75selectedtracks from15available regions;14MUSIC+1NOVELTY,not an unbiased library evaluation.','50/50assigns68of75tracks;7unassigned remain a coverage cost,not discarded successes.','MOSS captions and derived semantic fields can hallucinate; same-model wording consistency does not prove sonic cohesion.','No exactBPM,stereo/mono,width or inferred recording source enters theprimary rubric.','Automatic draft features require region-blind evidence review; unknown features are not filled from metadata.','Lexical sensitivity uses equal5caption-field TFIDF cosines excluding broad_style,changes,uncertainty; minimum3fields; this is wording similarity only.','Primary7fieldrubric needs4jointly observed fields per clip pair; all expected excerpt cross-pairs must be evaluable for a track-pair score.','NOVELTYiscontentcategory,not expected homogeneous sound; primarymatchedcontrols useMUSIConly.']}
 (a.output/'caption-analysis-aggregates.json').write_text(json.dumps(output,indent=2)+'\n');print(json.dumps({'status':output['status'],'completed':len(raw),'schema_valid':len(parsed),'primary_status':primary_status,'reports':[r['method'] for r in reports]},indent=2))
if __name__=='__main__':main()
