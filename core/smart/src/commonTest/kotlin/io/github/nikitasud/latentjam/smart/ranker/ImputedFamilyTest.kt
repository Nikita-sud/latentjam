/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import kotlin.test.Test
import kotlin.test.assertEquals

/** An imputed family enters same_family as the expected match from the tagger's calibrated probabilities. */
class ImputedFamilyTest {
    private val sameFamily = RankerFeatures.NAMES.indexOf("same_family")
    private val familyKnown = RankerFeatures.NAMES.indexOf("family_known")
    private val imputed = RankerFeatures.NAMES.indexOf("family_imputed")

    private fun row(family: String?, p: Map<String, Double>? = null) =
        RankerRow(family = family, familyProbabilities = p, familyImputed = p != null)

    @Test
    fun knownImputedAndBothImputed() {
        val anchor = row("rock")
        val rows = listOf(
            row("rock"), row("pop"),
            row("rock", mapOf("rock" to 0.6, "pop" to 0.3)),
            row(null, mapOf("rock" to 0.1, "pop" to 0.2)), // the tagger's "other": family null, probabilities kept
            row(null),
        )
        val f = RankerFeatures.of(anchor, rows)
        assertEquals(listOf(1.0, 0.0, 0.6, 0.1, 0.0), f.map { it[sameFamily] })
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 0.0), f.map { it[familyKnown] })
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0, 0.0), f.map { it[imputed] })
        val both = RankerFeatures.of(row("pop", mapOf("rock" to 0.5, "pop" to 0.4)), listOf(row("rock", mapOf("rock" to 0.2, "pop" to 0.7))))
        assertEquals(0.5 * 0.2 + 0.4 * 0.7, both[0][sameFamily], 1e-12)
        assertEquals(0.4, RankerFeatures.of(row("pop", mapOf("rock" to 0.5, "pop" to 0.4)), listOf(row("pop")))[0][sameFamily], 1e-12)
    }
}
