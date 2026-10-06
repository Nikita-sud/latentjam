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
| `predictor_scorer_n100.onnx`, `predictor_state.onnx`, `universal_semantic_head.onnx` | `qat_nets.py` (the SMART nets with `--student-features`, to read the pruned encoder) then `to_stock.py` | 4-bit MatMulNBits (stock ONNX Runtime), quantization-aware distillation |
| `text_encoder.onnx` | `to_stock.py` on the shipped student (embeddings only) | 4-bit GatherBlockQuantized embeddings + the INT8 layers |
| `mnv4_audio.onnx` | `qat_audio.py` (`p50_lq4c+sq4b32`: pruned to half its expanded channels) then `make_q4_encoder.py` | pointwise convolutions and projection: symmetric 4-bit on `latentjam.Q4Conv1x1` (core/ort-ops), a learned-clipping scale per output channel for the 44 over time and frequency, bf16 scales per block of 32 for the two that run once per window; the rest INT8 as before |

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
   conv_head and the projection, which run once per window). A `pNN_` prefix prunes first: each inverted-
   residual block keeps NN % of its expanded channels and conv_head NN % of its hidden units, those with the
   largest mean activation on 64 training windows times the norm of the weights that read them (a multiple of
   16, written to `prune_<cfg>.json` for the export). The shipped encoder is `p50_lq4c+sq4b32` after 40
   epochs on the Free Music Archive tracks under CC BY, CC0 or public domain (no share-alike or
   no-derivatives licence): the id list replaces `commercial_ids.txt` before `fetch_fma.py`.
2. `make_q4_encoder.py` quantizes the rest to INT8 as before, lets ONNX Runtime lay the graph out NHWC, and
   swaps the 46 pointwise layers for the operator; `q4pack.py` packs the weights exactly as KleidiAI's
   packer does (checked byte for byte).
3. The app registers the operator: Android loads `libljq4.so` (built by Gradle with CMake) and registers it
   by name; iOS links the `LatentJamOrtOps` pod and calls `LjRegisterOrtOps`.

A new encoder changes the space the SMART nets read. `qat_nets.py --student-features <the new bundle's
features>` retrains them on the same recordings with every music vector moved into the new space (tracks row
for row, the history means through a least-squares map). State outputs must also be in the retrieval
space: the explicitly selected `--state-target-map dual` query target is `old_query @ lstsq(new_tracks, old_tracks).T`, normalized, which
preserves dot products for a linear change of coordinates. This is an approximation for a nonlinear
encoder and must pass the full queue benchmark. Both tested transfers regressed owner-history queues;
there is therefore no default map when moving to a new space. `--state-target-map identity` keeps the
targets in 0.7.1's coordinates: the shipped p50 nets, whose encoder sits at cosine 0.988 to 0.7.1's. `--state-target-map forward` exists only to reproduce
the rejected first prototype. The scorer requires `--student-state <float exported new state.onnx>` and
trains with that model's outputs rather than the recorded old-coordinate states.

The semantic head requires `--head-pairs <pairs.npz>` when `--student-features` is supplied.
`prepare_head_pairs.py` builds paired embeddings from licensed FMA training/validation tracks, retaining
the official split and excluding test tracks. Selection uses the normalized logit error across all
AudioSet and FMA classes; Music argmax agreement is retained only as a diagnostic. Epoch zero is eligible
as the best model. The stored pair ids and validation mask are part of the experiment provenance.
The model version then changes (`AppGraph.kt`, `embedding_version.txt`) and the app re-indexes the library.

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
- The old AQ export discarded the learned activation ranges and fell to 0.956. The 2026-10-06 fix stores
  `ActQ.seen` in checkpoints, restores legacy observers, and passes learned scales and zero points from
  ONNX metadata into the converter. Numerical export verification passed on 32 windows (mean cosine
  0.99955, minimum 0.99859, 97 exact retained grids). This is an export check, not evidence that AQ improves
  retrieval or phone latency.
- Walsh-Hadamard rotation before 4-bit rounding: helps before training, hurts after it (val cos 0.9971
  rotated against 0.9989 plain, blocks of 32).
- EfficientAT students (`student_audio.py`: mn05 / mn10, MobileNetV3) in place of the encoder: their
  hard-swish and squeeze-excitation layers do not survive the graph's uint8 activations (owner library vectors
  at cosine 0.912 to the teacher for mn10, 4.7 MB); ReLU and no squeeze-excitation reach 0.965 (mn10, 3.0 MB)
  and 0.952 (mn05, 1.4 MB), against 0.989 for the pruned encoder at 3.35 MB.
- Pruning deeper than half: keeping 38 % or 25 % of the expanded channels (2.72 / 2.06 MB) puts the vectors at
  cosine 0.981 / 0.969 to the teacher, against 0.987 for half after the same 20 epochs.


## Audit fixes and bounded experiments (2026-10-06)

See `docs/model-diet-fixes-2026-10-06.md` for measurements, rejected candidates and reproducibility.
Do not promote a checkpoint because teacher cosine alone improves.

- `cache_audio_distillation.py` caches one deterministic crop and teacher target per training track,
  excludes evaluation-library paths, and keeps FMA's official training/validation separation.
- `distill_audio_cached.py` runs a bounded local teacher-assistant experiment with a fixed validation
  set and saves checkpoint hashes, selected track ids and every epoch, including epoch zero.
  It uses one fixed crop per track; it does not replace the augmented multi-crop training recipe.
  `--aq` fine-tunes with the graph's INT8 convolutions and uint8 activations simulated, starting from a
  checkpoint trained without them; export with `AQ=1` so the converter keeps the learned ranges.
- `verify_audio_export.py` compares a restored checkpoint, with fake quantization active when requested,
  to the final custom-operator ONNX. Run with the audio-student source directory on `PYTHONPATH` for
  `common.py`; the source, checkpoint, pruned channel indices, calibration PCM and host operator library
  are explicit arguments. The numerical check must precede downstream quality measurements.
- `tests/test_training_contracts.py` guards observer restoration, head selection, query-coordinate
  transforms and scorer inputs. Run with `python -m pytest tools/research/model_diet/tests`.
- The external bundle report now labels confidence intervals crossing zero `inconclusive`. Absence of a
  significant decline does not establish equivalence. Its patch is retained in the experiment directory.

The audio helpers require the same ONNX, onnx2torch, PyTorch and ORT dependencies as `qat_audio.py`.


New-space training now saves `split-audit.json`. Use `--require-disjoint-tracks` to reject overlapping
training/evaluation catalogs or missing evaluation evidence. The inherited SMART catalogs are not
track-disjoint from MPD evaluation, while the new audio-cache paths are disjoint. See the audit
report before interpreting a whole-queue score as unseen-track generalization.


For continuation experiments use `replay_continuation.py`, with explicit `--benchmark`, `--source`,
`--prepared`, `--assets`, `--seeds`, `--mode history|cold` and a new `--out` directory. `--legacy`
selects the unchanged control; `--length` defaults to 20. It builds the real Kotlin sources in its
own output directory and records model, feature, seed and source hashes. Do not assume that the
bundle report's `--src` rebuilds SMART: it controls feature extraction only.

The experimental `ChainTuning.continueAfterExhaustion` remains off by default. It refills candidates
from the entire eligible library, exhausts the active neighborhood before moving on, and uses the
same fixed-100 scorer shape. See `docs/model-diet-fixes-2026-10-06.md` for continuity measurements,
the user-clarified objective, the reduction in artist diversity on MPD, and deployment limitations.
