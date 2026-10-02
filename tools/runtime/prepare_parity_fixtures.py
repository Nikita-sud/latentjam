#!/usr/bin/env python3
"""Create deterministic JNI parity fixtures without changing app assets."""
import argparse
from pathlib import Path
import hashlib, json, shutil, subprocess, zipfile
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--stock-aar', type=Path, required=True)
parser.add_argument('--listener-vectors', type=Path)
parser.add_argument('--audio', type=Path, action='append', default=[])
args = parser.parse_args()
ROOT = args.output.resolve()
REPO = Path(__file__).resolve().parents[2]
FIX = ROOT / 'device/fixtures'
FIX.mkdir(parents=True, exist_ok=True)
MODELS = ROOT / 'device/models'
MODELS.mkdir(exist_ok=True)
for p in (REPO / 'androidApp/src/main/assets/ml').glob('*.onnx'):
    shutil.copy2(p, MODELS / p.name)
rng = np.random.default_rng(20261001)
manifest = []
cases = []

def unit(x):
    return (x / np.maximum(np.linalg.norm(x, axis=-1, keepdims=True), 1e-12)).astype(np.float32)

def case(name, model, inputs, source):
    manifest.append(f'{name}\t{model}.onnx\t{len(inputs)}')
    records = []
    for key, value in inputs.items():
        value = np.asarray(value)
        value = value.astype('<i8' if value.dtype.kind == 'i' else '<f4')
        file = f'{name}.{key}.bin'
        value.tofile(FIX / file)
        manifest.append(f'{key}\t{"int64" if value.dtype.kind == "i" else "float"}\t' + ','.join(map(str,value.shape)) + '\t' + file)
        records.append({'name':key,'shape':list(value.shape),'dtype':str(value.dtype),'sha256':hashlib.sha256(value.tobytes()).hexdigest()})
    cases.append({'id':name,'model':model,'source':source,'inputs':records})

library = args.listener_vectors
if library:
    audio = np.fromfile(library/'audio.f32', dtype='<f4').reshape(-1,960)
    text = np.fromfile(library/'text.f32', dtype='<f4').reshape(-1,384)
    vector_source = 'Provided library embeddings'
else:
    audio = unit(rng.standard_normal((128,960)))
    text = unit(rng.standard_normal((128,384)))
    vector_source = 'Synthetic normalized embeddings'
for i, n in enumerate([1,4,16]):
    case(f'semantic{i}', 'universal_semantic_head', {'embedding': audio[rng.choice(len(audio),n,False)]}, vector_source)
for i, seq in enumerate([3,12,32,48]):
    ids = rng.integers(100,2000,(1,seq), dtype=np.int64);ids[0,0]=101;ids[0,-1]=102
    mask = np.ones_like(ids)
    if i>1: mask[0,-seq//4:]=0;ids[0,-seq//4:]=0
    case(f'text{i}', 'text_encoder', {'input_ids':ids,'attention_mask':mask,'token_type_ids':np.zeros_like(ids)}, 'Synthetic valid vocabulary IDs, variable length and padding')
for i in range(4):
    rows = rng.choice(len(audio),4,False)
    small=np.concatenate([audio[rows],np.ones((4,1),np.float32)],axis=1)[None]
    if i==0:small[:]=0
    if i==1:small[0,:2]=0
    case(f'state{i}', 'predictor_state', {'history_small':small,'history_medium':unit(audio[rows].mean(axis=0)[None]),'history_large':unit(audio[rng.choice(len(audio),20)].mean(axis=0)[None]),'time_features':rng.random((1,5),dtype=np.float32),'session_features':rng.random((1,5),dtype=np.float32)}, vector_source + ', cold/partial/full context')
    rows=rng.choice(len(audio),101,False)
    packed=np.concatenate([audio[rows],text[rows]],axis=1)
    if i==0:packed[:,960:]=0
    candidates=packed[1:][None].copy()
    if i==1:candidates[:,30:]=0
    case(f'scorer{i}', 'predictor_scorer_n100', {'state':packed[:1],'candidates':candidates}, vector_source + ', text dropout and padded pool')
for i in range(3):
    t=np.arange(320000,dtype=np.float32)/32000
    wave=(.2*np.sin(2*np.pi*(110+220*i)*t)+.05*rng.standard_normal(len(t))).astype(np.float32)
    case(f'audio_synthetic{i}', 'mnv4_audio', {'waveform':wave[None]}, 'Deterministic sine plus noise, 10 seconds at 32kHz')
for i,path in enumerate(args.audio):
    name=path.name
    if not path.is_file(): raise FileNotFoundError(path)
    result=subprocess.run(['ffmpeg','-v','error','-ss','20','-i',str(path),'-t','10','-f','f32le','-ac','1','-ar','32000','-'],capture_output=True,check=True)
    wave=np.frombuffer(result.stdout,dtype='<f4')[:320000]
    assert len(wave)==320000
    case(f'audio_real{i}', 'mnv4_audio', {'waveform':wave[None]}, name)
(FIX/'manifest.tsv').write_text('\n'.join(manifest)+'\n')
(ROOT/'probe-cases.json').write_text(json.dumps({'seed':20261001,'cases':cases},indent=2)+'\n')
aar=args.stock_aar
with zipfile.ZipFile(aar) as z:
    (ROOT/'classes.jar').write_bytes(z.read('classes.jar'))
    dest=ROOT/'device/stock';dest.mkdir(exist_ok=True)
    for n in z.namelist():
        if n.startswith('jni/arm64-v8a/') and n.endswith('.so'):
            (dest/Path(n).name).write_bytes(z.read(n))
print(len(cases),'cases prepared')
