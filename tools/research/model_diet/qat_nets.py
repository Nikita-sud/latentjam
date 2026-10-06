"""Quantization-aware distillation of the SMART scorer, the state net and the semantic head into CQ weights.

    python qat_nets.py scorer|state|head BITS [--epochs 8] [--lr 1e-4] [--out runs/<net>-<bits>]
        [--student-features runs/features/<key>]

BITS names the bits per matrix, e.g. "w1=3,w2=4" (scorer: w1 w2 w3; state: tok med lg f0 f4 gru_w
gru_r; head: w1 w2); matrices not named stay float (fp16 in the shipped file). Group size 32.

Each student starts as an exact copy of its float teacher (phase1/teachers, or the shipped head) and
learns to reproduce the teacher's outputs on recorded app inputs (record.py on the training libraries,
plus the state net's original distillation inputs when present), the weights rounding straight-through
to CQ. Held-out samples measure fidelity each epoch; the best epoch is exported as a dequantized
stand-in ONNX plus the size its CQ blob would take (sizes.json).

--student-features moves the nets to another music encoder's vectors (a bundle's features of the same
training libraries, from the bundle report): the student hears the inputs in that space while the teacher
keeps hearing them in 0.7.1's, so the nets learn to read the new encoder as the old ones read the old one.
Candidates and the four latest tracks move row for row; the longer-window means, whose tracks the
recordings do not keep, move through the least-squares linear map between the two spaces.
Changing encoder space requires an explicit state target map. identity keeps the targets in 0.7.1's
coordinates, as the shipped p50 nets were trained (their encoder sits at cosine 0.988 to 0.7.1's); the dual
option uses transpose(new-to-old) to approximate retrieval dot products; the forward option maps track
vectors. dual and forward are diagnostic options, not verified upgrades; measure downstream retrieval and
continuity.
The scorer requires --student-state, the exported new-space state model. The head requires --head-pairs
from prepare_head_pairs.py and selects checkpoints by balanced logit error across both output branches.
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
        self.heard = torch.as_tensor(data["student_matrix"]).to(dev) if "student_matrix" in data else self.matrix
        self.heard_state = self.state
        if "student_matrix" in data:
            self.heard_state = self.state.clone()
            if data.get("student_state"):
                # Use the new state network, not old-coordinate state vectors, when teaching the scorer.
                assert len(data["e_in"]) == len(self.state)
                if not np.allclose(data["e_out"], data["s_state"][:, :960], atol=3e-4):
                    raise ValueError("recorded encodes and scorer states are not aligned")
                state_net = nets.StateNet(data["student_state"]).to(dev).eval()
                inputs = moved_states(data, torch.as_tensor(data["e_in"]))
                with torch.no_grad():
                    for start in range(0, len(inputs), 1024):
                        end = start + 1024
                        out = state_net(*nets.StateNet.split(inputs[start:end].to(dev).float()))
                        self.heard_state[start:end, :960] = out.cpu().half()
                del state_net
            else:
                raise ValueError("--student-state is required when moving the scorer to a new space")
        self.n = len(self.rows)

    def student(self):
        return nets.Scorer(TEACH / "predictor_scorer_fp32.onnx").to(dev)

    def batch(self, idx):
        rows = self.rows[idx].to(dev)
        live = rows >= 0
        c = self.matrix[rows.clamp_min(0)] * live.unsqueeze(-1)
        heard = self.heard[rows.clamp_min(0)] * live.unsqueeze(-1)
        return self.state[idx].to(dev).float(), self.heard_state[idx].to(dev).float(), c, heard, live

    def loss(self, model, b):
        s, hs, c, heard, live = b
        with torch.no_grad():
            t = self.teacher(s, c)
        o = model(hs, heard)
        mse = ((o - t) ** 2)[live].mean()
        neg = torch.full_like(t, -1e4)
        kl = F.kl_div(F.log_softmax(torch.where(live, o, neg), -1), F.softmax(torch.where(live, t, neg), -1),
                      reduction="batchmean")
        return mse + kl

    @torch.no_grad()
    def evaluate(self, model, idx):
        tops, sps = [], []
        for i in range(0, len(idx), 512):
            s, hs, c, heard, live = self.batch(idx[i:i + 512])
            neg = torch.full((len(s), c.shape[1]), -1e9, device=dev)
            t = torch.where(live, self.teacher(s, c), neg)
            o = torch.where(live, model(hs, heard), neg)
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
        self.heard = self.e
        self.space_map = None
        if "student_matrix" in data:  # the original distillation inputs have no student version
            self.e = self.e[:len(data["e_in"])]
            self.heard = moved_states(data, self.e)
            self.space_map = state_space_map(data)
        self.n = len(self.e)

    @torch.no_grad()
    def target(self, original):
        out = self.teacher(*original)
        return F.normalize(out @ self.space_map, dim=-1) if self.space_map is not None else out

    def student(self):
        return nets.StateNet(TEACH / "predictor_state_fp32.onnx").to(dev)

    def batch(self, idx):
        return (nets.StateNet.split(self.e[idx].to(dev).float()),
                nets.StateNet.split(self.heard[idx].to(dev).float()))

    def loss(self, model, b):
        b, heard = b
        with torch.no_grad():
            t = self.target(b)
        return (1 - (model(*heard) * t).sum(-1)).mean() * 100

    @torch.no_grad()
    def evaluate(self, model, idx):
        cos = []
        for i in range(0, len(idx), 2048):
            b, heard = self.batch(idx[i:i + 2048])
            cos.append((model(*heard) * self.target(b)).sum(-1).cpu())
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
        if extra.exists() and "student_matrix" not in data:
            parts.append(np.load(extra))
        self.x = torch.as_tensor(np.concatenate(parts).astype(np.float32))
        heard = data["student_matrix"][:, :960] if "student_matrix" in data else np.concatenate(parts)
        self.heard = torch.as_tensor(heard.astype(np.float32))
        self.explicit_val = None
        if data.get("head_pairs"):
            with np.load(data["head_pairs"], allow_pickle=False) as extra:
                tx, sx = extra["teacher"], extra["student"]
                if tx.shape != sx.shape or tx.ndim != 2 or tx.shape[1] != 960:
                    raise ValueError("head pairs must have matching [N, 960] vectors")
                split = extra["validation"].astype(bool)
                if split.shape != (len(tx),):
                    raise ValueError("head validation mask must have one entry per pair")
                base_val = np.zeros(len(self.x), bool)
                base_val[np.random.default_rng(0).permutation(len(base_val))[:len(base_val)//10]] = True
                self.explicit_val = np.concatenate([base_val, split])
                self.x = torch.cat([self.x, torch.from_numpy(tx.astype(np.float32))])
                self.heard = torch.cat([self.heard, torch.from_numpy(sx.astype(np.float32))])
        elif "student_matrix" in data:
            raise ValueError("--head-pairs is required for a new encoder; do not silently drop FMA examples")
        self.n = len(self.x)
        if self.explicit_val is None:
            self.explicit_val = np.zeros(self.n, bool)
            held_out = np.random.default_rng(0).permutation(self.n)[:min(5000, self.n // 10)]
            self.explicit_val[held_out] = True
        # Select on both branches and every class; Music-only argmax agreement is degenerate.
        fit_rows = np.flatnonzero(~self.explicit_val) if self.explicit_val is not None else np.arange(self.n)
        with torch.no_grad():
            a, f = self.teacher(self.x[fit_rows].to(dev))
            self.audio_scale = a.std(0).clamp_min(0.5)
            self.fma_scale = f.std(0).clamp_min(0.5)

    def student(self):
        return nets.Head(SHIPPED / "universal_semantic_head.onnx").to(dev)

    def batch(self, idx):
        return self.x[idx].to(dev), self.heard[idx].to(dev)

    def loss(self, model, b):
        x, heard = b
        with torch.no_grad():
            ta, tf = self.teacher(x)
        oa, of = model(heard)
        return self.logit_error(oa, of, ta, tf)

    def logit_error(self, oa, of, ta, tf):
        return ((oa - ta) / self.audio_scale).square().mean() + ((of - tf) / self.fma_scale).square().mean()

    @torch.no_grad()
    def evaluate(self, model, idx):
        x, heard = self.batch(idx)
        ta, tf = self.teacher(x)
        oa, of = model(heard)
        return {"quality": -float(self.logit_error(oa, of, ta, tf)), "audio_logit_rmse": float(F.mse_loss(oa, ta).sqrt()), "fma_logit_rmse": float(F.mse_loss(of, tf).sqrt()),
                "audio_top": float((ta.argmax(1) == oa.argmax(1)).float().mean()),
                "fma_top": float((tf.argmax(1) == of.argmax(1)).float().mean())}


TASKS = {"scorer": ScorerTask, "state": StateTask, "head": HeadTask}


def state_space_map(data):
    """The map the state targets go through in a changed audio space (--state-target-map)."""
    mapping = data.get("state_target_map")
    if mapping == "forward":
        return fit_space_map(data)
    if mapping == "dual":
        return fit_query_map(data)
    return None  # identity: the targets stay in 0.7.1's coordinates


@torch.no_grad()
def fit_space_map(data):
    if "space_map" not in data:
        t = torch.as_tensor(data["matrix"][:, :960]).double()
        s = torch.as_tensor(data["student_matrix"][:, :960]).double()
        # Fitted only on training-library tracks; never on evaluation libraries.
        data["space_map"] = torch.linalg.lstsq(t, s).solution.float().to(dev)
    return data["space_map"]


@torch.no_grad()
def fit_query_map(data):
    """If new @ B approximates old, query @ B.T preserves query/track dot products.

    Using the track map here is valid only for orthogonal changes of coordinates.
    This is still an approximation when the new encoder adds nonlinear information.
    """
    if "query_map" not in data:
        old = torch.as_tensor(data["matrix"][:, :960]).double()
        new = torch.as_tensor(data["student_matrix"][:, :960]).double()
        data["query_map"] = torch.linalg.lstsq(new, old).solution.T.float().to(dev)
    return data["query_map"]


@torch.no_grad()
def moved_states(data, e):
    """The state net's recorded inputs in the student's space. Each of the four latest tracks is found in
    its library's 0.7.1 vectors (the recorder wrote them as float16) and swapped for the student's vector of
    that track; the medium and large means go through the least-squares map from 0.7.1's space to the
    student's, fitted on every training track (a mean of mapped vectors is the map of the mean)."""
    teacher = torch.as_tensor(data["matrix"][:, :960], device=dev)
    student = torch.as_tensor(data["student_matrix"][:, :960], device=dev)
    linear = fit_space_map(data)
    unit = F.normalize(teacher, dim=-1)
    offsets, libs = data["offsets"], torch.as_tensor(data["lib"].astype(np.int64))
    out = torch.empty_like(e)
    worst, moved = 1.0, 0
    for i in range(0, len(e), 4096):
        row = e[i:i + 4096].to(dev).float()
        small = row[:, :3844].reshape(-1, 4, 961).clone()
        audio = small[..., :960]
        lib = libs[i:i + 4096].to(dev)
        for b in lib.unique().tolist():
            lo, hi = int(offsets[b]), int(offsets[b + 1])
            pick = (lib == b).nonzero().squeeze(1)
            tokens = audio[pick].reshape(-1, 960)
            live = tokens.norm(dim=-1) > 1e-6
            if not live.any():
                continue
            sim = F.normalize(tokens[live], dim=-1) @ unit[lo:hi].T
            best, where = sim.max(1)
            worst = min(worst, float(best.min()))
            moved += int(live.sum())
            swapped = tokens.clone()
            swapped[live] = student[lo:hi][where]
            audio[pick] = swapped.reshape(-1, 4, 960)
        small[..., :960] = audio
        row[:, :3844] = small.reshape(-1, 3844)
        row[:, 3844:4804] = row[:, 3844:4804] @ linear
        row[:, 4804:5764] = row[:, 4804:5764] @ linear
        out[i:i + 4096] = row.to(out.dtype).cpu()
    print(f"state inputs moved: {moved} tracks found (worst match cosine {worst:.4f}), means mapped", flush=True)
    return out


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
    ap.add_argument("--state-target-map", choices=["identity", "dual", "forward"],
                    help="where the state targets live in a changed audio space: identity keeps 0.7.1's coordinates "
                         "(the shipped p50 nets, for a space close to the old one); dual and forward are experimental "
                         "maps; every choice needs full downstream validation")
    ap.add_argument("--student-state", help="exported float state model for the new audio space")
    ap.add_argument("--head-pairs", help="NPZ of paired FMA teacher/student vectors, with validation mask")
    ap.add_argument("--init", help="initialize from an earlier exported float model")
    ap.add_argument("--max-samples", type=int, help="optional deterministic training subset")
    ap.add_argument("--require-disjoint-tracks", action="store_true",
                    help="reject overlap with evaluation catalogs or unavailable split evidence")
    ap.add_argument("--student-features", help="a bundle's features root: train the nets to read its music vectors")
    args = ap.parse_args()
    if args.net == "state" and args.student_features and not args.state_target_map:
        ap.error("--state-target-map is required for a changed audio space; neither experimental map is a proven upgrade")
    import data as D
    torch.manual_seed(0)
    if args.net == "head":  # audio vectors only: every training library's, no recordings needed
        names = sorted({f.name.split(".")[0] for f in D.FEATURES.glob("train_*")})
        data = {"matrix": np.concatenate([D.library_matrix(n) for n in names])}
        if args.student_features:
            for name in names:
                D.assert_paired_rows(name, args.student_features)
            data["student_matrix"] = np.concatenate([D.library_matrix(n, args.student_features) for n in names])
        data["head_pairs"] = args.head_pairs
        task = TASKS[args.net](data)
    else:
        data = D.load(args.data, student=args.student_features, max_samples=args.max_samples)
        data["student_state"] = args.student_state
        data["state_target_map"] = args.state_target_map
        task = TASKS[args.net](data)
    split_audit = D.split_audit(names if args.net == "head" else data["libraries"])
    overlap = any(split_audit["evaluation_catalog_overlap"].values())
    if args.require_disjoint_tracks and (overlap or split_audit["missing_evaluation_libraries"]):
        ap.error("training catalogs are not verified disjoint from evaluation tracks")
    if overlap:
        print("split audit: evaluation catalogs share training tracks; do not claim track-disjoint evaluation", flush=True)
    bits = parse_bits(args.bits)
    out = Path(args.out or PHASE / "runs" / f"{args.net}-{args.bits.replace('=', '').replace(',', '_')}")
    out.mkdir(parents=True, exist_ok=True)
    (out / "split-audit.json").write_text(json.dumps(split_audit, indent=2))
    perm = np.random.default_rng(0).permutation(task.n)
    val, train = perm[:min(5000, task.n // 10)], perm[min(5000, task.n // 10):]
    if args.net == "head" and task.explicit_val is not None:
        val, train = np.flatnonzero(task.explicit_val), np.flatnonzero(~task.explicit_val)
    model = task.student()
    if args.init:
        initial = {"head": nets.Head, "state": nets.StateNet, "scorer": nets.Scorer}[args.net](args.init)
        model.load_state_dict(initial.state_dict())
    quantize(model, bits, task.layouts)
    model.to(dev)  # the parametrizations' codebooks and Hadamard matrices join the weights' device
    before = task.evaluate(model.eval(), val)
    print(f"{args.net} {args.bits}: {task.n} samples on {dev}; post-training CQ: {before}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.0)
    steps = args.epochs * math.ceil(len(train) / args.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps, pct_start=0.05)
    key = {"scorer": "top10", "state": "cos", "head": "quality"}[args.net]
    best, history = (before[key], copy.deepcopy(model.state_dict())), [dict(epoch=0, **before)]
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
        if m[key] > best[0]:
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
