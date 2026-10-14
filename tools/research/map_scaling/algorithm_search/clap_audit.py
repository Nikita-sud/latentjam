"""Local-only independent audio audit; deterministic ten-second centre crops."""
import os
os.environ['HF_HUB_OFFLINE']='1'
os.environ['TRANSFORMERS_OFFLINE']='1'
import argparse,json,time,hashlib
from pathlib import Path
import numpy as np
import torch,soundfile as sf
from scipy.signal import resample_poly
from transformers import ClapModel,ClapProcessor

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--clips',type=Path,required=True);p.add_argument('--limit',type=int,default=0);p.add_argument('--model-path',type=Path,default=Path.home()/'.cache/huggingface/hub/models--laion--larger_clap_music/snapshots/a0b4534a14f58e20944452dff00a22a06ce629d1');a=p.parse_args()
torch.set_num_threads(4);torch.manual_seed(20261014);np.random.seed(20261014)
model_path=str(a.model_path)
model=ClapModel.from_pretrained(model_path,local_files_only=True).eval()
processor=ClapProcessor.from_pretrained(model_path,local_files_only=True)
items=[v for v in json.loads((a.clips/'private-clips.json').read_text()) if v['row']>=0]
if a.limit:items=items[:a.limit]
out=a.root/'clap';out.mkdir(exist_ok=True)
t=time.monotonic()
with torch.inference_mode():
 for i,v in enumerate(items):
  dst=out/(v['clip_id']+'.npy')
  if dst.exists():continue
  path=a.clips/'upload'/'clips'/(v['clip_id']+'.wav')
  assert hashlib.sha256(path.read_bytes()).hexdigest()==v['sha256']
  x,sr=sf.read(path,dtype='float32');assert sr==16000 and x.ndim==1
  off=max(0,(len(x)-160000)//2);x=x[off:off+160000]
  x=resample_poly(x,3,1).astype('float32')
  inputs=processor(audios=x,sampling_rate=48000,return_tensors='pt')
  y=model.get_audio_features(**inputs).cpu().numpy()[0]
  assert np.isfinite(y).all() and np.linalg.norm(y)>0
  y=y/np.linalg.norm(y);np.save(dst,y)
  if i%10==0:print(json.dumps({'done':i+1,'total':len(items),'seconds':time.monotonic()-t}),flush=True)
X=np.stack([np.load(out/(v['clip_id']+'.npy')) for v in items]);C=X@X.T
upper=C[np.triu_indices(len(X),1)]
print(json.dumps({'done':len(items),'seconds':time.monotonic()-t,'cos_mean':float(upper.mean()),'cos_min':float(upper.min()),'cos_max':float(upper.max())}),flush=True)
