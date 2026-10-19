/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-for-byte parity with the ListenBrainz evaluation on 36 FIT queries
 * (tools/research/habit_slots/export_golden.py): score.py's `transition` baseline (top 20) and the
 * SLOTS5 queue over the harness's chain. The path without the app's deviations: every listen may
 * count as a transition's second half (the reference has no origins), and the tie key is the
 * reference's own salted random key, given as its rank.
 */
class HabitSlotsGoldenTest {

    private class Case(
        val key: String,
        val size: Int,
        val seed: Int,
        val seedMs: Long,
        val artists: List<String>,
        val ranks: IntArray,
        val events: List<Pair<Int, Long>>,
        val chain: List<Int>,
        val transition: List<Int>,
        val slots5: List<Int>,
    )

    private fun ints(field: String): List<Int> =
        if (field.isEmpty()) emptyList() else field.split(',').map(String::toInt)

    private fun cases(): List<Case> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/habit_slots_golden.tsv.gz"))
        val lines = GZIPInputStream(stream).bufferedReader().readLines().filter(String::isNotEmpty)
        return lines.chunked(7).map { block ->
            val fields = block.map { it.split('\t', limit = 2) }.associate { it[0] to it.getOrElse(1) { "" } }
            val head = fields.getValue("case").split('\t')
            Case(
                key = head[0],
                size = head[1].toInt(),
                seed = head[2].toInt(),
                seedMs = head[3].toLong(),
                artists = fields.getValue("artists").split('\t'),
                ranks = ints(fields.getValue("ranks")).toIntArray(),
                events = fields.getValue("events").takeIf(String::isNotEmpty)?.split(',')?.map {
                    val (row, ts) = it.split(':')
                    row.toInt() to ts.toLong()
                }.orEmpty(),
                chain = ints(fields.getValue("chain")),
                transition = ints(fields.getValue("transition")),
                slots5 = ints(fields.getValue("slots5")),
            )
        }
    }

    private fun id(row: Int) = TrackId("r$row")

    @Test
    fun transitionListAndSlotsMatchTheReference() {
        val all = cases()
        assertEquals(36, all.size)
        for (case in all) {
            assertEquals(case.size, case.artists.size, case.key)
            val rowOf = (0 until case.size).associateBy(::id)
            val listens = case.events.map { (row, ts) -> HabitListen(id(row), ts) }
            val personal = HabitSlots.transitionList(
                candidates = (0 until case.size).map(::id),
                seed = id(case.seed),
                seedMs = case.seedMs,
                listens = listens,
                artistKey = { case.artists[rowOf.getValue(it)] },
                tieKey = { case.ranks[rowOf.getValue(it)].toDouble() },
            )
            assertEquals(case.transition, personal.take(20).map { rowOf.getValue(it) }, case.key)
            // The app ranks only heard candidates (unheard ones sort last anyway): the same top five here.
            val heard = listens.mapTo(HashSet()) { it.trackId }
            val heardOnly = HabitSlots.transitionList(
                candidates = (0 until case.size).map(::id).filter { it in heard },
                seed = id(case.seed),
                seedMs = case.seedMs,
                listens = listens,
                artistKey = { case.artists[rowOf.getValue(it)] },
                tieKey = { case.ranks[rowOf.getValue(it)].toDouble() },
                limit = HabitSlots.PERSONAL_SLOTS,
            )
            assertEquals(case.transition.take(5), heardOnly.map { rowOf.getValue(it) }, case.key)
            val slots = HabitSlots.interleave(personal, case.chain.map(::id))
            // The reference keeps ten rows after the window; the app keeps the whole chain.
            assertEquals(case.slots5, slots.take(case.slots5.size).map { rowOf.getValue(it) }, case.key)
            assertTrue(slots.size >= case.slots5.size, case.key)
        }
    }
}
