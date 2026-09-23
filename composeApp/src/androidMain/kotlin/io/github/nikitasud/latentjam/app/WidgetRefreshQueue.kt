/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/** One scheduled drain, with duplicate targets folded together while a render is in flight. */
internal class WidgetRefreshQueue<T>(
    private val execute: (() -> Unit) -> Unit,
    private val render: (Set<T>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private val pendingTargets = linkedSetOf<T>()
    private val completions = mutableListOf<() -> Unit>()
    private var scheduled = false

    fun request(targets: Collection<T>, onComplete: () -> Unit) {
        if (targets.isEmpty()) {
            finish(onComplete)
            return
        }
        val shouldSchedule = synchronized(lock) {
            pendingTargets.addAll(targets)
            completions.add(onComplete)
            if (scheduled) false else {
                scheduled = true
                true
            }
        }
        if (shouldSchedule) {
            try {
                execute(::drain)
            } catch (failure: Throwable) {
                val abandoned = synchronized(lock) {
                    pendingTargets.clear()
                    scheduled = false
                    completions.toList().also { completions.clear() }
                }
                abandoned.forEach(::finish)
                onFailure(failure)
            }
        }
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (pendingTargets.isEmpty()) {
                    scheduled = false
                    return
                }
                (pendingTargets.toSet() to completions.toList()).also {
                    pendingTargets.clear()
                    completions.clear()
                }
            }
            try {
                render(batch.first)
            } catch (failure: Throwable) {
                onFailure(failure)
            } finally {
                // Each broadcast retains its own goAsync completion, even when its render was
                // coalesced. One failed finish must not leave the remaining broadcasts open.
                batch.second.forEach(::finish)
            }
        }
    }

    private fun finish(onComplete: () -> Unit) {
        try {
            onComplete()
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }
}
