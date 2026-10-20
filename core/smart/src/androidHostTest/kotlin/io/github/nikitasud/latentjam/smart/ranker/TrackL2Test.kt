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
        assertEquals(61, checked)
        assertEquals(12, taxonomy.families.size)
    }

    @Test
    fun variousArtistsSpellings() {
        assertTrue(AlbumKeys.isVarious("Various Artists"))
        assertTrue(AlbumKeys.isVarious("VA"))
        assertTrue(AlbumKeys.isVarious("Разные исполнители"))
        assertFalse(AlbumKeys.isVarious("Vanessa"))
        assertFalse(AlbumKeys.isVarious(null))
    }
}
