/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class PrivacyClearTest {

    @Test
    fun `a clear whose screen is left in the middle still runs every step`() = runTest {
        val firstWrite = CompletableDeferred<Unit>()
        val steps = ArrayList<String>()
        val clearing = launch {
            runToTheEnd {
                steps += "history"
                firstWrite.await()
                steps += "journal"
            }
        }
        runCurrent()
        clearing.cancel() // Back, while the log is being deleted.
        firstWrite.complete(Unit)
        clearing.join()

        assertEquals(listOf("history", "journal"), steps)
        assertTrue(clearing.isCancelled, "the screen's coroutine still ends as cancelled")
    }
}
