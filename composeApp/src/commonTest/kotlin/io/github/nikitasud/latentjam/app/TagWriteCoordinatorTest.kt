/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.library.tags.write.TargetFile
import io.github.nikitasud.latentjam.library.tags.write.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class TagWriteCoordinatorTest {

    private class Harness(
        val backend: TestTagWriteBackend,
        val test: TestScope,
        restored: List<String>? = null,
        checkpoints: Boolean = true,
    ) {
        var saved: List<String> = restored.orEmpty()

        /** A checkpoint write that fails, as a full disk or a torn-down owner can make it. */
        var failSave: (List<String>) -> Boolean = { false }

        // A child of the test's background scope, so a recreation cancels this owner and not the test.
        val scope = CoroutineScope(
            test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]) +
                StandardTestDispatcher(test.testScheduler),
        )
        val coordinator = TagWriteCoordinator(backend, scope, StandardTestDispatcher(test.testScheduler), restored, {
            if (failSave(it)) throw IllegalStateException("the checkpoint could not be written")
            saved = it
        }, checkpoints = checkpoints)
        val reports = ArrayList<TagWriteReport>()

        fun enqueue(keys: List<String>, edits: TagEdits): Long {
            val id = assertNotNull(coordinator.enqueue(keys, edits))
            coordinator.listen(id) { reports += it }
            return id
        }

        /** What the platform host does: launch the prompt, let the "system" grant it, answer. */
        fun approvePrompt() {
            val prompt = assertNotNull(coordinator.prompt.value)
            assertTrue(coordinator.promptLaunched(prompt.requestId))
            when {
                prompt.consent == null -> backend.permission = true
                prompt.consent!!.startsWith("batch:") -> backend.granted += backend.consentBatches.last()
                else -> backend.granted += prompt.consent!!.removePrefix("file:")
            }
            coordinator.answer(WriteAnswer.APPROVED)
        }

        fun deliver() {
            coordinator.completed.value?.let { coordinator.deliver(it.id) }
        }

        fun recreate(): Harness {
            scope.cancel()
            return Harness(backend, test, saved)
        }
    }

    /**
     * What a process that died mid-patch leaves: the patch is in [key], and the roll-back could not
     * finish either (the same handle's force() throws), so the journal holds an open record.
     */
    private fun interruptSave(backend: TestTagWriteBackend, key: String) {
        val throwing = object : TargetFile by backend.files.track(key) {
            override fun force() = throw IllegalStateException("process died")
        }
        val result = DurableWriter(backend.files.directory, { "old" }).write(key, throwing, TagEdits(title = "Half"), null)
        assertIs<WriteResult.RecoveryPending>(result)
        assertEquals(JournalState.ROLLING_BACK, backend.recovery.pending().single { it.target == key }.state)
        assertFalse(backend.files.bytes(key).contentEquals(testMp3()), "the interrupted patch is in the file")
    }

    @Test
    fun anInPlaceSaveMakesWhatTheTestsCompareAgainst() {
        val file = MemoryWriteFiles().apply { put("a", testMp3()) }
        val result = DurableWriter(file.directory, { "w" }).write("a", file.track("a"), TagEdits(title = "New"), null)
        assertEquals(WriteResult.Saved(testMp3().size.toLong(), rewritten = false), result)
        assertContentEquals(testMp3("New"), file.bytes("a"))
    }

    @Test
    fun withoutConsentEveryFileIsSavedAndTheIndexRescannedOnce() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b", "c", "d").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b", "c", "d"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(List(4) { FileWriteStatus.SAVED }, harness.reports.single().results.map { it.status })
        assertEquals(listOf(listOf("a", "b", "c", "d")), backend.rescans.map { it.sorted() })
        assertContentEquals(testMp3("New"), backend.files.bytes("c"))
    }

    @Test
    fun theSystemRequestIsSplitAt2000Files() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = List(4_500) { "k$it" }
        val file = testMp3()
        keys.forEach { backend.files.put(it, file) }
        val harness = Harness(backend, this)
        harness.enqueue(keys, TagEdits(title = "Old")) // a no-op edit: fast, and no file changes
        repeat(3) {
            runCurrent()
            harness.approvePrompt()
        }
        runCurrent()
        harness.deliver()
        assertEquals(listOf(keys.subList(0, 2_000), keys.subList(2_000, 4_000), keys.subList(4_000, 4_500)), backend.consentBatches)
        assertEquals(4_500, harness.reports.single().results.count { it.status == FileWriteStatus.UNCHANGED })
        assertEquals(emptyList(), backend.rescans)
    }

    @Test
    fun recoverableConsentAsksOncePerFile() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b"), TagEdits(title = "New"))
        runCurrent()
        harness.approvePrompt()
        runCurrent()
        harness.approvePrompt()
        runCurrent()
        harness.deliver()
        assertEquals(List(2) { FileWriteStatus.SAVED }, harness.reports.single().results.map { it.status })
    }

    @Test
    fun aDeniedStoragePermissionWritesNothing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.WRITE_PERMISSION)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        val prompt = assertNotNull(harness.coordinator.prompt.value)
        harness.coordinator.promptLaunched(prompt.requestId)
        harness.coordinator.answer(WriteAnswer.CANCELLED)
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.DENIED, harness.reports.single().results.single().status)
        // Unlike a dismissed dialog, a denial is shown: Save would otherwise do nothing, every time.
        assertEquals(TagProblem.NOT_ALLOWED, TagSaveResult.of(harness.reports.single(), readOnlyIsMusicLibrary = false).entries.single().problem)
        assertContentEquals(testMp3(), backend.files.bytes("a"))
    }

    @Test
    fun aDismissedSystemDialogWritesNothing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.coordinator.promptLaunched(harness.coordinator.prompt.value!!.requestId)
        harness.coordinator.answer(WriteAnswer.CANCELLED)
        runCurrent()
        harness.deliver()
        assertEquals(TagProblem.CANCELLED, TagSaveResult.of(harness.reports.single(), readOnlyIsMusicLibrary = false).entries.single().problem)
        assertContentEquals(testMp3(), backend.files.bytes("a"))
    }

    @Test
    fun aDialogLostWithTheProcessIsAskedAgain() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        first.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        first.coordinator.promptLaunched(first.coordinator.prompt.value!!.requestId)
        val restored = first.recreate()
        runCurrent()
        assertNull(restored.coordinator.prompt.value)
        restored.coordinator.onHostResumed()
        runCurrent()
        restored.approvePrompt()
        runCurrent()
        restored.coordinator.listen(restored.coordinator.completed.value!!.id) { restored.reports += it }
        restored.deliver()
        assertEquals(FileWriteStatus.SAVED, restored.reports.single().results.single().status)
        assertEquals(2, backend.consentBatches.size)
    }

    @Test
    fun aSaveInterruptedByProcessDeathIsRecoveredThenRedone() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.SAVED, harness.reports.single().results.single().status)
        assertContentEquals(testMp3("New"), backend.files.bytes("a"))
        assertTrue(backend.recovery.pending().isEmpty())
    }

    @Test
    fun stopLeavesTheRestUntouched() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b", "c").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b", "c"), TagEdits(title = "New"))
        runCurrent()
        harness.approvePrompt()
        runCurrent()
        harness.coordinator.stop()
        runCurrent()
        harness.deliver()
        val statuses = harness.reports.single().results.associate { it.key to it.status }
        assertEquals(FileWriteStatus.SAVED, statuses["a"])
        assertEquals(FileWriteStatus.STOPPED, statuses["b"])
        assertEquals(FileWriteStatus.STOPPED, statuses["c"])
        assertContentEquals(testMp3(), backend.files.bytes("c"))
    }

    @Test
    fun aRecoveryRequestFinishesEveryInterruptedSave() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertEquals(1, harness.coordinator.pendingRecovery.value.size)
        harness.coordinator.enqueueRecovery()
        runCurrent()
        assertEquals(listOf(listOf("a")), backend.consentBatches)
        harness.approvePrompt()
        runCurrent()
        val completed = harness.coordinator.completed.value!!
        harness.coordinator.listen(completed.id) { harness.reports += it }
        harness.deliver()
        assertEquals(TagWriteKind.RECOVER, harness.reports.single().kind)
        assertEquals(FileWriteStatus.RESTORED, harness.reports.single().results.single().status)
        assertTrue(backend.recovery.pending().isEmpty())
        assertContentEquals(testMp3(), backend.files.bytes("a"))
        // The index may have read the half-written tags; it is told the file changed back.
        assertEquals(listOf(listOf("a")), backend.rescans)
    }

    @Test
    fun theStoreIsNeverSweptWhileASaveIsInFlight() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        interruptSave(backend, "a")
        // A leftover no open record needs: only a sweep deletes it.
        backend.files.directory.create("stray.patch").close()
        val inFlightAtSweep = ArrayList<Int>()
        backend.files.onDelete = { if (it == "stray.patch") inFlightAtSweep += backend.inFlight }
        val release = CompletableDeferred<Unit>()
        backend.gate = { if (it == "b") release.await() }

        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b"), TagEdits(title = "New"))
        runCurrent()
        // "a" was recovered and saved again while the save of "b" is held in flight.
        assertContentEquals(testMp3("New"), backend.files.bytes("a"))
        assertEquals(1, backend.inFlight)
        val refresh = launch { harness.coordinator.refreshRecovery() }
        runCurrent()
        assertEquals(emptyList(), inFlightAtSweep)

        release.complete(Unit)
        runCurrent()
        refresh.join()
        // One sweep, once every file of the request was closed.
        assertEquals(listOf(0), inFlightAtSweep)
        harness.deliver()
        assertEquals(List(2) { FileWriteStatus.SAVED }, harness.reports.single().results.map { it.status })

        // Idle, a refresh sweeps too.
        backend.files.directory.create("stray.patch").close()
        harness.coordinator.refreshRecovery()
        assertEquals(listOf(0, 0), inFlightAtSweep)
        assertEquals(emptyList(), harness.coordinator.pendingRecovery.value)
    }

    @Test
    fun aNewCoverSurvivesProcessDeath() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        first.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(png, "image/png")))
        runCurrent()
        val restored = first.recreate()
        val edits = decodeTagWriteRequests(restored.saved, backend::unstash, backend::loadKeys).single().edits
        val cover = edits.cover as CoverEdit.Replace
        assertContentEquals(png, cover.bytes)
        assertEquals("image/png", cover.mime)
    }

    @Test
    fun aReportCarriesItsRequestsCover() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Remove))
        harness.enqueue(listOf("b"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        runCurrent()
        harness.deliver()
        assertEquals(listOf<CoverEdit>(CoverEdit.Remove, CoverEdit.Keep), harness.reports.map { it.cover })
    }

    @Test
    fun aNewCoverRestoredAfterProcessDeathReachesItsUnclaimedReport() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        first.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(png, "image/png")))
        runCurrent()
        val restored = first.recreate()
        runCurrent()
        restored.coordinator.onHostResumed()
        runCurrent()
        restored.approvePrompt()
        runCurrent()
        restored.deliver()
        val cover = restored.coordinator.unclaimed.value.single().cover
        assertContentEquals(png, assertIs<CoverEdit.Replace>(cover).bytes)
    }

    @Test
    fun aStopWhileTheBatchConsentIsBuiltOffersNothing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val release = CompletableDeferred<Unit>()
        backend.consentGate = { release.await() }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b"), TagEdits(title = "New"))
        runCurrent()
        harness.coordinator.stop()
        release.complete(Unit)
        runCurrent()
        assertNull(harness.coordinator.prompt.value)
        harness.deliver()
        assertEquals(List(2) { FileWriteStatus.STOPPED }, harness.reports.single().results.map { it.status })
        assertContentEquals(testMp3(), backend.files.bytes("a"))
    }

    @Test
    fun aStopWhileAFileIsOpenedOffersNoConsentForIt() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val release = CompletableDeferred<Unit>()
        backend.gate = { if (it == "a") release.await() }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b"), TagEdits(title = "New"))
        runCurrent()
        harness.coordinator.stop()
        release.complete(Unit)
        runCurrent()
        assertNull(harness.coordinator.prompt.value)
        harness.deliver()
        assertEquals(List(2) { FileWriteStatus.STOPPED }, harness.reports.single().results.map { it.status })
    }

    @Test
    fun aCheckpointOf10000FilesKeepsTheirKeysOutOfTheSavedState() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = List(10_000) { "content://media/external/audio/media/${1_000_000 + it}" }
        // No file is ever opened here: only the checkpoint is under test.
        backend.looks = false
        val harness = Harness(backend, this)
        harness.enqueue(keys, TagEdits(title = "New"))
        runCurrent()
        assertNotNull(harness.coordinator.prompt.value)
        assertTrue(harness.saved.sumOf { it.length } < 64 * 1024, "checkpoint of ${harness.saved.sumOf { it.length }} chars")
        assertEquals(keys, backend.savedKeys.values.single())
        val restored = decodeTagWriteRequests(harness.saved, backend::unstash, backend::loadKeys).single()
        assertEquals(keys, restored.keys)
        assertEquals(keys.take(2_000), restored.batch)
    }

    @Test
    fun aCheckpointRoundTripsEveryField() {
        val keys = List(20) { "k,$it:!" }
        val results = FileWriteStatus.entries.mapIndexed { i, status ->
            FileWriteResult(
                keys[19 - i],
                status,
                refusal = if (status == FileWriteStatus.REFUSED) TagRefusal.TRUNCATED else null,
                newLength = if (status == FileWriteStatus.SAVED) 1_234_567_890_123 else null,
            )
        }
        val request = TagWriteRequest(
            id = 7,
            kind = TagWriteKind.EDIT,
            keys = keys,
            edits = TagEdits(title = "T:itle", year = "", lyrics = "line\nline", cover = CoverEdit.Remove),
            stage = TagWriteStage.WAIT_BATCH,
            consented = false,
            permissionRequested = true,
            batch = keys.take(2),
            results = results,
            stopRequested = true,
            interrupted = true,
        )
        val saved = encodeTagWriteRequests(listOf(request))
        assertEquals(listOf(request), decodeTagWriteRequests(saved, { null }, { if (it == 7L) keys else null }))
        // Keys that were lost drop the request rather than guess at them.
        assertEquals(emptyList(), decodeTagWriteRequests(saved, { null }, { null }))
        assertEquals(emptyList(), decodeTagWriteRequests(saved, { null }, { keys.drop(1) }))
        // So do other keys of the same count: the key file is not this request's.
        assertEquals(emptyList(), decodeTagWriteRequests(saved, { null }, { keys.reversed() }))
    }

    @Test
    fun anEditThatKeepsOriginalDatesSurvivesACheckpoint() {
        val keys = listOf("content://a", "content://b")
        for (follows in listOf(true, false)) {
            val request = TagWriteRequest(
                id = 3,
                kind = TagWriteKind.EDIT,
                keys = keys,
                edits = TagEdits(year = "2001", originalFollowsYear = follows),
            )
            val restored = decodeTagWriteRequests(encodeTagWriteRequests(listOf(request)), { null }, { keys }).single()
            assertEquals(request, restored)
            assertEquals(follows, restored.edits.originalFollowsYear)
        }
    }

    @Test
    fun aCheckpointFromBeforeTheOriginalDateSwitchRestoresAsMovingNone() {
        val keys = listOf("content://a", "content://b")
        val request = TagWriteRequest(id = 3, kind = TagWriteKind.EDIT, keys = keys, edits = TagEdits(year = "2001"))
        val current = encodeTagWriteRequests(listOf(request))
        // Version 4 had no flag after the cover code: header (2) + seven request fields + eleven edit fields + cover.
        val flagAt = 2 + 7 + 11 + 1
        assertEquals("5", current[0])
        assertEquals("1", current[flagAt])
        val old = listOf("4") + current.subList(1, flagAt) + current.subList(flagAt + 1, current.size)
        val restored = decodeTagWriteRequests(old, { null }, { keys }).single()
        assertEquals("2001", restored.edits.year)
        assertFalse(restored.edits.originalFollowsYear)
        assertEquals(request.copy(edits = request.edits.copy(originalFollowsYear = false)), restored)
    }

    @Test
    fun aFinishedCheckpointOf10000FilesStaysSmall() {
        val keys = List(10_000) { "content://media/external/audio/media/${1_000_000 + it}" }
        val request = TagWriteRequest(
            id = 1,
            kind = TagWriteKind.EDIT,
            keys = keys,
            edits = TagEdits(title = "New"),
            stage = TagWriteStage.COMPLETE,
            results = keys.mapIndexed { i, key ->
                if (i % 100 == 0) FileWriteResult(key, FileWriteStatus.REFUSED, refusal = TagRefusal.ID3_MALFORMED_FRAMES)
                else FileWriteResult(key, FileWriteStatus.SAVED, newLength = 12_345_678_901)
            },
        )
        val saved = encodeTagWriteRequests(listOf(request))
        assertTrue(saved.sumOf { it.length } < 200_000, "checkpoint of ${saved.sumOf { it.length }} chars")
        assertEquals(listOf(request), decodeTagWriteRequests(saved, { null }, { keys }))
    }

    @Test
    fun theCheckpointOfARunningBatchIsKeptUpAsFilesFinish() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        val keys = List(10_000) { "gone-$it" }
        val harness = Harness(backend, this)
        harness.enqueue(keys, TagEdits(title = "New"))
        runCurrent()
        val completed = assertNotNull(harness.coordinator.completed.value)
        assertEquals(10_000, completed.results.count { it.status == FileWriteStatus.MISSING })
        // What was appended file by file is exactly what encoding the request afresh gives.
        assertEquals(encodeTagWriteRequests(listOf(completed)), harness.saved)
        assertTrue(harness.saved.sumOf { it.length } < 200_000)
    }

    @Test
    fun keyFilesAreReadAndWrittenOffTheCallersThread() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        runCurrent()
        val id = first.enqueue(listOf("a"), TagEdits(title = "New"))
        // Nothing written yet, and no checkpoint names the request before its keys are.
        assertEquals(emptyList(), backend.savedKeyIds())
        assertEquals(emptyList(), parseTagWriteCheckpoint(first.saved).map { it.id })
        runCurrent()
        assertEquals(listOf(id), parseTagWriteCheckpoint(first.saved).map { it.id })
        assertEquals(listOf(id), backend.savedKeyIds())
        assertNotNull(first.coordinator.prompt.value)
        val reads = backend.keyReads
        val restored = first.recreate()
        assertEquals(reads, backend.keyReads, "the constructor read no key file")
        runCurrent()
        assertEquals(reads + 1, backend.keyReads)
        assertNotNull(restored.coordinator.prompt.value)
    }

    @Test
    fun aConsentAnswerThatArrivesBeforeTheRestoreFinishesIsKept() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        first.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        first.coordinator.promptLaunched(assertNotNull(first.coordinator.prompt.value).requestId)
        backend.granted += "a"
        val restored = first.recreate()
        // The activity result is delivered before the host resumes, while the keys are still loading.
        restored.coordinator.answer(WriteAnswer.APPROVED)
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(1, backend.consentBatches.size)
        assertEquals(FileWriteStatus.SAVED, assertNotNull(restored.coordinator.completed.value).results.single().status)
        assertContentEquals(testMp3("New"), backend.files.bytes("a"))
    }

    @Test
    fun oneFilesFailureDoesNotCancelTheOthers() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b", "c").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        var failed = false
        // The first file's result cannot be checkpointed; the other two have been written by then.
        harness.failSave = { saved ->
            !failed && decodeTagWriteRequests(saved, backend::unstash, backend::loadKeys).any { it.results.isNotEmpty() }
                .also { failed = it }
        }
        harness.enqueue(listOf("a", "b", "c"), TagEdits(title = "New"))
        runCurrent()
        assertTrue(failed)
        harness.deliver()
        listOf("a", "b", "c").forEach { assertContentEquals(testMp3("New"), backend.files.bytes(it), it) }
        assertEquals(List(3) { FileWriteStatus.SAVED }, harness.reports.single().results.sortedBy { it.key }.map { it.status })
    }

    @Test
    fun keysAndCoversNoRequestNeedsArePrunedAtStart() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        val id = first.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(byteArrayOf(1), "image/png")))
        runCurrent()
        backend.savedKeys[99] = listOf("stale")
        backend.stashed[coverName(99)] = byteArrayOf(2)
        val restored = first.recreate()
        // A request queued while the restore runs keeps its keys, whatever stale files shared its id.
        val next = assertNotNull(restored.coordinator.enqueue(listOf("b"), TagEdits(title = "x")))
        backend.savedKeys[next] = listOf("stale")
        runCurrent()
        assertEquals(setOf(id, next), backend.savedKeyIds().toSet())
        assertEquals(listOf("b"), backend.savedKeys[next])
        assertEquals(listOf(coverName(id)), backend.stashNames())
    }

    @Test
    fun aLeftoverCoordinatorsSaveBlocksAnotherCoordinatorsSweep() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val release = CompletableDeferred<Unit>()
        backend.gate = { if (it == "b") release.await() }
        val leftover = Harness(backend, this)
        leftover.enqueue(listOf("a", "b"), TagEdits(title = "New"))
        runCurrent()
        assertEquals(1, backend.inFlight)

        val inFlightAtSweep = ArrayList<Int>()
        backend.files.onDelete = { if (it == "stray.patch") inFlightAtSweep += backend.inFlight }
        backend.files.directory.create("stray.patch").close()
        val fresh = Harness(backend, this)
        val refresh = launch { fresh.coordinator.refreshRecovery() }
        runCurrent()
        assertEquals(emptyList(), inFlightAtSweep)
        assertTrue(refresh.isActive)

        release.complete(Unit)
        runCurrent()
        refresh.join()
        assertEquals(listOf(0), inFlightAtSweep)
    }

    @Test
    fun aRestartMidBatchAsksOnlyForTheRestAndFinishesTheInterruptedFile() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = listOf("a", "b", "c", "d")
        keys.forEach { backend.files.put(it, testMp3()) }
        backend.gate = { if (it == "c" || it == "d") CompletableDeferred<Unit>().await() }
        val first = Harness(backend, this)
        first.enqueue(keys, TagEdits(title = "New"))
        runCurrent()
        first.approvePrompt()
        runCurrent()
        assertEquals(TagWriteStage.WRITING, decodeTagWriteRequests(first.saved, backend::unstash, backend::loadKeys).single().stage)
        // The process dies: "c" mid-patch, "d" saved before its result was checkpointed.
        backend.gate = {}
        interruptSave(backend, "c")
        backend.files.put("d", testMp3("New"))
        val restored = first.recreate()
        runCurrent()
        assertEquals(listOf("c", "d"), backend.consentBatches.last())
        restored.approvePrompt()
        runCurrent()
        val results = assertNotNull(restored.coordinator.completed.value).results
        assertEquals(keys, results.map { it.key }.sorted())
        assertEquals(
            mapOf("a" to FileWriteStatus.SAVED, "b" to FileWriteStatus.SAVED, "c" to FileWriteStatus.SAVED, "d" to FileWriteStatus.UNCHANGED),
            results.associate { it.key to it.status },
        )
        keys.forEach { assertContentEquals(testMp3("New"), backend.files.bytes(it), it) }
        assertTrue(backend.recovery.pending().isEmpty())
        // "d" is rescanned too: its save may have landed after the index last read it.
        assertEquals(keys, backend.rescans.single().sorted())
    }

    @Test
    fun aGrantRevokedBetweenBatchesDeniesTheTail() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = List(2_002) { "k$it" }
        val file = testMp3()
        keys.forEach { backend.files.put(it, file) }
        val harness = Harness(backend, this)
        harness.enqueue(keys, TagEdits(title = "Old"))
        runCurrent()
        harness.approvePrompt()
        runCurrent()
        // Approved, but the grant is gone by the time the files are opened.
        harness.coordinator.promptLaunched(assertNotNull(harness.coordinator.prompt.value).requestId)
        harness.coordinator.answer(WriteAnswer.APPROVED)
        runCurrent()
        harness.deliver()
        val statuses = harness.reports.single().results.associate { it.key to it.status }
        assertEquals(2_000, keys.take(2_000).count { statuses[it] == FileWriteStatus.UNCHANGED })
        assertEquals(listOf(FileWriteStatus.DENIED, FileWriteStatus.DENIED), keys.drop(2_000).map { statuses[it] })
    }

    @Test
    fun aRecoveryOfAFileThatIsGoneKeepsItsRecordAndBackup() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val store = backend.files.directory.names().toSet()
        // Gone as far as the platform can tell, which may only mean its volume is not mounted.
        backend.files.remove("a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        val completed = assertNotNull(harness.coordinator.completed.value)
        assertEquals(FileWriteStatus.MISSING, completed.results.single().status)
        harness.coordinator.deliver(completed.id)
        harness.coordinator.refreshRecovery()
        assertEquals(listOf("a"), harness.coordinator.pendingRecovery.value.map { it.target })
        assertEquals(store, backend.files.directory.names().toSet())
        assertEquals(setOf("a"), harness.coordinator.missingAtFinish.value)
    }

    @Test
    fun aSystemRequestNamesOnlyTheFilesThatAreThere() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        listOf("a", "c", "d").forEach { backend.files.put(it, testMp3()) }
        backend.unopenable += "d"
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b", "c", "d"), TagEdits(title = "New"))
        runCurrent()
        assertEquals(listOf(listOf("a", "b", "c", "d")), backend.probes)
        assertEquals(listOf(listOf("a", "c")), backend.consentBatches)
        harness.approvePrompt()
        runCurrent()
        harness.deliver()
        val statuses = harness.reports.single().results.associate { it.key to it.status }
        assertEquals(
            mapOf(
                "a" to FileWriteStatus.SAVED, "b" to FileWriteStatus.MISSING,
                "c" to FileWriteStatus.SAVED, "d" to FileWriteStatus.FAILED,
            ),
            statuses,
        )
        assertFalse(backend.files.has("b"), "never created")
    }

    @Test
    fun aFinishOfAFileThatIsGoneAsksForNoConsentAndKeepsItsRecord() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val store = backend.files.directory.names().toSet()
        backend.files.remove("a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        assertNull(harness.coordinator.prompt.value, "no dialog for a file that is not there")
        assertEquals(emptyList(), backend.consentBatches)
        val completed = assertNotNull(harness.coordinator.completed.value)
        assertEquals(FileWriteStatus.MISSING, completed.results.single().status)
        harness.coordinator.deliver(completed.id)
        harness.coordinator.refreshRecovery()
        assertEquals(listOf("a"), harness.coordinator.pendingRecovery.value.map { it.target })
        assertEquals(store, backend.files.directory.names().toSet())
        assertEquals(setOf("a"), harness.coordinator.missingAtFinish.value)
        assertEquals(setOf("a"), harness.coordinator.couldNotFinish.value, "so Forget is offered at once")
    }

    @Test
    fun aFileThatIsGoneIsSaidToBeGoneAfterARestartAndNoLongerOnceItIsBack() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val bytes = backend.files.bytes("a")
        backend.files.remove("a")
        // A new process: no Finish has been tried in it.
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertEquals(setOf("a"), harness.coordinator.missingAtFinish.value)
        assertEquals(emptySet(), harness.coordinator.couldNotFinish.value, "Forget still waits for a Finish")
        assertEquals(listOf("a"), harness.coordinator.pendingRecovery.value.map { it.target }, "never given up")
        backend.files.put("a", bytes)
        harness.coordinator.refreshRecovery()
        assertEquals(emptySet(), harness.coordinator.missingAtFinish.value)
    }

    @Test
    fun aFinishsNoteStaysWhenTheBackendCannotLook() = runTest {
        // As on iOS, or an Android provider error: nothing is known about the file either way.
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        backend.files.remove("a")
        backend.looks = false
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        harness.deliver()
        assertEquals(setOf("a"), harness.coordinator.missingAtFinish.value)
        harness.coordinator.refreshRecovery()
        assertEquals(setOf("a"), harness.coordinator.missingAtFinish.value, "a look that saw nothing removes nothing")
    }

    @Test
    fun aFileTheLookCannotTellAboutKeepsItsNote() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        backend.files.remove("a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertEquals(setOf("a"), harness.coordinator.missingAtFinish.value)
        backend.cannotTell += "a"
        harness.coordinator.refreshRecovery()
        assertEquals(setOf("a"), harness.coordinator.missingAtFinish.value)
    }

    @Test
    fun aPerFileConsentIsNeverAskedForAFileThatIsNotThere() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.RECOVERABLE_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("b", "a"), TagEdits(title = "New"))
        runCurrent()
        assertEquals("file:a", harness.coordinator.prompt.value?.consent)
        harness.approvePrompt()
        runCurrent()
        harness.deliver()
        assertEquals(listOf(FileWriteStatus.MISSING, FileWriteStatus.SAVED), harness.reports.single().results.map { it.status })
    }

    @Test
    fun anInterruptedSaveRecordsWhereItsFileIs() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        backend.paths["a"] = "/storage/emulated/0/Music/Song A.mp3"
        backend.forceFails += "a"
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        assertEquals("/storage/emulated/0/Music/Song A.mp3", backend.recovery.pending().single().path)
    }

    @Test
    fun aFinishThatCouldNotFinishAFileSaysSoUntilOneDoes() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val bytes = backend.files.bytes("a")
        backend.files.remove("a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        harness.deliver()
        assertEquals(setOf("a"), harness.coordinator.couldNotFinish.value)

        // The volume is back: the next Finish finishes it, and it is no longer listed.
        backend.files.put("a", bytes)
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        harness.deliver()
        assertEquals(emptySet(), harness.coordinator.couldNotFinish.value)
        assertEquals(emptySet(), harness.coordinator.missingAtFinish.value)
    }

    @Test
    fun aFinishTheUserDeclinedTriedNothing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        val prompt = assertNotNull(harness.coordinator.prompt.value)
        assertTrue(harness.coordinator.promptLaunched(prompt.requestId))
        harness.coordinator.answer(WriteAnswer.CANCELLED)
        runCurrent()
        harness.deliver()
        assertEquals(emptySet(), harness.coordinator.couldNotFinish.value)
    }

    @Test
    fun aBackendErrorFinishesTheRequestAsFailed() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.WRITE_PERMISSION)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        backend.permissionError = true
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(List(2) { FileWriteStatus.FAILED }, harness.reports.single().results.map { it.status })
        assertNull(harness.coordinator.completed.value)
    }

    @Test
    fun aCoverLostWithTheProcessFailsTheRequest() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        first.enqueue(listOf("a"), TagEdits(title = "New", cover = CoverEdit.Replace(byteArrayOf(1), "image/png")))
        runCurrent()
        backend.stashed.clear()
        val restored = first.recreate()
        runCurrent()
        assertNull(restored.coordinator.prompt.value)
        val completed = assertNotNull(restored.coordinator.completed.value)
        assertEquals(FileWriteStatus.FAILED, completed.results.single().status)
        restored.coordinator.deliver(completed.id)
        runCurrent()
        assertContentEquals(testMp3(), backend.files.bytes("a"))
        assertEquals(emptyList(), backend.savedKeyIds())
    }

    @Test
    fun aDeliveredRequestDropsItsCoverAndKeys() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        val id = harness.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(byteArrayOf(1), "image/png")))
        runCurrent()
        assertEquals(listOf(coverName(id)), backend.stashNames())
        harness.deliver()
        runCurrent()
        assertEquals(TagProblem.BAD_IMAGE, TagSaveResult.of(harness.reports.single(), readOnlyIsMusicLibrary = false).entries.single().problem)
        assertEquals(emptyList(), backend.stashNames())
        assertEquals(emptyList(), backend.savedKeyIds())
    }

    @Test
    fun aRestoredRequestWhoseKeyFileNamesOtherFilesIsNotRun() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val first = Harness(backend, this)
        val id = first.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        assertNotNull(first.coordinator.prompt.value)
        // Another request's keys, as many of them, now sit under this request's name.
        backend.savedKeys[id] = listOf("b")
        val restored = first.recreate()
        runCurrent()
        restored.coordinator.onHostResumed()
        runCurrent()
        assertNull(restored.coordinator.prompt.value)
        assertNull(restored.coordinator.completed.value)
        assertFalse(restored.coordinator.active.value)
        assertEquals(listOf(listOf("a")), backend.consentBatches)
        assertContentEquals(testMp3(), backend.files.bytes("b"))
    }

    @Test
    fun requestIdsAreNeverReusedByAnotherCoordinatorOrAfterARestart() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val one = Harness(backend, this)
        val other = Harness(TestTagWriteBackend(TagWriteStrategy.NO_CONSENT).apply { files.put("a", testMp3()) }, this)
        runCurrent()
        val first = one.enqueue(listOf("a"), TagEdits(title = "New"))
        val second = other.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        one.deliver()
        other.deliver()
        val restarted = one.recreate()
        runCurrent()
        val third = assertNotNull(restarted.coordinator.enqueue(listOf("a"), TagEdits(title = "Newer")))
        assertEquals(3, setOf(first, second, third).size)
        assertTrue(listOf(first, second, third).all { it > 0 })
    }

    @Test
    fun aRestoreWhoseCheckpointCannotBeWrittenRestoresNothingAndStillLetsSavesRun() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val first = Harness(backend, this)
        first.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        val restored = first.recreate()
        restored.failSave = { true }
        runCurrent()
        // The restore finished: nothing waits on it for ever, and nothing was restored.
        assertNotNull(withTimeoutOrNull(1_000) { restored.coordinator.refreshRecovery() })
        assertNull(restored.coordinator.prompt.value)
        restored.failSave = { false }
        restored.enqueue(listOf("b"), TagEdits(title = "New"))
        runCurrent()
        restored.approvePrompt()
        runCurrent()
        restored.deliver()
        assertEquals(FileWriteStatus.SAVED, restored.reports.single().results.single().status)
        assertContentEquals(testMp3(), backend.files.bytes("a"))
    }

    @Test
    fun aRestoredRequestIsKnownOnceTheRestoreIsDoneAndADroppedOneIsNot() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val first = Harness(backend, this)
        val kept = first.enqueue(listOf("a"), TagEdits(title = "New"))
        val dropped = first.enqueue(listOf("b"), TagEdits(title = "New"))
        runCurrent()
        // The second request's keys were never saved, as after a failed key write.
        backend.savedKeys.remove(dropped)
        val restored = first.recreate()
        // Asked before the restore has run: answered after it, never from the empty start.
        val answers = ArrayList<Pair<Long, Boolean>>()
        launch { listOf(kept, dropped).forEach { answers += it to restored.coordinator.knows(it) } }
        runCurrent()
        assertEquals(listOf(kept to true, dropped to false), answers)
    }

    @Test
    fun anUnclaimedReportIsKnownUntilItIsAcknowledged() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        val id = assertNotNull(harness.coordinator.enqueue(listOf("a"), TagEdits(title = "New")))
        runCurrent()
        harness.deliver()
        val report = harness.coordinator.unclaimed.value.single()
        assertTrue(harness.coordinator.knows(id))
        harness.coordinator.acknowledge(report)
        assertFalse(harness.coordinator.knows(id))
    }

    @Test
    fun aRestoreWhoseKeysCannotBeReadRestoresNothingAndFinishes() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        val first = Harness(backend, this)
        first.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        backend.keysError = true
        val restored = first.recreate()
        runCurrent()
        assertNotNull(withTimeoutOrNull(1_000) { restored.coordinator.refreshRecovery() })
        assertNull(restored.coordinator.prompt.value)
        assertFalse(restored.coordinator.active.value)
        assertContentEquals(testMp3(), backend.files.bytes("a"))
    }

    @Test
    fun aSaveOfAFileAlreadyQueuedWaitsItsTurnInsteadOfFailing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val release = CompletableDeferred<Unit>()
        var peak = 0
        backend.gate = {
            peak = maxOf(peak, backend.inFlight)
            release.await()
        }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "First"))
        runCurrent()
        // Queued behind the first, with its own duplicate folded into one file.
        harness.enqueue(listOf("a", "a"), TagEdits(title = "Second"))
        release.complete(Unit)
        runCurrent()
        harness.deliver()
        runCurrent()
        harness.deliver()
        assertEquals(
            listOf(listOf(FileWriteStatus.SAVED), listOf(FileWriteStatus.SAVED)),
            harness.reports.map { report -> report.results.map { it.status } },
        )
        assertEquals(1, peak, "two saves of one file never overlap")
        // The second edit landed over the first.
        val expected = MemoryWriteFiles().apply { put("x", testMp3()) }
        var n = 0
        val writer = DurableWriter(expected.directory, { "e${++n}" })
        for (title in listOf("First", "Second")) writer.write("x", expected.track("x"), TagEdits(title = title), null)
        assertContentEquals(expected.bytes("x"), backend.files.bytes("a"))
    }

    @Test
    fun withoutACheckpointNoKeyOrCoverFileIsWritten() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this, checkpoints = false)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        harness.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(byteArrayOf(1), "image/png")))
        runCurrent()
        assertEquals(emptyList(), backend.savedKeyIds())
        assertEquals(emptyList(), backend.stashNames())
        harness.deliver()
        runCurrent()
        harness.deliver()
        assertEquals(listOf(FileWriteStatus.SAVED, FileWriteStatus.REFUSED), harness.reports.map { it.results.single().status })
        assertEquals(emptyList(), backend.savedKeyIds())
        assertEquals(emptyList(), backend.stashNames())
    }

    @Test
    fun aQueuedRequestStoppedBeforeItStartsWritesNothing() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        val release = CompletableDeferred<Unit>()
        backend.gate = { key -> if (key == "a") release.await() }
        harness.enqueue(listOf("a"), TagEdits(title = "First"))
        val second = harness.enqueue(listOf("b"), TagEdits(title = "Second"))
        runCurrent()
        harness.coordinator.stop(second)
        release.complete(Unit)
        runCurrent()
        harness.deliver()
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.SAVED, harness.reports[0].results.single().status)
        assertEquals(FileWriteStatus.STOPPED, harness.reports[1].results.single().status)
        assertContentEquals(testMp3(), backend.files.bytes("b"))
    }

    @Test
    fun stoppingOneRequestLeavesTheRunningOneAlone() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, testMp3()) }
        val harness = Harness(backend, this)
        val release = CompletableDeferred<Unit>()
        backend.gate = { key -> if (key == "a") release.await() }
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        val second = harness.enqueue(listOf("b"), TagEdits(title = "Second"))
        runCurrent()
        harness.coordinator.stop(second)
        release.complete(Unit)
        runCurrent()
        harness.deliver()
        assertContentEquals(testMp3("New"), backend.files.bytes("a"))
    }

    @Test
    fun aReportNobodyListensForIsKeptUntilAcknowledged() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        val id = assertNotNull(harness.coordinator.enqueue(listOf("a"), TagEdits(title = "New")))
        runCurrent()
        harness.deliver()
        val report = harness.coordinator.unclaimed.value.single()
        assertEquals(id, report.id)
        assertEquals(FileWriteStatus.SAVED, report.results.single().status)
        harness.coordinator.acknowledge(report)
        assertTrue(harness.coordinator.unclaimed.value.isEmpty())
    }

    @Test
    fun aListenedReportIsNeverAlsoUnclaimed() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(1, harness.reports.size)
        assertTrue(harness.coordinator.unclaimed.value.isEmpty())
    }

    @Test
    fun forgettingAnInterruptedSaveDeletesItsRecordAndLeavesTheFileAsItIs() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val halfWritten = backend.files.bytes("a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        val record = harness.coordinator.pendingRecovery.value.single()
        assertTrue(harness.coordinator.forget(record))
        assertTrue(harness.coordinator.pendingRecovery.value.isEmpty())
        assertTrue(backend.recovery.pending().isEmpty())
        assertContentEquals(halfWritten, backend.files.bytes("a"))
    }

    @Test
    fun aFileSomeQueuedRequestNamesIsNotForgotten() = runTest {
        // Consent is never granted here, so the request stays queued and owns the file's record.
        val backend = TestTagWriteBackend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", testMp3())
        interruptSave(backend, "a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        val record = harness.coordinator.pendingRecovery.value.single()
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        assertFalse(harness.coordinator.forget(record))
        assertEquals(1, backend.recovery.pending().size)
    }

    /** A Finish that found [key] not there, delivered: both of its notes are set. */
    private suspend fun TestScope.finishWhileGone(backend: TestTagWriteBackend, key: String): Harness {
        interruptSave(backend, key)
        backend.files.remove(key)
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        harness.deliver()
        assertEquals(setOf(key), harness.coordinator.couldNotFinish.value)
        assertEquals(setOf(key), harness.coordinator.missingAtFinish.value)
        return harness
    }

    @Test
    fun forgettingASaveAFinishFoundGoneClearsItsNotes() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val harness = finishWhileGone(backend, "a")
        harness.coordinator.refreshRecovery()
        assertTrue(harness.coordinator.forget(harness.coordinator.pendingRecovery.value.single()))
        assertEquals(emptySet(), harness.coordinator.couldNotFinish.value)
        assertEquals(emptySet(), harness.coordinator.missingAtFinish.value)
    }

    @Test
    fun aRecordASaveFinishesTakesItsNotesWithIt() = runTest {
        val backend = TestTagWriteBackend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", testMp3())
        val bytes = testMp3()
        val harness = finishWhileGone(backend, "a")
        // The file is back, and an ordinary save finishes the interrupted one before saving.
        backend.files.put("a", bytes)
        harness.coordinator.refreshRecovery()
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.SAVED, harness.reports.single().results.single().status)
        harness.coordinator.refreshRecovery()
        assertTrue(harness.coordinator.pendingRecovery.value.isEmpty())
        assertEquals(emptySet(), harness.coordinator.couldNotFinish.value)
        assertEquals(emptySet(), harness.coordinator.missingAtFinish.value)
    }
}
