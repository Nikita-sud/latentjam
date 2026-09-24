# SMART minimal stack: design

Date: 2026-09-24. Status: design for review. Parts are implemented on unmerged branches:
- energy (§1) on `feat/smart-energy`
- the audio encoder's Step 1+1b (§7) on `feat/audio-fft-int8`
- the pack reader, the descriptor channel and width, and the engine plumbing (§2–3) on
  `feat/artist-pack-engine`, which contains `feat/artist-pack-reader` and
  `feat/smart-descriptor-channel`

Evidence comes from the offline bench at `~/Documents/LJ/smart-bench-2026-09-24/`. Its README has
every table, and `ljchain.py` there is a NumPy port of `core/smart/.../chain/` that matches all 10
parity fixtures exactly.

## Goal

The goal is better SMART queues for any listener with less shipped, on the device only.

- **Quality.** Higher playlist P@10 on the listener's library and on stranger libraries, both cold and
  with history.
- **Size.** The ML payload drops from 86.5 MB to about 60 MB, and to about 41 MB once a static text
  table passes the search check.
- **Speed.** No ONNX call runs per queue hop, and audio indexing needs about 40 % less CPU per window.
- **Shape.** Teachers run once, offline. The phone receives static assets. No listener data leaves the
  device, and no desktop step is needed for the listener's own tracks.

## What the bench measured

**Metric.** P@10 is the share of the first ten queue picks that share a playlist with the seed.

**Libraries.**
- "Listener" is the 2026-08-14 phone export: 1,062 tracks and 18 playlists.
  - "Cold" runs with no history.
  - "History" replays the real listening log.
- MPD is six stranger libraries built from Million Playlist Dataset playlists. They are used for
  evaluation only.

Intervals are paired-bootstrap 95 % CIs. Every Δ below is in percentage points of P@10 against the
shipped chain.

| Configuration | Models | Listener cold | Listener history | MPD 1k (×3) | MPD 3k |
|---|---:|---:|---:|---:|---:|
| Shipped | 68.5 MB | 0 | 0 | 0 | 0 |
| Artist knowledge pack (descriptor slot + 4th pool channel + energy), float vectors | 68.5 MB + pack | +4.5 [+3.4, +5.7] | +3.6 [+2.4, +5.0] | +9.0…+10.1 | +4.7 |
| Same pack as PQ-16 codes (16 B per artist)¹ | ~72.5 MB | +5.0 | +3.8 | +9.4 | +5.7 |
| Knowledge + today's per-hop nets | ~72.5 MB | +5.9 | +4.3 | +8.8…+10.5 | +4.3 |
| **Minimal**: PQ-8 pack + adapter + energy, per-hop nets removed | ~47 MB | +4.9 | +3.5 | +9.4…+10.9 | +4.9 |
| **Minimal+**: Minimal + static text table + INT8 audio encoder | ~21 MB | **+6.0** | **+3.8** | +8.7…+10.3 | +5.3 |

"Models" counts the audio and text encoders, the predictor nets, the pack's codes for 250k artists
(2–4 MB) and the adapter. The entity index, the semantic head and the shipping pack format (≈7.5 MB)
are counted in [Size budget](#size-budget).

¹ The PQ rows come from a separate run (`exp_small.py`). Its float reference measured +4.4 / +3.5 /
+9.5 / +5.2, so PQ-16 matches float within noise.

**Fresh listener data (phone backup, 2026-09-24).**
- **The library.** 1,094 tracks, 21 playlists and 5,469 listens. The 89 tracks added since the export
  were embedded with the Android protocol, at cos 0.993–0.997 to the phone's own vectors.
- **Against today's app.** Energy plus the pack wiring (float descriptors and the adapter, nets kept):
  - cold, 400 seeds: +4.3 [+3.1, +5.6]
  - history, all 2,977 mid-session seeds: +3.5 [+3.1, +4.0]
  - seeds whose playlists are not SMART-marked, where the app's companion injection does not act:
    +5.1 cold and +4.6 history
  - without the per-hop nets: +3.7 cold and +2.0 history. On this data the nets are worth about
    1.5 pp with history, so they stay until an on-device check says otherwise.
- **Sampling.** A random 300-seed history sample swings by ±1.2 pp; use all history seeds.

**Other measured facts:**
- **Energy.** Feeding the chain's dormant energy term from the shipped semantic head cuts large energy
  jumps by 17–23 % with no change in relevance.
- **Adapter.** It adds +0.4 cold and +0.2 with history on top of the pack. On the listener's seeds
  whose artist the pack does not cover, P@10 goes from 0.525 to 0.575. With oracle coverage it would
  be 0.590.
- **INT8 audio encoder** (§7).
  - With a four-step FFT front end it is 10.7 MB.
  - Quality: cos 0.998 to the shipped model; P@10 +0.40 [+0.03, +0.78] pooled over six MPD libraries.
  - Speed, single-threaded as the app runs it: 35.8 → 19.3 ms per window on the Mac, and 115 → 97 ms per
    track on the Android emulator.
- **Static text table.** It is as good as MiniLM for SMART. The text space itself is required: without
  it, history mode drops by 3.0.
- **Teacher.** DeepSeek-V4.1-Flash gives +4.5 on the listener. V4-Pro gives only +1.4 because it
  states invented facts about niche artists with confidence.
- **Rejected:**
  - pool 200
  - rescaling any hand weight ×0.5–2
  - replacing the trusted text string with the descriptor. Uncovered artists cluster together, so
    Ce seara drifts into phonk.
  - the first distilled student scorer, which did not beat having no scorer at all

## Architecture

**Today**
- Index time, per track:
  - `mnv4_audio.onnx` (21.5 MB) produces 960-d audio.
  - `text_encoder_minilm.onnx` (23.0 MB) produces 384-d text.
  - `universal_semantic_head.onnx` (2.7 MB) produces labels.
- Queue time:
  - A three-channel round-robin pool of 100 is built from anchor audio, state audio and seed text.
  - Every hop runs `predictor_state.onnx` (11.9 MB) and `predictor_scorer_n100.onnx` (12.1 MB).
  - The chain score is hand-weighted.
  - The descriptor slot and the energy term exist in code but are always empty.

**Proposed**
- Index time, per track:
  - The INT8 audio encoder produces 960-d audio.
  - The static text table produces text vectors.
  - The semantic head produces labels and an energy score.
  - The artist resolves through the entity index to a knowledge code, which decodes to a 384-d
    descriptor. An artist without a code gets the adapter's descriptor instead.
- Queue time:
  - A four-channel pool of 100 adds a descriptor channel to the existing three.
  - The state is the mean audio of the context tokens.
  - The chain score has no scorer term, the descriptor slot is live, and the energy term is live.

## Components

### 1. Energy

`TrackDescriptor.energy` is documented as the input of the chain's energy-smoothness term, but KMP
never sets it.

- **Source.** Take `universal_semantic_head` output 6 (`ENERGY_HIGH`) minus output 5 (`ENERGY_LOW`)
  on the stored 960-d vector. No new model is involved.
- **Storage and ranking.** Compute that raw score at index time, alongside the labels, and store it
  with the track. Rank it within the library when `SmartSnapshot` is built. The chain's 0.2 dead band
  then means 20 percentile points.

This is the smallest step and can ship on its own. It is implemented on `feat/smart-energy`:
- The engine ranks the semantic head's energy when it builds the snapshot.
- Tracks the head has not seen are classified only if the head is already loaded.
- The snapshot is rebuilt as more tracks gain semantics.

### 2. Artist knowledge pack

**Artist list.**
- The base is the existing popularity-ranked MusicBrainz rows behind `music_entities_250k.bin`.
- Added to it are Wikidata musicians, singers and musical groups with at least one Wikipedia article
  that are not among those MusicBrainz rows: they have no MusicBrainz id, or one below the popularity
  cut. Both sources are CC0.
- The first design required no MusicBrainz id. That misses the artists it was meant for: Ion
  Suruceanu (Q4446651, 6 articles) has a MusicBrainz id and only falls below the top 250k.
- From the 2026-09-23 dump:
  - 418,584 such Wikidata artists have an article.
  - 100,852 of them are already in the MusicBrainz top 250k.
  - The rest are ranked by article count and capped at 100,000; the last one kept has 2 articles.
- Wikidata-only entities are appended to the entity index with their names and aliases, so search
  grounding gains them too. The index's dense popularity-ranked ids stay the key space.
- The measured need: the MusicBrainz top 250k lacks the listener's Moldovan estrada artists, and
  Wikidata has articles for them.

**Teacher.**
- DeepSeek-V4.1-Flash through the API, with `thinking: {type: disabled}`.
- **The prompt is the artist name alone.** Grounding it with CC0 facts was measured on 2026-09-24 and
  rejected. `exp_grounded.py` put Wikidata facts (description, genres, country, languages, start year)
  into the prompt for the 2,492 of 2,861 bench artists that Wikidata knows:
  - The listener cold was −1.7 pp [−2.8, −0.6] against the ungrounded descriptors.
  - With history it was ±0.
  - The MPD libraries ranged from −1.5 to +1.2 pp.
  - Facts pull the one-liner from style and mood toward years and countries, which predict playlists
    worse.
- **Wikidata extends the artist list instead.** Ungrounded descriptors for artists that only Wikidata
  knows add +0.3 pp for the listener (n.s.) and +1.0 pp [+0.3, +1.7] on MPD 3k. Giving just those
  tail artists grounded prompts adds nothing on top.
- The output is JSON:
  - `descriptor`: one line on style, era, scene, language and mood
  - `languages`, `country`, `decades`, `genres`
  - `energy`
  - `confidence` from 0 to 1
- An artist the model does not know must come back with low confidence.
- Measured cost is $0.10 per 2.9k artists for descriptors and $0.19 per 2.9k for attributes. About
  350k artists comes to roughly $25–35.
- DeepSeek's terms (checked 2026-04) allow training on outputs.
- The descriptor text never ships; only its codes do.

**Codes.**
1. Embed each descriptor with the shipped MiniLM. That 384-d space is the one the bench measured.
2. L2-normalize the vectors.
3. Product-quantize them with 16 sub-spaces × 256 centroids (PQ-16). The codebooks are fit on the pack
   itself.

**PQ-8 or PQ-16.**
- On the 2.9k-artist prototype the two measured the same (`check_pack_pq.py`).
- On the full 350k-entity build they do not: the codebooks fit a large pack worse.
  - Median reconstruction cosine: 0.841 for PQ-8, 0.855 for PQ-16.
  - Listener: PQ-8 +4.0 cold / +3.2 history; PQ-16 +5.5 / +4.3.
  - MPD: the two are within noise.
- PQ-16 ships: 7.5 MB against 4.7 MB.

**Asset format.** `artist_knowledge.bin`:
- header: magic, version, dim 384, M 8, K 256, entity count
- codebooks as FP16 (about 196 KB)
- one fixed-size record per dense entity id, with no id list:
  - 8 B of codes
  - a 2 B fingerprint of the entity's own normalized name
  - two 1 B language indices
  - a 1 B confidence
- An entity with confidence below 0.5 is marked absent.
- At about 350k entities the pack is about 4.8 MB.

**Lookup.**
1. `MusicEntityIndex.resolve(artist)` returns sorted ids. They can include the artist's groups and
   token matches.
2. Take the id whose fingerprint matches the normalized artist string. If none matches, take the
   smallest id, which is the most popular. Wikidata-only entities are appended after the MusicBrainz
   rows, so they rank below them.
3. Decode the codes into a float vector by concatenating centroids.

This runs once per track at index time.

### 3. Chain wiring

- **Descriptor dimension.** `SmartSnapshot.DESCRIPTOR_DIM` goes from 768 to 384. `SemanticZ.combine`
  and `Reanchor.fusedCos` already read the descriptor space, so the semantic z-term and the re-anchor
  trigger use it without further change.
- **Descriptor channel.** `buildPool` gains a fourth round-robin channel. It is ranked by centered
  descriptor cosine to the seed and takes one item per round. It is active only when the seed has a
  descriptor. The pool size stays 100.
  - It then also takes the trusted-text channel's turn. Measured with `exp_desc_quota.py`: listener
    +1.5 cold / +1.0 history, MPD ±1.
  - Two or three descriptor items per round measured no better.
- **Parity.** The NumPy parity exporter changes in lockstep, and the fixtures are regenerated because
  the descriptor dimension changes them.

### 4. Adapter for artists the pack does not cover

- **Shape.** A residual MLP (text dim → 768 → 384). It maps the trusted text-v2 string (`genre; artist;
  original year; language`) to the pack space.
- **Bench result.** Cosine to the teacher on unseen artists goes from 0.26 to 0.66. The bench fit
  cannot ship, because it saw MPD artists.
- **Shipping fit.**
  - Train only on the pack corpus. The trusted strings are synthesized from the teacher's attributes:
    genre from `genres`, year from `decades`, and `languages`. The target is the entity's pack vector.
  - Hold out 10 % of artists for the fidelity check.
  - Never fit on bench libraries or on a listener's library.
- **Recipe measured 2026-09-24** (`exp_adapter_synth.py`). The adapter was trained on 10.6k strings
  synthesized from the teacher's attributes of covered artists.
  - It matches the one trained on library strings: cosine 0.661 against 0.664 on uncovered artists.
  - P@10 is ±0.4 on every library.
  - On the listener's uncovered-artist seeds it scores 0.560, against 0.575 (library strings) and 0.590
    (oracle).
- **Runtime and size.** It runs once per uncovered track at index time. Two dense layers need no ONNX
  Runtime: INT8 weights and a Kotlin matmul are enough, at about 0.6 MB.
- **Re-fitting.** Re-fit it whenever the text encoder changes.

### 5. Static text table

- **Construction.**
  1. Run MiniLM on every token of its WordPiece vocabulary.
  2. Weight the outputs by log-rank.
  3. Reduce them to 128 dims with PCA.
  4. Quantize to INT8 with a per-row scale.
- **Use.** A string's vector is the normalized mean of its token vectors. The existing
  `BertWordPieceTokenizer` does the tokenization. The table is 3.9 MB, needs no ONNX, and takes
  microseconds per string.
- **Search.** `semanticSearch` encodes free-text queries in the same text space that SMART uses, and
  one text space serves both.
  - The table replaces MiniLM only after a search check passes. The query set covers artist names,
    genres, moods, languages and eras, and the table's recall@10 must be at least 0.9 × MiniLM's.
  - If the check fails, MiniLM stays. SMART loses nothing measurable either way; only 19 MB of
    savings are at stake.
- **The check was run on 2026-09-24 and failed** (`exp_search.py`). Queries were generated from the
  listener's own tags, and the score is recall@10:

  | Family | MiniLM | Static 128 int8 | Ratio |
  |---|---:|---:|---:|
  | Artist, exact | 0.986 | 0.994 | 1.01 |
  | Artist, first word typed in lowercase | 0.927 | 0.961 | 1.04 |
  | Genre | 0.871 | 0.671 | 0.77 |
  | Decade ("80s music") | 0.249 | 0.100 | 0.40 |

  - At 256 dimensions genre only reaches 0.84 of MiniLM.
- **A Tokenlearn-style table was tried the same day** (`fit_static_tokenlearn.py`, 39 s on the Mac). It
  fits token vectors so that their mean matches MiniLM's *sentence* embedding. The corpus had 66k
  strings (MPD artists, genre names, synthetic text-v2 strings, query templates) and excluded the
  listener's library.
  - Search: genre 0.809 against 0.871 (0.93, passes); artist 1.000; language 1.000.
  - Decade: 0.120 against 0.249 (fails). MiniLM is weak there too.
  - SMART against the token-level table: listener −1.0 cold / −0.9 history (n.s.), MPD −0.7 … +1.5
    (n.s.).
  - Decision: MiniLM can go once the app parses years and decades in a query as an explicit filter,
    which beats both encoders on those queries. Until then MiniLM stays.

### 6. Queue without per-hop nets

`SmartChain` already accepts a null `PredictorRuntime`.
- **State channel.** It uses the mean audio of the context tokens: the same seed and recent history
  that `prepareContext` hands the state net today.
- **Chain score.** The scorer term leaves the score, and the rest of the score is unchanged.
- **Removed.** `predictor_state.onnx`, `predictor_scorer_n100.onnx`, `ScorerPacking` and every per-hop
  ONNX call.

The trade-off is measured. Keeping the nets on top of knowledge is worth about +1.0 cold and +0.8
with history, at a cost of 24 MB and two ONNX calls per hop. Without them the chain is still +4.9
cold and +3.5 with history over today (the Minimal row).

Re-measured on 2026-09-24 with the pack in its file format (`exp_nonet_pack.py`), against pack + nets:
- centroid state: listener −0.3 cold / −0.6 history
- anchor-only pool: listener −0.3 cold / −1.0 history
- MPD: ±2 in both cases

**Do this step only after the pack ships.** Without the pack, the centroid state makes the current
fallback path worse: −2.8 cold on the listener (`exp_nonet_state.py`).

### 7. Audio encoder

**Step 1: a four-step FFT front end.** The shipped graph computes its 1024-point DFT as a convolution
with 1,026 stored kernels: 1.05 GMAC per 10 s window, a third of the whole encoder, and 4.2 MB of
weights. The same DFT can be computed as two 32-point stages (Cooley–Tukey, 1024 = 32 × 32) that are
plain matrix products.
- The math is identical. It matches NumPy's `rfft` to 1e-5 in log-mel, and the encoder's embeddings to
  cos ≥ 0.99997 against the shipped graph.
- Speed: the front end takes 18.4 → 4.2 ms per window on one thread (Mac, ORT 1.24). The app runs the
  encoder single-threaded (`setIntraOpNumThreads(1)`), so the whole encoder costs 34.6 → 20.7 ms, 40 %
  less CPU per window.
- Size: the front end shrinks from 4.47 MB to 0.32 MB.
- ORT's own `STFT` operator was rejected. It is single-threaded and 2× slower than the convolution at 4
  threads.
- Stored vectors stay compatible, and it needs no retraining.

**Step 1b: INT8 post-training quantization of the encoder behind that front end.**
- Method: QDQ, per-channel weights, percentile-99.99 activation calibration on CC-licensed audio. The
  front end stays float.
- Size: 10.7 MB, against the 21.5 MB shipped today.
- The earlier variant with the convolution front end measured cos 0.998 and unchanged P@10, so stored
  vectors stay compatible.

**Step 2, only if it passes its gates: a distilled student.**
- Architecture: EfficientAT `mn05` (1.9M parameters) or `mn10` (5.8M), initialized from their AudioSet
  checkpoints (MIT code).
- Training: online distillation from `mnv4` on a commercially licensed FMA subset.
  - The research run uses 11,599 tracks.
  - A ship-grade run drops the 17 NoDerivs tracks. It should also drop the 2,802 ShareAlike tracks
    unless weights are judged not to be adaptations. That leaves 8,780 tracks under CC BY, CC0 or
    public domain.
- Gates, fixed before the first result:
  - **A1.** Median track-level cos to `mnv4` ≥ 0.95 on held-out MPD previews (three windows per track).
  - **A2.** P@10 on MPD r1k_a with re-embedded vectors has a paired-CI lower bound ≥ −1.0 pp against
    `mnv4`.
  - **A3.** At least 2× faster per window on a mid-range Android phone.
- A student's vectors are not interchangeable with `mnv4`'s, so switching needs a one-time re-embed of
  the library behind a version bump. The index is keyed by `modelVersion` in `AppGraph`, so a new
  version string triggers it.

**Measured 2026-09-24.** Six MPD libraries, 900 seeds, the Minimal+ chain, P@10 paired against the same
library embedded by today's encoder. "1 thread" is on the Mac.

| Encoder | Size | 1 thread | Track cos to today's | ΔP@10 [95 % CI] | Head top genre agrees |
|---|---:|---:|---:|---:|---:|
| Today's FP16 graph | 21.5 MB | 35.8 ms | 1 | 0 | 100 % |
| Step 1+1b: four-step FFT + INT8 | 10.7 MB | 19.3 ms | 0.998 | +0.40 [+0.03, +0.78] | 97.5 % |
| mn10, fp16 storage | 8.2 MB | 16.5 ms | 0.987 | −0.09 [−0.61, +0.43] | 93.5 % |
| mn05, fp16 storage | 2.9 MB | 10.8 ms | 0.973 | −0.23 [−0.78, +0.32] | 90.5 % |
| mn05, INT8 | 2.0 MB | 9.3 ms | 0.963 | −0.03 [−0.60, +0.56] | 88.2 % |

- **Gates.** Every student passes A1 and A2. A3 is unmeasured on a phone.
- **Step 1+1b on a device.** It ran on the Android arm64 emulator (ORT 1.26, the production embed path,
  warm runs):
  - one 10 s track: 115 → 97 ms including decoding
  - model load: 120 → 92 ms
  - debug APK: 121.2 → 110.9 MB
- **Quantization.** Post-training INT8 hurts the small students more than the teacher: mn10 goes 0.987
  → 0.919–0.967 depending on calibration. Store them as fp16 instead, which is identical in quality and
  keeps fp32 speed.
- **Why Step 2 still waits:**
  - The semantic head was fitted to today's vectors, so a student changes the top genre of 10–12 % of
    tracks (mn05), which moves Map and For You mixes.
  - The duplicate finder's 0.99 threshold needs re-validating.
  - The teacher's training-data caveat in `LICENSE-MODEL.txt` carries over to anything distilled from
    it.
- **Before Step 2 ships:** re-fit the head on student vectors from CC audio, re-check the duplicate
  threshold, and measure A3 on a phone.

### 8. Language (optional)

- **Covered artists.** The teacher's `languages` feed the chain's language factor when confidence is at
  least 0.5.
- **Everything else.** Script-only detection stays.
- **Measured.** ±0 in aggregate, but the Moldovan tail turns post-Soviet instead of Western pop.
- **No statistical detector yet.** None, Lingua included, is proposed until it is measured. The
  2026-07 Romanian detection attempt split the cluster.

### 9. Later

- **ORT minimal build.** In ORT's own arm64 example the library drops from 16.3 MB to about 4 MB. It
  needs a from-source build, so check the F-Droid recipe first.
- **Category-mix calibration and a windowed DPP.** They target the drift after a niche runs out, for
  example the Ce seara tail ending in slowed phonk. Calibration was measured on 2026-09-24
  (`exp_calibration.py`, a Steck KL term over pack-space categories, λ = 4):
  - picks outside the seed's neighbourhood: −7 … −23 %
  - P@10: ±0
  - the listener's tag-genre match: 0.61 → 0.59
  - The Ce seara tail stays, because the library runs out of Moldovan tracks. It remains an option for a
    by-ear test.
- **Residual k-means codes (GRID).** Only if the pack grows well past 350k.

## The shipped build (2026-09-24)

**Data and teacher.**
- MusicBrainz dump 2026-09-23 (top 250k by artist credits) plus 99,998 Wikidata artists outside it:
  350k entities.
- DeepSeek-V4.1-Flash described 349,953 of them and gave attributes for 335,994 unique names. It cost
  $33.
- 189,669 entities have teacher confidence ≥ 0.5 and read as present. The adapter guesses the rest.

**Adapter.** Held-out artists reach cosine 0.735 to their descriptor, against 0.361 for the text
alone. It was trained on 758,664 synthetic strings from 183,240 confident entities.

**Bench, end to end.** The real files, looked up the way the app does, against today's app with
energy on. Figures are Δ P@10 in pp:

| Library | Δ P@10 |
|---|---:|
| Listener cold (400 seeds, fresh data) | +5.5 [+4.2, +6.8] |
| Listener history (all 2,977 seeds) | +4.2 [+3.6, +4.7] |
| MPD r1k a / b / c | +8.0 / +9.3 / +8.5 |
| MPD r3k | +4.3 |
| MPD s1k_b | +2.2 |
| MPD s1k_a | −2.5 [−4.4, −0.6] |

- On the listener's library, 86 % of tracks get a pack descriptor and 14 % the adapter's guess. On
  MPD, 97–99 % get a pack descriptor.
- s1k_a is the one loss. It is −0.8 (n.s.) with float descriptors, so quantization causes most of it.
  OPQ or finer codes would be the next step.

**On device.** The debug build was run on the read-only emulator.
- Kotlin decodes the same vectors as Python: 303/303 pack cases and 64/64 adapter cases.
- A Latin "Bulanova" query finds the Cyrillic-tagged tracks.
- A SMART queue from a Буланова seed stays in Russian 90s pop: Гурцкая, Натали, Насыров, Руссо,
  Куртукова, Нарцисс, Логинов, Меладзе, Шура. Four of those artists are reachable only through the
  Wikidata tail.

## Size budget

Sizes are in MB.

| Asset | Today | Proposed |
|---|---:|---:|
| `mnv4_audio.onnx` | 21.5 | 10.3 (four-step FFT front end + INT8, shipped; a student would be 2.0–2.9) |
| `text_encoder_minilm.onnx` | 23.0 | 23.0 until a static table passes the search check (then 3.9) |
| `predictor_state.onnx` | 11.9 | 0 |
| `predictor_scorer_n100.onnx` | 12.1 | 0 |
| `universal_semantic_head.onnx` | 2.7 | 2.7 |
| `music_entities_250k.bin` | 15.3 | 21.5 (250k MusicBrainz + 100k Wikidata, one native alias each) |
| `artist_knowledge.bin` | – | 7.5 (PQ-16, 350k entities) |
| `artist_adapter.bin` | – | 1.2 |
| **Total** | **86.5** | **≈60** with MiniLM; ≈41 once a static table passes the search check |

## Licensing and data

- **Artist list.** MusicBrainz core data and Wikidata are CC0.
- **Teacher outputs.** DeepSeek's terms allow them. Only embedded, quantized codes ship.
- **Text encoder.** MiniLM is Apache-2.0 and already ships. The static table is derived from it.
- **Audio student.** EfficientAT is MIT. Its AudioSet-trained checkpoints carry the same kind of
  training-data caveat already recorded in `androidApp/src/main/assets/ml/LICENSE-MODEL.txt`. FMA
  CC BY tracks need attribution, so a credits file listing track, artist and licence ships with any
  student weights.
- **MPD.** Evaluation only. Weights fitted on MPD never ship, which rules out the bench's student
  scorer and its adapters.

## Rollout and gates

Each step is its own branch and PR:
1. Energy. **Done** on the unmerged branch `feat/smart-energy`, with 344 core:smart tests green.
2. The pack builder (`tools/research/build_artist_pack.py`, extending `pack_music_entities.py`), the
   Kotlin reader and lookup, the 384-d descriptor slot, the descriptor channel, and parity. Wikidata
   extends the artist list; the teacher prompt is the name alone. Progress:
   - **Reader done** on the unmerged branch `feat/artist-pack-reader`: `ArtistKnowledgePack`, with a
     cross-language parity test against the Python builder.
   - **Builder prototyped** in the bench as `build_artist_pack.py`. Its file reproduces the pack's gains
     end to end: listener +5.4 cold / +3.4 history, MPD +8.7…+10.1.
   - **Chain side done** on the unmerged branch `feat/smart-descriptor-channel`: the 4th pool channel and
     a data-driven descriptor width (384 for the pack, 768 for the fixtures). The NumPy mirror matches,
     and a regenerated fixture replays 10/10.
   - **Engine plumbing done** on `feat/artist-pack-engine`, which stacks the reader and the channel
     branches.
     - `ArtistKnowledge` loads `ml/artist_knowledge.bin` lazily on both platforms.
     - It resolves the whole artist tag, then the first credited artist, and fills
       `SmartTrack.descriptor`.
     - Without the asset, nothing changes.
   - **Builder done:** `tools/research/build_artist_knowledge.py` on the same branch. The Kotlin reader
     decodes its output 600/600 like Python.
   - **Attributes and adapter training done** on the same branch.
     - `build_artist_knowledge.py --attributes` stores the teacher's confidence per record. The 0.5
       gate measured neutral.
     - `train_artist_adapter.py` uses the synthetic-string recipe.
   - Left: a fresh MusicBrainz dump plus the Wikidata extension, then a rebuilt entity index, then the
     teacher run (~$25–35). The three assets then ship together: the entity index, the pack and the
     adapter.
3. The adapter. **Device side done** on `feat/artist-pack-engine`: `ArtistAdapter`, an FP16 two-layer
   network in Kotlin, and `export_artist_adapter.py`, with a parity test. It is trained after the
   teacher run, on synthetic strings.
4. Removing the per-hop nets.
5. The four-step FFT + INT8 audio encoder. **Done** on the unmerged branch `feat/audio-fft-int8`, and
   verified on the Android emulator.
6. Static text table:
   - fit Tokenlearn-style
   - add an explicit year and decade filter to search
   - then the search check
7. The audio student. Gates A1 and A2 pass, but it waits on the semantic head (see §7).
8. The later items.

The bench code (not its MPD-derived caches) moves into `tools/research/smart_bench/` so every step can
be re-measured.

Every step passes:
- **Bench.**
  - Listener cold and with history, MPD r1k ×3, r3k and the two snowball communities.
  - Every library needs a paired-CI lower bound ≥ −1.0 pp, and the listener needs a gain ≥ 0 in both
    regimes.
  - Step 2 must have a listener cold lower bound above 0.
- **Parity.** Kotlin against the NumPy mirror, 10 of 10 fixtures identical.
- **Device.** Queue build p95 latency and peak memory on a mid-range Android phone and an iPhone are
  no worse than today.
- **By ear.** The listener compares before and after queues from about ten seeds:
  - Ce seara
  - Linkin Park
  - Michael Jackson – Bad
  - Frank Ocean
  - Jónsi
  - ABBA
  - four of the listener's choice
- **Size.** The ML payload stays within the budget above.

## Risks

- **Teacher errors on niche artists.** Handled by the confidence field; grounding the prompt measured
  worse. Entries below 0.5 fall back to the adapter.
- **Ambiguous names.** Handled by the name fingerprint plus popularity order.
- **Artists newer than the pack.** Handled by the adapter and by regenerating the pack each release.
- **One listener's library.** It is a single person, which is why the stranger libraries and CIs are
  part of every gate.
- **F-Droid.** Asset changes do not affect the build. An ORT from-source build would, and it is not
  part of this plan.

## Out of scope

- learned retrieval
- a new scorer
- For You
- per-track descriptors: artist-level was measured at least as good
- the "swap" text string, which was rejected
