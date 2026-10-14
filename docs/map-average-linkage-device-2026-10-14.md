# Average-linkage regions in the 0.9.1 device build — 2026-10-14

The full `codex/map-performance-0.9.1` branch now uses the practical candidate from
[the algorithm search](map-algorithm-search-2026-10-14.md) for production region discovery:
average linkage (UPGMA), 75% audio / 25% metadata, cosine-distance cut 0.75, minimum region size 4.
This is a device trial of the selected candidate, not a claim of universally optimal clustering.

## Implementation

- Normalize and weight the two modality blocks, then reuse production corpus centering and
  normalization. Reweight only at the one-shot region handoff; the 2D layout and Sonic Journey
  vectors retain their existing weights and cache identity. A missing modality retains the full
  unit norm of its available block.
- Build double-precision cosine distances in a packed triangular matrix, then run deterministic
  nearest-neighbor-chain average linkage. Weighted updates account for the number of tracks in
  each merged cluster. Continue processing separate branches even after an above-cut merge.
- Use hierarchy memberships with the existing semantic routing, naming and medoid ordering.
  Do not impose k-means rival-centroid margins on a different clustering algorithm. Small or
  rejected groups remain outside the named regions; there is no forced reassignment.
- Run on the existing low-priority background lane, with cooperative cancellation during distance
  construction and merging. At most 3,000 tracks use this path (under 36 MB for the distance matrix,
  excluding vectors and other working data). Larger libraries retain the previous bounded k-means
  path and its original weights, covering the full library rather than truncating it.
- Preserve explicit-k and map-vector diagnostic overloads for historical fixtures. The production
  fused-space overload used by `App` selects the new algorithm.

## Verification

- Independent direct global-minimum UPGMA reference: 20 seeded fixtures × 7 distance cuts,
  comparing every pair's membership. Also exercised exact ties, disconnected tight groups,
  undersized groups, zero centered directions, cancellation, the population bound, missing
  modalities and one-shot ownership.
- Frozen 1,107-track library replay: the production reweighting path and direct 75/25 fusion both
  exactly match the SciPy research result: **35 regions, 898 members, 209 outside**, including all
  names and ordered members. Private replay outputs remain outside Git.
- `:core:smart:testAndroidHostTest`: **533 tests passed**.
- `:composeApp:testAndroidHostTest`: **1,058 tests passed**.
- Full `:androidApp:assembleRelease --offline` passed, including native-library, release lint and
  DEX-register checks for all three APK architectures. No device-only ABI shortcut was used.

## Phone installation

The full arm64 release APK was installed with `adb install -r` on the connected Samsung S24 Ultra,
upgrading 0.9.0 (14) to **0.9.1 (15)**. The signing certificate matches the previous APK; no uninstall
or data clear was performed. The original first-install timestamp remains unchanged.

The new process restored all **1,107 indexed tracks** and logged **35 regions in 3,864 ms**:
33 music, one novelty, one sound-effects region. This is one observed startup computation, not a
sustained performance benchmark. The application and a track list rendered successfully; no fatal
application exception was observed in the captured launch log. A floating video overlay and user
interaction were present, so no controlled gesture-performance claim is made.

Installed build SHA-256:
`55ca4f3aa3e5e16fa769f4d4a6c39a3d19b1236c66d1a124efa278a4e240e4ca`

Broad or ambiguously named regions can still occur. The research's acoustic proxy and stability
limits remain applicable; listening feedback on actual region members is still needed.
