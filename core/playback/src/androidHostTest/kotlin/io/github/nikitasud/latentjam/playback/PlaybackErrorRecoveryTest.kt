/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlaybackErrorRecoveryTest {

    private val card = "1234-abcd"
    private val phone = "external_primary"

    /** Android 10+: MediaStore names every mounted volume. */
    private fun cardOut() = StoragePresence(mounted = setOf(phone), unmounted = emptySet(), mountedIsComplete = true)

    @Test
    fun aChainThatFoundNothingReadablePutsThePlayheadBackWhereItBegan() {
        // Ten rows, every one failed in turn from row 3 at 2:30; the chain ended on the last row.
        val ids = List(10) { "row-$it" }
        val origin = RecoveryOrigin("row-3", index = 3, positionMs = 150_000, cause = StartCause.USER_PICK)

        // Walks the queue exactly as the controller does: each failure adds its id and asks for the next row.
        val failed = mutableSetOf<String>()
        var at = 3
        while (true) {
            failed += ids[at]
            at = nextPlaybackErrorRecoveryIndex(ids, at, repeatAll = true, traversalOrder = IntArray(10) { it }, failedIds = failed)
                ?: break
        }

        assertEquals(10, failed.size)
        assertEquals(2, at)
        assertEquals(3, recoveryReturnRow(ids, failedIndex = at, origin = origin))
    }

    @Test
    fun aChainThatNeverLeftItsRowKeepsThePlayersOwnPosition() {
        val origin = RecoveryOrigin("only", index = 0, positionMs = 90_000, cause = null)

        assertNull(recoveryReturnRow(listOf("only"), failedIndex = 0, origin = origin))
        assertNull(recoveryReturnRow(listOf("a", "b"), failedIndex = 1, origin = null))
    }

    @Test
    fun theOriginIsFoundAgainByItsIdWhenTheQueueChangedUnderTheChain() {
        // A row before it was removed, and the same track is queued twice: the nearer row is the one.
        val origin = RecoveryOrigin("b", index = 2, positionMs = 0, cause = null)

        assertEquals(1, recoveryReturnRow(listOf("a", "b", "c", "b"), failedIndex = 3, origin = origin))
        assertNull(recoveryReturnRow(listOf("a", "c"), failedIndex = 1, origin = origin))
    }

    @Test
    fun aCardTakenOutEndsTheChainAtTheFirstFailureInsteadOfWalkingTheQueue() {
        val ids = List(10_000) { "card-$it" }
        val missing = rowsOnMissingStorage(ids, failedId = "card-40", volumeOf = { card }, presence = cardOut())

        assertEquals(10_000, missing.size)
        assertNull(
            nextPlaybackErrorRecoveryIndex(
                mediaIds = ids,
                failedIndex = 40,
                repeatAll = true,
                traversalOrder = IntArray(ids.size) { it },
                failedIds = setOf("card-40") + missing,
            ),
        )
    }

    @Test
    fun rowsOnStorageThatIsThereStillPlayAfterACardIsTakenOut() {
        // Card rows around two rows from the phone's own storage.
        val volumes = mapOf("c1" to card, "c2" to card, "p1" to phone, "c3" to card, "p2" to phone)
        val ids = volumes.keys.toList()
        val missing = rowsOnMissingStorage(ids, failedId = "c1", volumeOf = volumes::get, presence = cardOut())

        assertEquals(setOf("c1", "c2", "c3"), missing)
        assertEquals(
            2,
            nextPlaybackErrorRecoveryIndex(ids, 0, repeatAll = false, traversalOrder = IntArray(5) { it }, failedIds = setOf("c1") + missing),
        )
    }

    @Test
    fun aDamagedFileOnStorageThatIsThereLeavesEveryOtherRowToBeTried() {
        val present = StoragePresence(mounted = setOf(phone, card), unmounted = emptySet(), mountedIsComplete = true)

        assertEquals(emptySet(), rowsOnMissingStorage(listOf("a", "b"), "a", volumeOf = { card }, presence = present))
        // The system cannot say, or the track names no volume: nothing is taken for gone.
        assertEquals(emptySet(), rowsOnMissingStorage(listOf("a", "b"), "a", volumeOf = { card }, presence = null))
        assertEquals(emptySet(), rowsOnMissingStorage(listOf("a", "b"), "a", volumeOf = { null }, presence = cardOut()))
    }

    @Test
    fun beforeAndroid10OnlyAVolumeListedAsNotMountedIsGone() {
        // The volume's name comes from the file's path there and need not be the system's own.
        val old = StoragePresence(mounted = setOf(phone), unmounted = setOf(card), mountedIsComplete = false)

        assertTrue(old.isGone(card))
        assertTrue(old.isGone("1234-ABCD"))
        assertFalse(old.isGone("sdcard1"))
        assertFalse(old.isGone(phone))
        assertFalse(old.isGone(null))
    }
}
