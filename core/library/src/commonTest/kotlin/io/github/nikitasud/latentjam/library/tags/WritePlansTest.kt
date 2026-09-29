/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class WritePlansTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun oggCrcMatchesItsDefinition() {
        // CRC-32 with polynomial 0x04C11DB7, initial value 0, no reflection and no final XOR.
        assertEquals(0x89A1897F.toInt(), OggCrc.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun crc32CanBeFedInChunks() {
        val data = ByteArray(10_000) { (it * 31).toByte() }
        val chunked = Crc32()
        chunked.update(data, 0, 4_000)
        chunked.update(data, 4_000, 6_000)
        assertEquals(Crc32.of(data), chunked.value)
    }

    @Test
    fun byteArraySourceRefusesOutOfRangeReads() {
        val source = ByteArraySource(bytes(1, 2, 3))
        assertContentEquals(bytes(2, 3), source.read(1, 2))
        assertNull(source.read(2, 2))
        assertNull(source.read(-1, 1))
        assertEquals(3L, source.length)
    }

    @Test
    fun diffOfIdenticalRegionsIsEmpty() {
        val region = ByteArray(500) { it.toByte() }
        assertTrue(ByteDiff.writes(100, region, region.copyOf()).isEmpty())
    }

    @Test
    fun diffWritesOnlyTheChangedBytesAndMergesCloseRuns() {
        val old = ByteArray(1000)
        val new = old.copyOf()
        new[10] = 1
        new[20] = 1 // 10 bytes later: merged with the first run
        new[500] = 1 // far away: its own write
        val writes = ByteDiff.writes(4, old, new)
        assertEquals(2, writes.size)
        assertEquals(14L, writes[0].offset)
        assertEquals(11, writes[0].bytes.size)
        assertEquals(504L, writes[1].offset)
        assertEquals(1, writes[1].bytes.size)
    }

    @Test
    fun diffAppendsBytesPastTheOldEnd() {
        val old = ByteArray(100)
        val new = ByteArray(150)
        new[149] = 7
        val writes = ByteDiff.writes(0, old, new)
        val applied = WritePlans.applyInMemory(old, WritePlan.InPlacePatch(writes, 150))
        assertContentEquals(new, applied)
    }

    @Test
    fun patchOrNoChangeReportsNoChangeForAnEmptyDiffAtTheSameLength() {
        val region = ByteArray(10)
        assertIs<WritePlan.NoChange>(ByteDiff.patchOrNoChange(0, region, region.copyOf(), 10, 10))
        assertIs<WritePlan.InPlacePatch>(ByteDiff.patchOrNoChange(0, region, region.copyOf(), 8, 10))
    }

    @Test
    fun inPlacePatchWritesRangesAndSetsTheLength() {
        val original = ByteArray(20) { it.toByte() }
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(2, bytes(9, 9))), newLength = 18)
        val out = assertNotNullBytes(WritePlans.applyInMemory(original, plan))
        assertEquals(18, out.size)
        assertEquals(9, out[2].toInt())
        assertEquals(4, out[4].toInt())
    }

    @Test
    fun streamingRewriteConcatenatesSegments() {
        val original = ByteArray(3 * WritePlans.COPY_CHUNK + 17) { (it % 251).toByte() }
        val plan = WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Bytes(bytes(7, 7, 7)),
                OutputSegment.Copy(sourceOffset = 5, length = original.size - 5L),
            ),
        )
        val out = assertNotNullBytes(WritePlans.applyInMemory(original, plan))
        assertEquals(plan.newLength, out.size.toLong())
        assertContentEquals(bytes(7, 7, 7), out.copyOfRange(0, 3))
        assertContentEquals(original.copyOfRange(5, original.size), out.copyOfRange(3, out.size))
    }

    @Test
    fun transformedSegmentsPassThroughTheirTransform() {
        val original = ByteArray(2 * WritePlans.COPY_CHUNK + 3) { 1 }
        val invert = object : StreamTransform {
            override fun start() = object : StreamTransform.Pass {
                override fun process(chunk: ByteArray, sink: ByteSink) =
                    sink.write(ByteArray(chunk.size) { (chunk[it] + 1).toByte() })
                override fun finish(sink: ByteSink) = Unit
            }
        }
        val plan = WritePlan.StreamingRewrite(
            listOf(OutputSegment.Transformed(0, original.size.toLong(), invert)),
        )
        val out = assertNotNullBytes(WritePlans.applyInMemory(original, plan))
        assertTrue(out.all { it == 2.toByte() })
    }

    @Test
    fun copyPastTheEndOfTheSourceIsRefused() {
        val plan = WritePlan.StreamingRewrite(listOf(OutputSegment.Copy(0, 10)))
        val error = assertFailsWith<StreamRefusedException> {
            WritePlans.applyInMemory(ByteArray(5), plan)
        }
        assertEquals(TagRefusal.TRUNCATED, error.reason)
    }

    @Test
    fun refusedAndNoChangePlansAreHandled() {
        val original = bytes(1, 2)
        assertNull(WritePlans.applyInMemory(original, WritePlan.Refused(TagRefusal.TRUNCATED)))
        assertContentEquals(original, WritePlans.applyInMemory(original, WritePlan.NoChange))
    }

    private fun assertNotNullBytes(value: ByteArray?): ByteArray {
        kotlin.test.assertNotNull(value)
        return value
    }
}
