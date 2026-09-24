/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Reads the pack and index the app ships, and keeps the language table in step with its builder. */
class ArtistKnowledgeAssetTest {

    private val knowledge: ArtistKnowledge by lazy {
        fun asset(name: String): ByteArray {
            val file = File("../../androidApp/src/main/assets/ml/$name")
            assertTrue(file.isFile, "asset missing at ${file.absolutePath}")
            return file.readBytes()
        }
        val index = asset("music_entities_250k.bin")
        val pack = asset("artist_knowledge.bin")
        assertNotNull(ArtistKnowledgePack.parse(pack), "the shipped pack must parse")
        ArtistKnowledge(MusicEntityResolver { index }) { pack }
    }

    @Test
    fun `the shipped pack knows where and when well-known artists sang`() {
        assertEquals(ArtistFacts("en", 1980), knowledge.facts("ABBA"))
        assertEquals(ArtistFacts("ru", 1980), knowledge.facts("Кино"))
        assertEquals(ArtistFacts("ro", 1990), knowledge.facts("Ion Suruceanu"))
        assertEquals(ArtistFacts("instrumental", 2010), knowledge.facts("Ramin Djawadi"))
    }

    @Test
    fun `the language table matches the builder's`() {
        val builder = File("../../tools/research/build_artist_knowledge.py").readText()
        val list = Regex("""LANGUAGES = \[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(builder)
        val codes = Regex("\"([^\"]+)\"").findAll(assertNotNull(list).groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(codes, ArtistKnowledgePack.LANGUAGES)
    }
}
