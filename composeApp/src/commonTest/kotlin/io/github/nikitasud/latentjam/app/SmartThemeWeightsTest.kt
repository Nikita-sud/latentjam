/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.Genres
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The queue theme's genre terms ([SMART_THEME_WEIGHTS], ThemePool's family Jaccard and family any-overlap) with tags
 * that list several genres ("Folk; Rock; Indie"): a track sharing a genre family with the queue's start must never score
 * below one sharing none, whatever share of the families the two have in common. A negative any-overlap weight broke
 * this below a Jaccard of 0.39 and pushed rich-tag queues toward disjoint genres (ORACLE_MULTIGENRE, 2026-11-04).
 */
class SmartThemeWeightsTest {
    private val weights = assertNotNull(SMART_THEME_WEIGHTS)

    /** The theme score of the genre terms alone; a pair sharing no family scores 0 on both. */
    private fun genreTerms(jaccard: Float, any: Float): Float = weights[JACCARD] * jaccard + weights[ANY] * any

    @Test
    fun sharingAGenreFamilyNeverScoresBelowSharingNone() {
        assertEquals(8, weights.size)
        // A negative any-overlap weight breaks the property for some long enough list, whatever the Jaccard weight.
        assertTrue(weights[ANY] >= 0f, "any-overlap weight ${weights[ANY]}")
        // Every Jaccard a pair sharing at least one family can have, up to sixteen families a side.
        for (a in 1..MAX_FAMILIES) for (b in 1..MAX_FAMILIES) for (shared in 1..minOf(a, b)) {
            val jaccard = shared.toFloat() / (a + b - shared)
            assertTrue(
                genreTerms(jaccard, any = 1f) >= genreTerms(0f, any = 0f),
                "$shared of $a and $b families shared (Jaccard $jaccard) scores ${genreTerms(jaccard, 1f)}, below sharing none",
            )
        }
    }

    @Test
    fun aMultiGenreTagThatSharesOneFamilyScoresAtLeastNone() {
        val seed = Genres.families("Folk; Rock; Indie; Electronic")
        val shares = Genres.families("Rock; Hip-Hop; Jazz")
        val disjoint = Genres.families("Classical")
        val shared = seed.count { it in shares }
        assertTrue(shared >= 1 && seed.none { it in disjoint }, "families: $seed / $shares / $disjoint")
        val jaccard = shared.toFloat() / (seed.size + shares.size - shared)
        assertTrue(genreTerms(jaccard, any = 1f) >= genreTerms(0f, any = 0f), "Jaccard $jaccard scores below a disjoint genre")
    }

    private companion object {
        /** Indices in ThemePool.FEATURE_NAMES. */
        const val JACCARD = 2
        const val ANY = 3
        // One genre value can expand to several families ("dance pop" is three): room for long lists.
        const val MAX_FAMILIES = 16
    }
}
