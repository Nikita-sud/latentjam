"""Packs 4-bit weights the way latentjam.Q4Conv1x1 reads them, in numpy, byte for byte as KleidiAI's packers
write them for its i8mm kernels (nr 8, kr 16, sr 2):

- blocks of 32 inputs with a bf16 scale each (pack): kai_run_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0;
- one float scale per output channel (pack_channels): kai_run_rhs_pack_nxk_qsi4cxp_qs4cxs1s0.

Per 8 output channels: per block of 32 inputs, two segments of 8 channels x 4 little-endian words, each word
the values at k, k + 16, k + 1, k + 17 (value + 8 per nibble, xor 0x8888), then the 8 channels' bf16 scales;
after the last block the 8 channels' zero-point sums (float: sum over blocks of scale x sum of the values) and
their float biases.
"""
import numpy as np

NR, GROUP = 8, 32


def bf16_bits(a):
    """float32 -> bfloat16 bits, round to nearest, ties to even (as torch's bfloat16 cast)."""
    b = np.asarray(a, np.float32).view(np.uint32).astype(np.uint64)
    return ((b + 0x7FFF + ((b >> 16) & 1)) >> 16).astype(np.uint16)


def packed_size(n, k):
    return -(-n // NR) * (-(-k // GROUP) * NR * 18 + NR * 8)


def pack(q, scale, bias):
    """q int [n, k] in -8..7, scale float [n, ceil(k / 32)] (rounded to bf16 here), bias float [n] -> uint8 bytes."""
    q = np.asarray(q, np.int64)
    n, k = q.shape
    groups = -(-k // GROUP)
    padded_n = -(-n // NR) * NR
    qp = np.zeros((padded_n, groups * GROUP), np.int64)
    qp[:n, :k] = q
    qp[n:] = qp[n - 1] if n % NR else qp[n:]          # KleidiAI repeats the last channel into the padding
    sb = np.zeros((padded_n, groups), np.uint16)
    sb[:n] = bf16_bits(np.asarray(scale, np.float32).reshape(n, groups))
    sb[n:] = sb[n - 1] if n % NR else sb[n:]
    bp = np.zeros(padded_n, np.float32)
    bp[:n] = np.asarray(bias, np.float32)
    bp[n:] = bp[n - 1] if n % NR else bp[n:]
    sf = (sb.astype(np.uint32) << 16).view(np.float32)
    u = (qp + 8).astype(np.uint16)                      # unsigned nibbles
    out = []
    for first in range(0, padded_n, NR):
        rows = slice(first, first + NR)
        sums = np.zeros(NR, np.float32)
        for g in range(groups):
            for segment in range(2):
                for lane in range(NR):
                    for j in range(4):
                        k0 = g * GROUP + segment * 8 + 2 * j
                        ch = first + lane
                        word = (u[ch, k0] | (u[ch, k0 + 16] << 4) | (u[ch, k0 + 1] << 8) | (u[ch, k0 + 17] << 12)) ^ 0x8888
                        out.append(np.array([word], '<u2').view(np.uint8))
            out.append(sb[rows, g].astype('<u2').view(np.uint8))
            part = (qp[rows, g * GROUP:(g + 1) * GROUP]).sum(1).astype(np.float32)
            sums += part * sf[rows, g]
        out.append(sums.astype('<f4').view(np.uint8))
        out.append(bp[rows].astype('<f4').view(np.uint8))
    return np.concatenate(out)


def channels_packed_size(n, k):
    return -(-n // NR) * NR * (-(-k // GROUP) * GROUP // 2 + 12)


def pack_channels(q, scale, bias):
    """q int [n, k] in -8..7, scale float [n], bias float [n] -> uint8 bytes.

    Per 8 output channels: for each byte, the value at k (low nibble) and at k + 16 (high), value + 8 per nibble,
    xor 0x88, in blocks of 8 bytes per channel interleaved over the 8 channels; then sum(values) * 16 as int32,
    scale / 16 and bias as float, 8 each."""
    q = np.asarray(q, np.int64)
    n, k = q.shape
    internal = -(-k // GROUP) * GROUP
    padded_n = -(-n // NR) * NR
    rows = np.minimum(np.arange(padded_n), n - 1)          # KleidiAI repeats the last channel into the padding
    qp = np.zeros((padded_n, internal + 16), np.int64)      # beyond K: value 0 (nibble 8)
    qp[:, :k] = q[rows]
    scale = np.asarray(scale, np.float32)[rows]
    bias = np.asarray(bias, np.float32)[rows]
    idx = np.arange(NR * internal // 2)
    block, within = idx // 8, idx % 8
    super_, lane = block // NR, block % NR
    base = within + super_ * 8
    k0 = base + base // 16 * 16
    out = []
    for first in range(0, padded_n, NR):
        lo = qp[first + lane, k0] + 8
        hi = qp[first + lane, k0 + 16] + 8
        out.append(((lo | (hi << 4)) ^ 0x88).astype(np.uint8))
        sums = (qp[first:first + NR, :k].sum(1) * 16).astype('<i4')
        out.append(sums.view(np.uint8))
        out.append((scale[first:first + NR] * np.float32(0.0625)).astype('<f4').view(np.uint8))
        out.append(bias[first:first + NR].astype('<f4').view(np.uint8))
    return np.concatenate(out)
