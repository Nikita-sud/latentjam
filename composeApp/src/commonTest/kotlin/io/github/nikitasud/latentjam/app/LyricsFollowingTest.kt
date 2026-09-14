/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.LyricLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

@OptIn(ExperimentalCoroutinesApi::class)
internal class LyricsFollowingTest {
    @Test
    fun seeksResolveBeforeFirstCueAcrossTranslationsAndDuplicateTimes() {
        val timeline = LyricsTimeline(
            listOf(
                LyricLine(null, "Intro"),
                LyricLine(1_000, "First"),
                LyricLine(null, "Translation"),
                LyricLine(2_000, "Second"),
                LyricLine(2_000, "Simultaneous"),
                LyricLine(5_000, "Last"),
            ),
        )
        assertEquals(-1, timeline.activeIndexAt(849))
        assertEquals(1, timeline.activeIndexAt(850))
        assertEquals(4, timeline.activeIndexAt(2_000))
        assertEquals(5, timeline.activeIndexAt(20_000))
        assertEquals(1, timeline.activeIndexAt(1_100)) // Seek backwards, independent of earlier lookups.
    }

    @Test
    fun saturatedCueDoesNotOverflowAndUntimedLyricsHaveNoActiveRow() {
        assertEquals(-1, LyricsTimeline(listOf(LyricLine(null, "Words"))).activeIndexAt(1_000))
        val timeline = LyricsTimeline(listOf(LyricLine(Long.MAX_VALUE, "Extreme offset")))
        assertEquals(-1, timeline.activeIndexAt(0))
        assertEquals(0, timeline.activeIndexAt(Long.MAX_VALUE))
    }

    @Test
    fun pausedOrLongLyricResumesAfterHoldWithoutAnotherCueEvent() = runTest {
        val requests = MutableStateFlow(request().copy(interactionEndedAt = testScheduler.markNow()))
        val followed = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            followLyrics(requests) { followed += it.activeIndex }
        }
        advanceTimeBy(3_999)
        runCurrent()
        assertTrue(followed.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(7), followed)
    }

    @Test
    fun changingCueUsesTheRemainingHoldAndNewTouchCancelsPendingFollow() = runTest {
        val requests = MutableStateFlow(request().copy(interactionEndedAt = testScheduler.markNow()))
        val followed = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            followLyrics(requests) { followed += it.activeIndex }
        }
        advanceTimeBy(2_000)
        requests.value = requests.value.copy(activeIndex = 8)
        advanceTimeBy(1_000)
        requests.value = requests.value.copy(interacting = true)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(followed.isEmpty())
        requests.value = requests.value.copy(interacting = false, interactionEndedAt = testScheduler.markNow())
        advanceTimeBy(3_000)
        requests.value = requests.value.copy(activeIndex = 9)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(9), followed)
    }

    @Test
    fun followWaitsForViewportAndTouchCancelsAnOngoingAnimation() = runTest {
        val requests = MutableStateFlow(request().copy(viewportHeight = 0))
        val started = mutableListOf<Int>()
        var cancelled = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            followLyrics(requests) {
                started += it.activeIndex
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        runCurrent()
        assertTrue(started.isEmpty())
        requests.value = requests.value.copy(viewportHeight = 600)
        runCurrent()
        assertEquals(listOf(7), started)
        requests.value = requests.value.copy(interacting = true)
        runCurrent()
        assertTrue(cancelled)
    }

    private fun request() = LyricsFollowRequest(
        activeIndex = 7,
        interacting = false,
        interactionEndedAt = null,
        viewportHeight = 600,
        reduceMotion = false,
    )

    private fun TestCoroutineScheduler.markNow(): TimeMark {
        val startedAt = currentTime
        return object : TimeMark {
            override fun elapsedNow(): Duration = (currentTime - startedAt).milliseconds
        }
    }
}
