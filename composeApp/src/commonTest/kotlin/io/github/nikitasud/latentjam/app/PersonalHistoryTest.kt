/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalStart
import io.github.nikitasud.latentjam.smart.personal.PersonalSurface
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How history listens reach SMART's personal evidence, and the two facts the recorder adds to them. */
class PersonalHistoryTest {

    @AfterTest
    fun reset() = PersonalSignals.reset()

    private fun event(origin: ListenOrigin?, listenedMs: Long? = 200_000L, shuffle: String? = "OFF", skipped: Boolean = false) =
        ListenEvent(TrackId("a"), 1_000_000L, 200_000L, 200_000L, completed = !skipped, skipped = skipped,
            shuffleMode = shuffle, listenedMs = listenedMs, origin = origin)

    @Test
    fun startsMapToTheEvidenceGrades() {
        assertEquals(PersonalStart.PICK, personalListen(event(ListenOrigin(ListenStart.USER_PICK))).start)
        assertEquals(PersonalStart.QUEUED, personalListen(event(ListenOrigin(ListenStart.QUEUED))).start)
        assertEquals(PersonalStart.AUTO, personalListen(event(ListenOrigin(ListenStart.AUTO_ADVANCE))).start)
        assertEquals(PersonalStart.UNKNOWN, personalListen(event(ListenOrigin(start = null))).start)
        assertEquals(PersonalStart.IMPORTED, personalListen(event(ListenOrigin(ListenStart.IMPORTED), listenedMs = null)).start)
        // Imports written before the IMPORTED start: no origin, no listen time, no shuffle mode.
        assertEquals(PersonalStart.IMPORTED, personalListen(event(null, listenedMs = null, shuffle = null)).start)
        // A legacy listen of this device (it always logged its shuffle mode) is not an import.
        assertEquals(PersonalStart.UNKNOWN, personalListen(event(null, listenedMs = null, shuffle = "OFF")).start)
    }

    @Test
    fun originFieldsReachTheEvidence() {
        val listen = personalListen(event(ListenOrigin(ListenStart.AUTO_ADVANCE, smartPlanPosition = 3, parentId = "album:x",
            pickSurface = PickSurface.SEARCH, personalChanged = true)))
        assertTrue(listen.smartPlaced)
        assertTrue(listen.personalChanged)
        assertTrue(listen.albumParent)
        assertTrue(listen.shuffleOff)
        assertEquals(PersonalSurface.SEARCH, listen.surface)
    }

    @Test
    fun aMarkedPickGetsItsSurfaceAndOnlyAPick() {
        PersonalSignals.pick(PickSurface.SEARCH, atMs = 999_000L)
        assertEquals(PickSurface.SEARCH, PersonalSignals.annotate(event(ListenOrigin(ListenStart.USER_PICK))).origin?.pickSurface)
        assertNull(PersonalSignals.annotate(event(ListenOrigin(ListenStart.AUTO_ADVANCE))).origin?.pickSurface)
        // A start long after the mark is another pick.
        PersonalSignals.pick(PickSurface.SEARCH, atMs = 900_000L)
        assertNull(PersonalSignals.annotate(event(ListenOrigin(ListenStart.USER_PICK))).origin?.pickSurface)
    }

    @Test
    fun plannedRowsCarryThePersonalFlag() {
        PersonalSignals.planned(listOf(TrackId("a"), TrackId("b")), changed = setOf(TrackId("a")))
        assertEquals(true, PersonalSignals.annotate(event(ListenOrigin(ListenStart.AUTO_ADVANCE, smartPlanPosition = 1))).origin?.personalChanged)
        PersonalSignals.planned(listOf(TrackId("a")), changed = emptySet())
        assertEquals(false, PersonalSignals.annotate(event(ListenOrigin(ListenStart.AUTO_ADVANCE, smartPlanPosition = 1))).origin?.personalChanged)
        // Not a SMART row: no flag.
        assertNull(PersonalSignals.annotate(event(ListenOrigin(ListenStart.AUTO_ADVANCE))).origin?.personalChanged)
        assertFalse(PersonalSignals.annotate(event(null)).origin != null)
    }
}
