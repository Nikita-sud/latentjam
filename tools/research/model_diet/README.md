# Model diet tools

How the on-device models were made smaller without changing what LatentJam does (October 2026). The
plan, the quality bar and every measurement are in `docs/model-diet-plan.md`; candidates are judged by
the bundle report, which lives outside the repository with the evaluation libraries
(`$MODEL_DIET_WORK/report`, see `paths.py`).

| Asset | Tool | Format |
|---|---|---|
| `music_entities_250k.bin` | `../compact_music_entities.py` | LJENT3: same keys and ids as LJENT2, 40-bit keys, varint ids (lossless) |
| `artist_knowledge.bin` | `../compact_knowledge_pack.py` | version 3: only the confident records the app reads (lossless) |
| `artist_adapter.bin` | `../compact_artist_adapter.py` | LJADPT v2: 4-bit CQ (Walsh–Hadamard-rotated groups, Lloyd-Max codebook) |
| `predictor_scorer_n100.onnx`, `predictor_state.onnx`, `universal_semantic_head.onnx` | `qat_nets.py` then `to_stock.py` | 4-bit MatMulNBits (stock ONNX Runtime), quantization-aware distillation |
| `text_encoder.onnx` | `to_stock.py` on the shipped student (embeddings only) | 4-bit GatherBlockQuantized embeddings + the INT8 layers |
| `mnv4_audio.onnx` | `qat_audio.py` then `make_q4_encoder.py` | pointwise convolutions and projection: symmetric 4-bit on `latentjam.Q4Conv1x1` (core/ort-ops), a learned-clipping scale per output channel for the 44 over time and frequency, bf16 scales per block of 32 for the two that run once per window; the rest INT8 as before |

## Pipeline for the networks

1. `train_libraries.py` builds MPD libraries from playlists the evaluation libraries never drew (it first
   reproduces the six evaluation libraries exactly, so the exclusion is real).
2. `train_seeds.py` and `record.py` run the app's own SMART chain over them, cold and with synthetic
   listening histories, with the runner's worker recording every state-net and scorer call
   (`SMART_DUMP`, `SMART_DUMP_LIBRARY`).
3. `nets.py` holds PyTorch twins of the scorer, the state net and the semantic head, exact against
   their ONNX files; `qat_nets.py` distills each float teacher into a student whose matrices round
   straight-through to `cq.UQ` (MatMulNBits' own blocks: 32 weights, FP16 scale, 4-bit zero point) or
   `cq.CQ`. `qat_text.py` does the same for the text student. `sensitivity.py` measures post-training
   sensitivity per matrix first.
4. `to_stock.py` turns a uniform-block stand-in into the real graph (MatMulNBits / GatherBlockQuantized,
   `accuracy_level` 4 for int8 compute); `to_nbits.py` holds the packing, checked bit-exact against
   ONNX Runtime for 2, 4 and 8 bits.
5. `make_candidate.py` assembles a candidate bundle for the bundle report.

## The music encoder

The encoder is a convolution network: stock ONNX Runtime has no 4-bit convolution, and rewriting its
pointwise convolutions as MatMulNBits between transposes runs 1.6-2x slower on a phone. So it gets its own
operator, `latentjam.Q4Conv1x1` in `core/ort-ops` (C API only; KleidiAI's int8 kernels where the CPU has i8mm
or dotprod, its own NEON or SSSE3 code elsewhere), on uint8 NHWC activations so ONNX Runtime keeps running
everything else in INT8.

1. `qat_audio.py` trains the 4-bit weights against the float encoder (lq4c+sq4b32: a scale per channel,
   whose kernel is as fast as INT8 on a phone, with a clipping range learned per channel; blocks of 32 for
   conv_head and the projection, which run once per window).
2. `make_q4_encoder.py` quantizes the rest to INT8 as before, lets ONNX Runtime lay the graph out NHWC, and
   swaps the 46 pointwise layers for the operator; `q4pack.py` packs the weights exactly as KleidiAI's
   packer does (checked byte for byte).
3. The app registers the operator: Android loads `libljq4.so` (built by Gradle with CMake) and registers it
   by name; iOS links the `LatentJamOrtOps` pod and calls `LjRegisterOrtOps`.

On a phone, `core/smart/src/androidDeviceTest/.../ModelBenchmarkDeviceTest.kt` times and weighs bundles
pushed into the test package's own files (`files/bench/<name>/`), never touching the installed app.

## Rejected (measured)

- Knowledge pack re-quantized to 8 sub-spaces: −1.5 MB, owner cold ΔP@10 −1.07 pp [−2.02, −0.16].
- Semantic head at 3 or 2 bits: mood agreement −0.6 pp.
- Music encoder at 3-bit CQ: owner history ΔP@10 −1.10 pp [−1.87, −0.33].
- Music encoder pointwise convolutions as Transpose → MatMulNBits → Transpose: 1.6–2× slower per window
  on a Galaxy S24 Ultra than the INT8 graph, for −3.5 MB.
- Music encoder at 4 bits with one scale per output channel, clipped at max |w|: semantic head top genre
  −1.0 pp (gate 0.5); with blocks of 32 for conv_head and the projection, owner history with phase 1's
  assets ΔP@10 −0.53 pp [−0.99, −0.11] over 1,073 seeds. The learned clipping range fixed both.
- Music encoder with blocks of 32 everywhere: +19–24 % per window on a Galaxy S24 Ultra (KleidiAI's
  block-scaled 4-bit matmul costs about 1.8× its int8 one); blocks of 64 or 128 still +45 % in the matmul.
- Quantization-aware training that also simulates the INT8 activations: 0.9985 with them simulated, but
  the graph needs the ranges the training learned; with the converter's own calibration it falls to 0.956.
- Walsh-Hadamard rotation before 4-bit rounding: helps before training, hurts after it (val cos 0.9971
  rotated against 0.9989 plain, blocks of 32).
