/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The impression log's Android file: torn tails, the streamed trim, the cap through [SmartImpressionLog]. */
internal class FileSmartLogStoreTest {
    private fun <T> withFile(block: suspend (File) -> T) = runTest {
        val dir = Files.createTempDirectory("smartlog").toFile()
        try { block(File(dir, "smart_impressions.log")) } finally { dir.deleteRecursively() }
    }

    private suspend fun lines(store: SmartLogStore) = ArrayList<String>().also { out -> store.forEachLine { out += it } }

    @Test
    fun appendsAfterATornTailAndSkipsIt() = withFile { file ->
        val store = FileSmartLogStore(file)
        store.append("one\ntwo\n")
        file.appendText("tor")
        assertEquals(listOf("one", "two"), lines(store), "a torn last line is not read")
        store.append("three\n")
        assertEquals(listOf("one", "two", "tor", "three"), lines(store), "the torn line is closed, not glued")
    }

    @Test
    fun theTrimKeepsAWholeLineTail() = withFile { file ->
        val store = FileSmartLogStore(file)
        store.append((1..5_000).joinToString("") { "line-$it\n" })
        val before = store.size()
        store.keepTail(10_000)
        assertTrue(store.size() <= 10_000 && store.size() < before)
        val kept = lines(store)
        assertEquals("line-5000", kept.last())
        val first = kept.first().removePrefix("line-").toInt()
        assertEquals((first..5_000).map { "line-$it" }, kept)
        assertTrue(!File(file.parentFile, ".${file.name}.tmp").exists())
    }

    @Test
    fun aMultiMegabyteLogStaysUnderItsCap() = withFile { file ->
        val log = SmartImpressionLog(FileSmartLogStore(file), capBytes = 1_000_000, keepBytes = 600_000)
        repeat(400) { batch ->
            log.recordImpressions((1..20).map { hop ->
                SmartImpression(batch * 100L + hop, "q.$batch", 0, hop, "0011223344556677", hop, 0.5, 0.25, true, "R1", "core")
            })
        }
        assertTrue(file.length() <= 1_000_000, "size ${file.length()}")
        var last: SmartImpression? = null
        var count = 0
        log.forEachLine { line -> count++; last = SmartImpressionLog.parse(line) as SmartImpression }
        assertEquals("q.399", last!!.queue)
        assertTrue(count > 1_000)
    }

    @Test
    fun clearRemovesTheFile() = withFile { file ->
        val store = FileSmartLogStore(file)
        store.append("x\n")
        store.clear()
        assertEquals(0L, store.size())
        assertEquals(emptyList(), lines(store))
    }
}
