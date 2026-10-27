/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * [DecodeWorkers] over a fixed pool of [threads] daemon threads, created on first use.
 *
 * [abandon] shuts the pool down without waiting (interrupting its threads) and the next decoding gets
 * a fresh one: a thread stuck in MediaExtractor or MediaCodec stays stuck, but it no longer counts
 * against the pool. Work still queued on the old pool is dropped: it never runs and its Deferred never
 * completes, so it must not be awaited after an abandon (DeadlineDecoding starts it again instead).
 */
internal class ExecutorDecodeWorkers(
    private val threads: Int,
    private val threadFactory: ThreadFactory,
) : DecodeWorkers {
    private var pool: ExecutorService? = null
    private var current: CoroutineDispatcher? = null

    override val dispatcher: CoroutineDispatcher
        @Synchronized get() = current ?: Executors.newFixedThreadPool(threads, threadFactory).let { created ->
            pool = created
            created.asCoroutineDispatcher().also { current = it }
        }

    @Synchronized
    override fun abandon() {
        pool?.shutdownNow()
        pool = null
        current = null
    }

    override fun close() {
        abandon()
    }
}
