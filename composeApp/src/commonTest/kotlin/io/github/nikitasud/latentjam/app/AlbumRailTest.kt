/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumGroup
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class AlbumRailTest {

    private fun album(key: String, title: String?, artist: String = "Artist") = AlbumGroup(
        key = key,
        title = title,
        artist = artist,
        artworkUri = null,
        tracks = emptyList(),
    )

    @Test
    fun albumsUseOneGlobalAlphabetInsteadOfSeparateAlbumAndSingleBlocks() {
        val ordered = albumsInRailOrder(
            listOf(
                album("z", "Zebra"),
                album("a", "Aardvark"),
                album("b", "(Beta)"),
                album("unknown", null),
            ),
        )

        assertContentEquals(
            listOf("Aardvark", "(Beta)", "Zebra", null),
            ordered.map { it.title },
        )
    }

    @Test
    fun fullSpanHeadersProduceExactGridAnchors() {
        val sections = albumRailSections(
            listOf(
                album("b2", "Bravo Two"),
                album("a", "Alpha"),
                album("b1", "Bravo One"),
                album("c", "Charlie"),
            ),
        )

        assertContentEquals(listOf("A", "B", "C"), sections.map { it.bucket })
        // A: header + one card, B: header + two cards, then C's header.
        assertContentEquals(listOf(0, 2, 5), sections.map { it.emitStartIndex })
        assertEquals(listOf("Bravo One", "Bravo Two"), sections[1].albums.map { it.title })
    }
}
