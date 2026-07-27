/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typicality is a track's projection onto the library's mean direction — how much of this library
 * sounds like it. [SmartSnapshot] subtracts that mean to build the centered space every chain
 * cosine is measured in, which discards the axis entirely.
 *
 * It should not be discarded silently. Scored against 717 real listening transitions pulled from a
 * device, the landing track's typicality predicts whether the user kept it at AUC 0.633 on its own,
 * with keep rate rising 0.393 → 0.550 → 0.644 across terciles — a larger behavioural effect than
 * centered cosine, which manages 0.617. The mean direction carries only 1.6 % of the embedding's
 * variance, which is presumably why removing it looked free; variance is not the same as signal.
 *
 * So the snapshot keeps the axis and the chain may weight it explicitly.
 */
internal class TypicalityTest {

    /** Eight tracks packed around one direction, plus two isolated outliers. */
    private fun clusteredLibrary(): List<SmartTrack> = (0 until 10).map { row ->
        val audio = FloatArray(PredictorRuntime.EMBEDDING_DIM)
        if (row < 8) {
            audio[0] = 1f
            audio[100 + row] = 0.3f
        } else {
            audio[500 + row] = 1f
        }
        val norm = sqrt(audio.fold(0f) { acc, v -> acc + v * v })
        for (i in audio.indices) audio[i] /= norm
        SmartTrack(
            id = TrackId(row.toString()),
            audio = audio,
            meta = TrackMeta(
                title = "title$row",
                artist = "artist$row",
                album = null,
                genre = null,
                year = null,
            ),
        )
    }

    @Test
    fun `typicality is z-scored across the library`() {
        val snapshot = requireNotNull(SmartSnapshot.build(clusteredLibrary()))

        val values = snapshot.typicality
        assertEquals(snapshot.size, values.size)
        val mean = values.sum() / values.size
        val sd = sqrt(values.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / values.size)
        assertTrue(abs(mean) < 1e-3f, "expected zero mean, got $mean")
        assertTrue(abs(sd - 1f) < 1e-3f, "expected unit deviation, got $sd")
    }

    @Test
    fun `tracks inside the dense cluster are more typical than isolated outliers`() {
        val snapshot = requireNotNull(SmartSnapshot.build(clusteredLibrary()))

        val clustered = (0 until 8).map { snapshot.typicality[it] }
        val outliers = (8 until 10).map { snapshot.typicality[it] }

        assertTrue(
            clustered.min() > outliers.max(),
            "cluster members $clustered must outrank outliers $outliers",
        )
    }

    @Test
    fun `a weight of zero leaves the queue byte-identical to the shipped chain`() {
        val snapshot = requireNotNull(SmartSnapshot.build(clusteredLibrary()))

        val shipped = SmartChain(snapshot, runtime = null).build(
            seedId = TrackId("0"),
            length = 5,
            timeFeatures = FloatArray(5),
        )
        val explicitZero = SmartChain(snapshot, runtime = null, typicalityWeight = 0f).build(
            seedId = TrackId("0"),
            length = 5,
            timeFeatures = FloatArray(5),
        )

        assertEquals(shipped.rows.toList(), explicitZero.rows.toList())
    }

    @Test
    fun `a dominant weight pulls the most typical candidate to the front of the queue`() {
        val snapshot = requireNotNull(SmartSnapshot.build(clusteredLibrary()))

        val result = SmartChain(snapshot, runtime = null, typicalityWeight = 50f).build(
            seedId = TrackId("8"),   // seed is an outlier, so untilted the queue would stay out here
            length = 3,
            timeFeatures = FloatArray(5),
        )

        val first = result.rows.first()
        val mostTypical = (0 until snapshot.size)
            .filter { it != snapshot.rowOf(TrackId("8")) }
            .maxBy { snapshot.typicality[it] }
        assertEquals(mostTypical, first, "an overwhelming weight must select on typicality alone")
    }
}
