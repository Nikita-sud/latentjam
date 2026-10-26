/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ManualChangeFadesTest {
    @Test
    fun aChangeLeavesHoldsSilenceThenArrivesOverItsOwnLengths() {
        val fade = TransportFadeState()
        fade.beginChange(1_000L, ManualChange.TRACK.outMs)
        assertEquals(1f, fade.gainAt(1_000L))
        assertEquals(0.7071f, fade.gainAt(1_075L), absoluteTolerance = 0.001f)
        assertEquals(0f, fade.gainAt(1_150L))
        // Silence holds while the backend loads, however long that takes.
        assertEquals(0f, fade.gainAt(5_000L))
        assertTrue(fade.changePending)

        fade.endChange(5_000L, ManualChange.TRACK.inMs)
        assertFalse(fade.changePending)
        assertEquals(0f, fade.gainAt(5_000L))
        assertEquals(0.7071f, fade.gainAt(5_075L), absoluteTolerance = 0.001f)
        assertEquals(1f, fade.gainAt(5_150L))
        assertFalse(fade.active)
    }

    @Test
    fun aSeekFadeIsShorterAndAChangeMidResumeLeavesFromTheGainHeard() {
        val fade = TransportFadeState()
        fade.beginResume(0L)
        val heard = fade.gainAt(110L)
        fade.beginChange(110L, ManualChange.SEEK.outMs)
        assertEquals(heard, fade.gainAt(110L))
        assertEquals(0f, fade.gainAt(170L))
        fade.endChange(170L, ManualChange.SEEK.inMs)
        assertEquals(1f, fade.gainAt(270L))
    }

    @Test
    fun theArrivalWaitsForPlaybackToStartAfterTheChange() {
        val fade = TransportFadeState()
        fade.beginChange(0L, 60L)
        fade.endChange(60L, 100L, startImmediately = false)
        assertEquals(0f, fade.gainAt(500L))
        fade.onPlaybackStarted(500L)
        assertEquals(1f, fade.gainAt(600L))
    }

    private class Rig(scope: TestScope) {
        val fade = TransportFadeState()
        var playing = true
        val done = mutableListOf<String>()
        val fades = ManualChangeFades(fade, scope.backgroundScope, { scope.currentTime }, { playing }, onGain = {})
    }

    @Test
    fun whilePlayingAChangeWaitsForSilenceAndQuickRepeatsAllRunThere() = runTest {
        val rig = Rig(this)
        rig.fades.run(ManualChange.TRACK) { rig.done += "next" }
        runCurrent()
        assertEquals(emptyList(), rig.done, "not before the sound has left")
        advanceTimeBy(100L)
        rig.fades.run(ManualChange.TRACK) { rig.done += "next" }
        rig.fades.run(ManualChange.SEEK) { rig.done += "seek" }
        advanceTimeBy(60L)
        runCurrent()
        assertEquals(listOf("next", "next", "seek"), rig.done)
        assertFalse(rig.fades.pending)
        // One arrival from the silence at 150 ms, over the longest of the changes made.
        assertTrue(rig.fade.gainAt(currentTime) < 0.15f)
        assertTrue(rig.fade.gainAt(150L + ManualChange.SEEK.inMs) < 1f)
        assertEquals(1f, rig.fade.gainAt(150L + ManualChange.TRACK.inMs))
    }

    @Test
    fun pausedAChangeRunsAtOnce() = runTest {
        val rig = Rig(this)
        rig.playing = false
        rig.fades.run(ManualChange.TRACK) { rig.done += "next" }
        assertEquals(listOf("next"), rig.done)
        assertFalse(rig.fade.active)
        assertFalse(rig.fade.changePending)
    }

    @Test
    fun anotherCommandFirstRunsWhatWasAskedBeforeIt() = runTest {
        val rig = Rig(this)
        rig.fades.run(ManualChange.TRACK) { rig.done += "next" }
        advanceTimeBy(50L)
        rig.fades.flush()
        rig.done += "pause"
        assertEquals(listOf("next", "pause"), rig.done)
        // No arrival: the command that took over owns the sound from the gain reached.
        assertTrue(rig.fade.changePending)
        advanceTimeBy(1_000L)
        assertEquals(listOf("next", "pause"), rig.done)
    }
}
