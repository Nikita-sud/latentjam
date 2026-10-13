# Direct 2D MLP and learned compression pilot — 2026-10-13

**Decision: do not replace the 0.9.1 map or region engine with this pilot.** Direct neural 2D
projection works mechanically and has a cheap forward pass, but it generalizes poorly to the
held-out artists here. Keeping 64 dimensions before projection does better. A linear 64-dimensional
control preserves the original high-dimensional neighbors substantially better than the learned
64-dimensional representation, so this experiment does not establish MLP as the best fusion method.

This is a **geometry experiment on the saved phone snapshot**, not MPD/ListenBrainz training,
not an independent listening evaluation, and not a release checkpoint. It preserves the existing
25% audio / 75% metadata similarity as the target. Learning a better notion of musical relevance
requires additional supervision; fitting the existing metric cannot demonstrate that improvement.

## Other methods and the meaning of a direct 2D output

An MLP describes the function class; its training objective determines what it preserves. Relevant
alternatives include learned gates, CCA/DCCA, neighbor-graph fusion, autoencoders, and parametric
neighbor embeddings. They are not all competing replacements at the same pipeline stage.

- [Parametric UMAP](https://umap-learn.readthedocs.io/en/latest/parametric_umap.html) learns a neural
  mapping from input to display coordinates using a neighbor-preservation objective. It is a
  concrete reference for the user's `two embeddings -> 2D` idea; this pilot uses a t-SNE-style KL
  objective, **not the official ParametricUMAP implementation**.
- [DCCA](https://proceedings.mlr.press/v28/andrew13.html) learns nonlinear transformations of two
  views whose outputs are correlated. It is a candidate for representation alignment, not by itself
  proof that a 2D music map improves.
- [WNN](https://satijalab.org/seurat/articles/weighted_nearest_neighbor_analysis) integrates separate
  neighbor graphs with object-specific modality weights. Its published biological application
  does not validate it for this music library.

The richer representation should continue to determine region membership. Two coordinates are
useful for display, but necessarily lose neighborhood information. Do not define a region merely
because its points overlap in a lossy 2D drawing.

## Frozen inputs and evaluation split

The current export has 1,107 visible tracks; this pilot uses the **1,106 complete audio+text rows**.
It joins primary artist identities and exact duplicate audio embeddings into groups before splitting:

| Split | Tracks | Groups | Use |
| --- | ---: | ---: | --- |
| Train | 676 | 435 | Fit preprocessing and network weights |
| Validation | 118 | 94 | Select epochs and illustrative models |
| Test | 312 | 141 | Evaluate after checkpoint selection |

Split seeds are 1729/1730. Groups are disjoint, but artist aliases and all collaboration credits are
not exhaustively resolved, so this is a primary-artist split, not a guarantee of zero musical overlap.
Each modality is normalized, weighted by sqrt(.25/.75), concatenated, then centered using only
the training mean and normalized. No test feature statistics fit preprocessing.

For every query, the reference is its 15 nearest **training** tracks in the original centered fused
space. A query excludes itself if it is a training track. Test metrics therefore assess adding new
tracks to a fixed catalogue, not recalculating a map that has already seen every test track.

Playlist memberships are used only as a secondary metric, never in fitting or checkpoint selection.
The test has 129 eligible query-playlist memberships, with overlapping playlists. This is a small
proxy for user intent, not perceptual ground truth.

## Models and controls actually run

All six MLP fits use fixed seeds **3, 17, 41**, AdamW, learning rate .002, weight decay .0001,
gradient clipping 5, CPU execution with four threads and deterministic Torch operations.

Audio branch: `960 -> 128 -> GELU`. Metadata branch: `384 -> 64 -> GELU`.
Concatenate their outputs, then `192 -> 128 -> GELU -> output`.

1. **Direct output 2:** 172,610 parameters, unconstrained 2D coordinates. Minimize t-SNE KL against
   training-only Gaussian affinities with target perplexity 20. Maximum 800 epochs; validation
   recall checked every 25 epochs, early stop after 200 epochs without improvement after epoch 300.
   The 2D output is **not unit-normalized**, which would restrict it to a circle.
2. **Output 64:** 180,608 parameters, unit-normalized output. Minimize squared error of the fused
   training cosine Gram matrix. Maximum 600 epochs, same checkpoint criterion. Then run the
   unmodified Kotlin PCA-50/t-SNE on training outputs; place new tracks by fixed 5-neighbor inverse
   squared-distance interpolation in the learned 64-dimensional space. Epochs are selected by
   validation recall in 64D; the illustrative seed is selected afterward by final-2D validation
   recall. These fits still improved
   at the 600-epoch budget; they are bounded pilots, not demonstrated converged optima.
3. **Production-style control:** unmodified Kotlin PCA-50/t-SNE on raw fused training rows, plus
   the same 5-neighbor interpolation for unseen rows using the original fused space.
4. **Linear controls:** PCA-2 fitted on training rows; ridge regression to the training t-SNE
   coordinates; and PCA-64 followed by unit normalization, the same Kotlin projection, and kNN
   interpolation. The PCA-64 control was added after seeing the initial pilot, so the comparison
   is exploratory rather than a new untouched test.

No test target coordinates from a full-library layout are used for neural training. The final
full-library app projection is generated only as separately labelled transductive context.

## Results

| Method | Test neighbor recall @15 | Interpretation |
| --- | ---: | --- |
| Original 1344D | 100% | Reference, not a drawable map |
| Linear PCA-2 | 25.90% | Cheap 2D baseline |
| Ridge to training t-SNE | 16.13% | Coordinate-regression baseline |
| Production-style projection + kNN-5 | 33.89% | Inductive comparison control |
| MLP directly to 2D | **18.91–25.30%** | Three initializations; median 24.02% |
| MLP to 64D, then projection + kNN-5 | **38.29–45.30%** | Three initializations; median 44.19% |
| Linear PCA-64, then projection + kNN-5 | **39.34%** | Non-neural compression control |

Before the final 2D projection, the MLP64 variants retain **62.99–64.02%** of test neighbors;
PCA64 retains **87.09%**. The MLP64 seed selected by final-2D validation recall gets **38.29%** on test,
slightly below the linear control. Choosing the highest test score instead would overstate the
case for MLP. Direct MLP2 keeps roughly 47–54% on its training tracks but only 19–25% on new artists,
consistent with poor generalization under this small-data/objective/architecture configuration.
This does not reject all neural projection architectures or larger training corpora.

The separate full-library Kotlin projection has recall **55.53%**, trustworthiness **0.97186**,
and playlist agreement **61.95%** across all 1,106 tracks. It sees every track while fitting, so those
figures must not be ranked against the inductive test scores above.

The direct head's measured CPU forward pass for all 1,106 already-preprocessed vectors is
**0.68–0.82 ms** on this host. That excludes the encoders, preprocessing, model loading and training;
it is not phone latency or an end-to-end map speedup. Personal-library weights are not shipped.

## Which corpus should train a semantic fusion model?

**MPD is the practical first source here.** It supplies playlist co-occurrence as weak supervision;
ListenBrainz supplies listening events and is useful for multi-listener/time-based validation or
additional training signals. Neither source automatically provides this app's paired audio/text
embeddings or a ground-truth 2D position.

- [MPD](https://www.aicrowd.com/challenges/spotify-million-playlist-dataset-challenge): track lists,
  IDs and metadata for user-created playlists. Same-playlist positives should be weighted/sampled,
  not treated as proof that every pair sounds alike. The current challenge page says downloads
  are no longer available there; this project already has a local archive and event tables.
- [ListenBrainz dumps](https://listenbrainz.readthedocs.io/en/latest/users/listenbrainz-dumps.html):
  listening events. They require recording matching and audio availability before paired-vector
  training. Consecutive plays and popularity are imperfect proxies for similarity.

The existing `mpd_mnv4_distilled.parquet` contains **18,386 rows**, all 960-dimensional, with
**18,386 existing local audio paths**. Its model version is `mnv4-conv-m-distill-mw-ep4+v3`, not the
current `mnv4-960-retrieval-distill-v1-p50q4aq`. Genre and year columns are entirely null. This is a
useful source catalogue, not a ready training matrix for the current fusion head. Recompute audio
with the shipped model and construct metadata inputs using the shipped text pipeline, representing
missing fields honestly. Keep personal test-library tracks out of a release model's training and
checkpoint selection; audit track/artist/playlist overlap across public splits.

A next semantic model should first learn a useful 64–128D similarity representation from those
signals, with modality dropout and missing-input masks, and be compared with linear and fixed-weight
baselines. Train a 2D display head as a separate objective if the richer representation passes.
MPD-only success must not be presented as perceptual audio similarity or ListenBrainz generalization.

## Reproducibility and verification

`tools/research/map_scaling/parametric_map/` contains the runner, Kotlin JVM probe, validation/plot
helper and aggregate summary. Required Python packages: Torch, NumPy, SciPy, scikit-learn,
Matplotlib. Use the existing research Python environment; no new dependency installation or cloud
pod was needed. Compile the unchanged core module before using its classpath.

Run `parametric_map_pilot.py --input-dir PRIVATE_REGIONS --output-dir PRIVATE_OUTPUT
--classpath-file CLASSPATH_FILE`, then `verify_and_plot.py PRIVATE_OUTPUT PRIVATE_REGIONS
CLASSPATH_FILE`. `MapProjectionProbe.java` must be beside the pilot script. The classpath includes
the compiled core smart classes and Kotlin stdlib. Java 21 was used.

Assertions cover finite inputs/outputs, valid shapes, disjoint split groups, normalized affinity
mass, and noncollapsed outputs. The training-set trustworthiness calculation independently agrees
with scikit-learn within **1.2e-16**. The comparison figure was rendered and visually inspected.
Public summaries contain counts/metrics/hashes only. Input vectors, row splits, coordinates and
checkpoints remain in the private diagnostic archive. No application code, layout version,
default fusion weight, or region membership changed in this experiment.
