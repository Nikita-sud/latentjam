/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.nikitasud.latentjam.playback

import platform.AVFAudio.AVAudioFormat
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's own [IosAudioEngine], its whole graph (two decks, submixer, equalizer unit, main mixer), rendered offline:
 * no sound, no device, the framework's real nodes. Files hold the integer k in frame k, so what comes out says which
 * frame of which file it is. The equalizer unit delays everything by [DELAY] frames and, after a cue in the middle of a
 * file, fades the first few milliseconds in; tests look past both.
 */
internal class IosOfflineEngineTest {
    private val rate = 44100
    private val n = 22050

    private fun engine(block: (IosAudioEngine) -> Unit) {
        NSUserDefaults.standardUserDefaults.setBool(false, "ios_equalizer_enabled")
        val engine = IosAudioEngine(AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = 2u))
        try { block(engine) } finally { engine.stop() }
    }

    /** Stereo, so that no mixer scales it (IosPlayerNodeFactsTest): frame k holds k - n. */
    private fun first(): NSURL = IosTestWaves.stereo(rate, n) { it - n }

    private fun IosAudioEngine.render(frames: Int): IntArray =
        renderOffline(frames).let { samples -> IntArray(frames) { (samples[it] * 32768f).roundToInt() } }

    /** Lets what the engine sent to the main queue (a segment's notice) run. */
    private fun pump() = NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))

    private fun assertCountsUp(values: IntArray, from: Int, firstValue: Int, count: Int, what: String) {
        for (i in 0 until count) assertEquals(firstValue + i, values[from + i], "$what, rendered frame ${from + i}")
    }

    private fun assertSilent(values: IntArray, from: Int, until: Int, what: String) {
        for (i in from until until) assertEquals(0, values[i], "$what, rendered frame $i")
    }

    @Test fun aFilePlaysSampleForSampleThroughTheWholeGraphAndReportsItsEndOnce() = engine { engine ->
        var ended = 0
        assertTrue(engine.load(first(), autoPlay = true) { ended++ })
        // Asked before anything was rendered: the cued place, and no exception from a render time that is not valid yet.
        assertEquals(0L, engine.positionMs())
        val values = engine.render(n + 2000)
        assertSilent(values, 0, DELAY, "the equalizer's delay")
        assertCountsUp(values, DELAY, -n, n, "the file")
        assertSilent(values, DELAY + n, values.size, "after the file")
        pump()
        assertEquals(1, ended)
        assertEquals(500L, engine.positionMs())
    }

    @Test fun anOverlapsPositionAskedBeforeItsFirstRenderDoesNotRaise() = engine { engine ->
        assertTrue(engine.load(first(), autoPlay = true) {})
        engine.render(1000)
        assertEquals(500L, engine.prepareOverlap(IosTestWaves.stereo(rate, n) { it }))
        assertTrue(engine.startOverlap())
        assertEquals(0L, engine.standbyPositionMs())
        engine.render(4410)
        assertEquals(100L, engine.standbyPositionMs())
    }

    /** Longer than the second within which a stop counts as the end ([segmentEnd]): frame k holds k - LONG / 2. */
    private fun long(): NSURL = IosTestWaves.stereo(rate, LONG) { it - LONG / 2 }

    /** Plays 5,000 frames of [long] and has the system stop the engine under them. */
    private fun stoppedBySystem(engine: IosAudioEngine, onEnded: () -> Unit) {
        assertTrue(engine.load(long(), autoPlay = true, onEnded = onEnded))
        engine.render(5_000)
        assertEquals(113L, engine.positionMs())
        engine.stopEngineAsTheSystemDoes()
        assertFalse(engine.running)
        assertTrue(engine.playing, "the node goes on saying it plays")
        assertEquals(113L, engine.positionMs(), "the place it last sounded at, not the start of the track")
    }

    /** After a cue at frame 5,000: the delay, a short fade-in, then every frame from there to the file's end. */
    private fun assertGoesOnFromFrameFiveThousand(values: IntArray, what: String) {
        val settled = DELAY + 500
        assertCountsUp(values, settled, 5_000 + 500 - LONG / 2, LONG - 5_000 - 500, what)
    }

    @Test fun afterTheSystemStoppedTheEngineAPauseAndAPlaySoundAgainFromWhereItWas() = engine { engine ->
        var ended = 0
        stoppedBySystem(engine) { ended++ }
        // The interruption's way: the app pauses, later plays. No notice from the emptied node has been heard here
        // (on a device its notice may never come): the stopped engine under a playing node is evidence enough.
        engine.pause()
        assertEquals(113L, engine.positionMs())
        assertTrue(engine.play())
        assertGoesOnFromFrameFiveThousand(engine.render(LONG), "after pause and play")
        pump()
        assertEquals(1, ended, "the track's real end, once")
    }

    @Test fun theNoticeOfANodeTheSystemEmptiedIsNotTheEndOfTheTrack() = engine { engine ->
        var ended = 0
        stoppedBySystem(engine) { ended++ }
        // The emptied node reports its segment finished (it does, offline): mid-track that is no end.
        pump()
        assertEquals(0, ended)
        assertEquals(113L, engine.positionMs())
        assertTrue(engine.play())
        assertGoesOnFromFrameFiveThousand(engine.render(LONG), "after the notice and a play")
        pump()
        assertEquals(1, ended)
    }

    @Test fun theRecoveryCuesTheTrackAgainWhereItStoodAndItPlaysOn() = engine { engine ->
        var ended = 0
        stoppedBySystem(engine) { ended++ }
        assertTrue(engine.recueAfterSystemStop())
        assertFalse(engine.playing)
        assertEquals(113L, engine.positionMs())
        pump()
        assertEquals(0, ended, "the old segment's notice is a replaced one")
        assertTrue(engine.play())
        assertGoesOnFromFrameFiveThousand(engine.render(LONG), "after the recovery")
        pump()
        assertEquals(1, ended)
    }

    @Test fun aSystemStopWithinTheLastSecondCountsAsTheEndAndAPlayOfTheLastFrameReportsItAgain() = engine { engine ->
        var ended = 0
        assertTrue(engine.load(first(), autoPlay = true) { ended++ })
        engine.render(n - 4410)
        assertEquals(400L, engine.positionMs())
        engine.stopEngineAsTheSystemDoes()
        pump()
        // A tenth of a second before the end: taken for the end, as it always was.
        assertEquals(1, ended)
        assertEquals(500L, engine.positionMs())
        // What a remembered end does on the next Play: the last frame once more, and its end is reported again.
        engine.seekTo(500L, resume = false)
        assertTrue(engine.play())
        engine.render(2048)
        pump()
        assertEquals(2, ended)
    }

    // ------------------------------------------------------------------ a seam without a gap

    /** Continues [first]: frame k holds k, so the two played one after the other count up by one throughout. */
    private fun second(): NSURL = IosTestWaves.stereo(rate, n) { it }

    private class Seam { var seams = 0; var firstEnded = 0; var secondEnded = 0 }

    /** Plays [first] to 0.2 s before its end and queues [second] behind it. */
    private fun chained(engine: IosAudioEngine, seam: Seam): IntArray {
        assertTrue(engine.load(first(), autoPlay = true) { seam.firstEnded++ })
        val head = engine.render(n - LEAD)
        assertEquals(300L, engine.positionMs())
        assertTrue(engine.chainNext(second(), onSeam = { seam.seams++ }, onEnded = { seam.secondEnded++ }))
        assertTrue(engine.hasChained)
        return head
    }

    @Test fun aTrackQueuedBehindTheCurrentOnePlaysOnWithoutAGapAndBecomesTheCurrentTrackAtTheSeam() = engine { engine ->
        val seam = Seam()
        val head = chained(engine, seam)
        // Up to a tenth of a second before the seam: still the first track.
        val beforeSeam = engine.render(LEAD - 4410)
        assertEquals(400L, engine.positionMs())
        assertEquals(500L, engine.durationMs())
        assertTrue(engine.hasChained)
        // Across the seam and a tenth of a second into the second track. The clock says where it is before any
        // notice has been heard: the position starts again at the seam.
        val acrossSeam = engine.render(2 * 4410)
        assertEquals(100L, engine.positionMs())
        assertFalse(engine.hasChained)
        val rest = engine.render(n - 4410 + 2000)
        pump()
        val values = head + beforeSeam + acrossSeam + rest
        assertSilent(values, 0, DELAY, "the equalizer's delay")
        // Every frame of both files once and in order: nothing inserted, dropped or repeated at the seam.
        assertCountsUp(values, DELAY, -n, 2 * n, "through the seam")
        assertSilent(values, DELAY + 2 * n, values.size, "after the second file")
        assertEquals(1, seam.seams, "the seam is reported once")
        assertEquals(0, seam.firstEnded, "the first track's end is the seam, not an end of playback")
        assertEquals(1, seam.secondEnded, "the second track ends as any track does")
        assertEquals(500L, engine.positionMs())
    }

    @Test fun theSeamIsTakenFromTheFirstSegmentsNoticeToo() = engine { engine ->
        val seam = Seam()
        chained(engine, seam)
        engine.render(LEAD + 4410)
        // Nobody has asked for the position: the first segment's notice alone makes the queued track current.
        pump()
        assertEquals(1, seam.seams)
        assertFalse(engine.hasChained)
        assertEquals(100L, engine.positionMs())
        assertEquals(0, seam.firstEnded)
        engine.render(n)
        pump()
        assertEquals(1, seam.seams)
        assertEquals(1, seam.secondEnded)
    }

    @Test fun aQueuedTrackTakenBackNeverSoundsAndTheCurrentOneEndsThePlainWay() = engine { engine ->
        val seam = Seam()
        chained(engine, seam)
        engine.render(4410)
        // The queue changed, repeat-one was switched on, the sleep timer holds: the join is taken back.
        engine.dropChained()
        assertFalse(engine.hasChained)
        assertTrue(engine.playing)
        assertEquals(400L, engine.positionMs())
        val values = engine.render(LEAD + 4000)
        pump()
        // After the cue (delay and fade-in): the first file from where it was to its end, then nothing of the second.
        val from = n - LEAD + 4410
        assertCountsUp(values, DELAY + 500, from + 500 - n, n - from - 500, "the first file's remainder")
        assertSilent(values, DELAY + (n - from), values.size, "the second file must not sound")
        assertEquals(0, seam.seams)
        assertEquals(1, seam.firstEnded)
        assertEquals(0, seam.secondEnded)
    }

    @Test fun aSeekOrAStopOfTheEngineDropsWhatWasQueued() = engine { engine ->
        val seam = Seam()
        chained(engine, seam)
        engine.seekTo(100L)
        assertFalse(engine.hasChained)
        val values = engine.render(n)
        pump()
        assertCountsUp(values, DELAY + 500, 4410 + 500 - n, n - 4410 - 500, "from the seek to the first file's end")
        assertSilent(values, DELAY + n - 4410, values.size, "nothing queued is left")
        assertEquals(0, seam.seams)
        assertEquals(1, seam.firstEnded)
    }

    @Test fun aSystemStopMidTrackDropsWhatWasQueuedAndIsNoSeamAndNoEnd() = engine { engine ->
        // Files longer than the second within which a stop counts as the end.
        val seam = Seam()
        assertTrue(engine.load(long(), autoPlay = true) { seam.firstEnded++ })
        engine.render(5_000)
        assertEquals(113L, engine.positionMs())
        assertTrue(engine.chainNext(long(), onSeam = { seam.seams++ }, onEnded = { seam.secondEnded++ }))
        engine.stopEngineAsTheSystemDoes()
        // Both segments report themselves finished: the first was emptied mid-track, the second never began.
        pump()
        assertFalse(engine.hasChained)
        assertEquals(0, seam.seams + seam.firstEnded + seam.secondEnded)
        assertEquals(113L, engine.positionMs())
        // Cued again, the first track plays on alone to its own end.
        assertTrue(engine.recueAfterSystemStop())
        assertTrue(engine.play())
        assertGoesOnFromFrameFiveThousand(engine.render(LONG), "after the stop")
        pump()
        assertEquals(1, seam.firstEnded)
        assertEquals(0, seam.seams + seam.secondEnded)
    }

    @Test fun aPauseKeepsTheQueuedTrackAndTheSeamStaysWhole() = engine { engine ->
        val seam = Seam()
        val head = chained(engine, seam)
        val more = engine.render(4410)
        engine.pause()
        assertTrue(engine.hasChained)
        assertEquals(400L, engine.positionMs())
        assertTrue(engine.play())
        val rest = engine.render(LEAD - 4410 + n + 2000)
        pump()
        val values = head + more + rest
        assertCountsUp(values, DELAY, -n, 2 * n, "through a pause and the seam")
        assertEquals(1, seam.seams)
        assertEquals(1, seam.secondEnded)
    }

    @Test fun onlyAFileOfTheSameRateAndLayoutIsQueuedBehind() = engine { engine ->
        assertTrue(engine.load(first(), autoPlay = true) {})
        engine.render(1000)
        assertFalse(engine.chainNext(IosTestWaves.mono(rate, n) { it }, {}, {}), "another channel count")
        assertFalse(engine.chainNext(IosTestWaves.stereo(48_000, n) { it }, {}, {}), "another sample rate")
        assertFalse(engine.chainNext(NSURL.fileURLWithPath("/nowhere/at/all.wav"), {}, {}), "a file that is not there")
        assertFalse(engine.hasChained)
        assertTrue(engine.chainNext(second(), {}, {}))
        assertFalse(engine.chainNext(second(), {}, {}), "one at a time")
        // Paused, nothing is queued either: the join is made while the track sounds.
        engine.dropChained()
        engine.pause()
        assertFalse(engine.chainNext(second(), {}, {}))
    }

    private companion object {
        /** How long before the first file's end the tests queue the second: 0.2 s. */
        const val LEAD = 8820

        /** The equalizer unit's delay at 44.1 kHz: a two-hundredth of a second. */
        const val DELAY = 220

        /** 1.2 s at 44.1 kHz. */
        const val LONG = 52_920
    }
}
