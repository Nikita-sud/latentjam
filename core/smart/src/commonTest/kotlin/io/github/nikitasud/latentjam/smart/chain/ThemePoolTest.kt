/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The queue theme: seed-relative features, a ranking from them, and a channel that reaches past the sound neighbours. */
internal class ThemePoolTest {

    private fun unit(vararg parts: Pair<Int, Float>) = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { v ->
        for ((axis, value) in parts) v[axis] += value
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= norm
    }

    /** Row 0 the seed (Russian pop, 2005); rows 1-30 sound like it (other genre, era, script); rows 31-36 sound far but share
     * its genre, era and language; rows 37+ filler. */
    private fun library(): SmartSnapshot {
        val tracks = buildList {
            add(SmartTrack(TrackId("0"), unit(0 to 1f), meta = TrackMeta("Звезда", "Певица", null, "Russian Pop", 2005)))
            for (i in 1..30) add(SmartTrack(TrackId("$i"), unit(0 to 1f, (10 + i) to 0.3f), meta = TrackMeta("Song $i", "Band $i", null, "Metal", 1985)))
            for (j in 31..36) add(SmartTrack(TrackId("$j"), unit(0 to 0.1f, (100 + j) to 1f), meta = TrackMeta("Песня $j", "Артист $j", null, "Russian Pop", 2007)))
            for (k in 37..140) add(SmartTrack(TrackId("$k"), unit(0 to -1f, (200 + k) to 1f), meta = TrackMeta("Filler $k", "F$k", null, "Jazz", 1950)))
        }
        return requireNotNull(SmartSnapshot.build(tracks))
    }

    private val metadataTheme = floatArrayOf(0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f)

    @Test
    fun `features describe each row against the reference`() {
        val snapshot = library()
        val f = ThemePool.features(snapshot, 0)
        val k = ThemePool.FEATURES
        val close = snapshot.rowOf(TrackId("1"))
        val kin = snapshot.rowOf(TrackId("31"))
        assertTrue(f[close * k] > f[kin * k], "audio z: the sound neighbour is closer")
        assertEquals(1f, f[kin * k + 3], "shared genre family")
        assertEquals(0f, f[close * k + 3], "no shared genre family")
        assertEquals(1f, f[kin * k + 4], "same script language")
        assertEquals(1f, f[kin * k + 5], "within ten years")
        assertEquals(0f, f[close * k + 5], "twenty years apart")
        assertEquals(0.3f, f[kin * k + 7], "unknown energy is neutral")
    }

    @Test
    fun `the ranking follows the weights`() {
        val snapshot = library()
        val ranking = ThemePool(metadataTheme).ranking(snapshot, 0, BooleanArray(snapshot.size) { true })
        val top = ranking.take(6).map { snapshot.tracks[it].id.value.toInt() }.toSet()
        assertEquals((31..36).toSet(), top)
    }

    @Test
    fun `the theme channel brings far-sounding kin into the pool and the queue`() {
        val snapshot = library()
        fun queue(theme: ThemePool?) = SmartChain(
            snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true, theme = theme),
        ).build(TrackId("0"), 12, FloatArray(5)).rows.map { snapshot.tracks[it].id.value.toInt() }
        val plain = queue(null)
        val themed = queue(ThemePool(metadataTheme, slots = 10, bonus = 3f))
        assertTrue(plain.none { it in 31..36 }, "sound alone never reaches the kin: $plain")
        assertTrue(themed.count { it in 31..36 } >= 3, "the theme brings them in: $themed")
    }

    @Test
    fun `no theme changes nothing`() {
        val snapshot = library()
        assertEquals(
            SmartChain(snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true)).build(TrackId("0"), 12, FloatArray(5)),
            SmartChain(snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true, theme = null)).build(TrackId("0"), 12, FloatArray(5)),
        )
    }
}
