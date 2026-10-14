# Experimental Barnes–Hut map backend

Enable with `-Platentjam.experimentalMap=true`; the exact Kotlin backend remains
the default. The first integration keeps the 3,000-track limit and uses exact
t-SNE below 61 points. Android JNI and the iOS CocoaPod share a small C ABI and
have no ONNX Runtime dependency.

Pinned sources and hashes live in `dependencies.json` and
`src/main/cpp/vendor/manifest.sha256.json`. The implementation uses qdtsne with
Float32, a VP-tree, 60 neighbors, perplexity 20, theta 0.5, 1,000 iterations,
eta 800 and one thread. Layout PCA/fusion, warm start, alignment, and the existing
region weights are preserved. Cache identity includes the backend and parameters.

The callback is synchronous and never retained. Cancellation is checked before
work, for every neighbor query, and for every optimizer iteration. Building the
bounded VP-tree and initializing affinities have checks around them, not within
every internal upstream operation; cancellation latency must still be measured.
RAII owns all native buffers. Output is copied only after complete finite results.
Exceptions, invalid inputs and cancellation have explicit status values.

The application serializes region preparation and projection, checks a conservative
working-set estimate before constructing fused vectors, and retains old cached
geometry on refusal. Regions use the existing bounded k-means fallback when
average linkage exceeds the budget. These estimates do not replace process-level
memory measurements.

`python3 tools/ci/native_verify.py` verifies vendored bytes and both bundled
notices, then runs cancellation/determinism/invalid-input tests under ASan/UBSan.
`MapNativeDeviceTest` checks the actual Android JNI path.

The BSD-4-Clause files have an acknowledgement requirement:
**This product includes software developed by the Delft University of Technology.**
Full notices are in `THIRD-PARTY-NOTICES.txt` and bundled on both platforms.
Preserve this acknowledgement in publicity that mentions this component.

Default enablement requires the full-app speed, quality and memory evidence in
`tools/benchmark/map-acceptance.json`; insufficient evidence keeps this experimental.
