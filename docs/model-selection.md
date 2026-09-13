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

Updated 2026-09-13. The two-stage residual chosen on 2026-07-20 was superseded in August by a
single scorer that reads audio and text per candidate (the semtext-1344 contract, byte-identical
across the Kotlin port and the offline harness); this is the shipped state.

| File | Contract | Bytes |
|---|---:|---:|
| `mnv4_audio.onnx` | 10 s mono 32 kHz → 960-d | 21,538,547 |
| `text_encoder_minilm.onnx` | tokens → 384-d | 22,972,370 |
| `predictor_state.onnx` | recent 960-d history → 960-d state | 11,917,220 |
| `predictor_scorer_n100.onnx` | state 960 ⊕ 384 text centroid + 100 × (960 audio ⊕ 384 text) → logits | 12,114,045 |
| `universal_semantic_head.onnx` | 960-d audio → 27 genre-family scores | 2,678,194 |
| `text_vocab.txt` | WordPiece vocabulary | 231,508 |

Total: **71,451,884 bytes (68.1 MiB)** per platform, plus the 15 MiB CC0 MusicBrainz alias pack
used by search. Audio and text graphs run while tracks are indexed. Queue construction runs the
state graph and the scorer; a candidate without a text vector gets a zero text block, the trained
text-dropout path, so missing metadata degrades to audio-only scoring rather than to noise. The app
progressively builds the local index on first launch, embeds the selected seed on demand, and keeps
playback's metadata-only cold-start queue until audio candidates are ready. All inference, history, and stored
embeddings remain on the device; iOS and Android both persist private history across launches.
On iOS, Music-library items without a raw asset URL are indexed through the same trusted metadata
encoder and played by `MPMusicPlayerController`; only waveform-dependent audio embeddings are
omitted. This is an explicit capability mask, not an empty-library or random-fallback path.

## Metadata contract

Embed `genre; artist; original year; language`, dropping blank fields (text identity `text-v2`,
2026-09-13). Every genre the file carries, joined by `; `; the credited display artist; the first
release year from the tags, else the edition year; a language word from the file's `LANGUAGE`/`TLAN`
tag, else from the script of the title and artist (Cyrillic → russian, kana/kanji → japanese, Latin
silent). Never put title or filename text into the trusted channel. Candidate retrieval
interleaves anchor-audio, state-audio, and seed-text rankings instead of using a hand-tuned
cross-modal weight.

Measured on the real library (1,084 tracks, 20 listener playlists, leave-one-out retrieval inside
each playlist) before the change: adding the language word kept playlist R@1 (0.567 → 0.575 macro)
and raised same-language neighbours for Cyrillic tracks from 65 % to 77 %; the original year
added a further +0.02 macro. Transliterating Cyrillic instead raised R@1 to 0.619 but dropped
language coherence to 49 %, because for an English WordPiece vocabulary the foreign script is
itself the language signal. Label, album and release type were tried and rejected (noise, or
nothing). On the real listening log (1,519 next-track examples through the deployed scorer) the
new string scored MRR 0.068 against 0.069 for the old one and 0.056 with text removed: the text
channel matters and the change costs it nothing. Alternative encoders on the same strings:
all-MiniLM-L12-v2 gains +0.05 macro R@1 for +10 MB but needs the scorer retrained on its vectors;
multilingual MiniLM and multilingual-e5-small (118 MB int8 each) did not beat the current model.

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
3ccfc0ccd06ced1415a6572a48f71bf165110b14d452251a6df92a4f9ca098a2  mnv4_audio.onnx
13c5f87437e57b52ceb455f7e75f9ab841aa3ca6fe987507974a30657122b1e7  predictor_state.onnx
35a27eb06a16ad09aedf98a69dcf15b33151b1f675d24ab9dbb075638a4ff27f  predictor_scorer_n100.onnx
5002b2b116621e35265caaf63147c3c0c2877add77ec0d0cc5dcb45ad02cd503  universal_semantic_head.onnx
afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1  text_encoder_minilm.onnx
```
