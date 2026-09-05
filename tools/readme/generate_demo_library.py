#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0

"""Generate original fictional media for LatentJam screenshots.

Requires Python 3, Pillow, ffmpeg (libmp3lame), and rsvg-convert on PATH.
Run: python3 tools/readme/generate_demo_library.py --output-dir /tmp/latentjam-readme-demo

The four artists and releases are fictional. The artwork is original vector
geometry and the quiet audio is original synthesized sine-tone harmony.
No personal media, external recordings, downloaded art, or network is used.
Generated media belongs outside the source repository.
"""
import argparse
import concurrent.futures
import json
import subprocess
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, default=Path('/tmp/latentjam-readme-demo'))
ROOT = parser.parse_args().output_dir.expanduser().resolve()
ROOT.mkdir(parents=True, exist_ok=True)
MUSIC = ROOT / 'Music'
ART = ROOT / 'Artwork'
MUSIC.mkdir(exist_ok=True)
ART.mkdir(exist_ok=True)
ALBUMS = [
    dict(artist='Aster Loom', album='Soft Geometry', genre='Electronic', year=2026, color='#efb86c', bg='#172827', notes=[130.8128,164.8138,195.9977,246.9417], tracks=[('First Light',193),('Paper Planes',216),('Soft Geometry',247),('Slow Motion',182)]),
    dict(artist='Lumen Ferry', album='Night Current', genre='Ambient', year=2026, color='#72d5cd', bg='#112536', notes=[110,146.8324,164.8138,220], tracks=[('Blue Hour',228),('Night Current',265),('City in Rain',201),('Distant Windows',239)]),
    dict(artist='Juniper Signal', album='Golden Hours', genre='Indie', year=2025, color='#b75637', bg='#f0e3c9', notes=[146.8324,184.9972,220,277.1826], tracks=[('Golden Hours',204),('Small Adventures',191),('Sunroom',223),('The Way Home',248)]),
    dict(artist='Sora Atlas', album='Quiet Orbit', genre='Downtempo', year=2025, color='#dfc6f4', bg='#423354', notes=[123.4708,164.8138,195.9977,246.9417], tracks=[('Quiet Orbit',258),('Weightless',235),('Open Skies',209),('Goodnight, Satellite',274)]),
]

def art_for(i, a):
    bg,c=a['bg'],a['color']
    if i==0:
        art=f'''<g fill="{c}"><circle cx="300" cy="212" r="102"/><path d="M84 348h216v168H84z"/><path d="M324 324a108 108 0 0 1 216 0v192H324z"/></g><path d="M84 348h216" stroke="{bg}" stroke-width="18"/><g fill="{bg}"><circle cx="192" cy="432" r="56"/><path d="M366 380h132v18H366zM366 419h132v18H366zM366 458h132v18H366z"/></g>'''
    elif i==1:
        art=f'''<defs><linearGradient id="water" x1="0" y1="0" x2="0" y2="1"><stop stop-color="{c}"/><stop offset="1" stop-color="#28768a"/></linearGradient></defs><circle cx="300" cy="288" r="175" stroke="{c}" stroke-width="2" opacity=".2" fill="none"/><circle cx="300" cy="288" r="139" stroke="{c}" stroke-width="2" opacity=".4" fill="none"/><circle cx="300" cy="288" r="103" stroke="{c}" stroke-width="2" opacity=".6" fill="none"/><circle cx="300" cy="288" r="67" fill="{c}"/><path d="M40 344q130-52 260 0t260 0v215H40z" fill="url(#water)"/>'''+''.join(f'<path d="M40 {375+k*28}q130-52 260 0t260 0" stroke="{bg}" opacity=".65" stroke-width="3" fill="none"/>' for k in range(6))
    elif i==2:
        art=f'''<circle cx="412" cy="214" r="70" fill="#dc9866"/><path d="M50 390Q220 126 550 385V550H50Z" fill="#d88f58"/><path d="M50 438Q229 203 550 395V550H50Z" fill="#b75637"/><path d="M50 485Q322 273 550 464V550H50Z" fill="#784632"/><g fill="none" stroke="{bg}" stroke-width="2" opacity=".32">'''+''.join(f'<path d="M65 {463+k*12}Q281 {249+k*10} 535 {435+k*12}"/>' for k in range(6))+'</g>'
    else:
        art=f'''<defs><linearGradient id="planet" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#e5d2f2"/><stop offset="1" stop-color="#9881b2"/></linearGradient></defs><g transform="rotate(-28 300 340)" fill="none" stroke="{c}" opacity=".7"><ellipse cx="300" cy="340" rx="228" ry="86"/><ellipse cx="300" cy="340" rx="247" ry="105" opacity=".4"/></g><circle cx="300" cy="340" r="118" fill="url(#planet)"/><path d="M74 391a247 105 -28 0 0 435-80" stroke="{bg}" stroke-width="10" fill="none"/><path d="M74 391a247 105 -28 0 0 435-80" stroke="{c}" stroke-width="2" fill="none"/><circle cx="476" cy="204" r="9" fill="#f2c082"/><path d="M113 236h16m-8-8v16" stroke="{c}" stroke-width="2"/>'''
    svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 600 600"><rect width="600" height="600" fill="{bg}"/><rect x="28" y="28" width="544" height="544" rx="2" fill="none" stroke="{c}" stroke-width="1" opacity=".38"/><text x="50" y="78" font-family="Helvetica,Arial,sans-serif" font-size="24" font-weight="600" fill="{c}" letter-spacing="2">{a['artist'].upper()}</text><text x="50" y="108" font-family="Helvetica,Arial,sans-serif" font-size="14" fill="{c}" letter-spacing="2" opacity=".7">{a['album'].upper()}</text>{art}<text x="50" y="574" font-family="Helvetica,Arial,sans-serif" font-size="10" fill="{c}" letter-spacing="3">LATENTJAM DEMO / 0{i+1}</text></svg>'''
    svg_path=ART/f"{a['album']}.svg"
    png_path=svg_path.with_suffix('.png')
    jpg_path=svg_path.with_suffix('.jpg')
    svg_path.write_text(svg)
    subprocess.run(['rsvg-convert','-o',str(png_path),str(svg_path)],check=True)
    Image.open(png_path).convert('RGB').save(jpg_path,quality=88,optimize=True)
    return jpg_path

manifest=[]
jobs=[]
for ai,a in enumerate(ALBUMS):
    cover=art_for(ai,a)
    folder=MUSIC/a['artist']/a['album']
    folder.mkdir(parents=True,exist_ok=True)
    for number,(title,duration) in enumerate(a['tracks'],start=1):
        output=folder/f'{number:02d} - {title}.mp3'
        # Original, quiet sine-tone harmony, slowly breathing with an 8-second envelope.
        tone='+'.join(f'sin(2*PI*{n}*t)' for n in a['notes'])
        source=f"aevalsrc=0.007*(0.55-0.45*cos(2*PI*t/8))*({tone}):s=22050:d={duration}"
        cmd=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i',source,'-i',str(cover),'-map','0:a','-map','1:v','-c:a','libmp3lame','-b:a','24k','-ac','1','-af','afade=t=in:d=3,afade=t=out:st='+str(duration-3)+':d=3','-c:v','copy','-id3v2_version','3','-metadata','title='+title,'-metadata','artist='+a['artist'],'-metadata','album_artist='+a['artist'],'-metadata','album='+a['album'],'-metadata','genre='+a['genre'],'-metadata','date='+str(a['year']),'-metadata','track='+str(number)+'/4','-metadata','comment=Original synthetic demo audio for LatentJam screenshots. Fictional release.','-metadata:s:v','title=Album cover','-metadata:s:v','comment=Cover (front)',str(output)]
        jobs.append(cmd)
        manifest.append(dict(title=title,artist=a['artist'],album=a['album'],genre=a['genre'],year=a['year'],track=number,durationMs=duration*1000,filename=output.name,relativePath=str(output.relative_to(MUSIC)),path=str(output),coverPath=str(cover),fictional=True))

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(lambda cmd:subprocess.run(cmd,check=True),jobs))
(ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(ROOT/'README.txt').write_text('Original fictional demo library generated for LatentJam README media.\nAll cover art uses original vector geometry. All audio uses original synthesized sine tones.\nNo real user tracks, third-party recordings, photos, or downloaded artwork.\nRun python3 tools/readme/generate_demo_library.py to reproduce. Requires ffmpeg, rsvg-convert, and Pillow.\n')
print(f'Generated {len(manifest)} demo tracks, {len(ALBUMS)} covers, {sum(Path(t["path"]).stat().st_size for t in manifest)/1024/1024:.1f} MiB.')
