/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class QueueClassesTest {

    private val genres = listOf(
        "Russian Pop", // 0
        "Children's Music", // 1
        "Internet Meme", // 2
        "Russian Chanson", // 3
        "Soundtrack; Video Game Music", // 4
        "Ambient; Drone", // 5
        "Eurodance; Dance-Pop", // 6
    )

    private fun snapshot(): SmartSnapshot = requireNotNull(
        SmartSnapshot.build(
            genres.mapIndexed { row, genre ->
                val audio = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { it[row] = 1f }
                SmartTrack(TrackId("$row"), audio, meta = TrackMeta("t$row", "a$row", null, genre, null))
            },
        ),
    )

    private fun paying(seed: Int): Set<String> {
        val snapshot = snapshot()
        val rows = QueueClasses(1f).penalised(snapshot, snapshot.rowOf(TrackId("$seed")))
        return rows.indices.filter { rows[it] }.map { genres[snapshot.tracks[it].id.value.toInt()] }.toSet()
    }

    @Test
    fun `a pop seed holds back children songs and memes and chanson but not dance music`() {
        assertEquals(setOf("Children's Music", "Internet Meme", "Russian Chanson"), paying(0))
    }

    @Test
    fun `memes stay welcome after a soundtrack seed`() {
        assertEquals(setOf("Children's Music", "Russian Chanson"), paying(4))
    }

    @Test
    fun `dance music pays after a calm seed and memes do not`() {
        assertEquals(setOf("Children's Music", "Russian Chanson", "Eurodance; Dance-Pop"), paying(5))
    }

    @Test
    fun `the seed kind never pays`() {
        assertEquals(setOf("Children's Music", "Internet Meme"), paying(3))
    }
}
