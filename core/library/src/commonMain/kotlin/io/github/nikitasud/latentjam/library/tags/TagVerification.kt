/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * How a write is checked: the file read back must say what [TagSnapshot.expectedAfter] predicts,
 * its audio must be the same bytes, and everything the editor does not own must be unchanged.
 *
 * The comparison rules live here once, so the tests and the save path cannot drift apart.
 */
public object TagVerification {

    public enum class Check {
        /** The file was not editable to begin with; nothing written to it can verify. */
        EDITABLE,

        /** The tag version changed, other than an untagged MP3 gaining its first (ID3v2.3) tag. */
        VERSION,

        /** A field reads back other than the edit predicts. */
        READ_BACK,

        /** The audio differs, or could not be digested on either side. */
        AUDIO,

        /** Data the editor does not own changed. */
        INVENTORY,
    }

    public data class Failure(val check: Check, val detail: String)

    /** Every way [after] fails to be a correct write of [edits] onto [before]; empty when it verifies. */
    public fun verify(
        codec: TagCodec,
        before: RandomAccessSource,
        after: RandomAccessSource,
        edits: TagEdits,
    ): List<Failure> {
        val start = codec.read(before)
        start.refusal?.let { return listOf(Failure(Check.EDITABLE, "the original was refused: $it")) }
        val failures = ArrayList(readBackFailures(start, edits, codec.read(after)))
        val audioBefore = codec.audioDigest(before)
        val audioAfter = codec.audioDigest(after)
        if (!audioMatches(audioBefore, audioAfter)) {
            failures += Failure(Check.AUDIO, "digest $audioBefore before, $audioAfter after")
        }
        val expected = expectedInventory(codec.inventory(before), edits)
        val actual = codec.inventory(after)
        if (expected != actual) failures += Failure(Check.INVENTORY, "expected $expected\nactual   $actual")
        return failures
    }

    /** [after] against what a correct write of [edits] onto [before] reads back as. */
    public fun readBackFailures(before: TagSnapshot, edits: TagEdits, after: TagSnapshot): List<Failure> {
        val failures = ArrayList<Failure>()
        // The only version change allowed: an untagged MP3 that gains its first tag, always ID3v2.3.
        val versionOk = after.version == before.version || (before.version == "none" && after.version == "ID3v2.3")
        if (!versionOk) failures += Failure(Check.VERSION, "${before.version} became ${after.version}")
        val expected = before.expectedAfter(edits)
        // After a removal, which picture comes next is not predicted (see TagSnapshot.nextCover).
        val nextCover = if (edits.cover == CoverEdit.Remove) null else after.nextCover
        val comparable = after.copy(version = expected.version, nextCover = nextCover)
        if (comparable != expected) failures += Failure(Check.READ_BACK, "expected $expected\nactual   $after")
        return failures
    }

    /** Equal digests, and never a match when either side could not be digested. */
    public fun audioMatches(before: Long?, after: Long?): Boolean = before != null && before == after

    /** The inventory a correct write of [edits] leaves behind a file whose inventory was [before]. */
    @Suppress("UNUSED_PARAMETER")
    public fun expectedInventory(before: List<String>, edits: TagEdits): List<String> = before
}
