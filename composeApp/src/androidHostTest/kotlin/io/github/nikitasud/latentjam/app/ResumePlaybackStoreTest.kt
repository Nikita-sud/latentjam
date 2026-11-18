/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ResumePlaybackStoreTest {
    private val settings = MemoryPreferences("theme_mode" to "DARK")
    private val session = MemoryPreferences()
    private val position = MemoryPreferences()

    private fun store() = ResumePlaybackStore(settings.value, session.value, position.value)

    private val playing = ResumePlayback(
        trackId = "7",
        shuffleMode = "SMART",
        positionMs = 40_000,
        sourceKind = "COLLECTION",
        sourceName = "Road, trip",
        sourceReference = "pl-1",
        queueTrackIds = listOf("5", "7", "9"),
        queueIndex = 1,
        sourceQueueTrackIds = listOf("5", "7"),
        sourceQueuePersisted = true,
        smartContinuationIds = setOf("9"),
        smartPlanPositions = mapOf("9" to 1),
    )

    @Test
    fun positionTicksWriteOnlyTheSmallPositionFile() {
        val store = store()
        store.write(null, playing)
        val settingsWrites = settings.writes
        val sessionWrites = session.writes
        val positionWrites = position.writes

        var previous = playing
        for (seconds in 5..30 step 10) {
            val next = playing.copy(positionMs = seconds * 1_000L)
            store.write(previous, next)
            previous = next
        }

        assertEquals(settingsWrites, settings.writes, "the settings file is not written for a position")
        assertEquals(sessionWrites, session.writes, "nor is the queue")
        assertEquals(positionWrites + 3, position.writes)
        assertEquals(setOf("resume_serial", "resume_position_ms"), position.values.keys)
        assertEquals(previous, store().read())
    }

    @Test
    fun aSessionSavedByAnOlderVersionIsKeptAndLeavesTheSettingsFileAtTheFirstWrite() {
        // What 0.9.5 kept in app_settings.xml.
        settings.values += mapOf(
            "resume_track_id" to "7",
            "resume_shuffle_mode" to "SMART",
            "resume_position_ms" to 40_000L,
            "resume_source_kind" to "COLLECTION",
            "resume_source_name" to "Road, trip",
            "resume_source_reference" to "pl-1",
            "resume_queue_state_v2" to encodeResumeQueueState(
                ResumeQueueState(
                    queueTrackIds = playing.queueTrackIds,
                    sourceQueueTrackIds = playing.sourceQueueTrackIds,
                    queueIndex = playing.queueIndex,
                    smartContinuationIds = playing.smartContinuationIds,
                    smartPlanPositions = playing.smartPlanPositions,
                ),
            ),
        )
        val store = store()
        assertEquals(playing, store.read())
        assertEquals(0, settings.writes + session.writes + position.writes, "reading moves nothing")

        // The first write is a position tick of the same session: it still moves the whole session.
        val next = playing.copy(positionMs = 50_000)
        store.write(playing, next)

        assertEquals(mapOf<String, Any>("theme_mode" to "DARK"), settings.values)
        assertEquals(1, settings.writes)
        assertEquals(next, store().read())
        // From then on a tick is the position file alone.
        store.write(next, next.copy(positionMs = 60_000))
        assertEquals(1, settings.writes)
        assertEquals(1, session.writes)
    }

    @Test
    fun aNewTrackBesideThePositionOfTheOneBeforeStartsAtItsBeginning() {
        val store = store()
        store.write(null, playing)
        // The process ended between the two files: the session is the next track's, the position is not.
        val staleSerial = position.values.toMap()
        store.write(playing, playing.copy(trackId = "9", queueIndex = 2, positionMs = 0))
        position.values.clear()
        position.values += staleSerial

        assertEquals(playing.copy(trackId = "9", queueIndex = 2, positionMs = 0), store().read())
    }

    @Test
    fun anEmptiedQueueErasesTheSessionAndALegacyOneWithIt() {
        settings.values += mapOf("resume_track_id" to "7", "resume_shuffle_mode" to "ON")
        val store = store()
        assertEquals(ResumePlayback("7", "ON", 0), store.read())

        store.write(store.read(), null)

        assertNull(store().read())
        assertEquals(mapOf<String, Any>("theme_mode" to "DARK"), settings.values)
        assertTrue(session.values.isEmpty() && position.values.isEmpty())
    }

    @Test
    fun aSessionWithoutAQueueOrASourceReplacesOneThatHadThem() {
        val store = store()
        store.write(null, playing)
        val bare = ResumePlayback("11", "OFF", 1_000)
        store.write(playing, bare)
        assertEquals(bare, store().read())
    }

    /** Implements only the preferences operations the store uses; no Android runtime needed. */
    private class MemoryPreferences(vararg initial: Pair<String, Any>) {
        val values = initial.toMap().toMutableMap()
        var writes = 0
        val value = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0])
                "getString" -> values[args!![0]]?.let { it as String } ?: args[1]
                "getLong" -> values[args!![0]]?.let { it as Long } ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableListOf<(MutableMap<String, Any>) -> Unit>()
            var clear = false
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putLong" -> { val key = args!![0] as String; val v = args[1]; pending += { it[key] = v }; proxy }
                    "remove" -> { val key = args!![0] as String; pending += { it.remove(key) }; proxy }
                    "clear" -> { clear = true; proxy }
                    // As SharedPreferences does it: a clear in an editor comes before that editor's own puts.
                    "apply" -> { if (clear) values.clear(); pending.forEach { it(values) }; writes++; null }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
