"""Turns a QAT stand-in graph into a real low-bit graph that stock ONNX Runtime runs: every MatMul whose
weight is named becomes com.microsoft MatMulNBits (uniform blocks along K, a scale and a zero point per
block, as qat_nets.py's UQ trained them).

    python to_nbits.py IN.onnx OUT.onnx NAME=BITS [NAME=BITS ...] [--block 32]

NAME is a MatMul weight initializer ([K, N]). Scales are stored fp16 behind a Cast (folded once when the
session loads), so the file holds 2 bytes per block. The script runs both graphs on random inputs and
reports the largest difference; it should be rounding noise, because QAT already trained the float
weights to sit on the quantization grid this script uses.
"""
import argparse

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper


def quantize(W, bits, block):
    """W [K, N] -> packed B [N, blocks, block*bits/8], scales [N*blocks], packed zero points, dequantized W."""
    K, N = W.shape
    pad = (-K) % block
    Wp = np.pad(W, ((0, pad), (0, 0))).T.reshape(N, -1, block)          # [N, blocks, block]
    top = (1 << bits) - 1
    lo, hi = np.minimum(Wp.min(-1, keepdims=True), 0), np.maximum(Wp.max(-1, keepdims=True), 0)
    scale = np.maximum((hi - lo) / top, 6.104e-05).astype(np.float16).astype(np.float32)  # fp16 never rounds it to 0
    zp = np.clip(np.round(-lo / scale), 0, top)
    q = np.clip(np.round(Wp / scale) + zp, 0, top).astype(np.uint8)
    per = 8 // bits
    packed = np.zeros((N, q.shape[1], block * bits // 8), np.uint8)
    for i in range(block):
        packed[:, :, i // per] |= (q[:, :, i] << ((i % per) * bits)).astype(np.uint8)
    zq = zp.reshape(N, -1).astype(np.uint8)
    zblocks = -(-zq.shape[1] // per) * per
    zq = np.pad(zq, ((0, 0), (0, zblocks - zq.shape[1])))
    zpacked = np.zeros((N, zblocks // per), np.uint8)
    for i in range(per):
        zpacked |= (zq[:, i::per] << (i * bits)).astype(np.uint8)
    deq = ((q.astype(np.float32) - zp) * scale).reshape(N, -1)[:, :K].T
    return packed, scale.reshape(-1).astype(np.float16), zpacked.reshape(-1), deq


def convert(model, targets, block):
    inits = {t.name: t for t in model.graph.initializer}
    # The shipped fp16 graphs feed MatMul through Cast(fp16 initializer); look through those casts.
    cast_of = {n.output[0]: n for n in model.graph.node if n.op_type == "Cast" and n.input[0] in inits}
    dropped = set()
    for node in model.graph.node:
        if node.op_type == "MatMul" and len(node.input) == 2 and node.input[1] in cast_of and node.input[1] in targets:
            dropped.add(id(cast_of[node.input[1]]))
    nodes = []
    for node in model.graph.node:
        if id(node) in dropped:
            continue
        name = node.input[1] if node.op_type == "MatMul" and len(node.input) == 2 else None
        if name not in targets:
            nodes.append(node)
            continue
        source = inits[cast_of[name].input[0]] if name in cast_of else inits[name]
        W = numpy_helper.to_array(source).astype(np.float32)
        inits[name] = source
        K, N = W.shape
        bits = targets[name]
        packed, scales, zps, _ = quantize(W, bits, block)
        model.graph.initializer.remove(inits[name])
        model.graph.initializer.extend([numpy_helper.from_array(packed, f"{name}_nbits"),
                                        numpy_helper.from_array(scales, f"{name}_scales_f16"),
                                        numpy_helper.from_array(zps, f"{name}_zp")])
        nodes.append(helper.make_node("Cast", [f"{name}_scales_f16"], [f"{name}_scales"], to=TensorProto.FLOAT))
        nodes.append(helper.make_node("MatMulNBits", [node.input[0], f"{name}_nbits", f"{name}_scales", f"{name}_zp"],
                                      list(node.output), domain="com.microsoft", K=K, N=N, bits=bits, block_size=block,
                                      name=f"{node.name or name}_nbits"))
    del model.graph.node[:]
    model.graph.node.extend(nodes)
    if not any(o.domain == "com.microsoft" for o in model.opset_import):
        model.opset_import.append(helper.make_opsetid("com.microsoft", 1))
    return model


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("targets", nargs="+")
    ap.add_argument("--block", type=int, default=32)
    args = ap.parse_args()
    targets = {k: int(v) for k, v in (t.split("=") for t in args.targets)}
    model = convert(onnx.load(args.src), targets, args.block)
    onnx.save(model, args.dst)
    a = ort.InferenceSession(args.src, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(args.dst, providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(0)
    feeds = {}
    for i in a.get_inputs():
        shape = [d if isinstance(d, int) else 2 for d in i.shape]
        feeds[i.name] = (rng.standard_normal(shape) * 0.05).astype(np.float32)
    ya, yb = a.run(None, feeds)[0], b.run(None, feeds)[0]
    import os
    print(f"{os.path.getsize(args.src) / 1e6:.2f} MB -> {os.path.getsize(args.dst) / 1e6:.2f} MB; max |difference| "
          f"{np.abs(ya - yb).max():.2e} (output scale {np.abs(ya).max():.2e})")


if __name__ == "__main__":
    main()
