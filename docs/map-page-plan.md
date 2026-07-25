# Map Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Map page that draws every owned track as a dot positioned by the fused SMART space, paints listening history on it as colour, and lets a tap start playback from any region.

**Architecture:** A pure layout engine in `:core:smart` (randomized PCA → t-SNE → warm-start Procrustes alignment) turns the existing `LibraryVectorSpace` into 2-D positions, persisted through the existing `IndexStore` contract as 2-float vectors. `:core:history` gains pure listening aggregates. `composeApp` gets a canvas page whose every colour decision lives in a separate pure file so it is unit-tested rather than screenshot-tested.

**Tech Stack:** Kotlin Multiplatform (commonMain), Compose Multiplatform, Koin, kotlin.test. No new dependencies.

## Global Constraints

- Spec: [docs/map-page.md](map-page.md). Read it before Task 1.
- `:core:smart` and `:core:history` have `explicitApi()` — every public declaration states visibility and return type explicitly.
- Licence header on every new file, copied verbatim:
  ```kotlin
  /*
   * Copyright (c) 2026 LatentJam Project
   * SPDX-License-Identifier: Apache-2.0
   */
  ```
- All layout code is pure `commonMain`: no coroutines, no Compose, no platform APIs. Same discipline as `TrackClustering`.
- **Determinism is the product.** Every algorithm takes an explicit seed and must produce byte-identical output for identical input. No `Random()` without a seed, no iteration over unordered `HashMap`.
- Fused space parameters are fixed and already validated — do not retune: `LibraryVectorFusion.METADATA_WEIGHT = 0.75f`, dim 1344, `TrackClustering.SEED = 0x1A7E27`.
- Layout parameters are fixed and already validated — do not retune: PCA to 50 components, t-SNE perplexity 20, cosine metric, 1000 iterations.
- Test commands: `./gradlew :core:smart:testAndroidHostTest`, `./gradlew :core:history:testAndroidHostTest`, `./gradlew :composeApp:testAndroidHostTest`.
- **`LibraryVectorSpace` is one-shot.** `takeRows()` sets the internal matrix to null and a second call throws `IllegalStateException`. `App.kt` already consumes one space for `LibraryWorlds.discover`. The Map must call `engine.libraryMixFeatures(ids)` again for a fresh space. Never share one instance between clustering and layout.
- Commit after every task. Conventional commit prefixes as used in this repo (`feat:`, `fix:`, `test:`, `docs:`).

---

### Task 1: Randomized PCA

Reduces the 1344-d fused matrix to 50 components before t-SNE. Measured: keeping this step scores groups@15 0.398 vs 0.385 without it, and trustworthiness 0.965 vs 0.956.

Uses randomized subspace iteration rather than a full eigendecomposition — a 1344×1344 covariance solve is not worth writing when 50 components suffice.

**Files:**
- Create: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/Pca.kt`
- Test: `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/PcaTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal object Pca { fun reduce(rows: FloatArray, n: Int, dim: Int, components: Int, seed: Int): FloatArray }` — returns a row-major `n × components` matrix. Input is assumed already mean-centered (the caller centers).

- [ ] **Step 1: Write the failing test**

Create `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/PcaTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcaTest {

    // Data that lives on a plane inside a 5-d space must survive reduction to 2 components with
    // its pairwise distances essentially intact: that is the only property t-SNE needs from PCA.
    @Test
    fun `reduce preserves distances for data on a low-rank plane`() {
        val n = 40
        val dim = 5
        val rows = FloatArray(n * dim)
        for (i in 0 until n) {
            val a = (i % 8).toFloat() - 3.5f
            val b = (i / 8).toFloat() - 2f
            // Only two directions carry variance; the other three are fixed combinations.
            rows[i * dim + 0] = a
            rows[i * dim + 1] = b
            rows[i * dim + 2] = a + b
            rows[i * dim + 3] = a - b
            rows[i * dim + 4] = 0f
        }
        center(rows, n, dim)

        val reduced = Pca.reduce(rows, n, dim, components = 2, seed = 7)
        assertEquals(n * 2, reduced.size)

        var worst = 0f
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val full = distance(rows, i, j, dim)
                val low = distance(reduced, i, j, 2)
                worst = maxOf(worst, abs(full - low))
            }
        }
        assertTrue(worst < 1e-2f, "worst pairwise distance error was $worst")
    }

    // The whole page rests on the map not moving between runs.
    @Test
    fun `reduce is deterministic for a fixed seed`() {
        val n = 30
        val dim = 12
        val rows = FloatArray(n * dim) { ((it * 37) % 23).toFloat() - 11f }
        center(rows, n, dim)
        val a = Pca.reduce(rows, n, dim, components = 4, seed = 3)
        val b = Pca.reduce(rows, n, dim, components = 4, seed = 3)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    private fun center(rows: FloatArray, n: Int, dim: Int) {
        for (d in 0 until dim) {
            var mean = 0f
            for (i in 0 until n) mean += rows[i * dim + d]
            mean /= n
            for (i in 0 until n) rows[i * dim + d] -= mean
        }
    }

    private fun distance(rows: FloatArray, i: Int, j: Int, dim: Int): Float {
        var sum = 0f
        for (d in 0 until dim) {
            val delta = rows[i * dim + d] - rows[j * dim + d]
            sum += delta * delta
        }
        return kotlin.math.sqrt(sum)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*PcaTest*"`
Expected: FAIL — compilation error, `Unresolved reference: Pca`.

- [ ] **Step 3: Write the implementation**

Create `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/Pca.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.sqrt

/**
 * Randomized subspace iteration: enough of a PCA to feed t-SNE, and nothing more.
 *
 * A full eigendecomposition of a 1344 x 1344 covariance matrix is thousands of lines and
 * milliseconds of no consequence — the layout is computed once per library change. This finds the
 * leading [components] directions by repeatedly multiplying a random basis through the covariance
 * and re-orthonormalizing, which converges quickly because embedding spectra decay fast.
 *
 * Input rows must already be mean-centered; [LibraryLayout] centers before calling.
 */
internal object Pca {

    /** Subspace iterations. Four is past convergence for embedding covariance spectra. */
    private const val ITERATIONS = 4

    /**
     * @param rows row-major `n x dim`, mean-centered
     * @return row-major `n x components`
     */
    fun reduce(rows: FloatArray, n: Int, dim: Int, components: Int, seed: Int): FloatArray {
        require(n > 0 && dim > 0) { "Empty matrix has no principal components" }
        val k = components.coerceAtMost(dim).coerceAtMost(n)

        // Deterministic basis: a seeded xorshift rather than kotlin.random, so the same library
        // yields the same axes on every platform and every run.
        var state = (seed.toLong() and 0xFFFFFFFFL) or 1L
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2f - 1f
        }

        var basis = FloatArray(dim * k) { next() }
        orthonormalize(basis, dim, k)

        val scratch = FloatArray(n * k)
        repeat(ITERATIONS) {
            // scratch = rows * basis
            multiply(rows, n, dim, basis, k, scratch)
            // basis = rows^T * scratch
            val next = FloatArray(dim * k)
            for (i in 0 until n) {
                for (d in 0 until dim) {
                    val value = rows[i * dim + d]
                    if (value == 0f) continue
                    for (c in 0 until k) next[d * k + c] += value * scratch[i * k + c]
                }
            }
            basis = next
            orthonormalize(basis, dim, k)
        }

        val out = FloatArray(n * k)
        multiply(rows, n, dim, basis, k, out)
        return out
    }

    private fun multiply(
        rows: FloatArray,
        n: Int,
        dim: Int,
        basis: FloatArray,
        k: Int,
        out: FloatArray,
    ) {
        out.fill(0f)
        for (i in 0 until n) {
            val rowBase = i * dim
            val outBase = i * k
            for (d in 0 until dim) {
                val value = rows[rowBase + d]
                if (value == 0f) continue
                val basisBase = d * k
                for (c in 0 until k) out[outBase + c] += value * basis[basisBase + c]
            }
        }
    }

    /** Modified Gram-Schmidt over the columns of a `dim x k` matrix. */
    private fun orthonormalize(basis: FloatArray, dim: Int, k: Int) {
        for (c in 0 until k) {
            for (prev in 0 until c) {
                var dot = 0f
                for (d in 0 until dim) dot += basis[d * k + c] * basis[d * k + prev]
                for (d in 0 until dim) basis[d * k + c] -= dot * basis[d * k + prev]
            }
            var norm = 0f
            for (d in 0 until dim) {
                val value = basis[d * k + c]
                norm += value * value
            }
            norm = sqrt(norm)
            if (norm < 1e-6f) {
                // A collapsed direction carries no variance; park it on an axis so the basis stays
                // full rank instead of propagating NaN through the projection.
                for (d in 0 until dim) basis[d * k + c] = if (d == c % dim) 1f else 0f
            } else {
                for (d in 0 until dim) basis[d * k + c] /= norm
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*PcaTest*"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/Pca.kt core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/PcaTest.kt
git commit -m "feat(map): randomized PCA for the layout pre-reduction"
```

---

### Task 2: t-SNE

The layout core. Exact O(n²) t-SNE — no Barnes-Hut. At 873 tracks that is 762k pairs per iteration, which runs in a couple of seconds once per library change; the approximation is not worth its complexity.

**Files:**
- Create: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/Tsne.kt`
- Test: `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/TsneTest.kt`

**Interfaces:**
- Consumes: nothing (operates on a plain matrix).
- Produces:
  ```kotlin
  internal object Tsne {
      const val PERPLEXITY: Float = 20f
      const val ITERATIONS: Int = 1000
      fun embed(
          rows: FloatArray,
          n: Int,
          dim: Int,
          seed: Int,
          initial: FloatArray? = null,
      ): FloatArray   // row-major n x 2
  }
  ```
  `initial` is the warm start used by Task 4; `null` means seeded random init.

- [ ] **Step 1: Write the failing test**

Create `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/TsneTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TsneTest {

    // Three well-separated blobs in 8-d must come out as three separated blobs in 2-d. This is the
    // only behavioural claim the map makes: things that belong together land together.
    @Test
    fun `embed separates three planted clusters`() {
        val perCluster = 30
        val dim = 8
        val n = perCluster * 3
        val rows = FloatArray(n * dim)
        var state = 99L
        fun noise(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toFloat() / (1L shl 31).toFloat()) - 0.5f
        }
        for (c in 0 until 3) {
            for (i in 0 until perCluster) {
                val row = c * perCluster + i
                for (d in 0 until dim) {
                    rows[row * dim + d] = (if (d == c) 6f else 0f) + noise() * 0.4f
                }
            }
        }

        val out = Tsne.embed(rows, n, dim, seed = 5)
        assertEquals(n * 2, out.size)

        val within = meanDistance(out) { a, b -> a / perCluster == b / perCluster }
        val between = meanDistance(out) { a, b -> a / perCluster != b / perCluster }
        assertTrue(
            between > within * 2f,
            "clusters did not separate: within=$within between=$between",
        )
    }

    @Test
    fun `embed is deterministic for a fixed seed`() {
        val n = 40
        val dim = 6
        val rows = FloatArray(n * dim) { ((it * 17) % 13).toFloat() - 6f }
        val a = Tsne.embed(rows, n, dim, seed = 2)
        val b = Tsne.embed(rows, n, dim, seed = 2)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    // A warm start must be honoured, not ignored: Task 4's anti-churn machinery depends on it.
    @Test
    fun `embed started from a layout stays nearer it than a cold run`() {
        val n = 45
        val dim = 6
        val rows = FloatArray(n * dim) { ((it * 29) % 19).toFloat() - 9f }
        val reference = Tsne.embed(rows, n, dim, seed = 1)
        val warm = Tsne.embed(rows, n, dim, seed = 8, initial = reference)
        val cold = Tsne.embed(rows, n, dim, seed = 8)
        assertTrue(
            drift(warm, reference) < drift(cold, reference),
            "warm start drifted further than a cold run",
        )
    }

    private fun meanDistance(out: FloatArray, pair: (Int, Int) -> Boolean): Float {
        var sum = 0f
        var count = 0
        val n = out.size / 2
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (!pair(i, j)) continue
                val dx = out[i * 2] - out[j * 2]
                val dy = out[i * 2 + 1] - out[j * 2 + 1]
                sum += sqrt(dx * dx + dy * dy)
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun drift(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*TsneTest*"`
Expected: FAIL — `Unresolved reference: Tsne`.

- [ ] **Step 3: Write the implementation**

Create `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/Tsne.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.exp
import kotlin.math.ln

/**
 * Exact t-distributed stochastic neighbour embedding.
 *
 * Chosen over UMAP, PaCMAP, PCA and a kNN force layout by measurement on the real library, not by
 * reputation: UMAP and PaCMAP were both significantly worse under a paired bootstrap over
 * hand-made playlists, and PCA was far worse. See docs/map-page.md section 3.
 *
 * O(n^2) rather than Barnes-Hut. At library scale the full pair sweep is a couple of seconds, and
 * the layout is computed once per library change and cached, so the tree is complexity with no
 * user-visible return.
 */
internal object Tsne {

    /** Validated on the real library. Changing this re-shapes every existing map. */
    const val PERPLEXITY: Float = 20f

    const val ITERATIONS: Int = 1000

    /** Iterations during which P is inflated, which opens gaps between clusters early. */
    private const val EXAGGERATION_ITERATIONS = 250
    private const val EXAGGERATION = 12f

    private const val LEARNING_RATE = 200f
    private const val EARLY_MOMENTUM = 0.5f
    private const val LATE_MOMENTUM = 0.8f
    private const val MOMENTUM_SWITCH = 250

    /** Binary-search bounds for the per-point bandwidth that hits [PERPLEXITY]. */
    private const val PERPLEXITY_STEPS = 60
    private const val PERPLEXITY_TOLERANCE = 1e-5f

    /**
     * @param rows row-major `n x dim`; rows are unit-normalized by the caller so squared euclidean
     *   distance is a monotone function of cosine distance
     * @param initial optional row-major `n x 2` warm start
     * @return row-major `n x 2`
     */
    fun embed(
        rows: FloatArray,
        n: Int,
        dim: Int,
        seed: Int,
        initial: FloatArray? = null,
    ): FloatArray {
        require(n > 0 && dim > 0) { "Cannot embed an empty matrix" }
        if (n < 3) return FloatArray(n * 2)

        val p = affinities(rows, n, dim)

        var state = (seed.toLong() and 0xFFFFFFFFL) or 1L
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2f - 1f
        }

        val y = FloatArray(n * 2)
        if (initial != null && initial.size == n * 2) {
            initial.copyInto(y)
            // A warm start still needs a nudge, or points that start exactly coincident have zero
            // gradient between them and never separate.
            for (i in y.indices) y[i] += next() * 1e-3f
        } else {
            for (i in y.indices) y[i] = next() * 1e-2f
        }

        val gains = FloatArray(n * 2) { 1f }
        val velocity = FloatArray(n * 2)
        val grad = FloatArray(n * 2)
        val q = FloatArray(n * n)

        for (iteration in 0 until ITERATIONS) {
            val scale = if (iteration < EXAGGERATION_ITERATIONS) EXAGGERATION else 1f

            // Student-t affinities in the embedding, and their normalizer.
            var sum = 0f
            for (i in 0 until n) {
                q[i * n + i] = 0f
                for (j in i + 1 until n) {
                    val dx = y[i * 2] - y[j * 2]
                    val dy = y[i * 2 + 1] - y[j * 2 + 1]
                    val value = 1f / (1f + dx * dx + dy * dy)
                    q[i * n + j] = value
                    q[j * n + i] = value
                    sum += 2f * value
                }
            }
            if (sum <= 0f) sum = 1e-12f

            grad.fill(0f)
            for (i in 0 until n) {
                for (j in 0 until n) {
                    if (i == j) continue
                    val qij = q[i * n + j]
                    val force = (scale * p[i * n + j] - qij / sum) * qij
                    grad[i * 2] += 4f * force * (y[i * 2] - y[j * 2])
                    grad[i * 2 + 1] += 4f * force * (y[i * 2 + 1] - y[j * 2 + 1])
                }
            }

            val momentum = if (iteration < MOMENTUM_SWITCH) EARLY_MOMENTUM else LATE_MOMENTUM
            for (i in y.indices) {
                // Jacobs' adaptive gains: grow while the gradient keeps its sign, shrink when it
                // flips. Standard t-SNE, and without it 1000 iterations is not enough to settle.
                gains[i] = if ((grad[i] > 0f) != (velocity[i] > 0f)) {
                    gains[i] + 0.2f
                } else {
                    (gains[i] * 0.8f).coerceAtLeast(0.01f)
                }
                velocity[i] = momentum * velocity[i] - LEARNING_RATE * gains[i] * grad[i]
                y[i] += velocity[i]
            }

            // Recenter every pass so the embedding cannot drift off toward infinity.
            var mx = 0f
            var my = 0f
            for (i in 0 until n) {
                mx += y[i * 2]
                my += y[i * 2 + 1]
            }
            mx /= n
            my /= n
            for (i in 0 until n) {
                y[i * 2] -= mx
                y[i * 2 + 1] -= my
            }
        }
        return y
    }

    /**
     * Symmetric joint probabilities, one bandwidth per point chosen so its conditional
     * distribution has the target perplexity.
     */
    private fun affinities(rows: FloatArray, n: Int, dim: Int): FloatArray {
        val distances = FloatArray(n * n)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var sum = 0f
                val a = i * dim
                val b = j * dim
                for (d in 0 until dim) {
                    val delta = rows[a + d] - rows[b + d]
                    sum += delta * delta
                }
                distances[i * n + j] = sum
                distances[j * n + i] = sum
            }
        }

        val p = FloatArray(n * n)
        val target = ln(PERPLEXITY)
        val row = FloatArray(n)
        for (i in 0 until n) {
            var low = 0f
            var high = Float.MAX_VALUE
            var beta = 1f
            repeat(PERPLEXITY_STEPS) {
                var sum = 0f
                var entropySum = 0f
                for (j in 0 until n) {
                    if (i == j) {
                        row[j] = 0f
                        continue
                    }
                    val value = exp(-distances[i * n + j] * beta)
                    row[j] = value
                    sum += value
                    entropySum += distances[i * n + j] * value
                }
                if (sum <= 0f) sum = 1e-12f
                val entropy = ln(sum) + beta * entropySum / sum
                val error = entropy - target
                if (error > 0f) {
                    low = beta
                    beta = if (high == Float.MAX_VALUE) beta * 2f else (beta + high) / 2f
                } else {
                    high = beta
                    beta = (beta + low) / 2f
                }
                if (error < PERPLEXITY_TOLERANCE && error > -PERPLEXITY_TOLERANCE) return@repeat
                for (j in 0 until n) p[i * n + j] = row[j] / sum
            }
            var sum = 0f
            for (j in 0 until n) sum += p[i * n + j]
            if (sum <= 0f) sum = 1e-12f
            for (j in 0 until n) p[i * n + j] /= sum
        }

        // Symmetrize and normalize to a joint distribution, with a floor so no pair contributes a
        // zero gradient.
        val out = FloatArray(n * n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                out[i * n + j] = ((p[i * n + j] + p[j * n + i]) / (2f * n)).coerceAtLeast(1e-12f)
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*TsneTest*"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/Tsne.kt core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/TsneTest.kt
git commit -m "feat(map): exact t-SNE over the fused library space"
```

---

### Task 3: Procrustes alignment

Measured: recomputing the layout after 5% of the library changes retains only ~82% of each track's on-screen neighbourhood. Worse, an unaligned rerun can mirror or rotate the whole picture, so every location a user learned becomes wrong. This rotates, reflects and scales a fresh layout onto the previous one.

**Files:**
- Create: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutAnchor.kt`
- Test: `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutAnchorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal object LayoutAnchor { fun align(candidate: FloatArray, reference: FloatArray, n: Int): FloatArray }` — both row-major `n × 2`, aligned copy returned. Rows where the reference is absent are handled by the caller, which passes only overlapping rows.

- [ ] **Step 1: Write the failing test**

Create `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutAnchorTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class LayoutAnchorTest {

    // A layout that is the reference rotated by 40 degrees must come back essentially on top of it.
    @Test
    fun `align undoes a rotation`() {
        val n = 25
        val reference = FloatArray(n * 2) { ((it * 13) % 17).toFloat() - 8f }
        val angle = 0.698f
        val rotated = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = reference[i * 2]
            val y = reference[i * 2 + 1]
            rotated[i * 2] = x * cos(angle) - y * sin(angle)
            rotated[i * 2 + 1] = x * sin(angle) + y * cos(angle)
        }
        val aligned = LayoutAnchor.align(rotated, reference, n)
        assertTrue(rmse(aligned, reference) < 1e-3f, "rmse was ${rmse(aligned, reference)}")
    }

    // Mirroring is the failure that matters most: it leaves every learned location wrong while
    // every fidelity metric stays identical.
    @Test
    fun `align undoes a reflection`() {
        val n = 25
        val reference = FloatArray(n * 2) { ((it * 7) % 11).toFloat() - 5f }
        val mirrored = FloatArray(n * 2)
        for (i in 0 until n) {
            mirrored[i * 2] = -reference[i * 2]
            mirrored[i * 2 + 1] = reference[i * 2 + 1]
        }
        val aligned = LayoutAnchor.align(mirrored, reference, n)
        assertTrue(rmse(aligned, reference) < 1e-3f, "rmse was ${rmse(aligned, reference)}")
    }

    private fun rmse(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum / a.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*LayoutAnchorTest*"`
Expected: FAIL — `Unresolved reference: LayoutAnchor`.

- [ ] **Step 3: Write the implementation**

Create `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutAnchor.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.sqrt

/**
 * Puts a freshly computed layout back into the orientation of the one it replaces.
 *
 * t-SNE is invariant to rotation and reflection, so two runs over almost the same library can
 * produce the same picture mirrored. Every fidelity metric is identical and every location the
 * reader learned is wrong, which is the worst kind of regression: invisible to tests that only
 * measure quality.
 *
 * Closed-form orthogonal Procrustes over 2-D, which needs no SVD library: the optimal rotation
 * comes straight from the cross-covariance, and reflection is decided by comparing the residual of
 * the rotation against the residual of the mirrored rotation.
 */
internal object LayoutAnchor {

    /**
     * @param candidate row-major `n x 2`, the new layout
     * @param reference row-major `n x 2`, the previous layout for the same rows in the same order
     * @return an aligned copy of [candidate]
     */
    fun align(candidate: FloatArray, reference: FloatArray, n: Int): FloatArray {
        if (n < 2) return candidate.copyOf()

        val (cx, cy) = centroid(candidate, n)
        val (rx, ry) = centroid(reference, n)

        var sxx = 0f
        var sxy = 0f
        var syx = 0f
        var syy = 0f
        var candidateNorm = 0f
        for (i in 0 until n) {
            val ax = candidate[i * 2] - cx
            val ay = candidate[i * 2 + 1] - cy
            val bx = reference[i * 2] - rx
            val by = reference[i * 2 + 1] - ry
            sxx += ax * bx
            sxy += ax * by
            syx += ay * bx
            syy += ay * by
            candidateNorm += ax * ax + ay * ay
        }
        if (candidateNorm < 1e-12f) return candidate.copyOf()

        // Direct rotation: the angle whose cross-covariance trace is maximal.
        val rotationCos = sxx + syy
        val rotationSin = sxy - syx
        val rotationScale = sqrt(rotationCos * rotationCos + rotationSin * rotationSin)

        // Mirrored rotation: the same construction after flipping the candidate's x axis.
        val mirrorCos = sxx - syy
        val mirrorSin = sxy + syx
        val mirrorScale = sqrt(mirrorCos * mirrorCos + mirrorSin * mirrorSin)

        val mirrored = mirrorScale > rotationScale
        val scale = if (mirrored) mirrorScale else rotationScale
        if (scale < 1e-12f) return candidate.copyOf()

        val c = (if (mirrored) mirrorCos else rotationCos) / scale
        val s = (if (mirrored) mirrorSin else rotationSin) / scale

        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val ax0 = candidate[i * 2] - cx
            val ay = candidate[i * 2 + 1] - cy
            val ax = if (mirrored) -ax0 else ax0
            out[i * 2] = (ax * c - ay * s) + rx
            out[i * 2 + 1] = (ax * s + ay * c) + ry
        }
        return out
    }

    private fun centroid(rows: FloatArray, n: Int): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (i in 0 until n) {
            x += rows[i * 2]
            y += rows[i * 2 + 1]
        }
        return (x / n) to (y / n)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*LayoutAnchorTest*"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutAnchor.kt core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutAnchorTest.kt
git commit -m "feat(map): Procrustes alignment so a recomputed map keeps its orientation"
```

---

### Task 4: LibraryLayout — the public entry point

Wires PCA → t-SNE → warm start → alignment → normalization, and defines the cache key.

**Files:**
- Create: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/LibraryLayout.kt`
- Test: `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LibraryLayoutTest.kt`

**Interfaces:**
- Consumes: `Pca.reduce`, `Tsne.embed`, `LayoutAnchor.align` from Tasks 1–3; `LibraryVectorSpace` and its internal `takeRows()`/`trackIds`/`dim`/`size` from `LibraryVectorFusion.kt`.
- Produces:
  ```kotlin
  public data class LayoutPoint(
      public val trackId: TrackId,
      public val x: Float,   // 0f..1f
      public val y: Float,   // 0f..1f
  )

  public object LibraryLayout {
      public const val VERSION: String = "tsne-p20-pca50-v1"
      public const val COMPONENTS: Int = 50
      public fun compute(
          space: LibraryVectorSpace,
          previous: Map<TrackId, FloatArray> = emptyMap(),
      ): List<LayoutPoint>
      public fun covers(stored: Map<TrackId, FloatArray>, trackIds: List<TrackId>): Boolean
  }
  ```

  **Design note — one stored layout, two jobs.** The layout is stored under `VERSION` alone, not
  under a key that includes the track set. A track-set key would make the previous layout
  unreadable the moment the library changed, which is precisely when the warm start is needed.
  Instead one read serves both questions: if `covers` is true the stored layout is current and is
  used as-is; if false it is stale and becomes the warm start for a recompute.

- [ ] **Step 1: Write the failing test**

Create `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LibraryLayoutTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryLayoutTest {

    @Test
    fun `compute returns one normalized point per track`() {
        val points = LibraryLayout.compute(space(60, 24))
        assertEquals(60, points.size)
        assertEquals(60, points.map { it.trackId }.toSet().size)
        for (point in points) {
            assertTrue(point.x in 0f..1f, "x out of range: ${point.x}")
            assertTrue(point.y in 0f..1f, "y out of range: ${point.y}")
        }
    }

    @Test
    fun `compute is deterministic`() {
        val a = LibraryLayout.compute(space(50, 16))
        val b = LibraryLayout.compute(space(50, 16))
        assertEquals(a, b)
    }

    // Freshness is set equality, not order: the library list is rebuilt on every scan and its
    // order is not stable, so an order-sensitive check would recompute the map constantly.
    @Test
    fun `covers is true only for exactly the stored id set`() {
        val stored = mapOf(
            TrackId("a") to floatArrayOf(0f, 0f),
            TrackId("b") to floatArrayOf(1f, 1f),
        )
        assertTrue(LibraryLayout.covers(stored, listOf(TrackId("b"), TrackId("a"))))
        assertFalse(LibraryLayout.covers(stored, listOf(TrackId("a"), TrackId("b"), TrackId("c"))))
        assertFalse(LibraryLayout.covers(stored, listOf(TrackId("a"))))
    }

    @Test
    fun `compute tolerates a previous layout that covers only some tracks`() {
        val previous = LibraryLayout.compute(space(40, 16))
            .take(20)
            .associate { it.trackId to floatArrayOf(it.x, it.y) }
        val points = LibraryLayout.compute(space(40, 16), previous = previous)
        assertEquals(40, points.size)
    }

    private fun space(n: Int, dim: Int): LibraryVectorSpace {
        val ids = (0 until n).map { TrackId("t$it") }
        val audio = HashMap<TrackId, FloatArray>(n)
        val metadata = HashMap<TrackId, FloatArray>(n)
        for (i in 0 until n) {
            val cluster = i % 4
            audio[ids[i]] = FloatArray(dim) { d ->
                (if (d == cluster) 5f else 0f) + ((i * 7 + d * 3) % 5).toFloat() * 0.1f
            }
            metadata[ids[i]] = FloatArray(dim) { d ->
                (if (d == cluster) 4f else 0f) + ((i * 11 + d) % 3).toFloat() * 0.1f
            }
        }
        return requireNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = dim, metadataDim = dim),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*LibraryLayoutTest*"`
Expected: FAIL — `Unresolved reference: LibraryLayout`.

- [ ] **Step 3: Write the implementation**

Create `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/LibraryLayout.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt

/** Where one track sits on the Map, in a normalized 0..1 box. */
public data class LayoutPoint(
    public val trackId: TrackId,
    public val x: Float,
    public val y: Float,
)

/**
 * The library's 2-D shape.
 *
 * Projects the same fused space [LibraryWorlds] clusters — so the picture and the regions agree by
 * construction rather than by coincidence — and returns a stable position per track.
 *
 * Pure and deterministic: identical input yields byte-identical output. The Map is a place people
 * learn, and a page that rearranges itself between visits is indistinguishable from a broken one.
 *
 * NOTE: [LibraryVectorSpace] is one-shot. Callers must obtain their own space from
 * `SimilarityEngine.libraryMixFeatures`; passing the instance already consumed by
 * [LibraryWorlds.discover] throws.
 */
public object LibraryLayout {

    /**
     * Layout identity. Part of the cache key, so bumping it invalidates every stored map — which
     * is the point: a different algorithm is a different picture.
     */
    public const val VERSION: String = "tsne-p20-pca50-v1"

    /** Pre-reduction width. Measured: keeping this step is worth ~0.01 on both quality metrics. */
    public const val COMPONENTS: Int = 50

    private const val SEED: Int = 0x1A7E27

    /**
     * @param previous the last stored layout, used as a warm start so an added album nudges the map
     *   rather than redrawing it. Tracks it does not cover start from the centroid of the tracks it
     *   does, so newcomers appear in the middle rather than at a corner.
     */
    public fun compute(
        space: LibraryVectorSpace,
        previous: Map<TrackId, FloatArray> = emptyMap(),
    ): List<LayoutPoint> {
        val ids = space.trackIds
        val n = space.size
        if (n == 0) return emptyList()
        val dim = space.dim
        val rows = space.takeRows()
        if (n < 3) return ids.mapIndexed { index, id -> LayoutPoint(id, 0.5f, 0.5f) }

        center(rows, n, dim)
        unitize(rows, n, dim)
        val reduced = Pca.reduce(rows, n, dim, COMPONENTS, SEED)
        val reducedDim = reduced.size / n

        val warm = warmStart(ids, previous, n)
        var embedded = Tsne.embed(reduced, n, reducedDim, SEED, warm)

        if (warm != null) {
            val overlap = ids.withIndex().filter { previous.containsKey(it.value) }
            if (overlap.size >= 2) {
                val candidate = FloatArray(overlap.size * 2)
                val reference = FloatArray(overlap.size * 2)
                overlap.forEachIndexed { slot, (row, id) ->
                    candidate[slot * 2] = embedded[row * 2]
                    candidate[slot * 2 + 1] = embedded[row * 2 + 1]
                    val stored = previous.getValue(id)
                    reference[slot * 2] = stored[0]
                    reference[slot * 2 + 1] = stored[1]
                }
                embedded = applyTransform(embedded, candidate, reference, overlap.size, n)
            }
        }
        return normalize(ids, embedded, n)
    }

    /**
     * Whether [stored] is the layout for exactly [trackIds], and can be shown as-is.
     *
     * When false the stored layout is stale — but still the best available warm start, which is why
     * it is kept under [VERSION] alone rather than under a key that encodes the track set.
     */
    public fun covers(stored: Map<TrackId, FloatArray>, trackIds: List<TrackId>): Boolean =
        stored.size == trackIds.size && stored.keys.containsAll(trackIds)

    private fun warmStart(
        ids: List<TrackId>,
        previous: Map<TrackId, FloatArray>,
        n: Int,
    ): FloatArray? {
        if (previous.isEmpty()) return null
        var covered = 0
        var cx = 0f
        var cy = 0f
        for (id in ids) {
            val stored = previous[id] ?: continue
            if (stored.size < 2) continue
            covered++
            cx += stored[0]
            cy += stored[1]
        }
        if (covered < 2) return null
        cx /= covered
        cy /= covered
        val out = FloatArray(n * 2)
        ids.forEachIndexed { row, id ->
            val stored = previous[id]
            if (stored != null && stored.size >= 2) {
                out[row * 2] = stored[0]
                out[row * 2 + 1] = stored[1]
            } else {
                out[row * 2] = cx
                out[row * 2 + 1] = cy
            }
        }
        return out
    }

    private fun applyTransform(
        embedded: FloatArray,
        candidate: FloatArray,
        reference: FloatArray,
        overlap: Int,
        n: Int,
    ): FloatArray {
        val alignedOverlap = LayoutAnchor.align(candidate, reference, overlap)
        // Recover the affine map that alignment applied to the overlap, then apply it to every row
        // so newcomers travel with the tracks around them.
        var scaleNumerator = 0f
        var scaleDenominator = 0f
        var cx = 0f
        var cy = 0f
        var ax = 0f
        var ay = 0f
        for (i in 0 until overlap) {
            cx += candidate[i * 2]
            cy += candidate[i * 2 + 1]
            ax += alignedOverlap[i * 2]
            ay += alignedOverlap[i * 2 + 1]
        }
        cx /= overlap
        cy /= overlap
        ax /= overlap
        ay /= overlap
        for (i in 0 until overlap) {
            val dx = candidate[i * 2] - cx
            val dy = candidate[i * 2 + 1] - cy
            val ex = alignedOverlap[i * 2] - ax
            val ey = alignedOverlap[i * 2 + 1] - ay
            scaleNumerator += dx * ex + dy * ey
            scaleDenominator += dx * dx + dy * dy
        }
        if (scaleDenominator < 1e-12f) return embedded
        val cosScale = scaleNumerator / scaleDenominator

        var sinNumerator = 0f
        for (i in 0 until overlap) {
            val dx = candidate[i * 2] - cx
            val dy = candidate[i * 2 + 1] - cy
            val ex = alignedOverlap[i * 2] - ax
            val ey = alignedOverlap[i * 2 + 1] - ay
            sinNumerator += dx * ey - dy * ex
        }
        val sinScale = sinNumerator / scaleDenominator

        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val dx = embedded[i * 2] - cx
            val dy = embedded[i * 2 + 1] - cy
            out[i * 2] = ax + dx * cosScale - dy * sinScale
            out[i * 2 + 1] = ay + dx * sinScale + dy * cosScale
        }
        return out
    }

    private fun normalize(ids: List<TrackId>, embedded: FloatArray, n: Int): List<LayoutPoint> {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = minOf(minX, embedded[i * 2])
            maxX = maxOf(maxX, embedded[i * 2])
            minY = minOf(minY, embedded[i * 2 + 1])
            maxY = maxOf(maxY, embedded[i * 2 + 1])
        }
        val spanX = (maxX - minX).takeIf { it > 1e-6f } ?: 1f
        val spanY = (maxY - minY).takeIf { it > 1e-6f } ?: 1f
        return ids.mapIndexed { row, id ->
            LayoutPoint(
                trackId = id,
                x = ((embedded[row * 2] - minX) / spanX).coerceIn(0f, 1f),
                y = ((embedded[row * 2 + 1] - minY) / spanY).coerceIn(0f, 1f),
            )
        }
    }

    private fun center(rows: FloatArray, n: Int, dim: Int) {
        for (d in 0 until dim) {
            var mean = 0f
            for (i in 0 until n) mean += rows[i * dim + d]
            mean /= n
            for (i in 0 until n) rows[i * dim + d] -= mean
        }
    }

    private fun unitize(rows: FloatArray, n: Int, dim: Int) {
        for (i in 0 until n) {
            var norm = 0f
            for (d in 0 until dim) {
                val value = rows[i * dim + d]
                norm += value * value
            }
            norm = sqrt(norm)
            if (norm < 1e-12f) continue
            for (d in 0 until dim) rows[i * dim + d] /= norm
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*LibraryLayoutTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/LibraryLayout.kt core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LibraryLayoutTest.kt
git commit -m "feat(map): LibraryLayout projects the fused space to stable 2-D positions"
```

---

### Task 5: Layout persistence

A layout is a `Map<TrackId, FloatArray>` where each array is `[x, y]`. That is exactly the shape `IndexStore` already persists, so this task adds a qualifier and two platform bindings rather than a new binary format. `FileIndexStore` already takes a `fileName` parameter, and the metadata-text index already proves the second-instance pattern.

**Files:**
- Modify: `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/di/SmartModule.kt` (add qualifier + default binding)
- Modify: `core/smart/src/androidMain/kotlin/io/github/nikitasud/latentjam/smart/EmbeddingBackend.android.kt` (add the file-backed binding)
- Modify: `core/smart/src/iosMain/kotlin/io/github/nikitasud/latentjam/smart/EmbeddingBackend.ios.kt` (add the file-backed binding)
- Test: `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutRoundTripTest.kt`

**Interfaces:**
- Consumes: `LibraryLayout.VERSION`, `LibraryLayout.covers`, `LayoutPoint` from Task 4; `IndexStore` from `IndexStore.kt`.
- Produces: `public val smartLayoutQualifier: StringQualifier` in `di/SmartModule.kt`, plus two extension functions in `LibraryLayout.kt`:
  ```kotlin
  public suspend fun IndexStore.loadLayout(): Map<TrackId, FloatArray>
  public suspend fun IndexStore.saveLayout(points: List<LayoutPoint>)
  ```

- [ ] **Step 1: Write the failing test**

Create `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutRoundTripTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LayoutRoundTripTest {

    private class MemoryStore : IndexStore {
        private var version: String? = null
        private var entries: Map<TrackId, FloatArray> = emptyMap()
        override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
            if (modelVersion == version) entries else null
        override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
            version = modelVersion
            this.entries = entries
        }
        override suspend fun clear() {
            version = null
            entries = emptyMap()
        }
    }

    private val ids = listOf(TrackId("a"), TrackId("b"), TrackId("c"))
    private val points = listOf(
        LayoutPoint(TrackId("a"), 0.1f, 0.2f),
        LayoutPoint(TrackId("b"), 0.3f, 0.4f),
        LayoutPoint(TrackId("c"), 0.5f, 0.6f),
    )

    @Test
    fun `a saved layout round-trips`() = runTest {
        val store = MemoryStore()
        store.saveLayout(points)
        val loaded = store.loadLayout()
        assertEquals(3, loaded.size)
        assertEquals(0.3f, loaded.getValue(TrackId("b"))[0])
        assertEquals(0.4f, loaded.getValue(TrackId("b"))[1])
        assertTrue(LibraryLayout.covers(loaded, ids))
    }

    // A changed library must still READ — the stale layout is the warm start — but must report
    // itself stale so the caller recomputes instead of drawing a map missing its newest tracks.
    @Test
    fun `a changed library still loads but does not cover`() = runTest {
        val store = MemoryStore()
        store.saveLayout(points)
        val loaded = store.loadLayout()
        assertEquals(3, loaded.size)
        assertFalse(LibraryLayout.covers(loaded, ids + TrackId("d")))
    }

    @Test
    fun `an empty store loads empty rather than failing`() = runTest {
        assertEquals(emptyMap(), MemoryStore().loadLayout())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*LayoutRoundTripTest*"`
Expected: FAIL — `Unresolved reference: saveLayout`.

- [ ] **Step 3: Add the store helpers**

Append to `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/cluster/LibraryLayout.kt` (add `import io.github.nikitasud.latentjam.smart.IndexStore` at the top):

```kotlin
/**
 * Reads whatever layout is stored, current or not.
 *
 * Always returns a map rather than a nullable, because a stale layout is not a miss — it is the
 * warm start. Ask [LibraryLayout.covers] whether it can also be drawn as-is.
 */
public suspend fun IndexStore.loadLayout(): Map<TrackId, FloatArray> =
    load(LibraryLayout.VERSION).orEmpty()

/** Replaces the stored layout. Positions persist as 2-float vectors, so no new format is needed. */
public suspend fun IndexStore.saveLayout(points: List<LayoutPoint>) {
    save(LibraryLayout.VERSION, points.associate { it.trackId to floatArrayOf(it.x, it.y) })
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*LayoutRoundTripTest*"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Add the Koin qualifier**

In `core/smart/src/commonMain/kotlin/io/github/nikitasud/latentjam/smart/di/SmartModule.kt`, directly below the existing `smartTextIndexQualifier` declaration, add:

```kotlin
/**
 * Qualifier of the Map page's 2-D layout store. A third store rather than a field on an existing
 * one: the layout has its own version, its own invalidation trigger, and must survive an embedding
 * re-index untouched.
 */
public val smartLayoutQualifier: StringQualifier = named("smart-layout-index")
```

Then inside the `module { ... }` block, next to the existing `NoopIndexStore` binding, add:

```kotlin
    single<IndexStore>(smartLayoutQualifier) { NoopIndexStore() }
```

- [ ] **Step 6: Add the platform bindings**

In `core/smart/src/androidMain/kotlin/io/github/nikitasud/latentjam/smart/EmbeddingBackend.android.kt`, inside the module block after the `smartTextIndexQualifier` binding:

```kotlin
    single<IndexStore>(smartLayoutQualifier) {
        FileIndexStore(context = get(), fileName = "map_layout.bin")
    }
```

Add `import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier` if the file does not already import the qualifiers' package.

In `core/smart/src/iosMain/kotlin/io/github/nikitasud/latentjam/smart/EmbeddingBackend.ios.kt`, after the existing `smartTextIndexQualifier` binding:

```kotlin
    single<IndexStore>(smartLayoutQualifier) {
        IosFileIndexStore(fileName = "map_layout.bin")
    }
```

- [ ] **Step 7: Verify both platforms compile**

Run: `./gradlew :core:smart:compileAndroidHostTestSources :core:smart:compileTestKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add core/smart/src
git commit -m "feat(map): persist the layout through the existing index store"
```

---

### Task 6: LibraryListeningStats

Pure aggregates for the headline sentences and the selection card. Lives in `:core:history` because it reads `TrackStats`, and it stays free of any string formatting so it can be tested without resources.

**Files:**
- Create: `core/history/src/commonMain/kotlin/io/github/nikitasud/latentjam/history/LibraryListeningStats.kt`
- Test: `core/history/src/commonTest/kotlin/io/github/nikitasud/latentjam/history/LibraryListeningStatsTest.kt`

**Interfaces:**
- Consumes: `TrackStats`, `TrackId`.
- Produces:
  ```kotlin
  public data class RegionListening(
      public val region: Int,
      public val trackCount: Int,
      public val neverPlayed: Int,
      public val plays: Int,
      public val skipRate: Float,   // 0f..1f over tracks with at least one play
  )

  public data class LibraryListening(
      public val trackCount: Int,
      public val neverPlayed: Int,
      public val tracksForHalfOfPlays: Int,
      public val regions: List<RegionListening>,
      public val darkestRegion: Int?,     // highest never-played RATE, min 8 tracks
      public val skippiestRegion: Int?,   // highest skip rate, min 10 played tracks
      public val maxPlays: Int,
  )

  public object LibraryListeningStats {
      public fun summarize(
          regionOf: Map<TrackId, Int>,
          stats: Map<TrackId, TrackStats>,
      ): LibraryListening
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `core/history/src/commonTest/kotlin/io/github/nikitasud/latentjam/history/LibraryListeningStatsTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryListeningStatsTest {

    private fun stats(plays: Int, skips: Int) =
        TrackStats(plays = plays, completions = 0, skips = skips, totalPlayedMs = 0, lastPlayedAtMs = 0)

    @Test
    fun `summarize counts never-played tracks across the whole library`() {
        val regionOf = (0 until 10).associate { TrackId("t$it") to it % 2 }
        val played = mapOf(TrackId("t0") to stats(3, 0), TrackId("t1") to stats(1, 0))
        val summary = LibraryListeningStats.summarize(regionOf, played)
        assertEquals(10, summary.trackCount)
        assertEquals(8, summary.neverPlayed)
        assertEquals(3, summary.maxPlays)
    }

    // The concentration headline: how few tracks carry half the listening.
    @Test
    fun `tracksForHalfOfPlays counts down from the most played`() {
        val regionOf = (0 until 4).associate { TrackId("t$it") to 0 }
        val played = mapOf(
            TrackId("t0") to stats(10, 0),
            TrackId("t1") to stats(6, 0),
            TrackId("t2") to stats(3, 0),
            TrackId("t3") to stats(1, 0),
        )
        // Total 20; the top track alone is 10, which reaches half.
        assertEquals(1, LibraryListeningStats.summarize(regionOf, played).tracksForHalfOfPlays)
    }

    // Darkest region is a RATE, not a count: a big region always holds more unplayed tracks than a
    // small one, which would make the headline say "your biggest region" every time.
    @Test
    fun `darkestRegion uses the never-played rate and ignores tiny regions`() {
        val regionOf = buildMap {
            repeat(20) { put(TrackId("big$it"), 0) }
            repeat(10) { put(TrackId("dark$it"), 1) }
            repeat(3) { put(TrackId("tiny$it"), 2) }
        }
        val played = buildMap {
            repeat(14) { put(TrackId("big$it"), stats(1, 0)) }   // 30% unplayed
            repeat(2) { put(TrackId("dark$it"), stats(1, 0)) }   // 80% unplayed
        }                                                        // region 2: 100% but too small
        assertEquals(1, LibraryListeningStats.summarize(regionOf, played).darkestRegion)
    }

    @Test
    fun `skippiestRegion is null when no region has enough played tracks`() {
        val regionOf = (0 until 6).associate { TrackId("t$it") to 0 }
        val played = mapOf(TrackId("t0") to stats(1, 1))
        assertNull(LibraryListeningStats.summarize(regionOf, played).skippiestRegion)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:history:testAndroidHostTest --tests "*LibraryListeningStatsTest*"`
Expected: FAIL — `Unresolved reference: LibraryListeningStats`.

- [ ] **Step 3: Write the implementation**

Create `core/history/src/commonMain/kotlin/io/github/nikitasud/latentjam/history/LibraryListeningStats.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId

/** Listening aggregates for one region of the Map. */
public data class RegionListening(
    public val region: Int,
    public val trackCount: Int,
    public val neverPlayed: Int,
    public val plays: Int,
    /** Share of started tracks abandoned, over tracks with at least one play. */
    public val skipRate: Float,
)

/** Everything the Map's headlines and selection card need, and nothing about how to word it. */
public data class LibraryListening(
    public val trackCount: Int,
    public val neverPlayed: Int,
    public val tracksForHalfOfPlays: Int,
    public val regions: List<RegionListening>,
    public val darkestRegion: Int?,
    public val skippiestRegion: Int?,
    public val maxPlays: Int,
)

/**
 * Turns the listening log into the handful of facts worth showing.
 *
 * Every figure here is one a plain sorted list could not produce — that is the bar the Map page is
 * held to. Counts of plays and top artists are deliberately absent: the Tracks and Artists tabs
 * already answer those.
 */
public object LibraryListeningStats {

    /** Below this a region's never-played rate is noise rather than a finding. */
    private const val MIN_REGION_FOR_DARKEST = 8

    /** Skip rate needs a real denominator before it can be quoted in a sentence. */
    private const val MIN_PLAYED_FOR_SKIPPIEST = 10

    public fun summarize(
        regionOf: Map<TrackId, Int>,
        stats: Map<TrackId, TrackStats>,
    ): LibraryListening {
        val playsOf = { id: TrackId -> stats[id]?.plays ?: 0 }
        val trackCount = regionOf.size
        val neverPlayed = regionOf.keys.count { playsOf(it) == 0 }
        val maxPlays = regionOf.keys.maxOfOrNull(playsOf) ?: 0

        val descending = regionOf.keys.map(playsOf).sortedDescending()
        val total = descending.sum()
        var running = 0
        var half = 0
        for (plays in descending) {
            if (running * 2 >= total) break
            running += plays
            half++
        }

        val regions = regionOf.entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap()
            .map { (region, members) ->
                val played = members.filter { playsOf(it) > 0 }
                RegionListening(
                    region = region,
                    trackCount = members.size,
                    neverPlayed = members.size - played.size,
                    plays = members.sumOf(playsOf),
                    skipRate = if (played.isEmpty()) {
                        0f
                    } else {
                        played.sumOf { id ->
                            val entry = stats.getValue(id)
                            if (entry.plays == 0) 0.0 else entry.skips.toDouble() / entry.plays
                        }.toFloat() / played.size
                    },
                )
            }

        return LibraryListening(
            trackCount = trackCount,
            neverPlayed = neverPlayed,
            tracksForHalfOfPlays = half,
            regions = regions,
            darkestRegion = regions
                .filter { it.trackCount >= MIN_REGION_FOR_DARKEST }
                .maxByOrNull { it.neverPlayed.toFloat() / it.trackCount }
                ?.region,
            skippiestRegion = regions
                .filter { it.trackCount - it.neverPlayed >= MIN_PLAYED_FOR_SKIPPIEST }
                .maxByOrNull { it.skipRate }
                ?.region,
            maxPlays = maxPlays,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:history:testAndroidHostTest --tests "*LibraryListeningStatsTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add core/history/src
git commit -m "feat(map): library listening aggregates for the Map headlines"
```

---

### Task 7: MapLenses

Every colour, radius and legend decision, as pure functions over plain data. The rule the spec fixes: **colour always encodes a number, never an identity** — a scatter plot supports only three categorical hues at colourblind-safe separation, and the library has eight regions, so a rainbow map is not available.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/MapLenses.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/MapLensesTest.kt`

**Interfaces:**
- Consumes: `RegionListening` / `LibraryListening` from Task 6.
- Produces:
  ```kotlin
  enum class MapLens { WORLDS, PLAYS, NEVER_PLAYED, SKIPS }

  data class MapDot(
      val trackId: TrackId,
      val x: Float,
      val y: Float,
      val region: Int,
      val plays: Int,
      val skipRate: Float,
  )

  sealed interface MapInk {
      data object Neutral : MapInk
      data object Accent : MapInk
      data class Ramp(val step: Int) : MapInk   // 0..5, cool ramp
      data class WarmRamp(val step: Int) : MapInk
  }

  enum class MapLegend { REGION_SELECTION, PLAY_RAMP, NEVER_PLAYED_KEY, SKIP_RAMP }

  object MapLenses {
      const val RAMP_STEPS: Int = 6
      fun ink(lens: MapLens, dot: MapDot, selectedRegion: Int, maxPlays: Int): MapInk
      fun radius(lens: MapLens, dot: MapDot, selectedRegion: Int): Float
      fun legend(lens: MapLens): MapLegend
      fun availableLenses(listening: LibraryListening, minEvents: Int = MIN_EVENTS_FOR_STATS): List<MapLens>
      const val MIN_EVENTS_FOR_STATS: Int = 50
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/MapLensesTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.history.RegionListening
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapLensesTest {

    private fun dot(region: Int = 0, plays: Int = 0, skipRate: Float = 0f) =
        MapDot(TrackId("t"), 0.5f, 0.5f, region, plays, skipRate)

    @Test
    fun `worlds lens accents only the selected region`() {
        assertEquals(MapInk.Accent, MapLenses.ink(MapLens.WORLDS, dot(region = 2), 2, maxPlays = 9))
        assertEquals(MapInk.Neutral, MapLenses.ink(MapLens.WORLDS, dot(region = 3), 2, maxPlays = 9))
    }

    @Test
    fun `plays lens leaves unplayed tracks neutral and ramps the rest`() {
        assertEquals(MapInk.Neutral, MapLenses.ink(MapLens.PLAYS, dot(plays = 0), 0, maxPlays = 40))
        val top = MapLenses.ink(MapLens.PLAYS, dot(plays = 40), 0, maxPlays = 40)
        val low = MapLenses.ink(MapLens.PLAYS, dot(plays = 1), 0, maxPlays = 40)
        assertTrue(top is MapInk.Ramp && top.step == MapLenses.RAMP_STEPS - 1)
        assertTrue(low is MapInk.Ramp && low.step < MapLenses.RAMP_STEPS - 1)
    }

    // Never-played carries identity, so it must not rest on hue alone: the dot is also bigger.
    @Test
    fun `never played lens marks unplayed tracks with colour and size`() {
        assertEquals(
            MapInk.Accent,
            MapLenses.ink(MapLens.NEVER_PLAYED, dot(plays = 0), 0, maxPlays = 9),
        )
        val unplayed = MapLenses.radius(MapLens.NEVER_PLAYED, dot(plays = 0), 0)
        val played = MapLenses.radius(MapLens.NEVER_PLAYED, dot(plays = 4), 0)
        assertTrue(unplayed > played, "unplayed dot was not larger")
    }

    @Test
    fun `skips lens uses the warm ramp and skips unplayed tracks`() {
        assertEquals(MapInk.Neutral, MapLenses.ink(MapLens.SKIPS, dot(plays = 0), 0, maxPlays = 9))
        val hot = MapLenses.ink(MapLens.SKIPS, dot(plays = 5, skipRate = 1f), 0, maxPlays = 9)
        assertTrue(hot is MapInk.WarmRamp && hot.step == MapLenses.RAMP_STEPS - 1)
    }

    // Cold start: a lens that would say "you have never played 100% of your library" is worthless.
    @Test
    fun `stat lenses stay hidden until there is enough history`() {
        val thin = LibraryListening(
            trackCount = 300, neverPlayed = 298, tracksForHalfOfPlays = 1,
            regions = listOf(RegionListening(0, 300, 298, 4, 0f)),
            darkestRegion = 0, skippiestRegion = null, maxPlays = 3,
        )
        assertEquals(listOf(MapLens.WORLDS), MapLenses.availableLenses(thin))

        val rich = thin.copy(neverPlayed = 150, regions = listOf(RegionListening(0, 300, 150, 900, 0.2f)))
        assertEquals(MapLens.entries.toList(), MapLenses.availableLenses(rich))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testAndroidHostTest --tests "*MapLensesTest*"`
Expected: FAIL — `Unresolved reference: MapLenses`.

- [ ] **Step 3: Write the implementation**

Create `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/MapLenses.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.pow

/** Which fact the Map is currently painting. */
enum class MapLens { WORLDS, PLAYS, NEVER_PLAYED, SKIPS }

/** One track's position and listening signal, everything a dot needs and nothing more. */
data class MapDot(
    val trackId: TrackId,
    val x: Float,
    val y: Float,
    val region: Int,
    val plays: Int,
    val skipRate: Float,
)

/**
 * A dot's ink, named by role rather than by colour so the palette lives in the theme and this file
 * stays testable without Compose.
 */
sealed interface MapInk {
    data object Neutral : MapInk
    data object Accent : MapInk
    /** Cool sequential ramp, 0 = nearest the surface. */
    data class Ramp(val step: Int) : MapInk
    /** Warm sequential ramp, used where the quantity is unwelcome. */
    data class WarmRamp(val step: Int) : MapInk
}

enum class MapLegend { REGION_SELECTION, PLAY_RAMP, NEVER_PLAYED_KEY, SKIP_RAMP }

/**
 * Every colour and size decision the Map makes.
 *
 * The governing rule, forced by accessibility rather than taste: a scatter plot supports only three
 * categorical hues at colourblind-safe separation across all pairs, and a library clusters into
 * eight or more regions. Colouring regions by hue is therefore not on the table, so **colour always
 * encodes a number** and identity is carried by the selection state and the labels.
 */
object MapLenses {

    /** Steps in each sequential ramp. */
    const val RAMP_STEPS: Int = 6

    /** Below this many plays the statistical lenses cannot say anything true. */
    const val MIN_EVENTS_FOR_STATS: Int = 50

    private const val BASE_RADIUS = 2.2f

    fun ink(lens: MapLens, dot: MapDot, selectedRegion: Int, maxPlays: Int): MapInk = when (lens) {
        MapLens.WORLDS -> if (dot.region == selectedRegion) MapInk.Accent else MapInk.Neutral
        MapLens.PLAYS -> if (dot.plays <= 0) {
            MapInk.Neutral
        } else {
            // Compressed so the long tail of once-played tracks still separates from the top.
            val fraction = (dot.plays.toFloat() / maxOf(maxPlays, 1)).coerceIn(0f, 1f)
            MapInk.Ramp(step(fraction.pow(0.4f)))
        }
        MapLens.NEVER_PLAYED -> if (dot.plays <= 0) MapInk.Accent else MapInk.Neutral
        MapLens.SKIPS -> if (dot.plays <= 0) {
            MapInk.Neutral
        } else {
            MapInk.WarmRamp(step(dot.skipRate.coerceIn(0f, 1f)))
        }
    }

    fun radius(lens: MapLens, dot: MapDot, selectedRegion: Int): Float = when (lens) {
        // Size carries the never-played distinction alongside colour, so the one lens whose colour
        // means membership rather than magnitude never rests on hue alone.
        MapLens.NEVER_PLAYED -> if (dot.plays <= 0) 3.0f else 1.6f
        MapLens.WORLDS -> if (dot.region == selectedRegion) 2.8f else 1.8f
        else -> BASE_RADIUS
    }

    fun legend(lens: MapLens): MapLegend = when (lens) {
        MapLens.WORLDS -> MapLegend.REGION_SELECTION
        MapLens.PLAYS -> MapLegend.PLAY_RAMP
        MapLens.NEVER_PLAYED -> MapLegend.NEVER_PLAYED_KEY
        MapLens.SKIPS -> MapLegend.SKIP_RAMP
    }

    /**
     * Worlds always works; the rest wait until the log can support a true sentence. Hidden rather
     * than empty — an empty chart is a broken promise, a missing chip is not.
     */
    fun availableLenses(
        listening: LibraryListening,
        minEvents: Int = MIN_EVENTS_FOR_STATS,
    ): List<MapLens> {
        val plays = listening.regions.sumOf { it.plays }
        return if (plays >= minEvents) MapLens.entries.toList() else listOf(MapLens.WORLDS)
    }

    private fun step(fraction: Float): Int =
        (fraction * RAMP_STEPS).toInt().coerceIn(0, RAMP_STEPS - 1)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testAndroidHostTest --tests "*MapLensesTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/MapLenses.kt composeApp/src/commonTest/kotlin/io/github/nikitasud/latentjam/app/MapLensesTest.kt
git commit -m "feat(map): pure lens logic for the Map page"
```

---

### Task 8: Copy for the Map page, in all 18 locales

All user-visible copy, in `values/` **and every translation**, in one task.

This repo enforces translation parity in `composeApp/src/androidHostTest/.../StringResourceParityTest.kt`, and it is not advisory — it fails the build. Adding English keys alone leaves the suite red, which is why the source strings and their translations cannot be separate tasks.

**The contract that test enforces — read it before writing a single string:**

1. **Every key in `values/strings.xml` must exist in all 17 locale folders, with no orphans.** There is no English-fallback option.
2. **Positional placeholders must survive translation.** The set of `%N$` indices per key must match the source exactly. Order may change — that is what positional placeholders are for — but nothing may be dropped or invented. A literal `%%` is not a positional placeholder and is not checked, but still must be written `%%`.
3. **No string may contain `\'`.** Compose Multiplatform does not unescape it, so it reaches the UI as a visible backslash. Use a typographic apostrophe `’` instead, or word around it. This bites French, Italian and Catalan-style contractions hardest.
4. **`values-in/strings.xml` must stay a body-identical duplicate of `values-id/strings.xml`.** Indonesian needs both folders because `java.util.Locale` reports `in` on API 24–34 and `id` from 35. The files are *not* byte-identical — `values-in` carries a header comment — so copy the string bodies, never the file.
5. **Every `<plurals>` must declare the `other` category.** Extra categories (`one`, `few`, `many`) are optional; `other` is the CLDR fallback and the only one required everywhere.

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-{ar,de,es,fr,hi,id,in,it,ja,ko,pl,pt-rBR,ro,ru,tr,uk,zh-rCN}/strings.xml` (17 files)

**Interfaces:**
- Consumes: nothing.
- Produces: resource ids `Res.string.tab_map`, `Res.string.map_lens_worlds`, `Res.string.map_lens_plays`, `Res.string.map_lens_never_played`, `Res.string.map_lens_skips`, `Res.string.map_headline_worlds`, `Res.string.map_headline_plays`, `Res.string.map_headline_never_played`, `Res.string.map_headline_skips`, `Res.string.map_action_play_region`, `Res.string.map_action_smart_here`, `Res.string.map_empty_indexing`, `Res.string.map_legend_selected`, `Res.string.map_legend_rest`, `Res.string.map_legend_never`, `Res.string.map_legend_played`, `Res.string.map_legend_never_skips`, `Res.string.map_legend_always_skips`.
- **Does not produce a track-count plural.** `Res.plurals.count_tracks` already exists — `%1$d track` / `%1$d tracks`, already translated in all 17 locales, already used by `ForYouTab.kt`. The Map's selection card uses it. Adding a second plural meaning the same thing would be 17 files of duplicate translation for no gain.

- [ ] **Step 1: Add the source strings**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, add before the closing `</resources>`:

```xml
    <string name="tab_map">Map</string>

    <string name="map_lens_worlds">Worlds</string>
    <string name="map_lens_plays">Plays</string>
    <string name="map_lens_never_played">Never played</string>
    <string name="map_lens_skips">Skips</string>

    <string name="map_headline_worlds">%1$d tracks settle into %2$d regions. Tap one to hear it.</string>
    <string name="map_headline_plays">%1$d tracks — %2$d%% of your library — are half of everything you have ever played.</string>
    <string name="map_headline_never_played">You have never played %1$d of %2$d tracks. %3$s is %4$d%% untouched.</string>
    <string name="map_headline_skips">You bail out of %1$s more than anywhere else — %2$d%% of starts.</string>

    <string name="map_action_play_region">Play region</string>
    <string name="map_action_smart_here">SMART from here</string>
    <string name="map_empty_indexing">Still reading your library. The map appears when indexing finishes.</string>

    <string name="map_legend_selected">selected region</string>
    <string name="map_legend_rest">rest of library</string>
    <string name="map_legend_never">never played</string>
    <string name="map_legend_played">played at least once</string>
    <string name="map_legend_never_skips">never skipped</string>
    <string name="map_legend_always_skips">always skipped</string>
```

Note the `%%` in three of the headlines: a literal percent sign must be escaped in a formatted resource, and getting it wrong throws at runtime rather than at build time.

Place them in the file's existing sectioned order, following the surrounding comment-banner style (`<!-- ===== browse tabs -->` and friends) — `tab_map` belongs with the other tab labels, the rest in a new Map banner.

- [ ] **Step 2: Translate into all 17 locales**

Add all 18 keys to each of the 17 locale files. Guidance, in priority order:

- Match the tone and vocabulary each file already uses. These files are translated consistently; read a neighbouring string before inventing a term. In particular, reuse whatever each locale already calls a *track*, a *library* and *SMART*.
- `tab_map` sits in a horizontally-scrolling tab strip beside seven other labels. Prefer the shortest natural word for a map.
- Keep every `%1$d` / `%2$d` / `%3$s` / `%4$d` present. Reorder freely to suit the grammar.
- Write `%%` for a literal percent.
- Never write `\'`. Use `’`.
- Do `values-id` first, then copy its string bodies into `values-in` without disturbing that file's header comment.

- [ ] **Step 3: Verify parity**

Run: `./gradlew :composeApp:testAndroidHostTest --tests "*StringResourceParityTest*"`
Expected: PASS, 5 tests. This is the gate — key parity, placeholder survival, apostrophe escaping, `values-in`/`values-id` equality, and plural fallback are all checked here.

Then: `./gradlew :composeApp:generateComposeResClass`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/composeResources
git commit -m "feat(map): copy for the Map page in all supported locales"
```

---

### Task 9: MapTab

The canvas page. Drawing only — every decision it renders came from Task 7.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/MapTab.kt`

**Interfaces:**
- Consumes: `MapLens`, `MapDot`, `MapInk`, `MapLegend`, `MapLenses` (Task 7); `LibraryListening`, `RegionListening` (Task 6); string ids (Task 8); `LibraryWorld` from `:core:smart`.
- Produces:
  ```kotlin
  data class MapPage(
      val dots: List<MapDot>,
      val regionNames: List<String>,
      val listening: LibraryListening,
  )

  @Composable
  fun MapTab(
      page: MapPage?,
      contentPadding: PaddingValues,
      onPlayRegion: (Int) -> Unit,
      onSmartFromRegion: (Int) -> Unit,
      onOpenTrack: (TrackId) -> Unit,
  )
  ```

- [ ] **Step 1: Write the composable**

Create `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/MapTab.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.map_action_play_region
import io.github.nikitasud.latentjam.app.generated.resources.map_action_smart_here
import io.github.nikitasud.latentjam.app.generated.resources.map_empty_indexing
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_never_played
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_plays
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_worlds
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_never_played
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_plays
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_worlds
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_always_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_never
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_never_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_played
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_rest
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_selected
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Everything the Map draws, assembled once per visit. */
data class MapPage(
    val dots: List<MapDot>,
    val regionNames: List<String>,
    val listening: LibraryListening,
)

/**
 * The library as a place.
 *
 * Positions come from [io.github.nikitasud.latentjam.smart.cluster.LibraryLayout] and colours from
 * [MapLenses]; this file only draws. The page is assembled once per visit and does not re-rank
 * underneath the reader — the whole value is a stable shape you learn.
 */
@Composable
fun MapTab(
    page: MapPage?,
    contentPadding: PaddingValues,
    onPlayRegion: (Int) -> Unit,
    onSmartFromRegion: (Int) -> Unit,
    onOpenTrack: (TrackId) -> Unit,
) {
    if (page == null || page.dots.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.map_empty_indexing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val lenses = remember(page.listening) { MapLenses.availableLenses(page.listening) }
    var lens by remember(page) { mutableStateOf(MapLens.WORLDS) }
    var selectedRegion by remember(page) { mutableIntStateOf(largestRegion(page)) }
    var zoom by remember(page) { mutableFloatStateOf(1f) }
    var panX by remember(page) { mutableFloatStateOf(0f) }
    var panY by remember(page) { mutableFloatStateOf(0f) }

    val neutral = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val coolRamp = rememberCoolRamp()
    val warmRamp = rememberWarmRamp()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val centroids = remember(page) { largestRegionCentroids(page, limit = 5) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (candidate in lenses) {
                FilterChip(
                    selected = lens == candidate,
                    onClick = { lens = candidate },
                    label = { Text(stringResource(lensLabel(candidate))) },
                )
            }
        }

        Text(
            text = headline(lens, page),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(page) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                        panX += pan.x
                        panY += pan.y
                    }
                }
                .pointerInput(page, lens, zoom, panX, panY) {
                    detectTapGestures(
                        onTap = { offset ->
                            nearest(page.dots, offset, size.width, size.height, zoom, panX, panY)
                                ?.let { selectedRegion = it.region }
                        },
                        onLongPress = { offset ->
                            nearest(page.dots, offset, size.width, size.height, zoom, panX, panY)
                                ?.let { onOpenTrack(it.trackId) }
                        },
                    )
                },
        ) {
            for (dot in page.dots) {
                val ink = MapLenses.ink(lens, dot, selectedRegion, page.listening.maxPlays)
                drawCircle(
                    color = when (ink) {
                        MapInk.Neutral -> neutral
                        MapInk.Accent -> accent
                        is MapInk.Ramp -> coolRamp[ink.step]
                        is MapInk.WarmRamp -> warmRamp[ink.step]
                    },
                    radius = MapLenses.radius(lens, dot, selectedRegion) * zoom,
                    center = Offset(
                        x = dot.x * size.width * zoom + panX,
                        y = dot.y * size.height * zoom + panY,
                    ),
                )
            }
            // Spec section 5: regions are named on the map, because colour cannot carry identity
            // here. Only the five largest are labelled — past that the labels collide and the map
            // becomes a word cloud.
            for ((region, centre) in centroids) {
                val name = page.regionNames.getOrNull(region) ?: continue
                val measured = measurer.measure(name, labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = centre.x * size.width * zoom + panX - measured.size.width / 2f,
                        y = centre.y * size.height * zoom + panY - measured.size.height / 2f,
                    ),
                )
            }
        }

        MapLegendRow(MapLenses.legend(lens), neutral, accent, coolRamp, warmRamp)

        val region = page.listening.regions.getOrNull(selectedRegion)
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = page.regionNames.getOrElse(selectedRegion) { "" },
                style = MaterialTheme.typography.titleMedium,
            )
            if (region != null) {
                Text(
                    // The existing library-wide track-count plural, already translated everywhere.
                    text = pluralStringResource(
                        Res.plurals.count_tracks,
                        region.trackCount,
                        region.trackCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onPlayRegion(selectedRegion) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(Res.string.map_action_play_region)) }
                OutlinedButton(
                    onClick = { onSmartFromRegion(selectedRegion) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(Res.string.map_action_smart_here)) }
            }
        }
    }
}

/**
 * The legend for the active lens.
 *
 * Every lens ships one: a sequential ramp with no scale is decoration, and the never-played lens
 * needs its key stated because size and colour together carry a meaning neither states alone.
 * Sequential legends are discrete swatches rather than a gradient — six steps, matching the six the
 * map draws.
 */
@Composable
private fun MapLegendRow(
    legend: MapLegend,
    neutral: Color,
    accent: Color,
    coolRamp: List<Color>,
    warmRamp: List<Color>,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (legend) {
            MapLegend.REGION_SELECTION -> {
                Swatch(accent)
                LegendLabel(stringResource(Res.string.map_legend_selected))
                Swatch(neutral)
                LegendLabel(stringResource(Res.string.map_legend_rest))
            }
            MapLegend.NEVER_PLAYED_KEY -> {
                Swatch(accent)
                LegendLabel(stringResource(Res.string.map_legend_never))
                Swatch(neutral)
                LegendLabel(stringResource(Res.string.map_legend_played))
            }
            MapLegend.PLAY_RAMP -> {
                LegendLabel(stringResource(Res.string.map_legend_never))
                for (step in coolRamp) Swatch(step)
                LegendLabel(stringResource(Res.string.map_legend_played))
            }
            MapLegend.SKIP_RAMP -> {
                LegendLabel(stringResource(Res.string.map_legend_never_skips))
                for (step in warmRamp) Swatch(step)
                LegendLabel(stringResource(Res.string.map_legend_always_skips))
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(width = 13.dp, height = 9.dp).background(color, RoundedCornerShape(2.dp)))
}

@Composable
private fun LegendLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Centre of each of the [limit] largest regions, for the on-map labels. */
private fun largestRegionCentroids(page: MapPage, limit: Int): List<Pair<Int, Offset>> =
    page.dots.groupBy { it.region }
        .entries
        .sortedByDescending { it.value.size }
        .take(limit)
        .map { (region, members) ->
            region to Offset(
                x = members.sumOf { it.x.toDouble() }.toFloat() / members.size,
                y = members.sumOf { it.y.toDouble() }.toFloat() / members.size,
            )
        }

@Composable
private fun rememberCoolRamp(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        listOf(0.18f, 0.34f, 0.50f, 0.66f, 0.82f, 1f).map { scheme.primary.copy(alpha = it) }
    }
}

@Composable
private fun rememberWarmRamp(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        listOf(0.18f, 0.34f, 0.50f, 0.66f, 0.82f, 1f).map { scheme.error.copy(alpha = it) }
    }
}

private fun lensLabel(lens: MapLens) = when (lens) {
    MapLens.WORLDS -> Res.string.map_lens_worlds
    MapLens.PLAYS -> Res.string.map_lens_plays
    MapLens.NEVER_PLAYED -> Res.string.map_lens_never_played
    MapLens.SKIPS -> Res.string.map_lens_skips
}

@Composable
private fun headline(lens: MapLens, page: MapPage): String {
    val listening = page.listening
    return when (lens) {
        MapLens.WORLDS -> stringResource(
            Res.string.map_headline_worlds,
            listening.trackCount,
            listening.regions.size,
        )
        MapLens.PLAYS -> stringResource(
            Res.string.map_headline_plays,
            listening.tracksForHalfOfPlays,
            percent(listening.tracksForHalfOfPlays, listening.trackCount),
        )
        MapLens.NEVER_PLAYED -> {
            val darkest = listening.darkestRegion
            val region = listening.regions.getOrNull(darkest ?: -1)
            stringResource(
                Res.string.map_headline_never_played,
                listening.neverPlayed,
                listening.trackCount,
                page.regionNames.getOrElse(darkest ?: -1) { "" },
                percent(region?.neverPlayed ?: 0, region?.trackCount ?: 1),
            )
        }
        MapLens.SKIPS -> {
            val skippiest = listening.skippiestRegion
            val region = listening.regions.getOrNull(skippiest ?: -1)
            stringResource(
                Res.string.map_headline_skips,
                page.regionNames.getOrElse(skippiest ?: -1) { "" },
                ((region?.skipRate ?: 0f) * 100f).roundToInt(),
            )
        }
    }
}

private fun percent(part: Int, whole: Int): Int =
    if (whole <= 0) 0 else (part * 100f / whole).roundToInt()

private fun largestRegion(page: MapPage): Int =
    page.listening.regions.maxByOrNull { it.trackCount }?.region ?: 0

private fun nearest(
    dots: List<MapDot>,
    tap: Offset,
    width: Int,
    height: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
): MapDot? {
    var best: MapDot? = null
    var bestDistance = Float.MAX_VALUE
    for (dot in dots) {
        val dx = dot.x * width * zoom + panX - tap.x
        val dy = dot.y * height * zoom + panY - tap.y
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = dot
        }
    }
    // A generous hit target: dots are 2 px and fingers are not.
    return best.takeIf { bestDistance < 24f * 24f }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :composeApp:compileAndroidHostTestSources`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/MapTab.kt
git commit -m "feat(map): the Map page canvas"
```

---

### Task 10: Wire the Map into the app shell

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.kt:15-23` (StartPage)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppGraph.kt:136` (layout store accessor)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt:1557-1585` (BROWSE_TABS, tab constants, `tabIndex()`)
- Modify: `composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt` (page assembly effect + pager branch)

**Interfaces:**
- Consumes: `MapTab`, `MapPage`, `MapDot` (Task 9); `LibraryLayout`, `LayoutPoint`, `loadLayout`, `saveLayout`, `smartLayoutQualifier` (Tasks 4–5); `LibraryListeningStats` (Task 6); `Res.string.tab_map` (Task 8).
- Produces: a working Map destination at pager index 1.

- [ ] **Step 1: Add the StartPage entry**

In `AppSettings.kt`, add to the `StartPage` enum after `FOR_YOU`:

```kotlin
    MAP("map"),
```

- [ ] **Step 2: Shift the tab constants**

In `App.kt`, replace the block at lines 1568–1574 with:

```kotlin
private const val FOR_YOU_TAB = 0
private const val MAP_TAB = 1
private const val PLAYLISTS_TAB = 2
private const val TRACKS_TAB = 3
private const val ALBUMS_TAB = 4
private const val ARTISTS_TAB = 5
private const val GENRES_TAB = 6
private const val FOLDERS_TAB = 7
```

And extend `StartPage.tabIndex()` immediately below it with:

```kotlin
    StartPage.MAP -> MAP_TAB
```

- [ ] **Step 3: Add the tab label**

In `App.kt`, in the `BROWSE_TABS` list starting at line 1557, insert `Res.string.tab_map` immediately after the For You entry, and add the import:

```kotlin
import io.github.nikitasud.latentjam.app.generated.resources.tab_map
```

- [ ] **Step 4: Expose the layout store on AppGraph**

`App.kt` reaches services through `AppGraph`, not through `koinInject`. In
`composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppGraph.kt`, next to the
existing `engine` and `history` accessors (around line 136), add:

```kotlin
    /** Where the Map's 2-D layout is cached between visits. */
    val layoutStore: IndexStore
        get() = koin.get(smartLayoutQualifier)
```

with imports:

```kotlin
import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
```

- [ ] **Step 5: Assemble the page**

In `App.kt`, immediately after the existing `LaunchedEffect(tracks)` block that calls `LibraryWorlds.discover` (around line 572), add a new effect. Note the one-shot rule: this calls `libraryMixFeatures` again for its own space.

```kotlin
        // The Map's positions. A SECOND libraryMixFeatures call on purpose: LibraryVectorSpace is
        // one-shot, and the instance above was consumed by LibraryWorlds.discover.
        var mapPage by remember { mutableStateOf<MapPage?>(null) }
        LaunchedEffect(tracks, worlds) {
            val loaded = tracks ?: return@LaunchedEffect
            val regions = worlds
            if (regions.isEmpty()) return@LaunchedEffect
            val loadedIds = loaded.map(TrackDescriptor::id)

            val regionOf = buildMap {
                regions.forEachIndexed { index, world ->
                    for (track in world.tracks) put(track.id, index)
                }
            }
            val positions = withContext(Dispatchers.Default) {
                val stored = AppGraph.layoutStore.loadLayout()
                if (LibraryLayout.covers(stored, loadedIds)) {
                    stored
                } else {
                    // The stale layout is the warm start, so an added album nudges the map instead
                    // of redrawing it.
                    AppGraph.engine.libraryMixFeatures(loadedIds)?.let { features ->
                        LibraryLayout.compute(features.vectorSpace, stored)
                            .also { AppGraph.layoutStore.saveLayout(it) }
                            .associate { it.trackId to floatArrayOf(it.x, it.y) }
                    }
                }
            } ?: return@LaunchedEffect

            val stats = AppGraph.history.stats()
            val listening = LibraryListeningStats.summarize(
                regionOf = regionOf.filterKeys { positions.containsKey(it) },
                stats = stats,
            )
            mapPage = MapPage(
                dots = positions.mapNotNull { (id, position) ->
                    val region = regionOf[id] ?: return@mapNotNull null
                    val entry = stats[id]
                    MapDot(
                        trackId = id,
                        x = position[0],
                        y = position[1],
                        region = region,
                        plays = entry?.plays ?: 0,
                        skipRate = if (entry == null || entry.plays == 0) {
                            0f
                        } else {
                            entry.skips.toFloat() / entry.plays
                        },
                    )
                },
                regionNames = regions.map { it.name },
                listening = listening,
            )
        }
```

Add the imports this needs at the top of `App.kt`:

```kotlin
import io.github.nikitasud.latentjam.history.LibraryListeningStats
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.loadLayout
import io.github.nikitasud.latentjam.smart.cluster.saveLayout
```

- [ ] **Step 6: Add the pager branch**

In `App.kt`, in the `when (tab)` block starting around line 924, add immediately after the
`FOR_YOU_TAB -> ForYouTab(...)` branch. The handlers mirror the ones For You already uses —
`playback.play(list, index)` inside `scope.launch`, `engine.smartQueue(...)` for SMART, and
`trackMenuTarget` for the long-press sheet — so the Map introduces no new plumbing:

```kotlin
                                        MAP_TAB -> MapTab(
                                            page = mapPage,
                                            contentPadding = listPadding,
                                            onPlayRegion = { region ->
                                                worlds.getOrNull(region)?.let { world ->
                                                    scope.launch { playback.play(world.tracks, 0) }
                                                }
                                            },
                                            onSmartFromRegion = { region ->
                                                worlds.getOrNull(region)?.let { world ->
                                                    scope.launch {
                                                        val seed = world.representative
                                                        val queue = AppGraph.engine.smartQueue(
                                                            seed,
                                                            smartEligibleTracks,
                                                            smartQueueLength,
                                                            smartHistoryFor(AppGraph.history, seed),
                                                        )
                                                        val byId = visibleCatalog.songs
                                                            .associateBy { it.id }
                                                        val tail = queue.mapNotNull(byId::get)
                                                        playback.play(listOf(seed) + tail, 0)
                                                    }
                                                }
                                            },
                                            onOpenTrack = { id ->
                                                tracks?.firstOrNull { it.id == id }
                                                    ?.let { trackMenuTarget = it }
                                            },
                                        )
```

- [ ] **Step 7: Build and run the app**

Run: `./gradlew :composeApp:compileAndroidHostTestSources && ./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

Install and confirm by hand: the Map tab appears second, shows dots, tapping a region updates the card, the lens chips recolour the map, and pinch zooms.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/App.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppSettings.kt composeApp/src/commonMain/kotlin/io/github/nikitasud/latentjam/app/AppGraph.kt
git commit -m "feat(map): add the Map destination to the browse pager"
```

---

### Task 11: Layout quality and stability regression tests

Determinism tests catch a broken layout. They do not catch a layout that still runs and is quietly worse — which is the failure mode that matters, because the whole page rests on one measured claim. This plants known groups in a synthetic library and asserts two things: that the layout keeps those groups together, and that it stays put when the library grows.

Synthetic rather than a fixture of the real library: 873 × 1344 floats is a 4.7 MB test asset, and a planted structure tests the property directly.

**Files:**
- Create: `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutQualityTest.kt`

**Interfaces:**
- Consumes: `LibraryLayout.compute`, `LayoutPoint`, `LibraryVectorFusion.build`.
- Produces: nothing.

- [ ] **Step 1: Write the test**

Create `core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutQualityTest.kt`:

```kotlin
/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertTrue

class LayoutQualityTest {

    private companion object {
        const val GROUPS = 10
        const val PER_GROUP = 18
        const val DIM = 48
        const val NEIGHBOURS = 15

        /**
         * Floor for the share of a track's 15 nearest on-screen neighbours drawn from its own
         * planted group. A correct layout scores far above this; the floor exists to fail loudly
         * if the layout ever degenerates toward a blob, which no other test would notice.
         */
        const val FLOOR = 0.55f
    }

    @Test
    fun `layout keeps planted groups together on screen`() {
        val ids = (0 until GROUPS * PER_GROUP).map { TrackId("t$it") }
        val points = LibraryLayout.compute(plantedSpace(ids))
        val byId = points.associateBy { it.trackId }
        val ordered = ids.map { byId.getValue(it) }

        var total = 0f
        ordered.forEachIndexed { index, point ->
            val group = index / PER_GROUP
            val neighbours = ordered.asSequence()
                .withIndex()
                .filter { it.index != index }
                .sortedBy { squaredDistance(point, it.value) }
                .take(NEIGHBOURS)
                .count { it.index / PER_GROUP == group }
            total += neighbours.toFloat() / NEIGHBOURS
        }
        val precision = total / ordered.size
        assertTrue(precision >= FLOOR, "group precision fell to $precision, floor is $FLOOR")
    }

    // Spec section 3.1: a map people learn must survive the library growing. Recompute after
    // dropping 5% of the tracks, warm-started and Procrustes-aligned, and assert that surviving
    // tracks land near where they were. Without the warm start and alignment this fails outright,
    // because t-SNE is free to mirror the whole picture between runs.
    @Test
    fun `layout stays put when the library changes by five percent`() {
        val ids = (0 until GROUPS * PER_GROUP).map { TrackId("t$it") }
        val full = LibraryLayout.compute(plantedSpace(ids))
        val previous = full.associate { it.trackId to floatArrayOf(it.x, it.y) }

        val kept = ids.filterIndexed { index, _ -> index % 20 != 0 }
        val moved = LibraryLayout.compute(plantedSpace(kept), previous = previous)
        val movedById = moved.associateBy { it.trackId }

        var drift = 0f
        for (id in kept) {
            val before = previous.getValue(id)
            val after = movedById.getValue(id)
            val dx = before[0] - after.x
            val dy = before[1] - after.y
            drift += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val mean = drift / kept.size
        // Positions are normalized to a 0..1 box, so this is a fraction of the screen. A mirrored
        // or unanchored rerun lands around 0.4-0.5 here.
        assertTrue(mean < 0.12f, "mean drift was $mean of the canvas")
    }

    private fun plantedSpace(ids: List<TrackId>): LibraryVectorSpace {
        val audio = HashMap<TrackId, FloatArray>()
        val metadata = HashMap<TrackId, FloatArray>()
        for (id in ids) {
            // Group is derived from the id, so dropping tracks does not reshuffle the planting.
            val group = id.value.removePrefix("t").toInt() / PER_GROUP
            var state = (group * 7919L + id.value.hashCode()).toLong()
            fun noise(): Float {
                state = state * 6364136223846793005L + 1442695040888963407L
                return ((state ushr 33).toFloat() / (1L shl 31).toFloat()) - 0.5f
            }
            audio[id] = FloatArray(DIM) { d -> (if (d == group) 8f else 0f) + noise() * 0.6f }
            metadata[id] = FloatArray(DIM) { d -> (if (d == group) 8f else 0f) + noise() * 0.6f }
        }
        return requireNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = DIM, metadataDim = DIM),
        )
    }

    private fun squaredDistance(a: LayoutPoint, b: LayoutPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :core:smart:testAndroidHostTest --tests "*LayoutQualityTest*"`
Expected: PASS, 2 tests. If either fails, the layout is broken — do not lower `FLOOR` or the drift
threshold to make it pass.

- [ ] **Step 3: Run the whole suite**

Run: `./gradlew :core:smart:testAndroidHostTest :core:history:testAndroidHostTest :composeApp:testAndroidHostTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add core/smart/src/commonTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/LayoutQualityTest.kt
git commit -m "test(map): guard the layout against silent quality and churn regressions"
```

---

## Deferred, deliberately

These are named so they are decisions rather than omissions:

- **The three open questions in the spec** (`docs/map-page.md` §11): whether *Play region* shuffles, whether *SMART from here* seeds from the medoid or the least-played member, and whether `DEFAULT_K = 8` is right for a map when it was chosen for a carousel row. Task 10 implements medoid-first and medoid-seeded; both are one-line changes once the questions are answered.
- **Swapping the layout algorithm.** The harness in `~/Documents/LJ/map-layout-2026-07-25/` scores any candidate on the same playlist@15 metric. `LibraryLayout.VERSION` is the cache key, so bumping it retires every stored map cleanly.
