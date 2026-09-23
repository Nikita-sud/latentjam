/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumePlaybackPersistenceTest {
    private val session = ResumePlayback("track", "ON", 19_537)

    @Test
    fun `startup leaves the durable session untouched until restoration`() = runTest {
        assertEquals(emptyList(), listOf<ResumePlayback?>(null, null).asFlow().resumePlaybackWrites().toList())
        assertEquals(listOf(session), listOf(null, null, session).asFlow().resumePlaybackWrites().toList())
    }

    @Test
    fun `empty queue clears saved session once and a new queue can be saved`() = runTest {
        val next = session.copy(trackId = "another")
        assertEquals(
            listOf(session, null, next),
            listOf(null, session, session, null, null, next).asFlow().resumePlaybackWrites().toList(),
        )
    }

    @Test
    fun `playback ticks are bucketed but pause and paused seek keep exact positions`() = runTest {
        val playing = (10_000L..19_500L step 500).map {
            session.copy(positionMs = resumePositionMs(it, isPlaying = true))
        }
        val paused = session.copy(positionMs = resumePositionMs(19_537, isPlaying = false))
        val sought = session.copy(positionMs = resumePositionMs(24_813, isPlaying = false))
        assertEquals(
            listOf(session.copy(positionMs = 10_000), paused, sought),
            (playing + paused + paused + sought).asFlow().resumePlaybackWrites().toList(),
        )
    }

    @Test
    fun `negative native position cannot persist an invalid resume offset`() {
        assertEquals(0L, resumePositionMs(-1, isPlaying = true))
        assertEquals(0L, resumePositionMs(-1, isPlaying = false))
    }
}
