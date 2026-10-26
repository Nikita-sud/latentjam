# absum.py <prefix> <name>... : per-build medians over runs/ab_<name>_r*.txt (12 skips each)
import sys,re,glob,statistics as st,os,json
hdr=re.compile(r'^(.*?)\t(\d+)/(\d+) \[\d+\] ([\d.]+)')
cache=os.path.join(os.path.dirname(sys.argv[0]),'runs','absum_cache.json')
C=json.load(open(cache)) if os.path.exists(cache) else {}
def stats(f):
    if f in C: return C[f]
    samples=[];cur=None
    for line in open(f,errors='replace'):
        if not line.startswith('\t') and line.strip():
            m=hdr.match(line); cur=None
            if m and m.group(2)==m.group(3): cur=[float(m.group(4)),0,0]; samples.append(cur)
        elif cur is not None:
            if 'Recomposer.performRecompose' in line: cur[1]=1
            if 'animatePlayerCloud' in line: cur[2]=1
    samples.sort(); b=[];bursts=[]
    for s in samples:
        if b and s[0]-b[-1][0]>0.003: bursts.append(len(b)*0.25); b=[]
        b.append(s)
    bursts.append(len(b)*0.25)
    r=dict(total=len(samples)*.25, recomp=sum(s[1] for s in samples)*.25, cloud=sum(s[2] for s in samples)*.25,
           long=sum(x for x in bursts if x>=10), n20=sum(1 for x in bursts if x>20), n33=sum(1 for x in bursts if x>33), top=max(bursts))
    C[f]=r; json.dump(C,open(cache,'w')); return r
for name in sys.argv[1:]:
    fs=set(glob.glob(os.path.join(os.path.dirname(sys.argv[0]),'runs',f'ab_{name}_r*.txt')))|{k for k in C if os.path.basename(k).startswith(f'ab_{name}_r')}
    rs=[stats(f) for f in sorted(fs)]
    if not rs: continue
    med=lambda k: st.median(r[k] for r in rs)
    print(f"{name:6s} runs={len(rs)} per skip: main={med('total')/12:5.1f}ms recompose={med('recomp')/12:5.1f}ms long-burst={med('long')/12:5.1f}ms >20ms={med('n20')/12:4.2f} >33ms={med('n33')/12:4.2f} | worst burst median={med('top'):5.1f}ms cloud(speed)={med('cloud'):4.0f}")
