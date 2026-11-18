/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileOutputRecoveryTest {
    private val speaker = OutputPort("Speaker", builtIn = true)
    private val headphones = OutputPort("Wired Headphones", builtIn = false)
    private val airPods = OutputPort("AA-BB-tacl", builtIn = false)
    private val carKit = OutputPort("CC-DD-tacl", builtIn = false)

    @Test
    fun `headphones pulled out are an output lost`() {
        assertTrue(privateOutputLost(before = setOf(headphones), now = setOf(speaker)))
        assertTrue(privateOutputLost(before = setOf(airPods), now = setOf(speaker)))
        // Falling over to another device is still the one that was playing going away.
        assertTrue(privateOutputLost(before = setOf(airPods), now = setOf(carKit)))
        assertTrue(privateOutputLost(before = setOf(headphones), now = emptySet()))
    }

    @Test
    fun `a device joining or the same route in another format is no output lost`() {
        assertFalse(privateOutputLost(before = setOf(speaker), now = setOf(airPods)))
        assertFalse(privateOutputLost(before = setOf(speaker), now = setOf(headphones)))
        assertFalse(privateOutputLost(before = setOf(headphones), now = setOf(headphones)))
        assertFalse(privateOutputLost(before = setOf(speaker), now = setOf(speaker)))
        // A route never noted loses nothing that is known.
        assertFalse(privateOutputLost(before = emptySet(), now = setOf(speaker)))
        assertFalse(privateOutputLost(before = emptySet(), now = emptySet()))
    }

    @Test
    fun `a graph the system stopped under a playing track is restarted not reported paused`() {
        assertEquals(
            FileOutputCheck.RECOVER,
            fileOutputCheck(playing = true, nodePlaying = false, engineRunning = false, recoveryPending = false, recoveryAllowed = true),
        )
        // The node may go on saying it plays on a stopped engine.
        assertEquals(
            FileOutputCheck.RECOVER,
            fileOutputCheck(playing = true, nodePlaying = true, engineRunning = false, recoveryPending = false, recoveryAllowed = true),
        )
    }

    @Test
    fun `a recovery under way is left to finish and one tried too often ends in a pause`() {
        assertEquals(
            FileOutputCheck.NOTHING,
            fileOutputCheck(playing = true, nodePlaying = false, engineRunning = false, recoveryPending = true, recoveryAllowed = true),
        )
        assertEquals(
            FileOutputCheck.PAUSE,
            fileOutputCheck(playing = true, nodePlaying = false, engineRunning = false, recoveryPending = false, recoveryAllowed = false),
        )
    }

    @Test
    fun `a sound player and a paused one need nothing and a node silent on a running engine is a pause`() {
        assertEquals(
            FileOutputCheck.NOTHING,
            fileOutputCheck(playing = true, nodePlaying = true, engineRunning = true, recoveryPending = false, recoveryAllowed = true),
        )
        for (node in listOf(true, false)) for (engine in listOf(true, false)) {
            assertEquals(
                FileOutputCheck.NOTHING,
                fileOutputCheck(playing = false, nodePlaying = node, engineRunning = engine, recoveryPending = false, recoveryAllowed = true),
            )
        }
        assertEquals(
            FileOutputCheck.PAUSE,
            fileOutputCheck(playing = true, nodePlaying = false, engineRunning = true, recoveryPending = false, recoveryAllowed = true),
        )
    }

    private fun resume(
        playing: Boolean = true,
        sameItem: Boolean = true,
        fileBackend: Boolean = true,
        interrupted: Boolean = false,
        otherAudioPlaying: Boolean = false,
        outputReady: Boolean = true,
        privateOutputLost: Boolean = false,
    ) = resumeAfterOutputChange(playing, sameItem, fileBackend, interrupted, otherAudioPlaying, outputReady, privateOutputLost)

    @Test
    fun `the track cued again starts again when nothing speaks against it`() {
        assertEquals(OutputResume.RESUME, resume())
    }

    @Test
    fun `a pause that came meanwhile or a newer item is left alone`() {
        assertEquals(OutputResume.LEAVE, resume(playing = false))
        assertEquals(OutputResume.LEAVE, resume(sameItem = false))
        assertEquals(OutputResume.LEAVE, resume(fileBackend = false))
        // Already paused by the device going away: nothing is added to it.
        assertEquals(OutputResume.LEAVE, resume(playing = false, privateOutputLost = true))
        assertEquals(OutputResume.LEAVE, resume(playing = false, interrupted = true))
    }

    @Test
    fun `a device that went away an interruption another apps sound or a dead output pause instead`() {
        assertEquals(OutputResume.PAUSE, resume(privateOutputLost = true))
        assertEquals(OutputResume.PAUSE, resume(interrupted = true))
        assertEquals(OutputResume.PAUSE, resume(otherAudioPlaying = true))
        assertEquals(OutputResume.PAUSE, resume(outputReady = false))
    }

    @Test
    fun `an output that keeps stopping is restarted three times in ten seconds and no more`() {
        assertTrue(outputRecoveryAllowed(emptyList(), nowMs = 50_000L))
        assertTrue(outputRecoveryAllowed(listOf(49_000L, 49_500L), nowMs = 50_000L))
        assertFalse(outputRecoveryAllowed(listOf(48_000L, 49_000L, 49_500L), nowMs = 50_000L))
        // Old attempts no longer count, nor do times from before a clock that restarted.
        assertTrue(outputRecoveryAllowed(listOf(10_000L, 39_999L, 49_500L), nowMs = 50_000L))
        assertTrue(outputRecoveryAllowed(listOf(90_000L, 91_000L, 92_000L), nowMs = 50_000L))
    }
}
