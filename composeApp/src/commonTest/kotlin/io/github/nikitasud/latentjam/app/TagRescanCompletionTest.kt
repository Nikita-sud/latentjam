/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TagEdits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
internal class TagRescanCompletionTest {
    private class Fixture(test: TestScope, slow: Boolean = true, fail: Boolean = false) {
        val gate = CompletableDeferred<Unit>()
        var indexedTitle = "Old"
        var displayedTitle = "Old"
        var reports = 0
        val files = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT).apply { this.files.put("a", testMp3()) }
        private val backend = object : TagWriteBackend<String> by files {
            override suspend fun rescan(keys: List<String>) {
                if (slow) gate.await()
                if (fail) error("scanner unavailable")
                indexedTitle = "New"
            }
        }
        val coordinator = TagWriteCoordinator(backend, test.backgroundScope, StandardTestDispatcher(test.testScheduler))
        init {
            val id = assertNotNull(coordinator.enqueue(listOf("a"), TagEdits(title = "New")))
            coordinator.listen(id) { displayedTitle = indexedTitle; reports++ }
        }
        fun deliver() { coordinator.deliver(assertNotNull(coordinator.completed.value).id) }
        fun observe(test: TestScope) = test.backgroundScope.launch {
            coordinator.rescanRevision.collect { if (it > 0L) displayedTitle = indexedTitle }
        }
    }

    @Test
    fun aSlowScanRefreshesMetadataAfterTheSaveWasAlreadyReported() = runTest {
        val fixture = Fixture(this)
        fixture.observe(this)
        runCurrent()
        advanceTimeBy(1501)
        runCurrent()
        fixture.deliver()
        assertEquals(1, fixture.reports)
        assertEquals("Old", fixture.displayedTitle)
        fixture.gate.complete(Unit)
        runCurrent()
        assertEquals("New", fixture.displayedTitle)
        assertEquals(1L, fixture.coordinator.rescanRevision.value)
        assertEquals(1, fixture.reports, "A scanner refresh must not report the save twice")
    }

    @Test
    fun aRecreatedConsumerSeesTheScanThatFinishedWhileItWasAway() = runTest {
        val fixture = Fixture(this)
        val oldConsumer = fixture.observe(this)
        runCurrent()
        advanceTimeBy(1501)
        runCurrent()
        fixture.deliver()
        oldConsumer.cancel()
        fixture.gate.complete(Unit)
        runCurrent()
        assertEquals("Old", fixture.displayedTitle)
        fixture.observe(this)
        runCurrent()
        assertEquals("New", fixture.displayedTitle)
    }

    @Test
    fun aFastScanNeedsOnlyTheOriginalReportRefresh() = runTest {
        val fixture = Fixture(this, slow = false)
        runCurrent()
        fixture.deliver()
        assertEquals("New", fixture.displayedTitle)
        assertEquals(0L, fixture.coordinator.rescanRevision.value)
        assertEquals(1, fixture.reports)
    }

    @Test
    fun aFailedLateScanDoesNotFailOrRedeliverTheSavedFiles() = runTest {
        val fixture = Fixture(this, fail = true)
        runCurrent()
        advanceTimeBy(1501)
        runCurrent()
        fixture.deliver()
        fixture.gate.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.reports)
        assertEquals(1L, fixture.coordinator.rescanRevision.value)
    }
}
