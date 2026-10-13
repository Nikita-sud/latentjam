#!/usr/bin/env python3
"""Local-only frozen group allocation and caption preprocessing; never uploads data."""
import argparse
import collections
import datetime
import hashlib
import json
from pathlib import Path
import random
import re

SEEDS = {'control': 20261014, 'presentation': 20261015}
KEEP = ('instruments', 'vocals', 'energy')
# Conservative: discard the entire field when it contains any excluded claim.
UNVERIFIED = re.compile(
    r'\b(?:bpm|tempo|meter|metre|stereo|mono|monaural|panning|panned|spatial|'
    r'room|hall|cathedral|reverb\w*|echo\w*|acoustic\s+space|soundstage|'
    r'wide|spacious|distant|close[ -]?mic\w*|fast|slow|waltz|'
    r'triple[ -]time|duple[ -]time|double[ -]time|half[ -]time)\b|'
    r'\b\d+\s*/\s*\d+\b|\b\d+(?:\.\d+)?\s+beats?\b|'
    r'<\|[^>]*\|>', re.I)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x') as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write('\n')


def read_mapping(path):
    rows = json.loads(path.read_text())
    tracks = {}
    clip_rows = {}
    for row in rows:
        tid = str(row['track_id'])
        if row['clip_id'] in clip_rows:
            raise ValueError('Duplicate mapping clip')
        clip_rows[row['clip_id']] = row
        item = tracks.setdefault(tid, {'track_id': tid, 'region_index': row['region_index'], 'clip_ids': []})
        if item['region_index'] != row['region_index']:
            raise ValueError('Track appears in multiple regions')
        item['clip_ids'].append(row['clip_id'])
    for item in tracks.values():
        item['clip_ids'].sort()
        if not 1 <= len(item['clip_ids']) <= 2:
            raise ValueError('Expected one or two excerpts per track')
    return tracks, clip_rows


def freeze(args):
    tracks, clips = read_mapping(args.private_mapping)
    regions = collections.defaultdict(list)
    for track in tracks.values():
        regions[track['region_index']].append(track['track_id'])
    if len(regions) != 15 or len(tracks) != 75 or any(len(v) != 5 for v in regions.values()):
        raise ValueError('Expected the fixed 15-region x 5-track population')
    if args.novelty_region not in regions:
        raise ValueError('Novelty region absent')
    rng = random.Random(SEEDS['control'])
    pools = collections.defaultdict(list)
    for tid in sorted(tracks):
        track = tracks[tid]
        category = 'NOVELTY' if track['region_index'] == args.novelty_region else 'MUSIC'
        pools[(category, len(track['clip_ids']))].append(tid)
    for key in sorted(pools):
        rng.shuffle(pools[key])
    groups = []
    for region_index in sorted(regions):
        category = 'NOVELTY' if region_index == args.novelty_region else 'MUSIC'
        actual = sorted(regions[region_index])
        control = [pools[(category, len(tracks[tid]['clip_ids']))].pop() for tid in actual]
        for kind, members in [('actual', actual), ('control', control)]:
            groups.append({'kind': kind, 'region_index': region_index, 'category': category,
                           'tracks': [tracks[tid] for tid in members]})
    assert all(not value for value in pools.values())
    for kind in ('actual', 'control'):
        used = [t['track_id'] for g in groups if g['kind'] == kind for t in g['tracks']]
        assert len(used) == len(set(used)) == 75 and set(used) == set(tracks)
    order = random.Random(SEEDS['presentation'])
    order.shuffle(groups)
    for number, group in enumerate(groups, 1):
        order.shuffle(group['tracks'])
        group['group_id'] = f'g{number:04d}'
    plan = {
        'version': 1, 'created_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'private_mapping_sha256': sha(args.private_mapping), 'seeds': SEEDS,
        'novelty_region_index': args.novelty_region, 'tracks': len(tracks), 'real_clips': len(clips),
        'method': 'Uniform permutation within MUSIC/NOVELTY and excerpt-count strata; no result selection; no prohibition on original co-membership',
        'novelty_control': 'Same five novelty tracks in shuffled order; order control only',
        'groups': groups,
    }
    write_new(args.plan, plan)
    print(json.dumps({'status': 'plan_frozen', 'plan_sha256': sha(args.plan), 'groups': 30,
                      'music_tracks': 70, 'novelty_tracks': 5, 'created_utc': plan['created_utc']}))


def prepare(args):
    plan = json.loads(args.plan.read_text())
    if plan['private_mapping_sha256'] != sha(args.private_mapping):
        raise ValueError('Private mapping changed after allocation freeze')
    _, clip_rows = read_mapping(args.private_mapping)
    captions = {}
    for line in args.captions.read_text().splitlines():
        row = json.loads(line)
        cid = row['clip_id']
        if cid in captions:
            raise ValueError('Duplicate caption ID')
        captions[cid] = row
    controls = json.loads(args.private_controls.read_text())
    expected = set(clip_rows) | {row['clip_id'] for row in controls}
    if set(captions) != expected:
        raise ValueError(f'Need complete frozen captions: expected {len(expected)}, found {len(captions)}')
    filtered, audit = {}, []
    for cid, mapping in sorted(clip_rows.items()):
        row = captions[cid]
        if row['audio_sha256'] != mapping['wav_sha256']:
            raise ValueError('Caption/audio SHA mismatch')
        raw = row.get('parsed_caption')
        if not isinstance(raw, dict) or not row.get('schema_valid') or row.get('hit_token_limit'):
            raise ValueError('Fix or explicitly exclude invalid/truncated caption before judging: ' + cid)
        safe = {}
        actions = []
        for field, value in raw.items():
            if field not in KEEP:
                actions.append({'field': field, 'action': 'drop_field', 'reason': 'not_in_primary_acoustic_allowlist'})
        for field in KEEP:
            value = raw.get(field)
            if not isinstance(value, str):
                raise ValueError('Expected a string caption field')
            matches = UNVERIFIED.findall(value)
            if matches:
                safe[field] = None
                actions.append({'field': field, 'action': 'replace_with_null',
                                'reason': 'excluded_tempo_meter_spatial_or_template_claim', 'matched': matches})
            else:
                safe[field] = value
        filtered[cid] = safe
        audit.append({'clip_id': cid, 'raw_caption': row['caption'], 'raw_parsed_claims': raw,
                      'retained_claims': safe, 'actions': actions})
    args.outdir.mkdir(parents=True, exist_ok=True)
    destination = args.outdir / 'groups.jsonl'
    with destination.open('x') as stream:
        for group in plan['groups']:
            blind = {'group_id': group['group_id'], 'tracks': [
                {'track': f'T{index}', 'excerpts': [filtered[cid] for cid in track['clip_ids']]}
                for index, track in enumerate(group['tracks'], 1)]}
            stream.write(json.dumps(blind, ensure_ascii=False) + '\n')
    sidecar = {
        'plan_sha256': sha(args.plan), 'captions_sha256': sha(args.captions),
        'caption_meta_sha256': sha(args.caption_meta), 'groups_sha256': sha(destination),
        'script_sha256': sha(Path(__file__)), 'primary_fields': list(KEEP),
        'always_dropped': ['broad_style', 'rhythm_and_tempo', 'production_texture',
                           'changes_within_excerpt', 'uncertain_details'],
        'production_policy': 'Entire production field omitted; stereo/room claims cannot be separated reliably',
        'cross_field_filter_regex': UNVERIFIED.pattern,
        'raw_claims_and_exclusions': audit,
        'private_group_key': plan['groups'],
        'aggregation': 'Compare 14 MUSIC actual with their 14 MUSIC control groups; NOVELTY separately, its control only checks order',
        'limitations': 'Same-model judgement of captions, not independent audio accuracy; one null permutation, no calibrated significance',
    }
    write_new(args.private_sidecar, sidecar)
    print(json.dumps({'status': 'groups_prepared', 'groups': 30, 'groups_sha256': sha(destination),
                      'fields_retained': list(KEEP),
                      'fields_replaced_with_null': sum(a['action']=='replace_with_null' for r in audit for a in r['actions'])}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    first = sub.add_parser('freeze')
    first.add_argument('--private-mapping', type=Path, required=True)
    first.add_argument('--novelty-region', type=int, required=True)
    first.add_argument('--plan', type=Path, required=True)
    second = sub.add_parser('prepare')
    for flag in ('private-mapping','private-controls','plan','captions','caption-meta','outdir','private-sidecar'):
        second.add_argument('--'+flag, type=Path, required=True)
    args = parser.parse_args()
    (freeze if args.mode == 'freeze' else prepare)(args)


if __name__ == '__main__':
    main()
