/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one property every codec must have, checked the same way for all of them. */
internal object CodecAssertions {

    /** Plans [edits], applies the plan, and checks the result against every guarantee. */
    fun assertWriteMatchesExpectation(codec: TagCodec, original: ByteArray, edits: TagEdits): ByteArray {
        val source = ByteArraySource(original)
        val before = codec.read(source)
        assertNull(before.refusal, "fixture must be editable")
        val plan = codec.plan(source, edits)
        val out = assertNotNull(WritePlans.applyInMemory(original, plan), "plan was refused: $plan")
        val after = codec.read(ByteArraySource(out))
        val expected = before.expectedAfter(edits)
        // The only version change allowed: an untagged MP3 that gains its first tag (always ID3v2.3).
        val versionOk = after.version == before.version || (before.version == "none" && after.version == "ID3v2.3")
        assertTrue(versionOk, "tag version changed from ${before.version} to ${after.version}")
        // After a removal, which picture comes next is not predicted (see TagSnapshot.nextCover).
        val nextCover = if (edits.cover == CoverEdit.Remove) null else after.nextCover
        assertEquals(
            expected,
            after.copy(version = expected.version, nextCover = nextCover),
            "read-back must equal the expectation",
        )
        assertEquals(codec.audioDigest(source), codec.audioDigest(ByteArraySource(out)), "audio must not change")
        assertEquals(codec.inventory(source), codec.inventory(ByteArraySource(out)), "unmanaged data must not change")
        return out
    }
}
