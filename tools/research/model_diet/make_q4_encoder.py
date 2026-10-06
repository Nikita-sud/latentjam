"""Builds the shipped music encoder from a 4-bit QAT stand-in: a float graph (qat_audio.py export) whose
pointwise convolutions and final projection sit on a symmetric 4-bit grid, each layer either with one float
scale per output channel (sq4c) or with a bf16 scale per block of 32 inputs (sq4b32); the format of each
layer is read off its weights.

1. INT8 everywhere else, as rebuild_audio_encoder.py quantizes the encoder: QDQ, per-channel weights,
   percentile-99.99 activation ranges from calibration windows; the four-step front end stays float.
2. ONNX Runtime lays the graph out NHWC itself (its saved fully optimized graph).
3. Every pointwise convolution and the projection become latentjam.Q4Conv1x1 (core/ort-ops) with the exact
   4-bit weights, packed by q4pack.py; the uint8 activations around them are untouched.

    python make_q4_encoder.py STANDIN.onnx OUT.onnx --calibration windows.npy [--lib libljq4.dylib]

windows.npy: int16 [N, 320000], mono 32 kHz. With --lib (a host build of core/ort-ops, see its
CMakeLists.txt) the result is run against the INT8 graph of the same stand-in and against the stand-in.
"""
import argparse
import json
import os
import tempfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import helper, numpy_helper
from onnxruntime.quantization import CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType, quantize_static

from q4pack import GROUP, bf16_bits, pack, pack_channels


def blocks(W):
    """W [N, K] on the block grid -> (q int8 [N, K], bf16-exact scales [N, ceil(K / 32)], worst distance in steps)."""
    N, K = W.shape
    G = -(-K // GROUP)
    Wp = np.pad(W, ((0, 0), (0, G * GROUP - K))).reshape(N, G, GROUP)
    s = np.abs(Wp).max(-1) / 7.0
    s = np.where(s > 0, s, 1.0).astype(np.float32)
    s = (bf16_bits(s).astype(np.uint32) << 16).view(np.float32)
    r = Wp / s[:, :, None]
    q = np.clip(np.round(r), -8, 7)
    return q.reshape(N, -1)[:, :K].astype(np.int8), s, float(np.abs(r - q).max())


def channels(W):
    """W [N, K] on the per-channel grid -> (q int8 [N, K], float scales [N], worst distance in steps)."""
    s = np.abs(W).max(1) / 7.0
    s = np.where(s > 0, s, 1.0).astype(np.float32)
    r = W / s[:, None]
    q = np.clip(np.round(r), -8, 7)
    return q.astype(np.int8), s, float(np.abs(r - q).max())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("standin")
    ap.add_argument("out")
    ap.add_argument("--calibration", required=True)
    ap.add_argument("--ncal", type=int, default=200)
    ap.add_argument("--calibrate", choices=["percentile", "entropy", "minmax"], default="percentile",
                    help="how activation ranges are read off the calibration windows")
    ap.add_argument("--percentile", type=float, default=99.99)
    ap.add_argument("--lib", help="host build of libljq4, to check the result")
    args = ap.parse_args()

    work = Path(tempfile.mkdtemp())
    standin = onnx.load(args.standin)
    opsets = sorted({(o.domain, o.version) for o in standin.opset_import})   # merged graphs list opsets twice
    del standin.opset_import[:]
    standin.opset_import.extend(helper.make_opsetid(d, v) for d, v in opsets)
    onnx.save(standin, str(work / "standin.onnx"))
    ranges = next((json.loads(p.value) for p in standin.metadata_props
                   if p.key == "latentjam.activation_quantization"), {})
    overrides = {name: [{"scale": np.asarray(v["scale"], dtype=np.float32),
                         "zero_point": np.asarray(v["zero_point"], dtype=np.uint8)}]
                 for name, v in ranges.items()}
    if ranges:
        print(f"preserving {len(ranges)} trained activation ranges", flush=True)
    floats = {t.name: numpy_helper.to_array(t) for t in standin.graph.initializer}
    cal = np.load(args.calibration, mmap_mode="r")[:args.ncal].astype(np.float32) / 32767.0

    class Reader(CalibrationDataReader):
        def __init__(self):
            self.it = iter([{"waveform": w[None]} for w in cal])

        def get_next(self):
            return next(self.it, None)

    # The front end stays float: fft/ and fe4/ in the encoder's graph, /fe/ in a student's (student_audio.py).
    front = [n.name for n in standin.graph.node if n.name.startswith(("fft/", "fe4/", "/fe/"))]
    quantize_static(str(work / "standin.onnx"), str(work / "int8.onnx"), Reader(), quant_format=QuantFormat.QDQ,
                    per_channel=True, weight_type=QuantType.QInt8, activation_type=QuantType.QUInt8,
                    nodes_to_exclude=front, op_types_to_quantize=["Conv", "Gemm", "MatMul"] + (["Add", "GlobalAveragePool"] if ranges else []),
                    calibrate_method={"percentile": CalibrationMethod.Percentile, "entropy": CalibrationMethod.Entropy,
                                      "minmax": CalibrationMethod.MinMax}[args.calibrate],
                    extra_options={"CalibPercentile": args.percentile, "TensorQuantOverrides": overrides})
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.optimized_model_filepath = str(work / "nhwc.onnx")
    so.log_severity_level = 3
    ort.InferenceSession(str(work / "int8.onnx"), so, providers=["CPUExecutionProvider"])

    m = onnx.load(str(work / "nhwc.onnx"))
    g = m.graph
    inits = {t.name: t for t in g.initializer}
    nodes, swapped, worst, formats = [], 0, 0.0, {}
    for n in g.node:
        attrs = {a.name: helper.get_attribute_value(a) for a in n.attribute}
        pointwise = n.op_type == "QLinearConv" and attrs.get("kernel_shape") == [1, 1] and attrs.get("group", 1) == 1
        projection = n.op_type == "QLinearMatMul" and n.input[3] in inits
        if not (pointwise or projection):
            nodes.append(n)
            continue
        source = n.input[3].removesuffix("_quantized")
        W = floats[source].astype(np.float32)
        W = W.reshape(W.shape[0], -1) if pointwise else W.T          # [N, K]
        bias = np.zeros(W.shape[0], np.float32)
        if pointwise and len(n.input) > 8 and n.input[8]:
            bias = floats[n.input[8].removesuffix("_quantized")].astype(np.float32)
        q, scales, off = channels(W)               # a scale per channel where the weights allow it: faster
        block = 0
        if off > 0.01:
            q, scales, off = blocks(W)
            block = 32
        worst = max(worst, off)
        formats[block] = formats.get(block, 0) + 1
        blob = f"{source}_q4"
        packed = pack_channels(q, scales, bias) if block == 0 else pack(q, scales, bias)
        g.initializer.append(numpy_helper.from_array(packed, blob))
        nodes.append(helper.make_node("Q4Conv1x1", [n.input[0], n.input[1], n.input[2], blob, n.input[6], n.input[7]],
                                      list(n.output), domain="latentjam", name=n.name + "_q4", n=int(W.shape[0]),
                                      block=block))
        swapped += 1
    del g.node[:]
    g.node.extend(nodes)
    used = {i for n in g.node for i in n.input}
    keep = [t for t in g.initializer if t.name in used]
    del g.initializer[:]
    g.initializer.extend(keep)
    m.opset_import.append(helper.make_opsetid("latentjam", 1))
    onnx.save(m, args.out)
    print(f"{swapped} layers on Q4Conv1x1 ({formats.get(0, 0)} with a scale per channel, {formats.get(32, 0)} with "
          f"blocks of 32; worst distance from the 4-bit grid {worst:.3f} steps); "
          f"{os.path.getsize(args.out) / 1e6:.2f} MB, INT8 reference {os.path.getsize(work / 'int8.onnx') / 1e6:.2f} MB")
    if worst > 0.01:
        raise SystemExit("the stand-in's weights are on neither 4-bit grid: export it from qat_audio.py")
    if not args.lib:
        return
    so = ort.SessionOptions()
    so.register_custom_ops_library(os.path.abspath(args.lib))
    so.log_severity_level = 3
    q4 = ort.InferenceSession(args.out, so, providers=["CPUExecutionProvider"])
    int8 = ort.InferenceSession(str(work / "int8.onnx"), providers=["CPUExecutionProvider"])
    flt = ort.InferenceSession(str(work / "standin.onnx"), providers=["CPUExecutionProvider"])
    cos = lambda u, v: float((u * v).sum() / np.linalg.norm(u) / np.linalg.norm(v))
    to_int8, to_float = [], []
    for w in cal[:24]:
        a, b, c = (s.run(None, {"waveform": w[None]})[0][0] for s in (q4, int8, flt))
        to_int8.append(cos(a, b))
        to_float.append(cos(a, c))
    print(f"vs the INT8 graph of the stand-in: cos median {np.median(to_int8):.5f} min {min(to_int8):.5f}; "
          f"vs the float stand-in: median {np.median(to_float):.5f} min {min(to_float):.5f}")


if __name__ == "__main__":
    main()
