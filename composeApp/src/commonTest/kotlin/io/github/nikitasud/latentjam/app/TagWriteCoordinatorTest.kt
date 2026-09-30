/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.Id3Tags
import io.github.nikitasud.latentjam.library.tags.TagEdits
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

        /** Runs as a file's open begins; a test suspends here to hold that save in flight. */
        var gate: suspend (String) -> Unit = {}

        /** Files whose open has begun and whose handle is not yet closed. */
        var inFlight = 0
        private var ids = 0
        override val writer = DurableWriter(files.directory, { "w${++ids}" })
        override val recovery = TagRecovery(files.directory)

        override fun hasWritePermission() = permission
        override suspend fun batchConsent(keys: List<String>): String {
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
        assertEquals(listOf(2_000, 2_000, 500), backend.consentBatches.map { it.size })
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
        val edits = decodeTagWriteRequests(restored.saved) { backend.unstash(it) }.single().edits
        val cover = edits.cover as CoverEdit.Replace
        assertContentEquals(png, cover.bytes)
        assertEquals("image/png", cover.mime)
    }

    private companion object {
        /** An MPEG-1 Layer III frame header, so an untagged file may be given a tag. */
        val MPEG_FRAME = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
    }
}
