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

On a phone, `core/smart/src/androidDeviceTest/.../ModelBenchmarkDeviceTest.kt` times and weighs bundles
pushed into the test package's own files (`files/bench/<name>/`), never touching the installed app.

## Rejected (measured)

- Knowledge pack re-quantized to 8 sub-spaces: −1.5 MB, owner cold ΔP@10 −1.07 pp [−2.02, −0.16].
- Semantic head at 3 or 2 bits: mood agreement −0.6 pp.
- Music encoder at 3-bit CQ: owner history ΔP@10 −1.10 pp [−1.87, −0.33].
- Music encoder pointwise convolutions as Transpose → MatMulNBits → Transpose: 1.6–2× slower per window
  on a Galaxy S24 Ultra than the INT8 graph, for −3.5 MB.
