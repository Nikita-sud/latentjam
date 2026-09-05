#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0

"""Generate private app-file payloads from a synthetic-only library; never writes to a device.

python3 tools/readme/seed_demo_history.py --metadata /tmp/demo/tracks.json \
    --media-query /tmp/demo/media-query.txt --out /tmp/demo/seed --timezone UTC

metadata is a list (or {"tracks": [...]}) of fictional records with a title key.
media-query is adb shell content query --uri content://media/external/audio/media \
    --projection _id:title:artist:album:duration --where 'is_music != 0'
Run only against a dedicated, freshly wiped demo emulator. The exact title allowlist
must match the complete MediaStore music library; foreign tracks abort generation.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import random
import re
import time
from xml.etree import ElementTree as ET
from zoneinfo import ZoneInfo

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--metadata', required=True, type=Path)
parser.add_argument('--media-query', required=True, type=Path)
parser.add_argument('--out', required=True, type=Path)
parser.add_argument('--now-ms', type=int, default=int(time.time()*1000))
parser.add_argument('--timezone', default='UTC')
parser.add_argument('--theme', choices=['LIGHT', 'DARK', 'SYSTEM'], default='DARK')
parser.add_argument('--start-page', default='for_you', choices=['for_you','tracks','albums','playlists','statistics'])
args = parser.parse_args()

def require(condition, message):
    if not condition:
        raise ValueError(message)

metadata = json.loads(args.metadata.read_text())
if isinstance(metadata, dict):
    metadata = metadata['tracks']
require(all(item.get('fictional') is True for item in metadata), 'Every metadata record must explicitly be fictional')
expected = [item['title'] for item in metadata]
require(len(expected) >= 8 and len(set(expected)) == len(expected), 'Need >=8 unique synthetic titles')
rows=[]
for line in args.media_query.read_text().splitlines():
    if not line.startswith('Row: '):
        continue
    fields=dict(re.findall(r'(?:^Row: \d+ |, )([A-Za-z_][A-Za-z_0-9]*)=(.*?)(?=, [A-Za-z_][A-Za-z_0-9]*=|$)', line))
    require(fields.get('_id','').isdigit(), 'Expected decimal MediaStore ID')
    rows.append(fields)
require(len(rows)==len(expected), 'Library count differs from synthetic metadata: refusing to seed')
require({row.get('title') for row in rows} == set(expected), 'Non-demo or missing music found: refusing to seed')
by_title={row['title']:row for row in rows}
tracks=[by_title[title] for title in expected]
require(all(track['artist'] == item['artist'] for track,item in zip(tracks,metadata)), 'Artist mismatch: refusing to seed')
require(all(int(track['duration']) >= 1000 for track in tracks), 'MediaStore duration missing')
zone=ZoneInfo(args.timezone)
now=dt.datetime.fromtimestamp(args.now_ms/1000,zone)
rng=random.Random(90210)
events=[]
used_tracks=max(6,len(tracks)-2)
for days_ago in range(60,-1,-1):
    if days_ago in {4,12,19,26,33,43,44,54}:
        continue
    day=now.date()-dt.timedelta(days=days_ago)
    daily_plays=rng.randint(8,17) if days_ago < 30 else rng.randint(4,10)
    booked=[]
    for play in range(daily_plays):
        hour=rng.choices([7,9,12,15,18,21],weights=[1,1,2,1,5,3])[0]
        minute=rng.randrange(0,50)
        started=dt.datetime.combine(day,dt.time(hour,minute),zone)
        started_ms=int(started.timestamp()*1000)
        # Recent discoveries leave several honest "New to you" tracks in the dashboard.
        available=used_tracks-(2 if days_ago>7 else 0)-(2 if days_ago>30 else 0)
        index=rng.choices(range(available),weights=[max(1,available-i) for i in range(available)])[0]
        track=tracks[index]
        duration=int(track['duration'])
        draw=rng.random()
        skipped=draw < .065
        completed=draw >= .17
        listened=(min(duration//5,23000) if skipped else
                  duration if completed else round(duration*rng.uniform(.42,.81)))
        if started_ms+listened+60000>=args.now_ms:
            continue
        if any(started_ms < end and started_ms+listened > start for start,end in booked):
            continue
        booked.append((started_ms,started_ms+listened))
        mode=rng.choices(['OFF','ON','SMART'],weights=[18,13,69])[0]
        event=['v3',track['_id'].encode().hex(),str(started_ms),str(listened),str(duration),
               '1' if completed else '0','1' if skipped else '0',mode.encode().hex(),str(listened)]
        events.append((started_ms,'|'.join(event),listened,index))
# Ensure recent discoveries appear even when random selection favors familiar tracks.
for i in range(max(0,used_tracks-2),used_tracks):
    track=tracks[i]
    duration=int(track['duration'])
    started_ms=args.now_ms-(used_tracks-i+2)*3600000
    events.append((started_ms,'|'.join(['v3',track['_id'].encode().hex(),str(started_ms),str(duration),str(duration),'1','0','SMART'.encode().hex(),str(duration)]),duration,i))
events.sort(key=lambda e:e[0])
args.out.mkdir(parents=True,exist_ok=True)
files=args.out/'files'
files.mkdir(exist_ok=True)
(files/'listening_history.log').write_text('\n'.join(event[1] for event in events)+'\n')
ids=[track['_id'] for track in tracks]
(files/'favorites.txt').write_text('\n'.join(ids[i] for i in [0,1,4,6] if i<len(ids))+'\n')
def playlist(pid,name,indices,smart):
    return '\x1f'.join(['v3',pid.encode().hex(),name.encode().hex(),str(args.now_ms-7*86400000),
                         ','.join(ids[i].encode().hex() for i in indices if i<len(ids)),str(int(smart))])
(files/'playlists.txt').write_text('\n'.join([
    playlist('demo-blue-hour','Blue hour', [0,1,2,3,4,5],True),
    playlist('demo-slow-mornings','Slow mornings',[6,7,8,9,10],False),
    playlist('demo-after-dark','After dark',[10,11,12,13,14,15],True),
])+'\n')
prefs=ET.Element('map')
def string(key,value): ET.SubElement(prefs,'string',{'name':key}).text=value
def boolean(key,value): ET.SubElement(prefs,'boolean',{'name':key,'value':str(value).lower()})
string('theme_mode',args.theme)
string('start_page',args.start_page)
string('page_layout_v1','LJPL1|for_you,tracks,albums,playlists,statistics,artists,genres,folders,map|map')
string('track_color_mode','dynamic')
boolean('save_listening_history',True)
boolean('remember_searches',False)
ET.SubElement(prefs,'int',{'name':'smart_queue_length','value':'20'})
shared=args.out/'shared_prefs'
shared.mkdir(exist_ok=True)
ET.indent(prefs,space='    ')
ET.ElementTree(prefs).write(shared/'app_settings.xml',encoding='utf-8',xml_declaration=True)
summary={'synthetic_only':True,'track_count':len(tracks),'event_count':len(events),
         'listening_hours':round(sum(e[2] for e in events)/3600000,1),'timezone':args.timezone,
         'now':now.isoformat(),'files':[str(p.relative_to(args.out)) for p in args.out.rglob('*') if p.is_file()]}
(args.out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
