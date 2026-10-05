"""A smaller music encoder in the same 960-d space: an EfficientAT MobileNetV3 student (mn05 or mn10, MIT,
AudioSet weights) on the shipped log-mel, distilled from the float encoder, then trained to 4 bits for
latentjam.Q4Conv1x1 (a clipping range learned per channel for its pointwise convolutions, blocks of 32 for the
projection, which runs once per window; its squeeze-excitation layers and depthwise convolutions stay INT8).

The loss keeps three things of the teacher's: the embedding (cosine), the batch's similarity structure, and
what the shipped semantic head reads from it (universal_semantic_head.onnx of 0.7.1: its AudioSet scores and
its genre distribution), so the head keeps working on the student's vectors.

    python student_audio.py float 40 mn05,mn10   # distillation from AudioSet weights -> student_<name>_float.pt
    python student_audio.py qat 12 mn05,mn10     # 4 bits, from the float students   -> student_<name>_q4.pt

A name may carry its own head weight, "mn10:h0.3" (HEADW, default 1.0, otherwise), and ":relu", which
swaps every hard-swish for a ReLU so that ONNX Runtime fuses each activation into its INT8 convolution
instead of leaving float islands between quantize and dequantize steps, and ":nose", which drops the
squeeze-excitation blocks (pooling, two small layers and a full-size multiply each) - together the
MobileNetV4 recipe of plain ReLU blocks; files are named student_mn10-h0.3-relu-nose_*.
    python student_audio.py export mn05 student_mn05_q4.pt out/student_mn05_q4.onnx   # waveform -> embedding,
                                                                                      # for make_q4_encoder.py

It runs where qat_audio.py runs (backbone_fixed.onnx, common.py, frontend_params.npz, fma_wave.npy /
fma_lens.npy with commercial_ids.txt in their order), with EfficientAT (github.com/fschmid56/EfficientAT)
importable as `models`, nets.py and 0.7.1's universal_semantic_head.onnx beside it, and fft_frontend.py for the
export. It learns only from the FMA tracks CLEAN_IDS lists: CC BY, CC0 and public domain, no NoDerivs, no
ShareAlike, no recordings marked "Sound Recording Common Law Protection".
"""
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
from onnx2torch import convert

sys.path.insert(0, "EfficientAT")  # its models package; common.Student imports models.mn.model
from common import SR, WIN, Frontend, Student  # noqa: E402
from nets import Head  # noqa: E402
from qat_audio import LQ, quantizer  # noqa: E402

WIDTHS = {"mn05": 0.5, "mn10": 1.0}


def clean_tracks(lens):
    """Rows of fma_wave.npy (commercial_ids.txt order) whose track CLEAN_IDS lists and that hold 10 s."""
    ids = [int(x) for x in open("commercial_ids.txt").read().split()]
    keep = {int(x) for x in open(os.environ.get("CLEAN_IDS", "clean_ids.txt")).read().split()}
    return np.array([row for row, track in enumerate(ids) if track in keep and lens[row] >= 10 * SR])


def quantize(st, spatial="lq4c", projection="lq4b32"):
    """4-bit grids on the student's pointwise convolutions and its projection to 960. Layers with fewer than
    MIN4 weights (env, default 0) stay as they are, so the converter leaves them INT8: in a thin student they
    hold few of the bytes and much of the rounding error."""
    smallest = int(os.environ.get("MIN4", 0))
    for mod in st.net.features.modules():
        if isinstance(mod, nn.Conv2d) and mod.kernel_size == (1, 1) and mod.groups == 1 and mod.weight.numel() >= smallest:
            P.register_parametrization(mod, "weight", quantizer(spatial, mod.weight))
    P.register_parametrization(st.proj, "weight", quantizer(projection, st.proj.weight))
    return st


def parse(name):
    """"mn10:h0.3:relu:nose" -> ("mn10", 0.3, True, True); a bare "mn10" takes HEADW and keeps its hard-swish
    and squeeze-excitation."""
    width, *tags = name.split(":")
    headw = next((float(t[1:]) for t in tags if t.startswith("h")), float(os.environ.get("HEADW", 1.0)))
    return width, headw, "relu" in tags, "nose" in tags


def build(name, pretrained):
    width, _, relu, nose = parse(name)
    st = Student(width=WIDTHS[width], pretrained=pretrained)
    for parent in list(st.net.modules()):
        for child_name, child in parent.named_children():
            if relu and isinstance(child, nn.Hardswish):
                setattr(parent, child_name, nn.ReLU(inplace=True))
            elif nose and type(child).__name__ == "ConcurrentSEBlock":
                setattr(parent, child_name, nn.Identity())
    return st


def make(name, mode):
    width = parse(name)[0]
    audioset = mode == "float" and os.environ.get("PRETRAINED", "1") == "1"  # 0: random weights, for a smoke run
    st = build(name, f"{width}_as" if audioset else None)
    if mode != "float":
        st.load_state_dict(torch.load(f"student_{name.replace(':', '-')}_float.pt", map_location="cpu"))
        quantize(st)
    return st


def head_terms(head, emb):
    audio, genre = head(emb)
    return torch.sigmoid(audio.float()), F.log_softmax(genre.float(), -1)


def train(mode, epochs, names):
    torch.manual_seed(0)
    np.random.seed(0)
    dev = os.environ.get("DEV", "cuda")
    BS = int(os.environ.get("BS", 128))
    SPEED = float(os.environ.get("SPEED", 0.08))
    MAXSTEPS = int(os.environ.get("MAXSTEPS", 0))
    scale = 1.0 if mode == "float" else float(os.environ.get("LR_SCALE", 0.25))
    W = np.load(os.environ.get("WAVE", "fma_wave.npy"), mmap_mode="r")
    lens = np.load(os.environ.get("LENS", "fma_lens.npy"))
    usable = clean_tracks(lens)
    perm = np.random.default_rng(0).permutation(usable)
    NVAL = int(os.environ.get("NVAL", 400))
    val_idx, tr_idx = np.sort(perm[:NVAL]), np.sort(perm[NVAL:])
    fe = Frontend(np.load("frontend_params.npz")).to(dev).eval()
    teacher = convert(onnx.load("backbone_fixed.onnx")).to(dev).eval()
    head = Head(os.environ.get("HEAD", "universal_semantic_head.onnx")).to(dev).eval()
    for p in list(teacher.parameters()) + list(head.parameters()):
        p.requires_grad_(False)
    steps_per_epoch = len(tr_idx) // BS
    total = MAXSTEPS or epochs * steps_per_epoch
    students = {}
    for name in names:
        st = make(name, mode).to(dev).train()
        rest = list(st.proj.parameters()) + [st.affine]
        opt = torch.optim.AdamW([{"params": st.net.parameters(), "lr": 6e-4 * scale},
                                 {"params": rest, "lr": 2e-3 * scale}], weight_decay=1e-4)
        sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=[6e-4 * scale, 2e-3 * scale], total_steps=total,
                                                    pct_start=0.1 if mode == "float" else 0.05)
        students[name] = dict(model=st, opt=opt, sched=sched, best=-1.0, hist=[], headw=parse(name)[1],
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
        if SPEED > 0:  # one tempo-and-pitch factor per batch, both models hear it
            r = 1.0 + (2 * torch.rand(()).item() - 1) * SPEED
            y = F.interpolate(w[:, None], scale_factor=r, mode="linear", align_corners=False)[:, 0]
            if y.shape[1] >= WIN:
                off = int(torch.randint(0, y.shape[1] - WIN + 1, ()).item())
                w = y[:, off:off + WIN]
            else:
                w = F.pad(y, (0, WIN - y.shape[1]))
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
    with torch.no_grad():
        VT_genre = head(VT)[1].argmax(-1)
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
        with torch.no_grad():
            genre = float((head(S)[1].argmax(-1) == VT_genre).float().mean())
        return float(cos.mean()), float(cos.quantile(0.05)), float(ov), genre

    print(f"{mode}: train tracks {len(tr_idx)}, val {len(val_idx)}, steps/epoch {steps_per_epoch}, total {total}",
          flush=True)
    for name, s in students.items():
        print(name, f"params {sum(p.numel() for p in s['model'].parameters()):,}", "start", evaluate(s["model"]),
              flush=True)
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
                audio_t, genre_t = head_terms(head, t)
            for s in students.values():
                with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
                    y = s["model"](mel)
                y = y.float()
                l_cos = (1 - (y * t).sum(-1)).mean()
                yc = F.normalize(y - y.mean(0), dim=-1)
                l_rel = F.mse_loss(yc @ yc.T, rel_t)
                audio_s, genre_s = head_terms(head, y)
                l_head = F.mse_loss(audio_s, audio_t) + F.kl_div(genre_s, genre_t, log_target=True,
                                                                 reduction="batchmean")
                loss = l_cos + 2.0 * l_rel + s["headw"] * l_head
                s["opt"].zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(s["model"].parameters(), 3.0)
                s["opt"].step()
                s["sched"].step()
                for m in s["lq"]:
                    m.a.data.clamp_(0.3, 1.0)
                s["last"] = (float(loss.detach()), float(l_cos.detach()), float(l_rel.detach()),
                             float(l_head.detach()))
            step += 1
        for name, s in students.items():
            mean, p5, ov, genre = evaluate(s["model"])
            s["hist"].append(dict(epoch=ep, loss=s["last"][0], l_cos=s["last"][1], l_rel=s["last"][2],
                                  l_head=s["last"][3], val_cos_mean=mean, val_cos_p5=p5, val_top10_overlap=ov,
                                  val_genre=genre, t=time.time() - t0))
            print(name, json.dumps(s["hist"][-1]), flush=True)
            if mean > s["best"]:
                s["best"] = mean
                torch.save(s["model"].state_dict(),
                           f"student_{name.replace(':', '-')}_{'float' if mode == 'float' else 'q4'}.pt")
        if MAXSTEPS and step >= MAXSTEPS:
            break
    for name, s in students.items():
        json.dump(s["hist"], open(f"student_hist_{name.replace(':', '-')}_{mode}.json", "w"))
        print(name, "best val cos mean", s["best"], flush=True)


def export(name, state_path, out_path):
    """The student behind the shipped four-step front end, as a float graph whose 4-bit layers sit on their grids;
    the projection is a MatMul so make_q4_encoder.py can move it onto the operator too."""
    from fft_frontend import FourStepFrontend

    st = build(name, None)
    q4 = "_q4" in os.path.basename(state_path)
    if q4:
        quantize(st)
    st.load_state_dict(torch.load(state_path, map_location="cpu"))
    if q4:
        for mod in list(st.modules()):
            if P.is_parametrized(mod, "weight"):
                P.remove_parametrizations(mod, "weight", leave_parametrized=True)
    st.eval()

    class Full(nn.Module):
        def __init__(self):
            super().__init__()
            self.fe, self.st = FourStepFrontend(), st

        def forward(self, waveform):
            x = self.fe(waveform) * self.st.affine[0] + self.st.affine[1]
            feat = F.adaptive_avg_pool2d(self.st.net.features(x), (1, 1)).flatten(1)
            return F.normalize(torch.matmul(feat, self.st.proj.weight.T) + self.st.proj.bias, dim=-1)

    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    torch.onnx.export(Full().eval(), torch.zeros(1, WIN), out_path, input_names=["waveform"],
                      output_names=["embedding"], opset_version=17, dynamo=False)
    print(f"{name}: wrote {out_path} ({os.path.getsize(out_path) / 1e6:.2f} MB)")


if __name__ == "__main__":
    if sys.argv[1] in ("float", "qat"):
        train(sys.argv[1], int(sys.argv[2]), sys.argv[3].split(","))
    else:
        export(sys.argv[2], sys.argv[3], sys.argv[4])
