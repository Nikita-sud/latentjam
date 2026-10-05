"""Turns a QAT stand-in graph (uniform blocks, qat_nets.py / qat_text.py --uniform) into the real low-bit
graph stock ONNX Runtime runs, with no custom operator:

- every MatMul or Gemm (transB) whose weight is an initializer of at least --min elements becomes
  com.microsoft MatMulNBits (Gemm's bias follows as an Add; fp16 activations are cast around it);
- --embedding NAME: the Gather over that initializer becomes com.microsoft GatherBlockQuantized;
- every other float initializer of at least --min elements is stored fp16 behind a Cast (folded once
  at session load), as the shipped fp16 graphs already do.

    python to_stock.py IN.onnx OUT.onnx [--bits 4] [--block 32] [--embedding NAME] [--min 4096]

The weights are re-quantized with the same rule QAT trained them on (to_nbits.quantize), so the
result matches the stand-in up to float rounding; the script checks that on random inputs.
"""
import argparse
import os

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper

from to_nbits import quantize


def gather_quantize(W, bits, block):
    """[V, H] table -> packed data [V, H*bits/8], scales [V, H/block], packed zero points, dequantized table."""
    packed_t, scales, zps, deq = quantize(W.T.copy(), bits, block)   # quantize() blocks along its K = H
    V, H = W.shape
    blocks = H // block
    q = packed_t.reshape(V, blocks * block * bits // 8)                # [N=V, blocks, blob] -> [V, H*bits/8]
    return q, scales.reshape(V, blocks), zps.reshape(V, -1), deq.T


def convert(model, bits, block, embedding, minimum, only=None):
    g = model.graph
    inits = {t.name: t for t in g.initializer}
    producers = {o: n for n in g.node for o in n.output}
    consumers = {}
    for n in g.node:
        for i in n.input:
            consumers.setdefault(i, []).append(n)
    used_as_weight, nodes, extra = set(), [], []

    def weight(name):
        """The initializer behind a weight input (directly, or through one Cast)."""
        if name in inits:
            return inits[name], None
        p = producers.get(name)
        if p is not None and p.op_type == "Cast" and p.input[0] in inits and len(consumers.get(name, [])) == 1:
            return inits[p.input[0]], p
        return None, None

    dropped = set()
    for n in g.node:
        if n.op_type in ("MatMul", "Gemm") and len(n.input) >= 2:
            t, cast = weight(n.input[1])
            if t is not None and np.prod(t.dims) >= minimum and cast is not None and (only is None or t.name in only):
                dropped.add(id(cast))
    for n in g.node:
        if id(n) in dropped:
            continue
        if n.op_type in ("MatMul", "Gemm") and len(n.input) >= 2:
            t, cast = weight(n.input[1])
            attrs = {a.name: helper.get_attribute_value(a) for a in n.attribute}
            ok = t is not None and np.prod(t.dims) >= minimum and (only is None or t.name in only)
            if n.op_type == "Gemm":
                ok = ok and attrs.get("transA", 0) == 0 and attrs.get("alpha", 1.0) == 1.0 and attrs.get("beta", 1.0) == 1.0
            if ok:
                W = numpy_helper.to_array(t).astype(np.float32)
                if n.op_type == "Gemm" and attrs.get("transB", 0):
                    W = W.T
                K, N = W.shape
                half = t.data_type == TensorProto.FLOAT16 and cast is None  # fp16 weight used as is: fp16 compute
                packed, scales, zps, _ = quantize(W, bits, block)
                base = t.name.replace(":", "_")
                extra += [numpy_helper.from_array(packed, f"{base}_nbits"), numpy_helper.from_array(scales, f"{base}_scales_f16"),
                          numpy_helper.from_array(zps, f"{base}_zp")]
                used_as_weight.add(t.name)
                a, y = n.input[0], n.output[0]
                if half:  # the graph computes in fp16: MatMulNBits runs in float between two casts
                    nodes.append(helper.make_node("Cast", [a], [f"{base}_a32"], to=TensorProto.FLOAT))
                    a = f"{base}_a32"
                bias = n.input[2] if n.op_type == "Gemm" and len(n.input) > 2 and n.input[2] else None
                out = f"{base}_y32" if (half or bias) else y
                nodes.append(helper.make_node("Cast", [f"{base}_scales_f16"], [f"{base}_scales"], to=TensorProto.FLOAT))
                nodes.append(helper.make_node("MatMulNBits", [a, f"{base}_nbits", f"{base}_scales", f"{base}_zp"], [out],
                                              domain="com.microsoft", K=K, N=N, bits=bits, block_size=block,
                                              name=f"{n.name}_nbits"))
                if half:  # back to the graph's fp16 before its fp16 bias
                    back = f"{base}_y16" if bias else y
                    nodes.append(helper.make_node("Cast", [out], [back], to=TensorProto.FLOAT16))
                    out = back
                if bias:
                    nodes.append(helper.make_node("Add", [out, bias], [y]))
                continue
        if n.op_type == "Gather" and embedding and n.input[0] == embedding:
            W = numpy_helper.to_array(inits[embedding]).astype(np.float32)
            data, scales, zps, _ = gather_quantize(W, bits, block)
            extra += [numpy_helper.from_array(data, f"{embedding}_q"), numpy_helper.from_array(scales.astype(np.float16), f"{embedding}_scales_f16"),
                      numpy_helper.from_array(zps, f"{embedding}_zp")]
            used_as_weight.add(embedding)
            nodes.append(helper.make_node("Cast", [f"{embedding}_scales_f16"], [f"{embedding}_scales"], to=TensorProto.FLOAT))
            nodes.append(helper.make_node("GatherBlockQuantized", [f"{embedding}_q", n.input[1], f"{embedding}_scales", f"{embedding}_zp"],
                                          list(n.output), domain="com.microsoft", gather_axis=0, quantize_axis=1,
                                          block_size=block, bits=bits, name=f"{n.name}_q"))
            continue
        nodes.append(n)
    keep = [t for t in g.initializer if t.name not in used_as_weight]
    rest = []
    for t in keep:  # other big float initializers: fp16 on disk behind a Cast
        if t.data_type == TensorProto.FLOAT and np.prod(t.dims) >= minimum:
            a = numpy_helper.to_array(t)
            rest.append(numpy_helper.from_array(a.astype(np.float16), f"{t.name}_f16"))
            nodes.insert(0, helper.make_node("Cast", [f"{t.name}_f16"], [t.name], to=TensorProto.FLOAT))
        else:
            rest.append(t)
    del g.initializer[:]
    g.initializer.extend(rest + extra)
    del g.node[:]
    g.node.extend(nodes)
    if not any(o.domain == "com.microsoft" for o in model.opset_import):
        model.opset_import.append(helper.make_opsetid("com.microsoft", 1))
    return model


def feeds_for(session, rng):
    out = {}
    for i in session.get_inputs():
        shape = [d if isinstance(d, int) else 3 for d in i.shape]
        if "int" in i.type:
            out[i.name] = rng.integers(1, 1000, shape).astype(np.int64) if "ids" in i.name else np.ones(shape, np.int64)
            if "token_type" in i.name:
                out[i.name] = np.zeros(shape, np.int64)
        else:
            out[i.name] = (rng.standard_normal(shape) * 0.05).astype(np.float32 if "float)" in i.type else np.float16)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--bits", type=int, default=4)
    ap.add_argument("--block", type=int, default=32)
    ap.add_argument("--embedding")
    ap.add_argument("--min", type=int, default=4096)
    ap.add_argument("--only", help="comma-separated weight initializers to convert (the ones QAT trained); default all")
    args = ap.parse_args()
    only = set(args.only.split(",")) if args.only else None
    model = convert(onnx.load(args.src), args.bits, args.block, args.embedding, args.min, only)
    onnx.save(model, args.dst)
    a = ort.InferenceSession(args.src, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(args.dst, providers=["CPUExecutionProvider"])
    f = feeds_for(a, np.random.default_rng(0))
    ya, yb = a.run(None, f)[0], b.run(None, f)[0]
    print(f"{os.path.getsize(args.src) / 1e6:.2f} MB -> {os.path.getsize(args.dst) / 1e6:.2f} MB; max |difference| "
          f"{np.abs(ya.astype(np.float32) - yb.astype(np.float32)).max():.2e} (output scale {np.abs(ya).max():.2e})")


if __name__ == "__main__":
    main()
