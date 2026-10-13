#!/usr/bin/env python3
"""Re-encode the local MPD preview catalogue with the exact shipped model assets.

Audio follows the app's 3 x 10s window policy (FFmpeg host decode); metadata/tokens
come from the Kotlin app itself. Private cache rows and source hashes stay outside Git.
"""
import argparse
from concurrent.futures import ProcessPoolExecutor
import hashlib
import json
import multiprocessing as mp
from pathlib import Path
import subprocess
import time
import numpy as np
import pandas as pd
import mutagen
import onnxruntime as ort

SESSION = None

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def write_json(path, value):
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
    temp.replace(path)

def init_worker(model, library):
    global SESSION
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    options.register_custom_ops_library(library)
    SESSION = ort.InferenceSession(model, options, providers=['CPUExecutionProvider'])

def embed(row):
    try:
        decoded = subprocess.run(['ffmpeg','-nostdin','-v','error','-threads','1','-i',row['path'],'-map','0:a:0','-vn','-ac','1','-ar','32000','-f','f32le','-'],capture_output=True,check=True,timeout=45)
        wave = np.frombuffer(decoded.stdout,dtype='<f4')
        if not len(wave) or not np.isfinite(wave).all():
            raise ValueError('No finite decoded audio')
        duration = row['duration_ms'] or len(wave)*1000//32000
        starts = [int((duration-10000)*f) for f in (.2,.5,.8)] if duration > 10000 else [0]
        def infer(start):
            piece = wave[start*32:start*32+320000]
            if not len(piece): return None
            w = np.zeros((1,320000),dtype=np.float32); w[0,:len(piece)] = piece
            for gain in (1.,.5,.25):
                v = SESSION.run(['embedding'],{'waveform':w*np.float32(gain)})[0][0]
                if v.shape != (960,): raise ValueError('Wrong embedding shape')
                if np.isfinite(v).all() and np.isfinite(np.dot(v,v)) and np.dot(v,v)>0:
                    return v
            return None
        vectors = [v for s in starts if (v:=infer(s)) is not None]
        if not vectors and duration > 10000:
            v=infer(0)
            if v is not None: vectors.append(v)
        if not vectors: raise ValueError('No valid window embeddings')
        pooled = np.zeros(960,dtype=np.float32)
        for v in vectors: pooled += v
        squared = np.float32(0)
        for v in pooled: squared = np.float32(squared+np.float32(v*v))
        pooled /= np.float32(np.sqrt(squared))
        if not np.isfinite(pooled).all(): raise ValueError('Invalid pooled embedding')
        return row['index'],pooled,None
    except Exception as exc:
        return row['index'],None,f'{type(exc).__name__}: {exc}'

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--store',type=Path,required=True)
    ap.add_argument('--assets',type=Path,required=True)
    ap.add_argument('--lib',type=Path,required=True)
    ap.add_argument('--classpath-file',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--workers',type=int,default=8)
    ap.add_argument('--java-bin',type=Path,default=Path('/opt/homebrew/opt/openjdk@21/bin'))
    a=ap.parse_args(); out=a.output; out.mkdir(parents=True,exist_ok=True)
    started=time.perf_counter()
    frame=pd.read_parquet(a.store,columns=['track_id','path','artist','title'])
    assert frame.track_id.is_unique and frame.path.is_unique
    rows=[]
    for idx, r in enumerate(frame.to_dict('records')):
        f=Path(r['path']); media=mutagen.File(f,easy=True)
        def first(key):
            value=media.get(key) if media else None
            return str(value[0]).strip() if isinstance(value,list) and value else None
        def year(key):
            value=first(key)
            return int(value[:4]) if value and value[:4].isdigit() and int(value[:4])>0 else None
        rows.append(dict(index=idx,**r,sha256=sha(f),bytes=f.stat().st_size,
                         duration_ms=int(media.info.length*1000) if media else None,
                         genre=first('genre'),year=year('date'),original_year=year('originaldate'),language=first('language')))
    manifest={'protocol':'current-audio-3x10s-ms-gain-v1; current-Kotlin-text-and-tokenizer-v1',
              'model_versions':{name:(a.assets/name).read_text().strip() for name in ['embedding_version.txt','text_encoder_version.txt']},
              'asset_sha256':{name:sha(a.assets/name) for name in ['mnv4_audio.onnx','text_encoder.onnx','text_vocab.txt','music_entities_250k.bin','artist_knowledge.bin']},
              'custom_ops_sha256':sha(a.lib),'source_store_sha256':sha(a.store),'rows':rows}
    manifest_path=out/'private-manifest.json'
    if manifest_path.exists() and json.loads(manifest_path.read_text())!=manifest:
        raise ValueError('Cache provenance changed; use a new output directory')
    write_json(manifest_path,manifest); write_json(out/'private-rows.json',rows)
    cp=a.classpath_file.read_text().strip()
    subprocess.run([str(a.java_bin/'javac'),'-cp',cp,'-d',str(out),str(Path(__file__).with_name('MpdTextInputs.java'))],check=True)
    proc=subprocess.run([str(a.java_bin/'java'),'-cp',str(out)+':'+cp,'MpdTextInputs',str(a.assets),str(out/'private-rows.json'),str(out/'private-text-inputs.json')],check=True,capture_output=True,text=True)
    text_summary=json.loads(proc.stdout)
    text_rows=json.loads((out/'private-text-inputs.json').read_text())
    options=ort.SessionOptions(); options.intra_op_num_threads=1;options.inter_op_num_threads=1;options.log_severity_level=3
    ts=ort.InferenceSession(str(a.assets/'text_encoder.onnx'),options,providers=['CPUExecutionProvider'])
    cache={}; text=[]
    for r in text_rows:
        key=tuple(r['tokens'])
        if key not in cache:
            ids=np.array(key,dtype=np.int64)[None]
            h=ts.run(None,{'input_ids':ids,'attention_mask':np.ones_like(ids),'token_type_ids':np.zeros_like(ids)})[0][0]
            pooled=np.zeros(384,dtype=np.float32)
            for token in h: pooled+=token
            pooled*=np.float32(1/len(h)); pooled/=np.float32(np.linalg.norm(pooled.astype(float)))
            assert pooled.shape==(384,) and np.isfinite(pooled).all()
            cache[key]=pooled
        text.append(cache[key])
    text=np.stack(text); np.save(out/'metadata.npy',text)
    print(json.dumps({'metadata_done':len(text),'unique_inputs':len(cache),**text_summary}),flush=True)
    audio=np.zeros((len(rows),960),dtype=np.float32);done=np.zeros(len(rows),dtype=bool)
    checkpoint=out/'audio-checkpoint.npz'
    if checkpoint.exists():
        z=np.load(checkpoint);audio[:]=z['audio'];done[:]=z['done']
    todo=[r for r in rows if not done[r['index']]]
    def save():
        temp=out/'audio-checkpoint.tmp.npz';np.savez(temp,audio=audio,done=done);temp.replace(checkpoint)
    init_worker(str(a.assets/'mnv4_audio.onnx'),str(a.lib))
    failures=[];start=time.perf_counter()
    with ProcessPoolExecutor(a.workers,mp_context=mp.get_context('spawn'),initializer=init_worker,initargs=(str(a.assets/'mnv4_audio.onnx'),str(a.lib))) as pool:
        for count,(idx,vec,error) in enumerate(pool.map(embed,todo,chunksize=16),1):
            if error: failures.append({'index':idx,'error':error})
            else: audio[idx]=vec;done[idx]=True
            if count%500==0 or count==len(todo):
                save();print(json.dumps({'audio_done':int(done.sum()),'total':len(rows),'failures':len(failures),'seconds':round(time.perf_counter()-start,1)}),flush=True)
    write_json(out/'private-failures.json',failures)
    np.savez(out/'vectors.npz',audio=audio,metadata=text,valid=done,ids=frame.track_id.to_numpy(dtype=str))
    summary={'rows':len(rows),'audio_success':int(done.sum()),'audio_failures':len(failures),'text_success':len(text),'unique_text_inputs':len(cache),**text_summary,
             'genre_non_null':sum(bool(r['genre']) for r in rows),'year_non_null':sum(r['year'] is not None for r in rows),
             'duplicate_file_rows':len(rows)-len(set(r['sha256'] for r in rows)),
             'versions':manifest['model_versions'],'asset_sha256':manifest['asset_sha256'],'custom_ops_sha256':manifest['custom_ops_sha256'],
             'vectors_sha256':sha(out/'vectors.npz'),'manifest_sha256':sha(manifest_path),
             'wall_seconds':time.perf_counter()-started,'ort':ort.__version__,'workers':a.workers,
             'limitations':['Local MPD audio previews, generally 30 seconds; not full tracks.','Same shipped model assets and window policy; host FFmpeg decoding is not bitwise phone decoder parity.','Missing genres/years remain absent; artist facts use the shipped frozen pack.']}
    write_json(out/'refresh-summary.json',summary);print(json.dumps(summary),flush=True)
    if not done.all(): raise RuntimeError('Some audio files failed; inspect private failure log before training')

if __name__=='__main__':main()
