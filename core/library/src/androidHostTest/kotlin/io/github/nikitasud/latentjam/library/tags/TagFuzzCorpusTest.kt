/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import java.io.File
import io.github.nikitasud.latentjam.library.tags.write.WriteFixtures
import kotlin.test.Test
import kotlin.test.assertTrue

class TagFuzzCorpusTest {
    @Test fun replaySavedInputsAndValidContainers() {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "tools/fuzz/corpus").isDirectory }
        val codecs = mapOf("id3" to Id3TagCodec, "flac" to FlacTagCodec, "mp4" to Mp4TagCodec, "ogg" to OggTagCodec)
        for ((name, codec) in codecs) {
            val files = File(root, "tools/fuzz/corpus/$name").listFiles().orEmpty().filter { it.isFile }
            assertTrue(files.isNotEmpty(), "Missing corpus: $name")
            files.forEach { fuzzCodec(codec, it.readBytes()) }
        }
        for (case in WriteFixtures.inPlace + WriteFixtures.rewrites) {
            val codec = TagCodecs.forSource(ByteArraySource(case.original))!!
            fuzzCodec(codec, case.original)
        }
    }
}

