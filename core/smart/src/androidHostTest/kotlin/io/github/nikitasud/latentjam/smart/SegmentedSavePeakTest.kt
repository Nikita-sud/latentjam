/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * docs/analysis-094-segments.md, peak memory: the smallest Java heap (-Xmx, to 2 MB) in which a 30,000-row index (a size
 * the single file can still hold) is built and saved twice — everything, then after one changed row — by the single
 * file and by the segmented store. The index's stored view copies each row as it is read, as the off-heap index does.
 * The heap that suffices is the peak of live memory; garbage does not count.
 */
internal class SegmentedSavePeakTest {
    private val directory: File = Files.createTempDirectory("lj-peak").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun fits(mode: String, heapMb: Int): Boolean {
        val dir = File(directory, "$mode-$heapMb").apply { mkdirs() }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-Xmx${heapMb}m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
            SavePeakProbe::class.java.name, mode, dir.path,
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "probe hung" }
        dir.deleteRecursively()
        return process.exitValue() == 0
    }

    private fun smallestHeap(mode: String): Int {
        var low = 32 // fails
        var high = 512 // fits
        check(fits(mode, high)) { "$mode does not fit in $high MB" }
        while (high - low > 2) {
            val mid = (low + high) / 2
            if (fits(mode, mid)) high = mid else low = mid
        }
        return high
    }

    @Test
    fun aSegmentedSaveNeedsNoMoreHeapThanTheSingleFile() {
        val single = smallestHeap("single")
        val segmented = smallestHeap("segmented")
        println("LJ_PEAK rows=${SavePeakProbe.ROWS} single_xmx_mb=$single segmented_xmx_mb=$segmented")
        // 2 MB is the search's step; the segmented store's lookup (12 B a row, 0.4 MB here) is inside it.
        assertTrue(segmented <= single + 2, "segmented $segmented MB > single $single MB")
    }
}

/** The child JVM of [SegmentedSavePeakTest]: exits 0 when both saves complete, 1 when the heap runs out. */
internal object SavePeakProbe {
    const val ROWS = 30_000

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val dir = File(args[1])
        try {
            runBlocking {
                val index = InMemoryVectorIndex(960, HalfRowStore(960))
                val identities = LinkedHashMap<TrackId, String>()
                repeat(ROWS) { i ->
                    val id = TrackId("${1_000_000_000L + i}")
                    index.upsert(id, FloatArray(960) { d -> (((i * 31 + d * 17) % 997) - 498) / 997f })
                    identities[id] = IdentityDigests.token(i.toLong(), 7L)
                }
                val store: IndexStore = if (mode == "single") {
                    FileIndexStore(dir, "smart_index.v.bin", half = true)
                } else {
                    SegmentedIndexStore("smart_index.v.bin", { FileIndexStore(dir, it, half = true) }, { dir.list()!!.toList() })
                }
                store.saveSnapshot("v", StoredIndexSnapshot(index.storedEntries(), identities))
                index.upsert(TrackId("${1_000_000_000L + ROWS / 2}"), FloatArray(960) { 0.01f * it })
                store.saveSnapshot("v", StoredIndexSnapshot(index.storedEntries(), identities))
            }
        } catch (exhausted: OutOfMemoryError) {
            exitProcess(1)
        }
        exitProcess(0)
    }
}
