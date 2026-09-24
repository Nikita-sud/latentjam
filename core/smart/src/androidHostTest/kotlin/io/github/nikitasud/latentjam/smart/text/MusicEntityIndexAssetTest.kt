/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Reads the index the app ships, so a rebuilt asset cannot silently stop parsing or lose relations. */
class MusicEntityIndexAssetTest {

    private val index: MusicEntityIndex by lazy {
        val file = File("../../androidApp/src/main/assets/ml/music_entities_250k.bin")
        assertTrue(file.isFile, "index asset missing at ${file.absolutePath}")
        assertNotNull(MusicEntityIndex.parse(file.readBytes()), "the shipped index must parse")
    }

    @Test
    fun `a member name finds their group in either script`() {
        assertTrue(index.matches("Виктор Цой", "Кино"))
        assertTrue(index.matches("Viktor Tsoi", "КИНО"))
        assertFalse(index.matches("Виктор Цой", "Сплин"))
    }

    @Test
    fun `artists below the MusicBrainz cut come from the Wikidata tail`() {
        val ids = index.resolve("Ion Suruceanu")
        assertTrue(ids.isNotEmpty() && ids.all { it >= 250_000 }, "ids ${ids.toList()}")
    }
}
