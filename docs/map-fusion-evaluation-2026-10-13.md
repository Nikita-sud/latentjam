# Map fusion and mixed-region fixes — 2026-10-13

The current hybrid is a reasonable baseline, not a demonstrated optimum for perceptual music
similarity. This follow-up keeps the production metric and layout version unchanged. It adds
an offline normalization/weight diagnostic and fixes a confirmed mixed-region presentation defect
on `codex/map-performance-0.9.1`.

## What the app actually combines

`LibraryVectorFusion` independently L2-normalizes the 960-dimensional audio embedding and the
384-dimensional metadata embedding. With complete modalities, the input is

```text
z = concatenate(sqrt(0.25) * unit(audio), sqrt(0.75) * unit(metadata))
cos(z_i, z_j) = 0.25 * cos(audio_i, audio_j) + 0.75 * cos(metadata_i, metadata_j)
```

The square-root factors are necessary: dot products square each block's scale. The differing
dimensions cause no implicit 960:384 weighting after independent normalization. Directly adding
the two vectors would be invalid; even reducing them to equal dimensions would not align their
coordinate meanings. A simple weighted sum of the **same cosine scores** is algebraically the
same metric, not a more expressive fusion method.

Metadata text contains genre, artist, original year (with fallbacks), and language; album and
playlist memberships are not directly encoded. Playlists/albums can subsequently supply region
names. Missing-modality masking/fallback is handled separately, so the identity above is for
complete pairs only.

Region discovery runs spherical k-means after subtracting the library mean and normalizing each
fused row. The map independently applies centering/normalization, PCA-50, normalization, and t-SNE.
Regions are therefore not clusters computed from the displayed 2D coordinates.

Both post-fusion normalization and PCA change geometry. On the full saved production replay
(1,107 tracks; 1,106 complete), the audio block's squared-norm share after centering has complete-row
p10/median/p90 of **11.86% / 16.41% / 23.94%**. This measures geometric energy, not semantic relevance,
a causal contribution, or a fixed post-centering cosine weight. The production-centered rows agree
with an independent NumPy reconstruction within **1.67e-7** maximum absolute coordinate error.

## Controlled offline comparison

All following comparisons use the same **1,106 complete tracks**, excluding the single missing-text
row from every variant, and the same 20 eligible playlists / 763 memberships. Neither naming nor
playlist membership is used to build the vectors. The score is the fraction of a playlist member's
15 nearest tracks that are in that playlist, averaged over memberships. It rewards themes and
artists as well as sound, and is not listening accuracy.

The grid compares current joint centering with a candidate that centers and unit-normalizes each
modality independently before weighting/concatenation. It includes audio weights 0, .1, .25, .35,
.5, .65, .8, 1. The candidate is **not WNN**, does not learn confidence, and can amplify weak/noisy
residual directions. Three selected cases additionally run the actual Kotlin production PCA path,
including its existing common centering step. No t-SNE was run in this comparison.

| Geometry | Audio / metadata | Full-space playlist @15 | After production PCA-50 |
| --- | --- | ---: | ---: |
| Metadata only, centered | 0 / 100 | 60.98% | Not run |
| Current joint centering | 25 / 75 | 62.68% | 62.55% |
| Current joint centering | 35 / 65 | 63.05% | Not run |
| Current joint centering | 50 / 50 | 62.94% | 62.51% |
| Current joint centering | 65 / 35 | 61.17% | Not run |
| Separate normalization | 25 / 75 | 63.31% | 62.80% |
| Separate normalization | 35 / 65 | 63.37% | Not run |
| Audio only, centered | 100 / 0 | 32.23% | Not run |

Separate normalization at 25/75 improves this full-space proxy by **0.63 percentage points**, but
only **0.25 points** after the existing PCA stage. Its exploratory paired playlist-bootstrap interval
before PCA is [+0.09, +1.27] points. This is descriptive: playlists overlap, the grid is exploratory,
the library is not held out, and no independent listening evaluation was performed. The slightly
larger full-space point estimate at 35/65 has an interval crossing zero. A global 50/50 change does
not improve the post-PCA point estimate. More audio-only agreement is not evidence of better music
similarity, since the audio embedding itself is an imperfect reference.

**Decision:** preserve production 25/75; retain separate normalization at 25/75 as a reproducible
candidate. Before adoption, measure final-region coverage/stability and 2D neighbor preservation,
use another library, and compare fixed central/boundary tracks by listening. A grid winner from
one library is not a universal default. The earlier full-library region replay remains the source
for assignment coverage; this diagnostic does not recompute candidate regions.

## More expressive candidates

- **Adaptive neighbor-graph fusion.** Build separate audio and metadata neighbor graphs, calibrate
  local distances, then combine with per-track modality weights. This can represent changing
  usefulness of tags across tracks and supply a common graph for grouping and layout. WNN is a
  concrete reference implementation, developed for single-cell data, not validated here for music:
  [Hao et al. / Seurat WNN](https://satijalab.org/seurat/articles/weighted_nearest_neighbor_analysis).
  Internally consistent tags can still be wrong; adaptive confidence must not be sold as truth.
- **Graph combination for projection.** UMAP supports intersection/union of representations of the
  same samples from different feature spaces. This offers an offline baseline, not an automatic
  drop-in replacement for the current Kotlin pipeline:
  [UMAP model composition](https://umap-learn.readthedocs.io/en/latest/composing_models.html).
- **Learned alignment/projections.** Contrastive learning can align complementary audio/editorial
  representations and handle missing modalities, but requires suitable training/evaluation data.
  Artist retrieval evidence does not establish better track regions:
  [Ferraro et al., 2023](https://arxiv.org/abs/2308.06556).

Complexity alone is not a reason to switch. In a different task, language-based audio retrieval,
simple late fusion outperformed a more complex multimodal-transformer fusion in the authors'
experiments: [Primus and Widmer, 2024](https://arxiv.org/html/2406.15897v1). That result motivates
keeping simple baselines; it is not a benchmark of LatentJam.

## Changes actually included in 0.9.1

- Weak leading-style and two-family titles explicitly say `• Mix`, separating them from a pure
  genre claim. Strong supported genre/blend names and curated playlist/album names retain their
  existing presentation.
- A `MAJOR_FAMILIES` region now records the two named families, moves the nearest supporting
  member to the representative position, and uses those families in `supportsName` when ranking
  fresh cover candidates. Previously any genre passed that check and the representative could
  contradict the name. The existing For You fallback when all supporting candidates are unavailable
  is unchanged; this is a support/preference fix, not a guarantee for every fallback state.
- Corrected stale metadata-only documentation and documented the precise limits of the 25/75
  interpretation. No genre alias remapping, modality weight change, or membership threshold change.

A fresh replay of the saved full library retains **17 regions / 974 assigned tracks**, with every
region's member set identical to baseline. Two displayed names change: `Rap + Dance • Mix` and
`Game ost + Production music • Mix`. Their existing representatives already support the names;
the bad-representative case is covered by a regression fixture. Other members retain their relative
centrality order when the representative moves. These changes improve presentation/selection;
they do not establish increased acoustic homogeneity.

## Reproduction and validation

Research files are in `tools/research/map_scaling/`:

- `audit_fusion_geometry.py`: NumPy diagnostic and aggregate-only JSON.
- `FusionProjectionProbe.java`: calls the compiled Kotlin production PCA on three private matrices.
- `fusion-geometry-2026-10-13.json`: frozen aggregates, input hashes, full grid and limitations.
- `region-naming-2026-10-13.json`: aggregate before/after replay changes.

Use an external/private output directory. Supply the earlier private replay directory containing
`audio.f32`, `metadata.f32`, and `private-playlists.json`. Run the Python script once, compile/run
the Java probe against `core/smart/build/classes/kotlin/android/main` and Kotlin stdlib, then rerun
Python with the same arguments. PCA outputs carry an input SHA-256 and mismatched cached output
is rejected. The full-library geometry check additionally uses the old input/centered f32 files
when present. Do not commit vectors, playlist contents, row IDs, or the temporary replay directory.

Validation: all **525 SMART + 1,058 Compose host tests passed**, including weak-title and
unsupported-representative cases. Android release assembly, the DEX register check, and iOS simulator
compilation also passed. No phone install or on-device visual/performance
claim is made for these changes.
