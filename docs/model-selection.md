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

Updated 2026-09-24. The two-stage residual chosen on 2026-07-20 was superseded in August by a
single scorer that reads audio and text per candidate (the semtext-1344 contract, byte-identical
across the Kotlin port and the offline harness). In September the artist knowledge pack joined it,
and every file was made smaller without changing what SMART picks; this is the shipped state.

| File | Contract | Bytes |
|---|---:|---:|
| `mnv4_audio.onnx` | 10 s mono 32 kHz → 960-d (four-step FFT front end, INT8) | 10,746,975 |
| `text_encoder.onnx` | tokens → 384-d in MiniLM's space (three-layer student, INT8) | 5,190,836 |
| `text_vocab.txt` | the student's WordPiece vocabulary | 61,317 |
| `predictor_state.onnx` | recent 960-d history → 960-d state (dynamic INT8) | 4,223,128 |
| `predictor_scorer_n100.onnx` | state 960 ⊕ 384 text centroid + 100 × (960 audio ⊕ 384 text) → logits (FP16 weights) | 6,059,712 |
| `universal_semantic_head.onnx` | 960-d audio → 27 genre-family scores | 2,678,194 |
| `music_entities_250k.bin` | artist names, aliases, members → entity ids (LJENT2, 48-bit keys) | 11,918,291 |
| `artist_knowledge.bin` | entity → PQ-16 descriptor, language, decade, confidence (version 2) | 7,896,585 |
| `artist_adapter.bin` | 384-d text → descriptor guess for artists outside the pack | 1,181,968 |

Total: **49,957,006 bytes (47.6 MiB)** per platform, against 86,761,581 bytes (82.7 MiB) in v0.5.1,
which had neither the pack nor the adapter. Audio and text graphs run while tracks are indexed.
Queue construction runs the state graph and the scorer; a candidate without a text vector gets a
zero text block, the trained text-dropout path, so missing metadata degrades to audio-only scoring
rather than to noise. The app progressively builds the local index on first launch, embeds the
selected seed on demand, and keeps playback's metadata-only cold-start queue until audio
candidates are ready. All inference, history, and stored embeddings remain on the device; iOS and
Android both persist private history across launches. On iOS, Music-library items without a raw
asset URL are indexed through the same trusted metadata encoder and played by
`MPMusicPlayerController`; only waveform-dependent audio embeddings are omitted. This is an
explicit capability mask, not an empty-library or random-fallback path.

How each file was made smaller, and what it cost: `docs/smart-minimal-stack.md` ("The compact
build"). MiniLM itself stays in `tools/research/minilm` as the teacher for the pack, the adapter
and the text student.

## Metadata contract

Embed `genre; artist; original year; language`, dropping blank fields (text identity `text-v3`,
2026-09-24). Every genre the file carries, joined by `; `; the credited display artist; the first
release year from the tags, else the edition year; a language word from the file's `LANGUAGE`/`TLAN`
tag, else the language the knowledge pack's teacher says the artist mainly sings in (confident
artists only), else from the script of the title and artist (Cyrillic → russian, kana/kanji →
japanese, Latin silent). A track with no year at all ends in the artist's decade from the pack
(`; 1980s`). Never put title or filename text into the trusted channel. A stored vector's identity
is the string it encodes, so a tag edit or a pack update re-encodes exactly the tracks whose string
changes. Candidate retrieval interleaves anchor-audio, state-audio, seed-text and artist-descriptor
rankings instead of using a hand-tuned cross-modal weight.

The pack's language word (2026-09-24, with the student encoder): a "<language> songs" search found
82 % of its top ten in that language instead of 16 %, SMART queues kept the seed's language more
often (listener 0.84 → 0.89, MPD +1 to +7 pp) with P@10 within half a point, and the decade raised
P@10 by 0.3–2.7 pp on the year-less MPD libraries.

The text-v2 string (2026-09-13), measured with MiniLM on the real library (1,084 tracks, 20 listener
playlists, leave-one-out retrieval inside each playlist): adding the language word kept playlist R@1 (0.567 → 0.575 macro)
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
4eb333e1109a148e8f53c00f5a2ea28513e67bc02f8adac1f0b99e3fe6b513d3  mnv4_audio.onnx
6437af63f36e8a84a3088d4df70fe2bbad453e6677632e00819088cb25dd5732  text_encoder.onnx
ee9eb9187486a0398dff497468e37f4179da1fd513a591a91bba74be6b92bfc3  predictor_state.onnx
7b84f932820802d52129a11cbb0b216e9f621457037022eabf8c5bf6d417dbc9  predictor_scorer_n100.onnx
5002b2b116621e35265caaf63147c3c0c2877add77ec0d0cc5dcb45ad02cd503  universal_semantic_head.onnx
89fc881bced7164413953dc3d63627255973cb8fd9f3fac9313e6f09e75d1111  music_entities_250k.bin
191ee2ad2d0f7521b7720b99f487ca801389292ac32eaa40b8980ee53fc0687f  artist_knowledge.bin
8cfe1f444d8d91e230bdf10ed54413ca8c5af1aa7bb97e7726bfe9ec6f03e9a9  artist_adapter.bin
```
