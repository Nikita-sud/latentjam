/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackDeleteCoordinatorTest {
    @Test fun `restored storage permission continues the original deletion`() = runTest {
        val backend = Backend(TrackDeleteStrategy.WRITE_PERMISSION)
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one", "two"))
        runCurrent()
        original.launchPrompt()
        val restored = original.recreate()
        runCurrent()
        assertNull(restored.coordinator.prompt.value)
        assertEquals(emptyList(), backend.deletes)
        backend.writeAllowed = true
        restored.coordinator.answer(DeleteAnswer.APPROVED)
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(listOf("one", "two"), backend.deletes)
        assertEquals(TrackDeleteReport(deleted = 2), restored.report())
    }

    @Test fun `restored file consent retries that file once before moving on`() = runTest {
        val backend = Backend(TrackDeleteStrategy.RECOVERABLE_CONSENT)
        backend.attempt = { if (backend.deletes.size == 1) DeleteAttempt.NeedsConsent("one") else DeleteAttempt.Deleted }
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one", "two"))
        runCurrent()
        original.launchPrompt()
        val restored = original.recreate()
        restored.coordinator.answer(DeleteAnswer.APPROVED)
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(listOf("one", "one", "two"), backend.deletes)
        assertEquals(TrackDeleteReport(deleted = 2), restored.report())
    }

    @Test fun `restored system deletion consumes the result without deleting again`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one", "two"))
        runCurrent()
        original.launchPrompt()
        val restored = original.recreate()
        runCurrent()
        assertNull(restored.coordinator.prompt.value)
        restored.coordinator.answer(DeleteAnswer.APPROVED)
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(1, backend.batches.size)
        assertTrue(backend.deletes.isEmpty())
        assertEquals(TrackDeleteReport(deleted = 2), restored.report())
        // A completed result also survives recreation until the UI acknowledges it.
        val delivered = restored.recreate()
        assertEquals(TrackDeleteReport(deleted = 2), delivered.report())
        delivered.coordinator.acknowledge(assertNotNull(delivered.coordinator.completed.value).id)
        assertNull(delivered.recreate().coordinator.completed.value)
    }

    @Test fun `overlapping requests are queued and do not replace an active confirmation`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one"))
        runCurrent()
        val first = assertNotNull(harness.coordinator.prompt.value)
        harness.launchPrompt()
        harness.coordinator.enqueue(listOf("two"))
        harness.coordinator.enqueue(listOf("one", "two"))
        runCurrent()
        assertEquals(listOf(listOf("one")), backend.batches)
        harness.coordinator.answer(DeleteAnswer.APPROVED)
        harness.coordinator.acknowledge(first.requestId)
        runCurrent()
        val second = assertNotNull(harness.coordinator.prompt.value)
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(listOf(listOf("one"), listOf("two")), backend.batches)
        assertFalse(harness.coordinator.promptLaunched(first.requestId))
        harness.launchPrompt()
        harness.coordinator.answer(DeleteAnswer.APPROVED)
        harness.coordinator.acknowledge(second.requestId)
        runCurrent()
        assertNull(harness.coordinator.prompt.value)
        assertNull(harness.coordinator.completed.value)
    }

    @Test fun `provider operations remain sequential while another request arrives`() = runTest {
        val backend = Backend(TrackDeleteStrategy.WRITE_PERMISSION).apply { writeAllowed = true }
        val release = CompletableDeferred<Unit>()
        backend.attempt = { if (it == "one") release.await(); DeleteAttempt.Deleted }
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one"))
        runCurrent()
        harness.coordinator.enqueue(listOf("two"))
        runCurrent()
        assertEquals(listOf("one"), backend.deletes)
        release.complete(Unit)
        runCurrent()
        harness.coordinator.acknowledge(assertNotNull(harness.coordinator.completed.value).id)
        runCurrent()
        assertEquals(listOf("one", "two"), backend.deletes)
        assertEquals(TrackDeleteReport(deleted = 1), harness.report())
    }

    @Test fun `repeated recoverable refusal after approval does not prompt forever`() = runTest {
        val backend = Backend(TrackDeleteStrategy.RECOVERABLE_CONSENT)
        backend.attempt = { DeleteAttempt.NeedsConsent(it) }
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one"))
        runCurrent()
        harness.launchPrompt()
        harness.coordinator.answer(DeleteAnswer.APPROVED)
        runCurrent()
        assertEquals(listOf("one", "one"), backend.deletes)
        assertNull(harness.coordinator.prompt.value)
        assertEquals(TrackDeleteReport(denied = 1), harness.report())
    }

    @Test fun `failure to launch a system prompt is reported separately from cancellation`() = runTest {
        for (answer in listOf(DeleteAnswer.FAILED, DeleteAnswer.CANCELLED)) {
            val harness = Harness(Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST))
            harness.coordinator.enqueue(listOf("one", "two"))
            runCurrent()
            harness.launchPrompt()
            harness.coordinator.answer(answer)
            val report = harness.report()
            assertEquals(if (answer == DeleteAnswer.FAILED) TrackDeleteReport(failed = 2)
                else TrackDeleteReport(cancelled = 2), report)
            assertEquals(answer == DeleteAnswer.CANCELLED, report.isSilent)
        }
    }

    @Test fun `permission denial is reported and a stale grant does not loop`() = runTest {
        for (answer in listOf(DeleteAnswer.CANCELLED, DeleteAnswer.APPROVED)) {
            val backend = Backend(TrackDeleteStrategy.WRITE_PERMISSION)
            val harness = Harness(backend)
            harness.coordinator.enqueue(listOf("one"))
            runCurrent()
            harness.launchPrompt()
            harness.coordinator.answer(answer)
            runCurrent()
            assertEquals(TrackDeleteReport(denied = 1), harness.report())
            assertTrue(backend.deletes.isEmpty())
            assertNull(harness.coordinator.prompt.value)
        }
    }

    @Test fun `partial success retains permission and source failures`() = runTest {
        val backend = Backend(TrackDeleteStrategy.WRITE_PERMISSION).apply { writeAllowed = true }
        backend.attempt = { when (it) {
            "one" -> DeleteAttempt.Deleted
            "two" -> DeleteAttempt.Denied
            else -> DeleteAttempt.Failed
        } }
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one", "two", "three"))
        runCurrent()
        assertEquals(TrackDeleteReport(deleted = 1, denied = 1, failed = 1), harness.report())
        assertEquals(harness.report(), harness.recreate().report())
    }

    @Test fun `cancellation stops the remaining file prompts without hiding prior deletions`() = runTest {
        val backend = Backend(TrackDeleteStrategy.RECOVERABLE_CONSENT)
        backend.attempt = { if (it == "one") DeleteAttempt.Deleted else DeleteAttempt.NeedsConsent(it) }
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one", "two", "three"))
        runCurrent()
        harness.launchPrompt()
        harness.coordinator.answer(DeleteAnswer.CANCELLED)
        runCurrent()
        assertEquals(listOf("one", "two"), backend.deletes)
        assertEquals(TrackDeleteReport(deleted = 1, cancelled = 2), harness.report())
    }

    @Test fun `large system requests are split and the second batch can be cancelled`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val harness = Harness(backend)
        harness.coordinator.enqueue((0..2_000).map { "item-$it" })
        runCurrent()
        harness.launchPrompt()
        harness.coordinator.answer(DeleteAnswer.APPROVED)
        runCurrent()
        assertEquals(listOf(2_000, 1), backend.batches.map { it.size })
        harness.launchPrompt()
        harness.coordinator.answer(DeleteAnswer.CANCELLED)
        assertEquals(TrackDeleteReport(deleted = 2_000, cancelled = 1), harness.recreate().report())
    }

    @Test fun `a prompt prepared but not launched is rebuilt after process recreation`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one"))
        runCurrent()
        val restored = harness.recreate()
        runCurrent()
        restored.launchPrompt()
        restored.coordinator.answer(DeleteAnswer.APPROVED)
        assertEquals(TrackDeleteReport(deleted = 1), restored.report())
    }

    @Test fun `coroutine cancellation is not reported as user cancellation or failure`() = runTest {
        val backend = Backend(TrackDeleteStrategy.WRITE_PERMISSION).apply { writeAllowed = true }
        backend.attempt = { throw CancellationException("owner cleared") }
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one"))
        runCurrent()
        assertNull(harness.coordinator.completed.value)
        assertEquals(DeleteStage.DELETING, decodeDeleteRequests(harness.saved).single().stage)
        // The provider may have removed it just before the old process disappeared.
        backend.attempt = { DeleteAttempt.Missing }
        val restored = harness.recreate()
        runCurrent()
        assertEquals(TrackDeleteReport(deleted = 1), restored.report())
    }

    @Test fun `batch preparation failure is visible and does not block the next request`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST).apply { failBatch = true }
        val harness = Harness(backend)
        harness.coordinator.enqueue(listOf("one"))
        harness.coordinator.enqueue(listOf("two"))
        runCurrent()
        assertEquals(TrackDeleteReport(failed = 1), harness.report())
        backend.failBatch = false
        harness.coordinator.acknowledge(assertNotNull(harness.coordinator.completed.value).id)
        runCurrent()
        assertNotNull(harness.coordinator.prompt.value)
    }

    @Test fun `lost storage permission result is retried and unblocks a queued request`() = runTest {
        for (alreadyGranted in listOf(false, true)) {
            val backend = Backend(TrackDeleteStrategy.WRITE_PERMISSION)
            val original = Harness(backend)
            original.coordinator.enqueue(listOf("one"))
            runCurrent()
            original.launchPrompt()
            val restored = original.recreate()
            backend.writeAllowed = alreadyGranted
            restored.coordinator.enqueue(listOf("two"))
            // No answer is delivered for the original launcher.
            restored.coordinator.onHostResumed()
            runCurrent()
            if (!alreadyGranted) {
                restored.launchPrompt()
                backend.writeAllowed = true
                restored.coordinator.answer(DeleteAnswer.APPROVED)
                runCurrent()
            }
            assertEquals(TrackDeleteReport(deleted = 1), restored.report())
            restored.coordinator.acknowledge(assertNotNull(restored.coordinator.completed.value).id)
            runCurrent()
            assertEquals(listOf("one", "two"), backend.deletes)
            assertEquals(TrackDeleteReport(deleted = 1), restored.report())
        }
    }

    @Test fun `lost file consent produces a fresh prompt and later requests still run`() = runTest {
        val backend = Backend(TrackDeleteStrategy.RECOVERABLE_CONSENT)
        var granted = false
        backend.attempt = { if (granted) DeleteAttempt.Deleted else DeleteAttempt.NeedsConsent(it) }
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one"))
        runCurrent()
        original.launchPrompt()
        val restored = original.recreate()
        restored.coordinator.enqueue(listOf("two"))
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(listOf("one", "one"), backend.deletes)
        restored.launchPrompt()
        // Another resume must not discard the new, live dialog.
        restored.coordinator.onHostResumed()
        runCurrent()
        assertNull(restored.coordinator.prompt.value)
        assertEquals(listOf("one", "one"), backend.deletes)
        granted = true
        restored.coordinator.answer(DeleteAnswer.APPROVED)
        runCurrent()
        restored.coordinator.acknowledge(assertNotNull(restored.coordinator.completed.value).id)
        runCurrent()
        assertEquals(listOf("one", "one", "one", "two"), backend.deletes)
        assertEquals(TrackDeleteReport(deleted = 1), restored.report())
    }

    @Test fun `lost system result requests fresh consent and unblocks the next batch`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one"))
        runCurrent()
        original.launchPrompt()
        val restored = original.recreate()
        restored.coordinator.enqueue(listOf("two"))
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(listOf("one"), backend.queries)
        assertEquals(listOf(listOf("one"), listOf("one")), backend.batches)
        restored.launchPrompt()
        restored.coordinator.answer(DeleteAnswer.APPROVED)
        restored.coordinator.acknowledge(assertNotNull(restored.coordinator.completed.value).id)
        runCurrent()
        assertEquals(listOf("two"), backend.batches.last())
        restored.launchPrompt()
        restored.coordinator.answer(DeleteAnswer.APPROVED)
        assertEquals(TrackDeleteReport(deleted = 1), restored.report())
    }

    @Test fun `already removed batch is reconciled without asking to delete missing files`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one", "two"))
        runCurrent()
        original.launchPrompt()
        backend.checkPresence = { DeletePresence.MISSING }
        val restored = original.recreate()
        restored.coordinator.enqueue(listOf("three"))
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(TrackDeleteReport(deleted = 2), restored.report())
        assertNull(restored.coordinator.prompt.value)
        assertEquals(1, backend.batches.size)
        assertTrue(backend.deletes.isEmpty())
        restored.coordinator.acknowledge(assertNotNull(restored.coordinator.completed.value).id)
        runCurrent()
        assertEquals(listOf("three"), backend.batches.last())
        assertNotNull(restored.coordinator.prompt.value)
    }

    @Test fun `partly removed batch only asks consent for survivors`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one", "two", "three"))
        runCurrent()
        original.launchPrompt()
        backend.checkPresence = { if (it == "two") DeletePresence.MISSING else DeletePresence.PRESENT }
        val restored = original.recreate()
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(listOf("one", "three"), backend.batches.last())
        restored.launchPrompt()
        restored.coordinator.answer(DeleteAnswer.CANCELLED)
        assertEquals(TrackDeleteReport(deleted = 1, cancelled = 2), restored.report())
        assertEquals(restored.report(), restored.recreate().report())
    }

    @Test fun `failed presence checks are never counted as successful deletions`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one", "two", "three"))
        runCurrent()
        original.launchPrompt()
        backend.checkPresence = { when (it) {
            "one" -> DeletePresence.MISSING
            "two" -> DeletePresence.DENIED
            else -> error("provider unavailable")
        } }
        val restored = original.recreate()
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(TrackDeleteReport(deleted = 1, denied = 1, failed = 1), restored.report())
        assertTrue(backend.deletes.isEmpty())
        assertEquals(1, backend.batches.size)
    }

    @Test fun `batch recovery survives another process death during the presence check`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val original = Harness(backend)
        original.coordinator.enqueue(listOf("one"))
        runCurrent()
        original.launchPrompt()
        backend.checkPresence = { CompletableDeferred<DeletePresence>().await() }
        val restored = original.recreate()
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(DeleteStage.RECOVER_BATCH, decodeDeleteRequests(restored.saved).single().stage)
        val again = restored.recreate()
        backend.checkPresence = { DeletePresence.MISSING }
        runCurrent()
        assertEquals(TrackDeleteReport(deleted = 1), again.report())
    }

    @Test fun `recovery reconciles only the submitted batch and preserves earlier counts`() = runTest {
        val backend = Backend(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST)
        val original = Harness(backend)
        original.coordinator.enqueue((0..2_000).map { "item-$it" })
        runCurrent()
        original.launchPrompt()
        original.coordinator.answer(DeleteAnswer.APPROVED)
        runCurrent()
        original.launchPrompt()
        backend.checkPresence = { DeletePresence.MISSING }
        val restored = original.recreate()
        restored.coordinator.onHostResumed()
        runCurrent()
        assertEquals(listOf("item-2000"), backend.queries)
        assertEquals(TrackDeleteReport(deleted = 2_001), restored.report())
    }

    private inner class Harness(
        val backend: Backend,
        restored: List<String>? = null,
        val testScope: TestScope,
    ) {
        var saved = restored ?: emptyList()
        private val ownerScope = CoroutineScope(testScope.backgroundScope.coroutineContext + Job(testScope.backgroundScope.coroutineContext[Job]))
        val coordinator = TrackDeleteCoordinator(backend, ownerScope, restored) { saved = it }
        fun launchPrompt() {
            assertTrue(coordinator.promptLaunched(assertNotNull(coordinator.prompt.value).requestId))
        }
        fun report() = assertNotNull(coordinator.completed.value).report
        fun recreate(): Harness {
            ownerScope.cancel()
            return Harness(backend, saved, testScope)
        }
    }

    private fun TestScope.Harness(backend: Backend) = Harness(backend, testScope = this)

    private class Backend(override val strategy: TrackDeleteStrategy) : TrackDeleteBackend<String> {
        var writeAllowed = false
        var failBatch = false
        val deletes = mutableListOf<String>()
        val batches = mutableListOf<List<String>>()
        val queries = mutableListOf<String>()
        var checkPresence: suspend (String) -> DeletePresence = { DeletePresence.PRESENT }
        var attempt: suspend (String) -> DeleteAttempt<String> = { DeleteAttempt.Deleted }
        override fun hasWritePermission() = writeAllowed
        override suspend fun presence(uri: String): DeletePresence {
            queries += uri
            return checkPresence(uri)
        }
        override suspend fun delete(uri: String): DeleteAttempt<String> {
            deletes += uri
            return attempt(uri)
        }
        override suspend fun batchConsent(uris: List<String>): String {
            if (failBatch) error("system request unavailable")
            batches += uris
            return "batch-${batches.size}"
        }
    }
}
