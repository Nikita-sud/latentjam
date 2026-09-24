#!/usr/bin/env python3
"""Rebuild the shipped audio encoder with a four-step FFT front end and INT8 weights.

The shipped graph computes its log-mel front end with a 1024-point DFT written as a convolution over
1,026 stored kernels: 1.05 GMAC per 10 s window, about a third of the encoder, and 4.2 MB. This tool
keeps every other operation and weight and replaces only that DFT. The same transform is computed as
two 32-point stages (Cooley-Tukey, 1024 = 32 x 32) of plain matrix products. The math is identical;
it needs ~0.2 GMAC and 0.3 MB and runs on the same multithreaded GEMM kernels as the network.

It then quantizes the encoder behind the front end to INT8: QDQ, per-channel weights, percentile-99.99
activation ranges from calibration windows the caller supplies. The front end stays float.

Calibration audio for the published graph: the first 10 s of 200 Free Music Archive tracks released
under CC BY, CC BY-SA, CC0 or public domain. No audio is stored in the model.

    python3 tools/research/rebuild_audio_encoder.py \\
        androidApp/src/main/assets/ml/mnv4_audio.onnx /tmp/mnv4_audio.onnx \\
        --calibration fma_calibration.npy        # int16 [N, 320000], mono, 32 kHz
"""
import argparse
import math
import tempfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F
from onnx import TensorProto, compose, numpy_helper
from onnxruntime.quantization import (CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType,
                                      quantize_static)

WINDOW_SAMPLES = 320_000
LOG_MEL = "/encoder/mel/Unsqueeze_1_output_0"   # [B, 1, 128, T]: where the shipped front end ends
FLOOR = 1.1920928955078125e-07                   # the shipped log floor
A, K2 = 32, 17                                   # N = A * A = 1024; onesided bins k1 + 32*k2 <= 512


def to_fp32(model: onnx.ModelProto) -> onnx.ModelProto:
    for tensor in model.graph.initializer:
        if tensor.data_type == TensorProto.FLOAT16:
            tensor.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(tensor).astype(np.float32), tensor.name))
    for node in model.graph.node:
        for attr in node.attribute:
            if attr.type == onnx.AttributeProto.TENSOR and attr.t.data_type == TensorProto.FLOAT16:
                attr.t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(attr.t).astype(np.float32), attr.t.name))
            if node.op_type == "Cast" and attr.name == "to" and attr.i == TensorProto.FLOAT16:
                attr.i = TensorProto.FLOAT
    for info in list(model.graph.value_info) + list(model.graph.input) + list(model.graph.output):
        if info.type.tensor_type.elem_type == TensorProto.FLOAT16:
            info.type.tensor_type.elem_type = TensorProto.FLOAT
    return model


class FourStepLogMel(nn.Module):
    """The shipped log-mel: reflect pad 512, 1024-point frames every 320 samples, power, mel, log."""

    def __init__(self, window: np.ndarray, mel_fb: np.ndarray):
        super().__init__()
        n = torch.arange(A, dtype=torch.float64)
        stage1 = 2 * math.pi * torch.outer(n, n) / A                                   # [n1, k1]
        self.register_buffer("w1", torch.cat([torch.cos(stage1), -torch.sin(stage1)], 1).float())
        twiddle = 2 * math.pi * torch.outer(n, n) / (A * A)                            # [n2, k1]
        self.register_buffer("tc", torch.cos(twiddle).float())
        self.register_buffer("ts", torch.sin(twiddle).float())
        stage2 = 2 * math.pi * torch.outer(n, torch.arange(K2, dtype=torch.float64)) / A   # [n2, k2]
        c2, s2 = torch.cos(stage2), torch.sin(stage2)
        self.register_buffer("w2", torch.cat([torch.cat([c2, -s2], 1), torch.cat([s2, c2], 1)], 0).float())
        mel = torch.zeros(A, K2, mel_fb.shape[0], dtype=torch.float64)
        for k1 in range(A):
            for k2 in range(K2):
                if k1 + A * k2 <= 512:
                    mel[k1, k2] = torch.from_numpy(mel_fb[:, k1 + A * k2].astype(np.float64))
        self.register_buffer("mel", mel.reshape(A * K2, -1).float())
        # Frame sample n = 32*n1 + n2; stage 1 contracts over n1, so frames are laid out [n2, n1].
        self.register_buffer("win", torch.from_numpy(window.astype(np.float32)).reshape(A, A).t().contiguous())

    def forward(self, waveform):                                          # [B, 320000]
        b = waveform.shape[0]
        x = F.pad(waveform[:, None], (512, 512), mode="reflect")[:, 0]   # [B, 321024]
        hops = F.pad(x, (0, 256)).reshape(b, 1004, 320)
        frames = torch.cat([hops[:, 0:1001], hops[:, 1:1002], hops[:, 2:1003], hops[:, 3:1004]], 2)[:, :, :1024]
        frames = frames.reshape(b, 1001, A, A).transpose(2, 3) * self.win            # [B, F, n2, n1]
        y = frames @ self.w1                                                         # [B, F, n2, re|im k1]
        yr, yi = y[..., :A], y[..., A:]
        zr = yr * self.tc + yi * self.ts                                             # x exp(-2 pi i n2 k1 / N)
        zi = yi * self.tc - yr * self.ts
        z = torch.cat([zr.transpose(2, 3), zi.transpose(2, 3)], 3)                   # [B, F, k1, re|im n2]
        spectrum = z @ self.w2                                                       # [B, F, k1, re|im k2]
        power = (spectrum[..., :K2] ** 2 + spectrum[..., K2:] ** 2).reshape(b, 1001, A * K2)
        return torch.log((power @ self.mel).clamp_min(FLOOR)).transpose(1, 2)[:, None]   # [B, 1, 128, F]


def session(path: str) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.log_severity_level = 3
    return ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])


def cosines(reference: str, candidate: str, windows: np.ndarray) -> np.ndarray:
    a, b = session(reference), session(candidate)
    out = []
    for w in windows:
        x = a.run(None, {"waveform": w[None]})[0][0]
        y = b.run(None, {"waveform": w[None]})[0][0]
        out.append(float(x @ y / np.linalg.norm(x) / np.linalg.norm(y)))
    return np.array(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("shipped", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--calibration", type=Path, help="int16 [N, 320000] windows; omit for an fp32 graph")
    args = parser.parse_args()

    work = Path(tempfile.mkdtemp())
    fp32 = to_fp32(onnx.load(args.shipped))
    inits = {t.name: numpy_helper.to_array(t) for t in fp32.graph.initializer}
    window = inits["encoder.mel.kernel_cos"][0, 0, :]          # k = 0 row of window * cos(2 pi k n / N)
    mel_fb = inits["encoder.mel.mel_fb"]
    onnx.save(fp32, work / "reference.onnx")
    inferred = onnx.shape_inference.infer_shapes(fp32)
    if LOG_MEL not in {v.name for v in inferred.graph.value_info}:
        inferred.graph.value_info.append(onnx.helper.make_tensor_value_info(LOG_MEL, TensorProto.FLOAT, [1, 1, 128, 1001]))
    body = onnx.utils.Extractor(inferred).extract_model([LOG_MEL], [o.name for o in fp32.graph.output])

    torch.onnx.export(FourStepLogMel(window, mel_fb).eval(), torch.zeros(1, WINDOW_SAMPLES), work / "front.onnx",
                      input_names=["waveform"], output_names=["logmel"], opset_version=17, dynamo=False)
    front = compose.add_prefix(onnx.load(work / "front.onnx"), "fft/", rename_inputs=False)
    front.ir_version = body.ir_version
    merged = compose.merge_models(front, body, io_map=[("fft/logmel", LOG_MEL)])
    opsets = sorted({(o.domain, o.version) for o in merged.opset_import})  # merge lists each model's opsets
    del merged.opset_import[:]
    merged.opset_import.extend(onnx.helper.make_opsetid(d, v) for d, v in opsets)
    onnx.checker.check_model(merged)
    onnx.save(merged, work / "fft_fp32.onnx")

    probe = np.random.default_rng(0).uniform(-0.5, 0.5, (8, WINDOW_SAMPLES)).astype(np.float32)
    if args.calibration:
        calibration = np.load(args.calibration).astype(np.float32) / 32767.0
        probe = calibration[:16]
    same = cosines(str(work / "reference.onnx"), str(work / "fft_fp32.onnx"), probe)
    print(f"four-step front end vs shipped: cos min {same.min():.6f}")
    if same.min() < 0.9999:
        raise SystemExit("the rebuilt front end does not reproduce the shipped one")

    if not args.calibration:
        onnx.save(merged, args.output)
        return

    class Reader(CalibrationDataReader):
        def __init__(self):
            self.windows = iter([{"waveform": w[None]} for w in calibration])

        def get_next(self):
            return next(self.windows, None)

    front_nodes = [n.name for n in merged.graph.node if n.name.startswith("fft/")]
    quantize_static(str(work / "fft_fp32.onnx"), str(args.output), Reader(), quant_format=QuantFormat.QDQ,
                    per_channel=True, weight_type=QuantType.QInt8, activation_type=QuantType.QUInt8,
                    nodes_to_exclude=front_nodes, op_types_to_quantize=["Conv", "Gemm", "MatMul"],
                    calibrate_method=CalibrationMethod.Percentile, extra_options={"CalibPercentile": 99.99})
    quantized = cosines(str(work / "reference.onnx"), str(args.output), probe)
    print(f"INT8 vs shipped on calibration windows: cos median {np.median(quantized):.4f}, min {quantized.min():.4f}; "
          f"{args.output.stat().st_size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
