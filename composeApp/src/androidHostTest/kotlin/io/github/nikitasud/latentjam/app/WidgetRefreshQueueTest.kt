/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class WidgetRefreshQueueTest {
    @Test
    fun burstsRenderEachTargetOnceAndFinishEveryBroadcast() {
        val scheduled = mutableListOf<() -> Unit>()
        val rendered = mutableListOf<Set<Int>>()
        var finished = 0
        val queue = WidgetRefreshQueue<Int>(scheduled::add, rendered::add) { throw it }

        repeat(100) { queue.request(listOf(1, 2, 2)) { finished++ } }
        queue.request(listOf(3)) { finished++ }

        assertEquals(1, scheduled.size)
        assertEquals(0, finished)
        scheduled.removeAt(0).invoke()
        assertEquals(listOf(setOf(1, 2, 3)), rendered)
        assertEquals(101, finished)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun updatesDuringRenderNeedOnlyOneFollowUpAndReadFreshState() {
        val scheduled = mutableListOf<() -> Unit>()
        val renderedRevisions = mutableListOf<Int>()
        var revision = 1
        var finished = 0
        lateinit var queue: WidgetRefreshQueue<Int>
        queue = WidgetRefreshQueue(scheduled::add, { targets ->
            assertEquals(setOf(1), targets)
            renderedRevisions += revision
            if (revision == 1) {
                revision = 2
                repeat(50) { queue.request(listOf(1)) { finished++ } }
            }
        }) { throw it }

        queue.request(listOf(1)) { finished++ }
        scheduled.removeAt(0).invoke()
        assertEquals(listOf(1, 2), renderedRevisions)
        assertEquals(51, finished)
        assertTrue(scheduled.isEmpty())
        queue.request(listOf(1)) { finished++ }
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).invoke()
        assertEquals(52, finished)
    }

    @Test
    fun failedRenderAndCompletionDoNotStrandOtherBroadcastsOrFutureUpdates() {
        val scheduled = mutableListOf<() -> Unit>()
        val failures = mutableListOf<Throwable>()
        var failRender = true
        var finished = 0
        var renders = 0
        val queue = WidgetRefreshQueue<Int>(scheduled::add, {
            renders++
            if (failRender) error("artwork unreadable")
        }, failures::add)

        queue.request(listOf(1)) { error("already finished") }
        queue.request(listOf(2)) { finished++ }
        scheduled.removeAt(0).invoke()
        assertEquals(1, finished)
        assertEquals(2, failures.size)

        failRender = false
        queue.request(listOf(1)) { finished++ }
        scheduled.removeAt(0).invoke()
        assertEquals(2, renders)
        assertEquals(2, finished)
    }

    @Test
    fun rejectedScheduleFinishesRequestsAndAllowsRetry() {
        val scheduled = mutableListOf<() -> Unit>()
        val failures = mutableListOf<Throwable>()
        var reject = true
        var finished = 0
        val queue = WidgetRefreshQueue<Int>({ work ->
            if (reject) error("executor unavailable")
            scheduled += work
        }, {}, failures::add)

        queue.request(listOf(1)) { finished++ }
        assertEquals(1, finished)
        assertEquals(1, failures.size)
        reject = false
        queue.request(listOf(1)) { finished++ }
        scheduled.removeAt(0).invoke()
        assertEquals(2, finished)
    }

    @Test
    fun noInstalledWidgetsDoNotScheduleWork() {
        var finished = false
        val queue = WidgetRefreshQueue<Int>({ error("unnecessary work") }, {}, { throw it })
        queue.request(emptyList()) { finished = true }
        assertTrue(finished)
    }
}
