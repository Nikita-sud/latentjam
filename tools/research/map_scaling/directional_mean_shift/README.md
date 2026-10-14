# Directional mean-shift library experiment

Research only, 2026-10-14. No app code, weights, assets, defaults or caches change.
Private inputs/outputs must stay outside Git. `results-2026-10-14.json` contains
aggregate results only; report builders deliberately produce private track lists.

## Method and provenance

Zhang & Chen (2021), https://jmlr.org/papers/v22/20-1194.html.
Reference code: https://github.com/zhangyk8/DirMS at
`9a9fd6754a3eec0bf7eba7260a268fb3dbf67112`.

`directional.py` independently implements the non-blurring von Mises update on
unit vectors, float64, all rows as seeds. It rescales exponents to avoid underflow
and retains only the current iteration. The source-order mode merge matches the
authors' `Unique_Modes`; the tolerance is cosine distance. We supply bandwidth
explicitly, never use the high-dimensional rule-of-thumb Bessel estimator, and
check actual convergence rather than relying on the reference's printed message.

Exact Kotlin fused+centered 1,344D vectors, audio/metadata weights .25/.75,
1,107 rows. `DmsRegionProbe` calls private Kotlin `collect` and `worlds` through
reflection, intentionally restricted to this research harness, so min-size,
centroid evidence, confidence, content routing and naming are the actual app's.
No-confidence variants remove just centroid-confidence filtering; other product
rules still apply. Raw basins are also retained. Special content can make the
final region count exceed the raw mode count.

The coarse protocol preceded all results. Its count-only main selector chose
kappa 32 (20 final regions but only 255 tracks), illustrating why matching the final
count is not a quality criterion. `refinement-protocol.json` records the subsequent
exploratory transition sweep. Kappa 9 was fixed before refinement as the midpoint
of 8 and 10; kappa 8.5 is additionally shown because 16 final regions are comparable
in count to 17 baseline regions. No bandwidth is declared a musical-quality winner.

## Reproduce

Runtime versions are in `requirements-observed.txt`; use the existing research
environment. Java 21 and compiled Kotlin smart/library classes plus Kotlin stdlib
and Gson are required, as in the adjacent `moss_weights` experiment.

Create a PRIVATE output directory with `src/` containing these source files and
the two protocol JSON files in its root. Obtain `DirMS_fun.py` and `Utility_fun.py`
from the pinned authors' commit in PRIVATE_ROOT/source, and write a commit.json
with `repo` and `commit` keys there. The reference files are used only for a small
numerical parity check; read the functions before executing an unfamiliar source.

Required snapshot format: `regions/audio25-meta75-input.f32`, the exact Kotlin
`audio25-meta75-centered.f32`, `audio.f32`, private input/semantics/cooked-metadata
JSON files and `classpath.txt`. Use the final baseline `private-regions-a25.json`
from the adjacent weight comparison, which includes the current naming changes.

```sh
python PRIVATE_ROOT/src/run_experiment.py PRIVATE_ROOT --snapshot SNAPSHOT --baseline BASELINE_JSON
python PRIVATE_ROOT/src/analyze.py PRIVATE_ROOT --snapshot SNAPSHOT
# Only after coarse results: copy the disclosed refinement protocol into the root.
python PRIVATE_ROOT/src/refine.py PRIVATE_ROOT SNAPSHOT
python PRIVATE_ROOT/src/analyze.py PRIVATE_ROOT --snapshot SNAPSHOT
python PRIVATE_ROOT/src/extra_checks.py PRIVATE_ROOT SNAPSHOT
python PRIVATE_ROOT/src/build_report.py PRIVATE_ROOT --map-coordinates BASELINE_2D_F32
```

`refine.py` archives coarse outputs before adding six settings. The second analysis
uses the disclosed midpoint selection. The report additionally displays 8.5 and
all sweep counts. No automatic uploads or remote inference occur.

Validation covers reference-update parity, three analytically separated caps,
permutation on that fixture, density ascent, exact baseline/naming replay,
byte-identical Kotlin centering, all 17 setting convergence, merge tolerance,
stricter convergence for 8.5/9, reverse merge order, and three 10% deletion probes.
The deletion comparison is for raw clusters, not final named regions. It is not
a cross-library or perceptual evaluation. Desktop Python/JVM timings are not a
fair integrated engine benchmark and establish no phone speedup.

The standalone HTML was checked in the local browser: region selection, complete
track list, variant switch, search, reset, visible canvas and no console errors.
