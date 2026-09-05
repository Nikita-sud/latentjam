/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticIndexingRunTest {

    @Test
    fun `failed work preserves its checkpoint and leaves analysis available for retry`() = runTest {
        var state = AutomaticIndexingState(running = true)
        var staged = 0
        var persisted = 0
        var notificationsFinished = 0
        var promptsCancelled = 0

        runAutomaticIndexingSafely(
            checkpoint = { persisted = staged },
            finishNotification = { notificationsFinished++ },
            cancelNotificationPrompt = { promptsCancelled++ },
            onStopped = { state = state.copy(running = false) },
        ) {
            staged = 8
            // A failed preference write or rejected foreground-service update must not escape
            // the app-lifetime launch and take playback down with it.
            throw IllegalStateException("Storage is temporarily unavailable")
        }

        assertEquals(8, persisted)
        assertEquals(1, notificationsFinished)
        assertEquals(1, promptsCancelled)
        assertFalse(state.running)
        assertFalse(state.complete)

        state = state.copy(running = true)
        runAutomaticIndexingSafely(
            checkpoint = { persisted = staged },
            finishNotification = { notificationsFinished++ },
            cancelNotificationPrompt = { promptsCancelled++ },
            onStopped = { state = state.copy(running = false) },
        ) {
            staged += 8
            state = state.copy(complete = true)
        }
        assertEquals(16, persisted)
        assertEquals(2, notificationsFinished)
        assertEquals(2, promptsCancelled)
        assertFalse(state.running)
        assertTrue(state.complete)
    }

    @Test
    fun `every cleanup is attempted even when storage and notification cleanup both fail`() = runTest {
        val released = mutableListOf<String>()
        var state = AutomaticIndexingState(running = true)

        runAutomaticIndexingSafely(
            checkpoint = {
                released += "checkpoint"
                throw IllegalStateException("Disk full")
            },
            finishNotification = {
                released += "notification"
                throw IllegalStateException("Service unavailable")
            },
            cancelNotificationPrompt = {
                released += "prompt"
                throw IllegalStateException("Prompt unavailable")
            },
            onStopped = {
                released += "stopped"
                state = state.copy(running = false)
            },
        ) {
            throw IllegalStateException("Checkpoint failed during indexing")
        }

        assertEquals(listOf("checkpoint", "notification", "prompt", "stopped"), released)
        assertFalse(state.running)
        assertFalse(state.complete)
    }

    @Test
    fun `cancellation remains cancellation and finishes a suspending checkpoint before replacement`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val released = mutableListOf<String>()
            var checkpointWasActive = false
            var returnedNormally = false
            val worker = launch {
                runAutomaticIndexingSafely(
                    checkpoint = {
                        yield()
                        checkpointWasActive = currentCoroutineContext().isActive
                        released += "checkpoint"
                    },
                    finishNotification = { released += "notification" },
                    cancelNotificationPrompt = { released += "prompt" },
                    onStopped = { released += "stopped" },
                ) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                returnedNormally = true
            }
            started.await()

            worker.cancelAndJoin()
            released += "replacement"

            assertTrue(worker.isCancelled)
            assertFalse(returnedNormally)
            assertTrue(checkpointWasActive)
            assertEquals(
                listOf("checkpoint", "notification", "prompt", "stopped", "replacement"),
                released,
            )
        }
}
