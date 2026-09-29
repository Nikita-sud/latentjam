/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The one property every codec must have, checked the same way for all of them. */
internal object CodecAssertions {

    /** Plans [edits], applies the plan, and checks the result with [TagVerification]. */
    fun assertWriteMatchesExpectation(codec: TagCodec, original: ByteArray, edits: TagEdits): ByteArray {
        val source = ByteArraySource(original)
        assertNull(codec.read(source).refusal, "fixture must be editable")
        val plan = codec.plan(source, edits)
        val out = assertNotNull(WritePlans.applyInMemory(original, plan), "plan was refused: $plan")
        assertEquals(emptyList(), TagVerification.verify(codec, source, ByteArraySource(out), edits), "verification")
        return out
    }
}
