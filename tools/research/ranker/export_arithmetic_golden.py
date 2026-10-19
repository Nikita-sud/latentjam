"""Cases that tell left-to-right, unfused arithmetic from a fused multiply-add, for RankerArithmeticTest (common code,
run on the JVM and the iOS simulator): the float32 cosine (dot += a * b), the float64 norm of normalized() and the
float64 model score (total += w * z). Each case's expected bits are the unfused result; the fused one differs.

    python3 tools/research/ranker/export_arithmetic_golden.py > /dev/null   # prints the Kotlin literals
"""
import struct
from fractions import Fraction
import numpy as np

rng = np.random.default_rng(20261019)


def f32_bits(x): return struct.unpack('<I', struct.pack('<f', float(x)))[0]
def f64_bits(x): return struct.unpack('<Q', struct.pack('<d', float(x)))[0]


def dot32(a, b, fused):
    acc = np.float32(0)
    for x, y in zip(a, b):
        if fused: acc = np.float32(float(Fraction(float(acc)) + Fraction(float(x)) * Fraction(float(y))))
        else: acc = np.float32(acc + np.float32(x * y))
    return acc


def dot64(w, z, fused):
    acc = 0.0
    for x, y in zip(w, z):
        acc = float(Fraction(acc) + Fraction(x) * Fraction(y)) if fused else acc + x * y
    return acc


cases32, cases64 = [], []
while len(cases32) < 8:
    a = rng.standard_normal(16).astype(np.float32); b = rng.standard_normal(16).astype(np.float32)
    s, f = dot32(a, b, False), dot32(a, b, True)
    if f32_bits(s) != f32_bits(f): cases32.append((a, b, s))
while len(cases64) < 8:
    w = rng.standard_normal(12); z = rng.standard_normal(12)
    s, f = dot64(w, z, False), dot64(w, z, True)
    if s != f: cases64.append((w, z, s))
print('// float32: a, b, expected')
for a, b, s in cases32:
    print('Case32(intArrayOf(' + ', '.join(f'0x{f32_bits(x):08x}.toInt()' for x in a) + '), intArrayOf(' +
          ', '.join(f'0x{f32_bits(x):08x}.toInt()' for x in b) + f'), 0x{f32_bits(s):08x}.toInt()),')
print('// float64: w, z, expected')
for w, z, s in cases64:
    print('Case64(longArrayOf(' + ', '.join(f'0x{f64_bits(x):016x}UL.toLong()' for x in w) + '), longArrayOf(' +
          ', '.join(f'0x{f64_bits(x):016x}UL.toLong()' for x in z) + f'), 0x{f64_bits(s):016x}UL.toLong()),')
