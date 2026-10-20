/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The impression log's iOS file (stdio streaming): torn tails, the trim, the cap through [SmartImpressionLog]. */
@OptIn(ExperimentalForeignApi::class)
class IosFileSmartLogStoreTest {
    private fun <T> withPath(block: suspend (String) -> T) = runTest {
        val path = NSTemporaryDirectory() + "smartlog-" + NSUUID().UUIDString + ".log"
        try { block(path) } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
            NSFileManager.defaultManager.removeItemAtPath("$path.tmp", null)
        }
    }

    private suspend fun lines(store: SmartLogStore) = ArrayList<String>().also { out -> store.forEachLine { out += it } }

    @Test
    fun appendsAfterATornTailAndSkipsIt() = withPath { path ->
        val store = FileSmartLogStore { path }
        store.append("one\ntwo\n")
        durableAppend(path, "tor", "test")
        assertEquals(listOf("one", "two"), lines(store))
        store.append("three\n")
        assertEquals(listOf("one", "two", "tor", "three"), lines(store))
    }

    @Test
    fun theTrimKeepsAWholeLineTailAcrossChunks() = withPath { path ->
        val store = FileSmartLogStore { path }
        store.append((1..20_000).joinToString("") { "line-$it\n" })
        store.keepTail(100_000)
        assertTrue(store.size() <= 100_000)
        val kept = lines(store)
        assertEquals("line-20000", kept.last())
        val first = kept.first().removePrefix("line-").toInt()
        assertEquals((first..20_000).map { "line-$it" }, kept)
        assertTrue(!NSFileManager.defaultManager.fileExistsAtPath("$path.tmp"))
    }

    @Test
    fun aLogStaysUnderItsCap() = withPath { path ->
        val log = SmartImpressionLog(FileSmartLogStore { path }, capBytes = 200_000, keepBytes = 120_000)
        repeat(150) { batch ->
            log.recordImpressions((1..20).map { hop ->
                SmartImpression(batch * 100L + hop, "q.$batch", 0, hop, "0011223344556677", hop, 0.5, 0.25, true, "R1", "core")
            })
        }
        val store = FileSmartLogStore { path }
        assertTrue(store.size() <= 200_000)
        val kept = lines(store)
        assertEquals("q.149", (SmartImpressionLog.parse(kept.last()) as SmartImpression).queue)
        store.clear()
        assertEquals(0L, store.size())
    }
}
