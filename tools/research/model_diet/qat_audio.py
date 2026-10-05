"""Quantization-aware fine-tuning of the music encoder's pointwise convolutions and final projection.

Every student starts as an exact copy of the encoder (backbone_fixed.onnx via onnx2torch) and is trained to
reproduce the frozen float encoder's embeddings while those weights round straight-through
(w + stop_grad(Q(w) - w)) to a 4-bit grid; every other parameter stays trainable so the network adapts
around it. Grids:

    sq4b32    symmetric 4-bit, blocks of 32 inputs with a bf16 scale (shipped: latentjam.Q4Conv1x1)
    sq4c      symmetric 4-bit, one float scale per output channel (fails the semantic-head gate)
    hsq4c, hsq4b32  the same on Walsh-Hadamard-rotated groups of 32 inputs (worse after training)
    cqBgG     Cactus CQ: rotated groups of G, Lloyd-Max codebook of B bits, fp16 group norms

    python qat_audio.py train 8 sq4b32          # on a GPU, after fetch_fma.py (val cos 0.9989 to the teacher)
    python qat_audio.py export sq4b32 qat_sq4b32_best.pt   # out/mnv4_qat_sq4b32.onnx, for make_q4_encoder.py

It runs from the research folder that holds the float encoder (backbone_fixed.onnx, mnv4_4step_fp32.onnx),
the front end (common.py, frontend_params.npz) and the training audio: fma_wave.npy / fma_lens.npy, 30 s of
each of the 11,599 Free Music Archive tracks under CC BY, CC BY-SA, CC0 or public domain that fetch_fma.py
pulls (commercial_ids.txt). Loss: cosine to the teacher embedding plus batch similarity-structure matching.
Eight epochs on one A40 take about 12 minutes for four students.
"""
import copy
import functools
import json
import os
import queue
import sys
import threading
import time

import numpy as np
import onnx
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.nn.utils.parametrize as P
from onnx import numpy_helper
from onnx2torch import convert

from common import SR, WIN, Frontend

BACKBONE = "backbone_fixed.onnx"
FULL = "mnv4_4step_fp32.onnx"


@functools.lru_cache(maxsize=None)
def lloyd_max(bits, iters=200, samples=400000, seed=0):
    levels = 1 << bits
    x = np.sort(np.random.RandomState(seed).randn(samples))
    c = x[((np.arange(levels) + 0.5) / levels * samples).astype(int)].astype(np.float64)
    for _ in range(iters):
        bnd = (c[:-1] + c[1:]) / 2.0
        idx = np.searchsorted(bnd, x)
        for k in range(levels):
            m = idx == k
            if m.any():
                c[k] = x[m].mean()
    return np.sort(c)


def hadamard(n):
    H = np.array([[1.0]])
    while H.shape[0] < n:
        H = np.block([[H, H], [H, -H]])
    return torch.tensor(H / np.sqrt(n), dtype=torch.float32)


class CQ(nn.Module):
    """Parametrization: the weight as a CQ blob would reconstruct it, straight-through."""

    def __init__(self, bits, group, transposed=False):
        super().__init__()
        cb = torch.tensor(lloyd_max(bits) / np.sqrt(group), dtype=torch.float32)
        self.register_buffer("cb", cb)
        self.register_buffer("bounds", (cb[:-1] + cb[1:]) / 2)
        self.register_buffer("H", hadamard(group))
        self.group, self.transposed = group, transposed

    def roundtrip(self, w):
        W = w.T if self.transposed else w.reshape(w.shape[0], -1)   # [out, in]
        out, inn = W.shape
        pad = (-inn) % self.group
        Wp = F.pad(W, (0, pad)) if pad else W
        g = Wp.float().reshape(out, -1, self.group) @ self.H
        norm = g.norm(dim=-1, keepdim=True)
        unit = g / norm.clamp_min(1e-12)
        q = self.cb[torch.bucketize(unit, self.bounds)]
        deq = (q * norm.half().float()) @ self.H                      # H is its own inverse
        deq = deq.reshape(out, -1)[:, :inn]
        return (deq.T if self.transposed else deq.reshape(w.shape)).to(w.dtype)

    def forward(self, w):
        return w + (self.roundtrip(w) - w).detach()


class SQ(nn.Module):
    """Symmetric uniform weights as KleidiAI's qsi4cx / qsi4c32 kernels compute them, straight-through:
    q = clamp(round(w / s), -8, 7), w ~ q * s, with s = max|w| / 7 per output channel (block 0, f32) or per
    block of `block` inputs (stored bf16); optionally on Walsh-Hadamard-rotated groups of 32 inputs."""

    def __init__(self, bits, block, rotate, transposed=False):
        super().__init__()
        self.bits, self.block, self.rotate, self.transposed = bits, block, rotate, transposed
        self.register_buffer("H", hadamard(32))

    def roundtrip(self, w):
        W = w.T if self.transposed else w.reshape(w.shape[0], -1)   # [out, in]
        out, inn = W.shape
        pad = (-inn) % 32
        Wp = (F.pad(W, (0, pad)) if pad else W).float()
        if self.rotate:
            Wp = (Wp.reshape(out, -1, 32) @ self.H).reshape(out, -1)
        top = (1 << (self.bits - 1)) - 1
        if self.block:
            g = Wp.reshape(out, -1, self.block)
            s = (g.abs().amax(-1, keepdim=True) / top).clamp_min(1e-12).to(torch.bfloat16).float()
            deq = (torch.clamp(torch.round(g / s), -top - 1, top) * s).reshape(out, -1)
        else:
            s = (Wp.abs().amax(-1, keepdim=True) / top).clamp_min(1e-12)
            deq = torch.clamp(torch.round(Wp / s), -top - 1, top) * s
        if self.rotate:
            deq = (deq.reshape(out, -1, 32) @ self.H).reshape(out, -1)   # H is its own inverse
        deq = deq[:, :inn]
        return (deq.T if self.transposed else deq.reshape(w.shape)).to(w.dtype)

    def forward(self, w):
        return w + (self.roundtrip(w) - w).detach()


def quantizer(cfg, transposed=False):
    """cq4g32 -> CQ; sq4c / sq4b32 / hsq4c / hsq4b32 -> SQ."""
    if cfg.startswith("cq"):
        bits, group = parse(cfg)
        return CQ(bits, group, transposed=transposed)
    rotate = cfg.startswith("h")
    body = cfg[3:] if rotate else cfg[2:]          # "4c" or "4b32"
    bits = int(body[0])
    block = 0 if body[1:] == "c" else int(body[2:])
    return SQ(bits, block, rotate, transposed=transposed)


def load_backbone():
    net = convert(onnx.load(BACKBONE))
    # The final projection is an initializer buffer in the converted graph; make it trainable.
    name = next(n for n, b in net.initializers.named_buffers() if tuple(b.shape) == (1280, 960))
    w = getattr(net.initializers, name).detach().clone()
    delattr(net.initializers, name)
    net.initializers.register_parameter(name, nn.Parameter(w))
    return net, name


def make_student(base, proj_name, cfg):
    st = copy.deepcopy(base)
    for mod in st.modules():
        if isinstance(mod, nn.Conv2d) and mod.kernel_size == (1, 1):
            P.register_parametrization(mod, "weight", quantizer(cfg))
    # MatMul x @ W with W [in=1280, out=960]: groups run along the input axis, i.e. W's rows.
    P.register_parametrization(st.initializers, proj_name, quantizer(cfg, transposed=True))
    return st


def parse(cfg):  # "cq4g32" -> (4, 32)
    bits, group = cfg[2:].split("g")
    return int(bits), int(group)


def param_to_initializer(net, proj_name):
    """Map every trainable tensor of the converted backbone to its ONNX initializer by exact value."""
    inits = {t.name: numpy_helper.to_array(t) for t in onnx.load(BACKBONE).graph.initializer}
    by_bytes = {}
    for n, a in inits.items():
        by_bytes.setdefault(a.astype(np.float32).tobytes(), []).append(n)
    mapping = {}
    for pname, p in net.named_parameters():
        key = p.detach().cpu().numpy().astype(np.float32).tobytes()
        names = by_bytes.get(key)
        if names:
            mapping[pname] = names[0]
    return mapping


def export(cfg, state_path, out_dir="out"):
    base, proj_name = load_backbone()
    mapping = param_to_initializer(base, proj_name)
    st = make_student(base, proj_name, cfg)
    st.load_state_dict(torch.load(state_path, map_location="cpu"))
    st.eval()
    values = {}
    with torch.no_grad():
        for pname, init in mapping.items():
            # Reading through the module applies the parametrization: the CQ-reconstructed weight.
            obj = st
            parts = pname.split(".")
            for part in parts[:-1]:
                obj = getattr(obj, part)
            values[init] = getattr(obj, parts[-1]).detach().cpu().numpy().astype(np.float32)
    model = onnx.load(FULL)
    index = {t.name: i for i, t in enumerate(model.graph.initializer)}
    for init, a in values.items():
        model.graph.initializer[index[init]].CopyFrom(numpy_helper.from_array(a, init))
    os.makedirs(out_dir, exist_ok=True)
    path = f"{out_dir}/mnv4_qat_{cfg}.onnx"
    onnx.save(model, path)
    print(f"{cfg}: wrote {path} ({len(values)} tensors replaced of {len(mapping)} mapped)")
    return path


def train(epochs, cfgs):
    torch.manual_seed(0); np.random.seed(0)
    dev = os.environ.get("DEV", "cuda")
    BS = int(os.environ.get("BS", 64))
    LR = float(os.environ.get("LR", 2e-4))
    torch.backends.cudnn.benchmark = dev == "cuda"
    W = np.load(os.environ.get("WAVE", "fma_wave.npy"), mmap_mode="r")
    lens = np.load(os.environ.get("LENS", "fma_lens.npy"))
    usable = np.nonzero(lens >= 10 * SR)[0]
    perm = np.random.default_rng(0).permutation(usable)
    NVAL = int(os.environ.get("NVAL", 400))
    val_idx, tr_idx = np.sort(perm[:NVAL]), np.sort(perm[NVAL:])
    MAXSTEPS = int(os.environ.get("MAXSTEPS", 0))
    fe = Frontend(np.load("frontend_params.npz")).to(dev).eval()
    base, proj_name = load_backbone()
    teacher = copy.deepcopy(base).to(dev).eval()
    for p in teacher.parameters():
        p.requires_grad_(False)
    steps_per_epoch = len(tr_idx) // BS
    total = MAXSTEPS or epochs * steps_per_epoch
    students = {}
    for cfg in cfgs:
        st = make_student(base, proj_name, cfg).to(dev).train()
        opt = torch.optim.AdamW(st.parameters(), lr=LR, weight_decay=0.0)
        sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=total, pct_start=0.05)
        students[cfg] = dict(model=st, opt=opt, sched=sched, best=-1.0, hist=[])

    def producer(q):
        r = np.random.default_rng(1)
        for _ in range(epochs):
            order = r.permutation(tr_idx)
            for s in range(steps_per_epoch):
                idx = order[s * BS:(s + 1) * BS]
                out = np.empty((len(idx), WIN), np.int16)
                for k, i in enumerate(idx):
                    off = int(r.integers(0, int(lens[i]) - WIN + 1))
                    out[k] = W[i, off:off + WIN]
                b = torch.from_numpy(out)
                q.put(b.pin_memory() if dev == "cuda" else b)
        q.put(None)

    def augment(w):
        n = w.shape[0]
        w = w * torch.empty(n, 1, device=w.device).uniform_(0.5, 1.5)
        mix = torch.rand(n, device=w.device) < 0.35
        partner = w[torch.randperm(n, device=w.device)]
        lam = torch.empty(n, 1, device=w.device).uniform_(0.6, 0.9)
        return torch.where(mix[:, None], lam * w + (1 - lam) * partner, w)

    VW = torch.from_numpy(np.stack([np.stack([W[i, int((int(lens[i]) - WIN) * f):int((int(lens[i]) - WIN) * f) + WIN]
                                              for f in (0.2, 0.5, 0.8)]) for i in val_idx]))

    @torch.no_grad()
    def embed_val(fn):
        out = []
        for b in range(0, len(VW), 16):
            w = VW[b:b + 16].reshape(-1, WIN).to(dev).float() / 32767.0
            e = F.normalize(fn(fe(w)).float(), dim=-1).reshape(-1, 3, 960).sum(1)
            out.append(F.normalize(e, dim=-1))
        return torch.cat(out)

    VT = embed_val(teacher)
    VTc = F.normalize(VT - VT.mean(0), dim=-1)
    K = min(10, len(VT) - 1)
    VT_top = (VTc @ VTc.T).fill_diagonal_(-9).topk(K).indices.cpu()

    def evaluate(st):
        st.eval()
        S = embed_val(st)
        st.train()
        cos = (VT * S).sum(-1)
        Sc = F.normalize(S - S.mean(0), dim=-1)
        S_top = (Sc @ Sc.T).fill_diagonal_(-9).topk(K).indices.cpu()
        ov = np.mean([len(set(a.tolist()) & set(b.tolist())) / K for a, b in zip(VT_top, S_top)])
        return float(cos.mean()), float(cos.quantile(0.05)), float(cos.min()), float(ov)

    print(f"train tracks {len(tr_idx)}, val {len(val_idx)}, steps/epoch {steps_per_epoch}, total steps {total}", flush=True)
    for cfg, s in students.items():
        print(cfg, "before QAT (post-training CQ):", evaluate(s["model"]), flush=True)
    q = queue.Queue(maxsize=6)
    threading.Thread(target=producer, args=(q,), daemon=True).start()
    t0, step = time.time(), 0
    for ep in range(epochs):
        for _ in range(steps_per_epoch):
            if MAXSTEPS and step >= MAXSTEPS:
                break
            w = augment(q.get().to(dev, non_blocking=True).float() / 32767.0)
            with torch.no_grad():
                mel = fe(w)
                with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                    t = teacher(mel)
                t = F.normalize(t.float(), dim=-1)
                tc = F.normalize(t - t.mean(0), dim=-1)
                rel_t = tc @ tc.T
            for s in students.values():
                with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                    y = s["model"](mel)
                y = F.normalize(y.float(), dim=-1)
                l_cos = (1 - (y * t).sum(-1)).mean()
                yc = F.normalize(y - y.mean(0), dim=-1)
                l_rel = F.mse_loss(yc @ yc.T, rel_t)
                loss = l_cos + 2.0 * l_rel
                s["opt"].zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(s["model"].parameters(), 3.0)
                s["opt"].step(); s["sched"].step()
                s["last"] = (float(loss), float(l_cos), float(l_rel))
            step += 1
            if step == 20:
                if dev == "cuda":
                    torch.cuda.synchronize()
                print(f"20 steps in {time.time() - t0:.1f}s", flush=True)
        for cfg, s in students.items():
            mean, p5, mn, ov = evaluate(s["model"])
            s["hist"].append(dict(epoch=ep, loss=s["last"][0], l_cos=s["last"][1], l_rel=s["last"][2],
                                  val_cos_mean=mean, val_cos_p5=p5, val_cos_min=mn, val_top10_overlap=ov,
                                  t=time.time() - t0))
            print(cfg, json.dumps(s["hist"][-1]), flush=True)
            if mean > s["best"]:
                s["best"] = mean
                torch.save(s["model"].state_dict(), f"qat_{cfg}_best.pt")
        if MAXSTEPS and step >= MAXSTEPS:
            break
    for cfg, s in students.items():
        json.dump(s["hist"], open(f"qat_hist_{cfg}.json", "w"))
        print(cfg, "best val cos mean", s["best"], flush=True)


if __name__ == "__main__":
    if sys.argv[1] == "train":
        train(int(sys.argv[2]), sys.argv[3].split(","))
    else:
        export(sys.argv[2], sys.argv[3])
