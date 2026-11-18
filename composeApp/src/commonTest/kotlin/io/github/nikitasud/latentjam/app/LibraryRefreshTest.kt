/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRefreshTest {

    /** A library whose hidden set a scan reads when it starts, as the real one does. */
    private class FakeLibrary {
        val hidden = mutableSetOf("b", "c")
        val shown = mutableListOf<Set<String>>()
        val indexed = mutableListOf<Boolean>()
        var busy = false
        var snackbars = 0

        /** Held open by a test to keep a pass, or the snackbar after it, on screen. */
        var scanGate: CompletableDeferred<Unit>? = null
        var snackbarGate: CompletableDeferred<Unit>? = null

        suspend fun pass(index: Boolean) {
            indexed += index
            val visible = setOf("a", "b", "c") - hidden
            scanGate?.await()
            shown += visible
        }

        suspend fun snackbar() {
            snackbars++
            snackbarGate?.await()
        }
    }

    private fun LibraryRefresher.request(scope: TestScope, library: FakeLibrary, indexNewFiles: Boolean) =
        request(scope, indexNewFiles, onBusy = { library.busy = it }, pass = library::pass, done = library::snackbar)

    @Test fun `a track restored while the refreshed snackbar is still up is read into the library`() = runTest {
        val library = FakeLibrary()
        val refresher = LibraryRefresher()
        library.snackbarGate = CompletableDeferred()
        library.hidden -= "b"
        refresher.request(this, library, indexNewFiles = false)
        runCurrent()
        assertEquals(1, library.snackbars)
        // The snackbar of the first restore is still shown: the flag is down, and the second restore is not dropped.
        assertFalse(library.busy)
        library.hidden -= "c"
        refresher.request(this, library, indexNewFiles = false)
        runCurrent()
        assertEquals(setOf("a", "b", "c"), library.shown.last())
        library.snackbarGate?.complete(Unit)
    }

    @Test fun `a request made while a pass runs is followed by another pass`() = runTest {
        val library = FakeLibrary()
        val refresher = LibraryRefresher()
        val gate = CompletableDeferred<Unit>()
        library.scanGate = gate
        library.hidden -= "b"
        refresher.request(this, library, indexNewFiles = false)
        runCurrent()
        assertTrue(library.busy)
        // The running pass has read the hidden set already; this restore needs a pass of its own.
        library.hidden -= "c"
        refresher.request(this, library, indexNewFiles = false)
        refresher.request(this, library, indexNewFiles = false)
        library.scanGate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(setOf("a", "b"), setOf("a", "b", "c")), library.shown)
        assertFalse(library.busy)
        assertEquals(1, library.snackbars)
    }

    @Test fun `only the Refresh row waits for the system to index new files`() = runTest {
        val library = FakeLibrary()
        val refresher = LibraryRefresher()
        refresher.request(this, library, indexNewFiles = false)
        runCurrent()
        refresher.request(this, library, indexNewFiles = true)
        runCurrent()
        assertEquals(listOf(false, true), library.indexed)
    }

    @Test fun `a Refresh asked for during a plain pass still indexes new files`() = runTest {
        val library = FakeLibrary()
        val refresher = LibraryRefresher()
        val gate = CompletableDeferred<Unit>()
        library.scanGate = gate
        refresher.request(this, library, indexNewFiles = false)
        runCurrent()
        refresher.request(this, library, indexNewFiles = true)
        library.scanGate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), library.indexed)
    }

    @Test fun `a scan that finishes after a newer one published is dropped`() {
        val order = LibraryScanOrder()
        val older = order.begin()
        val newer = order.begin()
        assertEquals(ScanOutcome.PUBLISH, order.finish(newer))
        assertEquals(ScanOutcome.SUPERSEDED, order.finish(older))
    }

    @Test fun `scans that finish in the order they began both publish`() {
        val order = LibraryScanOrder()
        val older = order.begin()
        val newer = order.begin()
        assertEquals(ScanOutcome.PUBLISH, order.finish(older))
        assertEquals(ScanOutcome.PUBLISH, order.finish(newer))
    }

    @Test fun `a scan that began before a track was hidden reads the library again`() {
        val order = LibraryScanOrder()
        val running = order.begin()
        order.changedLocally()
        assertEquals(ScanOutcome.RESCAN, order.finish(running))
        assertEquals(ScanOutcome.PUBLISH, order.finish(order.begin()))
    }

    @Test fun `a scan that began before a hide is dropped once a later scan has published`() {
        val order = LibraryScanOrder()
        val running = order.begin()
        order.changedLocally()
        assertEquals(ScanOutcome.PUBLISH, order.finish(order.begin()))
        assertEquals(ScanOutcome.SUPERSEDED, order.finish(running))
    }
}
