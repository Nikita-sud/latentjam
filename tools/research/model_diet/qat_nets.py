"""Quantization-aware distillation of the SMART scorer, the state net and the semantic head into CQ weights.

    python qat_nets.py scorer|state|head BITS [--epochs 8] [--lr 1e-4] [--out runs/<net>-<bits>]

BITS names the bits per matrix, e.g. "w1=3,w2=4" (scorer: w1 w2 w3; state: tok med lg f0 f4 gru_w
gru_r; head: w1 w2); matrices not named stay float (fp16 in the shipped file). Group size 32.

Each student starts as an exact copy of its float teacher (phase1/teachers, or the shipped head) and
learns to reproduce the teacher's outputs on recorded app inputs (record.py on the training libraries,
plus the state net's original distillation inputs when present), the weights rounding straight-through
to CQ. Held-out samples measure fidelity each epoch; the best epoch is exported as a dequantized
stand-in ONNX plus the size its CQ blob would take (sizes.json).
"""
import argparse
import copy
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

import cq
import nets

HERE = Path(__file__).resolve().parent
from paths import PHASE, WORK

TEACH = PHASE / "teachers"
SHIPPED = WORK / "baseline" / "ml"
import os
dev = os.environ.get("DEVICE") or ("cuda" if torch.cuda.is_available() else "cpu")


def parse_bits(text):
    """"w1=4,w2=u4": CQ bits per matrix, or u<bits> for MatMulNBits-style uniform blocks."""
    return {k: v for k, v in (kv.split("=") for kv in text.split(",") if kv)}


def bits_of(spec):
    return (int(spec[1:]), True) if spec.startswith("u") else (int(spec), False)


# ---------------------------------------------------------------------------------------------- scorer

class ScorerTask:
    file, source = "predictor_scorer_n100.onnx", SHIPPED / "predictor_scorer_n100.onnx"
    layouts = {"w1": 0, "w2": 0, "w3": 0}       # [in, out]

    def __init__(self, data):
        self.teacher = nets.Scorer(TEACH / "predictor_scorer_fp32.onnx").to(dev).eval()
        self.state = torch.as_tensor(data["s_state"], dtype=torch.float16)
        self.rows = torch.as_tensor(data["s_rows"].astype(np.int64))
        self.matrix = torch.as_tensor(data["matrix"]).to(dev)
        self.n = len(self.rows)

    def student(self):
        return nets.Scorer(TEACH / "predictor_scorer_fp32.onnx").to(dev)

    def batch(self, idx):
        rows = self.rows[idx].to(dev)
        live = rows >= 0
        c = self.matrix[rows.clamp_min(0)] * live.unsqueeze(-1)
        return self.state[idx].to(dev).float(), c, live

    def loss(self, model, b):
        s, c, live = b
        with torch.no_grad():
            t = self.teacher(s, c)
        o = model(s, c)
        mse = ((o - t) ** 2)[live].mean()
        neg = torch.full_like(t, -1e4)
        kl = F.kl_div(F.log_softmax(torch.where(live, o, neg), -1), F.softmax(torch.where(live, t, neg), -1),
                      reduction="batchmean")
        return mse + kl

    @torch.no_grad()
    def evaluate(self, model, idx):
        tops, sps = [], []
        for i in range(0, len(idx), 512):
            s, c, live = self.batch(idx[i:i + 512])
            neg = torch.full((len(s), c.shape[1]), -1e9, device=dev)
            t = torch.where(live, self.teacher(s, c), neg)
            o = torch.where(live, model(s, c), neg)
            tt, ot = t.topk(10, 1).indices, o.topk(10, 1).indices
            tops += [len(set(a.tolist()) & set(b.tolist())) / 10 for a, b in zip(tt, ot)]
            sps.append((t.argmax(1) == o.argmax(1)).float().cpu())
        return {"top10": float(np.mean(tops)), "top1": float(torch.cat(sps).mean())}


# ---------------------------------------------------------------------------------------------- state

class StateTask:
    file, source = "predictor_state.onnx", TEACH / "predictor_state_fp32.onnx"
    layouts = {"tok": 0, "med": 0, "lg": 0, "t0": 0, "t2": 0, "s0": 0, "s2": 0, "f0": 0, "f4": 0,
               "gru_w": 1, "gru_r": 1}

    def __init__(self, data):
        self.teacher = nets.StateNet(TEACH / "predictor_state_fp32.onnx").to(dev).eval()
        parts = [torch.as_tensor(data["e_in"], dtype=torch.float16)]
        extra = PHASE / "data" / "state_extra.npy"   # the original distillation inputs, flattened like e_in
        if extra.exists():
            parts.append(torch.as_tensor(np.load(extra), dtype=torch.float16))
        self.e = torch.cat(parts)
        self.n = len(self.e)

    def student(self):
        return nets.StateNet(TEACH / "predictor_state_fp32.onnx").to(dev)

    def batch(self, idx):
        return nets.StateNet.split(self.e[idx].to(dev).float())

    def loss(self, model, b):
        with torch.no_grad():
            t = self.teacher(*b)
        return (1 - (model(*b) * t).sum(-1)).mean() * 100

    @torch.no_grad()
    def evaluate(self, model, idx):
        cos = []
        for i in range(0, len(idx), 2048):
            b = self.batch(idx[i:i + 2048])
            cos.append((model(*b) * self.teacher(*b)).sum(-1).cpu())
        c = torch.cat(cos)
        return {"cos": float(c.mean()), "cos_p1": float(c.quantile(0.01)), "cos_min": float(c.min())}


# ---------------------------------------------------------------------------------------------- head

class HeadTask:
    file, source = "universal_semantic_head.onnx", SHIPPED / "universal_semantic_head.onnx"
    layouts = {"w1": 0, "w2": 1}

    def __init__(self, data):
        self.teacher = nets.Head(SHIPPED / "universal_semantic_head.onnx").to(dev).eval()
        parts = [data["matrix"][:, :960]]
        extra = PHASE / "data" / "head_extra.npy"    # more audio vectors (FMA clips), when present
        if extra.exists():
            parts.append(np.load(extra))
        self.x = torch.as_tensor(np.concatenate(parts).astype(np.float32))
        self.n = len(self.x)

    def student(self):
        return nets.Head(SHIPPED / "universal_semantic_head.onnx").to(dev)

    def batch(self, idx):
        return self.x[idx].to(dev)

    def loss(self, model, x):
        with torch.no_grad():
            ta, tf = self.teacher(x)
        oa, of = model(x)
        return F.mse_loss(oa, ta) + F.mse_loss(of, tf)

    @torch.no_grad()
    def evaluate(self, model, idx):
        x = self.batch(idx)
        ta, tf = self.teacher(x)
        oa, of = model(x)
        return {"audio_logit_rmse": float(F.mse_loss(oa, ta).sqrt()), "fma_logit_rmse": float(F.mse_loss(of, tf).sqrt()),
                "audio_top": float((ta.argmax(1) == oa.argmax(1)).float().mean()),
                "fma_top": float((tf.argmax(1) == of.argmax(1)).float().mean())}


TASKS = {"scorer": ScorerTask, "state": StateTask, "head": HeadTask}


def quantize(model, bits, layouts, group=32):
    params = {}
    for name, spec in bits.items():
        b, uniform = bits_of(spec)
        owner, attr = (model.W, name) if hasattr(model, "W") and name in model.W else (model, name)
        cq.attach(owner, attr, b, group, layouts[name], uniform=uniform)
        params[name] = (owner, attr, b)
    return params


def blob_bytes(model, bits, layouts, group=32):
    """CQ codes and norms for the named matrices, fp16 for every other parameter."""
    total = 0.0
    for pname, p in model.named_parameters():
        quantized = [n for n in bits if pname.endswith(f"parametrizations.{n}.original")]
        if quantized:
            b, uniform = bits_of(bits[quantized[0]])
            total += (cq.uq_bytes if uniform else cq.cq_bytes)(tuple(p.shape), b, group, layouts[quantized[0]])
        else:
            total += p.numel() * 2
    return int(total)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("net", choices=TASKS)
    ap.add_argument("bits")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--data", default=str(PHASE / "data" / "smart"))
    ap.add_argument("--out")
    args = ap.parse_args()
    import data as D
    torch.manual_seed(0)
    if args.net == "head":  # audio vectors only: every training library's, no recordings needed
        names = sorted({f.name.split(".")[0] for f in D.FEATURES.glob("train_*")})
        task = TASKS[args.net]({"matrix": np.concatenate([D.library_matrix(n) for n in names])})
    else:
        task = TASKS[args.net](D.load(args.data))
    bits = parse_bits(args.bits)
    out = Path(args.out or PHASE / "runs" / f"{args.net}-{args.bits.replace('=', '').replace(',', '_')}")
    out.mkdir(parents=True, exist_ok=True)
    perm = np.random.default_rng(0).permutation(task.n)
    val, train = perm[:min(5000, task.n // 10)], perm[min(5000, task.n // 10):]
    model = task.student()
    quantize(model, bits, task.layouts)
    model.to(dev)  # the parametrizations' codebooks and Hadamard matrices join the weights' device
    before = task.evaluate(model.eval(), val)
    print(f"{args.net} {args.bits}: {task.n} samples on {dev}; post-training CQ: {before}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.0)
    steps = args.epochs * math.ceil(len(train) / args.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps, pct_start=0.05)
    key = {"scorer": "top10", "state": "cos", "head": "audio_top"}[args.net]
    best, history = None, [dict(epoch=0, **before)]
    for ep in range(args.epochs):
        model.train()
        t0 = time.time()
        order = np.random.default_rng(ep + 1).permutation(train)
        for i in range(0, len(order), args.batch):
            loss = task.loss(model, task.batch(order[i:i + args.batch]))
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
        m = task.evaluate(model.eval(), val)
        history.append(dict(epoch=ep + 1, **m))
        print(f"epoch {ep + 1}: {m} ({time.time() - t0:.0f}s)", flush=True)
        if best is None or m[key] > best[0]:
            best = (m[key], copy.deepcopy(model.state_dict()))
    model.load_state_dict(best[1])
    model.eval()
    import torch.nn.utils.parametrize as P
    size = blob_bytes(model, bits, task.layouts)
    for name in bits:
        owner = model.W if hasattr(model, "W") and name in model.W else model
        P.remove_parametrizations(owner, name, leave_parametrized=True)
    model.cpu().export(out / task.file, task.source)
    (out / "sizes.json").write_text(json.dumps({task.file: {"raw": size, "apk": size, "note": (
        f"{args.bits}: CQ bits (Hadamard + Lloyd-Max, fp16 norms), or u = MatMulNBits uniform blocks (fp16 scale, "
        f"packed zero point), group 32; other weights fp16; measured as the "
        f"dequantized stand-in written by tools/research/model_diet/qat_nets.py")}}, indent=1))
    (out / "history.json").write_text(json.dumps(history, indent=1))
    print(f"best {key} {best[0]:.5f}; CQ blob {size / 1e6:.2f} MB -> {out / task.file}", flush=True)


if __name__ == "__main__":
    main()
