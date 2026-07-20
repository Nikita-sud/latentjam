# SMART model selection

Decision date: 2026-07-20

## Decision

Ship the 960-dimensional acoustic stack plus a 253 KB learned optional-text residual trained on both
real-history and exact first-open contexts. Do not ship
the 256-dimensional stack yet: on the exact 256 phone candidate pool its text-conditioned
end-to-end MRR is 0.04401, versus 0.06579 for the frozen 960 scorer on the same candidates.

The final 960 session-grouped evaluation improves history-aware end-to-end MRR from 0.06215 to
0.08555 (+37.7%) and first-open MRR from 0.06199 to 0.07837 (+26.4%). Missing text is an exact
fallback in both modes; genre words injected into titles are an exact no-op because titles are not
embedded.

Full methods, counterfactuals, limitations, teacher research, and mobile results are in
[`smart-model-final-report.md`](smart-model-final-report.md).

## Shipped bundle

| File | Contract | Bytes |
|---|---:|---:|
| `mnv4_audio.onnx` | 10 s mono 32 kHz → 960-d | 21,939,891 |
| `text_encoder_minilm.onnx` | tokens → 384-d | 22,972,370 |
| `predictor_state.onnx` | recent 960-d history → 960-d state | 11,917,220 |
| `predictor_scorer_n100.onnx` | state + 100 audio candidates → logits | 2,728,912 |
| `predictor_text_residual_n100_960.onnx` | base logits + optional text → logits | 253,566 |
| `text_vocab.txt` | WordPiece vocabulary | 231,508 |

Total: **60,043,467 bytes (57.3 MiB)** per platform. Audio and text graphs run while tracks are
indexed. Queue construction runs the state graph, frozen acoustic scorer, and residual. The app
progressively builds the local index on first launch, embeds the selected seed on demand, and keeps
playback's immediate random fallback until candidates are ready. All inference, history, and stored
embeddings remain on the device; iOS and Android both persist private history across launches.

## Metadata contract

Embed `genre; artist; year`, dropping blank fields. Never put title or filename text into the trusted
channel. Candidate retrieval interleaves anchor-audio, state-audio, and seed-text rankings instead
of using a hand-tuned cross-modal weight. The learned residual is bounded to ±0.75 and its mask makes
missing text bit-exact acoustic-only output.

## SMART behavior

Use a two-stage local system, not a single nearest-neighbour call: round-robin multi-channel
retrieval, learned candidate reranking, then list-level coherence/diversity rules. The state combines
the last four plays with completion/skip signals and 30/365-day taste centroids. Empty history maps
to an explicitly tested seed-only state, not zeros. With only 1,519 positives from one listener, the
small GRU is a safer production choice than SASRec/BERT4Rec; the next evidence target is candidate
pool recall (currently 53%), followed by contrastive sequence training after multi-listener data.

Research basis and rejected alternatives are detailed in the full report.

## Encoder status

MNv4 is the best encoder actually verified here, not a claim that a generic MNv4 is optimal for the
product. The LatentJam-specific recommendation architecture is already custom, but the waveform
trunk is still the incumbent. The next controlled experiment is a small UIB/Mobile-MQA audio trunk
trained end to end with relational teacher distillation, rhythm/harmonic auxiliary heads, and the
exact next-track candidate-pool loss. Compare native 256-, 384- and 512-d heads; the earlier failure
only rejects post-hoc 256-d compression. The concrete `LJ-Audio-S` contract and promotion gates are
in the full report.

## Teacher policy

Use large models offline only and promote them by the held-out next-track target:

- EfficientAT MN10: primary permissive acoustic teacher.
- Beat This and Basic Pitch: confidence-masked rhythm and pitch auxiliaries.
- Whisper: vocals/speech/language auxiliary only when confidence is high.
- Qwen2-Audio: Apache-2.0 offline structured labels or pairwise judgements; never a phone model or
  free-form descriptor source.
- MERT and MuQ published weights: excluded because their CC-BY-NC-4.0 terms do not fit the intended
  permissive release pipeline.

References checked on 2026-07-20:
[EfficientAT](https://github.com/fschmid56/EfficientAT),
[all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2),
[Beat This](https://github.com/CPJKU/beat_this),
[Basic Pitch](https://github.com/spotify/basic-pitch),
[Qwen2-Audio](https://huggingface.co/Qwen/Qwen2-Audio-7B-Instruct),
[MERT](https://huggingface.co/m-a-p/MERT-v1-330M),
[MuQ](https://huggingface.co/OpenMuQ/MuQ-large-msd-iter), and
[ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/).

## Asset hashes

```text
507626838393bb9132714fdb6740707a21f38bf56befa3dd826e91f420ef9b53  mnv4_audio.onnx
13c5f87437e57b52ceb455f7e75f9ab841aa3ca6fe987507974a30657122b1e7  predictor_state.onnx
adaa42bc3bd4b535d4ad2a49a348f3a5d9b41048c092650fedbd9fcc2a7457a6  predictor_scorer_n100.onnx
e02f2bc70576ce23ce8fb763c662922af5bed8d065c303937ec1b4b8b11d9080  predictor_text_residual_n100_960.onnx
afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1  text_encoder_minilm.onnx
```
