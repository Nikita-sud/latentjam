"""Track vectors from candidate teacher models, embedded the way the app embeds a track: ffmpeg decodes to the
model's own sample rate (mono), three 10 s windows at 20, 50 and 80 % of the track (one zero-padded window when it
is shorter), each window's hidden states averaged over time per layer and L2-normalised, the windows summed and
each layer L2-normalised again. Output out/<teacher>/<library>.npz: ids, vectors [n, layers, dim] (float16).

    python embed.py musicfm_msd,mert330 r1k_a,r3k [--remote /workspace] [--device cuda] [--batch 16] [--workers 12]

Without --remote the manifests' local paths are read: the owner's library is embedded on the Mac and never leaves it.
The bench folder (TEACHER_BENCH) holds manifests/<library>.json ({id, path, remote} per track), out/ and vendor/,
the code the teachers need: musicfm/ (github.com/minzwon/musicfm, MIT/Apache-2.0), EfficientAT/
(MIT) and weights/ (EfficientAT's mn10_as_mAP_471.pt and dymn20_as_mAP_493.pt).
"""
import argparse
import contextlib
import json
import multiprocessing as mp
import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import torch

HERE = Path(os.environ.get("TEACHER_BENCH", "~/Documents/LJ/teacher-bench-2026-10-05")).expanduser()  # manifests, out
VENDOR = HERE / "vendor"
WIN_S = 10.0


def decode(path, sr):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-threads", "1", "-i", path, "-f", "f32le", "-ac", "1", "-ar",
                          str(sr), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def windows(y, sr):
    win = int(WIN_S * sr)
    if len(y) <= win:
        w = np.zeros(win, np.float32)
        w[:len(y)] = y
        return [w]
    span = len(y) - win
    return [y[int(span * f):int(span * f) + win] for f in (0.2, 0.5, 0.8)]


def _job(item):
    i, path, sr = item
    try:
        return i, np.stack(windows(decode(path, sr), sr))
    except Exception as failure:  # an undecodable file stays missing, it never becomes zeros
        print(f"  skipped {path}: {failure}", flush=True)
        return i, None


def mean_layers(hidden):
    """[B, T, D] per layer -> [B, L, D]: each layer's time mean."""
    return torch.stack([h.float().mean(1) for h in hidden], 1)


def load(name, dev):
    """(sample rate, fn: waveforms [B, N] float32 -> [B, layers, dim])."""
    if name.startswith("musicfm_"):
        variant = name.split("_", 1)[1]  # msd | fma
        sys.path.insert(0, str(VENDOR))
        from huggingface_hub import hf_hub_download
        from musicfm.model.musicfm_25hz import MusicFM25Hz
        stats = hf_hub_download("minzwon/MusicFM", f"{variant}_stats.json")
        weights = hf_hub_download("minzwon/MusicFM", f"pretrained_{variant}.pt")
        m = MusicFM25Hz(is_flash=False, stat_path=stats, model_path=None)
        state = torch.load(weights, map_location="cpu", weights_only=False)["state_dict"]
        m.load_state_dict({k[6:]: v for k, v in state.items()}, strict=True)
        m = m.to(dev).eval()
        return 24000, lambda x: mean_layers(m.get_predictions(x)[1])
    if name == "mert330":
        from transformers import AutoModel, Wav2Vec2FeatureExtractor
        m = AutoModel.from_pretrained("m-a-p/MERT-v1-330M", trust_remote_code=True).to(dev).eval()
        normalize = Wav2Vec2FeatureExtractor.from_pretrained("m-a-p/MERT-v1-330M", trust_remote_code=True).do_normalize

        def fn(x):
            if normalize:  # as Wav2Vec2FeatureExtractor does
                x = (x - x.mean(1, keepdim=True)) / torch.sqrt(x.var(1, keepdim=True, unbiased=False) + 1e-7)
            return mean_layers(m(x, output_hidden_states=True).hidden_states)
        return 24000, fn
    if name == "muq":
        from muq import MuQ
        m = MuQ.from_pretrained("OpenMuQ/MuQ-large-msd-iter").to(dev).eval()
        return 24000, lambda x: mean_layers(m(x, output_hidden_states=True).hidden_states)
    if name in ("clap_music", "clap_music_tower"):  # the joint audio-text space, or the audio tower's own vector
        from transformers import ClapModel, ClapProcessor
        m = ClapModel.from_pretrained("laion/larger_clap_music").to(dev).eval()
        proc = ClapProcessor.from_pretrained("laion/larger_clap_music")

        def fn(x):
            inputs = proc(audios=list(x.float().cpu().numpy()), sampling_rate=48000, return_tensors="pt")
            inputs = {k: v.to(dev) for k, v in inputs.items()}
            if name == "clap_music":
                return m.get_audio_features(**inputs).float()[:, None]
            return m.audio_model(**inputs).pooler_output.float()[:, None]
        return 48000, fn
    if name.startswith("dasheng"):
        import dasheng
        m = {"dasheng_base": dasheng.dasheng_base, "dasheng_06b": dasheng.dasheng_06B,
             "dasheng_12b": dasheng.dasheng_12B}[name]().to(dev).eval()
        captured = []
        for block in m.blocks:
            block.register_forward_hook(lambda module, inputs, output: captured.append(output))

        def fn(x):
            captured.clear()
            m(x)
            return mean_layers(captured)
        return 16000, fn
    if name in ("mn10", "dymn20"):
        root = VENDOR / "EfficientAT"
        sys.path.insert(0, str(root))
        cwd = os.getcwd()
        os.chdir(root)
        with contextlib.redirect_stdout(open(os.devnull, "w")):
            from helpers.utils import NAME_TO_WIDTH
            from models.dymn.model import get_model as get_dymn
            from models.mn.model import get_model as get_mn
            from models.preprocess import AugmentMelSTFT
        os.chdir(cwd)
        mel = AugmentMelSTFT(n_mels=128, sr=32000, win_length=800, hopsize=320, n_fft=1024, freqm=0, timem=0, fmin=0.0,
                             fmax=None, fmin_aug_range=1, fmax_aug_range=1).to(dev).eval()
        if name == "mn10":
            net = get_mn(width_mult=NAME_TO_WIDTH("mn10_as"), pretrained_name=None, strides=[2, 2, 2, 2],
                         head_type="mlp")
            weights = VENDOR / "weights" / "mn10_as_mAP_471.pt"
        else:  # the AudioSet checkpoint's inference temperature, as train_audio_retrieval_student.py loads it
            net = get_dymn(width_mult=NAME_TO_WIDTH("dymn20_as"), pretrained_name=None, strides=[2, 2, 2, 2],
                           T_max=1.0, T_min=1.0)
            weights = VENDOR / "weights" / "dymn20_as_mAP_493.pt"
        net.load_state_dict(torch.load(weights, map_location="cpu", weights_only=True))
        net = net.to(dev).eval()

        def fn(x):
            spec = mel(x)
            if spec.ndim == 3:
                spec = spec.unsqueeze(1)
            return net(spec)[1].float()[:, None]
        return 32000, fn
    raise SystemExit(f"unknown teacher {name}")


def run(name, libraries, remote, dev, batch, workers, out_root):
    t0 = time.time()
    sr, fn = load(name, dev)
    # fp16 only where it stays finite: MusicFM and MuQ overflow to NaN in it (fp32 with TF32 instead)
    half = dev == "cuda" and name in ("clap_music", "clap_music_tower", "mert330", "dasheng_base", "dasheng_06b",
                                      "dasheng_12b")
    torch.backends.cuda.matmul.allow_tf32 = torch.backends.cudnn.allow_tf32 = True
    print(f"{name}: loaded in {time.time() - t0:.0f}s, {sr} Hz, fp16 {half}", flush=True)
    for lib in libraries:
        path = out_root / name / f"{lib}.npz"
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        rows = json.loads((HERE / "manifests" / f"{lib}.json").read_text())
        items = [(i, os.path.join(remote, r["remote"]) if remote else r["path"], sr) for i, r in enumerate(rows)]
        acc = [None] * len(rows)
        buf, owners = [], []
        t1 = time.time()

        def flush():
            x = torch.from_numpy(np.stack(buf)).to(dev)
            ctx = torch.autocast("cuda", dtype=torch.float16) if half else contextlib.nullcontext()
            with torch.inference_mode(), ctx:
                y = fn(x)
            y = torch.nn.functional.normalize(y.float(), dim=-1).cpu().numpy()
            for j, owner in enumerate(owners):
                acc[owner] = y[j] if acc[owner] is None else acc[owner] + y[j]
            buf.clear()
            owners.clear()

        with mp.Pool(workers) as pool:
            for i, w in pool.imap_unordered(_job, items, chunksize=4):
                if w is None:
                    continue
                for win in w:
                    buf.append(win)
                    owners.append(i)
                    if len(buf) == batch:
                        flush()
            if buf:
                flush()
        keep = [i for i, a in enumerate(acc) if a is not None]
        vectors = np.stack([acc[i] for i in keep])
        vectors /= np.linalg.norm(vectors, axis=-1, keepdims=True) + 1e-12
        np.savez(path, ids=np.array([rows[i]["id"] for i in keep]), vectors=vectors.astype(np.float16))
        print(f"  {lib}: {len(keep)}/{len(rows)} tracks, layers {vectors.shape[1]}, dim {vectors.shape[2]}, "
              f"{time.time() - t1:.0f}s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("teachers")
    ap.add_argument("libraries")
    ap.add_argument("--remote", help="root of the manifests' remote paths (on a GPU pod)")
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--out", default=str(HERE / "out"))
    args = ap.parse_args()
    for name in args.teachers.split(","):
        run(name, args.libraries.split(","), args.remote, args.device, args.batch, args.workers, Path(args.out))


if __name__ == "__main__":
    main()
