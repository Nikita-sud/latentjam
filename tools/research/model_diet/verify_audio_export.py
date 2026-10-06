"""Compare a restored QAT checkpoint with the deployed ONNX, including fake quantization.

Run with common.py from the audio-student source directory on PYTHONPATH.
The calibration array is int16 PCM [N, 320000]. This checks numerical export
fidelity, not retrieval quality or phone performance.
"""

import argparse
import json
import os
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn.functional as F

import qat_audio as q


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source", type=Path, required=True)
    ap.add_argument("--checkpoint", type=Path, required=True)
    ap.add_argument("--encoder", type=Path, required=True)
    ap.add_argument("--cfg", required=True)
    ap.add_argument("--prune", type=Path)
    ap.add_argument("--calibration", type=Path, required=True)
    ap.add_argument("--lib", required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--n", type=int, default=32)
    ap.add_argument("--min-cosine", type=float, default=0.99)
    ap.add_argument("--activation-quantization", action="store_true")
    a = ap.parse_args()
    os.chdir(a.source)
    os.environ["AQ"] = "1" if a.activation_quantization else "0"
    base, projection = q.load_backbone()
    indices = json.loads(a.prune.read_text()) if a.prune else None
    model = q.make_student(base, projection, a.cfg, indices)
    model.load_state_dict(
        torch.load(a.checkpoint, map_location="cpu", weights_only=True)
    )
    model.eval()
    frontend = q.Frontend(np.load("frontend_params.npz")).eval()
    so = ort.SessionOptions()
    so.intra_op_num_threads = 2
    so.log_severity_level = 3
    so.register_custom_ops_library(a.lib)
    session = ort.InferenceSession(
        str(a.encoder), so, providers=["CPUExecutionProvider"]
    )
    waveform = np.load(a.calibration, mmap_mode="r")[: a.n].astype(np.float32) / 32767.0
    cosine = []
    with torch.no_grad():
        for w in waveform:
            expected = F.normalize(
                model(frontend(torch.from_numpy(w[None]))), dim=-1
            ).numpy()[0]
            actual = session.run(None, {"waveform": w[None]})[0][0]
            actual /= np.linalg.norm(actual)
            cosine.append(float(expected @ actual))
    exported = onnx.load(a.encoder)
    tensors = {
        t.name: onnx.numpy_helper.to_array(t) for t in exported.graph.initializer
    }
    ranges = next(
        (
            json.loads(p.value)
            for p in exported.metadata_props
            if p.key == "latentjam.activation_quantization"
        ),
        {},
    )
    checked = 0
    for name, grid in ranges.items():
        scale, zero = name + "_scale", name + "_zero_point"
        if scale in tensors and zero in tensors:
            np.testing.assert_array_equal(tensors[scale], np.float32(grid["scale"]))
            np.testing.assert_array_equal(tensors[zero], np.uint8(grid["zero_point"]))
            checked += 1
    result = dict(
        n=len(cosine),
        cosine_mean=float(np.mean(cosine)),
        cosine_min=min(cosine),
        cosine=cosine,
        activation_ranges_checked=checked,
        bytes=a.encoder.stat().st_size,
        minimum_required=a.min_cosine,
    )
    a.out.write_text(json.dumps(result, indent=2))
    print(json.dumps({k: v for k, v in result.items() if k != "cosine"}))
    if min(cosine) < a.min_cosine:
        raise SystemExit("Export fidelity below the specified tolerance")
    if a.activation_quantization and not checked:
        raise SystemExit(
            "Activation quantization requested but no preserved grids were checked"
        )


if __name__ == "__main__":
    main()
