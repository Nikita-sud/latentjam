#!/usr/bin/env python3
"""Pure-audio clustering on the full saved library; retain production naming/routing."""
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess
import numpy as np
from threadpoolctl import threadpool_limits
from audit_transfer import neighbors,playlist_score
from train_fusion import unit
from refresh_mpd import write_json


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--snapshot',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--classpath-file',type=Path,required=True)
    a=ap.parse_args();threadpool_limits(limits=4);p=a.snapshot/'regions';out=a.output;out.mkdir(exist_ok=True)
    rows=json.loads((p/'private-cooked-metadata.json').read_text());audio=np.fromfile(p/'audio.f32',dtype='<f4').reshape(-1,960)
    assert len(rows)==len(audio) and np.all(np.linalg.norm(audio,axis=1)>0)
    audio.astype('<f4').tofile(out/'private-audio-input.f32')
    current=np.fromfile(p/'audio25-meta75-input.f32',dtype='<f4').reshape(len(rows),1344)
    current.astype('<f4').tofile(out/'private-current-input.f32')
    plan={'ids':[r['id'] for r in rows],'cases':[{'name':'audio_only','dim':960,'input':str(out/'private-audio-input.f32'),'audio_only':True},{'name':'current_1344','dim':1344,'input':str(out/'private-current-input.f32')}]}
    write_json(out/'private-plan.json',plan);cp=a.classpath_file.read_text().strip();java=Path('/opt/homebrew/opt/openjdk@21/bin')
    for cls in ['FusionRegionProbe','MapProjectionProbe']:
        subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(out),str(Path(__file__).with_name(cls+'.java'))],check=True)
    subprocess.run([str(java/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'FusionRegionProbe',str(a.snapshot),str(out),str(out/'private-plan.json')],check=True)
    playlists=[r['members'] for r in json.loads((p/'private-playlists.json').read_text())]
    genres=[set(r.get('genreFamilies',[])) for r in rows];artists=np.array([(r.get('artist') or '').strip().casefold() for r in rows])
    summaries={};details={}
    centered_audio=unit(unit(audio)-unit(audio).mean(0))
    for name,dim in [('audio_only',960),('current_1344',1344)]:
        features=np.fromfile(out/f'private-{name}-used-input.f32',dtype='<f4').reshape(len(rows),dim)
        centered=unit(features-features.mean(0));near=neighbors(centered)
        reg=json.loads((out/f'private-regions-{name}.json').read_text());worlds=reg['worlds']
        known=0;dominant=0;members=[];region_memberships=[];audio_pair_sum=0.;pairs=0;enriched=[]
        for world in worlds:
            mem=world['members'];members.extend(mem);freq=Counter(g for i in mem for g in genres[i]);has=sum(bool(genres[i]) for i in mem)
            known+=has;dominant+=max(freq.values(),default=0)
            for pl in playlists:
                queries=set(mem)&set(pl)
                if queries and len(pl)>=4:region_memberships.extend([(len(queries)-1)/(len(mem)-1)]*len(queries))
            av=centered_audio[mem];cnt=len(mem)*(len(mem)-1)
            if cnt:audio_pair_sum+=float(np.square(av.sum(0)).sum()-np.square(av).sum());pairs+=cnt
            # Central tracks by the actual representation's direction from its library mean.
            centroid=unit(centered[mem].mean(0,keepdims=True))[0]
            central=sorted(mem,key=lambda i:-float(centered[i]@centroid))[:5]
            examples=[{'artist':rows[i].get('artist'),'title':rows[i].get('title'),'genre':rows[i].get('genre')} for i in central]
            own=dict(world,size=len(mem),known_genres=has,top_genre_families=freq.most_common(5),top_artists=Counter(artists[mem]).most_common(4),central_tracks=examples)
            enriched.append(own)
        path=out/f'private-{name}-2d.f32'
        subprocess.run([str(java/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'MapProjectionProbe',str(out/f'private-{name}-used-input.f32'),str(path),str(dim)],check=True,capture_output=True,text=True)
        xy=np.fromfile(path,dtype='<f4').reshape(len(rows),2)
        summaries[name]={'tracks':len(rows),'dimensions':dim,'raw_clusters':len(reg['raw_clusters']),'regions':len(worlds),'assigned_tracks':len(members),'unassigned_tracks':len(rows)-len(set(members)),
                        'sizes':[len(w['members']) for w in worlds],'playlist_at15':playlist_score(near,playlists)[0],
                        'map_playlist_at15':playlist_score(neighbors(xy),playlists)[0],
                        'same_artist_at15':float((artists[near]==artists[:,None]).mean()),
                        'dominant_genre_coverage':dominant/known if known else None,
                        'within_region_playlist_agreement':float(np.mean(region_memberships)),
                        'within_region_audio_pair_cosine':audio_pair_sum/pairs,
                        'genre_known_assigned':known,'name_sources':dict(Counter(w['discovered_name_source'] for w in worlds))}
        details[name]=enriched
    write_json(out/'private-region-details.json',details)
    # Confirm the full-population control exactly reproduces the earlier saved default.
    old=json.loads((p/'private-regions-audio25-meta75.json').read_text())['worlds']
    old_sets={frozenset(w['members']) for w in old};new_sets={frozenset(w['members']) for w in details['current_1344']}
    assert old_sets==new_sets,'Full-library production control did not replay'
    summary={'metrics':summaries,'production_control_memberships_match_saved_replay':True,
             'limits':['Clustering geometry uses only audio for audio_only; production content routing and names still consult tags/semantics.','No new listening or MOSS evaluation. Metadata agreement and audio-vector coherence are imperfect, different proxies.','Full 1107-row population; do not mix with the separate 1106 complete-modality MLP transfer table.']}
    write_json(out/'audio-only-summary.json',summary)
    print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
