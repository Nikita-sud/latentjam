import sys,re,collections
# outermost app frame (root-side) within samples containing a marker frame; also 2nd-level
f=sys.argv[1]; minb=float(sys.argv[2]); marker=sys.argv[3]; depth=int(sys.argv[4]) if len(sys.argv)>4 else 1
samples=[];cur=None
hdr=re.compile(r'^(.*?)\t(\d+)/(\d+) \[\d+\] ([\d.]+): (\d+) cpu-clock')
for line in open(f, errors='replace'):
    if not line.startswith('\t') and line.strip():
        m=hdr.match(line)
        cur=[float(m.group(4)),[]] if m and m.group(2)==m.group(3) else None
        if cur: samples.append(cur)
        continue
    if cur is not None and line.startswith('\t'):
        p=line.strip().split(' ',1)
        if len(p)==2: cur[1].append(p[1].rsplit(' (',1)[0])
samples.sort()
bursts=[];b=[]
for s in samples:
    if b and s[0]-b[-1][0]>0.003: bursts.append(b); b=[]
    b.append(s)
bursts.append(b)
long=[s for b in bursts if len(b)*0.25>=minb for s in b]
c=collections.Counter(); tot=0
for t,fr in long:
    fr=fr[::-1]  # root first
    idx=[i for i,n in enumerate(fr) if re.search(marker,n)]
    if not idx: continue
    tot+=0.25
    rest=fr[idx[0]+1:]
    apps=[n for n in rest if 'latentjam' in n and ('Synthetic' not in n or len(sys.argv)>6)]
    key=' > '.join(a.replace('io.github.nikitasud.latentjam.','') [:90] for a in apps[:depth]) or ('<no app frame> '+(rest[-1][:80] if rest else ''))
    c[key]+=0.25
print(f"{marker}: {tot:.0f} ms")
for k,v in c.most_common(int(sys.argv[5]) if len(sys.argv)>5 else 30): print(f"{v:7.1f}  {k}")
