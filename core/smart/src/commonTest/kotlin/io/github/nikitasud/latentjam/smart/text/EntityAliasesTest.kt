/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

internal class EntityAliasesTest {
    /** The pack names "Кино" and "Kino" as one entity, and knows "Björk" only in that exact spelling. */
    private val pack = object : KnownArtists {
        override fun canonical(matchKey: Long): Long? = null
        override fun canonicalOf(raw: String): Long? = when (raw) {
            "Кино", "Kino" -> 7L
            "Björk" -> 9L
            else -> null
        }
    }

    private fun keys(credit: String, known: KnownArtists) = ArtistSets.parse(credit, null, known).artists

    @Test
    fun `two names of one entity share a key`() {
        val union = EntityAliases(pack)
        assertEquals(keys("Кино", union), keys("Kino", union))
    }

    @Test
    fun `a spelling the pack does not know meets the one it knows`() {
        val union = EntityAliases(pack)
        // "Bjork" normalises like "Björk" but only the exact spelling is a pack name: the entity key alone kept them apart.
        assertNotEquals(keys("Björk", pack), keys("Bjork", pack))
        assertEquals(keys("Björk", union), keys("Bjork", union))
    }

    @Test
    fun `unknown names keep their own name hash`() {
        val union = EntityAliases(pack)
        assertNull(union.canonicalOf("Somebody"))
        assertEquals(keys("Somebody", KnownArtists.NONE), keys("Somebody", union))
    }
}
