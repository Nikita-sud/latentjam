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

internal class SmartQueueRowsTest {

    @Test
    fun aHandQueuedRowHiddenFromSmartStaysWhenTheLibraryIsSynced() {
        // current, a SMART pick, a track hidden from SMART that the listener queued by hand, a SMART pick now hidden.
        val ids = listOf("current", "pick", "hand-queued-hidden", "pick-now-hidden")
        val eligible = setOf("current", "pick")
        val smartOwned = setOf("pick", "pick-now-hidden")

        assertEquals(
            listOf(3),
            smartRowsToDrop(ids, keepThrough = 0, eligible = { it in eligible }, smartOwned = { it in smartOwned }),
        )
    }

    @Test
    fun whatAlreadyPlayedAndTheCurrentRowAreNeverDropped() {
        val ids = listOf("played-hidden", "current-hidden", "pick-hidden", "pick")
        val smartOwned = ids.toSet()

        assertEquals(
            listOf(2),
            smartRowsToDrop(ids, keepThrough = 1, eligible = { it == "pick" }, smartOwned = { it in smartOwned }),
        )
    }

    @Test
    fun droppedRowsComeLastFirstSoEachIndexStaysValid() {
        val ids = listOf("current", "a", "b", "c", "d")

        assertEquals(
            listOf(4, 2, 1),
            smartRowsToDrop(ids, keepThrough = 0, eligible = { it == "c" || it == "current" }, smartOwned = { true }),
        )
    }

    @Test
    fun anEmptyQueueAndAQueueWithNoCurrentRowDropNothingBeforeTheFirstRow() {
        assertEquals(emptyList(), smartRowsToDrop(emptyList(), keepThrough = -1, eligible = { false }, smartOwned = { true }))
        assertEquals(
            listOf(1),
            smartRowsToDrop(listOf("seed", "pick"), keepThrough = 0, eligible = { false }, smartOwned = { true }),
        )
    }

    @Test
    fun everyQueuedTrackTheNewLibraryLacksKeepsItsDescriptorInAnyMode() {
        // A queue shuffled or in order: the playing row and a later one are known only from the library before the sync.
        val before = mapOf("playing" to "Playing", "later" to "Later", "kept" to "Kept (old tags)")
        val library = setOf("kept", "other")

        assertEquals(
            listOf("Playing", "Later"),
            descriptorsLeavingLibrary(
                queueIds = listOf("kept", "playing", "later", "playing"),
                inLibrary = { it in library },
                known = before::get,
            ),
        )
    }

    @Test
    fun aQueueTheLibraryHoldsInFullKeepsNothingAside() {
        assertEquals(
            emptyList(),
            descriptorsLeavingLibrary(listOf("a", "b"), inLibrary = { true }, known = { error("never asked") }),
        )
        // A row nothing ever named has no descriptor to keep.
        assertEquals(emptyList(), descriptorsLeavingLibrary(listOf("unknown"), inLibrary = { false }, known = { null as String? }))
    }

    private fun track(id: String) = TrackDescriptor(id = TrackId(id), title = id)

    /** The player's rows after [order] is spliced around its current row, as the controller applies it. */
    private fun splicedIds(order: PlaybackQueueOrder): List<String> {
        val splice = checkNotNull(playbackQueueSplice(order))
        return (splice.beforeCurrent + splice.current + splice.afterCurrent).map { it.id.value }
    }

    @Test
    fun shuffleChosenFromSmartPlaysTheSourceListAroundTheCurrentTrack() {
        // SMART queue [a, x, y] started from the list [a, b, c]; x, a SMART pick, is playing.
        val source = listOf(track("a"), track("b"), track("c"))

        val order = checkNotNull(shuffleSourceLeavingSmart(ShuffleMode.SMART, source, current = track("x")))

        assertEquals(listOf("x", "a", "b", "c"), splicedIds(order))
        assertEquals("x", order.tracks[order.currentIndex].id.value)
    }

    @Test
    fun aCurrentTrackOfTheSourceStaysWhereTheSourceHasIt() {
        val source = listOf(track("a"), track("b"), track("c"))

        val order = checkNotNull(shuffleSourceLeavingSmart(ShuffleMode.SMART, source, current = track("b")))

        assertEquals(listOf("a", "b", "c"), splicedIds(order))
        assertEquals(1, order.currentIndex)
    }

    @Test
    fun onlyAQueueLeavingSmartWithASourceIsExchanged() {
        val source = listOf(track("a"), track("b"))
        assertNull(shuffleSourceLeavingSmart(ShuffleMode.OFF, source, current = track("a")))
        assertNull(shuffleSourceLeavingSmart(ShuffleMode.ON, source, current = track("a")))
        assertNull(shuffleSourceLeavingSmart(ShuffleMode.SMART, source, current = null))
        // A session restored without its source keeps the rows it has rather than shrinking to one.
        assertNull(shuffleSourceLeavingSmart(ShuffleMode.SMART, emptyList(), current = track("x")))
    }
}
