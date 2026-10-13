"""Conservative label-free caption feature draft. Blinded text review by an agent is required for primary use; evidence remains model-derived."""
import re
FIELDS=('content','voice','instruments','rhythm','tempo','energy','production')
CATEGORIES={'content':{'music','speech','effects','mixed'},'voice':{'instrumental','sung','rapped','spoken','mixed'},'rhythm':{'none','loose','steady','complex'},'tempo':{'slow','medium','fast'},'energy':{'low','medium','high'},'production':{'acoustic','electronic','hybrid'}}
INSTRUMENTS={'percussion','bass','guitars','keyboards','strings','brass_woodwind','synthesizers','choir','other'}
UNKNOWN=re.compile(r'^(unknown|unclear|not clear|not sure|uncertain|n/?a|insufficient|cannot determine|can\x27t determine)(\b|$)',re.I)
def text(v):return str(v or '').lower().strip()
def positive(t,p):
 for m in re.finditer(p,t):
  prefix=t[max(0,m.start()-48):m.start()]
  if not re.search(r'\b(no|not|without|lacks?|absent|absence of)\s+(?:\w+\s+){0,3}$',prefix):return True
 return False
def chosen(t,options):
 if UNKNOWN.search(t):return None
 vals=[v for v,p in options.items() if positive(t,p)]
 return vals[0] if len(vals)==1 else None
def draft(c):
 c={k:text(v) for k,v in c.items()}; result={f:None for f in FIELDS};evidence={}
 broad=' '.join(c.get(k,'') for k in ['broad_style','vocals','instruments']); kinds=[v for v,p in {'music':r'\bmusic\b','speech':r'\bspeech\b|\bspoken dialogue\b','effects':r'\bsound effects?\b|\bnoise recording\b'}.items() if positive(broad,p)]
 if kinds:result['content']=kinds[0] if len(kinds)==1 else 'mixed';evidence['content']=broad
 v=c.get('vocals','');options={'sung':r'\bsing(?:s|ing|er)?\b|\bsung\b|\bmelodic vocals?\b','rapped':r'\brap(?:s|ping|ped)?\b','spoken':r'\bspoken\b|\bspeaking\b|\bspoken word\b'};hits=[k for k,p in options.items() if positive(v,p)]
 if re.search(r'\binstrumental\b|\bno (?:audible )?(?:vocals?|voices?|singing)\b|\bwithout (?:vocals?|voices?|singing)\b',v) or v in ('none','no vocals'):result['voice']='instrumental'
 elif hits:result['voice']=hits[0] if len(hits)==1 else 'mixed'
 if result['voice']:evidence['voice']=v
 ins=c.get('instruments','');patterns={'percussion':r'\bdrums?\b|\bpercussion\b|\bcymbals?\b|\bhi[- ]?hats?\b|\bsnare\b|\bkick\b','bass':r'\bbass\b','guitars':r'\bguitars?\b','keyboards':r'\bpiano\b|\bkeyboards?\b|\borgan\b|\bharpsichord\b','strings':r'\bstrings?\b|\bviolins?\b|\bcello\b|\bviola\b','brass_woodwind':r'\bbrass\b|\bwoodwinds?\b|\bsaxophone\b|\bsax\b|\btrumpet\b|\btrombone\b|\bflute\b|\bclarinet\b|\boboe\b','synthesizers':r'\bsynth(?:s|esizer|esizers|esiser|esisers)?\b|\belectronic pads?\b','choir':r'\bchoir\b|\bchoral\b'}
 if not UNKNOWN.search(ins):
  fam=[k for k,p in patterns.items() if positive(re.sub(r'\bbass guitars?\b','bass',ins) if k=='guitars' else ins,p)]
  if fam:result['instruments']=fam;evidence['instruments']=ins
 rt=c.get('rhythm_and_tempo','')
 if re.search(r'\bno (?:clear |steady |discernible )?(?:beat|pulse|rhythm)\b|\bbeatless\b',rt):result['rhythm']='none'
 else:result['rhythm']=chosen(rt,{'steady':r'\bsteady\b|\bregular\b|\bconsistent beat\b|\bstraightforward\b','loose':r'\bfree[- ]?flowing\b|\bfree time\b|\bloose\b|\bsparse\b','complex':r'\bcomplex\b|\bpolyrhythm\w*\b|\birregular\b|\bodd[- ]meter\b'})
 result['tempo']=chosen(rt,{'slow':r'\bslow\b|\bdowntempo\b|\bleisurely\b','medium':r'\bmedium\b|\bmoderate\b|\bmid[- ]tempo\b|\bmidtempo\b','fast':r'\bfast\b|\bquick\b|\brapid\b|\buptempo\b|\bup[- ]tempo\b'})
 # Exact numeric BPM is deliberately ignored. Conflicting adjectives stay unknown.
 for f in ['rhythm','tempo']:
  if result[f]:evidence[f]=rt
 en=c.get('energy','');result['energy']=chosen(en,{'low':r'\blow\b|\bsubdued\b|\brestrained\b|\bquiet\b|\bcalm\b|\bgentle\b','medium':r'\bmedium\b|\bmoderate\b|\bbalanced\b','high':r'\bhigh\b|\benergetic\b|\bintense\b|\bdriving\b|\bpowerful\b|\bloud\b|\bupbeat\b'})
 if result['energy']:evidence['energy']=en
 pr=c.get('production_texture','');a=positive(pr,r'\bacoustic\b');el=positive(pr,r'\belectronic\b|\bsynthetic\b|\bsynthesized\b|\bsynthesised\b');hy=positive(pr,r'\bhybrid\b')
 if not UNKNOWN.search(pr):
  if hy or a and el:result['production']='hybrid'
  elif a:result['production']='acoustic'
  elif el:result['production']='electronic'
 if result['production']:evidence['production']=pr
 return {'features':result,'evidence':evidence,'reviewed_blind':False,'warning':'Heuristic draft only. Check literal caption support without metadata; unknown is preferable to inference.'}
def validate(record):
 if record.get('reviewed_blind') is not True:raise ValueError('primary features require explicit blind review')
 fs=record['features']
 if set(fs)!=set(FIELDS):raise ValueError('feature keys mismatch')
 for f,v in fs.items():
  if v is None:continue
  if f=='instruments':
   if not isinstance(v,list) or len(v)!=len(set(v)) or not set(v)<=INSTRUMENTS:raise ValueError('invalid instruments')
  elif v not in CATEGORIES[f]:raise ValueError('invalid category for '+f)
  if not record.get('evidence',{}).get(f):raise ValueError('observed feature requires evidence: '+f)
 return fs

def distance(a,b):
 ds={}
 for f in FIELDS:
  x,y=a.get(f),b.get(f)
  if x is None or y is None:continue
  if f=='instruments':
   sx,sy=set(x),set(y);ds[f]=1-len(sx&sy)/len(sx|sy) if sx|sy else 0.0
  elif f in ('tempo','energy'):
   order=['slow','medium','fast'] if f=='tempo' else ['low','medium','high'];ds[f]=abs(order.index(x)-order.index(y))/2
  else:ds[f]=float(x!=y)
 return (sum(ds.values())/len(ds) if len(ds)>=4 else None),ds
