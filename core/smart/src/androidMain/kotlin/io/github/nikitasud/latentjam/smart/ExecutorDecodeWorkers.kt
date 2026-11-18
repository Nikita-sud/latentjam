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
    /** How long after its pool was abandoned a thread still alive counts as [stuck] (tests shorten it). */
    private val stuckAfterNanos: Long = STUCK_WORKER_NANOS,
    private val threadFactory: ThreadFactory,
) : DecodeWorkers {
    private var pool: ExecutorService? = null
    private var current: CoroutineDispatcher? = null

    /** The threads the current pool has made. */
    private var poolThreads: MutableList<Thread>? = null

    /** The threads of abandoned pools, with when ([System.nanoTime]) each pool was abandoned. */
    private val abandoned = ArrayList<Pair<List<Thread>, Long>>()

    override val dispatcher: CoroutineDispatcher
        @Synchronized get() = current ?: run {
            val made = java.util.concurrent.CopyOnWriteArrayList<Thread>()
            val created = Executors.newFixedThreadPool(threads) { task -> threadFactory.newThread(task).also { made += it } }
            pool = created
            poolThreads = made
            created.asCoroutineDispatcher().also { current = it }
        }

    @Synchronized
    override fun abandon() {
        pool?.shutdownNow()
        poolThreads?.let { abandoned += it to System.nanoTime() }
        pool = null
        poolThreads = null
        current = null
    }

    override val stuck: Int
        @Synchronized get() {
            val now = System.nanoTime()
            // A pool whose threads have all ended has nothing left to count.
            abandoned.removeAll { (made, _) -> made.none(Thread::isAlive) }
            return abandoned.sumOf { (made, at) -> if (now - at >= stuckAfterNanos) made.count(Thread::isAlive) else 0 }
        }

    override fun close() {
        abandon()
    }
}

/**
 * A thread of an abandoned pool still alive this long sits in a platform call. A worker that is not stuck notices its
 * cancellation at its next liveness check and ends within one window's 30 s wall budget (AndroidAudioDecoder), the
 * same 30 s after which its codec no longer counts as live there.
 */
internal const val STUCK_WORKER_NANOS: Long = 30_000_000_000L
