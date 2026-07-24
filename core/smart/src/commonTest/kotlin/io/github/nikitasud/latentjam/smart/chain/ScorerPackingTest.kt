/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the pure tensor packing for the semantics-aware scorer (`scoring-semtext-v1`). The layout
 * must mirror the offline harness's `sem_logits` + `_text_centroid` exactly — the ONNX↔torch parity
 * only holds if every candidate row is `[audio 960 ⊕ text 384]` and the state's centroid is the
 * unit-normalized, de-duplicated mean of the context tracks' text. The graceful-degradation
 * invariants (all-text-missing, null text matrix) are trained text-dropout paths and must produce
 * clean zero blocks, never NaNs or crashes.
 */
internal class ScorerPackingTest {

    private val audioDim = ScorerPacking.AUDIO_DIM
    private val textDim = ScorerPacking.TEXT_DIM
    private val inputDim = ScorerPacking.INPUT_DIM
    private val scorerN = PredictorRuntime.POOL_SIZE

    // Build an N×audioDim matrix where row r is all (r+1).
    private fun audioMatrix(n: Int) = FloatArray(n * audioDim) { (it / audioDim + 1).toFloat() }

    // Build an N×textDim matrix where row r is all (r+1)*0.5.
    private fun textMatrix(n: Int) = FloatArray(n * textDim) { (it / textDim + 1) * 0.5f }

    @Test
    fun packCandidates_layoutAudioThenText() {
        val n = 3
        val audio = audioMatrix(n)
        val text = textMatrix(n)
        val hasText = booleanArrayOf(true, true, true)
        val poolRows = intArrayOf(2, 0, 1) // arbitrary pool order
        val out = FloatArray(inputDim * scorerN)
        ScorerPacking.packCandidates(audio, text, hasText, poolRows, scorerN, out)

        for (i in poolRows.indices) {
            val row = poolRows[i]
            val base = i * inputDim
            // Audio half echoes the source row value.
            assertEquals((row + 1).toFloat(), out[base])
            assertEquals((row + 1).toFloat(), out[base + audioDim - 1])
            // Text half echoes the source text row value.
            assertEquals((row + 1) * 0.5f, out[base + audioDim])
            assertEquals((row + 1) * 0.5f, out[base + inputDim - 1])
        }
    }

    @Test
    fun packCandidates_paddedTailIsZero() {
        val n = 2
        val out = FloatArray(inputDim * scorerN) { 7f } // pre-dirty
        ScorerPacking.packCandidates(
            audioMatrix(n),
            textMatrix(n),
            booleanArrayOf(true, true),
            intArrayOf(0, 1),
            scorerN,
            out,
        )
        // Everything from pool slot 2 onward is zero.
        for (i in 2 * inputDim until out.size) assertEquals(0f, out[i])
    }

    @Test
    fun packCandidates_zeroFillsTextWhenMissing() {
        val n = 2
        val out = FloatArray(inputDim * scorerN)
        ScorerPacking.packCandidates(
            audioMatrix(n),
            textMatrix(n),
            booleanArrayOf(false, true), // row 0 has no text
            intArrayOf(0, 1),
            scorerN,
            out,
        )
        // Slot 0 audio present, text zero.
        assertEquals(1f, out[0])
        for (d in 0 until textDim) assertEquals(0f, out[audioDim + d])
        // Slot 1 has both.
        assertEquals(2f, out[inputDim])
        assertEquals(1f, out[inputDim + audioDim]) // (1+1)*0.5
    }

    @Test
    fun packCandidates_nullTextMatrixZeroFillsAllText() {
        val n = 3
        val out = FloatArray(inputDim * scorerN)
        ScorerPacking.packCandidates(
            audioMatrix(n),
            null, // no text matrix at all (pre-backfill snapshot)
            BooleanArray(n) { true }, // hasText irrelevant when matrix is null
            intArrayOf(0, 1, 2),
            scorerN,
            out,
        )
        for (i in 0 until n) {
            val base = i * inputDim
            assertEquals((i + 1).toFloat(), out[base]) // audio present
            for (d in 0 until textDim) assertEquals(0f, out[base + audioDim + d])
        }
    }

    @Test
    fun textCentroid_unitNorm() {
        val n = 4
        // Distinct text rows so the mean is non-degenerate.
        val text = FloatArray(n * textDim)
        for (r in 0 until n) text[r * textDim + r] = 1f // one-hot-ish rows
        val hasText = BooleanArray(n) { true }
        val c = ScorerPacking.textCentroid(text, hasText, intArrayOf(0, 1, 2, 3))
        assertEquals(textDim, c.size)
        var sumSq = 0.0
        for (v in c) sumSq += v.toDouble() * v.toDouble()
        assertEquals(1.0, sqrt(sumSq), 1e-5)
    }

    @Test
    fun textCentroid_dedupsRepeatedRows() {
        val n = 2
        val text = FloatArray(n * textDim)
        text[0 * textDim + 0] = 1f // row 0 = e0
        text[1 * textDim + 1] = 1f // row 1 = e1
        val hasText = booleanArrayOf(true, true)
        // Repeated row 0 must count once — [0,0,0,1] collapses to {0,1}, same as [0,1].
        val repeated = ScorerPacking.textCentroid(text, hasText, intArrayOf(0, 0, 0, 1))
        val plain = ScorerPacking.textCentroid(text, hasText, intArrayOf(0, 1))
        for (d in 0 until textDim) assertEquals(plain[d], repeated[d], 1e-6f)
        // Expected: mean of e0,e1 = (0.5,0.5,0…), normalized → (0.707,0.707,0…).
        assertEquals(sqrt(0.5).toFloat(), repeated[0], 1e-5f)
        assertEquals(sqrt(0.5).toFloat(), repeated[1], 1e-5f)
    }

    @Test
    fun textCentroid_skipsNegativeAndTextlessRows() {
        val n = 3
        val text = FloatArray(n * textDim)
        text[0 * textDim + 0] = 1f
        text[1 * textDim + 1] = 1f
        text[2 * textDim + 2] = 1f
        val hasText = booleanArrayOf(true, false, true) // row 1 has no text
        // -1 (no track) and the textless row 1 are dropped; only rows 0 and 2 contribute.
        val c = ScorerPacking.textCentroid(text, hasText, intArrayOf(-1, 0, 1, 2))
        assertEquals(sqrt(0.5).toFloat(), c[0], 1e-5f)
        assertEquals(0f, c[1]) // row 1 excluded
        assertEquals(sqrt(0.5).toFloat(), c[2], 1e-5f)
    }

    @Test
    fun textCentroid_emptyContextIsZeroVector() {
        val n = 2
        val text = textMatrix(n)
        val hasText = booleanArrayOf(true, true)
        // No usable rows (all -1) → zero vector, the trained empty-context path.
        val c = ScorerPacking.textCentroid(text, hasText, intArrayOf(-1, -1))
        assertEquals(textDim, c.size)
        assertTrue(c.all { it == 0f })
    }

    @Test
    fun textCentroid_nullTextMatrixIsZeroVector() {
        val c = ScorerPacking.textCentroid(null, booleanArrayOf(true), intArrayOf(0))
        assertEquals(textDim, c.size)
        assertTrue(c.all { it == 0f })
    }

    @Test
    fun packState_concatenatesAudioThenCentroid() {
        val state = FloatArray(audioDim) { 3f }
        val centroid = FloatArray(textDim) { 0.25f }
        val packed = ScorerPacking.packState(state, centroid)
        assertEquals(inputDim, packed.size)
        assertEquals(3f, packed[0])
        assertEquals(3f, packed[audioDim - 1])
        assertEquals(0.25f, packed[audioDim])
        assertEquals(0.25f, packed[inputDim - 1])
    }

    @Test
    fun packState_zeroCentroidLeavesAudioIntact() {
        val state = FloatArray(audioDim) { (it % 5).toFloat() }
        val packed = ScorerPacking.packState(state, FloatArray(textDim))
        for (i in 0 until audioDim) assertEquals(state[i], packed[i])
        for (i in audioDim until inputDim) assertEquals(0f, packed[i])
    }
}
