/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.playback.nativeeq.*
import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A mono file must come out of the app's graph at the level the same music has as a file of two equal channels, which
 * is what Android plays (a mono track goes to both speakers as it is). The main mixer turns a mono input down by 3 dB
 * (its pan law, into a mono output as into a stereo one), so without a make-up a mono file was 3 dB down: 0.354 where
 * the two-channel file gives 0.5. The make-up is at the equalizer unit's output ([lj_eq_ios_set_output_gain]).
 *
 * Rendered offline through the whole graph of [IosAudioEngine]. The tolerance, [TOLERANCE], is more than a hundred
 * times the rounding of the two float multiplications involved (the mixer's 0.70710677 and the make-up's 1.4142135:
 * about 6e-8 of the sample), and far below anything audible (0.0002 dB at half scale).
 */
internal class IosMonoLevelTest {
    private val rate = 44100
    private val n = 22050

    private fun sine(amplitude: Int): (Int) -> Int = { k -> (amplitude * sin(2 * PI * 1000 * k / rate)).roundToInt() }
    private fun mono(amplitude: Int = HALF): NSURL = IosTestWaves.mono(rate, n, sine(amplitude))
    private fun stereo(amplitude: Int = HALF): NSURL = IosTestWaves.stereo(rate, n, sine(amplitude))

    private fun engine(equalizer: Boolean = false, block: (IosAudioEngine) -> Unit) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setBool(equalizer, "ios_equalizer_enabled")
        defaults.setInteger(1, "ios_equalizer_software_version")
        defaults.setInteger(if (equalizer) 1000 else 0, "ios_equalizer_software_bass")
        val engine = IosAudioEngine(AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = 2u))
        try { block(engine) } finally {
            engine.stop()
            defaults.setBool(false, "ios_equalizer_enabled")
            defaults.setInteger(0, "ios_equalizer_software_bass")
        }
    }

    /** What the whole graph renders for [url] played from its start. */
    private fun played(url: NSURL, equalizer: Boolean = false, deckGain: Float = 1f): FloatArray {
        lateinit var out: FloatArray
        engine(equalizer) { engine ->
            engine.setDeckGains(deckGain)
            assertTrue(engine.load(url, autoPlay = true) {})
            out = engine.renderOffline(n + 2000)
        }
        return out
    }

    private fun peak(samples: FloatArray): Float = samples.maxOf { abs(it) }

    private fun assertSameLevel(expected: FloatArray, actual: FloatArray, tolerance: Float, what: String) {
        assertEquals(expected.size, actual.size)
        assertTrue(peak(expected) > 0.1f, "$what: the reference is not silent")
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) <= tolerance, "$what, frame $i: two channels ${expected[i]}, mono ${actual[i]}")
        }
    }

    @Test fun aMonoFileComesOutAtTheLevelOfTheSameMusicInTwoChannels() {
        val two = played(stereo())
        val one = played(mono())
        assertSameLevel(two, one, TOLERANCE, "equalizer off")
        assertEquals(0.5f, peak(one), TOLERANCE)
    }

    @Test fun aMonoFileAtFullScaleIsNotLouderThanItsTwoChannelSelfAndDoesNotClip() {
        val two = played(stereo(FULL))
        val one = played(mono(FULL))
        assertSameLevel(two, one, TOLERANCE, "full scale")
        assertTrue(peak(one) <= 1f, "a full-scale mono file stays within full scale: ${peak(one)}")
    }

    @Test fun theEqualizerAndItsLimiterTreatAMonoFileAsTheyTreatTwoEqualChannels() {
        val two = played(stereo(FULL), equalizer = true)
        val one = played(mono(FULL), equalizer = true)
        // The limiter works on what it is given; given the same level it does the same.
        assertSameLevel(two, one, 10 * TOLERANCE, "bass boost and limiter")
    }

    @Test fun loudnessNormalizationsGainComesOnTopOfBothAlike() {
        val two = played(stereo(), deckGain = 0.4f)
        val one = played(mono(), deckGain = 0.4f)
        assertSameLevel(two, one, TOLERANCE, "deck gain 0.4")
        assertEquals(0.4f * HALF / 32768f, peak(one), 0.001f)
    }

    /** Two tracks sounding at once on the two decks (an overlap), each at its own gain. */
    private fun overlapped(first: NSURL, second: NSURL): FloatArray {
        lateinit var out: FloatArray
        engine { engine ->
            assertTrue(engine.load(first, autoPlay = true) {})
            val head = engine.renderOffline(4410)
            assertEquals(500L, engine.prepareOverlap(second))
            assertTrue(engine.startOverlap())
            engine.setDeckGains(0.5f, 0.25f)
            out = head + engine.renderOffline(8820)
        }
        return out
    }

    @Test fun bothDecksOfAnOverlapCarryAMonoFileAtTheSameLevel() {
        // The second track a fifth above, so that the sum is no simple multiple of the first.
        val fifth: (Int) -> Int = { k -> (HALF * sin(2 * PI * 1500 * k / rate)).roundToInt() }
        val two = overlapped(stereo(), IosTestWaves.stereo(rate, n, fifth))
        val one = overlapped(mono(), IosTestWaves.mono(rate, n, fifth))
        assertSameLevel(two, one, TOLERANCE, "current deck and standby deck")
    }

    /** One track queued behind another on the same deck (a seam without a gap). */
    private fun chained(first: NSURL, second: NSURL): FloatArray {
        lateinit var out: FloatArray
        engine { engine ->
            assertTrue(engine.load(first, autoPlay = true) {})
            val head = engine.renderOffline(n - 8820)
            assertTrue(engine.chainNext(second, {}, {}))
            out = head + engine.renderOffline(8820 + n + 2000)
        }
        return out
    }

    @Test fun theLevelHoldsAcrossASeamWithoutAGap() {
        val two = chained(stereo(), stereo())
        val one = chained(mono(), mono())
        assertSameLevel(two, one, TOLERANCE, "through the seam")
    }

    @Test fun aMonoTrackAndATwoChannelTrackOneAfterTheOtherAreEquallyLoud() = engine { engine ->
        // A mono track is never queued behind or overlapped with a two-channel one: the next one is loaded, and the
        // graph is set up for it. The level must not jump there.
        assertTrue(engine.load(mono(), autoPlay = true) {})
        val one = engine.renderOffline(n + 2000)
        assertTrue(engine.load(stereo(), autoPlay = true) {})
        val two = engine.renderOffline(n + 2000)
        assertTrue(engine.load(mono(), autoPlay = true) {})
        val oneAgain = engine.renderOffline(n + 2000)
        assertSameLevel(two, one, TOLERANCE, "mono then two channels")
        assertSameLevel(two, oneAgain, TOLERANCE, "two channels then mono")
    }

    /** The equalizer unit alone between a player and the output: no mixer, no engine class. */
    private fun unitAlone(url: NSURL, frames: Int): FloatArray {
        val engine = AVAudioEngine()
        val player = AVAudioPlayerNode()
        val unit = checkNotNull(lj_eq_ios_create())
        val file = AVAudioFile(forReading = url, error = null)
        val format = file.processingFormat
        check(engine.enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline, format, 512u, null))
        engine.attachNode(player); engine.attachNode(unit)
        engine.connect(player, unit, format); engine.connect(unit, engine.outputNode, format)
        IntArray(10).usePinned { lj_eq_ios_set_settings(unit, 0, it.addressOf(0), 0) }
        val out = FloatArray(frames)
        try {
            player.scheduleSegment(file, 0, file.length.toUInt(), null, null)
            check(engine.startAndReturnError(null))
            player.play()
            val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = 512u)
            var done = 0
            while (done < frames) {
                val count = minOf(512, frames - done)
                check(engine.renderOffline(count.toUInt(), buffer, null) == AVAudioEngineManualRenderingStatusSuccess)
                val samples = checkNotNull(buffer.floatChannelData)[0]!!
                for (i in 0 until count) out[done + i] = samples[i]
                done += count
            }
        } finally { player.stop(); engine.stop() }
        return out
    }

    @Test fun aTwoChannelFileGoesThroughTheGraphBitForBit() {
        // Nothing in the graph may touch a two-channel file: what the whole graph renders is, float for float, what
        // the equalizer unit renders alone, and the make-up for mono files is not in its way.
        val whole = played(stereo(FULL))
        val alone = unitAlone(stereo(FULL), whole.size)
        assertTrue(peak(whole) > 0.99f)
        for (i in whole.indices) {
            assertEquals(alone[i].toRawBits(), whole[i].toRawBits(), "frame $i: ${alone[i]} and ${whole[i]}")
        }
    }

    @Test fun theLevelIsTheSameIntoAMonoOutputAsIntoAStereoOne() {
        // The main mixer's 3 dB for a mono input does not depend on what it mixes into, so neither does the make-up.
        for (outChannels in listOf(1, 2)) {
            val peaks = listOf(mono(), stereo()).map { url ->
                val engine = IosAudioEngine(AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = outChannels.toUInt()))
                try {
                    assertTrue(engine.load(url, autoPlay = true) {})
                    peak(engine.renderOffline(n))
                } finally { engine.stop() }
            }
            assertEquals(peaks[1], peaks[0], TOLERANCE, "into $outChannels output channel(s): two channels ${peaks[1]}, mono ${peaks[0]}")
            assertEquals(0.5f, peaks[0], TOLERANCE)
        }
    }

    private companion object {
        const val HALF = 16384
        const val FULL = 32767
        const val TOLERANCE = 1e-5f
    }
}
