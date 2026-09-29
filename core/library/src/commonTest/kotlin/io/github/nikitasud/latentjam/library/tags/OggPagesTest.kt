/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class OggPagesTest {

    private fun page(sequence: Int, payload: ByteArray, serial: Int = 9, headerType: Int = 0) =
        OggPages.serialize(headerType, 100L * sequence, serial, sequence, OggPages.lacingOf(payload.size), payload)

    @Test
    fun serializedPagesReadBackWithAValidChecksum() {
        val bytes = OggPages.serialize(0x02, 0, 7, 0, OggPages.lacingOf(10), ByteArray(10) { it.toByte() })
        val read = assertNotNull(OggPages.readAt(ByteArraySource(bytes), 0))
        assertTrue(read.crcValid)
        assertEquals(7, read.serial)
        assertEquals(0, read.sequence)
        assertTrue(read.isBeginning)
        assertFalse(read.continues)
        assertEquals(bytes.size, read.size)
    }

    @Test
    fun aFlippedPayloadByteBreaksTheChecksum() {
        val bytes = page(1, ByteArray(40) { 3 })
        bytes[35] = (bytes[35].toInt() xor 1).toByte()
        assertFalse(assertNotNull(OggPages.readAt(ByteArraySource(bytes), 0)).crcValid)
    }

    @Test
    fun readAtRejectsWhatIsNotAPage() {
        assertNull(OggPages.readAt(ByteArraySource(ByteArray(100)), 0))
        assertNull(OggPages.readAt(ByteArraySource(page(1, ByteArray(40)).copyOf(50)), 0))
    }

    @Test
    fun lacingSplitsPacketsIntoFullSegmentsAndAClosingOne() {
        assertContentEquals(intArrayOf(0), OggPages.lacingOf(0))
        assertContentEquals(intArrayOf(255, 0), OggPages.lacingOf(255))
        assertContentEquals(intArrayOf(255, 45), OggPages.lacingOf(300))
    }

    @Test
    fun packetsReassembleAcrossPages() {
        val big = ByteArray(600) { it.toByte() }
        val small = ByteArray(5) { 7 }
        val laid = assertNotNull(OggPages.layout(listOf(big, small), 2))
        val bytes = laid.mapIndexed { i, content ->
            OggPages.serialize(if (content.continues) 1 else 0, 0, 9, i, content.lacing, content.payload)
        }.reduce { a, b -> a + b }
        val source = ByteArraySource(bytes)
        val first = assertNotNull(OggPages.readAt(source, 0))
        val second = assertNotNull(OggPages.readAt(source, first.size.toLong()))
        assertTrue(second.continues)
        val (packets, closed) = assertNotNull(OggPages.packets(listOf(first, second)))
        assertTrue(closed)
        assertContentEquals(big, packets[0])
        assertContentEquals(small, packets[1])
    }

    @Test
    fun packetsRefuseAContinuationWithNothingToContinue() {
        val bytes = OggPages.serialize(1, 0, 9, 0, OggPages.lacingOf(3), ByteArray(3))
        assertNull(OggPages.packets(listOf(assertNotNull(OggPages.readAt(ByteArraySource(bytes), 0)))))
    }

    @Test
    fun layoutUsesExactlyThePageCountOrRefuses() {
        assertNull(OggPages.layout(listOf(ByteArray(10)), 2))
        val three = assertNotNull(OggPages.layout(listOf(ByteArray(600)), 3))
        assertEquals(listOf(1, 1, 1), three.map { it.lacing.size })
        assertEquals(listOf(false, true, true), three.map { it.continues })
        assertEquals(listOf(false, false, true), three.map { it.completesPacket })
        assertNull(OggPages.layout(listOf(ByteArray(70_000)), 1))
        assertEquals(2, OggPages.minimumPages(listOf(ByteArray(70_000))))
    }

    @Test
    fun renumbererShiftsSequencesAndRecomputesChecksumsAcrossChunks() {
        val input = page(5, ByteArray(300) { 1 }) + page(6, ByteArray(10) { 2 }) + page(7, ByteArray(0))
        val pass = OggRenumberer(serial = 9, sequenceDelta = 2).start()
        val sink = ByteArraySink()
        input.toList().chunked(7).forEach { pass.process(it.toByteArray(), sink) }
        pass.finish(sink)
        val out = sink.toByteArray()
        assertEquals(input.size, out.size)
        val source = ByteArraySource(out)
        var offset = 0L
        for (expected in listOf(7, 8, 9)) {
            val read = assertNotNull(OggPages.readAt(source, offset))
            assertTrue(read.crcValid)
            assertEquals(expected, read.sequence)
            offset += read.size
        }
    }

    @Test
    fun renumbererRefusesAForeignSerial() {
        val input = page(5, ByteArray(10)) + page(6, ByteArray(10), serial = 10)
        val pass = OggRenumberer(serial = 9, sequenceDelta = 1).start()
        val error = assertFailsWith<StreamRefusedException> { pass.process(input, ByteArraySink()) }
        assertEquals(TagRefusal.OGG_MULTIPLE_STREAMS, error.reason)
    }

    @Test
    fun renumbererRefusesACorruptSourcePage() {
        val input = page(5, ByteArray(10))
        input[30] = (input[30].toInt() xor 1).toByte()
        val error = assertFailsWith<StreamRefusedException> { OggRenumberer(9, 1).start().process(input, ByteArraySink()) }
        assertEquals(TagRefusal.OGG_BAD_PAGE_CRC, error.reason)
    }

    @Test
    fun renumbererRefusesAPageWithAnUnknownVersion() {
        val input = page(5, ByteArray(10))
        input[4] = 1
        OggPages.putLe32(input, 22, 0)
        OggPages.putLe32(input, 22, OggCrc.compute(input))
        val error = assertFailsWith<StreamRefusedException> {
            OggRenumberer(9, 1).start().process(input, ByteArraySink())
        }
        assertEquals(TagRefusal.OGG_MALFORMED_PAGES, error.reason)
    }

    @Test
    fun renumbererRefusesInputThatEndsMidPage() {
        val input = page(5, ByteArray(10)).copyOf(20)
        val pass = OggRenumberer(9, 1).start()
        pass.process(input, ByteArraySink())
        val error = assertFailsWith<StreamRefusedException> { pass.finish(ByteArraySink()) }
        assertEquals(TagRefusal.OGG_MALFORMED_PAGES, error.reason)
    }
}
