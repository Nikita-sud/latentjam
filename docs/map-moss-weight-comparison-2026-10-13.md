# Blind MOSS comparison of 25/50/75% audio weights

Date: 2026-10-13. Research only on `codex/map-performance-0.9.1`. No app defaults or model assets changed.

**Decision: the MOSS judge did not qualify, so its scores cannot select a winner.** The 50% variant is a candidate for human listening because it keeps nearly the same personal-playlist proxy and most region coverage. Neither that proxy nor the failed judge establishes better perceived musical coherence. Keep the released default at 25% pending a valid listening assessment.

## Exact library replay

All 1,107 tracks are included, including the one missing text vector. Each variant uses the actual Kotlin fusion with metadata weights .75/.50/.25, then unchanged centering, region construction, confidence trimming, special-content routing and PCA/t-SNE. The baseline input bytes and region memberships exactly reproduce the prior saved 25% replay. Nominal weights are the input block weights; centering changes per-track residual energy.

| Audio weight | Regions | Assigned | Outside | Playlist neighbors @15 | Same artist @15 | Playlist neighbors in 2D |
|---|---:|---:|---:|---:|---:|---:|
| 25% | 17 | 974 | 133 | 62.68% | 26.18% | 61.28% |
| 50% | 18 | 959 | 148 | 62.94% | 25.82% | 61.78% |
| 75% | 17 | 855 | 252 | 58.06% | 22.83% | 56.18% |

The 50% variant retains 77.74% of the baseline top-15 neighbor identities; 75% retains 53.81%. These are descriptive geometry/metadata proxies, not listening scores. At unchanged confidence thresholds, 50% leaves 15 more tracks outside regions and 75% leaves 119 more. Threshold recalibration is not part of this experiment.

## Frozen sampling and blindness

The upload contained **256 distinct tracks, 509 anonymous WAVs and 200 tasks**. Only opaque task/clip IDs, task type and audio were sent; names, tags, memberships, weights and the allocation key stayed local. Source matches use basename, exact byte size and duration; phone audio hashes were unavailable. There are 615 strong local matches with at least 12 seconds; the exact coverage count is in the JSON.

Neighbors: 36 distinct reference tracks, 12 each at true full-library neighbor ranks 1, 3 and 5. For each rank, sample uniformly from anchors whose reference and all three actual candidates have strong local audio matches. Never substitute a lower-ranked available track. Eligibility is 367/255/202 anchors out of 602 available MUSIC anchors. Two 12-second positions near 30%/65% of each song produce 72 main tasks; candidate ordering is randomized and hidden. Fourteen references have one common candidate for all weights, twelve have two distinct candidates, ten have three.

Regions: every final region with at least five strong available members supplies one central member, one lowest final-centroid margin member and three uniform others. The same five tracks are heard at two positions. This gives 43 sampled regions across variants: 14/14/12 MUSIC regions plus one NOVELTY group per variant; 86 main tasks. NOVELTY is excluded from the primary musical-coherence mean. There are also 18 seeded random-group tasks and twelve order reversals, plus twelve qualification controls. This is sampled region composition, not listening to all 1,107 full songs.

Audio is mono 16 kHz PCM16 with metadata stripped and original gain preserved. One excerpt decodes to 11.88 seconds; it is retained and recorded, not replaced after judging. The montage diagnostic pads that excerpt to the common segment length. Missing regions and neighbor eligibility remain coverage limitations.

## Model and execution

[MOSS-Music](https://github.com/OpenMOSS/MOSS-Music) source `ad107c7ddaa06de168a0dfbc18d3e1e6a40c0e5e`; `OpenMOSS-Team/MOSS-Music-8B-Instruct` weights `fce7f8304e96cc2d3398b8106456cbb2ecec3139`. Four model shards were SHA-256 verified. Official Transformers path: torch 2.9.1+cu128, transformers 4.57.1, BF16 A40, greedy generation, seed 2026101321, maximum 256 output tokens. Backend equivalence with the authors’ recommended SGLang route is not established.

Each neighbor request supplies four separate audio inputs; each region request supplies five. Explicit audio spans and semantic labels distinguish reference/candidates or recordings. Audio encoder hooks verify processing, and audio token counts are checked against each clip’s mel length. No intermediate captions are used.

## Judge qualification and diagnostic results

The preregistered twelve-control gate required all controls to pass. It passed **11/12**, so **no winner claim is permitted**. Three identical nonempty candidate waveforms received scores 0/2/2 in one control, with the first described as silent and the others as guitar/vocals. The shared candidate RMS was 0.115692 and peak 0.526856, with identical bytes. Two other equal-candidate controls tied numerically but were also described as silent/non-musical; passing an arithmetic tie check does not validate that description.

A bounded secondary serialization diagnostic used the same twelve controls, rubric and recordings as one audio montage with explicit time ranges and 0.5-second gaps. It passed **7/12**. The original failed run is retained; its threshold and results were never rewritten. The montage was introduced after primary-control failure and is not an independent preregistered comparison.

All 200 primary tasks completed. Strict schema: 200/200; usable normalized answers: 200/200; token-limit truncations: 0. Valid JSON is not evidence of valid listening. Among 108 identical-candidate pair comparisons, 21 differ by more than one score point (maximum 3). Across available order-repeat scores, mean absolute change is 1.083, maximum 3.

### Raw model scores — failed judge, not a ranking recommendation

Scores are ordinal 0..4 model opinions. Average the two positions within each reference/region first. MUSIC regions have different memberships and cannot be treated as paired populations. Bootstrap intervals in the JSON are descriptive; repeated tracks and overlapping regions break independent-population assumptions.

| Audio weight | Neighbor score | MUSIC region score | Complete MUSIC regions | Random-group score |
|---|---:|---:|---:|---:|
| 25% | 1.569 | 1.464 | 14 | 0.333 |
| 50% | 1.611 | 1.500 | 14 | 0.000 |
| 75% | 1.375 | 1.083 | 12 | 0.333 |

The numerical differences above must not be promoted to musical accuracy or a preferred weight because the judge contradicted identical inputs. Exact-key/tempo claims in free-text reasons are unverified and excluded from conclusions.

## Artifacts and verification

Private diagnostics live in `smart-diag-2026-10-13/moss-weight-comparison/`: all WAVs, allocation, identities, full raw responses, model environment, logs, source provenance, figures and a Russian region dossier. The repository contains only reproducible runners, the fixed protocol and aggregate results.

Verification: exact baseline input and region replay; 200 unique tasks; 509 WAV SHA-256 and stripped RIFF metadata; true full-catalogue ranks; 36 distinct references with two positions each; frozen plan/manifests; unchanged inference runner/model identities on resume; encoder processing and downloaded output hashes. Figures were inspected visually. No Android/iOS build is needed for research-only files.

The pod was a temporary A40 in EU-SE-1 at the live quoted compute rate $0.59/hour. Lifecycle and cleanup verification are recorded in the execution provenance.

## Reproduce

Use a fresh private output directory and the existing snapshot format. The current compiled Kotlin smart/library classes plus Gson/Kotlin stdlib form `CLASSPATH`. Use Java 21, NumPy/SciPy/threadpoolctl, FFmpeg, Matplotlib locally and the pinned MOSS runtime from the sibling `moss/bootstrap.sh`.

```sh
python prepare_comparison.py --snapshot PRIVATE_SNAPSHOT --output PRIVATE_ROOT --classpath-file CLASSPATH
python verify_comparison.py PRIVATE_ROOT
python render_comparison.py PRIVATE_ROOT
# On the dedicated GPU host, upload only PRIVATE_ROOT/upload and the runner/helper:
python judge_audio.py --repo-dir PINNED_MOSS --weights-dir PINNED_WEIGHTS --model-api-json HF_MODEL_JSON --clips-root ANONYMOUS_CLIPS --tasks ANONYMOUS_TASKS --output judgements.jsonl --max-tasks 12
# Read qualification outputs locally; continuation in this run was diagnostic despite the failed gate.
# Preserve the same runner/input/output and add --resume --max-tasks 200 for that continuation.
python analyze_results.py PRIVATE_ROOT
```
