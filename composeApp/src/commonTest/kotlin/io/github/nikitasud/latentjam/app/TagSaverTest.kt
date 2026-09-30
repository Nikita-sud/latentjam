/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class TagSaverTest {

    private fun track(key: String) = TrackDescriptor(TrackId(key), audioUri = key)

    private fun TestScope.saverFor(
        backend: TestTagWriteBackend,
        results: MutableList<TagSaveResult>,
        mine: SnapshotStateList<Long> = mutableStateListOf(),
    ): TagSaver {
        val coordinator = TagWriteCoordinator(
            backend,
            CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler)),
            StandardTestDispatcher(testScheduler),
            checkpoints = false,
        )
        val access = TagWriteAccess(coordinator, readOnlyIsMusicLibrary = false) { it.audioUri }
        return TagSaver(access, mine) { results += it }
    }

    private fun TagSaver.deliverCompleted() {
        access.coordinator.completed.value?.let { access.coordinator.deliver(it.id) }
    }

    @Test
    fun aSaveReportsEveryFileAndForgetsItsRequest() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        runCurrent()
        saver.start(listOf(track("a"), track("b")), TagEdits(title = "New"))
        assertTrue(saver.busy)
        runCurrent()
        saver.deliverCompleted()
        assertEquals(2, results.single().savedCount)
        assertTrue(results.single().notChanged.isEmpty())
        assertFalse(saver.busy)
    }

    @Test
    fun emptyEditsFinishAtOnceWithoutQueueing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        saver.start(listOf(track("a")), TagEdits())
        assertEquals(FileWriteStatus.UNCHANGED, results.single().entries.single().status)
        assertFalse(saver.busy)
    }

    @Test
    fun aTrackWithNoWayToWriteItIsReportedReadOnlyWithoutQueueing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        saver.start(listOf(TrackDescriptor(TrackId("x"))), TagEdits(title = "New"))
        val entry = results.single().entries.single()
        assertEquals(FileWriteStatus.READ_ONLY, entry.status)
        assertEquals(TagProblem.READ_ONLY_STORAGE, entry.problem)
    }

    @Test
    fun aReportDeliveredWhileTheEditorWasAwayIsClaimedWhenItReturns() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val results = ArrayList<TagSaveResult>()
        val mine = mutableStateListOf<Long>()
        val saver = saverFor(backend, results, mine)
        runCurrent()
        saver.start(listOf(track("a")), TagEdits(title = "New"))
        // The sheet is being recreated: nobody listens when the report is handed out.
        saver.detach()
        runCurrent()
        saver.deliverCompleted()
        assertTrue(results.isEmpty())
        // The recreated sheet: the same saved request ids, a fresh saver over the same coordinator.
        val returned = TagSaver(saver.access, mine) { results += it }
        returned.attach()
        returned.claim(saver.access.coordinator.unclaimed.value)
        assertEquals(1, results.single().savedCount)
        assertTrue(saver.access.coordinator.unclaimed.value.isEmpty())
        assertFalse(returned.busy)
    }

    @Test
    fun anotherEditorsReportIsNotClaimed() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val results = ArrayList<TagSaveResult>()
        val saver = saverFor(backend, results)
        runCurrent()
        assertNotNull(saver.access.coordinator.enqueue(listOf("a"), TagEdits(title = "Elsewhere")))
        runCurrent()
        saver.deliverCompleted()
        saver.claim(saver.access.coordinator.unclaimed.value)
        assertTrue(results.isEmpty())
        assertEquals(1, saver.access.coordinator.unclaimed.value.size)
    }

    @Test
    fun aResultSurvivesBeingSavedAsStrings() {
        val result = TagSaveResult(
            listOf(
                TagSaveEntry("a", FileWriteStatus.SAVED, newLength = 10),
                TagSaveEntry("b\tc", FileWriteStatus.REFUSED, TagRefusal.MP4_DRM_PROTECTED, problem = TagProblem.PROTECTED),
            ),
        )
        assertEquals(result, tagSaveResultOf(result.toSaveable()))
        assertNull(tagSaveResultOf(listOf("a", "SAVED")))
    }

    @Test
    fun everyFailureHasAProblemAndEverySuccessNone() {
        val saved = setOf(FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED, FileWriteStatus.RECOVERED)
        for (status in FileWriteStatus.entries) {
            val problem = tagProblemOf(status, null, readOnlyIsMusicLibrary = false)
            if (status in saved) assertNull(problem, "$status") else assertNotNull(problem, "$status")
        }
        assertEquals(TagProblem.MUSIC_LIBRARY, tagProblemOf(FileWriteStatus.READ_ONLY, null, readOnlyIsMusicLibrary = true))
        assertEquals(TagProblem.READ_ONLY_STORAGE, tagProblemOf(FileWriteStatus.READ_ONLY, null, readOnlyIsMusicLibrary = false))
        assertEquals(TagProblem.PROTECTED, tagProblemOf(FileWriteStatus.REFUSED, TagRefusal.MP4_DRM_PROTECTED, false))
        assertEquals(TagProblem.UNUSUAL_LAYOUT, tagProblemOf(FileWriteStatus.REFUSED, null, false))
    }

    @Test
    fun aDismissedPromptIsACancelNotAResult() {
        val cancelled = TagSaveResult(listOf(TagSaveEntry("a", FileWriteStatus.CANCELLED, problem = TagProblem.CANCELLED)))
        assertTrue(cancelled.cancelled)
        val mixed = cancelled.copy(entries = cancelled.entries + TagSaveEntry("b", FileWriteStatus.SAVED))
        assertFalse(mixed.cancelled)
    }
}
