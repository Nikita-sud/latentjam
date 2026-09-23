/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class LocalBackupExchangeModelTest {
    private val models = mutableListOf<LocalBackupExchangeModel>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        models.forEach(LocalBackupExchangeModel::abandon)
        Dispatchers.resetMain()
    }

    @Test
    fun exportKeepsTheExactPayloadWhileOnlySmallMetadataIsSaved() = runTest {
        val handle = SavedStateHandle()
        val text = "large backup ".repeat(100_000)
        var written: String? = null
        val exchange = model(handle, write = { encoded, destination ->
            assertEquals("chosen-document", destination)
            written = encoded
        })

        assertTrue(exchange.beginExport(text))
        assertFalse(exchange.beginExport("must not replace the retained payload"))
        assertFalse(exchange.beginImport())
        assertEquals(setOf("operation"), handle.keys())
        assertEquals("export", handle.get<String>("operation"))

        // A replacement UI only supplies the returned destination to this retained model.
        exchange.exportDestination("chosen-document")
        runCurrent()
        assertSame(text, written)
        assertEquals(
            LocalBackupExchangeResult.Export(LocalBackupFileResult.Success(Unit)),
            exchange.completed.value,
        )
        assertTrue(exchange.inProgress.value)
        exchange.acknowledge()
        assertFalse(exchange.inProgress.value)
        assertTrue(handle.keys().isEmpty())
    }

    @Test
    fun documentIOAndItsResultOutliveAReplacedScreenCallback() = runTest {
        val ready = CompletableDeferred<String>()
        val exchange = model(read = { ready.await() })
        assertTrue(exchange.beginImport())
        exchange.importSource("chosen-document")
        runCurrent()
        assertTrue(exchange.inProgress.value)
        assertFalse(exchange.beginImport())
        assertNull(exchange.completed.value)

        ready.complete("retained imported contents")
        runCurrent()
        assertEquals(
            LocalBackupExchangeResult.Import(LocalBackupFileResult.Success("retained imported contents")),
            exchange.completed.value,
        )
        // A fresh callback can consume the result once, even if no UI was present at completion.
        exchange.acknowledge()
        assertNull(exchange.completed.value)
        assertFalse(exchange.inProgress.value)
    }

    @Test
    fun processDeathCancelsLostWorkAndIgnoresTheOldPickerResult() = runTest {
        for (kind in listOf("export", "import")) {
            val exchange = model(
                handle = SavedStateHandle(mapOf("operation" to kind)),
                write = { _, _ -> error("A lost export must not write an empty document") },
                read = { error("An abandoned request must not restart") },
            )
            val expected = if (kind == "export") {
                LocalBackupExchangeResult.Export(LocalBackupFileResult.Cancelled)
            } else {
                LocalBackupExchangeResult.Import(LocalBackupFileResult.Cancelled)
            }
            assertEquals(expected, exchange.completed.value)
            exchange.exportDestination("late-destination")
            exchange.importSource("late-source")
            runCurrent()
            assertEquals(expected, exchange.completed.value)
            exchange.acknowledge()
            assertTrue(exchange.beginImport())
            exchange.abandon()
        }
    }

    @Test
    fun cancellationAndLaunchFailureBothReleaseTheBusyStateAfterDelivery() = runTest {
        val exchange = model()
        assertTrue(exchange.beginExport("payload"))
        exchange.exportDestination(null)
        assertEquals(
            LocalBackupExchangeResult.Export(LocalBackupFileResult.Cancelled),
            exchange.completed.value,
        )
        exchange.acknowledge()
        assertTrue(exchange.beginImport())
        exchange.failedToLaunch(IllegalStateException("No document provider"))
        val failure = assertIs<LocalBackupExchangeResult.Import>(exchange.completed.value)
        assertIs<LocalBackupFileResult.Failure>(failure.result)
        exchange.acknowledge()
        assertFalse(exchange.inProgress.value)
    }

    @Test
    fun leavingTheScreenCannotDeliverAnOldResultIntoANewRequest() = runTest {
        val releaseOldRead = CompletableDeferred<Unit>()
        val exchange = model(read = {
            // Some document providers cannot interrupt their current blocking read immediately.
            withContext(NonCancellable) { releaseOldRead.await() }
            "old document"
        })
        assertTrue(exchange.beginImport())
        exchange.importSource("old-source")
        runCurrent()
        exchange.abandon()
        assertTrue(exchange.beginExport("new document"))
        releaseOldRead.complete(Unit)
        runCurrent()
        assertNull(exchange.completed.value)
        assertTrue(exchange.inProgress.value)
        exchange.exportDestination(null)
        assertEquals(
            LocalBackupExchangeResult.Export(LocalBackupFileResult.Cancelled),
            exchange.completed.value,
        )
    }

    private fun model(
        handle: SavedStateHandle = SavedStateHandle(),
        write: suspend (String, String) -> Unit = { _, _ -> },
        read: suspend (String) -> String = { "document" },
    ) = LocalBackupExchangeModel(handle, write, read).also(models::add)
}
