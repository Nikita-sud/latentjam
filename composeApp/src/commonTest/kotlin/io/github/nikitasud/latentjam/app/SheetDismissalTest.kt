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

    @Test
    fun aSheetThatClosesItselfStillDismissesAndActsOnceWhenTheHideIsInterrupted() = runTest {
        val reported = mutableListOf<String>()
        // The sheets that close themselves after a tap (the track menu, a world, add-to-playlist)
        // hand hideSheetThen the owner's cleanup and then the action the tap chose. Back or a scrim
        // tap during the animation cancels the hide; neither may be dropped or run twice.
        hideSheetThen(
            hide = { throw CancellationException("another hide took over") },
            onHidden = {
                reported += "dismissed"
                reported += "action"
            },
        )
        advanceUntilIdle()
        assertEquals(listOf("dismissed", "action"), reported)
    }
}
