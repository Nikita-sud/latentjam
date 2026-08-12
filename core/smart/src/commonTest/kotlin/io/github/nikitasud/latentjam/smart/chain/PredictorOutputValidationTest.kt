/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PredictorOutputValidationTest {

    @Test
    fun `model output must have the exported tensor width`() {
        assertNull(validatedPredictorOutput(FloatArray(3), expectedSize = 4))
        assertNull(validatedPredictorOutput(FloatArray(5), expectedSize = 4))
        assertNull(validatedPredictorOutput(null, expectedSize = 4))

        assertContentEquals(
            floatArrayOf(1f, 2f, 3f, 4f),
            validatedPredictorOutput(floatArrayOf(1f, 2f, 3f, 4f), expectedSize = 4),
        )
    }

    @Test
    fun `model output rejects every non-finite component`() {
        assertNull(validatedPredictorOutput(floatArrayOf(1f, Float.NaN), expectedSize = 2))
        assertNull(
            validatedPredictorOutput(floatArrayOf(Float.POSITIVE_INFINITY, 1f), expectedSize = 2),
        )
        assertNull(
            validatedPredictorOutput(floatArrayOf(1f, Float.NEGATIVE_INFINITY), expectedSize = 2),
        )
    }

    @Test
    fun `negative expected width is a caller error`() {
        assertFailsWith<IllegalArgumentException> {
            validatedPredictorOutput(FloatArray(0), expectedSize = -1)
        }
    }
}
