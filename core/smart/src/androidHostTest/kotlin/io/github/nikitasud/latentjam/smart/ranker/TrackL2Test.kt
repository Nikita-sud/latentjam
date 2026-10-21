/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PLAN_L2's track fields match the research functions (track_l2_golden.tsv: taxonomy.family_of_tag, album_key). */
class TrackL2Test {
    private val taxonomy = assertNotNull(GenreFamilies.parse(File("../../androidApp/src/main/assets/ml/genre_families.json").readText()))

    @Test
    fun familiesAndAlbumKeysMatchTheResearch() {
        var checked = 0
        for (line in javaClass.getResource("/track_l2_golden.tsv")!!.readText().lines()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            when (p[0]) {
                "family" -> assertEquals(p[2], taxonomy.familyOfTag(p[1]).orEmpty(), p[1])
                "album" -> assertEquals(p[4], AlbumKeys.albumKey(p[1], p[2], p[3]).replace('\t', '|'), p[1])
            }
            checked++
        }
        assertEquals(103, checked)
        assertEquals(14, taxonomy.families.size)
    }

    @Test
    fun variousArtistsSpellings() {
        assertTrue(AlbumKeys.isVarious("Various Artists"))
        assertTrue(AlbumKeys.isVarious("VA"))
        assertTrue(AlbumKeys.isVarious("Разные исполнители"))
        assertFalse(AlbumKeys.isVarious("Vanessa"))
        assertFalse(AlbumKeys.isVarious(null))
    }

    @Test
    fun titleFlagsUsePythonWordBoundaries() {
        // reference.TITLE_FLAG_RE on the lower-cased title (Python's Unicode \b).
        assertTrue(RulePenalties.titleFlagged("Intro"))
        assertTrue(RulePenalties.titleFlagged("Skit (Interlude)"))
        assertTrue(RulePenalties.titleFlagged("Song (Short)"))
        assertTrue(RulePenalties.titleFlagged("Интро"))
        assertTrue(RulePenalties.titleFlagged("часть 2: скит"))
        assertFalse(RulePenalties.titleFlagged("Introduction"))
        assertFalse(RulePenalties.titleFlagged("Reprised"))
        assertFalse(RulePenalties.titleFlagged("Интровертный"))
        assertFalse(RulePenalties.titleFlagged(null))
    }

    @Test
    fun theShippedInstrumentalnessHeadParses() {
        val heads = assertNotNull(LinearHeads.parse(File("../../androidApp/src/main/assets/ml/heads.json").readText()))
        assertEquals(setOf("instrumentalness"), heads.names)
        val v = FloatArray(960) { 1f / kotlin.math.sqrt(960f) }
        assertTrue(heads.value("instrumentalness", v)!!.isFinite())
    }

    @Test
    fun theInstrumentalnessHeadMatchesNumpy() {
        // heads_fast32.json's formula in numpy on the tagger golden's (public) vectors, L2-normalised as the index stores them.
        val heads = assertNotNull(LinearHeads.parse(File("../../androidApp/src/main/assets/ml/heads.json").readText()))
        val expected = listOf(0.6755881633445623, 0.6108707534541727, 0.7410859069679542, 0.57757338796027, 0.6572518849331415, 0.7924251286062861)
        val rows = javaClass.getResource("/audio_tagger_golden.tsv")!!.readText().lines().filter(String::isNotEmpty)
        for ((i, line) in rows.withIndex()) {
            val x = line.split('\t')[0].split(',').map(String::toFloat).toFloatArray()
            val unit = assertNotNull(SmartRanker.normalized(x))
            assertEquals(expected[i], heads.value("instrumentalness", unit)!!, 1e-6)
        }
    }
}
