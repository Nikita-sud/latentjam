#!/usr/bin/env python3
"""Write ml/artist_adapter.bin, the layout ArtistAdapter.kt reads, from a trained residual adapter.

The adapter is `normalize(x + Linear2(GELU_tanh(Linear1(x))))`: nn.Sequential(Linear(d, h),
GELU(approximate="tanh"), Dropout, Linear(h, d)), with state-dict keys net.0.* and net.3.* (or net.2.*
without dropout). Train it on trusted strings synthesized from the teacher's attributes of covered
artists (the recipe measured in smart-bench exp_adapter_synth.py), never on a library's real strings.

    python3 tools/research/export_artist_adapter.py adapter.pt out/artist_adapter.bin [--parity out/dir]

--parity also writes dir/artist_adapter.bin and dir/cases.tsv (input vector, expected output) from this
exporter's own forward pass, for ArtistKnowledgePackParityTest. `adapter.pt` may be "random:384x768"
to check the format without a trained model.
"""
import argparse
import struct
from pathlib import Path

import numpy as np


def load(source: str):
    if source.startswith("random:"):
        d, h = (int(x) for x in source.split(":", 1)[1].split("x"))
        rng = np.random.default_rng(0)
        return (rng.normal(0, 1 / np.sqrt(d), (h, d)), rng.normal(0, 0.05, h),
                rng.normal(0, 1 / np.sqrt(h), (d, h)), rng.normal(0, 0.05, d))
    import torch
    state = torch.load(source, map_location="cpu")
    first = [k for k in state if k.endswith(".weight")]
    w1, w2 = (state[k].numpy() for k in first[:2])
    b1, b2 = (state[k.replace("weight", "bias")].numpy() for k in first[:2])
    return w1, b1, w2, b2


def forward(tensors, x):
    w1, b1, w2, b2 = (t.astype(np.float32) for t in tensors)
    with np.errstate(all="ignore"):  # macOS Accelerate raises spurious FP flags in float32 matmul
        z = x @ w1.T + b1
        h = 0.5 * z * (1 + np.tanh(0.7978845608 * (z + 0.044715 * z ** 3)))
        y = x + h @ w2.T + b2
    if not np.isfinite(y).all():
        raise SystemExit("the adapter's forward pass is not finite")
    return y / np.linalg.norm(y, axis=-1, keepdims=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("adapter")
    parser.add_argument("output", type=Path)
    parser.add_argument("--parity", type=Path)
    args = parser.parse_args()
    w1, b1, w2, b2 = load(args.adapter)
    hidden, width = w1.shape
    halves = [np.asarray(t, np.float16) for t in (w1, b1, w2, b2)]
    blob = struct.pack("<8sIHH", b"LJADPT1\0", 1, width, hidden) + b"".join(t.tobytes() for t in halves)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(blob)
    print(f"wrote {args.output}: {width} -> {hidden} -> {width}, {len(blob) / 1e6:.2f} MB")
    if args.parity:
        args.parity.mkdir(parents=True, exist_ok=True)
        (args.parity / "artist_adapter.bin").write_bytes(blob)
        rng = np.random.default_rng(1)
        x = rng.normal(size=(64, width)).astype(np.float32)
        x /= np.linalg.norm(x, axis=1, keepdims=True)
        y = forward([t.astype(np.float32) for t in halves], x)   # the fp16 weights the device reads
        with (args.parity / "cases.tsv").open("w") as fh:
            for a, b in zip(x, y):
                fh.write(",".join(f"{v:.8g}" for v in a) + "\t" + ",".join(f"{v:.8g}" for v in b) + "\n")
        print(f"wrote {len(x)} parity cases to {args.parity}")


if __name__ == "__main__":
    main()
