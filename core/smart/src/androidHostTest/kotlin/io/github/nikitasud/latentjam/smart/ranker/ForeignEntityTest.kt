/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Latin-language pack entity is not the artist of a Cyrillic-titled track (an English "Sabi" for "Sabi & MIA BOYKA"). */
class ForeignEntityTest {
    private val knowledge by lazy {
        val ml = File("../../androidApp/src/main/assets/ml")
        val index = File(ml, "music_entities_250k.bin").readBytes()
        val pack = File(ml, "artist_knowledge.bin").readBytes()
        ArtistKnowledge(MusicEntityResolver { AssetBytes.of(index) }) { AssetBytes.of(pack) }
    }

    private val model = RankerModel.parse(
        """{"name": "t", "features": ["same_artist", "same_lang"], "mean": [0, 0], "sd": [1, 1], "weights": [1, 1]}""",
    )!!

    private fun track(id: String, title: String, artist: String, artists: List<String>) =
        TrackDescriptor(TrackId(id), title = title, artist = artist, artists = artists)

    @Test
    fun theRule() {
        assertTrue(ScriptLanguage.foreignEntity("en", "Базовый минимум"))
        assertFalse(ScriptLanguage.foreignEntity("en", "Choose Your Fighter"))
        assertFalse(ScriptLanguage.foreignEntity("ru", "Базовый минимум"))
        assertFalse(ScriptLanguage.foreignEntity(null, "Базовый минимум"))
        assertFalse(ScriptLanguage.foreignEntity("instrumental", "Колыбельная"))   // not a Latin-script language
        // a Latin title with a gloss in another script keeps its entity; a mostly-Cyrillic title with a Latin word does not
        assertFalse(ScriptLanguage.foreignEntity("en", "Hanami (花見)"))
        assertFalse(ScriptLanguage.foreignEntity("en", "『鯖江市日の出』Sabae Sunrise"))
        assertTrue(ScriptLanguage.foreignEntity("en", "Пьяный DJ"))
    }

    @Test
    fun aCyrillicTitleDoesNotTakeAnEnglishHomonymButALatinTitleStill() = runTest {
        // The pack resolves the first credit "Sabi" to an English entity.
        assertEquals("en", knowledge.facts("Sabi")?.language)
        val ru = track("1", "Базовый минимум", "Sabi & MIA BOYKA", listOf("Sabi", "MIA BOYKA"))
        val en = track("2", "Cali Love", "Sabi", listOf("Sabi"))
        val other = track("3", "Other", "Someone Else", listOf("Someone Else"))
        val ranker = SmartRanker(model, knowledge, null)
        val library = listOf(ru, en, other)
        ranker.plan(ru, library, library, exclude = emptySet(), length = 2)
        assertEquals(null, ranker.entityOf(ru.id).takeIf { it != null && it >= 0 })
        assertEquals(null, ranker.languageOf(ru.id))
        assertTrue((ranker.entityOf(en.id) ?: -1) >= 0)
        assertEquals("en", ranker.languageOf(en.id))
    }
}
