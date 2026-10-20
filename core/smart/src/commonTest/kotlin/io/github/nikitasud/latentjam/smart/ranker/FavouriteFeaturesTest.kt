/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The FULL model's is_favourite / favourited_days_ago: computed from the listener's hearts, weighted by the asset. */
class FavouriteFeaturesTest {
    private val day = 86_400_000L
    private val seedMs = 100 * day
    private val fav = RankerFeatures.NAMES.indexOf("is_favourite")
    private val ago = RankerFeatures.NAMES.indexOf("favourited_days_ago")

    @Test
    fun columnsFollowTheHearts() {
        val hearts = mapOf(TrackId("a") to seedMs - 3 * day, TrackId("b") to null, TrackId("c") to seedMs + day)
        val h = PersonalHistory(emptyList(), seedMs, favourites = hearts) { "" }
        val ids = listOf("a", "b", "c", "d").map(::TrackId)
        val rows = RankerFeatures.of(RankerRow(), ids.map { RankerRow(favourite = h.isFavourite(it), favouritedDaysAgo = h.favouritedDaysAgo(it)) })
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0), rows.map { it[fav] })
        // b's heart time is unknown, c's lies after the seed (a clock change): both read 0.
        assertEquals(listOf(3.0, 0.0, 0.0, 0.0), rows.map { it[ago] })
        // Appended after reference.py's first 32 columns and its pop_known / is_favourite, which keep their indexes.
        assertEquals(listOf("pop_known", "is_favourite", "favourited_days_ago"), RankerFeatures.NAMES.drop(32))
    }

    private fun track(id: String) = TrackDescriptor(TrackId(id), title = id, artist = "Artist $id")

    private fun model(weight: Double) = RankerModel.parse(
        """{"features": ["same_artist", "is_favourite", "favourited_days_ago"], "mean": [0, 0, 0], "sd": [1, 1, 1],
           "weights": [0, $weight, 0], "anchor": "seed"}""",
    )!!

    @Test
    fun theWeightComesFromTheAssetAndHabitsOffMeansNoHearts() = runTest {
        val seed = track("seed")
        val lib = listOf(seed) + (1..30).map { track("t$it") }
        val hearts = mapOf(TrackId("t17") to seedMs - day)
        fun request(personal: Boolean) = PlanRequest(
            seed, lib, lib, 5, personal = personal,
            history = PersonalHistory(emptyList(), seedMs, favourites = if (personal) hearts else emptyMap()) { "" },
        )
        // A positive weight puts the favourite first; with the asset's default weight 0 the plan does not change.
        assertEquals("t17", SmartRanker(model(5.0), null, null, fullModel = model(5.0)).plan(request(true)).ids.first().value)
        val zero = SmartRanker(model(0.0), null, null, fullModel = model(0.0))
        assertEquals(zero.plan(request(false)).ids, SmartRanker(model(0.0), null, null, fullModel = model(0.0)).plan(request(true)).ids)
        // Habits off: the chooser passes no hearts, so even a weighted model cannot see them.
        val off = SmartRanker(model(5.0), null, null, fullModel = model(5.0)).plan(request(false)).ids
        assertEquals(SmartRanker(model(0.0), null, null).plan(request(false)).ids, off)
    }
}
