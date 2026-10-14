#!/usr/bin/env python3
import argparse, ast, hashlib, json, platform, subprocess, sys
from pathlib import Path
import numpy as np
from threadpoolctl import threadpool_limits, threadpool_info
from sklearn.metrics import adjusted_rand_score
from directional import fit, unit, step, mode_labels, density

def read(p): return json.loads(p.read_text())
def write(p, v): p.write_text(json.dumps(v, ensure_ascii=False, indent=2)+'\n')
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def reference_function(path, name):
    # Execute only the inspected function definition, with NumPy as its sole
    # dependency. Explicit bandwidth avoids the authors' Bessel estimator.
    tree=ast.parse(path.read_text()); f=next(x for x in tree.body if isinstance(x,ast.FunctionDef) and x.name==name)
    scope={'np':np};exec(compile(ast.Module(body=[f],type_ignores=[]),str(path),'exec'),scope)
    return scope[name]

def verify(root, x):
    rng=np.random.default_rng(20261014)
    fixture=unit(rng.normal(size=(32,8)))
    reference=reference_function(root/'source/DirMS_fun.py','MS_DirKDE')
    ref=reference(fixture,fixture,h=1/np.sqrt(8),eps=1e-9,max_iter=1001)
    lab,y,info=fit(fixture,8)
    error=float(np.abs(ref[:,:,-1]-y).max());assert error < 1e-10
    unique=reference_function(root/'source/Utility_fun.py','Unique_Modes')
    assert np.array_equal(unique(y)[1],lab)
    # Analytically separated caps must recover three basins, independently of
    # the reference implementation and regardless of input row permutation.
    centers=np.eye(8)[:3]; caps=unit(np.repeat(centers,20,axis=0)+rng.normal(scale=.025,size=(60,8)))
    l,yy,ii=fit(caps,20);assert len(set(l))==3 and adjusted_rand_score(np.repeat(np.arange(3),20),l)==1
    perm=rng.permutation(len(caps));ll,_,_=fit(caps[perm],20)
    assert adjusted_rand_score(l[perm],ll)==1
    q=x[np.linspace(0,len(x)-1,13,dtype=int)]; before=density(q,x,12);after=density(step(q,x,12),x,12)
    assert np.min(after-before) >= -1e-10
    result={'upstream_final_max_abs_error':error,'upstream_labels_equal':True,'three_caps_recovered':True,'permutation_fixture_ari':1.,'minimum_log_density_gain':float((after-before).min())}
    write(root/'verification-initial.json',result)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);ap.add_argument('--snapshot',type=Path,required=True);ap.add_argument('--baseline',type=Path,required=True);a=ap.parse_args()
    root=a.root; src=a.snapshot/'regions';out=root/'geometry';out.mkdir(exist_ok=True)
    protocol=read(root/'protocol.json');threadpool_limits(limits=4)
    rows=read(src/'private-cooked-metadata.json');n=len(rows);assert n==protocol['population']
    x=np.fromfile(src/'audio25-meta75-centered.f32',dtype='<f4').reshape(n,1344)
    assert np.isfinite(x).all() and np.max(np.abs(np.linalg.norm(x,axis=1)-1))<1e-6
    x=unit(x.astype('float64'));verify(root,x)
    cp=(src/'classpath.txt').read_text().strip();java=Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(out),str(root/'src/DmsRegionProbe.java')],check=True)
    cases=[{'name':'baseline','dim':1344,'input':str(src/'audio25-meta75-input.f32')}]
    runs={}
    for k in protocol['kappa_grid']:
        name=f'dms{k}';lab,y,info=fit(x,k,eps=protocol['epsilon_cosine_step'],max_iter=protocol['max_iterations'])
        assert info['converged'], info
        np.save(out/f'private-{name}-modes.npy',y);write(out/f'private-{name}-labels.json',lab.tolist())
        counts=np.bincount(lab);info['mode_sizes']=sorted(counts.tolist(),reverse=True)
        info['merge_sensitivity']={str(t):{'modes':len(set(mode_labels(y,t)[0])),'ari_to_default':float(adjusted_rand_score(lab,mode_labels(y,t)[0]))} for t in protocol['merge_sensitivity']}
        runs[name]=info
        cases.append({'name':name,'dim':1344,'input':str(src/'audio25-meta75-input.f32'),'assignments':str(out/f'private-{name}-labels.json')})
        cases.append({**cases[-1],'name':name+'-untrimmed','without_confidence':True})
        write(root/'runs.json',runs)
        print(json.dumps({'name':name,**{key:info[key] for key in ['modes','iterations','seconds','converged']},'largest':int(counts.max())}),flush=True)
    plan={'ids':[r['id'] for r in rows],'cases':cases};write(out/'private-input-plan.json',plan)
    subprocess.run([str(java/'java'),'-Xmx2g','-cp',str(out)+':'+cp,'DmsRegionProbe',str(a.snapshot),str(out),str(out/'private-input-plan.json')],check=True)
    old=read(a.baseline)['worlds']
    new=read(out/'private-regions-baseline.json')['worlds']
    assert old==new,'Exact current baseline must match names, order, members and evidence'
    write(root/'provenance.json',{'protocol_sha256':sha(root/'protocol.json'),'source_hashes':{p.name:sha(p) for p in sorted((root/'src').glob('*')) if p.is_file()},'input_hashes':{p.name:sha(p) for p in [src/'audio25-meta75-input.f32',src/'audio25-meta75-centered.f32',src/'private-cooked-metadata.json',src/'private-semantics.json']},'reference':read(root/'source/commit.json'),'reference_hashes':{p.name:sha(p) for p in (root/'source').glob('*.py')},'python':sys.version,'platform':platform.platform(),'numpy':np.__version__,'threadpools':threadpool_info(),'exact_baseline_replay':True})

if __name__=='__main__':main()
