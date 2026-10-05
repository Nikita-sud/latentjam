"""Quantization-aware distillation of the text student (s256x3m) into CQ weights.

    python qat_text.py EMB_BITS LAYER_BITS [--epochs 3] [--lr 2e-4] [--out runs/text-...]

EMB_BITS for the word-embedding table (11,588 x 256, row groups along the 256 dims), LAYER_BITS for
every Linear in the three encoder layers and the 256->384 projection; 16 keeps a part fp16. Group 32.
The student starts from the shipped float checkpoint (s256x3m/student.pt) and learns, as in
text_student/train.py, to land each string's mean-pooled, L2-normalised output on MiniLM's vector
(teacher.npy + aug + ×20 multilingual pairs, the same 10,000 held out), while also staying close to
its own float self (so search, the scorer and the adapter, all fitted in MiniLM space, keep reading
it). Writes a dequantized stand-in ONNX with the app's interface and sizes.json.
"""
import argparse
import copy
import json
import math
import os
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.nn.utils.parametrize as P
from tokenizers import BertWordPieceTokenizer
from transformers import BertConfig, BertModel

import cq

HERE = Path(__file__).resolve().parent
from paths import PHASE, TEXT_STUDENT as TS
dev = os.environ.get("DEVICE") or ("cuda" if torch.cuda.is_available() else "cpu")


class Student(nn.Module):
    def __init__(self, cfg):
        super().__init__()
        c = BertConfig(vocab_size=cfg["vocab"], hidden_size=cfg["hidden"], num_hidden_layers=cfg["layers"],
                       num_attention_heads=cfg["heads"], intermediate_size=cfg["ff"], max_position_embeddings=64,
                       type_vocab_size=2, hidden_act="gelu", attn_implementation="eager")
        self.bert = BertModel(c, add_pooling_layer=False)
        self.proj = nn.Linear(cfg["hidden"], 384)

    def forward(self, input_ids, attention_mask, token_type_ids):
        return self.proj(self.bert(input_ids=input_ids, attention_mask=attention_mask,
                                   token_type_ids=token_type_ids).last_hidden_state)


def pooled(model, x, m):
    h = model(x, m, torch.zeros_like(x))
    p = (h * m.unsqueeze(-1)).sum(1) / m.sum(1, keepdim=True).clamp(min=1)
    return F.normalize(p, dim=-1)


def targets():
    strings = json.load(open(TS / "strings.json"))
    Y = np.load(TS / "teacher.npy")
    aug_s = json.load(open(TS / "aug_strings.json"))
    aug_y = np.load(TS / "aug_teacher.npy")
    mp = json.load(open(TS / "ml_pairs.json"))["train"]
    my = np.load(TS / "ml_train_teacher.npy").astype(np.float16)
    limit = int(os.environ.get("TEXT_LIMIT", 0))  # smoke tests only
    if limit:
        strings, Y = strings[:limit], Y[:limit]
    return strings + aug_s + [p for p, _, _ in mp] * 20, np.concatenate([Y, aug_y] + [my] * 20).astype(np.float16)


def quantized_linears(model):
    return [(name, mod) for name, mod in model.named_modules() if isinstance(mod, nn.Linear)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("emb_bits", type=int)
    ap.add_argument("layer_bits", type=int)
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--self_weight", type=float, default=0.5)
    ap.add_argument("--uniform", action="store_true",
                    help="uniform blocks with a zero point (MatMulNBits / GatherBlockQuantized) instead of CQ")
    ap.add_argument("--out")
    args = ap.parse_args()
    torch.manual_seed(0)
    np.random.seed(0)
    cfg = json.load(open(TS / "s256x3m" / "config.json"))
    out = Path(args.out or PHASE / "runs" / f"text-e{args.emb_bits}-l{args.layer_bits}")
    out.mkdir(parents=True, exist_ok=True)

    strings, Y = targets()
    tok = BertWordPieceTokenizer(str(TS / "s256x3m" / "vocab.txt"), lowercase=True, strip_accents=True)
    tok.enable_truncation(48)
    ids = [e.ids for e in tok.encode_batch(strings)]
    n = len(ids)
    perm = np.random.permutation(n)
    val, train = perm[:10_000], perm[10_000:]
    lengths = np.array([len(s) for s in ids])

    def batch(idx):
        seqs = [ids[i] for i in idx]
        w = max(len(s) for s in seqs)
        x = np.zeros((len(seqs), w), np.int64)
        m = np.zeros_like(x)
        for r, s in enumerate(seqs):
            x[r, :len(s)] = s
            m[r, :len(s)] = 1
        return (torch.tensor(x, device=dev), torch.tensor(m, device=dev),
                torch.tensor(Y[idx].astype(np.float32), device=dev))

    state = torch.load(TS / "s256x3m" / "student.pt", map_location="cpu")
    teacher = Student(cfg).to(dev).eval()
    teacher.load_state_dict(state)
    model = Student(cfg).to(dev)
    model.load_state_dict(state)
    size = 0.0
    nbytes = cq.uq_bytes if args.uniform else cq.cq_bytes
    if args.emb_bits < 16:
        emb = model.bert.embeddings.word_embeddings
        cq.attach(emb, "weight", args.emb_bits, 32, input_axis=1, uniform=args.uniform)
        size += nbytes(tuple(emb.weight.shape), args.emb_bits, 32, 1)
    if args.layer_bits < 16:
        for name, mod in quantized_linears(model):
            cq.attach(mod, "weight", args.layer_bits, 32, input_axis=1, uniform=args.uniform)
            size += nbytes(tuple(mod.weight.shape), args.layer_bits, 32, 1)
    for pname, p in model.named_parameters():
        if not pname.endswith("parametrizations.weight.original"):
            size += p.numel() * 2
    model.to(dev)  # the parametrizations' codebooks and Hadamard matrices join the weights' device

    @torch.no_grad()
    def evaluate():
        model.eval()
        to_teacher, to_float = [], []
        for i in range(0, len(val), 1024):
            x, m, y = batch(val[i:i + 1024])
            p = pooled(model, x, m)
            to_teacher.append((p * y).sum(-1).cpu())
            to_float.append((p * pooled(teacher, x, m)).sum(-1).cpu())
        return {"cos_minilm": float(torch.cat(to_teacher).mean()), "cos_float_student": float(torch.cat(to_float).mean()),
                "cos_float_p1": float(torch.cat(to_float).quantile(0.01))}

    history = [dict(epoch=0, **evaluate())]
    print(f"text e{args.emb_bits} l{args.layer_bits}: {len(train)} strings on {dev}; CQ blob {size / 1e6:.2f} MB; "
          f"post-training: {history[0]}", flush=True)
    BS = 512
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    steps = args.epochs * math.ceil(len(train) / BS)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps, pct_start=0.05)
    best = None
    for ep in range(args.epochs):
        model.train()
        t0 = time.time()
        order = np.random.permutation(train)
        chunks = [order[i:i + BS * 50] for i in range(0, len(order), BS * 50)]
        batches = []
        for c in chunks:
            c = c[np.argsort(lengths[c])]
            batches += [c[i:i + BS] for i in range(0, len(c), BS)]
        np.random.shuffle(batches)
        for b in batches:
            x, m, y = batch(b)
            p = pooled(model, x, m)
            with torch.no_grad():
                f = pooled(teacher, x, m)
            loss = (1 - (p * y).sum(-1)).mean() + args.self_weight * (1 - (p * f).sum(-1)).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
        mtr = evaluate()
        history.append(dict(epoch=ep + 1, **mtr))
        print(f"epoch {ep + 1}: {mtr} ({time.time() - t0:.0f}s)", flush=True)
        score = mtr["cos_minilm"] + mtr["cos_float_student"]
        if best is None or score > best[0]:
            best = (score, copy.deepcopy(model.state_dict()))
    model.load_state_dict(best[1])
    model.eval().cpu()
    for mod in [model.bert.embeddings.word_embeddings] + [m for _, m in quantized_linears(model)]:
        if P.is_parametrized(mod, "weight"):
            P.remove_parametrizations(mod, "weight", leave_parametrized=True)
    torch.save(model.state_dict(), out / "student_cq.pt")
    x = torch.ones(2, 8, dtype=torch.int64)
    torch.onnx.export(model, (x, x, torch.zeros_like(x)), str(out / "text_encoder.onnx"),
                      input_names=["input_ids", "attention_mask", "token_type_ids"], output_names=["last_hidden_state"],
                      dynamic_axes={k: {0: "batch", 1: "seq"} for k in ("input_ids", "attention_mask", "token_type_ids",
                                                                         "last_hidden_state")},
                      opset_version=17, dynamo=False)
    (out / "sizes.json").write_text(json.dumps({"text_encoder.onnx": {"raw": int(size), "apk": int(size), "note": (
        f"{'uniform blocks' if args.uniform else 'CQ'}: word embeddings {args.emb_bits}-bit, Linear layers "
        f"{args.layer_bits}-bit (16 = fp16), group 32; the rest "
        f"fp16; measured as the dequantized float stand-in written by tools/research/model_diet/qat_text.py")}}, indent=1))
    (out / "history.json").write_text(json.dumps(history, indent=1))
    print(f"done -> {out}", flush=True)


if __name__ == "__main__":
    main()
