/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BusyNoticeTest {

    @Test fun `the busy flag is down while the snackbar of a finished action is still shown`() = runTest {
        val snackbar = SnackbarHostState()
        var busy = false
        val job = launch { snackbar.showAfterBusy({ busy = it }, failure = "failed") { "removed" } }
        runCurrent()
        // Nobody has dismissed the snackbar: it is on screen, and the next action is already allowed.
        assertEquals("removed", snackbar.currentSnackbarData?.visuals?.message)
        assertFalse(busy)
        job.cancel()
    }

    @Test fun `the busy flag is down while the snackbar of a failed action is still shown`() = runTest {
        val snackbar = SnackbarHostState()
        var busy = false
        val job = launch { snackbar.showAfterBusy({ busy = it }, failure = "failed") { error("storage") } }
        runCurrent()
        assertEquals("failed", snackbar.currentSnackbarData?.visuals?.message)
        assertFalse(busy)
        job.cancel()
    }

    @Test fun `the flag is up while the action runs and nothing is shown for an action with no message`() = runTest {
        val snackbar = SnackbarHostState()
        var busy = false
        val gate = CompletableDeferred<Unit>()
        launch { snackbar.showAfterBusy({ busy = it }, failure = "failed") { gate.await(); null } }
        runCurrent()
        assertTrue(busy)
        gate.complete(Unit)
        runCurrent()
        assertFalse(busy)
        assertNull(snackbar.currentSnackbarData)
    }

    @Test fun `a cancelled action lowers the flag and shows nothing`() = runTest {
        val snackbar = SnackbarHostState()
        var busy = false
        val gate = CompletableDeferred<Unit>()
        val job = launch { snackbar.showAfterBusy({ busy = it }, failure = "failed") { gate.await(); "done" } }
        runCurrent()
        job.cancel(CancellationException("screen left"))
        runCurrent()
        assertFalse(busy)
        assertNull(snackbar.currentSnackbarData)
    }
}
