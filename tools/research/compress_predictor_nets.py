#!/usr/bin/env python3
"""Shrink the two SMART nets without changing what SMART picks.

- predictor_state.onnx: dynamic INT8 (per-channel weights, MatMulInteger), the op family the MiniLM
  text encoder already ran on every platform. 11.9 MB -> 4.2 MB.
- predictor_scorer_n100.onnx: FP16 weight storage, FP32 compute (each weight is cast back when the
  session loads). 12.1 MB -> 6.1 MB. INT8 moved the scorer's logits enough to reorder candidates;
  FP16 storage does not.

Measured on 2026-09-24 with the phone configuration (knowledge pack, adapter, energy, sound gate) over the
listener's library, cold and with history, and the MPD evaluation libraries: the INT8 state net keeps a cosine of
0.9998 to the FP32 state and P@10 moves within +-0.6 pp; FP16 storage alone leaves P@10 exactly unchanged. An
INT8 scorer kept only 74-77 % of its top-10 candidates and was rejected.

    python3 tools/research/compress_predictor_nets.py <dir with the FP32 nets> androidApp/src/main/assets/ml

The FP32 nets are in git history before this change (scoring-semtext-v1).
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import helper, numpy_helper
from onnxruntime.quantization import QuantType, quantize_dynamic

STATE = "predictor_state.onnx"
SCORER = "predictor_scorer_n100.onnx"


def fp16_weights(source: Path, target: Path) -> None:
    """Stores every FP32 initializer of 64+ values as FP16 behind a Cast back to FP32."""
    model = onnx.load(source)
    graph = model.graph
    initializers, casts = [], []
    for initializer in graph.initializer:
        array = numpy_helper.to_array(initializer)
        if array.dtype == np.float32 and array.size >= 64:
            initializers.append(numpy_helper.from_array(array.astype(np.float16), initializer.name + "_f16"))
            casts.append(helper.make_node("Cast", [initializer.name + "_f16"], [initializer.name],
                                          to=onnx.TensorProto.FLOAT, name=initializer.name + "_cast"))
        else:
            initializers.append(initializer)
    del graph.initializer[:]
    graph.initializer.extend(initializers)
    nodes = casts + list(graph.node)
    del graph.node[:]
    graph.node.extend(nodes)
    onnx.save(model, target)


def random_feeds(path: Path, count: int, rng: np.random.Generator) -> list[dict]:
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    feeds = []
    for _ in range(count):
        feed = {}
        for spec in session.get_inputs():
            shape = [1 if not isinstance(d, int) else d for d in spec.shape]
            feed[spec.name] = rng.standard_normal(shape).astype(np.float32)
        feeds.append(feed)
    return feeds


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="directory holding the FP32 nets")
    parser.add_argument("target", type=Path, help="directory to write the compressed nets into")
    args = parser.parse_args()
    args.target.mkdir(parents=True, exist_ok=True)

    quantize_dynamic(args.source / STATE, args.target / STATE, weight_type=QuantType.QInt8, per_channel=True)
    fp16_weights(args.source / SCORER, args.target / SCORER)

    # A sanity check on random inputs; the real-chain measurement is in the docstring.
    rng = np.random.default_rng(0)
    for name in (STATE, SCORER):
        before = ort.InferenceSession(str(args.source / name), providers=["CPUExecutionProvider"])
        after = ort.InferenceSession(str(args.target / name), providers=["CPUExecutionProvider"])
        scores = []
        for feed in random_feeds(args.source / name, 32, rng):
            x, y = before.run(None, feed)[0].ravel(), after.run(None, feed)[0].ravel()
            scores.append(float(x @ y / (np.linalg.norm(x) * np.linalg.norm(y) + 1e-12)))
        size = (args.target / name).stat().st_size / 1e6
        print(f"{name}: {size:.1f} MB, output cosine to FP32 min {min(scores):.4f} median {np.median(scores):.4f}")


if __name__ == "__main__":
    main()
