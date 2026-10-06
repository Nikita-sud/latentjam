"""Quantization-aware fine-tuning of the music encoder's pointwise convolutions and final projection.

Every student starts as an exact copy of the encoder (backbone_fixed.onnx via onnx2torch) and is trained to
reproduce the frozen float encoder's embeddings while those weights round straight-through
(w + stop_grad(Q(w) - w)) to a 4-bit grid; every other parameter stays trainable so the network adapts
around it. Grids:

    sq4b32    symmetric 4-bit, blocks of 32 inputs with a bf16 scale (latentjam.Q4Conv1x1)
    sq4c      symmetric 4-bit, one float scale per output channel (as fast as INT8 on a phone; blocks are not)
    lq4c, lq4b32  the same grids with a clipping range per channel or block learned in training (LQ)
    A+B       mixed: A for the 44 pointwise convolutions over time and frequency, B for the two layers that run
              once per window (conv_head 960 -> 1280 after pooling, and the projection), e.g. lq4c+sq4b32
    hsq4c, hsq4b32  the same on Walsh-Hadamard-rotated groups of 32 inputs (worse after training)
    cqBgG     Cactus CQ: rotated groups of G, Lloyd-Max codebook of B bits, fp16 group norms
    pNN_cfg   pruned first: every inverted-residual block keeps NN % of its expanded channels and conv_head
              NN % of its hidden units (the largest mean activation on 64 training windows times the norm
              of the weights that read them, a multiple of 16; the choice goes to prune_<cfg>.json, which
              the export reads), then cfg's grids

Validation cosine to the teacher after 12 epochs: lq4c+sq4b32 0.9989, sq4b32 0.9989, sq4c+sq4b32 0.9986,
lq4c 0.9987, sq4c 0.9980. Pruned, after 20 epochs: p50_lq4c+sq4b32 0.9892, p38_ 0.9833, p25_ 0.9707; after 40,
p50_ 0.9903 (shipped) and p38_ 0.9852.

    python qat_audio.py train 12 lq4c+sq4b32     # on a GPU, after fetch_fma.py
    python qat_audio.py export lq4c+sq4b32 qat_lq4c+sq4b32_best.pt   # out/mnv4_qat_lq4c+sq4b32.onnx, for
                                                                      # make_q4_encoder.py

AQ=1 (both commands) also rounds the other convolutions to INT8 and every activation to uint8 as the shipped
graph does (ActQ, I8): 0.9985 with all of that simulated, against about 0.997 for the shipped graph of a model
trained without it. The exporter now carries the learned ranges in ONNX metadata and make_q4_encoder.py preserves them.
Older conversion discarded those ranges and fell to 0.956. Checkpoint reload also restores ActQ.seen.
Use verify_audio_export.py to compare the restored fake-quantized model with the final operator graph;
preserving scales alone does not prove model quality or bit-exact inference.

MIX="dasheng_06b:26" adds a second, frozen teacher (Dasheng, Apache-2.0: block 26, time-averaged, 16 kHz). By default
its centred similarity structure is mixed into the relational target (weight MIXW, 0.5); MIXMODE=regress instead
makes the students reproduce a new 960-d space: both teachers' centred unit vectors joined (weights 1 - MIXW and
MIXW), reduced by PCA over NFIT training windows and rotated as close to the encoder's space as an orthogonal map
gets (saved as mix_basis<TAG>.npz; MIX_BASIS reuses one). COSW and RELW weigh the cosine and the relational terms;
INIT starts each student from a checkpoint (PRUNE_JSON: the channel choice it was pruned with); TAG names the run's
files; WAVE and LENS take comma-separated lists (FMA and MPD previews, read as one set). Measured 2026-10-05
(tools/research/model_diet/teachers): the joined space beats the encoder by 9 % on MPD playlists, the full-size
student trained on FMA + MPD by 2 %; the pruned student stayed further from the joined space (top-10 overlap 0.81
against 0.84 on FMA alone).

It runs from the research folder that holds the float encoder (backbone_fixed.onnx, mnv4_4step_fp32.onnx),
the front end (common.py, frontend_params.npz) and the training audio: fma_wave.npy / fma_lens.npy, 30 s of
each of the 11,599 Free Music Archive tracks under CC BY, CC BY-SA, CC0 or public domain that fetch_fma.py
pulls (commercial_ids.txt). Loss: cosine to the teacher embedding plus batch similarity-structure matching.
Twelve epochs on one A40 take about 17 minutes for four students, about 40 for three with AQ.
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
        pad = (-inn) % max(32, self.block)        # whole blocks, and whole rotation groups of 32
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


class LQ(nn.Module):
    """SQ's grids with a learned clipping range per output channel (or block): s = a * max|w| / 7 with a in
    [0.3, 1], started at the a with the least squared error and trained with the network (the step size gets
    LSQ's gradient, round(w / s) - w / s inside the range and +-7 at its ends; clipped weights get none).
    Because a <= 1 the largest weight still lands on +-7, so max|w| / 7 reads the step back off the exported
    weights and the operator's formats do not change."""

    def __init__(self, bits, block, w, transposed=False):
        super().__init__()
        self.bits, self.block, self.transposed = bits, block, transposed
        with torch.no_grad():
            groups = self.groups(w.detach().float())
            best = torch.ones(groups.shape[:-1])
            err = torch.full(groups.shape[:-1], float("inf"))
            for a in torch.linspace(0.3, 1.0, 36):
                e = ((self.dequantize(groups, torch.full_like(best, float(a))) - groups) ** 2).sum(-1)
                better = e < err
                best, err = torch.where(better, a, best), torch.where(better, e, err)
        self.a = nn.Parameter(best)

    def groups(self, w):
        W = w.T if self.transposed else w.reshape(w.shape[0], -1)   # [out, in]
        if not self.block:
            return W[:, None, :]                                     # one group per channel
        pad = (-W.shape[1]) % self.block
        return (F.pad(W, (0, pad)) if pad else W).reshape(W.shape[0], -1, self.block)

    def dequantize(self, g, a):
        top = (1 << (self.bits - 1)) - 1
        s = (a.clamp(0.3, 1.0)[..., None] * g.abs().amax(-1, keepdim=True).detach() / top).clamp_min(1e-12)
        if self.block:  # stored bf16: the step rounds too, straight-through
            s = s + (s.to(torch.bfloat16).float() - s).detach()
        t = torch.clamp(g / s, -top, top)
        return (t + (torch.round(t) - t).detach()) * s

    def forward(self, w):
        g = self.groups(w.float())
        deq = self.dequantize(g, self.a).reshape(g.shape[0], -1)
        inn = w.shape[0] if self.transposed else w[0].numel()
        deq = deq[:, :inn]
        return (deq.T if self.transposed else deq.reshape(w.shape)).to(w.dtype)


class I8(nn.Module):
    """The other convolutions' weights as make_q4_encoder leaves them: INT8 per output channel with ONNX
    Runtime's symmetric scale (max|w| / 127.5), straight-through."""

    def forward(self, w):
        W = w.reshape(w.shape[0], -1).float()
        s = (W.abs().amax(1, keepdim=True) / 127.5).clamp_min(1e-12)
        deq = (torch.clamp(torch.round(W / s), -128, 127) * s).reshape(w.shape).to(w.dtype)
        return w + (deq - w).detach()


class ActQ(nn.Module):
    """A uint8 activation as ONNX Runtime's QLinear operators hold it: a scale and a zero point over a range
    that includes 0, here a moving average of each training batch's 0.005th and 99.995th percentiles (0 below
    for ReLU outputs); straight-through inside the range, nothing passes outside it. Evaluation keeps the range."""

    def __init__(self, nonneg):
        super().__init__()
        self.nonneg, self.seen = nonneg, False
        self.register_buffer("lo", torch.zeros(()))
        self.register_buffer("hi", torch.ones(()))

    def get_extra_state(self):
        return {"seen": self.seen}

    def set_extra_state(self, state):
        self.seen = bool(state["seen"])

    def _load_from_state_dict(self, state_dict, prefix, *args, **kwargs):
        # Older checkpoints saved valid lo/hi ranges but omitted the observer's initialized flag.
        key = prefix + "_extra_state"
        if key not in state_dict:
            state_dict[key] = {"seen": prefix + "lo" in state_dict and prefix + "hi" in state_dict}
        super()._load_from_state_dict(state_dict, prefix, *args, **kwargs)

    @torch.no_grad()
    def observe(self, x):
        flat = x.detach().float().flatten()
        if flat.numel() > 1 << 18:
            flat = flat[torch.randint(flat.numel(), (1 << 18,), device=flat.device)]
        q = torch.quantile(flat, torch.tensor([5e-5, 1 - 5e-5], device=flat.device))
        lo = torch.zeros_like(q[0]) if self.nonneg else q[0].clamp(max=0.0)
        hi = q[1].clamp(min=1e-6)
        if self.seen:
            self.lo.lerp_(lo, 0.1)
            self.hi.lerp_(hi, 0.1)
        else:
            self.lo.copy_(lo)
            self.hi.copy_(hi)
            self.seen = True

    def forward(self, x):
        if self.training:
            self.observe(x)
        if not self.seen:
            return x
        xf = x.float()
        scale = ((self.hi - self.lo) / 255).clamp_min(1e-8)
        zero = torch.round(-self.lo / scale).clamp(0, 255)
        xc = torch.clamp(xf, -zero * scale, (255 - zero) * scale)
        return (xc + (torch.round(xc / scale) * scale - xc).detach()).to(x.dtype)


def quantize_activations(st):
    """ActQ on every tensor the shipped graph holds in uint8: the backbone's input, each convolution's output
    (after its ReLU, which ONNX Runtime fuses), each residual sum, the pooled vector and the projection."""
    from onnx2torch.onnx_graph import OnnxGraph
    source = OnnxGraph(onnx.load(BACKBONE).graph)
    full = onnx.load(FULL)
    first_conv = next(n for n in full.graph.node if n.name == "/encoder/backbone/conv_stem/Conv")
    g = st.graph
    marks = []
    names = {}
    for node in list(g.nodes):
        if node.op == "placeholder":
            marks.append((node, False))
            names[node.name] = [first_conv.input[0]]
        if node.op != "call_module":
            continue
        mod = st.get_submodule(node.target)
        original = source.nodes.get(node.target)
        if original is not None:
            names.setdefault(node.name, list(original.output_values))
        if isinstance(mod, nn.Conv2d):
            users = list(node.users)
            relu = len(users) == 1 and users[0].op == "call_module" and isinstance(st.get_submodule(users[0].target), nn.ReLU)
            marks.append((users[0], True) if relu else (node, False))
            if relu:
                # ORT may fuse Relu into Conv. Both names must retain the trained post-ReLU grid.
                names[users[0].name] = list(source.nodes[users[0].target].output_values) + list(original.output_values)
        elif node.name.endswith("_add") or type(mod).__name__ in ("OnnxGlobalAveragePoolWithKnownInputShape", "OnnxMatMul"):
            marks.append((node, False))
    for i, (node, nonneg) in enumerate(marks):
        name = f"actq_{i}"
        observer = ActQ(nonneg)
        observer.tensor_names = names[node.name]
        st.add_submodule(name, observer)
        with g.inserting_after(node):
            q = g.call_module(name, (node,))
        node.replace_all_uses_with(q, delete_user_cb=lambda user, q=q: user is not q)
    g.lint()
    st.recompile()
    return st


def quantizer(cfg, w, transposed=False):
    """cq4g32 -> CQ; sq4c / sq4b32 / hsq4c / hsq4b32 -> SQ; lq4c / lq4b32 -> LQ (learned clipping)."""
    if cfg.startswith("cq"):
        bits, group = parse(cfg)
        return CQ(bits, group, transposed=transposed)
    if cfg.startswith("lq"):
        return LQ(int(cfg[2]), 0 if cfg[3:] == "c" else int(cfg[4:]), w, transposed=transposed)
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


def split_prune(cfg):
    """"p50_lq4c+sq4b32" -> (0.5, "lq4c+sq4b32"): keep half of every expansion and of the head's hidden layer."""
    if cfg.startswith("p") and "_" in cfg:
        keep, rest = cfg[1:].split("_", 1)
        return int(keep) / 100, rest
    return None, cfg


def expansions(st):
    """Every inverted-residual block's (expansion, middle depthwise or None, projection) convolution names."""
    convs = {n for n, m in st.named_modules() if isinstance(m, nn.Conv2d)}
    out = []
    for n in sorted(convs):
        if n.endswith("/pw_exp/conv/Conv"):
            prefix = n[:-len("pw_exp/conv/Conv")]
            mid = prefix + "dw_mid/conv/Conv"
            out.append((n, mid if mid in convs else None, prefix + "pw_proj/conv/Conv"))
    return out


HEAD_CONV = "encoder/backbone/conv_head/Conv"


@torch.no_grad()
def prune_indices(st, proj_name, keep, mel):
    """Which expanded channels (and hidden head units) to keep: the largest mean ReLU activation on the
    calibration log-mels times the L2 norm of the projection weights that read the channel; a multiple of 16."""
    mods = dict(st.named_modules())
    sums, hooks = {}, []

    def watch(name):
        def hook(_, __, out):
            sums[name] = sums.get(name, 0) + F.relu(out.float()).mean((0, 2, 3)).cpu()
        hooks.append(mods[name].register_forward_hook(hook))

    blocks = expansions(st)
    for exp, mid, _ in blocks:
        watch(mid or exp)
    watch(HEAD_CONV)
    for b in range(0, len(mel), 8):
        st(mel[b:b + 8])
    for h in hooks:
        h.remove()
    keep_n = lambda n: max(16, int(round(n * keep / 16)) * 16)
    out = {}
    for exp, mid, proj in blocks:
        score = sums[mid or exp] * mods[proj].weight.detach().float().cpu()[:, :, 0, 0].norm(dim=0)
        out[exp] = sorted(score.topk(keep_n(len(score))).indices.tolist())
    head = sums[HEAD_CONV] * getattr(st.initializers, proj_name).detach().float().cpu().norm(dim=1)
    out[HEAD_CONV] = sorted(head.topk(keep_n(len(head))).indices.tolist())
    return out


def shrink(conv, rows=None, cols=None):
    """A copy of `conv` with output channels `rows` and (for a dense convolution) input channels `cols`."""
    w = conv.weight.detach()
    b = None if conv.bias is None else conv.bias.detach()
    if rows is not None:
        w, b = w[rows], (None if b is None else b[rows])
    depthwise = conv.groups > 1
    if cols is not None and not depthwise:
        w = w[:, cols]
    out_ch = w.shape[0]
    new = nn.Conv2d(out_ch if depthwise else w.shape[1], out_ch, conv.kernel_size, conv.stride, conv.padding,
                    conv.dilation, groups=out_ch if depthwise else 1, bias=b is not None, padding_mode=conv.padding_mode)
    new.weight.data.copy_(w)
    if b is not None:
        new.bias.data.copy_(b)
    return new


def prune(st, proj_name, indices):
    """Applies prune_indices' choice in place: expansions, their depthwise convolutions and projections shrink."""
    mods = dict(st.named_modules())
    for exp, mid, proj in expansions(st):
        idx = torch.tensor(indices[exp])
        setattr(st, exp, shrink(mods[exp], rows=idx))
        if mid:
            setattr(st, mid, shrink(mods[mid], rows=idx))
        setattr(st, proj, shrink(mods[proj], cols=idx))
    idx = torch.tensor(indices[HEAD_CONV])
    setattr(st, HEAD_CONV, shrink(mods[HEAD_CONV], rows=idx))
    w = getattr(st.initializers, proj_name).detach()
    delattr(st.initializers, proj_name)
    st.initializers.register_parameter(proj_name, nn.Parameter(w[idx].clone()))
    return st


def make_student(base, proj_name, cfg, indices=None):
    keep, cfg = split_prune(cfg)
    spatial, head = cfg.split("+") if "+" in cfg else (cfg, cfg)
    st = copy.deepcopy(base)
    if keep is not None:
        prune(st, proj_name, indices)
    for mod in st.modules():
        if isinstance(mod, nn.Conv2d) and mod.kernel_size == (1, 1):
            once = mod is getattr(st, HEAD_CONV)                        # conv_head, after global pooling
            P.register_parametrization(mod, "weight", quantizer(head if once else spatial, mod.weight))
    # MatMul x @ W with W [in=1280, out=960]: groups run along the input axis, i.e. W's rows.
    proj = getattr(st.initializers, proj_name)
    P.register_parametrization(st.initializers, proj_name, quantizer(head, proj, transposed=True))
    if os.environ.get("AQ") == "1":  # also the shipped graph's INT8: the other weights and every activation
        for mod in st.modules():
            if isinstance(mod, nn.Conv2d) and mod.kernel_size != (1, 1):
                P.register_parametrization(mod, "weight", I8())
        quantize_activations(st)
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
    indices = json.load(open(f"prune_{cfg}.json")) if split_prune(cfg)[0] is not None else None
    st = make_student(base, proj_name, cfg, indices)
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
    if indices is not None:  # pruned: depthwise convolutions take their new channel count, shapes are re-inferred
        shapes = {t.name: list(t.dims) for t in model.graph.initializer}
        for node in model.graph.node:
            if node.op_type == "Conv":
                for a in node.attribute:
                    if a.name == "group" and a.i > 1:
                        a.i = shapes[node.input[1]][0]
        del model.graph.value_info[:]
    os.makedirs(out_dir, exist_ok=True)
    path = f"{out_dir}/mnv4_qat_{cfg}.onnx"
    ranges = {}
    for observer in st.modules():
        if not isinstance(observer, ActQ):
            continue
        if not observer.seen:
            raise ValueError("cannot export uninitialized activation quantization")
        scale = float(((observer.hi - observer.lo) / 255).clamp_min(1e-8))
        zero = int(torch.round(-observer.lo / scale).clamp(0, 255))
        for name in observer.tensor_names:
            ranges[name] = {"scale": scale, "zero_point": zero}
    if ranges:
        entry = model.metadata_props.add()
        entry.key = "latentjam.activation_quantization"
        entry.value = json.dumps(ranges, sort_keys=True)
    onnx.save(model, path)
    print(f"{cfg}: wrote {path} ({len(values)} tensors replaced of {len(mapping)} mapped)")
    return path


class Waves:
    """Several wave memmaps (WAVE="fma_wave.npy,mpd_wave.npy", LENS likewise) read as one, rows in order."""

    def __init__(self, paths):
        self.parts = [np.load(p, mmap_mode="r") for p in paths]
        self.offsets = np.cumsum([0] + [len(p) for p in self.parts])

    def __len__(self):
        return int(self.offsets[-1])

    def __getitem__(self, key):
        i, window = key
        k = int(np.searchsorted(self.offsets, i, side="right") - 1)
        return self.parts[k][int(i - self.offsets[k]), window]


def mix_teacher(spec, dev):
    """MIX="dasheng_06b:26": Dasheng's block 26 (github.com/RicherMans/Dasheng, Apache-2.0), time-averaged, as a
    second teacher whose similarity structure is mixed into the relational target. 32 kHz windows in, unit vectors out."""
    import dasheng
    import torchaudio
    name, layer = spec.split(":")
    net = {"dasheng_06b": dasheng.dasheng_06B, "dasheng_base": dasheng.dasheng_base}[name]().to(dev).eval()
    for p in net.parameters():
        p.requires_grad_(False)
    captured = {}
    net.blocks[int(layer)].register_forward_hook(lambda module, inputs, output: captured.__setitem__("h", output))

    @torch.no_grad()
    def fn(w):
        x = torchaudio.functional.resample(w, SR, 16000)
        with torch.autocast(dev, dtype=torch.float16, enabled=dev == "cuda"):
            net(x)
        return F.normalize(captured["h"].float().mean(1), dim=-1)
    return fn


def train(epochs, cfgs):
    torch.manual_seed(0); np.random.seed(0)
    dev = os.environ.get("DEV", "cuda")
    BS = int(os.environ.get("BS", 64))
    LR = float(os.environ.get("LR", 2e-4))
    torch.backends.cudnn.benchmark = dev == "cuda"
    W = Waves(os.environ.get("WAVE", "fma_wave.npy").split(","))
    lens = np.concatenate([np.load(p) for p in os.environ.get("LENS", "fma_lens.npy").split(",")])
    usable = np.nonzero(lens >= 10 * SR)[0]
    perm = np.random.default_rng(0).permutation(usable)
    NVAL = int(os.environ.get("NVAL", 400))
    val_idx, tr_idx = np.sort(perm[:NVAL]), np.sort(perm[NVAL:])
    MAXSTEPS = int(os.environ.get("MAXSTEPS", 0))
    # MIX: a second teacher (see mix_teacher) sharing the relational target with weight MIXW; COSW and RELW weigh the
    # cosine to the encoder and the relational term; INIT starts each student from a checkpoint (PRUNE_JSON: the
    # channel choice it was pruned with); TAG names the run's files.
    mix = mix_teacher(os.environ["MIX"], dev) if os.environ.get("MIX") else None
    MIXW = float(os.environ.get("MIXW", 0.5)) if mix else 0.0
    COSW, RELW = float(os.environ.get("COSW", 1.0)), float(os.environ.get("RELW", 2.0))
    # MIXMODE=regress: the students reproduce a new 960-d space instead, the two teachers' centred vectors joined
    # (weights 1 - MIXW and MIXW) and reduced to 960 dimensions by PCA, fitted on NFIT training windows at the start
    # (means and basis saved to mix_basis<TAG>.npz). Selection then goes by the cosine to that target.
    REGRESS = mix is not None and os.environ.get("MIXMODE") == "regress"
    TAG = os.environ.get("TAG", "")
    fe = Frontend(np.load("frontend_params.npz")).to(dev).eval()
    base, proj_name = load_backbone()
    teacher = copy.deepcopy(base).to(dev).eval()
    for p in teacher.parameters():
        p.requires_grad_(False)
    steps_per_epoch = len(tr_idx) // BS
    total = MAXSTEPS or epochs * steps_per_epoch
    calib = None
    students = {}
    for cfg in cfgs:
        indices = None
        keep = split_prune(cfg)[0]
        if keep is not None and os.environ.get("PRUNE_JSON"):
            indices = json.load(open(os.environ["PRUNE_JSON"]))
            json.dump(indices, open(f"prune_{cfg}.json", "w"))
        elif keep is not None:  # choose the channels on 64 training windows, keep the choice for the export
            if calib is None:
                r = np.random.default_rng(2)
                rows = r.choice(tr_idx, min(64, len(tr_idx)), replace=False)
                crops = np.stack([W[i, (o := int(r.integers(0, int(lens[i]) - WIN + 1))):o + WIN] for i in rows])
                with torch.no_grad():
                    calib = fe(torch.from_numpy(crops).to(dev).float() / 32767.0)
            indices = prune_indices(teacher, proj_name, keep, calib)
            json.dump(indices, open(f"prune_{cfg}.json", "w"))
        st = make_student(base, proj_name, cfg, indices)
        if os.environ.get("INIT"):
            st.load_state_dict(torch.load(os.environ["INIT"], map_location="cpu"))
        st = st.to(dev).train()
        opt = torch.optim.AdamW(st.parameters(), lr=LR, weight_decay=0.0)
        sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=total, pct_start=0.05)
        students[cfg] = dict(model=st, opt=opt, sched=sched, best=-1.0, hist=[],
                             lq=[m for m in st.modules() if isinstance(m, LQ)])

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
    if mix:  # the neighbours the run aims for: both teachers' centred similarities, mixed
        VD = []
        for b in range(0, len(VW), 16):
            d = mix(VW[b:b + 16].reshape(-1, WIN).to(dev).float() / 32767.0)
            VD.append(F.normalize(d.reshape(-1, 3, d.shape[-1]).sum(1), dim=-1))
        VD = torch.cat(VD)
        VDc = F.normalize(VD - VD.mean(0), dim=-1)
        VM_top = ((1 - MIXW) * VTc @ VTc.T + MIXW * VDc @ VDc.T).fill_diagonal_(-9).topk(K).indices.cpu()

    if REGRESS and os.environ.get("MIX_BASIS"):  # the target space of an earlier run, to continue its students
        saved = np.load(os.environ["MIX_BASIS"])
        mu_old, mu_new, basis = (torch.from_numpy(saved[k]).to(dev) for k in ("mu_old", "mu_new", "basis"))
        np.savez(f"mix_basis{TAG}.npz", **{k: saved[k] for k in ("mu_old", "mu_new", "basis")})
    elif REGRESS:
        r = np.random.default_rng(3)
        fit = r.choice(tr_idx, int(os.environ.get("NFIT", 12000)), replace=True)
        olds, news = [], []
        with torch.no_grad():
            for b in range(0, len(fit), 64):
                rows = fit[b:b + 64]
                crops = np.stack([W[i, (o := int(r.integers(0, int(lens[i]) - WIN + 1))):o + WIN] for i in rows])
                w = torch.from_numpy(crops).to(dev).float() / 32767.0
                with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                    olds.append(F.normalize(teacher(fe(w)).float(), dim=-1))
                news.append(mix(w))
        olds, news = torch.cat(olds), torch.cat(news)
        mu_old, mu_new = olds.mean(0), news.mean(0)

        def joined(a, b):
            return torch.cat([(1 - MIXW) ** 0.5 * F.normalize(a - mu_old, dim=-1),
                              MIXW ** 0.5 * F.normalize(b - mu_new, dim=-1)], -1)
        _, _, V = torch.linalg.svd(joined(olds, news), full_matrices=False)
        basis = V[:960]
        # Rotated as close to the encoder's own space as an orthogonal map gets (Procrustes): cosines are unchanged,
        # and a student that starts from the encoder starts near its target.
        U, _, Vh = torch.linalg.svd((F.normalize(joined(olds, news) @ basis.T, dim=-1)).T @ olds)
        basis = (U @ Vh).T @ basis
        np.savez(f"mix_basis{TAG}.npz", mu_old=mu_old.cpu().numpy(), mu_new=mu_new.cpu().numpy(), basis=basis.cpu().numpy())
    if REGRESS:
        def joined(a, b):
            return torch.cat([(1 - MIXW) ** 0.5 * F.normalize(a - mu_old, dim=-1),
                              MIXW ** 0.5 * F.normalize(b - mu_new, dim=-1)], -1)

        def target(t, d):  # per window: both teachers' unit vectors -> the 960-d unit target
            return F.normalize(joined(t, d) @ basis.T, dim=-1)
        VY = []
        with torch.no_grad():
            for b in range(0, len(VW), 16):
                w = VW[b:b + 16].reshape(-1, WIN).to(dev).float() / 32767.0
                with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                    t = F.normalize(teacher(fe(w)).float(), dim=-1)
                y = target(t, mix(w))
                VY.append(F.normalize(y.reshape(-1, 3, 960).sum(1), dim=-1))
        VT = torch.cat(VY)   # from here on "the teacher" of the cosine and of the top-10 overlap is the target space
        VTc = F.normalize(VT - VT.mean(0), dim=-1)
        VT_top = VM_top = (VTc @ VTc.T).fill_diagonal_(-9).topk(K).indices.cpu()
        print(f"regression target: {basis.shape[0]} of {basis.shape[1]} dimensions", flush=True)

    def evaluate(st):
        st.eval()
        S = embed_val(st)
        st.train()
        cos = (VT * S).sum(-1)
        Sc = F.normalize(S - S.mean(0), dim=-1)
        S_top = (Sc @ Sc.T).fill_diagonal_(-9).topk(K).indices.cpu()
        ov = np.mean([len(set(a.tolist()) & set(b.tolist())) / K for a, b in zip(VT_top, S_top)])
        if mix:  # selection then goes by the mixed teacher's neighbours, reported in place of the minimum cosine
            mixed = np.mean([len(set(a.tolist()) & set(b.tolist())) / K for a, b in zip(VM_top, S_top)])
            return float(cos.mean()), float(cos.quantile(0.05)), float(mixed), float(ov)
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
                if REGRESS:
                    t = target(t, mix(w))
                    tc = F.normalize(t - t.mean(0), dim=-1)
                    rel_t = tc @ tc.T
                elif mix:
                    d = mix(w)
                    dc = F.normalize(d - d.mean(0), dim=-1)
                    rel_t = (1 - MIXW) * rel_t + MIXW * (dc @ dc.T)
            for s in students.values():
                with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                    y = s["model"](mel)
                y = F.normalize(y.float(), dim=-1)
                l_cos = (1 - (y * t).sum(-1)).mean()
                yc = F.normalize(y - y.mean(0), dim=-1)
                l_rel = F.mse_loss(yc @ yc.T, rel_t)
                loss = COSW * l_cos + RELW * l_rel
                s["opt"].zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(s["model"].parameters(), 3.0)
                s["opt"].step(); s["sched"].step()
                for m in s["lq"]:
                    m.a.data.clamp_(0.3, 1.0)   # projected: a clamp in the forward pass would stall a at 1
                s["last"] = (float(loss), float(l_cos), float(l_rel))
            step += 1
            if step == 20:
                if dev == "cuda":
                    torch.cuda.synchronize()
                print(f"20 steps in {time.time() - t0:.1f}s", flush=True)
        for cfg, s in students.items():
            mean, p5, mn, ov = evaluate(s["model"])
            s["hist"].append(dict(epoch=ep, loss=s["last"][0], l_cos=s["last"][1], l_rel=s["last"][2],
                                  val_cos_mean=mean, val_cos_p5=p5, **{"val_mix_top10" if mix else "val_cos_min": mn},
                                  val_top10_overlap=ov, t=time.time() - t0))
            print(cfg, json.dumps(s["hist"][-1]), flush=True)
            score = mn if mix and not REGRESS else mean  # MIX: the mixed top-10 overlap; regress: the cosine
            if score > s["best"]:
                s["best"] = score
                torch.save(s["model"].state_dict(), f"qat_{cfg}{TAG}_best.pt")
        if MAXSTEPS and step >= MAXSTEPS:
            break
    for cfg, s in students.items():
        json.dump(s["hist"], open(f"qat_hist_{cfg}{TAG}.json", "w"))
        print(cfg, "best", "mixed top-10 overlap" if mix else "val cos mean", s["best"], flush=True)


if __name__ == "__main__":
    if sys.argv[1] == "train":
        train(int(sys.argv[2]), sys.argv[3].split(","))
    else:
        export(sys.argv[2], sys.argv[3])
