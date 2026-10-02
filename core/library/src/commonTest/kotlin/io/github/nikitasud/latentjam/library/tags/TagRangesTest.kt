/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class TagRangesTest {

    @Test
    fun byteSourceRefusesLongOffsetsThatOverflow() {
        val source = ByteArraySource(byteArrayOf(1, 2, 3))
        assertNull(source.read(Long.MAX_VALUE, 2))
        assertNull(source.read(1L shl 32, 1))
        assertNull(source.read(0, -1))
    }

    @Test
    fun aDigestOfARangePastTheEndIsNone() {
        val source = ByteArraySource(byteArrayOf(1, 2, 3))
        assertNull(Digests.crc32(source, Long.MAX_VALUE, 2))
        assertNull(Digests.crc32(source, 1, Long.MAX_VALUE))
        assertNull(Digests.crc32(source, 0, -1))
    }

    @Test
    fun invalidCopyRangesCannotSilentlyProduceAShorterFile() {
        for (copy in listOf(OutputSegment.Copy(0, -1), OutputSegment.Copy(Long.MAX_VALUE, 2))) {
            assertFailsWith<StreamRefusedException> {
                WritePlans.stream(ByteArraySource(byteArrayOf(1)), WritePlan.StreamingRewrite(listOf(copy)), ByteArraySink())
            }
        }
    }
}
