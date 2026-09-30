/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class TagVerificationBaselineTest {
    private val original = Id3TestTags.build(3, listOf(TestFrame("TIT2", Id3TestTags.latin1Body("Old")))) +
        Id3TestTags.mp3Payload()

    @Test
    fun aCorrectInPlaceEditVerifiesWithoutAnAudioPass() {
        val codec = Id3TagCodec
        val source = ByteArraySource(original)
        val baseline = TagVerification.baseline(codec, source)
        val edits = TagEdits(title = "New")
        val edited = WritePlans.applyInMemory(original, codec.plan(source, edits))!!
        assertEquals(emptyList(), TagVerification.verifyTags(codec, baseline, ByteArraySource(edited), edits))
    }

    @Test
    fun aWrongFieldFailsTheTagCheck() {
        val codec = Id3TagCodec
        val baseline = TagVerification.baseline(codec, ByteArraySource(original))
        val failures = TagVerification.verifyTags(codec, baseline, ByteArraySource(original), TagEdits(title = "New"))
        assertEquals(TagVerification.Check.READ_BACK, failures.single().check)
    }

    @Test
    fun aRefusedOriginalNeverVerifies() {
        val broken = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 2, 0, 0, 0, 0, 0, 10) +
            ByteArray(10)
        val baseline = TagVerification.baseline(Id3TagCodec, ByteArraySource(broken))
        val failures = TagVerification.verifyTags(Id3TagCodec, baseline, ByteArraySource(broken), TagEdits(title = "x"))
        assertEquals(TagVerification.Check.EDITABLE, failures.single().check)
    }

    @Test
    fun aCodecThatThrowsIsARefusalNotACrash() {
        val throwing = object : TagCodec by Id3TagCodec {
            override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan =
                WritePlan.InPlacePatch(listOf(ByteWrite(-1, byteArrayOf(1))), 10)
        }
        val plan = TagCodecs.planSafely(throwing, ByteArraySource(original), TagEdits(title = "x"))
        assertIs<WritePlan.Refused>(plan)
        assertEquals(TagRefusal.PLAN_INCONSISTENT, plan.reason)
        assertTrue(TagCodecs.planSafely(Id3TagCodec, ByteArraySource(original), TagEdits(title = "x")) !is WritePlan.Refused)
    }
}
