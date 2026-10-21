#!/usr/bin/env python3
"""Overnight candidate model pairs: base (research serving/) + judge-driven keys -> stand/night-models/<label>_{core,full}.json"""
import copy, json, sys
from pathlib import Path
SV = Path('/Users/nichitabulgaru/Documents/LJ/latentjam/.claude/worktrees/smart-stage0-eval-f2a727/tools/research/listenbrainz_eval/serving')
OUT = Path.home() / 'Documents/LJ/phone-state/stand/night-models'


def classes(lam, chanson=None):
    c = [{'name': 'kids', 'genre': ['children', 'kids', 'nursery', 'lullab', 'детск', 'animation ost'], 'text': ['смешарик', 'smeshariki', 'мультфильм'], 'lambda': lam},
         {'name': 'meme', 'genre': ['meme', 'sound effect', 'tiktok', 'voice recording', 'ringtone', 'game audio'], 'text': ['meme', '-мем', ' мем', 'sound effect'], 'lambda': lam},
         {'name': 'comedy', 'tokens': ['comedy', 'comedy rock', 'parody'], 'lambda': lam}]
    if chanson: c.append({'name': 'chanson', 'genre': ['chanson', 'шансон'], 'seedIn': ['meme', 'comedy'], 'lambda': chanson})
    return {'classes': c}


def make(label, base, cls=None, chanson=None, short=None, jump=None, fill_all=False, extra=None, run=None):
    for k in ('core', 'full'):
        m = json.loads((SV / f'model_{k}_next_{base}.json').read_text())
        m['name'] = f"{m['name']} night-{label}"
        if cls is not None: m['tagClasses'] = classes(cls, chanson)
        if short is not None: m['durationRule']['shortLambda'] = short
        if jump is not None: m['prevFamilyJump'] = {'lambda': jump}
        if fill_all: m['serving']['coldAudioFill'] = 'all'
        if run is not None: m['runPenalty'] = round(m['runPenalty'] * run, 4)
        for kk, vv in (extra or {}).items(): m[kk] = vv
        (OUT / f'{label}_{k}.json').write_text(json.dumps(m, indent=1, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    make('r1a', 'n9', cls=2.0, chanson=2.0, short=2.0, fill_all=True)
    make('r1b', 'n9', cls=2.0, chanson=2.0, short=2.0, fill_all=True, jump=1.0)
    make('r1c', 'n8', cls=2.0, chanson=2.0, short=2.0, fill_all=True, jump=1.0)
    R1 = dict(cls=2.0, chanson=2.0, short=2.0, fill_all=True, jump=1.0)
    make('r2a', 'n9', **R1, run=1.3)
    make('r2b', 'n9', **R1, run=1.6)
    make('r2c', 'n9', **dict(R1, cls=3.0, chanson=3.0, jump=2.0))
    make('r2d', 'n8', **R1, run=1.3)
    R3 = dict(cls=3.0, chanson=3.0, short=2.0, fill_all=True, jump=2.0, run=1.6)
    make('r3a', 'n9', **R3)
    make('r3b', 'n9', **dict(R3, cls=4.0, chanson=4.0))
    make('r3c', 'n8', **R3)
    print(sorted(p.name for p in OUT.iterdir()))
