#!/usr/bin/env python3
"""Generates the cross-platform embedding fixture.

The audio encoder has to produce the same vector on every platform or the
index built on one device is meaningless on another. This writes a reference
embedding for a waveform that any runtime can regenerate exactly, so a port can
be checked without shipping a WAV file or trusting a decoder.

The waveform is an LCG rather than anything trigonometric on purpose: integer
arithmetic is bit-identical in Python, Kotlin/JVM and Kotlin/Native, whereas
sin() is only identical to within each libm's rounding. This fixture is meant
to isolate the MODEL, not to re-test the platform's math library.

    python3 tools/make_embedding_fixture.py \
        androidApp/src/main/assets/ml/mnv4_audio.onnx \
        core/smart/src/commonTest/resources/embedding_fixture.bin
"""
import struct
import sys

import numpy as np
import onnxruntime

SAMPLES = 320_000  # 10 s at 32 kHz, the model's fixed input length
EMBEDDING_DIM = 960
SEED = 12345
MULTIPLIER = 1103515245
INCREMENT = 12345
MODULUS = 0x7FFFFFFF


def waveform() -> np.ndarray:
    """Deterministic pseudo-audio in [-1, 1). Reproduce this exactly to compare."""
    out = np.empty(SAMPLES, dtype=np.float32)
    state = SEED
    for i in range(SAMPLES):
        state = (state * MULTIPLIER + INCREMENT) & MODULUS
        out[i] = np.float32(state / 2147483648.0 * 2.0 - 1.0)
    return out


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    model_path, fixture_path = sys.argv[1], sys.argv[2]

    session = onnxruntime.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    wave = waveform().reshape(1, SAMPLES)
    embedding = session.run(["embedding"], {"waveform": wave})[0][0]

    assert embedding.shape == (EMBEDDING_DIM,), embedding.shape
    norm = float(np.linalg.norm(embedding))
    print(f"onnxruntime {onnxruntime.__version__}")
    print(f"embedding dim={embedding.shape[0]} norm={norm:.6f}")
    print(f"first 6: {', '.join(f'{v:+.6f}' for v in embedding[:6])}")

    with open(fixture_path, "wb") as handle:
        handle.write(struct.pack("<i", EMBEDDING_DIM))
        handle.write(embedding.astype("<f4").tobytes())
    print(f"wrote {fixture_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
