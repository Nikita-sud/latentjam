/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-checks this reader against the Python builder that writes the asset: same file, same entity
 * ids, same decoded vectors.
 *
 * The fixture is not committed. Point `ARTIST_PACK_PARITY` at a directory holding
 * `artist_knowledge.bin` and `cases.tsv` (artist name, comma-separated entity ids, and the expected
 * vector or nothing). Without it the test checks nothing, so it never blocks a normal build. Run with
 * `--rerun`: the variable is not a task input.
 */
class ArtistKnowledgePackParityTest {

    @Test
    fun kotlinReaderDecodesWhatThePythonBuilderWrote() {
        val dir = System.getenv("ARTIST_PACK_PARITY")?.let(::File)?.takeIf(File::isDirectory) ?: return
        val pack = assertNotNull(ArtistKnowledgePack.parse(File(dir, "artist_knowledge.bin").readBytes()))
        var checked = 0
        File(dir, "cases.tsv").readLines().filter(String::isNotEmpty).forEach { line ->
            val (name, idsField, vectorField) = line.split("\t")
            val ids = idsField.split(",").filter(String::isNotEmpty).map(String::toInt).toIntArray()
            val actual = pack.descriptor(ids, name)
            if (vectorField.isEmpty()) {
                assertNull(actual, "no descriptor expected for $name")
            } else {
                val expected = vectorField.split(",").map(String::toFloat)
                val values = assertNotNull(actual, "descriptor expected for $name")
                val cosine = expected.indices.sumOf { expected[it].toDouble() * values[it] }
                assertTrue(cosine > 0.9999, "$name: cosine $cosine")
                checked++
            }
        }
        assertTrue(checked > 0, "the fixture held no descriptors")
        println("ARTIST_PACK_PARITY ok: $checked descriptors match")
    }
}
