"""Rewrites the artist adapter (LJADPT1 version 1, fp16) as version 2 with CQ weights.

    python tools/research/compact_artist_adapter.py IN.bin OUT.bin [--bits 4]

CQ (Cactus quantization): along each output row, groups of 32 input weights are rotated by the
normalised 32-point Walsh-Hadamard matrix, scaled to unit length, and every coordinate is replaced by
the nearest level of a Lloyd-Max codebook for a unit Gaussian, divided by sqrt(32); the group's length
is kept as fp16. The app reconstructs a group as (codebook[codes] * length) times the same Hadamard
matrix (its own inverse) when it loads the file.

Layout of version 2, little endian: magic `LJADPT1\\0`, u32 version 2, u16 input width, u16 hidden
width, u8 bits, u8 group size, u16 level count, the levels as f32, then W1 [hidden, input] as packed
codes (each byte holds codes from its low bits up, row after row) and fp16 group lengths, b1 fp16, W2
[input, hidden] likewise, b2 fp16. Both widths must be multiples of the group size.
"""
import argparse
import struct

import numpy as np

GROUP = 32


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
    return H / np.sqrt(n)


def quantize(W, levels, H):
    """W [rows, inputs] -> (codes uint8 [rows, inputs], lengths fp16 [rows, groups], dequantized W)."""
    rows, inputs = W.shape
    g = W.reshape(rows, inputs // GROUP, GROUP) @ H
    lengths = np.linalg.norm(g, axis=-1, keepdims=True).astype(np.float16)
    unit = g / np.maximum(lengths.astype(np.float32), 1e-12)
    codes = np.searchsorted((levels[:-1] + levels[1:]) / 2, unit).astype(np.uint8)
    deq = ((levels[codes] * lengths.astype(np.float32)) @ H).reshape(rows, inputs)
    return codes.reshape(rows, inputs), lengths[..., 0], deq.astype(np.float32)


def pack_codes(codes, bits):
    flat = codes.ravel().astype(np.uint32)
    per_byte = 8 // bits
    flat = np.pad(flat, (0, (-len(flat)) % per_byte)).reshape(-1, per_byte)
    return (flat << (np.arange(per_byte) * bits)).sum(1).astype(np.uint8).tobytes()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--bits", type=int, default=4, choices=(2, 4, 8))
    args = ap.parse_args()
    raw = open(args.src, "rb").read()
    assert raw[:8] == b"LJADPT1\0" and struct.unpack_from("<I", raw, 8)[0] == 1
    width, hidden = struct.unpack_from("<HH", raw, 12)
    assert width % GROUP == 0 and hidden % GROUP == 0
    values = np.frombuffer(raw, "<f2", offset=16).astype(np.float32)
    w1, b1, w2, b2 = np.split(values, np.cumsum([hidden * width, hidden, width * hidden])[:])[:4]
    levels = (lloyd_max(args.bits) / np.sqrt(GROUP)).astype(np.float32)
    H = hadamard(GROUP).astype(np.float32)
    c1, l1, _ = quantize(w1.reshape(hidden, width), levels, H)
    c2, l2, _ = quantize(w2.reshape(width, hidden), levels, H)
    out = bytearray(b"LJADPT1\0" + struct.pack("<IHHBBH", 2, width, hidden, args.bits, GROUP, len(levels)))
    out += levels.astype("<f4").tobytes()
    out += pack_codes(c1, args.bits) + l1.astype("<f2").tobytes() + b1.astype("<f2").tobytes()
    out += pack_codes(c2, args.bits) + l2.astype("<f2").tobytes() + b2.astype("<f2").tobytes()
    open(args.dst, "wb").write(bytes(out))
    print(f"{len(raw) / 1e6:.2f} MB -> {len(out) / 1e6:.2f} MB ({args.bits}-bit CQ, group {GROUP})")


if __name__ == "__main__":
    main()
