# MPD fusion experiment: refreshed vectors, MLP 64/128 and linear controls

Date: 2026-10-13. Branch: `codex/map-performance-0.9.1`.

**Decision: keep the 0.9.1 application unchanged.** Playlist supervision improves held-out MPD
retrieval over the current fixed fusion and PCA, but a trained linear projection beats the MLP.
Both trained families lose substantial personal-playlist coherence after transfer to the saved
phone library. PCA preserves that personal proxy much more closely. These results establish a
useful research baseline, not a model ready to replace regions or the map.

## What was refreshed

All **18,386 local MPD audio previews** were decoded and re-embedded; all 18,386 corresponding
metadata vectors were regenerated. No decode/inference failures. The audio graph is the exact
shipped `mnv4-960-retrieval-distill-v1-p50q4aq` (960D), and the text graph is the exact shipped
`student-s256x3m-emb4-int8-trusted-v1` (384D). The custom Q4 operator was rebuilt from this branch.

Audio follows the app's 32 kHz mono, three 10-second windows at 20/50/80% of available starts,
with the same gain retries and sequential float32 pooling/normalization. The sources are generally
30-second previews; this does not reproduce full-song coverage. FFmpeg decoding on this host is
not bitwise parity with the phone's decoder. Extraction took about 332 seconds with eight workers.

The current Kotlin `TextEncoder.metadataString`, `ArtistKnowledge`, and `BertWordPieceTokenizer`
are called directly from Java, including the actual vocabulary and 48-token limit. Text inference
is unpadded one-string-at-a-time, mean pooled and normalized like the app. Duplicate strings share
one computation. The frozen artist pack supplies facts for **17,382 rows**. All source genre/year
fields are absent; they remain absent. There are **6,130 unique text inputs**, largely artist identity
plus language/decade. No playlist names, memberships or direct title text enter these embeddings.

A new paired cache, `mpd_mnv4_p50q4aq_text_current_2026-10-13.parquet`, is saved in the local research
project's `models/embed/`. The previous MPD cache is preserved. It contains current audio and text
vectors, model identities, exact source-file hashes and the metadata string. Parquet round-trip
checks preserve every vector exactly. Personal rows and learned checkpoints remain outside Git.

## Frozen evaluation design

This uses the existing **2,999,990-row cached MPD membership table**, not all million playlists.
696,386 events do not join the available audio catalogue and are discarded. Only playlist
membership is read: the table's synthetic timestamps, users, completions and skips are never
interpreted as real listening behavior. After available-member deduplication and filtering there
are 111,469 eligible playlists; 88,127 training playlists contribute 1,351,959 train memberships.

Primary artist names and identical audio-file SHA-256s are joined into indivisible groups.
There are 76 duplicate audio-file rows. **1,133 rows** belonging to groups matching the personal
library's primary artists are excluded from MPD fitting, validation and test. Artist aliases and
all featured/collaboration credits are not exhaustively resolved.

| Split | Tracks | Artist/audio groups |
|---|---:|---:|
| Training | 13,967 | 4,746 |
| Validation | 1,740 | 593 |
| Test | 1,546 | 594 |

Track split seeds: 20261013/20261014. Playlists are deduplicated by complete available membership
before filtering artists, then split by SHA-256 buckets 80/10/10. No validation/test playlist
contributes a training positive. Normalization fits and PCA see training tracks only.

Each evaluation case takes a held-out-artist query track from a held-out playlist and ranks the
**entire fixed 13,967-track training catalogue**. Available training members of that playlist are
relevant. Each playlist has at least three relevant tracks and at most two sampled queries.
Uniform playlist sampling, seed 20261015, is capped at 1,500 playlists per split before fitting.
Validation has 2,399 query memberships / 792 unique queries; test has 2,345 / 793. Metrics first
average within playlist, then across playlists. This asks about new artists entering a catalogue,
not about remapping a library the head already trained on.

Checkpoint epochs and the illustrative seed within each family/dimension are selected exclusively
by validation macro nDCG@20. Selection is written and hashed before the separate test command.
All twelve completed fits are reported; the best test seed is never selected for transfer.

## Models and actual training

Each audio/text block is individually unit-normalized. The fixed baseline concatenates
`sqrt(.25) * audio` and `sqrt(.75) * text`, subtracts the training-catalogue mean, and normalizes.
PCA-64/PCA-128 are full-SVD fits to those training rows, followed by unit normalization.
Audio-only, text-only and training-playlist-popularity controls use the same candidates/evaluation.

The two-branch MLP uses `audio: 960 -> 256 -> LayerNorm -> GELU` and
`text: 384 -> 128 -> LayerNorm -> GELU`, then concatenates both with two availability masks:
`386 -> 256 -> GELU -> Dropout(.1) -> 64 or 128 -> L2`. It has 411,584 / 428,032 parameters.
The supervised linear comparator maps the two unit blocks and masks directly
`1346 -> 64 or 128 -> L2`, with 86,208 / 172,416 parameters. It receives identical labels,
sampling, optimizer settings and modality dropout. This separates the benefit of supervised
metric learning from the benefit of a nonlinear architecture.

All four configurations run seeds **3, 17, 41**. The loss is symmetric multi-positive InfoNCE,
temperature .12. Playlists are sampled uniformly; each positive pair has different primary
artist/audio groups. Other batch pairs sharing any *training* playlist are also positives.
Same-group pairs are excluded from the denominator. This avoids treating known train co-occurrences
as negative, but unobserved co-occurrence is still only an imperfect negative signal.

AdamW: learning rate .001, weight decay .001, gradient clipping 5. Each epoch samples 80 batches
of 128 pairs. Maximum 40 epochs; validation at epoch 1 and every 5, early stopping after four
unimproved checks. Each branch is omitted with probability .1, mutually exclusively. CPU, four
Torch threads, deterministic operations. The twelve fit/validation loops total about 215 seconds;
this excludes extraction and some setup. Some fits reach the epoch budget; no convergence optimum
or mobile latency claim is made. Frozen encoders retain their prior training history: only the
new fusion-head split is controlled here.

## Held-out MPD results

Higher is better. nDCG rewards placing relevant tracks near the beginning; precision and recall
are macro averages under the protocol above. Absolute scores are low in this large catalogue.
The following learned models are **validation-selected**.

| Method | nDCG@20 | Precision@20 | Recall@20 |
|---|---:|---:|---:|
| Current 25/75, 1344D | 0.00928 | 0.00675 | 0.00968 |
| PCA 64 | 0.00910 | 0.00672 | 0.01013 |
| PCA 128 | 0.00884 | 0.00662 | 0.00959 |
| MLP 64 | 0.02061 | 0.01553 | 0.02228 |
| MLP 128 | 0.02067 | 0.01605 | 0.02208 |
| Supervised linear 64 | 0.02835 | 0.02190 | 0.02846 |
| Supervised linear 128 | 0.02818 | 0.02050 | 0.02730 |
| Audio only | 0.01532 | 0.01122 | 0.01498 |
| Metadata only | 0.00566 | 0.00400 | 0.00626 |
| Global training popularity | 0.03361 | 0.02870 | 0.02812 |

MLP improves the MPD proxy over current fusion, but the supervised linear projection does better.
Global popularity scores **0.03361 nDCG**, above every validation-selected embedding model. That
control returns the same songs for every query and cannot define a useful content map, but it
exposes how strongly the MPD objective rewards popularity. It prevents interpreting a gain on this
metric as proof of better musical regions.

A separately frozen diagnostic permutes held-out query vectors five times. MLP64 falls from
0.02061 to mean 0.00171 nDCG; MLP128 from 0.02067 to 0.00195; linear64 from 0.02835 to 0.00340.
Thus the learned retrieval is query-dependent, not merely a constant popularity list. This still
does not turn playlist membership into listening judgments.

| Family | Test nDCG range, three seeds | Validation-selected seed / epoch |
|---|---:|---|
| MLP 64 | 0.02061–0.02226 | 3 / 20 |
| MLP 128 | 0.02067–0.02537 | 41 / 15 |
| Supervised linear 64 | 0.02773–0.02862 | 41 / 35 |
| Supervised linear 128 | 0.02818–0.03106 | 17 / 20 |

Paired playlist-bootstrap intervals for selected models versus controls are retained in the JSON.
They are descriptive: playlists overlap in tracks/artists, so they are not independent population
confidence intervals. There is one artist/playlist split, not a multi-corpus evaluation.

## Transfer to the saved phone library and actual regions

Use the same **1,106 complete audio+text tracks**, dropping the one missing-text track from every
variant. No personal row trains or selects a checkpoint. This library and its playlist labels were
already inspected in earlier work, so this is descriptive transfer context, not a blind new test.

Current-control input rows come directly from the Kotlin-produced frozen fusion matrix. All
variants run the unchanged Kotlin clustering, confidence trimming, content routing, naming and
PCA-50/t-SNE. The learned heads and PCA are frozen from MPD; only normal per-library
centering/clustering/layout is rerun. This common-population control gives 18 worlds, different
from the earlier 17-world full 1,107-track replay. It is not an installed-app change.

The personal @15 metric is mean fraction of nearest neighbors sharing the query's personal
playlist, over the same 20 eligible playlists / 763 memberships. Same-artist and theme grouping
also contribute; it is not perceptual listening accuracy. The two dataset columns below are
**different tasks/metrics and must not be compared numerically with each other**.

| Representation | Personal playlist @15 before 2D | After 2D | Worlds | Assigned tracks | Dominant genre coverage |
|---|---:|---:|---:|---:|---:|
| Current 25/75, 1344D | 62.68% | 61.56% | 18 | 991 | 79.58% |
| PCA 64 | 62.25% | 60.83% | 18 | 984 | 72.30% |
| PCA 128 | 62.21% | 62.07% | 18 | 928 | 78.94% |
| MLP 64 | 44.45% | 40.22% | 18 | 890 | 71.73% |
| MLP 128 | 45.78% | 42.75% | 18 | 882 | 66.14% |
| Supervised linear 64 | 55.75% | 53.32% | 18 | 847 | 74.67% |
| Supervised linear 128 | 55.56% | 53.45% | 18 | 835 | 81.63% |

Dominant genre coverage counts, within each returned world, assigned tracks carrying the most
frequent known genre family, divided by assigned genre-known tracks. Tracks can carry multiple
families. Coverage and assigned population differ across variants, so a larger percentage alone
is not evidence of better regions. For example, linear128 improves this proportion while retaining
only 835 tracks, versus the control's 991.

MLP personal playlist coherence falls from **62.68% to 44.45–45.78%** before drawing the map,
and from **61.56% to 40.22–42.75%** after drawing. This is a representation-transfer loss, not
just a 2D layout artifact. PCA64/128 stay near **62.2%** before drawing. The present experiment
therefore supports neither replacing current regions with MLP nor calling 128 dimensions a
meaningful improvement over 64.

A plausible contributor is domain mismatch: the MPD text contains no track genres/years and
its audio consists of previews; the personal library has richer tags and different audio-window
coverage. The experiment does **not** isolate that cause. The co-occurrence objective and limited
training configuration may also matter. No new MOSS judging or human listening was performed.

## Follow-up: regions using audio alone

This separate comparison uses all **1,107 tracks**, including the row without metadata.
Audio-only geometry is built by the actual Kotlin fusion with an empty metadata-vector map.
Region construction, confidence thresholds and projection stay unchanged. Names and subsequent
special-content routing still consult tags/semantics. The full current control exactly reproduces
the previously saved 17 regions and their 974 assigned members.

| Full-library measure | Current 25/75 | Audio only |
|---|---:|---:|
| Final regions | 17 | 18 |
| Assigned tracks | 974 | 879 |
| Outside regions | 133 | 228 |
| Personal playlist neighbors @15 | 62.68% | 32.21% |
| Same-artist neighbors @15 | 26.18% | 11.34% |
| Dominant genre coverage | 77.14% | 68.56% |
| Mean within-region centered-audio cosine | .228 | .435 |

The audio-only result includes recognizable rock, rap, dance-pop, disco/funk and instrumental
groups. Some regions mix genres and some existing metadata-derived names poorly describe their
members. The detailed private report lists every region and its nearest-to-centroid tracks.

The audio-cosine measure favors the method optimizing that same space by construction; playlist,
artist and genre agreement instead favor metadata alignment. Neither set of proxies establishes
perceptual superiority. More tracks are left outside regions at the unchanged confidence
thresholds; this may partly require recalibration. No listening or MOSS evaluation was added.
Do not mix this full-library comparison with the 1,106-row transfer table above.

## Saved artifacts and reproducibility

Repository: `tools/research/map_scaling/mpd_fusion/` contains the Python runners, three Java
probes, the frozen protocol, and aggregate results only. Private raw rows, vectors, per-playlist
scores, coordinates, checkpoints and figures are in the local diagnostic archive
`smart-diag-2026-10-13/mpd-fusion/`. The new paired parquet remains available in the research
project for future experiments. No model was exported into the mobile bundle.

Use a fresh private output directory for an independent experiment. Dependencies are the existing
research environment: Python, NumPy, SciPy, pandas, pyarrow, mutagen, ONNX Runtime, Torch,
scikit-learn, threadpoolctl, Matplotlib, FFmpeg, CMake and Java 21. No new download, cloud pod,
phone access or dependency installation was needed. Build `libljq4` with the repository's CMake
project; build the core smart/library classes before preparing the classpath file.

```sh
python refresh_mpd.py --store OLD_MPD_PARQUET --assets APP_ML_ASSETS   --lib HOST_LIBLJQ4 --classpath-file CLASSPATH --output PRIVATE_DATA
python prepare_experiment.py --data PRIVATE_DATA --events MPD_EVENTS   --personal-metadata PRIVATE_PERSONAL_ROWS --output PRIVATE_EXPERIMENT
python train_fusion.py --data PRIVATE_DATA --experiment PRIVATE_EXPERIMENT
python train_fusion.py --data PRIVATE_DATA --experiment PRIVATE_EXPERIMENT --evaluate-test
python audit_transfer.py --data PRIVATE_DATA --experiment PRIVATE_EXPERIMENT   --personal-snapshot PRIVATE_PHONE_SNAPSHOT --classpath-file CLASSPATH
python verify_experiment.py PRIVATE_DATA PRIVATE_EXPERIMENT PRIVATE_PERSONAL_ROWS
python render_results.py PRIVATE_EXPERIMENT
python audit_audio_only.py --snapshot PRIVATE_PHONE_SNAPSHOT   --classpath-file CLASSPATH --output PRIVATE_AUDIO_ONLY
python render_audio_only.py PRIVATE_AUDIO_ONLY
```

Verification passed: split isolation for primary artist names and audio hashes; train-only
positive memberships; finite vectors; all 12 checkpoint hashes and exact inference replay;
nDCG agreement with independent scikit-learn on 25 actual test queries (max difference
1.4e-17); finite multi-positive loss gradients. Sequential float32 audio normalization has a
maximum norm error of 2.17e-6, within the documented 1e-5 check; training normalizes rows again.
Figures were rendered and visually inspected. No Android/iOS build was necessary because this
change adds research and documentation only.

A next experiment could retain the current representation and learn a small correction, and/or
supply track-level genre/year evidence for the training corpus. Those are hypotheses, not work
performed or a promise of improvement. These checkpoint weights should remain experimental.
