/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/** Test packs in the version-2 layout that tools/research/build_artist_knowledge.py writes. */
internal object KnowledgePackBytes {

    /**
     * Dimension 4, two sub-spaces of two, two centroids each: [1, 0] and [0, 1].
     * - entity 0, "Alpha": codes (0, 1), sings Romanian, 1980s
     * - entity 1, "Beta": codes (1, 0), sings English, decade unknown
     * - entity 2, "Gamma": confidence 0, so its Russian and 1990s stay hidden
     */
    fun tiny(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += value.toByte() }
        fun u16(value: Int) { u8(value and 0xff); u8(value shr 8) }
        fun u32(value: Int) { u16(value and 0xffff); u16(value ushr 16) }
        "LJKNOW1\u0000".encodeToByteArray().forEach { out += it }
        u32(2); u16(4); u8(2); u16(2); u32(3)
        repeat(2) { u16(0x3C00); u16(0x0000); u16(0x0000); u16(0x3C00) } // per sub-space: [1, 0], [0, 1]
        fun record(first: Int, second: Int, name: String, language: String?, decade: Int?, confidence: Int) {
            u8(first); u8(second); u16(ArtistKnowledgePack.fingerprint(name))
            u8(language?.let { ArtistKnowledgePack.LANGUAGES.indexOf(it) + 1 } ?: 0); u8(0)
            u8(decade?.let { (it - 1000) / 10 + 1 } ?: 0)
            u8(confidence)
        }
        record(0, 1, "Alpha", "ro", 1980, 200)
        record(1, 0, "Beta", "en", null, 200)
        record(0, 0, "Gamma", "ru", 1990, 0)
        return out.toByteArray()
    }
}
