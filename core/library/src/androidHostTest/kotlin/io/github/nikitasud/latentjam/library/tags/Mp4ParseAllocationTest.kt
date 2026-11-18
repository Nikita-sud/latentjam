/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What reading an M4A's tags costs in memory. The readers that walk a whole library (track identity,
 * the lyrics index) parse `moov` for every song, cover included, and a phone with a small heap has
 * room for the atom twice (its bytes, and the tree made of them) but not five times.
 */
internal class Mp4ParseAllocationTest {

    /** Bytes this thread has allocated so far, as the JVM counts them (not on the Android compile classpath). */
    private fun allocatedBytes(): Long {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val counter = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        return counter.invoke(bean, Thread.currentThread().id) as Long
    }

    @Test
    fun readingTheTagsOfAFileWithALargeCoverAllocatesItsMoovTwiceNotFiveTimes() {
        val cover = ByteArray(4 shl 20) { (it * 31).toByte() }
        val file = Mp4Fixtures.file(
            listOf(
                Mp4Fixtures.text("©nam", "Song"),
                Mp4Fixtures.covers(13 to cover),
                // Something after the cover, as every real file has: it is what made the old buffer double.
                Mp4Fixtures.freeform("MusicBrainz Track Id", "b1a9c0e9-d987-4042-ae91-78d6a3267d69"),
            ),
            freeAfterIlst = 2000,
        )
        val source = ByteArraySource(file)
        // Once before measuring, so class loading is not counted.
        val expected = listOf("MusicBrainz Track Id" to "b1a9c0e9-d987-4042-ae91-78d6a3267d69")
        assertEquals(expected, Mp4TagCodec.identityComments(source))
        val before = allocatedBytes()
        assertEquals(expected, Mp4TagCodec.identityComments(source))
        val allocated = allocatedBytes() - before
        assertTrue(allocated < 3L * cover.size, "allocated $allocated bytes for a ${cover.size}-byte cover")
    }
}
