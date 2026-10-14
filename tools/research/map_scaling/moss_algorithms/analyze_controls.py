"""Recompute all four qualification outcomes from identity-free observations."""
from pathlib import Path
from collections import defaultdict
import json,statistics,argparse

def analyze(observations):
 byid={x['task_id']:x for x in observations};assert len(byid)==len(observations)
 groups=defaultdict(list)
 for x in observations:groups[x['experiment']].append(x)
 result={}
 for experiment,rows in groups.items():
  roles=defaultdict(lambda:{'passed':0,'total':0});order=[]
  for x in rows:
   a=x['answer'];role=x['role'];ok=not x['truncated'] and bool(a)
   if experiment=='instruct-multi':
    ok=ok and x['schema_valid']
    if role=='qa_exact':
     s=a['scores'];exact=s[x['exact_index']];silence=s[x['silence_index']];ok=ok and exact>=3 and silence<=1 and exact>silence
    elif role=='qa_equal':s=a['scores'];ok=ok and min(s)>=3 and max(s)-min(s)<=1
    elif role=='qa_identical':ok=ok and a['assessable'] and a['coherence']>=3
    elif role=='qa_silence':ok=ok and not a['assessable'] and a['coherence'] is None
    elif role=='qa_music':ok=ok and a['music'] is True
    elif role=='qa_single_silence':ok=ok and a['music'] is False
    else:raise AssertionError(role)
   elif experiment.endswith('-pair'):
    first,second=x['expected_music'];ok=ok and a['first_music']==first and a['second_music']==second
    if not(first and second):ok=ok and a['similarity'] is None
    elif role=='identical':ok=ok and a['similarity']>=3
    if role=='reverse':order.append(abs(a['similarity']-byid[x['original']]['answer']['similarity']))
   else:
    ok=ok and x['schema_valid'] and a['audible_music']==x['expected_music']
    if role=='control_duplicate':ok=ok and a==byid[x['original']]['answer']
   roles[role]['passed']+=int(ok);roles[role]['total']+=1
  exact_roles=[k for k in roles if k not in {'different','reverse'}]
  passed=sum(roles[k]['passed'] for k in exact_roles);total=sum(roles[k]['total'] for k in exact_roles)
  syntax=sum(x['schema_valid'] for x in rows)
  qualified=passed==total and syntax==len(rows) and all(d<=1 for d in order)
  result[experiment]={'answered':len(rows),'usable_schema':syntax,'truncated':sum(x['truncated'] for x in rows),'functional_control_passes':passed,'functional_control_total':total,'by_role':dict(roles),'order_absolute_differences':order,'order_mean_absolute_difference':statistics.mean(order) if order else None,'qualified':qualified}
 assert sum(x['answered'] for x in result.values())==88
 return result

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--input',type=Path,default=Path(__file__).with_name('control-observations.json'));ap.add_argument('--output',type=Path);args=ap.parse_args()
 result=analyze(json.loads(args.input.read_text()));rendered=json.dumps(result,indent=2)
 if args.output:args.output.write_text(rendered+'\n')
 print(rendered)
if __name__=='__main__':main()
