# Model diet: the best quality per megabyte (plan, 2026-10-04)

Goal: make LatentJam's on-device models as small as possible **and** better, spending whatever compute
that takes. Decisions taken with the owner on 2026-10-04:

- **One library re-index is acceptable** when the gain is clear (bump `modelVersion`).
- **Compressed weights run natively** through our own ONNX Runtime operator (Apache-2.0, Android and
  iOS, built from source), not by unpacking at first launch.
- **Compress and improve**: stronger offline teachers distilled into small on-device students are in
  scope, not only shrinking today's models.

Nothing leaves the device at run time: teachers run offline, students ship (the on-device-only rule).

## What ships today (Android assets, origin/main = 0.7.1)

| Asset | Size | What it is |
|---|---:|---|
| `music_entities_250k.bin` | 11.4 MB | artist/entity table for recognition and aliases |
| `mnv4_audio.onnx` | 10.3 MB | music encoder, INT8, 9.8M params, 960-d vectors |
| `artist_knowledge.bin` | 7.5 MB | artist descriptors, PQ-16 |
| `predictor_scorer_n100.onnx` | 5.8 MB | SMART scorer, fp16, 3.0M params |
| `text_encoder.onnx` | 5.0 MB | MiniLM student (text-v3 strings), INT8, 5.1M params |
| `predictor_state.onnx` | 4.0 MB | SMART state net, INT8, 3.0M params |
| `universal_semantic_head.onnx` | 2.6 MB | genre/mood head, fp16, 1.3M params |
| `artist_adapter.bin` | 1.1 MB | artist adapter |
| **Total** | **47.6 MB** | 28.7 MB networks + 18.9 MB tables |

## What is already proven (2026-10-04, `smart-bench-2026-09-24/audio_student/`)

Cactus CQ weights (Hadamard rotation + Lloyd-Max codebook + fp16 group norm, the format behind
Whistle's 17 MB) with quantization-aware training, 8 epochs on 11,456 CC FMA clips, one A40, ~$0.42:

| Music encoder | Size | cos to float | SMART ΔP@10 | top genre (all / confident) |
|---|---:|---:|---:|---:|
| INT8 (shipped) | 10.7 MB | 0.9978 | +0.40 | 97.5 % / 99.9 % |
| QAT 4-bit g32 | ~6.0 MB | 0.9982 | +0.20 (noise) | 98.1 % / 100 % |
| QAT 2-bit g32 | ~3.5 MB | 0.9901 | +0.16 (noise) | 94.7 % / 99.1 % |

Post-training CQ (no training) breaks this conv net; QAT is required. Longer training at the same
learning rate got worse (20 epochs), so the schedule needs tuning, not just more epochs.

## Quality bar

A change ships only if, against today's app, every one of these is within noise or better:

1. SMART P@10 on the six MPD libraries (`check_student_all.py`, 900 seeds, paired, 95 % CI).
2. Listener metrics on the owner's own history (cold / history), as in the 2026-09-24 rounds.
3. Semantic head: top genre and mood agreement, and the calibrated Electronic / Hip-Hop decisions.
4. Text search and text vectors: the text-student evaluation (`text_student/evaluate_wide.py`) and
   search hit rate on the owner's library, including Russian and Romanian queries.
5. Artist recognition and knowledge-pack metrics for anything touching the tables.
6. On device: load time, embedding latency and peak memory on the Pixel 7 Pro emulator and the phone,
   not worse than today by more than 10 %.

Size is counted as shipped bytes in the APK, plus resident memory at run time.

## Phases (each ends in a measured go/no-go)

**0. One bundle report (½ day, local).** A single command that prints every asset's shipped size
and every metric above, runnable on the Mac at low parallelism or on a GPU pod. All later phases
report through it.

*Done 2026-10-04:* `~/Documents/LJ/model-diet-2026-10-04/` (README there),
`cd report && python3 report.py ../candidates/<name>` → `reports/<name>.md`. Baseline frozen from
origin/main 0.7.1; features and queues come from the app's own Kotlin (compiled from a `git archive`
of 0.7.1); the teachers (float music encoder, MiniLM) serve as a reference. 0.7.1 against itself
gives exactly zero everywhere. A new music encoder costs about 8 minutes on the Mac, a cached
report 5 seconds. Measured 0.7.1 sizes: 50.0 MB in memory, 40.9 MB in a release APK (assets are
stored deflated). First candidate, the 4-bit CQ QAT music encoder: −4.7 MB in memory, −2.9 MB
in the APK, every gate passes (six MPD ΔP@10 −0.17 [−0.59, +0.23], owner cold +0.63, history
−0.27, head closer to the teacher's labels: top genre 97.4 → 98.2 %).

**1. Lossless shrink of what ships (1–2 days, ~$5 GPU).** QAT with CQ for every network (music
encoder done; SMART scorer and state nets, text encoder, semantic head, artist adapter), with a
per-layer bit allocation (2, 3 or 4 bits by measured sensitivity) and a fixed training schedule.
Tables: tighter product quantization (OPQ / residual codebooks) for the knowledge pack, and a
compact encoding for the entity strings. Gate: every metric within noise, vectors compatible, so no
re-index. Expected: 47.6 → roughly 26–30 MB.

*Status 2026-10-04 (evening):* the combined phase-1 candidate (`reports/phase1-all.md`) passes
every gate at 25.6 MB in memory (−49 %) and 24.4 MB in the APK (−40 %):

| Asset | 0.7.1 | Phase 1 | How |
|---|---:|---:|---|
| entity index | 11.92 | 8.40 | LJENT3: 40-bit keys, varint id lists; resolves identically |
| knowledge pack | 7.90 | 4.04 | version 3: only the 189,669 confident entities the app reads; identical answers |
| music encoder | 10.75 | 6.09 | CQ 4-bit g32 QAT (closer to the float teacher than INT8) |
| scorer | 6.06 | 1.73 | CQ 4-bit QAT on recorded chain inputs; 98.7 % identical top-10 |
| text encoder | 5.19 | 2.88 | CQ 4-bit QAT (embeddings + layers) |
| state net | 4.22 | 1.32 | CQ 3-bit QAT incl. the GRU (was fp32 inside the INT8 file) |
| semantic head | 2.68 | 0.77 | CQ 4-bit QAT (3- and 2-bit lose 0.6 pp mood agreement) |
| artist adapter | 1.18 | 0.33 | LJADPT version 2, CQ 4-bit, decoded in Kotlin at load |

Rejected: pack re-quantized to PQ-8 (−1.5 MB more; owner cold ΔP@10 −1.07 [−2.02, −0.16]).
Table formats, readers and tests: branch `feat/model-diet` (worktree `~/Documents/LJ/latentjam-diet`).
The networks are measured as dequantized stand-ins; shipping them is phase 2. Stock ONNX Runtime
already has `MatMulNBits` (2, 4, 8 bits, verified bit-exact against our packing), which may cover the
MatMul-shaped nets (scorer, state, head, text) without a custom operator; the uniform-vs-CQ QAT
comparison decides it.

*Phase 1 closed (2026-10-04 night, `~/Documents/LJ/model-diet-2026-10-04/phase1/README.md`):* the bundle of
real files that runs on STOCK ONNX Runtime (`reports/phase1-stock.md`) passes every gate at 32.5 MB in memory
and 28.2 MB in the APK: lossless tables + CQ adapter (app code on the branch), and MatMulNBits 4-bit head,
state net and scorer plus 4-bit text embeddings (GatherBlockQuantized) — no custom operator. Only the music
encoder (CQ 4-bit, −4.7 MB) still needs our own operator; 3-bit fails the owner-history gate. Pod cost ≈ $0.70.

*Stock-operator findings (Mac M-series, one thread, ORT 1.24.3; phones may differ):* uniform 4-bit
QAT (MatMulNBits' own format, fp16 scales, zero points) matches CQ 4-bit on the head and the state net,
and real stock files of both pass every gate (head 0.82 MB, state 2.33 MB with its GRU kept fp16).
GatherBlockQuantized (4-bit embeddings) is bit-exact against our packing. Speed is the catch:
with int8 compute (`accuracy_level` 4) the state net runs 0.110 ms against 0.082 ms (INT8) and the head
0.079 against 0.067 ms, but the scorer 4.2 ms against 1.2 ms (fp32 SGEMM over weights the fp16 file
expands at load, 11 MB of RAM), and the music encoder with its 45 pointwise convolutions rewritten as
Transpose → MatMulNBits → Transpose 43–49 ms against 19 ms per window. So: stock operators for the
small nets and the text encoder; the scorer and the music encoder need either a faster kernel (our own
operator, NCHW-native for convolutions) or an on-device measurement showing the phone is
memory-bound enough for 4-bit weights to pay for themselves.

**2. Our own ONNX Runtime operator (2–3 days, local + emulator/phone).** `CqMatMul` / `CqConv1x1`
in C++: rotate the input once per group with a fast Walsh-Hadamard transform, then multiply by the
packed 2/3/4-bit codebook weights with NEON int8 dot products, weights staying compressed in memory.
A graph rewriter turns a float ONNX + CQ weights into the shipped model. Registered in the ORT the
app already uses (Android via the AAR's C API, iOS via the xcframework). Gate: outputs identical to
the dequantized reference (cos ≥ 0.9999) and on-device latency within 10 % of today. This is also
what lets Whistle run without Cactus's closed engine.

*Status 2026-10-05 (branch `feat/model-diet`, worktree `~/Documents/LJ/latentjam-diet`, uncommitted):*
everything that runs on stock ONNX Runtime is in the app (`phase1-stock`: lossless tables, CQ adapter,
4-bit head / state net / scorer, 4-bit text embeddings); the music encoder is still 0.7.1's INT8.

- Release APKs (R8, the releases' debug key, reduced arm64 ORT rebuilt for the new operators):
  arm64 56,808,169 → 44,358,753 bytes (54.2 → 42.3 MiB, −21.9 %), armv7 64,562,335 → 51,883,543
  (61.6 → 49.5 MiB). The reduced runtime grew 0.23 MB (11.96 MB) for MatMulNBits and
  GatherBlockQuantized; against stock 1.26.0 its outputs are bit-identical on the phone and the
  emulator (47,047 floats, 21 cases incl. three real tracks; error and recovery cases pass).
- Phone (Galaxy S24 Ultra, one thread, median of 21 warm runs, same session, stock runtime; the
  reduced runtime measured the same):

  | | 0.7.1 | diet |
  |---|---:|---:|
  | scorer, one call | 17.9 ms | 14.6 ms |
  | state net | 0.38 ms | 0.43 ms |
  | semantic head | 0.47 ms | 0.42 ms |
  | text encoder | 0.65 ms | 0.67 ms |
  | music encoder (unchanged), 10 s window | 62 ms | 62 ms |
  | tables, first parse (entities / pack / adapter) | 68 / 51 / 53 ms | 22 / 58 / 29 ms |

  Two items miss the 10 % bar on their own (state net +55 µs per call, pack +6.5 ms once) while the
  operations they belong to got faster: a SMART step (state + scorer) 18.3 → 15.0 ms, the three
  tables 172 → 109 ms. MatMulNBits with fp32 compute (`accuracy_level` 1) is 2.5–3.5× slower, so the
  int8 compute stays.
- **Resident memory is mostly ONNX Runtime's memory arena, not weights.** An idle session keeps every
  activation buffer it ever needed: music encoder 79.5 MiB, scorer 17.7 MiB (diet bundle, all five
  sessions 112 MiB; 0.7.1 128 MiB). With the arena off (`setCPUArenaAllocator(false)`, iOS
  `DisableCpuMemArena`) they hold 14.8 and 2.2 MiB (all five 28.9 MiB), outputs bit-identical (CRC over every output), warm
  runs equally fast in alternating runs; only a session's first run is 3–20 ms slower. Now on the
  branch: `createOrtSession` in core:smart androidMain, one line in `IosOnnxInferenceProvider.swift`
  (simulator footprint after the smoke 167 → 152 MB).
- Checks: host tests, the emulator device tests (SmartInferenceDeviceTest), the iOS simulator smoke
  (`semantics=27`), and an emulator run of the app (851 tracks, 17 mixes, no errors) pass.
- The text encoder's outputs differ between the phone and the Mac (pooled cos ≈ 0.998) equally for
  0.7.1 and the diet model: the Mac's runtime takes other int8 kernels (SME). Bench text metrics
  carry that caveat for baseline and candidate alike.

Open: the music encoder (CQ 4-bit QAT passes every quality gate, −4.7 MB raw / −2.9 MB in the APK).
Running it natively needs our own operator, and the stock route (INT8 graph with MatMulNBits
pointwise convolutions) takes 103 ms per window on the phone against 62 ms. With the arena off its
weights are about 10 of the session's 15 MiB, so native 4-bit execution would save about 4–5 MB of RAM
while the encoder is loaded; storing 4-bit codes and building the INT8 weights at load would keep
the APK saving at today's speed. *Decision 2026-10-05 (owner): build our own operator.*

*Our own operator, 2026-10-05:* `latentjam.Q4Conv1x1` (module `core/ort-ops`) runs the encoder's 45
pointwise convolutions and its projection with 4-bit weights; ONNX Runtime keeps everything else in INT8.

- Format by quantization-aware training on a pod (11,456 CC-licensed FMA tracks, 8 epochs, $0.20),
  validation cosine to the float encoder: symmetric 4-bit in blocks of 32 inputs with a bf16 scale
  **0.9989**; one scale per output channel 0.9979; Cactus CQ 4-bit 0.9979; INT8 (0.7.1) 0.9978. A
  Walsh-Hadamard rotation helps before training and hurts after it (0.9971 / 0.9945).
- Kernels: Arm KleidiAI's int8 kernels (Apache-2.0, vendored): i8mm straight from the packed weights the
  model carries, dotprod from a copy repacked at first use. Elsewhere (older 64-bit Arm, armv7, x86_64) the
  operator's own NEON or SSSE3 code multiplies weights decoded once to int8; 16-bit lanes sum 128 products
  of a 4-bit weight before widening. On the S24 Ultra's X4 / A720 / A520 cores that path takes 62 / 111 /
  347 ms per window for the 44 per-channel layers, against 164 / 307 / 905 ms for the plain loop it
  replaced and 22 / 39 / 95 ms for the dotprod kernels. All three give the same embedding on the device
  (`OrtOperatorsDeviceTest`; bitwise on the S24), and `ljq4_test` checks every path the host has against
  exact sums (x86_64 under Rosetta). C API only, version 16.
- Graph: ONNX Runtime's own NHWC layout of the INT8 graph, the 46 layers swapped
  (`tools/research/model_diet/make_q4_encoder.py`); uint8 in and out, so no transposes and no float
  passes around the operator.
- Bundle report on the shipped graph (`audio-q4-sq4b32`): every gate passes (six MPD ΔP@10 −0.23
  [−0.64, +0.17], owner cold −0.24, history +0.13; head top genre −0.3 pp, mood +0.5 pp); the track
  vectors sit at cosine 0.998 to 0.7.1's, the level of the 0.7.0 INT8 rebuild, so the model version (and
  the index) stays. One scale per channel fails the head gate (top genre −1.0 pp).
- Size: encoder 10.75 → 6.28 MB in memory, 8.94 → 5.25 MB in the APK (−3.69 MB), plus libljq4.so
  0.16–0.28 MB per ABI. Mac, one thread: 21 ms per window against 22 ms for INT8. Loads in 28–44 ms
  instead of ~115 ms (the graph is already optimized).
- Integration: Android loads `libljq4.so` from the APK and registers it by name (CMake through Gradle,
  NDK 28.2.13676358 pinned, so the F-Droid recipe needs that NDK); iOS links the `LatentJamOrtOps` pod and
  calls `LjRegisterOrtOps`. Pending: the phone's timing gate (S24 Ultra, i8mm).

**3. Better and smaller (3–5 days, ~$30–80 GPU).** Stronger offline teachers, chosen by measured
SMART and listener quality and by licence (only teachers whose licence allows distilling into an
Apache-2.0 app):
- music: candidate large audio models scored offline on the bench, the best distilled into a small
  student (MobileNet family or one with Whistle-style Hadamard MLPs) with QAT at 2–4 bits;
- text: a multilingual sentence model as teacher (better Russian and Romanian queries), student
  re-distilled with QAT;
- downstream: semantic head and SMART nets retrained on the new vector space.
Gate: end-to-end metrics better than today at clearly smaller size. Adopting it triggers the one
re-index.

**4. Integration (1–2 days).** A branch with the new assets and the operator, `modelVersion` bump if
phase 3 lands, parity fixtures and device tests updated, APK size measured per ABI, and an F-Droid
build check (the operator builds from source with CMake). Merging stays the owner's call.

## Expected outcome (hypothesis, to be measured)

47.6 MB → about 15–20 MB of models with equal or better quality, compressed weights also in memory.

## Spending and safety

- GPU: about $40–90 in total, pods terminated as soon as results are downloaded; anything beyond
  ~$100 is asked first. Mac jobs run at low parallelism.
- No change reaches `main` or a release without the owner; work happens on branches and in the
  bench folders.
- Teacher licences are checked before any distillation; anything non-commercial is excluded.
