/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class EqualizerMeterTest {
    @Test fun meterHoldsShortPeaksAndClearsAfterProcessingStops() {
        val meter = EqualizerMeter()
        assertNull(meter.snapshot(0).sampleRateHz)
        assertEquals(0f, meter.snapshot(0).limiterReductionDb)
        meter.report(44100, 0.5f, 10_000_000)
        meter.report(44100, 1f, 110_000_000)
        assertEquals(44100, meter.snapshot(120_000_000).sampleRateHz)
        assertEquals(6.0206f, meter.snapshot(120_000_000).limiterReductionDb!!, 0.0001f)
        meter.report(48000, 1f, 420_000_000)
        assertEquals(0f, meter.snapshot(430_000_000).limiterReductionDb)
        meter.report(48000, 0.25f, 440_000_000)
        assertEquals(0f, meter.snapshot(950_000_000).limiterReductionDb)
    }

    @Test fun decodedPcmReportsTheConfiguredRateAndRealLimiterAttenuation() {
        val meter = EqualizerMeter()
        val processor = EqualizerAudioProcessor(meter) { SoftwareEqualizerSettings(enabled = true, bass = 1000) }
        processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(48000 * 2).order(ByteOrder.nativeOrder())
        repeat(48000) { input.putShort((25000 * sin(2 * PI * 40 * it / 48000)).toInt().toShort()) }
        input.flip()
        processor.queueInput(input)
        val reading = meter.snapshot()
        assertEquals(48000, reading.sampleRateHz)
        assertTrue(reading.limiterReductionDb!! > 3f)
        assertTrue(processor.output.remaining() > 0)
    }
}
