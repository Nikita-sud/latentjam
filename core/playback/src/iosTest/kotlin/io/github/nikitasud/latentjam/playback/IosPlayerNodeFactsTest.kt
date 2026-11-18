/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.NSThread
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What AVAudioEngine and AVAudioPlayerNode really do, recorded as assertions: the iOS player is built on these facts
 * and nobody here can run it on a phone. Each test drives the real framework in offline manual rendering (no sound, no
 * device) over files whose frame k holds the integer k, so a gap, a repeat or a jump shows in the rendered samples.
 *
 * The limit of this evidence: scheduling, queueing, node clocks and what a stop does are the framework's own logic,
 * the same code on a phone. What only a device has is not here: `DataPlayedBack` notices never fire in offline
 * rendering (see [whenTheNoticesOfAFinishedSegmentCome]), a realtime engine does not start in this test environment,
 * and what the system does to the engine at an interruption or a route change is not something a test can cause;
 * `engine.stop()` is the nearest thing.
 */
internal class IosPlayerNodeFactsTest {
    private val rate = 44100
    private val n = 22050 // frames per fixture file: half a second

    private inner class Rig {
        val engine = AVAudioEngine()
        val player = AVAudioPlayerNode()
        val format = AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = 1u)
        private val output: AVAudioPCMBuffer
        var rendered = 0L

        init {
            check(engine.enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline, format, 512u, null))
            engine.attachNode(player)
            // Straight to the output: a mixer in between scales a mono input (see [aMixerTurnsAMonoInputDownByThreeDecibels]).
            engine.connect(player, engine.outputNode, format)
            output = AVAudioPCMBuffer(pCMFormat = engine.manualRenderingFormat, frameCapacity = 512u)
        }

        /** Renders [frames] and returns the integer each rendered sample stands for. */
        fun render(frames: Int, step: Int = 512): IntArray {
            val values = IntArray(frames)
            var done = 0
            while (done < frames) {
                val count = minOf(step, frames - done)
                check(engine.renderOffline(count.toUInt(), output, null) == AVAudioEngineManualRenderingStatusSuccess)
                val samples = checkNotNull(output.floatChannelData)[0]!!
                for (i in 0 until count) values[done + i] = (samples[i] * 32768f).roundToInt()
                done += count
                rendered += count
            }
            return values
        }

        fun start() = check(engine.startAndReturnError(null))

        /** The player's own sample time; null where the node gives none. Never asked of a render time that is not valid. */
        fun playerTime(): Long? {
            val time = player.lastRenderTime ?: return null
            if (!time.sampleTimeValid && !time.hostTimeValid) return null
            return player.playerTimeForNodeTime(time)?.sampleTime
        }

        fun close() { player.stop(); engine.stop() }
    }

    private fun rig(block: (Rig) -> Unit) {
        val rig = Rig()
        try { block(rig) } finally { rig.close() }
    }

    /** Frame k of the first file holds k - n, of the second k: played one after the other they count up by one throughout. */
    private fun first() = AVAudioFile(forReading = IosTestWaves.mono(rate, n) { it - n }, error = null)
    private fun second() = AVAudioFile(forReading = IosTestWaves.mono(rate, n) { it }, error = null)

    private fun assertCountsUp(values: IntArray, from: Int, firstValue: Int, count: Int, what: String) {
        for (i in 0 until count) assertEquals(firstValue + i, values[from + i], "$what, rendered frame ${from + i}")
    }

    private fun assertSilent(values: IntArray, from: Int, what: String) {
        for (i in from until values.size) assertEquals(0, values[i], "$what, rendered frame $i")
    }

    // a. Framework logic: the same on a phone.
    @Test fun twoSegmentsQueuedOnOneNodePlayWithoutAGapARepeatOrADrop() = rig { rig ->
        rig.player.scheduleSegment(first(), 0, n.toUInt(), null, null)
        rig.player.scheduleSegment(second(), 0, n.toUInt(), null, null)
        rig.start()
        rig.player.play()
        val values = rig.render(2 * n + 2048)
        // Every frame of both files once, in order, the second file's first frame straight after the first's last.
        assertCountsUp(values, 0, -n, 2 * n, "across the seam")
        assertSilent(values, 2 * n, "after the queue")
    }

    // b. Framework logic. The position of the second track is  playerTime - (firstLength - firstStart).
    @Test fun thePlayersClockCountsOnThroughTheSecondSegment() = rig { rig ->
        val start = 5000
        rig.player.scheduleSegment(first(), start.toLong(), (n - start).toUInt(), null, null)
        rig.player.scheduleSegment(second(), 0, n.toUInt(), null, null)
        rig.start()
        // The engine may render before the node plays: the player's clock starts at its own play, not the engine's.
        rig.render(1000)
        assertNull(rig.playerTime(), "a node that does not play has no player time")
        rig.player.play()
        // Straight after play() the node may still give no player time, or zero: both were seen.
        assertTrue(rig.playerTime() in listOf(null, 0L))
        val framesOfFirst = n - start
        var played = 0
        for (chunk in listOf(512, 4000, framesOfFirst - 4512, 1, 511, 3000)) {
            val values = rig.render(chunk)
            played += chunk
            assertEquals(played.toLong(), rig.playerTime(), "frames rendered since play")
            // The last frame rendered is frame (playerTime - 1 - framesOfFirst) of the second file once past the seam.
            if (played > framesOfFirst) assertEquals(played - 1 - framesOfFirst, values.last())
            else assertEquals(start + played - 1 - n, values.last())
        }
        // And on past the end of everything queued: the app bounds the position by the file's length itself.
        rig.render(2 * n)
        assertEquals((played + 2 * n).toLong(), rig.playerTime())
    }

    // Framework logic, and the reason IosAudioEngine checks a render time before it asks the player about it:
    // playerTimeForNodeTime raises ("nodeTime == nil || nodeTime.sampleTimeValid || nodeTime.hostTimeValid") for the
    // time an engine gives before it has rendered anything, and an exception from there ends the app.
    @Test fun anEngineThatHasNotRenderedYetGivesARenderTimeThatIsNotValid() = rig { rig ->
        rig.player.scheduleSegment(first(), 0, n.toUInt(), null, null)
        rig.start()
        rig.player.play()
        val time = assertNotNull(rig.player.lastRenderTime, "started and playing, nothing rendered")
        assertFalse(time.sampleTimeValid)
        assertFalse(time.hostTimeValid)
        rig.render(64)
        assertTrue(assertNotNull(rig.player.lastRenderTime).sampleTimeValid)
    }

    // c. Offline: Consumed comes at once, Rendered with the quantum that renders the segment's last frame, neither on
    // the main thread. PlayedBack, which the app uses on a device, never comes offline (its documentation says it is
    // for rendering to a device): WHEN it comes on a phone, and that it comes for a segment with another queued behind
    // it, is device-dependent and NOT verified here.
    @Test fun whenTheNoticesOfAFinishedSegmentCome() {
        fun run(type: AVAudioPlayerNodeCompletionCallbackType): Triple<Long, Long, Int> {
            var result = Triple(-1L, -1L, -1)
            rig { rig ->
                val progress = AtomicLong(0)
                val firstAt = AtomicLong(-1); val secondAt = AtomicLong(-1); val onMain = AtomicInt(-1)
                rig.player.scheduleSegment(first(), 0, n.toUInt(), null, type) { _ ->
                    firstAt.value = progress.value; onMain.value = if (NSThread.isMainThread) 1 else 0
                }
                rig.player.scheduleSegment(second(), 0, n.toUInt(), null, type) { _ -> secondAt.value = progress.value }
                rig.start()
                rig.player.play()
                // Small steps and a pause after each, so that a notice from another thread lands between two steps.
                while (rig.rendered < 2 * n + 4096) {
                    rig.render(64, step = 64)
                    usleep(2_000u)
                    progress.value = rig.rendered
                }
                usleep(200_000u)
                result = Triple(firstAt.value, secondAt.value, onMain.value)
            }
            return result
        }
        val consumed = run(AVAudioPlayerNodeCompletionDataConsumed)
        assertTrue(consumed.first in 0L until n / 2 && consumed.second in 0L until n / 2, "consumed long before it sounds: $consumed")
        val renderedNotice = run(AVAudioPlayerNodeCompletionDataRendered)
        // Never before the segment's last frame; within a few quanta after it, with the next segment already sounding.
        assertTrue(renderedNotice.first in (n - 64).toLong()..(n + 2048).toLong(), "first segment: $renderedNotice")
        assertTrue(renderedNotice.second in (2 * n - 64).toLong()..(2 * n + 2048).toLong(), "second segment: $renderedNotice")
        assertEquals(0, renderedNotice.third, "not on the main thread")
        val playedBack = run(AVAudioPlayerNodeCompletionDataPlayedBack)
        assertEquals(Triple(-1L, -1L, -1), playedBack, "PlayedBack does not exist offline")
    }

    private class Stopped(val playing: Boolean, val running: Boolean, val renderTimeNil: Boolean, val notices: Int, val after: IntArray, val playerTimeAfter: Long?)

    /** Plays 10,000 frames of two queued segments, does [stop], then [restart], and renders on. */
    private fun stopAndGoOn(stop: (Rig) -> Unit, restart: (Rig) -> Unit): Stopped {
        lateinit var result: Stopped
        rig { rig ->
            val notices = AtomicInt(0)
            for (file in listOf(first(), second())) {
                rig.player.scheduleSegment(file, 0, n.toUInt(), null, AVAudioPlayerNodeCompletionDataRendered) { _ -> notices.incrementAndGet() }
            }
            rig.start()
            rig.player.play()
            assertEquals(10_000 - 1 - n, rig.render(10_000).last())
            stop(rig)
            usleep(200_000u)
            val playing = rig.player.playing
            val running = rig.engine.running
            val renderTimeNil = rig.player.lastRenderTime == null
            val noticesAtStop = notices.value
            restart(rig)
            val playerTimeAfter = rig.playerTime()
            result = Stopped(playing, running, renderTimeNil, noticesAtStop, rig.render(40_000), playerTimeAfter)
        }
        return result
    }

    // d. Framework logic for engine.stop(); that the SYSTEM's stop at a change of route is this same stop is what its
    // documentation says ("the engine stops, uninitializes itself") and is not something a test can cause.
    @Test fun aStoppedEngineEmptiesItsNodeWhichGoesOnSayingItPlays() {
        for (playAgain in listOf(false, true)) {
            val stopped = stopAndGoOn({ it.engine.stop() }) { rig ->
                rig.start()
                if (playAgain) rig.player.play()
            }
            assertFalse(stopped.running)
            assertTrue(stopped.playing, "the node's flag survives the engine's stop")
            assertTrue(stopped.renderTimeNil, "a stopped engine has no render time")
            // Both queued segments report themselves finished, the second without one frame of it having played.
            assertEquals(2, stopped.notices)
            // Started again, with or without another play(): nothing is left to sound. The clock, though, goes on.
            assertSilent(stopped.after, 0, "after engine.stop, playAgain=$playAgain")
            assertEquals(10_000L, stopped.playerTimeAfter)
        }
    }

    // The app's own pause: nothing is lost, nothing is reported, and the clock leaves the pause out.
    @Test fun aPausedEngineKeepsWhatIsQueuedAndGoesOnWhereItWas() {
        val engineOnly = stopAndGoOn({ it.engine.pause() }) { it.start() }
        assertTrue(engineOnly.playing)
        assertTrue(engineOnly.renderTimeNil, "a paused engine has no render time either")
        assertEquals(0, engineOnly.notices)
        assertCountsUp(engineOnly.after, 0, 10_000 - n, 2 * n - 10_000, "after engine.pause")
        val asTheAppPauses = stopAndGoOn({ it.player.pause(); it.engine.pause() }) { it.start(); it.player.play() }
        assertFalse(asTheAppPauses.playing)
        assertEquals(0, asTheAppPauses.notices)
        assertCountsUp(asTheAppPauses.after, 0, 10_000 - n, 2 * n - 10_000, "after the app's pause")
        assertEquals(10_000L, asTheAppPauses.playerTimeAfter)
    }

    // e. Framework logic.
    @Test fun aStoppedNodeForgetsItsQueueAndItsClockAndAPausedOneKeepsBoth() {
        val stopped = stopAndGoOn({ it.player.stop() }) { it.player.play() }
        assertFalse(stopped.playing)
        assertEquals(2, stopped.notices, "stop reports every queued segment finished: why the app tells its own stops apart by a token")
        assertSilent(stopped.after, 0, "after player.stop")
        assertEquals(0L, stopped.playerTimeAfter, "the clock starts over")
        val paused = stopAndGoOn({ it.player.pause(); it.render(3000) }) { it.player.play() }
        assertEquals(0, paused.notices)
        assertCountsUp(paused.after, 0, 10_000 - n, 2 * n - 10_000, "after player.pause")
        assertEquals(10_000L, paused.playerTimeAfter, "frames rendered while paused do not count")
    }

    // f. What the app's recovery does (IosAudioEngine.cueAt): engine and node stopped, a segment from a frame, started.
    @Test fun aSegmentCuedFromAFrameAfterAStopPlaysFromThatFrame() = rig { rig ->
        val file = first()
        rig.player.scheduleSegment(file, 0, n.toUInt(), null, null)
        rig.start()
        rig.player.play()
        rig.render(8_000)
        rig.engine.stop()
        rig.player.stop()
        rig.player.scheduleSegment(file, 7_000, (n - 7_000).toUInt(), null, null)
        rig.start()
        rig.player.play()
        val values = rig.render(n)
        assertCountsUp(values, 0, 7_000 - n, n - 7_000, "from the cued frame")
        assertSilent(values, n - 7_000, "after its end")
        assertEquals(n.toLong(), rig.playerTime(), "the clock counts from the new play")
        // One last frame alone is a segment like any other: what a remembered end plays out.
        rig.engine.stop()
        rig.player.stop()
        rig.player.scheduleSegment(file, (n - 1).toLong(), 1u, null, null)
        rig.start()
        rig.player.play()
        val tail = rig.render(1024)
        assertEquals(-1, tail[0])
        assertSilent(tail, 1, "after the one frame")
    }

    // Framework logic. The node converts a file of another sample rate to its own (with the converter's ringing at the
    // file's end); it is not refused and it does not play at the wrong speed. A gapless join still asks for the same rate.
    @Test fun aFileOfAnotherSampleRateQueuedBehindIsConverted() = rig { rig ->
        rig.player.scheduleSegment(first(), 0, n.toUInt(), null, null)
        // Half the rate, every frame worth two: converted, it goes on counting up by one.
        val half = AVAudioFile(forReading = IosTestWaves.mono(rate / 2, n / 2) { it * 2 }, error = null)
        rig.player.scheduleSegment(half, 0, (n / 2).toUInt(), null, null)
        rig.start()
        rig.player.play()
        val values = rig.render(2 * n)
        assertCountsUp(values, 0, -n, n, "the first file")
        for (i in n until 2 * n - 64) assertTrue(kotlin.math.abs(values[i] - (i - n)) <= 2, "converted frame $i is ${values[i]}")
    }

    // Framework logic, and a defect it explains (not fixed here): a mixer plays a mono input 3 dB down, into a mono
    // output as into a stereo one, and a stereo input as it is. The app's graph has two mixers, so a mono file sounds
    // 6 dB quieter than the same music in two channels.
    @Test fun aMixerTurnsAMonoInputDownByThreeDecibels() {
        for (channels in listOf(1, 2)) for (outChannels in listOf(1, 2)) {
            val engine = AVAudioEngine()
            val player = AVAudioPlayerNode()
            val inFormat = AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = channels.toUInt())
            val outFormat = AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = outChannels.toUInt())
            check(engine.enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline, outFormat, 512u, null))
            engine.attachNode(player)
            engine.connect(player, engine.mainMixerNode, inFormat)
            val input = AVAudioPCMBuffer(pCMFormat = inFormat, frameCapacity = 512u)
            input.frameLength = 512u
            val source = checkNotNull(input.floatChannelData)
            for (c in 0 until channels) for (i in 0 until 512) source[c]!![i] = 0.5f
            try {
                player.scheduleBuffer(input, completionHandler = null)
                check(engine.startAndReturnError(null))
                player.play()
                val output = AVAudioPCMBuffer(pCMFormat = engine.manualRenderingFormat, frameCapacity = 512u)
                check(engine.renderOffline(512u, output, null) == AVAudioEngineManualRenderingStatusSuccess)
                val expected = if (channels == 1) 0.35355338f else 0.5f
                for (c in 0 until outChannels) {
                    assertEquals(expected, checkNotNull(output.floatChannelData)[c]!![100], 0.0001f, "$channels in, $outChannels out")
                }
            } finally { player.stop(); engine.stop() }
        }
    }
}
