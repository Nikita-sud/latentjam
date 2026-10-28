/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.personal

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** PLAN_PERSONAL §7, store budget: 10k songs and 100k listens build in ≤ 2 s and retain ≤ 2 MB. */
class PersonalBudgetTest {
    @Test
    fun `ten thousand songs and a hundred thousand listens fit the budget`() {
        val songs = 10_000
        val library = (0 until songs).map {
            PersonalTrack(TrackId("t$it"), "artist${it / 12}|song$it", listOf("artist${it / 12}"), album = "album${it / 12}",
                trackNumber = it % 12 + 1, durationMs = 210_000L)
        }
        // A listener: sessions of eight to fifteen songs, wandering within neighbourhoods of the library, with picks, skips,
        // SMART's own continuations and an imported first half.
        val random = Random(42)
        val listens = ArrayList<PersonalListen>(100_000)
        var at = 1_500_000_000_000L
        var current = random.nextInt(songs)
        while (listens.size < 100_000) {
            val imported = listens.size < 50_000
            repeat(8 + random.nextInt(8)) {
                current = ((current + random.nextInt(-40, 41)) % songs + songs) % songs
                if (random.nextInt(20) == 0) current = random.nextInt(songs)
                val skipped = !imported && random.nextInt(6) == 0
                val start = when {
                    imported -> PersonalStart.IMPORTED
                    random.nextInt(4) == 0 -> PersonalStart.PICK
                    else -> PersonalStart.AUTO
                }
                val heard = if (skipped) 8_000L else 210_000L
                listens += PersonalListen(TrackId("t$current"), at, if (imported) null else heard, !skipped, skipped, start,
                    smartSourced = start == PersonalStart.AUTO && random.nextBoolean())
                at += heard
            }
            at += 4 * 3_600_000L
        }
        PersonalModel.build(listens.take(5_000), library) // warm the JIT
        val started = System.nanoTime()
        val model = PersonalModel.build(listens, library)
        val ms = (System.nanoTime() - started) / 1_000_000
        val bytes = model.retainedBytes()
        println("personal store: ${listens.size} listens over $songs songs: built in $ms ms, retains ${bytes / 1024} KiB, ${model.sizes()}")
        assertTrue(ms <= 2_000, "build $ms ms")
        assertTrue(bytes <= 2L * 1024 * 1024, "retained $bytes bytes")
    }
}
