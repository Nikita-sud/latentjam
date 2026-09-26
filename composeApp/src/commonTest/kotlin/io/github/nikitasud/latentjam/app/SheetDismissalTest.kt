/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class SheetDismissalTest {

    @Test
    fun aHideThatRunsToItsEndReportsTheSheetGone() = runTest {
        val reported = mutableListOf<Boolean>()
        hideSheetThen(hide = { delay(150) }, onHidden = { completed -> reported += completed })
        advanceUntilIdle()
        assertEquals(listOf(true), reported)
    }

    @Test
    fun aHideInterruptedByAnotherStillReportsTheSheetGone() = runTest {
        val reported = mutableListOf<Boolean>()
        // Material3 cancels a running hide when Back or a scrim tap starts a hide of its own.
        hideSheetThen(
            hide = { throw CancellationException("another hide took over") },
            onHidden = { completed -> reported += completed },
        )
        advanceUntilIdle()
        assertEquals(listOf(false), reported)
    }
}
