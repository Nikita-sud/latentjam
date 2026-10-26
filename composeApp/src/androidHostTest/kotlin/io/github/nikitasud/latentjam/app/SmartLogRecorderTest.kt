/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.SmartImpression
import io.github.nikitasud.latentjam.history.SmartImpressionLog
import io.github.nikitasud.latentjam.history.SmartLogStore
import io.github.nikitasud.latentjam.history.SmartOutcome
import io.github.nikitasud.latentjam.history.SmartOutcomeKind
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackStart
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartLogRecorderTest {
    private class Lines : SmartLogStore {
        val text = StringBuilder()
        override suspend fun append(text: String) { this.text.append(text) }
        override suspend fun size(): Long = text.length.toLong()
        override suspend fun keepTail(maxBytes: Long) = Unit
        override suspend fun forEachLine(line: (String) -> Unit) = text.lines().filter(String::isNotEmpty).forEach(line)
        override suspend fun clear() { text.clear() }
    }

    private fun t(id: String) = TrackDescriptor(TrackId(id), title = id, artist = "A")
    private fun row(id: String) = SmartPlanRow(TrackId(id), "k$id", 1.0, 0.5, true, "R1")

    @Test
    fun aPlanItsListensAndItsLeftoversReachTheLog() = runTest {
        val store = Lines()
        val recorder = SmartLogRecorder(SmartImpressionLog(store), backgroundScope, clock = { 42L })
        recorder.planned("q.0.1", 0, rankOffset = 0, model = "core", rows = listOf(row("a"), row("b"), row("c")))
        recorder.served(TrackId("a"))
        val queue = listOf(t("seed"), t("a"))
        recorder.onNowPlaying(NowPlaying(track = t("seed"), queue = queue))
        recorder.onNowPlaying(NowPlaying(track = t("a"), queue = queue, playbackStart = PlaybackStart(2, TrackId("a"), StartCause.AUTO_ADVANCE)))
        recorder.onListen(ListenEvent(TrackId("a"), 0, 12_000, 240_000, completed = false, skipped = true, listenedMs = 12_000))
        // The listener starts other music: b and c never come.
        recorder.onNowPlaying(NowPlaying(track = t("x"), queue = listOf(t("x")), playbackStart = PlaybackStart(3, TrackId("x"), StartCause.USER_PICK)))
        runCurrent()
        val parsed = store.text.lines().filter(String::isNotEmpty).map { SmartImpressionLog.parse(it) }
        assertEquals(3, parsed.count { it is SmartImpression })
        assertEquals((parsed[0] as SmartImpression).rank, 1)
        val outcomes = parsed.filterIsInstance<SmartOutcome>()
        assertEquals(listOf(SmartOutcomeKind.SKIPPED, SmartOutcomeKind.REPLACED, SmartOutcomeKind.REPLACED), outcomes.map { it.kind })
        assertEquals(0.05, outcomes[0].fraction)
        assertEquals(listOf("ka", "kb", "kc"), outcomes.map { it.track })
    }

    @Test
    fun clearingTheHistoryLeavesNothingOfTheRunningQueueToWriteBack() = runTest {
        val store = Lines()
        val recorder = SmartLogRecorder(SmartImpressionLog(store), backgroundScope, clock = { 42L })
        recorder.planned("q.0.1", 0, rankOffset = 0, model = "core", rows = listOf(row("a"), row("b")))
        recorder.served(TrackId("a"))
        // Clear listening history, with the plan's lines possibly still queued.
        recorder.forgetAll()
        runCurrent()
        assertEquals("", store.text.toString())
        // The queue plays on: its old rows must not reach the emptied log.
        val queue = listOf(t("seed"), t("a"))
        recorder.onNowPlaying(NowPlaying(track = t("a"), queue = queue, playbackStart = PlaybackStart(2, TrackId("a"), StartCause.AUTO_ADVANCE)))
        recorder.onListen(ListenEvent(TrackId("a"), 0, 240_000, 240_000, completed = true, skipped = false, listenedMs = 240_000))
        runCurrent()
        assertEquals("", store.text.toString())
        assertEquals(emptySet(), recorder.engagedKeys())
    }

    @Test
    fun recentPressesAreTheFirstTenRowsOfEachPlanZeroNewestFirst() = runTest {
        val store = Lines()
        var now = 1_000L
        val recorder = SmartLogRecorder(SmartImpressionLog(store), backgroundScope, clock = { now })
        recorder.planned("q1", 0, 0, "m", (1..12).map { row("a$it") })
        now = 2_000L
        recorder.planned("q1", 1, 12, "m", listOf(row("topup")))
        recorder.planned("q2", 0, 0, "m", listOf(row("b1")))
        val got = recorder.recentPresses(nowMs = 2_500L, windowMs = 10_000L, presses = 5)
        assertEquals(listOf(setOf("kb1"), (1..10).map { "ka$it" }.toSet()), got)
        assertEquals(listOf(setOf("kb1")), recorder.recentPresses(2_500L, 1_000L, 5), "the window drops q1")
        assertEquals(listOf((1..10).map { "ka$it" }.toSet()), recorder.recentPresses(2_500L, 10_000L, 5, excludeQueue = "q2"))
        runCurrent()
        // A new process reads the same presses back from the log.
        val again = SmartLogRecorder(SmartImpressionLog(store), backgroundScope, clock = { now })
        assertEquals(got, again.recentPresses(2_500L, 10_000L, 5))
        again.forgetPresses()
        assertEquals(emptyList(), again.recentPresses(2_500L, 10_000L, 5))
    }

    @Test
    fun nothingIsLoggedWhileHistoryIsOff() = runTest {
        val store = Lines()
        val recorder = SmartLogRecorder(SmartImpressionLog(store), backgroundScope, enabled = { false })
        recorder.planned("q", 0, 0, "core", listOf(row("a")))
        recorder.onNowPlaying(NowPlaying(track = t("a"), queue = listOf(t("a"))))
        runCurrent()
        assertTrue(store.text.isEmpty())
    }

    @Test
    fun favouriteChangesAreTheDifferenceOfTwoLists() {
        val (added, removed) = favoriteChanges(listOf(TrackId("a"), TrackId("b")), listOf(TrackId("c"), TrackId("a"), TrackId("c")))
        assertEquals(listOf(TrackId("c")), added)
        assertEquals(listOf(TrackId("b")), removed)
    }
}
