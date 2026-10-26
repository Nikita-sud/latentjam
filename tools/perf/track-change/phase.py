import sys,re
hdr=re.compile(r'^(.*?)\t(\d+)/(\d+) \[\d+\] ([\d.]+)')
P={'total':None,'recompose':'Recomposer.performRecompose','apply':'CompositionImpl.applyChanges','layout':'AndroidComposeView.measureAndLayout','draw':'AndroidComposeView.dispatchDraw','frame':'Choreographer.doFrame','media3sess':'MediaSessionImpl$PlayerListener','ctrl':'AndroidPlaybackController','widget':'Widget','fades':'Fades','cloudanim':'animatePlayerCloud'}
for f in sys.argv[1:]:
    cnt={k:0 for k in P}; cur=None
    def done(fr):
        if fr is None: return
        cnt['total']+=1
        for k,v in P.items():
            if v and any(v in n for n in fr): cnt[k]+=1
    for line in open(f,errors='replace'):
        if not line.startswith('\t') and line.strip():
            done(cur); m=hdr.match(line); cur=[] if m and m.group(2)==m.group(3) else None
        elif cur is not None and line.startswith('\t'): cur.append(line)
    done(cur)
    print(f"{f.split('/')[-1][:-4]:14s} "+' '.join(f"{k}={v*0.25:6.0f}" for k,v in cnt.items()))
