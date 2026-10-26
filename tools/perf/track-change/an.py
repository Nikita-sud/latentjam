import sys,re,collections
f=sys.argv[1]; minb=float(sys.argv[2]) if len(sys.argv)>2 else 10.0
samples=[]  # (time, frames) main thread only
cur=None
hdr=re.compile(r'^(.*?)\t(\d+)/(\d+) \[\d+\] ([\d.]+): (\d+) cpu-clock')
for line in open(f, errors='replace'):
    if not line.startswith('\t') and line.strip():
        m=hdr.match(line)
        if m:
            pid,tid=m.group(2),m.group(3)
            cur=[float(m.group(4)),[]] if pid==tid else None
            if cur: samples.append(cur)
        continue
    if cur is not None and line.startswith('\t'):
        parts=line.strip().split(' ',1)
        if len(parts)==2:
            name=parts[1].rsplit(' (',1)[0]
            cur[1].append(name)
samples.sort()
tot=len(samples)*0.25
bursts=[];b=[]
for s in samples:
    if b and s[0]-b[-1][0]>0.003: bursts.append(b); b=[]
    b.append(s)
if b: bursts.append(b)
dur=lambda b: len(b)*0.25
long=[b for b in bursts if dur(b)>=minb]
print(f"main-thread CPU {tot:.0f} ms; bursts>={minb}ms: {len(long)} sum {sum(dur(b) for b in long):.0f} ms; >20ms: {sum(1 for b in bursts if dur(b)>20)}; top {sorted([round(dur(b)) for b in bursts],reverse=True)[:12]}")
inc=collections.Counter()
for b in long:
    for t,fr in b:
        seen=set()
        for n in fr:
            if ('latentjam' in n or 'androidx.compose' in n[:20] and False) and n not in seen:
                if 'ExternalSynthetic' in n: continue
                seen.add(n); inc[n]+=0.25
n_long=sum(dur(b) for b in long) or 1
for n,v in inc.most_common(int(sys.argv[3]) if len(sys.argv)>3 else 45):
    print(f"{v:7.1f} ms {100*v/n_long:5.1f}%  {n[:150]}")
