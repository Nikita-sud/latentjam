/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class GenreSetsTest {
    @Test
    fun `electronic styles named with rock words stay electronic`() {
        assertEquals(setOf("dance", "electronic"), GenreSets.families("UK Garage"))
        assertEquals(setOf("dance", "electronic"), GenreSets.families("Happy Hardcore"))
        assertEquals(setOf("dance", "electronic"), GenreSets.families("Progressive House"))
        assertEquals(setOf("dance", "electronic"), GenreSets.families("Psytrance"))
        assertEquals(setOf("dance", "electronic"), GenreSets.families("Nightcore"))
        assertEquals(setOf("hiphop"), GenreSets.families("Hardcore Hip-Hop"))
        assertEquals(setOf("hiphop"), GenreSets.families("Emo Rap"))
        assertEquals(setOf("hiphop"), GenreSets.families("Horrorcore"))
        assertEquals(setOf("metal", "rock"), GenreSets.families("Symphonic Metal"))
        assertEquals(setOf("dance", "electronic"), GenreSets.families("Dub Techno"))
        assertFalse("classical" in GenreSets.families("New Romantic"))
        assertTrue(GenreSets.overlap(GenreSets.families("Progressive House"), GenreSets.families("Rock")) == 0f)
    }

    @Test
    fun `the rock words still mean rock on their own`() {
        assertEquals(setOf("rock"), GenreSets.families("Garage Rock"))
        assertEquals(setOf("rock"), GenreSets.families("Progressive Rock"))
        assertEquals(setOf("punk", "rock"), GenreSets.families("Hardcore Punk"))
    }

    @Test
    fun `sub-genres carry their parent and unknown values keep themselves`() {
        assertEquals(setOf("metal", "rock"), GenreSets.families("Metalcore"))
        assertEquals(setOf("vaporsomething"), GenreSets.families("Vaporsomething"))
        assertEquals(1f, GenreSets.overlap(setOf("rock"), setOf("metal", "rock")))
    }
}
