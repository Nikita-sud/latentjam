#!/usr/bin/env python3
"""Local transparent summary and evidence-review packet for frozen MOSS group judgements."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import statistics
from judge_groups_v2 import valid_result


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def canonical_sha(row):
    return sha_bytes(json.dumps(row, sort_keys=True, ensure_ascii=False).encode())


def write_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def sentence_count(value):
    return len([part for part in re.split(r'[.!?]+(?:\s+|$)',value.strip()) if part.strip()])


def normalize_response(raw):
    record={'json_valid':False,'strict_schema_valid':False,'normalized_eligible':False,
            'raw_score':None,'normalized_score':None,'repairs':[],'schema_warnings':[]}
    def unambiguous_object(pairs):
        result={}
        for key,value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key: '+key)
            result[key]=value
        return result
    def reject_constant(value):
        raise ValueError('Non-standard JSON constant: '+value)
    try: parsed=json.loads(raw,object_pairs_hook=unambiguous_object,parse_constant=reject_constant)
    except (json.JSONDecodeError,ValueError) as error:
        record['schema_warnings'].append('invalid_json_no_guessing')
        record['parse_error']={'line':getattr(error,'lineno',None),'column':getattr(error,'colno',None),'message':str(error)}
        return record,None
    record['json_valid']=True
    record['strict_schema_valid']=valid_result(parsed)
    if not isinstance(parsed,dict):
        record['schema_warnings'].append('response_not_object');return record,parsed
    record['raw_score']=parsed.get('coherence')
    normalized=dict(parsed)
    score=parsed.get('coherence')
    if isinstance(score,str) and re.fullmatch('[0-4]',score):
        score=int(score);normalized['coherence']=score
        record['repairs'].append({'field':'coherence','original':parsed['coherence'],'normalized':score,
                                  'rule':'exact_single_digit_string_0_to_4_to_integer'})
    if type(score) is int and 0<=score<=4:
        record['normalized_score']=score
    elif score is not None:
        record['schema_warnings'].append('invalid_coherence_type_or_value')
    expected={'coherence','evidence_sufficiency','shared_traits','contrasts','uncertainty'}
    if set(normalized)!=expected:
        record['schema_warnings'].append('unexpected_or_missing_keys');return record,parsed
    string_fields=('evidence_sufficiency','shared_traits','contrasts','uncertainty')
    if any(not isinstance(normalized[k],str) for k in string_fields):
        record['schema_warnings'].append('non_string_descriptive_field');return record,parsed
    for key in ('shared_traits','contrasts'):
        if sentence_count(normalized[key])>3:
            record['schema_warnings'].append(key+'_exceeds_three_sentences')
        if any(ref not in {'T1','T2','T3','T4','T5'} for ref in re.findall(r'\bT[0-9]+\b',normalized[key])):
            record['schema_warnings'].append(key+'_invalid_track_reference')
    # Length is a format warning, not an excuse to discard a clear explicit score.
    shorter=dict(normalized,shared_traits='',contrasts='')
    record['normalized_eligible']=(valid_result(shorter)
        and not any('invalid_track_reference' in warning for warning in record['schema_warnings']))
    return record,parsed


def candidate_flags(parsed, group):
    """Review cues, never automatic determinations of factual error."""
    if not isinstance(parsed,dict):return []
    by_track={track['track']:track for track in group['tracks']}
    flags=[]
    for field in ('shared_traits','contrasts'):
        text=parsed.get(field)
        if not isinstance(text,str):continue
        for sentence in re.split(r'(?<=[.!?])\s+',text):
            refs=sorted(set(re.findall(r'\bT[1-5]\b',sentence)))
            if re.search(r'\binstrumental\b|\bno vocals\b|\black(?:ing)? vocals\b|\bwithout vocals\b|\babsence of vocals\b',sentence,re.I):
                for tid in refs:
                    values=[excerpt['vocals'] for excerpt in by_track[tid]['excerpts']]
                    known=[value for value in values if value is not None]
                    if not known:
                        reason='all_vocal_evidence_null_not_absence'
                    elif any(not re.fullmatch(r'(none|no vocals|instrumental|no singing)',value.strip(),re.I) for value in known):
                        reason='at_least_one_excerpt_describes_vocals_check_attribution'
                    else:continue
                    flags.append({'field':field,'claim_sentence':sentence,'track':tid,
                                  'candidate_reason':reason,'source_vocal_values':values})
            if re.search(r'\bbpm\b|\btempo\b|\bmeter\b|\bstereo\b|\breverb\w*\b|\broom\b|\bkey\b|\bchord\w*\b',sentence,re.I):
                flags.append({'field':field,'claim_sentence':sentence,
                              'candidate_reason':'mentions_dimensions_not_in_primary_input_check_claim_vs_qualification'})
    return flags


def describe(rows,condition):
    chosen=[row for row in rows if row['kind']==condition]
    values=[row['normalized_score'] for row in chosen]
    return {'n':len(values),'mean':statistics.mean(values) if values else None,
            'median':statistics.median(values) if values else None,
            'distribution':{str(score):values.count(score) for score in range(5)},
            'group_ids':[row['group_id'] for row in chosen]}


def comparison(all_rows, predicate):
    music=[row for row in all_rows if row['category']=='MUSIC']
    included=[row for row in music if predicate(row) and row['normalized_score'] is not None]
    actual,control=describe(included,'actual'),describe(included,'control')
    pairs=[]
    for index in sorted({row['region_index'] for row in music}):
        a=next((row for row in included if row['region_index']==index and row['kind']=='actual'),None)
        b=next((row for row in included if row['region_index']==index and row['kind']=='control'),None)
        if a and b:pairs.append({'region_index':index,'actual_group_id':a['group_id'],
                                'control_group_id':b['group_id'],
                                'difference':a['normalized_score']-b['normalized_score']})
    differences=[pair['difference'] for pair in pairs]
    return {'expected_per_condition':14,'observed_groups':len(music),'actual':actual,'control':control,
            'difference_of_included_means':actual['mean']-control['mean'] if actual['n'] and control['n'] else None,
            'matched_complete_pairs':len(pairs),'matched_mean_difference':statistics.mean(differences) if differences else None,
            'matched_median_difference':statistics.median(differences) if differences else None,
            'pairs_actual_higher':sum(value>0 for value in differences),
            'pairs_equal':sum(value==0 for value in differences),
            'pairs_actual_lower':sum(value<0 for value in differences),
            'paired_details':pairs,
            'excluded_group_ids':[row['group_id'] for row in music if row not in included]}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for flag in ('judgements','groups','private-plan','output','review-packet'):
        parser.add_argument('--'+flag,type=Path,required=True)
    parser.add_argument('--consistency-review',type=Path)
    parser.add_argument('--allow-partial',action='store_true')
    args=parser.parse_args()
    raw_results=args.judgements.read_bytes(); raw_groups=args.groups.read_bytes(); raw_plan=args.private_plan.read_bytes()
    if raw_results and not raw_results.endswith(b'\n'):
        raise ValueError('Incomplete final JSONL line; no guessing')
    groups={row['group_id']:row for row in map(json.loads,raw_groups.decode().splitlines())}
    plan=json.loads(raw_plan)
    private_key={row['group_id']:row for row in plan['groups']}
    if len(groups)!=30 or set(groups)!=set(private_key):
        raise ValueError('Need matching frozen thirty-group manifest and plan')
    source_rows=[json.loads(line) for line in raw_results.decode().splitlines()]
    if not args.allow_partial and len(source_rows)!=30:
        raise ValueError('Need all 30 judgements; --allow-partial is for explicit development checks')
    reviews={}
    if args.consistency_review:
        review_doc=json.loads(args.consistency_review.read_text())
        if review_doc['groups_sha256']!=sha_bytes(raw_groups):
            raise ValueError('Consistency review group manifest differs')
        reviews={row['group_id']:row for row in review_doc['reviews']}
        if len(reviews)!=len(review_doc['reviews']) or not set(reviews)<=set(groups):
            raise ValueError('Duplicate or unknown consistency-review group')
        for review in reviews.values():
            if review['verdict'] not in {'supported','material_unsupported_or_contradicted','unclear'}:
                raise ValueError('Unknown consistency verdict')
            if review['verdict']=='material_unsupported_or_contradicted' and not review.get('findings'):
                raise ValueError('Material claim finding requires explicit evidence')
    output_rows=[]; packet=[]; seen=set()
    for source in source_rows:
        gid=source['group_id']
        if gid in seen or gid not in groups:
            raise ValueError('Duplicate or unknown judgement group')
        seen.add(gid)
        if source['group_sha256']!=canonical_sha(groups[gid]):
            raise ValueError('Judgement group contents differ from frozen input')
        normalized,parsed=normalize_response(source['judgement'])
        normalized['group_id']=gid
        for key in ('kind','region_index','category'):normalized[key]=private_key[gid][key]
        normalized['hit_token_limit']=source['hit_token_limit']
        normalized['strict_eligible']=normalized['strict_schema_valid'] and not source['hit_token_limit']
        normalized['normalized_eligible']=normalized['normalized_eligible'] and not source['hit_token_limit']
        response_sha=sha_bytes(source['judgement'].encode())
        normalized['response_sha256']=response_sha
        review=reviews.get(gid)
        if review and (review['response_sha256']!=response_sha or review['group_sha256']!=canonical_sha(groups[gid])):
            raise ValueError('Consistency review response/input changed')
        normalized['consistency_verdict']=review['verdict'] if review else 'unreviewed'
        normalized['consistency_findings']=review.get('findings',[]) if review else []
        flags=candidate_flags(parsed,groups[gid])
        normalized['automatic_review_candidate_count']=len(flags)
        output_rows.append(normalized)
        packet.append({'group_id':gid,'group_sha256':canonical_sha(groups[gid]),'response_sha256':response_sha,
                       'parsed_judgement':parsed,'raw_judgement':source['judgement'],
                       'input_tracks':groups[gid]['tracks'],'automatic_review_candidates':flags,
                       'review_status':normalized['consistency_verdict']})
    confirmed='material_unsupported_or_contradicted'
    summary={
        'provenance':{'judgements_sha256':sha_bytes(raw_results),'groups_sha256':sha_bytes(raw_groups),
                      'private_plan_sha256':sha_bytes(raw_plan),'script_sha256':sha_bytes(Path(__file__).read_bytes()),
                      'consistency_review_sha256':sha_bytes(args.consistency_review.read_bytes()) if args.consistency_review else None,
                      'original_responses_modified':False,'observed_groups':len(source_rows),'complete':len(source_rows)==30},
        'normalization_policy':'Only exact string [0-4] to integer; no JSON repair or score inference. Sentence-count excess is a logged format warning.',
        'strict_music':comparison(output_rows,lambda row:row['strict_eligible']),
        'score_normalized_music':comparison(output_rows,lambda row:row['normalized_eligible']),
        'strict_music_excluding_confirmed_claim_errors':comparison(output_rows,lambda row:row['strict_eligible'] and row['consistency_verdict']!=confirmed),
        'score_normalized_music_excluding_confirmed_claim_errors':comparison(output_rows,lambda row:row['normalized_eligible'] and row['consistency_verdict']!=confirmed),
        'score_normalized_music_reviewed_supported_only':comparison(output_rows,lambda row:row['normalized_eligible'] and row['consistency_verdict']=='supported'),
        'consistency_review_counts':dict(collections.Counter(row['consistency_verdict'] for row in output_rows)),
        'novelty_order_control':[row for row in output_rows if row['category']=='NOVELTY'],
        'all_groups':output_rows,
        'limitations':[
            'Coherence is an ordinal same-model opinion about three filtered caption fields, not independent audio accuracy.',
            'Same captions in both conditions imply dependent scores; one random partition is exploratory, no confidence intervals or significance claims.',
            'Format/content sensitivity analyses can change the included cohort; compare complete pairs and report denominators.',
            'Automatic evidence flags are review candidates, not factual-error findings. Unreviewed groups are not assumed correct.',
            'Text-source consistency does not establish that the original audio captions are acoustically correct.',
            'NOVELTY control contains the same five tracks in different order and is reported separately.',
        ],
    }
    write_new(args.review_packet,{'groups_sha256':sha_bytes(raw_groups),'judgements_sha256':sha_bytes(raw_results),
                                  'review_scope':'Input-source consistency only; group identities/conditions omitted for blind review',
                                  'groups':packet})
    write_new(args.output,summary)
    print(json.dumps({'status':'aggregated','groups':len(source_rows),'score_repairs':sum(bool(r['repairs']) for r in output_rows),
                      'strict_eligible':sum(r['strict_eligible'] for r in output_rows),
                      'score_normalized_eligible':sum(r['normalized_eligible'] for r in output_rows),
                      'review_counts':summary['consistency_review_counts']}))


if __name__=='__main__':main()
