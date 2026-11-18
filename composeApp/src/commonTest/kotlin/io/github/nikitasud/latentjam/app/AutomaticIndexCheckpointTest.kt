/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomaticIndexCheckpointTest {

    @Test
    fun `crash loss stays within one checkpoint window while batches remain eight tracks`() =
        runTest {
            val cadence = AutomaticIndexCheckpointCadence(tracksPerCheckpoint = 32)
            var processed = 0
            var persisted = 0
            var checkpoints = 0

            repeat(10) {
                // A process can die after the staged batch mutates memory and before afterBatch
                // gets control. Include that worst point in the bound, not just settled states.
                processed += 8
                assertTrue(processed - persisted <= 32)
                cadence.afterBatch(trackCount = 8) {
                    persisted = processed
                    checkpoints++
                }
                assertTrue(processed - persisted < 32)
            }

            assertEquals(2, checkpoints)
            assertEquals(64, persisted)
            assertEquals(80, processed)
        }

    @Test
    fun `the metadata step rewrites the text index a bounded number of times`() = runTest {
        // Every checkpoint of the text index writes the whole file, so their count is what a first analysis costs.
        val library = (0 until 10_000).map { TrackDescriptor(TrackId("t$it"), title = "Track $it") }
        var staged = 0
        var persisted = 0
        var checkpoints = 0

        stageMetadataInChunks(
            tracks = library,
            stage = { chunk -> staged += chunk.size; chunk.size },
            checkpoint = { persisted = staged; checkpoints++ },
            pause = {},
        )

        assertEquals(10_000, persisted)
        // 0.9.5 checkpointed every 32 tracks: 313 whole-file writes, about 2.5 GB for this library.
        assertTrue(checkpoints <= 8, "$checkpoints checkpoints")
    }

    @Test
    fun `the metadata step still checkpoints a library shorter than its window`() = runTest {
        var checkpoints = 0
        stageMetadataInChunks(
            tracks = (0 until 20).map { TrackDescriptor(TrackId("t$it")) },
            stage = { it.size },
            checkpoint = { checkpoints++ },
            pause = {},
        )
        assertEquals(1, checkpoints)
    }

    @Test
    fun `completion flush persists the short final window`() = runTest {
        val cadence = AutomaticIndexCheckpointCadence(tracksPerCheckpoint = 32)
        var processed = 0
        var persisted = 0

        repeat(5) {
            processed += 8
            cadence.afterBatch(trackCount = 8) { persisted = processed }
        }
        assertEquals(32, persisted)

        cadence.flush { persisted = processed }

        assertEquals(40, persisted)
    }

    @Test
    fun `cancellation boundary flush persists an incomplete checkpoint window`() = runTest {
        val cadence = AutomaticIndexCheckpointCadence(tracksPerCheckpoint = 32)
        var processed = 0
        var persisted = 0

        repeat(3) {
            processed += 8
            cadence.afterBatch(trackCount = 8) { persisted = processed }
        }
        assertEquals(0, persisted)

        // This is the same unconditional flush used from ensureAutomaticIndexing's finally block.
        cadence.flush { persisted = processed }

        assertEquals(24, persisted)
    }
}
