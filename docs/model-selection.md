# SMART model selection

Decision date: 2026-07-20

## Shipped bundle

LatentJam should ship **four ONNX files serving three logical roles**:

| Role | File | Contract | Size |
|---|---|---:|---:|
| Audio encoder | `mnv4_audio.onnx` | 10 s mono at 32 kHz → 960-d | 24.18 MB |
| Metadata encoder | `text_encoder_minilm.onnx` | WordPiece tokens → 384-d | 22.97 MB |
| Session encoder | `predictor_state.onnx` | recent 960-d history → 960-d state | 11.92 MB |
| Candidate scorer | `predictor_scorer_n100.onnx` | state + 100 candidates → 100 scores | 2.73 MB |

The text vocabulary adds 0.23 MB. The ONNX bundle is 61.79 MB; including the vocabulary it is
62.03 MB. The previous bundle was 86.24 MB including its 4.96 MB static descriptor table. This
change removes 24.21 MB (28.1%).

Four files are intentional. Audio and metadata are indexed at different times and have different
input pipelines. The state encoder and scorer are one logical recommender split into two graphs so
the state is computed once while the fixed candidate pool is rescored at each hop. Merging them
would make deployment less flexible without removing a meaningful amount of work.

## Audio encoder decision

The selected encoder is the existing MobileNetV4-Conv-M student of EfficientAT MN10. Its fixed
STFT/mel tensors remain FP32; learned backbone and projection tensors are FP16. Input/output stay
FP32, so this is a drop-in replacement for the persisted 960-d contract.

72-track curated retrieval benchmark, library-mean-centred space:

| Candidate | Size | Language purity@5 | Genre purity@5 | Artist MRR | Decision |
|---|---:|---:|---:|---:|---|
| Existing MNv4 960 FP32 | 43.43 MB | 0.6056 | 0.5397 | 0.6457 | baseline |
| **MNv4 960 mixed precision** | **24.18 MB** | **0.5861** | **0.5238** | **0.6600** | **ship** |
| MNv4 256 full-head FP16 | 19.75 MB | 0.5889 | 0.5429 | 0.6712 | next contract, not drop-in |

The 256-d model is the best endpoint, but it cannot be shipped honestly until the session encoder,
candidate scorer, persisted index and parity fixtures are retrained/migrated to 256 dimensions.
Projecting only the audio output would put the predictor outside its training distribution. The
backbone dominates file size, so the immediate model saving is only 4.43 MB; the larger 256-d win is
smaller recommender graphs and 73% less per-track audio-index storage.

The old phone-side research measured the 960-d student at about 7 ms with Qualcomm QNN, versus
393 ms for its MN10 teacher. The Apache clean-room app currently uses ONNX Runtime CPU and has no
QNN execution-provider binding yet. RunPod timings are useful for regression checks, not phone
latency claims; a real-device QNN/thermal gate remains required before advertising a latency.

## Why the descriptor table was removed

`semantic_descriptors.bin` encoded descriptions generated for one library on a Mac. It could not
represent a newly imported track and therefore made behaviour depend on whether a song happened to
exist in that private catalogue. A curated audit also found free-form audio-LLM descriptions
hallucinating geography, era and scene (for example Romanian estradă described as Bollywood or
Italian folk). Incidental clustering gains on one library do not make that a general model.

The production chain now uses the int8 MiniLM embedding generated from
`genre; artist; title; year` on the device for every track. On the 18-anchor chain harness,
descriptor-free MiniLM at the existing 1× previous/2× seed z-weights retained essentially the same
exact-genre coherence as the combined table (0.6806 versus 0.6852). Raising its global weight fixed
more Romanian anchors but caused R&B→phonk and Russian-rap→generic-Russian collapse, so the weights
were not increased.

## Teacher candidates

Teachers run only during training or evaluation. No teacher, track, prompt or generated descriptor
is needed by the app.

| Candidate | Best use | Result / constraint |
|---|---|---|
| **EfficientAT MN10** | primary acoustic teacher | Best tested target; current MNv4 student reaches 92% of its playlist purity. EfficientAT is MIT. |
| Beat This | beat/downbeat auxiliary targets | MIT code and weights; useful for rhythm, not a universal embedding. |
| Spotify Basic Pitch | pitch/chroma/melody auxiliary targets | Apache-2.0; useful for harmonic structure, not a universal embedding. |
| Whisper | vocals, speech/language confidence | Useful only when speech is confidently present; silence/instrumentals must be masked. |
| Qwen2-Audio | structured offline tags or pairwise judgements | Apache-2.0, but free-form descriptions failed the factual audit. Never use unverified prose as a feature. |
| MERT-v1-95M/330M | music SSL comparison | Both tested below the current encoder; published weights are CC-BY-NC-4.0. Exclude from an Apache/permissive release pipeline. |
| MuQ-large-msd | music SSL comparison | Tested below the current encoder; weights are CC-BY-NC-4.0. Exclude. |

Relevant upstream licences: [EfficientAT](https://github.com/fschmid56/EfficientAT),
[all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2),
[Beat This](https://github.com/CPJKU/beat_this),
[Basic Pitch](https://github.com/spotify/basic-pitch),
[Qwen2-Audio](https://huggingface.co/Qwen/Qwen2-Audio-7B-Instruct),
[MERT](https://huggingface.co/m-a-p/MERT-v1-330M), and
[MuQ](https://huggingface.co/OpenMuQ/MuQ-large-msd-iter).

## Recommended next training target

Keep three logical roles, but move the complete learned audio/recommender contract to 256-d:

1. Use MN10 as the main acoustic teacher.
2. Add confidence-masked Beat This and Basic Pitch auxiliary losses; use Whisper only on detected
   vocals. Use Qwen2-Audio only for schema-constrained labels or pairwise rankings that pass an
   agreement/abstention gate.
3. Fine-tune the 256-d MNv4 head with neighbour-preservation and session-ranking losses, not PCA
   alone.
4. Distil new 256-d GRU state and scorer graphs from the existing recommender, then fine-tune on
   real/synthetic sessions.
5. Accept only after full-chain parity, cold-start/niche suites, and real-phone CPU/QNN latency,
   memory and thermal tests.

That is the likely 45–50 MB total bundle. Shipping an on-phone LLM, MERT or MuQ would be slower,
larger and no better on the measured recommendation target.

## Reproducibility

The mixed-precision export is reproducible with `tools/convert_audio_encoder_fp16.py`. Selected
asset SHA-256 values:

```text
0223fd2d7a1e30f2dc0172dbb6141ce395d990f559b4d6a756492be669253dcc  mnv4_audio.onnx
13c5f87437e57b52ceb455f7e75f9ab841aa3ca6fe987507974a30657122b1e7  predictor_state.onnx
adaa42bc3bd4b535d4ad2a49a348f3a5d9b41048c092650fedbd9fcc2a7457a6  predictor_scorer_n100.onnx
afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1  text_encoder_minilm.onnx
```
