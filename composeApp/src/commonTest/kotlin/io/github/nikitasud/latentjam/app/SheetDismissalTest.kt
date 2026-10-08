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
    fun aCompletedHideDismissesAndRunsTheChosenActionExactlyOnce() = runTest {
        val reported = mutableListOf<String>()
        // The sheets whose close carries a choice (the track menu, a world, add-to-playlist, a
        // multi-selection) hand the owner's cleanup and that choice to hideSheetThenConfirmed.
        hideSheetThenConfirmed(
            hide = { delay(150) },
            onDismiss = { reported += "dismissed" },
            action = { reported += "action" },
        )
        advanceUntilIdle()
        assertEquals(listOf("dismissed", "action"), reported)
    }

    @Test
    fun anInterruptedHideDismissesButDropsTheChosenAction() = runTest {
        val reported = mutableListOf<String>()
        // Back or a scrim tap mid-animation cancels the hide. The sheet must still leave the
        // composition — a hidden sheet left composed swallows every touch — but that gesture
        // withdraws the choice, so it may not be carried out behind the listener's back.
        hideSheetThenConfirmed(
            hide = { throw CancellationException("another hide took over") },
            onDismiss = { reported += "dismissed" },
            action = { reported += "action" },
        )
        advanceUntilIdle()
        assertEquals(listOf("dismissed"), reported)
    }

    @Test
    fun anActionThatIsTheTapsOwnEffectSurvivesAnInterruptedHide() = runTest {
        val reported = mutableListOf<String>()
        // dismissWhile (play, queue, favourite, hide-from-library) runs its action before the hide
        // starts: it is the tap's own effect rather than a handoff, so the animation cannot retract
        // it, while the owner is still told exactly once that the sheet is gone.
        reported += "action"
        hideSheetThen(
            hide = { throw CancellationException("another hide took over") },
            onHidden = { reported += "dismissed" },
        )
        advanceUntilIdle()
        assertEquals(listOf("action", "dismissed"), reported)
    }
}
