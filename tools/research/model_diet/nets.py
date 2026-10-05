"""PyTorch twins of the shipped SMART nets, loaded from the ONNX files, for QAT.

Scorer (predictor_scorer_n100.onnx, "scoring-semtext-v1"), per candidate and independent of the
others: x = [s, c, s*c, |s - c|, sum(s*c)] (5377) -> LayerNorm -> Linear 512 -> GELU -> Linear 512
-> GELU -> Linear 1, with s the 1344-d scorer state and c the 1344-d candidate.
"""
import numpy as np
import onnx
import torch
import torch.nn as nn
import torch.nn.functional as F
from onnx import numpy_helper


def initializers(path):
    m = onnx.load(str(path))
    return {t.name: numpy_helper.to_array(t).astype(np.float32) for t in m.graph.initializer}


class Scorer(nn.Module):
    NAMES = {"ln_w": "head.mlp.0.weight", "ln_b": "head.mlp.0.bias", "w1": "onnx::MatMul_57", "b1": "head.mlp.1.bias",
             "w2": "onnx::MatMul_58", "b2": "head.mlp.4.bias", "w3": "onnx::MatMul_59", "b3": "head.mlp.7.bias"}

    def __init__(self, path):
        """From the shipped fp16 file or the float original (git 9331ead9^): same names, minus _f16."""
        super().__init__()
        w = initializers(path)
        for attr, name in self.NAMES.items():
            setattr(self, attr, nn.Parameter(torch.tensor(w[name] if name in w else w[f"{name}_f16"])))

    def features(self, state, cands):
        s = state[:, None, :].expand_as(cands)
        prod = s * cands
        return torch.cat([s, cands, prod, (s - cands).abs(), prod.sum(-1, keepdim=True)], -1)

    def forward(self, state, cands):
        x = F.layer_norm(self.features(state, cands), (self.ln_w.shape[0],), self.ln_w, self.ln_b, eps=1e-5)
        h = F.gelu(x @ self.w1 + self.b1)
        h = F.gelu(h @ self.w2 + self.b2)
        return (h @ self.w3 + self.b3).squeeze(-1)

    def export(self, path, source):
        """Writes the (dequantized) weights into a copy of the source graph, in that graph's precision."""
        m = onnx.load(str(source))
        values = {name: getattr(self, attr) for attr, name in self.NAMES.items()}
        values.update({f"{name}_f16": getattr(self, attr) for attr, name in self.NAMES.items()})
        for t in m.graph.initializer:
            if t.name in values:
                a = values[t.name].detach().cpu().numpy()
                dtype = np.float16 if t.name.endswith("_f16") else np.float32
                t.CopyFrom(numpy_helper.from_array(a.astype(dtype), t.name))
        onnx.save(m, str(path))


class StateNet(nn.Module):
    """predictor_state.onnx: history -> 960-d unit state.

    history_small [B,4,961] = four latest tracks (960-d audio + a play weight); history_medium and
    history_large [B,960] = longer-window means; time and session features [B,5]. The four tokens run
    through Linear(961->256) and a GRU (ONNX layout, linear_before_reset); medium and large through
    Linear(960->256) -> LayerNorm -> GELU; time and session through Linear(5->32) -> GELU -> Linear(32->32).
    Their concatenation (832) -> Linear 1024 -> LayerNorm -> GELU -> Linear 960, scaled by residual_scale
    and added to the anchor (play-weighted mean of the four tracks, plain mean when no weight), then L2.
    Weights load from the float original (latentjam-research distill export) or dequantized from the
    shipped INT8 file (per-channel, zero point 0).
    """

    LINEAR = {"tok": "onnx::MatMul_237", "med": "m.med_proj.0.weight", "lg": "m.lg_proj.0.weight",
              "t0": "m.time_enc.0.weight", "t2": "m.time_enc.2.weight", "s0": "m.session_enc.0.weight",
              "s2": "m.session_enc.2.weight", "f0": "m.fuse.0.weight", "f4": "m.fuse.4.weight"}
    BIAS = {"tok": "m.token_in_proj.bias", "med": "m.med_proj.0.bias", "lg": "m.lg_proj.0.bias",
            "t0": "m.time_enc.0.bias", "t2": "m.time_enc.2.bias", "s0": "m.session_enc.0.bias",
            "s2": "m.session_enc.2.bias", "f0": "m.fuse.0.bias", "f4": "m.fuse.4.bias"}

    def __init__(self, path):
        super().__init__()
        w = initializers(path)
        t = lambda a: nn.Parameter(torch.tensor(a, dtype=torch.float32))

        def matrix(v):  # [in, out]: dequantized INT8, or the float original (Gemm weights are [out, in])
            if f"{v}_quantized" in w:
                return w[f"{v}_quantized"] * w[f"{v}_scale"][None, :]
            return w[v].T if v.startswith("m.") else w[v]

        self.W = nn.ParameterDict({k: t(matrix(v)) for k, v in self.LINEAR.items()})
        self.B = nn.ParameterDict({k: t(w[v]) for k, v in self.BIAS.items()})
        self.ln = nn.ParameterDict({k: t(w[v]) for k, v in {
            "med_w": "m.med_proj.1.weight", "med_b": "m.med_proj.1.bias", "lg_w": "m.lg_proj.1.weight",
            "lg_b": "m.lg_proj.1.bias", "f_w": "m.fuse.1.weight", "f_b": "m.fuse.1.bias"}.items()})
        self.gru_w, self.gru_r, self.gru_b = t(w["onnx::GRU_257"][0]), t(w["onnx::GRU_258"][0]), t(w["onnx::GRU_259"][0])
        self.residual_scale = t(w["m.residual_scale"])

    def lin(self, k, x):
        return x @ self.W[k] + self.B[k]

    def gru(self, x):
        """ONNX GRU, gates z, r, h; linear_before_reset = 1; zero initial state; last hidden."""
        h = x.new_zeros(x.shape[0], 256)
        wz, wr, wh = self.gru_w.split(256)
        rz, rr, rh = self.gru_r.split(256)
        bwz, bwr, bwh, brz, brr, brh = self.gru_b.split(256)
        for t in range(x.shape[1]):
            xt = x[:, t]
            z = torch.sigmoid(xt @ wz.T + h @ rz.T + bwz + brz)
            r = torch.sigmoid(xt @ wr.T + h @ rr.T + bwr + brr)
            n = torch.tanh(xt @ wh.T + r * (h @ rh.T + brh) + bwh)
            h = (1 - z) * n + z * h
        return h

    def forward(self, small, medium, large, time, session):
        audio, weight = small[..., :960], small[..., 960:961].clamp(0, 1)
        count = weight.sum(1)                                            # [B,1]
        has = (count > 1e-6).float()
        weighted = (audio * weight).sum(1) / count.clamp_min(1e-6)
        anchor = has * weighted + (1 - has) * audio.mean(1)
        anchor = anchor / anchor.norm(dim=-1, keepdim=True).clamp_min(1e-12)
        g = self.gru(self.lin("tok", small))
        med = F.gelu(F.layer_norm(self.lin("med", medium), (256,), self.ln["med_w"], self.ln["med_b"], eps=1e-5))
        lg = F.gelu(F.layer_norm(self.lin("lg", large), (256,), self.ln["lg_w"], self.ln["lg_b"], eps=1e-5))
        tm = self.lin("t2", F.gelu(self.lin("t0", time)))
        se = self.lin("s2", F.gelu(self.lin("s0", session)))
        x = torch.cat([g, med, lg, tm, se], -1)
        x = F.gelu(F.layer_norm(self.lin("f0", x), (1024,), self.ln["f_w"], self.ln["f_b"], eps=1e-5))
        out = anchor + self.residual_scale * self.lin("f4", x)
        return out / out.norm(dim=-1, keepdim=True).clamp_min(1e-12)

    def export(self, path, source):
        """Writes the weights into a copy of the float original's graph (Gemm weights as [out, in])."""
        m = onnx.load(str(source))
        values = {v: (self.W[k].T if v.startswith("m.") else self.W[k]) for k, v in self.LINEAR.items()}
        values.update({v: self.B[k] for k, v in self.BIAS.items()})
        values.update({"onnx::GRU_257": self.gru_w[None], "onnx::GRU_258": self.gru_r[None], "onnx::GRU_259": self.gru_b[None],
                       "m.residual_scale": self.residual_scale})
        values.update({v: self.ln[k] for k, v in {"med_w": "m.med_proj.1.weight", "med_b": "m.med_proj.1.bias",
                                                  "lg_w": "m.lg_proj.1.weight", "lg_b": "m.lg_proj.1.bias",
                                                  "f_w": "m.fuse.1.weight", "f_b": "m.fuse.1.bias"}.items()})
        for t in m.graph.initializer:
            if t.name in values:
                t.CopyFrom(numpy_helper.from_array(values[t.name].detach().cpu().numpy().astype(np.float32), t.name))
        onnx.save(m, str(path))

    @staticmethod
    def split(e_in):
        """The recorder's flat 5774-wide input row -> the five named inputs."""
        e = torch.as_tensor(e_in, dtype=torch.float32)
        return (e[:, :3844].reshape(-1, 4, 961), e[:, 3844:4804], e[:, 4804:5764], e[:, 5764:5769], e[:, 5769:5774])


class Head(nn.Module):
    """universal_semantic_head.onnx up to its logits; the 27 scores are a fixed composition of them.

    x = L2(embedding); audio branch: HardSwish(2.5 * x @ W1 + b1) @ W2^T + b2 -> 59 AudioSet logits;
    FMA branch: (x @ Wf^T + bf) * scale + bias -> 18 calibrated genre logits. The shipped graph runs in
    fp16; this twin runs in float32 (logits agree to ~1e-2).
    """

    def __init__(self, path):
        super().__init__()
        w = initializers(path)
        p = lambda name: nn.Parameter(torch.tensor(w[name]))
        self.w1, self.b1 = p("onnx::MatMul_361"), p("audio_first_bias")        # [960, 1280]
        self.w2, self.b2 = p("audio_second_weight"), p("audio_second_bias")   # [59, 1280]
        self.fw, self.fb = p("fma_weights"), p("fma_biases")                  # [18, 960]
        self.register_buffer("fs", torch.tensor(w["fma_calibration_scales"]))
        self.register_buffer("fc", torch.tensor(w["fma_calibration_biases"]))

    def forward(self, emb):
        x = emb / emb.norm(dim=-1, keepdim=True).clamp_min(1.1920929e-07)
        h = F.hardswish(2.5 * (x @ self.w1) + self.b1)
        return h @ self.w2.T + self.b2, (x @ self.fw.T + self.fb) * self.fs + self.fc

    def export(self, path, source):
        m = onnx.load(str(source))
        values = {"onnx::MatMul_361": self.w1, "audio_first_bias": self.b1, "audio_second_weight": self.w2,
                  "audio_second_bias": self.b2, "fma_weights": self.fw, "fma_biases": self.fb}
        for t in m.graph.initializer:
            if t.name in values:
                t.CopyFrom(numpy_helper.from_array(values[t.name].detach().cpu().numpy().astype(np.float16), t.name))
        onnx.save(m, str(path))
