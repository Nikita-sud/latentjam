/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartChainMetadataGuardTest {

    @Test
    fun `missing titles do not make every untitled candidate look duplicated`() {
        val tracks = (0 until 4).map { row ->
            SmartTrack(
                id = TrackId(row.toString()),
                audio = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { it[row] = 1f },
                meta = TrackMeta(
                    title = if (row % 2 == 0) null else "   ",
                    artist = "artist$row",
                    album = null,
                    genre = null,
                    year = null,
                ),
            )
        }
        val snapshot = requireNotNull(SmartSnapshot.build(tracks))

        val result = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 3,
            timeFeatures = FloatArray(5),
        )

        assertEquals(3, result.rows.size)
    }
}
