/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.IosBatchDotProducts
import io.github.nikitasud.latentjam.smart.chain.batchDots
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Opt-in check of Kotlin pinning, the Swift callback and C arithmetic, plus first/warm batch timings.
 *
 * A missing native path is reported for what it is: either the Swift shell never installed a provider, or
 * the installed bridge answered false — the default of the optional callback included. Those two say
 * different things about the build, so they must not share one message.
 */
public fun runIosBatchDotsDiagnostics(): String = runCatching {
    check(IosInferenceRegistry.current() != null) {
        "no IosInferenceProvider installed: the Swift shell never handed the bridge over"
    }
    val random = Random(71236)
    var checked = 0
    for (dim in listOf(1, 17, 384, 960)) {
        val n = 23
        val matrix = FloatArray(n * dim) { random.nextFloat() - 0.5f }
        val query = FloatArray(dim + 7) { random.nextFloat() - 0.5f }
        val rows = (0 until n).shuffled(random).toIntArray()
        for (alias in listOf(false, true)) {
            val q = if (alias) matrix else query
            val offset = if (alias) dim else 7
            val expected = FloatArray(n) { -1234.5f }
            val actual = expected.copyOf()
            batchDots(matrix, dim, q, offset, rows, 21, expected)
            check(IosBatchDotProducts.compute(matrix, dim, q, offset, rows, 21, actual)) {
                "the installed provider answered false to the batch-dots callback"
            }
            for (row in 0 until n) {
                check(expected[row].toRawBits() == actual[row].toRawBits()) { "Parity failed dim=$dim row=$row" }
                checked++
            }
        }
    }
    buildString {
        appendLine("LJ_DOTS_IOS parity=ok outputs=$checked")
        for (n in listOf(3_000, 10_000)) {
            val dim = 960
            val matrix = FloatArray(n * dim) { random.nextFloat() - 0.5f }
            val query = FloatArray(dim) { random.nextFloat() - 0.5f }
            val rows = IntArray(n) { it }
            val expected = FloatArray(n)
            batchDots(matrix, dim, query, 0, rows, n, expected)
            for (native in listOf(false, true)) {
                val output = FloatArray(n)
                val times = DoubleArray(31)
                var first = 0.0
                var checksum = 0
                repeat(36) { run ->
                    val start = TimeSource.Monotonic.markNow()
                    if (native) {
                        check(IosBatchDotProducts.compute(matrix, dim, query, 0, rows, n, output)) {
                            "the installed provider answered false to the batch-dots callback at $n rows"
                        }
                    } else {
                        batchDots(matrix, dim, query, 0, rows, n, output)
                    }
                    val ms = start.elapsedNow().inWholeNanoseconds / 1e6
                    if (run == 0) first = ms
                    if (run >= 5) times[run - 5] = ms
                    checksum = checksum xor output[run % n].toRawBits()
                }
                for (row in 0 until n) check(expected[row].toRawBits() == output[row].toRawBits())
                times.sort()
                appendLine("LJ_DOTS_IOS rows=$n native=$native first_ms=$first median_ms=${times[times.size / 2]} checksum=$checksum")
            }
        }
    }
}.getOrElse { "LJ_DOTS_IOS failed: ${it.message}" }
