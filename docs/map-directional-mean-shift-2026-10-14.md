# Directional mean-shift on the frozen personal library

2026-10-14 · research only · no app changes.

**Keep the current region algorithm for 0.9.1.** Directional mean-shift produces
more specific small groups, but at a comparable final region count its coverage
is substantially lower. This is not evidence of better or worse perceived music
coherence; it is evidence against an unqualified replacement in the current app.

## Controlled comparison

All 1,107 rows, exact current 25% audio/75% metadata fusion, full 1,344D centered
unit vectors. No PCA, training, audio upload, MOSS inference or weight change.
Non-blurring von Mises directional mean-shift; all rows initialize trajectories.
Each iteration normalizes a kernel-weighted sum of the fixed input directions.
Kappa=1/h², so larger kappa narrows smoothing. Exact Kotlin product postprocessing
is shared: minimum 4, final-centroid evidence, confidence, content routing, naming.
Raw modal basins and no-confidence product variants are preserved separately.

The current 17 regions/974 tracks reproduce exactly, including titles and order.
The saved centered matrix equals a fresh Kotlin replay byte for byte. Existing
t-SNE coordinates are unchanged across figures: only memberships/colors differ.

| Method | Raw groups | Final regions | Assigned | Outside | Largest final region |
|---|---:|---:|---:|---:|---:|
| Current spherical k-means | 16 | 17 | 974 | 133 | 148 |
| DMS κ=7.5 | 15 | 11 | 775 | 332 | 170 |
| DMS κ=8 | 51 | 11 | 736 | 371 | 157 |
| DMS κ=8.5 | 103 | 16 | 730 | 377 | 151 |
| DMS κ=9 | 154 | 21 | 682 | 425 | 150 |
| DMS κ=10 | 298 | 24 | 644 | 463 | 145 |

Raw group counts include tiny modes discarded by product rules. Final counts
also reflect semantic routing and naming. They need not move monotonically with
bandwidth. Without just the confidence filter, κ=8.5 includes 863 tracks and κ=9
includes 835; lower coverage is not entirely caused by the old centroid margin.
Those thresholds were designed for k-means, so this is a controlled integration
comparison, not proof that DMS with retuned admission rules cannot work.

## Interpretation and selection disclosure

The coarse grid was 2,4,6,8,10,12,16,24,32,48,64. All settings converged. A frozen
count-only display selector chose κ=32, because 20 retained regions look close to 17
even though 827 raw modes left only 255 retained tracks. The coarse result remains
archived; it is not hidden or described as successful model selection.

An explicitly exploratory refinement added 6.5,7,7.5,8.5,9,9.5 around the sharp
transition. Kappa 9 was fixed as the midpoint of 8/10 before refinement. Kappa 8.5
is additionally shown for its comparable 16-region UI. Every setting's aggregate
result is in the JSON. No held-out quality claim or listening winner is made.

The detailed private report shows both larger broad groups and smaller groups
associated with specific albums/composers/styles. Several useful broad baseline
collections cease to appear in that form. At κ=8.5 six large regions contain 631 of
730 assigned tracks, while ten small regions share the other 99. Thus, the result
is not simply a uniformly more precise partition.

On three seeded 10% deletions, with exact centering recomputed each time, raw
assignment ARI was .887–.961 for κ=8.5,.941–.944 for κ=9 and .706–.777 for k-means.
Different group counts and no final name/filter replay limit this comparison.
Stricter convergence and reverse merge order preserve the 8.5/9 assignments.

Retained full-space cosine silhouettes are .303 current,.405 κ=8.5,.398 κ=9.
They describe different retained populations; higher values cannot establish a
whole-library improvement. Metadata/playlist proxies inherit similar coverage
and representation biases. No perceptual assessment was performed.

## Validation and artifacts

Independent implementation agrees with the authors' reference on a separate
fixture, recovers three known spherical caps, preserves that fixture under
permutation, increases KDE log density in a real-data one-step check, and remains
unit-normalized. The reference commit and all input/source hashes are archived.
The current product baseline and centered matrix are exact replays.

All 17 bandwidth settings converged. The report preserves mode-merge sensitivity,
raw memberships, common product processing, no-confidence diagnostics and full
private track lists. Browser checks cover the report's variant switch, region
selection, list expansion, search, reset and canvas, with no console errors.
No Android build was run because no production source changed.

Public runners and aggregate results: `tools/research/map_scaling/directional_mean_shift/`.
Private durable archive: `smart-diag-2026-10-13/directional-mean-shift-2026-10-14/`.
Track identities, vectors, per-row memberships and reports remain outside Git.

Sources: [Zhang & Chen, JMLR 2021](https://jmlr.org/papers/v22/20-1194.html),
[authors' source at pinned commit](https://github.com/zhangyk8/DirMS/tree/9a9fd6754a3eec0bf7eba7260a268fb3dbf67112).
