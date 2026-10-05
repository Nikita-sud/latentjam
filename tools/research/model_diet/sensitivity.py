"""Post-training CQ sensitivity of each SMART-net matrix, before any training.

    python sensitivity.py scorer|state|head [samples]

Quantizes one matrix at a time (and then all of them) at 2, 3 and 4 bits, group 32, everything else
float, and measures how far the outputs move on recorded inputs. QAT then gets the bit budget where
it matters. Scorer: top-10 overlap and Spearman within each 100-candidate pool; state: cosine to the
float state and the scorer's top-10 overlap downstream; head: logit error and top-genre agreement.
"""
import sys

import numpy as np
import torch

import cq
import data
import nets

torch.set_num_threads(4)
ML = data.ROOT / "baseline" / "ml"
TEACH = data.ROOT / "phase1" / "teachers"  # the float originals of the two SMART nets


def spearman(a, b):
    ra, rb = a.argsort(1).argsort(1).float(), b.argsort(1).argsort(1).float()
    ra, rb = ra - ra.mean(1, keepdim=True), rb - rb.mean(1, keepdim=True)
    return ((ra * rb).sum(1) / (ra.norm(dim=1) * rb.norm(dim=1)).clamp_min(1e-9))


def top_overlap(a, b, k=10):
    ta, tb = a.topk(k, 1).indices, b.topk(k, 1).indices
    return torch.tensor([len(set(x.tolist()) & set(y.tolist())) / k for x, y in zip(ta, tb)])


def candidates(d, idx):
    rows = torch.as_tensor(d["s_rows"][idx])
    m = torch.as_tensor(d["matrix"])
    c = m[rows.clamp_min(0)] * (rows >= 0).unsqueeze(-1)
    return c, rows >= 0


def masked(scores, live):
    return torch.where(live, scores, torch.full_like(scores, -1e9))


def with_cq(net, name, bits, group=32):
    """A copy of net whose parameter `name` ([in, out] layout) is replaced by its CQ round trip."""
    import copy
    q = copy.deepcopy(net)
    p = dict(q.named_parameters())[name]
    with torch.no_grad():
        p.copy_(cq.CQ(bits, group, input_axis=0).roundtrip(p))
    return q


def scorer(d, n):
    idx = np.random.default_rng(0).choice(len(d["s_rows"]), n, replace=False)
    net = nets.Scorer(TEACH / "predictor_scorer_fp32.onnx").eval()
    state = torch.as_tensor(d["s_state"][idx]).float()
    c, live = candidates(d, idx)
    with torch.no_grad():
        ref = masked(net(state, c), live)
        for name in ("w1", "w2", "w3", "all"):
            for bits in (2, 3, 4):
                q = net
                for nm in (["w1", "w2", "w3"] if name == "all" else [name]):
                    q = with_cq(q, nm, bits)
                out = masked(q(state, c), live)
                print(f"scorer {name:4s} {bits} bits: top-10 overlap {top_overlap(ref, out).mean():.4f}  "
                      f"spearman {spearman(ref, out).mean():.4f}", flush=True)


def state(d, n):
    idx = np.random.default_rng(0).choice(len(d["e_in"]), n, replace=False)
    net = nets.StateNet(TEACH / "predictor_state_fp32.onnx").eval()
    sc = nets.Scorer(TEACH / "predictor_scorer_fp32.onnx").eval()
    inputs = nets.StateNet.split(d["e_in"][idx].astype(np.float32))
    s_state = torch.as_tensor(d["s_state"][idx]).float()
    c, live = candidates(d, idx)
    with torch.no_grad():
        ref = net(*inputs)
        ref_scores = masked(sc(torch.cat([ref, s_state[:, 960:]], 1), c), live)
        names = [k for k in net.W.keys() if net.W[k].numel() >= 4096] + ["gru_w", "gru_r"]  # tiny ones stay fp16
        for name in names + ["all"]:
            for bits in (2, 3, 4):
                import copy
                q = copy.deepcopy(net)
                for nm in (names if name == "all" else [name]):
                    if nm.startswith("gru"):  # [768, 256]: gates stacked on the output axis
                        getattr(q, nm).copy_(cq.CQ(bits, 32, input_axis=1).roundtrip(getattr(q, nm)))
                    else:
                        q.W[nm].copy_(cq.CQ(bits, 32, input_axis=0).roundtrip(q.W[nm]))
                out = q(*inputs)
                cos = (out * ref).sum(1)
                scores = masked(sc(torch.cat([out, s_state[:, 960:]], 1), c), live)
                print(f"state {name:4s} {bits} bits: cos mean {cos.mean():.5f} min {cos.min():.5f}  "
                      f"scorer top-10 overlap {top_overlap(ref_scores, scores).mean():.4f}", flush=True)


def head(d, n):
    import onnxruntime as ort
    m = torch.as_tensor(d["matrix"][:, :960])
    idx = np.random.default_rng(0).choice(len(m), n, replace=False)
    x = m[idx]
    net = nets.Head(ML / "universal_semantic_head.onnx").eval()
    src = ML / "universal_semantic_head.onnx"
    ref = ort.InferenceSession(str(src), providers=["CPUExecutionProvider"]).run(None, {"embedding": x.numpy()})[0]
    for name in ("w1", "all"):
        for bits in (2, 3, 4):
            q = net
            for nm in (["w1", "w2"] if name == "all" else [name]):
                if nm == "w2":
                    import copy
                    q = copy.deepcopy(q)
                    with torch.no_grad():
                        q.w2.copy_(cq.CQ(bits, 32, input_axis=1).roundtrip(q.w2))
                else:
                    q = with_cq(q, nm, bits)
            q.export("/tmp/head_probe.onnx", src)
            out = ort.InferenceSession("/tmp/head_probe.onnx", providers=["CPUExecutionProvider"]).run(None, {"embedding": x.numpy()})[0]
            g = (ref[:, 14:27].argmax(1) == out[:, 14:27].argmax(1)).mean()
            mood = (ref[:, 7:14].argmax(1) == out[:, 7:14].argmax(1)).mean()
            print(f"head {name:4s} {bits} bits: max |Δscore| {np.abs(ref - out).max():.4f}  top genre {100 * g:.1f} %  "
                  f"mood {100 * mood:.1f} %", flush=True)


if __name__ == "__main__":
    which = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 2000
    d = data.load(data.ROOT / "phase1" / "data" / "smart")
    {"scorer": scorer, "state": state, "head": head}[which](d, n)
