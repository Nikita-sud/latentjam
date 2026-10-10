/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The packs as Android reads them — mapped from the file, not copied — answer exactly as bytes in memory do. */
class MappedAssetBytesTest {

    private fun shipped(asset: String): File =
        File("../../androidApp/src/main/assets/$asset").also { assertTrue(it.isFile, "asset missing at ${it.absolutePath}") }

    /** Mapped and the channel closed before anyone reads it, as the app does. */
    private fun mapped(file: File): AssetBytes =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).asAssetBytes()
        }

    private val index = shipped(MUSIC_ENTITY_ASSET)
    private val pack = shipped(ARTIST_KNOWLEDGE_ASSET)

    /** Known artists, members, other scripts, collaborations, ambiguous words and strangers. */
    private val names = listOf(
        "ABBA", "Кино", "КИНО", "Виктор Цой", "Viktor Tsoi", "Ion Suruceanu", "Ramin Djawadi", "The Beatles",
        "Earth, Wind & Fire", "Marvin Gaye & Tammi Terrell", "Hans Zimmer", "Girls²", "Daft Punk", "澤野弘之",
        "Silver", "Unknown", "Traditional", "LatentJam nonexistent smoke artist 91d02", "", "  ",
    )

    @Test
    fun `a mapped entity index resolves every name as a copied one does`() {
        val copied = assertNotNull(MusicEntityIndex.parse(index.readBytes()))
        val viaMapping = assertNotNull(MusicEntityIndex.parse(mapped(index)))
        for (name in names) assertContentEquals(copied.resolve(name), viaMapping.resolve(name), name)
        assertTrue(viaMapping.matches("Виктор Цой", "Кино"))
    }

    @Test
    fun `a mapped knowledge pack gives the same facts and descriptors`() {
        val indexBytes = index.readBytes()
        val packBytes = pack.readBytes()
        val copied = ArtistKnowledge(MusicEntityResolver { AssetBytes.of(indexBytes) }) { AssetBytes.of(packBytes) }
        val viaMapping = ArtistKnowledge(MusicEntityResolver { mapped(index) }) { mapped(pack) }
        for (name in names) {
            assertEquals(copied.facts(name), viaMapping.facts(name), name)
            assertContentEquals(copied.descriptor(name), viaMapping.descriptor(name), name)
        }
        assertEquals(ArtistFacts("en", 1980), viaMapping.facts("ABBA"))
    }

    @Test
    fun `the build stores uncompressed every pack the app maps`() {
        // A compressed entry cannot be mapped: the app would quietly fall back to a heap copy.
        val build = File("../../androidApp/build.gradle.kts").readText()
        val noCompress = Regex("""noCompress \+= listOf\(([^)]*)\)""").find(build)
        val stored = Regex("\"([^\"]+)\"").findAll(assertNotNull(noCompress, "no noCompress list").groupValues[1])
            .map { it.groupValues[1] }.toSet()
        for (asset in listOf(MUSIC_ENTITY_ASSET, ARTIST_KNOWLEDGE_ASSET)) {
            assertTrue(asset.substringAfterLast('/') in stored, "$asset is compressed in the APK")
        }
    }

    /** A stream that reports [reported] bytes available up front, whatever it really holds. */
    private class Reporting(bytes: ByteArray, private val reported: Int) : ByteArrayInputStream(bytes) {
        private var asked = false

        override fun available(): Int = if (asked) super.available() else reported.also { asked = true }
    }

    private val contents = ByteArray(10_000) { (it * 31 + 7).toByte() }

    @Test
    fun `readExactly reads a stream of the length it reports`() {
        assertContentEquals(contents, Reporting(contents, contents.size).readExactly())
    }

    @Test
    fun `readExactly reads to the end a stream longer than it reports`() {
        assertContentEquals(contents, Reporting(contents, 100).readExactly())
        assertContentEquals(contents, Reporting(contents, 0).readExactly())
    }

    @Test
    fun `readExactly stops where a stream shorter than it reports ends`() {
        assertContentEquals(contents, Reporting(contents, contents.size + 500).readExactly())
        assertContentEquals(ByteArray(0), Reporting(ByteArray(0), 0).readExactly())
    }
}
