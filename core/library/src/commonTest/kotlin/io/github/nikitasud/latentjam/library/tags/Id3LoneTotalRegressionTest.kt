// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Id3LoneTotalRegressionTest {
    @Test fun unrelatedEditPreservesTotalsWithoutReadableNumbers() {
        for (major in listOf(3, 4)) {
            val original = Id3TestTags.build(major, listOf(
                TestFrame("TPOS", Id3TestTags.latin1Body("/2")),
                TestFrame("TRCK", Id3TestTags.latin1Body("/12")),
            )) + Id3TestTags.mp3Payload(1024)
            val source = ByteArraySource(original)
            val before = Id3TagCodec.read(source)
            assertEquals(2, before.discTotal)
            assertEquals(12, before.trackTotal)
            val edits = TagEdits(title = "New title", artist = "New artist")
            val output = assertNotNull(WritePlans.applyInMemory(original, Id3TagCodec.plan(source, edits)))
            val after = Id3TagCodec.read(ByteArraySource(output))
            assertEquals(before.copy(title = "New title", artist = "New artist"), after)
            assertTrue(TagVerification.verify(Id3TagCodec, source, ByteArraySource(output), edits).isEmpty())
        }
    }
}
