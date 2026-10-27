/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parity with tools/research/listenbrainz_eval/artist_sets.py (230a6214) on its hashed hard cases (test_artist_sets.py
 * CASES without MBIDs, against TEST_LIST), 3,000 real FIT credits against the shipped known list, and normalize() of
 * tricky strings (tools/research/ranker/export_artist_sets_golden.py).
 */
class ArtistSetsGoldenTest {
    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2
            } else append(s[i++])
        }
    }

    private fun keys(field: String) = if (field.isEmpty()) emptyList() else field.split(',').map(String::toLong)

    private fun golden(name: String) = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream(name))).reader(Charsets.UTF_8)
        .readLines().filter(String::isNotEmpty).map { it.split('\t') }

    private val lines by lazy { golden("/artist_sets_golden.tsv.gz") }

    /** artist_sets.py with LJ_AS2=1 (audit 07 #4 / #7 / #10), research HEAD 5faa2926. */
    private val linesAs2 by lazy { golden("/artist_sets_golden_as2.tsv.gz") }

    private fun check(kind: String, known: KnownArtists, as2: Boolean = false): Int {
        var n = 0
        for (f in (if (as2) linesAs2 else lines).filter { it[0] == kind }) {
            val set = ArtistSets.parse(unescape(f[1]), unescape(f[2]), known, as2 = as2)
            assertEquals(keys(f[3]), set.artists, "$kind '${f[1]}' / '${f[2]}' ${set.rules}")
            assertEquals(keys(f[4]).toSet(), set.featured, "$kind featured '${f[1]}'")
            n++
        }
        return n
    }

    @Test
    fun hardCasesMatchTheReference() {
        val map = lines.filter { it[0] == "list" }.associate { it[1].toLong() to it[2].toLong() }
        assertEquals(47, check("case", KnownArtists { map[it] }))
    }

    @Test
    fun realCreditsMatchTheReferenceOnTheKnownList() {
        val bytes = javaClass.getResourceAsStream("/artist_known.bin")!!.readBytes()
        val table = assertNotNull(KnownArtistTable.parse(AssetBytes.of(bytes)))
        assertEquals(3000, check("real", table))
    }

    @Test
    fun normalizeMatchesTheReference() {
        var n = 0
        for (f in lines.filter { it[0] == "norm" }) {
            assertEquals(unescape(f.getOrElse(2) { "" }), ArtistSets.normalize(unescape(f[1])), "normalize '${f[1]}'")
            n++
        }
        assertTrue(n >= 30)
    }

    @Test
    fun as2HardCasesMatchTheReference() {
        val map = linesAs2.filter { it[0] == "list" }.associate { it[1].toLong() to it[2].toLong() }
        assertEquals(47, check("case", KnownArtists { map[it] }, as2 = true))
    }

    @Test
    fun as2RealCreditsMatchTheReferenceOnTheKnownList() {
        val bytes = javaClass.getResourceAsStream("/artist_known.bin")!!.readBytes()
        val table = assertNotNull(KnownArtistTable.parse(AssetBytes.of(bytes)))
        assertEquals(3000, check("real", table, as2 = true))
    }
}
