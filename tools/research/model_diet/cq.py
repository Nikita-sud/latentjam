"""Cactus CQ weights for QAT and size accounting, shared by every phase-1 net.

The format (cactus-compute/needle quantize.py, as rebuilt for Whistle): along a matrix's INPUT axis,
groups of G weights are rotated by a normalised Walsh-Hadamard matrix, scaled to unit length, and
each coordinate is replaced by the nearest level of a Lloyd-Max codebook for a Gaussian (divided by
sqrt(G)); the group's L2 norm is kept as fp16. Reconstruction: (codebook[idx] * norm) @ H.
QAT rounds straight-through (w + stop_grad(CQ(w) - w)), as audio_student/qat.py does.
"""
import functools

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F


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
    """Parametrization of a weight stored [in, out] (input_axis=0) or [out, in] (input_axis=1)."""

    def __init__(self, bits, group, input_axis):
        super().__init__()
        cb = torch.tensor(lloyd_max(bits) / np.sqrt(group), dtype=torch.float32)
        self.register_buffer("cb", cb)
        self.register_buffer("bounds", (cb[:-1] + cb[1:]) / 2)
        self.register_buffer("H", hadamard(group))
        self.bits, self.group, self.input_axis = bits, group, input_axis

    def roundtrip(self, w):
        W = w.T if self.input_axis == 0 else w          # [out, in]
        out, inn = W.shape
        pad = (-inn) % self.group
        Wp = F.pad(W, (0, pad)) if pad else W
        g = Wp.float().reshape(out, -1, self.group) @ self.H
        norm = g.norm(dim=-1, keepdim=True)
        unit = g / norm.clamp_min(1e-12)
        q = self.cb[torch.bucketize(unit, self.bounds)]
        deq = ((q * norm.half().float()) @ self.H).reshape(out, -1)[:, :inn]
        return (deq.T if self.input_axis == 0 else deq).to(w.dtype)

    def forward(self, w):
        return w + (self.roundtrip(w) - w).detach()


def cq_bytes(shape, bits, group, input_axis):
    """Bytes a CQ blob needs for one matrix: packed codes plus one fp16 norm per group."""
    out, inn = (shape[1], shape[0]) if input_axis == 0 else (shape[0], shape[1])
    padded = -(-inn // group) * group
    return out * padded * bits / 8 + out * padded // group * 2


def attach(module, name, bits, group, input_axis, uniform=False):
    """Puts CQ (or MatMulNBits-style uniform blocks) on module.<name> and returns the parametrization."""
    import torch.nn.utils.parametrize as P
    q = (UQ if uniform else CQ)(bits, group, input_axis).to(getattr(module, name).device)  # registration evaluates it
    P.register_parametrization(module, name, q)
    return q


class UQ(nn.Module):
    """Uniform block quantization as ONNX Runtime's MatMulNBits stores it: per block of `group` input
    weights a scale and a zero point, q = clamp(round(w / scale) + zp, 0, 2^bits - 1), w ≈ (q - zp) * scale.
    Straight-through like CQ; lets QAT target the operator stock ONNX Runtime already ships."""

    def __init__(self, bits, group, input_axis):
        super().__init__()
        self.bits, self.group, self.input_axis = bits, group, input_axis

    def roundtrip(self, w):
        W = w.T if self.input_axis == 0 else w          # [out, in]
        out, inn = W.shape
        pad = (-inn) % self.group
        Wp = F.pad(W, (0, pad)) if pad else W
        g = Wp.float().reshape(out, -1, self.group)
        top = (1 << self.bits) - 1
        lo, hi = g.amin(-1, keepdim=True).clamp(max=0), g.amax(-1, keepdim=True).clamp(min=0)
        # Stored fp16, as to_nbits.py writes it; kept at or above fp16's smallest normal so it never rounds to 0.
        scale = ((hi - lo) / top).clamp_min(6.104e-05).half().float()
        zp = torch.round(-lo / scale).clamp(0, top)
        q = torch.clamp(torch.round(g / scale) + zp, 0, top)
        deq = ((q - zp) * scale).reshape(out, -1)[:, :inn]
        return (deq.T if self.input_axis == 0 else deq).to(w.dtype)

    def forward(self, w):
        return w + (self.roundtrip(w) - w).detach()


def uq_bytes(shape, bits, group, input_axis):
    """MatMulNBits bytes: packed codes, an fp16 scale per block (stored fp16, cast at load), a packed zero point."""
    out, inn = (shape[1], shape[0]) if input_axis == 0 else (shape[0], shape[1])
    blocks = -(-inn // group)
    return out * blocks * group * bits / 8 + out * blocks * 2 + out * blocks * bits / 8
