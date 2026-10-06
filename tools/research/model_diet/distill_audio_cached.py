"""A bounded teacher-assistant experiment using cached training-only crops.

The best checkpoint is selected on held-out tracks, including epoch zero.
This is a pilot with one fixed crop per track, not a replacement for the
multi-crop, augmented training recipe in qat_audio.py.
"""

import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import torch
import torch.nn.functional as F

import qat_audio as q


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cache", type=Path, required=True)
    ap.add_argument("--source", type=Path, required=True)
    ap.add_argument("--init", type=Path, required=True)
    ap.add_argument("--prune", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--cfg", default="p50_lq4c+sq4b32")
    ap.add_argument("--device", default="mps")
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--max-train", type=int, default=2048)
    ap.add_argument("--max-val", type=int, default=384)
    ap.add_argument("--lr", type=float, default=5e-5)
    a = ap.parse_args()
    torch.manual_seed(0)
    rng = np.random.default_rng(0)
    a.out.mkdir(parents=True, exist_ok=True)
    q.BACKBONE = str(a.source / "backbone_fixed.onnx")
    q.FULL = str(a.source / "mnv4_4step_fp32.onnx")
    manifest = json.loads((a.cache / "manifest.json").read_text())
    done = np.load(a.cache / "done.npy")
    validation = np.array([r["validation"] for r in manifest["rows"]])
    train = rng.permutation(np.flatnonzero(done & ~validation))[: a.max_train]
    val = rng.permutation(np.flatnonzero(done & validation))[: a.max_val]
    if len(train) < a.max_train or len(val) < a.max_val:
        raise ValueError(
            "Cache must contain the requested training and validation samples"
        )
    mel = np.load(a.cache / "mel.npy", mmap_mode="r")
    targets = np.load(a.cache / "teacher.npy", mmap_mode="r")
    base, proj = q.load_backbone()
    indices = json.loads(a.prune.read_text())
    model = q.make_student(base, proj, a.cfg, indices)
    model.load_state_dict(torch.load(a.init, map_location="cpu", weights_only=True))
    model.to(a.device)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=0)
    lr = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs)
    history = []
    start = time.monotonic()

    def batch(ids):
        x = torch.from_numpy(np.array(mel[ids], dtype=np.float32)).to(a.device)
        y = torch.from_numpy(np.array(targets[ids])).to(a.device)
        return x, y

    @torch.no_grad()
    def evaluate():
        model.eval()
        output, teacher = [], []
        for b in range(0, len(val), a.batch):
            x, y = batch(val[b : b + a.batch])
            output.append(F.normalize(model(x).float(), dim=-1).cpu())
            teacher.append(y.cpu())
        s, t = torch.cat(output), torch.cat(teacher)
        cos = (s * t).sum(-1)
        sc = F.normalize(s - s.mean(0), dim=-1)
        tc = F.normalize(t - t.mean(0), dim=-1)
        sn = (sc @ sc.T).fill_diagonal_(-9).topk(10).indices
        tn = (tc @ tc.T).fill_diagonal_(-9).topk(10).indices
        overlap = np.mean(
            [len(set(x.tolist()) & set(y.tolist())) / 10 for x, y in zip(sn, tn)]
        )
        return dict(
            cos=float(cos.mean()),
            cos_p5=float(cos.quantile(0.05)),
            top10=float(overlap),
        )

    def record(epoch, metrics, loss=None):
        row = dict(
            epoch=epoch, **metrics, loss=loss, elapsed_s=time.monotonic() - start
        )
        history.append(row)
        (a.out / "history.json").write_text(json.dumps(history, indent=2))
        print(json.dumps(row), flush=True)

    before = evaluate()
    best = before["cos"]
    torch.save(
        {k: v.detach().cpu() for k, v in model.state_dict().items()}, a.out / "best.pt"
    )
    record(0, before)
    provenance = dict(
        arguments={k: str(v) if isinstance(v, Path) else v for k, v in vars(a).items()},
        init_sha256=hashlib.sha256(a.init.read_bytes()).hexdigest(),
        teacher_sha256=manifest["teacher_sha256"],
        train_ids=[manifest["rows"][i]["id"] for i in train],
        validation_ids=[manifest["rows"][i]["id"] for i in val],
    )
    (a.out / "provenance.json").write_text(json.dumps(provenance, indent=2))
    (a.out / ("prune_" + a.cfg + ".json")).write_text(json.dumps(indices))
    for epoch in range(1, a.epochs + 1):
        model.train()
        order = rng.permutation(train)
        total = 0.0
        for b in range(0, len(order), a.batch):
            x, t = batch(order[b : b + a.batch])
            s = F.normalize(model(x).float(), dim=-1)
            sc = F.normalize(s - s.mean(0), dim=-1)
            tc = F.normalize(t - t.mean(0), dim=-1)
            loss = (1 - (s * t).sum(-1)).mean() + F.mse_loss(sc @ sc.T, tc @ tc.T)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 3.0)
            opt.step()
            for mod in model.modules():
                if isinstance(mod, q.LQ):
                    mod.a.data.clamp_(0.3, 1.0)
            total += float(loss.detach()) * len(x)
            if b // a.batch == 19:
                print(
                    f"epoch {epoch}: 20 batches, elapsed {time.monotonic() - start:.1f}s",
                    flush=True,
                )
        metrics = evaluate()
        record(epoch, metrics, total / len(train))
        if metrics["cos"] > best:
            best = metrics["cos"]
            torch.save(
                {k: v.detach().cpu() for k, v in model.state_dict().items()},
                a.out / "best.pt",
            )
        lr.step()
    print(f"best validation cosine {best:.6f}", flush=True)


if __name__ == "__main__":
    main()
