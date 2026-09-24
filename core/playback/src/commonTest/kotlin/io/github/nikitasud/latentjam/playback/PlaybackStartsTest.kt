/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlaybackStartsTest {

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")

    @Test
    fun `an announced start labels the track it named`() {
        val ledger = PlaybackStartLedger()
        ledger.announce(StartCause.USER_PICK, target = a)

        assertEquals(PlaybackStart(1, a, StartCause.USER_PICK), ledger.begin(a, observed = null))
    }

    @Test
    fun `a different track falls back to what the player observed`() {
        val ledger = PlaybackStartLedger()
        // Next was pressed for B, but the current track ended and A began first.
        ledger.announce(StartCause.SKIP_NEXT, target = b)

        assertEquals(StartCause.AUTO_ADVANCE, ledger.begin(a, observed = StartCause.AUTO_ADVANCE).cause)
    }

    @Test
    fun `an announcement that did not come true cannot label a later start`() {
        val ledger = PlaybackStartLedger()
        ledger.announce(StartCause.SKIP_PREVIOUS, target = b)
        ledger.begin(a, observed = StartCause.AUTO_ADVANCE)

        assertNull(ledger.begin(b, observed = null).cause)
    }

    @Test
    fun `every start is newer than the last even when the same track repeats`() {
        val ledger = PlaybackStartLedger()
        val first = ledger.begin(a, StartCause.USER_PICK)
        val repeat = ledger.begin(a, StartCause.REPEAT)

        assertTrue(repeat.sequence > first.sequence)
        assertEquals(repeat, ledger.current)
    }

    @Test
    fun `a playlist change around the same playing track continues its play`() {
        val ledger = PlaybackStartLedger()
        val picked = ledger.begin(a, StartCause.USER_PICK)
        // A generated cover replaced the current row's metadata; nothing started again.
        ledger.playlistChanged(a)

        assertEquals(picked, ledger.current)
    }

    @Test
    fun `a playlist change a command announced is a new play`() {
        val ledger = PlaybackStartLedger()
        ledger.begin(a, StartCause.AUTO_ADVANCE)
        // The listener tapped the track that was already playing.
        ledger.announce(StartCause.USER_PICK, target = a)
        ledger.playlistChanged(a)

        assertEquals(PlaybackStart(2, a, StartCause.USER_PICK), ledger.current)
    }

    @Test
    fun `a playlist change to another track is a new play nobody explained`() {
        val ledger = PlaybackStartLedger()
        ledger.begin(a, StartCause.USER_PICK)
        // The playing row was removed and the next one took its place.
        ledger.playlistChanged(b)

        assertEquals(PlaybackStart(2, b, cause = null), ledger.current)
    }

    @Test
    fun `an unannounced jump reads as the skip it matches and anywhere else as a direct pick`() {
        assertEquals(StartCause.SKIP_NEXT, seekStartCause(toIndex = 4, nextIndex = 4, previousIndex = 2))
        assertEquals(StartCause.SKIP_PREVIOUS, seekStartCause(toIndex = 2, nextIndex = 4, previousIndex = 2))
        assertEquals(StartCause.USER_PICK, seekStartCause(toIndex = 9, nextIndex = 4, previousIndex = 2))
        // The last row with repeat off has no successor; the first row is then a direct choice.
        assertEquals(StartCause.USER_PICK, seekStartCause(toIndex = 0, nextIndex = null, previousIndex = 5))
    }

    @Test
    fun `a queue set from outside the app starts on the row its controller asked for`() {
        assertEquals(b, externalQueueStart(listOf(a, b, c), startIndex = 1, shuffled = false))
        // A given row wins over shuffle: the player starts there, whatever order follows.
        assertEquals(b, externalQueueStart(listOf(a, b, c), startIndex = 1, shuffled = true))
    }

    @Test
    fun `a queue set from outside without a start row begins on its first`() {
        assertEquals(a, externalQueueStart(listOf(a, b), startIndex = null, shuffled = false))
        // One row comes first in any order.
        assertEquals(a, externalQueueStart(listOf(a), startIndex = null, shuffled = true))
    }

    @Test
    fun `a shuffled queue set from outside without a start row names no track`() {
        // The player draws its first row at random. A guess at row 0 could outlive the change and
        // label a later start of that track as a pick.
        assertNull(externalQueueStart(listOf(a, b), startIndex = null, shuffled = true))
    }

    @Test
    fun `a queue set from outside that cannot start names no track`() {
        // The row asked for did not resolve, and the player rejects a start past its end.
        assertNull(externalQueueStart(listOf(a), startIndex = 1, shuffled = false))
        assertNull(externalQueueStart(emptyList<TrackId>(), startIndex = null, shuffled = false))
    }

    @Test
    fun `an appended recommendation keeps its plan position and a continuation has none`() {
        val positions = mutableMapOf<TrackId, Int>()
        recordSmartPlanPosition(positions, a, SmartChoice(TrackDescriptor(a), planPosition = 2))
        assertEquals(mapOf(a to 2), positions)

        // Later SMART abstained and A came back as a labelled continuation.
        recordSmartPlanPosition(positions, a, choice = null)
        assertEquals(emptyMap(), positions)
    }

    @Test
    fun `a track queued by hand loses the plan position an earlier SMART row left`() {
        val positions = mutableMapOf(a to 3, b to 4)
        // SMART played A from slot 3 earlier; the listener now queues A again with Play next.
        releaseSmartPlanPosition(positions, a)

        assertEquals(mapOf(b to 4), positions)
    }

    @Test
    fun `a row that left the queue takes its plan position with it`() {
        val snapshot = PlaybackPlanPositionSnapshot()
        val positions = mutableMapOf(a to 1, b to 2)

        assertEquals(mapOf(a to 1), snapshot.get(listOf(TrackDescriptor(a)), positions))
        // Pruned at the source too: B queued again by hand later is not SMART's pick.
        assertEquals(mapOf(a to 1), positions)
    }
}
