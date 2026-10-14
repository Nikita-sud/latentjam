/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import java.io.File
import io.github.nikitasud.latentjam.library.tags.write.WriteFixtures

/** Reuse valid synthetic files, including checksums and container offsets, as mutation seeds. */
public object TagFuzzSeeds {
    @JvmStatic public fun main(args: Array<String>) {
        val root = File(args.single())
        for ((index, fixture) in (WriteFixtures.inPlace + WriteFixtures.rewrites).withIndex()) {
            val codec = TagCodecs.forSource(ByteArraySource(fixture.original))
            val name = when (codec) {
                Id3TagCodec -> "id3"
                FlacTagCodec -> "flac"
                Mp4TagCodec -> "mp4"
                OggTagCodec -> "ogg"
                else -> error("Fixture codec missing")
            }
            File(root, name).apply { mkdirs() }.resolve("valid-$index").writeBytes(fixture.original)
        }
    }
}

/** Standalone Jazzer entry points. Errors, OOMs and unexpected exceptions must escape. */
public object Id3Fuzz {
    @JvmStatic public fun fuzzerTestOneInput(data: ByteArray) { fuzzCodec(Id3TagCodec, data) }
}
public object FlacFuzz {
    @JvmStatic public fun fuzzerTestOneInput(data: ByteArray) { fuzzCodec(FlacTagCodec, data) }
}
public object Mp4Fuzz {
    @JvmStatic public fun fuzzerTestOneInput(data: ByteArray) { fuzzCodec(Mp4TagCodec, data) }
}
public object OggFuzz {
    @JvmStatic public fun fuzzerTestOneInput(data: ByteArray) { fuzzCodec(OggTagCodec, data) }
}

internal fun fuzzCodec(codec: TagCodec, data: ByteArray) {
    // Same bound as the runner; the fuzzer's memory/time limits catch oversized parsed lengths.
    require(data.size <= 1024 * 1024)
    val original = data.copyOf()
    val source = ByteArraySource(data)
    codec.read(source)
    codec.readCover(source)
    codec.inventory(source)
    val edits = TagEdits(title = "Fuzz title", artist = "Fuzz artist")
    // Use the codec directly: planSafely's production refusal must not hide parser bugs here.
    val plan = codec.plan(source, edits)
    check(data.contentEquals(original)) { "Planning mutated the source" }
    if (plan is WritePlan.Refused) {
        check(WritePlans.applyInMemory(data, plan) == null)
        return
    }
    val outputLength = when (plan) {
        is WritePlan.StreamingRewrite -> plan.newLength
        is WritePlan.InPlacePatch -> plan.newLength
        else -> data.size.toLong()
    }
    check(outputLength in 0..(data.size.toLong() + 2 * 1024 * 1024)) { "Unbounded write plan" }
    val output = try {
        WritePlans.applyInMemory(data, plan) ?: error("Unexpected refusal")
    } catch (_: StreamRefusedException) {
        // Streaming validation can refuse a malformed audio tail before DurableWriter commits it.
        return
    }
    if (plan != WritePlan.NoChange) {
        val failures = TagVerification.verify(codec, source, ByteArraySource(output), edits)
        check(failures.isEmpty()) { failures.toString() }
    }
    check(data.contentEquals(original)) { "Serialization mutated the source" }
}

