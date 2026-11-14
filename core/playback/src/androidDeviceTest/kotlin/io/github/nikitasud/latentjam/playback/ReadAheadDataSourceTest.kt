/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ReadAheadDataSourceTest {
    private val bytes = Random(7).nextBytes(300_000)

    @Test
    fun mixedReadSizesReturnTheSameBytesFromEveryOffset() {
        val random = Random(11)
        val source = ReadAheadDataSource(ByteArrayDataSource(bytes), bufferSize = 4096)
        repeat(40) {
            val start = random.nextInt(bytes.size)
            source.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(start.toLong()).build())
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(20_000)
            while (true) {
                // Single bytes, header-sized peeks and playback-sized blocks, interleaved.
                val want = when (random.nextInt(3)) {
                    0 -> 1
                    1 -> 1 + random.nextInt(64)
                    else -> 8192 + random.nextInt(10_000)
                }
                val read = source.read(chunk, 0, want)
                if (read == C.RESULT_END_OF_INPUT) break
                assertTrue(read in 1..want)
                out.write(chunk, 0, read)
            }
            source.close()
            assertArrayEquals(bytes.copyOfRange(start, bytes.size), out.toByteArray())
        }
    }

    @Test
    fun tinyReadsReachTheInnerSourceInBlocks() {
        val inner = CountingSource(ByteArrayDataSource(bytes))
        val source = ReadAheadDataSource(inner, bufferSize = 65_536)
        source.open(DataSpec(Uri.EMPTY))
        val one = ByteArray(1)
        repeat(100_000) { assertEquals(1, source.read(one, 0, 1)) }
        assertEquals(bytes[99_999], one[0])
        // 100,000 single-byte reads, two inner reads.
        assertEquals(2, inner.reads)
    }

    @Test
    fun reopeningDropsTheBufferedBytes() {
        val source = ReadAheadDataSource(ByteArrayDataSource(bytes), bufferSize = 4096)
        val one = ByteArray(1)
        source.open(DataSpec(Uri.EMPTY))
        source.read(one, 0, 1)
        source.close()
        source.open(DataSpec.Builder().setUri(Uri.EMPTY).setPosition(1000).build())
        source.read(one, 0, 1)
        assertEquals(bytes[1000], one[0])
    }

    private class CountingSource(private val inner: DataSource) : DataSource by inner {
        var reads = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            return inner.read(buffer, offset, length)
        }
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)
    }
}
