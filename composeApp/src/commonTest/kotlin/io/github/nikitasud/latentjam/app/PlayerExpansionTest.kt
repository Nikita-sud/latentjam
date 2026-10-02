/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The expansion's state machine against the app's open state. The app shows the player as open
 * exactly when [PlayerExpansion.target] says so (the sheet reports every release to it), so the
 * Back owner is open-and-at-rest-at-1 or closed-and-at-rest-at-0, never anything in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerExpansionTest {
    private val travel = 1_000f
    private val commit = 140f
    private val fling = 500f

    /** Frames every 16 ms of virtual time, which is all `animate` needs. */
    private class TestFrameClock(private val scope: TestScope) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(scope.testScheduler.currentTime * 1_000_000L)
        }
    }

    private fun TestScope.expansion(open: Boolean): Pair<PlayerExpansion, CoroutineScope> {
        val animations = CoroutineScope(
            StandardTestDispatcher(testScheduler) + TestFrameClock(this) + SupervisorJob(),
        )
        return PlayerExpansion(open, animations).also { it.travelPx = travel } to animations
    }

    private fun PlayerExpansion.assertAtRest(open: Boolean) {
        assertFalse(dragging)
        assertFalse(settling)
        assertEquals(open, target)
        assertEquals(if (open) 1f else 0f, progress)
        assertFalse(outOfPlace)
    }

    @Test
    fun aPillDragCarriedToTheTopAndReleasedOpensThePlayer() = runTest {
        val (expansion, animations) = expansion(open = false)
        expansion.dragBy(-1_400f)
        assertEquals(1f, expansion.progress)
        assertTrue(expansion.dragging)
        // Still the finger's, not the app's: nothing may be torn down under it.
        assertFalse(expansion.target)
        assertTrue(expansion.release(downVelocity = 0f, commit, fling, reduceMotion = false))
        advanceUntilIdle()
        expansion.assertAtRest(open = true)
        animations.cancel()
    }

    @Test
    fun aPillDragAtTheTopThatLosesItsGestureGoesBackToTheMiniPlayer() = runTest {
        val (expansion, animations) = expansion(open = false)
        expansion.dragBy(-1_400f)
        expansion.abandonDrag(reduceMotion = false)
        advanceUntilIdle()
        expansion.assertAtRest(open = false)
        animations.cancel()
    }

    @Test
    fun backDuringAPullClosesWhateverTheFingerDoes() = runTest {
        val (expansion, animations) = expansion(open = true)
        expansion.dragBy(50f)
        // Back arrives mid-pull: remembered, not animated against the finger.
        expansion.request(open = false, reduceMotion = false)
        assertEquals(0.95f, expansion.progress, absoluteTolerance = 0.0001f)
        assertFalse(expansion.settling)
        expansion.dragBy(-50f)
        // A release that by distance would stay open still honours the explicit close.
        assertFalse(expansion.release(downVelocity = 0f, commit, fling, reduceMotion = false))
        advanceUntilIdle()
        expansion.assertAtRest(open = false)
        animations.cancel()
    }

    @Test
    fun backDuringADragUpFromThePillKeepsThePlayerClosed() = runTest {
        val (expansion, animations) = expansion(open = false)
        expansion.dragBy(-600f)
        expansion.request(open = false, reduceMotion = false)
        assertFalse(expansion.release(downVelocity = -2_000f, commit, fling, reduceMotion = false))
        advanceUntilIdle()
        expansion.assertAtRest(open = false)
        animations.cancel()
    }

    @Test
    fun aReleaseIsMeasuredFromTheEndThePlayerBelongedTo() = runTest {
        val (expansion, animations) = expansion(open = true)
        expansion.dragBy(100f) // less than the commit distance
        assertTrue(expansion.release(downVelocity = 0f, commit, fling, reduceMotion = false))
        advanceUntilIdle()
        expansion.assertAtRest(open = true)
        expansion.dragBy(200f) // past it
        assertFalse(expansion.release(downVelocity = 0f, commit, fling, reduceMotion = false))
        advanceUntilIdle()
        expansion.assertAtRest(open = false)
        // A fling up from the mini player opens after a short lift.
        expansion.dragBy(-30f)
        assertTrue(expansion.release(downVelocity = -900f, commit, fling, reduceMotion = false))
        advanceUntilIdle()
        expansion.assertAtRest(open = true)
        animations.cancel()
    }

    @Test
    fun aRequestTurnsARunningSettleAround() = runTest {
        val (expansion, animations) = expansion(open = false)
        expansion.request(open = true, reduceMotion = false)
        testScheduler.advanceTimeBy(100)
        assertTrue(expansion.settling)
        assertTrue(expansion.progress in 0.01f..0.99f)
        expansion.request(open = false, reduceMotion = false)
        advanceUntilIdle()
        expansion.assertAtRest(open = false)
        animations.cancel()
    }

    @Test
    fun theReconcilerReturnsAStrayRestingProgressToTheTarget() = runTest {
        val (expansion, animations) = expansion(open = true)
        expansion.request(open = false, reduceMotion = false)
        testScheduler.advanceTimeBy(100)
        // The settle is cut off between the ends (its coroutine cancelled from outside).
        animations.coroutineContext[Job]!!.children.forEach { it.cancel() }
        testScheduler.runCurrent()
        assertFalse(expansion.settling)
        assertTrue(expansion.outOfPlace)
        expansion.reconcile(reduceMotion = false)
        advanceUntilIdle()
        expansion.assertAtRest(open = false)
        // At rest and in place there is nothing to do.
        expansion.reconcile(reduceMotion = false)
        assertFalse(expansion.settling)
        animations.cancel()
    }

    @Test
    fun reducedMotionJumpsToTheEndAtOnce() = runTest {
        val (expansion, animations) = expansion(open = false)
        expansion.request(open = true, reduceMotion = true)
        expansion.assertAtRest(open = true)
        expansion.dragBy(300f)
        assertFalse(expansion.release(downVelocity = 0f, commit, fling, reduceMotion = true))
        expansion.assertAtRest(open = false)
        animations.cancel()
    }
}
