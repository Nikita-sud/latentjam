/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TextMetadataTest {

    @Test
    fun `genre bait in a title cannot enter the trusted text embedding`() {
        val clean = TextEncoder.metadataString("Soul", "Example Artist", "Ordinary Song", 1972)
        val bait = TextEncoder.metadataString("Soul", "Example Artist", "Hard Techno Mix", 1972)

        assertEquals("Soul; Example Artist; 1972", clean)
        assertEquals(clean, bait)
    }

    @Test
    fun `the original year outranks the edition year and a language tag becomes a word`() {
        assertEquals(
            "Pop; 5sta Family; 2017; russian",
            TextEncoder.metadataString("Pop", "5sta Family", "Снова вместе", 2017, originalYear = 2017, language = "rus"),
        )
        // A 2012 remaster of a 1987 song is a 1987 song.
        assertEquals(
            "Rock; Band; 1987",
            TextEncoder.metadataString("Rock", "Band", "Song", 2012, originalYear = 1987),
        )
        assertEquals("english", TextLanguage.word("en-US", null, null))
        assertEquals("instrumental", TextLanguage.word("zxx", null, null))
        assertEquals("russian", TextLanguage.word("eng; rus".let { "rus; eng" }, null, null))
    }

    @Test
    fun `without a tag the script speaks and latin stays silent`() {
        // Cyrillic anywhere in title or artist is Russian, kana or kanji Japanese, as in the chain.
        assertEquals("Pop; Кино; 1988; russian", TextEncoder.metadataString("Pop", "Кино", "Группа крови", 1988))
        assertEquals("Anime OST; 2009; japanese", TextEncoder.metadataString("Anime OST", null, "紅蓮の弓矢", 2009))
        // A Romanian track has no tag and Latin script: no word, rather than a wrong one.
        assertEquals("Pop; Akcent; 2006", TextEncoder.metadataString("Pop", "Akcent", "Buchet de trandafiri", 2006))
        // A title's script may name the language, but its words still never enter the string.
        assertEquals("Soul; Example Artist; 1972", TextEncoder.metadataString("Soul", "Example Artist", "Hard Techno Mix", 1972))
        assertEquals("Soul; Example Artist; 1972", TextEncoder.metadataString("Soul", "Example Artist", null, 1972, language = "not a language"))
    }
}
