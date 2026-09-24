/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RailSelectionCallbacksTest {
    @Test
    fun retainedRailUpdatesTheNewPagesBubbleAndReleasesTheNewGesture() {
        val first = Bubble()
        val second = Bubble()
        val original = callbacksFor(first)
        // rememberUpdatedState uses the same structural equality policy as mutableStateOf.
        // Raw references to the local functions below compare equal despite different captures.
        val start = mutableStateOf(original.start)
        val select = mutableStateOf(original.select)
        val end = mutableStateOf(original.end)
        val cancel = mutableStateOf(original.cancel)
        start.value()
        select.value(2, 90f)
        end.value()

        val replacement = callbacksFor(second)
        start.value = replacement.start
        select.value = replacement.select
        end.value = replacement.end
        cancel.value = replacement.cancel

        start.value()
        select.value(7, 240f)
        assertTrue(second.visible)
        assertEquals(7, second.index)
        assertEquals(240f, second.y)
        assertEquals(2, first.index)
        assertEquals(90f, first.y)
        assertFalse(first.visible)

        end.value()
        assertFalse(second.visible)
        assertEquals(1, second.drops)
        assertEquals(1, first.drops)

        start.value()
        select.value(3, 110f)
        cancel.value()
        assertFalse(second.visible)
        assertEquals(1, second.cancellations)
        assertEquals(0, first.cancellations)
    }

    @Test
    fun revisitingAPageDoesNotWriteIntoItsDisposedBubbleState() {
        val old = Bubble()
        val select = mutableStateOf(callbacksFor(old).select)
        select.value(1, 40f)
        repeat(3) { visit ->
            val restored = Bubble()
            select.value = callbacksFor(restored).select
            select.value(visit + 2, 100f + visit)
            assertEquals(visit + 2, restored.index)
            assertEquals(100f + visit, restored.y)
            assertEquals(1, old.index)
        }
    }

    private class Bubble {
        var visible = false
        var index: Int? = null
        var y = 0f
        var drops = 0
        var cancellations = 0
    }

    private fun callbacksFor(bubble: Bubble): RailSelectionCallbacks {
        // Match the local-function captures used by StandaloneAlphabetRailOverlay.
        fun start() { bubble.visible = true }
        fun select(index: Int, y: Float) { bubble.index = index; bubble.y = y }
        fun end() { bubble.visible = false; bubble.drops++ }
        fun cancel() { bubble.visible = false; bubble.cancellations++ }
        return RailSelectionCallbacks(::start, ::select, ::end, ::cancel)
    }
}
