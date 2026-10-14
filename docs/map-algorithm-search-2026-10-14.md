# Region algorithm and audio-weight search — 2026-10-14

**Practical candidate: average-linkage hierarchical clustering, 75% audio / 25% metadata, cosine-distance cut 0.75, minimum final region size 4.** Use the hierarchy's memberships, then existing semantic routing and naming; do not force small or rejected groups into another region. This is the best-supported practical candidate in this experiment, not a universal optimum or a validated rate of musically correct assignments. Production code is unchanged.

The candidate gives **35 regions, 898 included tracks and 209 outside**, versus the current **17 / 974 / 133**. It produces smaller, more specific areas, with some mixed or poorly named groups still present. An algorithm cannot currently certify every membership as correct.

## What was compared

The frozen 13 October owner snapshot contains 1,107 tracks, 960-dimensional shipped audio vectors and 384-dimensional metadata vectors. One metadata vector is absent. Exact Kotlin fusion, missing-modality handling, corpus centering and normalization were reused. All clustering uses the full 1,344-dimensional space, not the 2D projection. The maps in the private report deliberately retain the same original coordinates.

Weights were 25%, 40%, 50%, 60%, and 75% audio. Five families were tested:

- Actual production spherical k-means: k = 12, 16, 24, 32.
- Directional mean-shift with von Mises kernel: κ = 4, 6, 7, 8, 8.5, 9, 9.5, 10, 12, 16, 24, 32.
- Directional mean-shift with uniform update weights inside spherical caps: cosine radii 0.25–0.65. These are the mean-shift updates for a compact Epanechnikov density profile, not a claim that von Mises was a uniform kernel.
- HDBSCAN: minimum group sizes 8/16/32, minimum samples 4/8/16, EOM and leaf extraction, Euclidean chord distance on normalized directions.
- Average linkage: cosine-distance cuts 0.35/0.45/0.55/0.65/0.75.

There were **220 algorithm/weight settings and 440 outputs**, including with and without the production centroid-confidence filter. The extra DMS refinement was declared before any new quality scores were inspected, because the previous experiment had already identified a narrow transition at κ≈8–10. The baseline was reproduced exactly, including final names and ordered members.

Noise rows are removed before centroid collection, never combined into one artificial noise cluster. The selected average-linkage variant bypasses the k-means centroid-margin filter, but still applies the product's minimum size, semantic routing, genre trimming and naming. It does **not** add a CLAP model to the application.

## Independent audio audit

508 previously authorized local anonymous excerpts were embedded locally with cached `laion/larger_clap_music`, revision `a0b4534a14f58e20944452dff00a22a06ce629d1`, transformers 4.57.6. No new cloud resources or uploads were used. Deterministic central 10-second crops were resampled from 16 to 48 kHz. The released audio encoder was not trained to imitate this CLAP checkpoint.

252 tracks had both excerpts and entered the audit. Four additional tracks had only one excerpt and were excluded. A deterministic artist split gave **107 development tracks and 145 held-out tracks**, with no exact normalized artist-string overlap. Development used excerpt 0; holdout used excerpt 1. Name aliases and collaborations were not resolved into a global artist identity graph.

Control checks: median rank of another excerpt of the same track was **98.4th percentile** among the 252 tracks; top-1 retrieval was 33.7%. Six repeated embeddings were bit-identical. Vectors were finite and noncollapsed. These checks support an acoustic proxy, not musical judgement.

For each included audit track with at least two different-artist region peers, compute each peer's CLAP cosine percentile relative to all eligible different-artist tracks in that audit split, and average those percentiles. Then average across scored tracks. **This is a rank of acoustic similarity, not a percentage of correct assignments.** Same-artist-only areas are unscored by this primary measure, not declared bad.

The frozen development selection rule required ≥55% library coverage, ≥45% audit-score coverage, 8–45 final regions and no region above 30% of the library. Within 1 similarity-rank point of the best development score, choose greater coverage. Floors of 35% and 70% were also reported. These usefulness guardrails are assumptions; low coverage does not itself imply poor quality.

## Why the development winner is not the final recommendation

Development selected **DMS 75/25, κ=10**, with 15 final regions and 745 included tracks. On its own surviving held-out population, its mean rank was 59.3 versus 55.7 for the baseline. However, the populations differ. On the **same 69 scored tracks**, its gain was only **+1.4 points**, with substantial uncertainty.

Each family's configuration was selected before holdout inspection. Comparing these fixed family candidates on common tracks against the baseline gives:

| Candidate | Final regions | Included / outside | Common audit tracks | Similarity-rank change | Artist-bootstrap interval* |
|---|---:|---:|---:|---:|---:|
| Average linkage, 75/25, cut 0.75 | 35 | 898 / 209 | 97 | **+4.37** | **+0.33 to +8.52** |
| Compact mean-shift, 50/50, radius 0.55 | 37 | 807 / 300 | 71 | +2.68 | −0.81 to +6.49 |
| von Mises DMS, 75/25, κ=10 | 15 | 745 / 362 | 69 | +1.41 | −2.80 to +5.53 |
| Spherical k-means, 60/40, k=24 | 26 | 1018 / 89 | 114 | +2.75 | −1.56 to +7.34 |

*10,000 bootstrap draws resampling artist groups, 98.75% intervals for four comparisons (approximate Bonferroni adjustment). These are conditional proxy estimates, not causal effects or listener-accuracy bounds. Dependence between region peers remains a limitation. Each row has its own common-track population. On the 45 anchors shared by all four candidates and baseline, the scores are respectively 67.2, 62.9, 60.1, 60.4 and baseline 58.6; this smaller intersection is a sensitivity check.

Average linkage also improves the lower quartile on its 97 shared anchors: **46.6 → 51.0**. That supports the result beyond just its mean. The recommendation uses these paired results, the larger retained population than DMS, and inspection of actual region contents. It is a comparison of four previously selected candidates; there is no further untouched dataset confirming the final choice between them.

HDBSCAN did not meet the frozen usefulness/audit guardrails: depending on settings it generated a few broad groups or small islands with inadequate cross-artist audit coverage. This is not evidence that HDBSCAN is universally inferior, nor that excluded tracks should be forcibly reassigned.

## What weight works for mean-shift?

The development-selected DMS settings for each weight produced these held-out ranks on their **own, differing** scored populations:

| Audio / metadata | κ | Included | Held-out similarity rank |
|---|---:|---:|---:|
| 25 / 75 | 8.5 | 863 | 56.0 |
| 40 / 60 | 9 | 837 | 59.1 |
| 50 / 50 | 8.5 | 886 | 54.6 |
| 60 / 40 | 9 | 856 | 58.3 |
| 75 / 25 | 10 | 745 | 59.3 |

Thus **50/50 is not established as optimal**, and 75/25 is a useful DMS candidate, but a precise optimum over 40–75% audio is not identified. On 59 common anchors, DMS 75/25 versus 40/60 was −1.4 points (95% artist bootstrap −3.0 to +0.4): 75/25 cannot be claimed superior there. Ratios above 75% and other algorithms were not searched; conclusions are limited to the tested grid.

Weights refer to normalized blocks scaled by square roots before production centering. After centering, mean squared audio-block norm is 17.2%, 29.1%, 37.9%, 47.5%, 64.0% for nominal 25/40/50/60/75%. This is a geometric diagnostic, not a perceptual contribution percentage.

## Stability and checks

After three independent deletions of 10% of tracks, refitting with fresh production centering:

- DMS 75/25 κ=10: raw-cluster ARI **0.917–0.937**.
- Average linkage 75/25 cut 0.75: **0.670–0.747**.
- Current baseline: **0.706–0.777**.

Noise and groups smaller than four are separate singletons for this ARI, not one artificial common noise group. Therefore DMS has the strongest stability, and average linkage sacrifices that advantage for better matched acoustic cohesion. These are raw-cluster stability measurements, not final named-region identity guarantees. Android latency and memory were not benchmarked here.

Known three-cap synthetic fixtures were recovered exactly by all four Python families; DMS previously matched the authors' implementation. The exact current Kotlin baseline and byte-level input checks passed. No application tests/build were needed because this is research-only.

## Limits and next product decision

The audio subset is nonrandom, includes only 252 of 1,107 tracks and was originally sampled for another experiment. Metadata influences both clustering and product naming. Broad soundtrack areas and ambiguous small mixes remain; no result justifies automatic assignment of every track. MOSS scores were excluded because its earlier controls failed. CLAP alone cannot validate musical correctness, and one library does not establish defaults for every user.

For the next implementation experiment, use **average linkage 75/25 / cosine cut 0.75 / minimum size 4**, preserve unassigned tracks, and inspect the ambiguous areas before release. Keep DMS 75/25 κ=10 as the stability-focused alternative. This recommendation changes the region experiment, not the map projection or production version by itself.

Methods: [directional mean-shift, JMLR](https://jmlr.org/papers/v22/20-1194.html), [scikit-learn HDBSCAN 1.8](https://scikit-learn.org/1.8/modules/generated/sklearn.cluster.HDBSCAN.html), [CLAP model card](https://huggingface.co/laion/larger_clap_music). Aggregate results and a runnable analysis notebook accompany this report; private vectors, track identities, assignments, audio and the interactive region browser stay outside Git.
