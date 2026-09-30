/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.Id3Tags
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.library.tags.write.TagRecovery
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

    /** An MP3 whose fresh ID3v2.3 tag (16 KiB padding) holds [title], so title edits patch in place. */
    private fun mp3(title: String = "Old"): ByteArray =
        Id3Tags.updateTag(MPEG_FRAME + ByteArray(1024), TagEdits(title = title))!!

    private class Backend(override val strategy: TagWriteStrategy) : TagWriteBackend<String> {
        val files = MemoryWriteFiles()
        var permission = false
        val granted = HashSet<String>()
        val consentBatches = ArrayList<List<String>>()
        val rescans = ArrayList<List<String>>()
        val stashed = HashMap<String, ByteArray>()
        val savedKeys = HashMap<Long, List<String>>()
        var permissionError = false

        /** Runs as a file's open begins; a test suspends here to hold that save in flight. */
        var gate: suspend (String) -> Unit = {}

        /** Runs before a batch consent is built; a test suspends here to act while it is asked for. */
        var consentGate: suspend () -> Unit = {}

        /** Files whose open has begun and whose handle is not yet closed. */
        var inFlight = 0
        private var ids = 0
        override val writer = DurableWriter(files.directory, { "w${++ids}" })
        override val recovery = TagRecovery(files.directory)

        override fun hasWritePermission(): Boolean {
            if (permissionError) throw IllegalStateException("the permission service is gone")
            return permission
        }
        override suspend fun batchConsent(keys: List<String>): String {
            consentGate()
            consentBatches += keys
            return "batch:${keys.size}"
        }
        override suspend fun open(key: String): WriteOpen<String> {
            inFlight++
            gate(key)
            val opened = openAllowed(key)
            if (opened !is WriteOpen.Opened) inFlight--
            return opened
        }
        private fun openAllowed(key: String): WriteOpen<String> {
            if (!files.has(key)) return WriteOpen.Missing
            val allowed = when (strategy) {
                TagWriteStrategy.NO_CONSENT -> true
                TagWriteStrategy.WRITE_PERMISSION -> permission
                TagWriteStrategy.SYSTEM_WRITE_REQUEST, TagWriteStrategy.RECOVERABLE_CONSENT -> key in granted
            }
            return when {
                allowed -> WriteOpen.Opened(
                    object : TargetFile by files.track(key) {
                        override fun close() {
                            inFlight--
                        }
                    },
                    freeBytes = null,
                )
                strategy == TagWriteStrategy.RECOVERABLE_CONSENT -> WriteOpen.NeedsConsent("file:$key")
                else -> WriteOpen.Denied
            }
        }
        override suspend fun rescan(keys: List<String>) {
            rescans += keys
        }
        override fun stash(name: String, bytes: ByteArray) {
            stashed[name] = bytes
        }
        override fun unstash(name: String): ByteArray? = stashed[name]
        override fun drop(name: String) {
            stashed.remove(name)
        }
        override fun stashNames(): List<String> = stashed.keys.toList()
        override fun saveKeys(id: Long, keys: List<String>) {
            savedKeys[id] = keys.toList()
        }
        override fun loadKeys(id: Long): List<String>? = savedKeys[id]
        override fun dropKeys(id: Long) {
            savedKeys.remove(id)
        }
        override fun savedKeyIds(): List<Long> = savedKeys.keys.toList()
    }

    private class Harness(val backend: Backend, val test: TestScope, restored: List<String>? = null) {
        var saved: List<String> = restored.orEmpty()

        // A child of the test's background scope, so a recreation cancels this owner and not the test.
        val scope = CoroutineScope(
            test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]) +
                StandardTestDispatcher(test.testScheduler),
        )
        val coordinator = TagWriteCoordinator(backend, scope, StandardTestDispatcher(test.testScheduler), restored, { saved = it })
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
    private fun interruptSave(backend: Backend, key: String) {
        val throwing = object : TargetFile by backend.files.track(key) {
            override fun force() = throw IllegalStateException("process died")
        }
        val result = DurableWriter(backend.files.directory, { "old" }).write(key, throwing, TagEdits(title = "Half"), null)
        assertIs<WriteResult.RecoveryPending>(result)
        assertEquals(JournalState.ROLLING_BACK, backend.recovery.pending().single { it.target == key }.state)
        assertFalse(backend.files.bytes(key).contentEquals(mp3()), "the interrupted patch is in the file")
    }

    @Test
    fun anInPlaceSaveMakesWhatTheTestsCompareAgainst() {
        val file = MemoryWriteFiles().apply { put("a", mp3()) }
        val result = DurableWriter(file.directory, { "w" }).write("a", file.track("a"), TagEdits(title = "New"), null)
        assertEquals(WriteResult.Saved(mp3().size.toLong(), rewritten = false), result)
        assertContentEquals(mp3("New"), file.bytes("a"))
    }

    @Test
    fun withoutConsentEveryFileIsSavedAndTheIndexRescannedOnce() = runTest {
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b", "c", "d").forEach { backend.files.put(it, mp3()) }
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a", "b", "c", "d"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(List(4) { FileWriteStatus.SAVED }, harness.reports.single().results.map { it.status })
        assertEquals(listOf(listOf("a", "b", "c", "d")), backend.rescans.map { it.sorted() })
        assertContentEquals(mp3("New"), backend.files.bytes("c"))
    }

    @Test
    fun theSystemRequestIsSplitAt2000Files() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = List(4_500) { "k$it" }
        val file = mp3()
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
        val backend = Backend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, mp3()) }
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
        val backend = Backend(TagWriteStrategy.WRITE_PERMISSION)
        backend.files.put("a", mp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        val prompt = assertNotNull(harness.coordinator.prompt.value)
        harness.coordinator.promptLaunched(prompt.requestId)
        harness.coordinator.answer(WriteAnswer.CANCELLED)
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.DENIED, harness.reports.single().results.single().status)
        assertContentEquals(mp3(), backend.files.bytes("a"))
    }

    @Test
    fun aDismissedSystemDialogWritesNothing() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.coordinator.promptLaunched(harness.coordinator.prompt.value!!.requestId)
        harness.coordinator.answer(WriteAnswer.CANCELLED)
        runCurrent()
        harness.deliver()
        assertEquals(TagWriteOutcome.Cancelled, tagWriteOutcome(harness.reports.single()))
        assertContentEquals(mp3(), backend.files.bytes("a"))
    }

    @Test
    fun aDialogLostWithTheProcessIsAskedAgain() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
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
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", mp3())
        interruptSave(backend, "a")
        val harness = Harness(backend, this)
        harness.enqueue(listOf("a"), TagEdits(title = "New"))
        runCurrent()
        harness.deliver()
        assertEquals(FileWriteStatus.SAVED, harness.reports.single().results.single().status)
        assertContentEquals(mp3("New"), backend.files.bytes("a"))
        assertTrue(backend.recovery.pending().isEmpty())
    }

    @Test
    fun stopLeavesTheRestUntouched() = runTest {
        val backend = Backend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b", "c").forEach { backend.files.put(it, mp3()) }
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
        assertContentEquals(mp3(), backend.files.bytes("c"))
    }

    @Test
    fun aRecoveryRequestFinishesEveryInterruptedSave() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
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
        assertContentEquals(mp3(), backend.files.bytes("a"))
        // The index may have read the half-written tags; it is told the file changed back.
        assertEquals(listOf(listOf("a")), backend.rescans)
    }

    @Test
    fun theStoreIsNeverSweptWhileASaveIsInFlight() = runTest {
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, mp3()) }
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
        assertContentEquals(mp3("New"), backend.files.bytes("a"))
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
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
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
    fun aStopWhileTheBatchConsentIsBuiltOffersNothing() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        listOf("a", "b").forEach { backend.files.put(it, mp3()) }
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
        assertContentEquals(mp3(), backend.files.bytes("a"))
    }

    @Test
    fun aStopWhileAFileIsOpenedOffersNoConsentForIt() = runTest {
        val backend = Backend(TagWriteStrategy.RECOVERABLE_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, mp3()) }
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
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = List(10_000) { "content://media/external/audio/media/${1_000_000 + it}" }
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
        val keys = listOf("a", "b", "c", "d")
        val request = TagWriteRequest(
            id = 7,
            kind = TagWriteKind.EDIT,
            keys = keys,
            edits = TagEdits(title = "T:itle", year = "", lyrics = "line\nline", cover = CoverEdit.Remove),
            stage = TagWriteStage.WAIT_BATCH,
            consented = false,
            permissionRequested = true,
            batch = listOf("c", "d"),
            results = listOf(
                FileWriteResult("b", FileWriteStatus.SAVED, newLength = 1_234_567_890_123),
                FileWriteResult("a", FileWriteStatus.REFUSED, refusal = TagRefusal.TRUNCATED),
            ),
            stopRequested = true,
            interrupted = true,
        )
        val saved = encodeTagWriteRequests(listOf(request))
        assertEquals(listOf(request), decodeTagWriteRequests(saved, { null }, { if (it == 7L) keys else null }))
        // Keys that were lost drop the request rather than guess at them.
        assertEquals(emptyList(), decodeTagWriteRequests(saved, { null }, { null }))
        assertEquals(emptyList(), decodeTagWriteRequests(saved, { null }, { keys.drop(1) }))
    }

    @Test
    fun keysAndCoversNoRequestNeedsArePrunedAtStart() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
        val first = Harness(backend, this)
        val id = first.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(byteArrayOf(1), "image/png")))
        runCurrent()
        backend.savedKeys[99] = listOf("stale")
        backend.stashed[coverName(99)] = byteArrayOf(2)
        val restored = first.recreate()
        assertEquals(listOf(id), backend.savedKeyIds())
        assertEquals(listOf(coverName(id)), backend.stashNames())
        // A new request never takes the id of keys that were there.
        assertTrue(assertNotNull(restored.coordinator.enqueue(listOf("b"), TagEdits(title = "x"))) > 99)
    }

    @Test
    fun aLeftoverCoordinatorsSaveBlocksAnotherCoordinatorsSweep() = runTest {
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        listOf("a", "b").forEach { backend.files.put(it, mp3()) }
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
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = listOf("a", "b", "c", "d")
        keys.forEach { backend.files.put(it, mp3()) }
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
        backend.files.put("d", mp3("New"))
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
        keys.forEach { assertContentEquals(mp3("New"), backend.files.bytes(it), it) }
        assertTrue(backend.recovery.pending().isEmpty())
        // "d" is rescanned too: its save may have landed after the index last read it.
        assertEquals(keys, backend.rescans.single().sorted())
    }

    @Test
    fun aGrantRevokedBetweenBatchesDeniesTheTail() = runTest {
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        val keys = List(2_002) { "k$it" }
        val file = mp3()
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
    fun aRecoveryOfAFileThatIsGoneClosesItsRecord() = runTest {
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", mp3())
        interruptSave(backend, "a")
        backend.files.remove("a")
        val harness = Harness(backend, this)
        harness.coordinator.refreshRecovery()
        assertNotNull(harness.coordinator.enqueueRecovery())
        runCurrent()
        val completed = assertNotNull(harness.coordinator.completed.value)
        assertEquals(FileWriteStatus.MISSING, completed.results.single().status)
        harness.coordinator.deliver(completed.id)
        harness.coordinator.refreshRecovery()
        assertEquals(emptyList(), harness.coordinator.pendingRecovery.value)
        assertEquals(emptyList(), backend.files.directory.names())
    }

    @Test
    fun aBackendErrorFinishesTheRequestAsFailed() = runTest {
        val backend = Backend(TagWriteStrategy.WRITE_PERMISSION)
        listOf("a", "b").forEach { backend.files.put(it, mp3()) }
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
        val backend = Backend(TagWriteStrategy.SYSTEM_WRITE_REQUEST)
        backend.files.put("a", mp3())
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
        assertContentEquals(mp3(), backend.files.bytes("a"))
        assertEquals(emptyList(), backend.savedKeyIds())
    }

    @Test
    fun aDeliveredRequestDropsItsCoverAndKeys() = runTest {
        val backend = Backend(TagWriteStrategy.NO_CONSENT)
        backend.files.put("a", mp3())
        val harness = Harness(backend, this)
        val id = harness.enqueue(listOf("a"), TagEdits(cover = CoverEdit.Replace(byteArrayOf(1), "image/png")))
        assertEquals(listOf(coverName(id)), backend.stashNames())
        runCurrent()
        harness.deliver()
        assertEquals(TagWriteOutcome.Refused(TagRefusal.UNSUPPORTED_IMAGE), tagWriteOutcome(harness.reports.single()))
        assertEquals(emptyList(), backend.stashNames())
        assertEquals(emptyList(), backend.savedKeyIds())
    }

    private companion object {
        /** An MPEG-1 Layer III frame header, so an untagged file may be given a tag. */
        val MPEG_FRAME = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
    }
}
