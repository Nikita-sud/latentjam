#!/usr/bin/env python3
"""Convert the audio encoder to FP16, optionally retaining DSP in FP32.

The exported graph starts with a fixed waveform -> STFT -> mel front end. Those
kernels are signal-processing constants, not learned weights, and converting
them causes avoidable embedding drift. The first node that consumes a
``backbone.*`` or ``proj.*`` initializer marks the learned network boundary.
"""

from __future__ import annotations

import argparse
import copy
from pathlib import Path

import onnx
from onnxconverter_common.float16 import convert_float_to_float16


LEARNED_PREFIXES = ("backbone.", "proj.")
FRONTEND_INITIALIZER_PREFIX = "mel."


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--include-frontend",
        action="store_true",
        help="Also convert STFT/mel tensors; this reproduces the smaller production 960-d export",
    )
    return parser.parse_args()


def frontend_node_names(model: onnx.ModelProto) -> list[str]:
    """Return the topological prefix before the learned MobileNet begins."""
    blocked: list[str] = []
    for node in model.graph.node:
        if any(value.startswith(LEARNED_PREFIXES) for value in node.input):
            break
        if not node.name:
            raise ValueError("Every front-end node must have a name")
        blocked.append(node.name)
    if not blocked:
        raise ValueError("Could not find the fixed audio front end")
    return blocked


def main() -> None:
    args = parse_args()
    model = onnx.load(args.model.as_posix())
    blocked = [] if args.include_frontend else frontend_node_names(model)
    converted = convert_float_to_float16(
        model,
        keep_io_types=True,
        min_positive_val=1e-7,
        max_finite_val=1e4,
        node_block_list=blocked,
        disable_shape_infer=False,
    )
    if not args.include_frontend:
        # onnxconverter-common quantizes initializers even when their consuming
        # nodes are blocked. Restore the three STFT/mel tensors byte-for-byte.
        original_frontend = {
            value.name: value
            for value in model.graph.initializer
            if value.name.startswith(FRONTEND_INITIALIZER_PREFIX)
        }
        for index, value in enumerate(converted.graph.initializer):
            original = original_frontend.get(value.name)
            if original is not None:
                converted.graph.initializer[index].CopyFrom(copy.deepcopy(original))
        if len(original_frontend) != 3:
            raise ValueError(
                f"Expected three fixed mel initializers, found {len(original_frontend)}"
            )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(converted, args.output.as_posix(), save_as_external_data=False)
    onnx.checker.check_model(onnx.load(args.output.as_posix()))
    print(
        f"kept {len(blocked)} DSP nodes in FP32; wrote {args.output} "
        f"({args.output.stat().st_size} bytes)"
    )


if __name__ == "__main__":
    main()
