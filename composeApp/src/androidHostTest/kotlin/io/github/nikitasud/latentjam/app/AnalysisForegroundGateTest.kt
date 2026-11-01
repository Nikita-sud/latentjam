/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisForegroundGateTest {

    private class Recorder : AnalysisForegroundGate.Platform {
        val events = ArrayList<String>()
        var refuseStart = false

        override fun requestStart() {
            if (refuseStart) throw IllegalStateException("background start not allowed")
            events += "requestStart"
        }

        override fun post(content: AnalysisForegroundGate.Content) {
            events += "post ${content.done}"
        }
    }

    private val platform = Recorder()
    private val gate = AnalysisForegroundGate(platform)

    private fun content(done: Int) = AnalysisForegroundGate.Content("t", "x", done, 10)

    /** The service's onStartCommand: what it puts in the foreground, and whether it stops. */
    private fun deliverStart(failForeground: Boolean = false) = gate.onStartCommand(
        startForeground = {
            if (failForeground) throw IllegalStateException("startForeground refused")
            platform.events += "startForeground ${it.done}"
        },
        stop = { platform.events += "stop" },
    )

    @Test
    fun onlyTheFirstUpdateStartsAndTheServiceShowsTheLatest() {
        val session = gate.open()
        session.show(content(0))
        session.show(content(8))
        deliverStart()
        session.show(content(16))
        assertEquals(listOf("requestStart", "startForeground 8", "post 16"), platform.events)
    }

    @Test
    fun aStopBeforeStartForegroundIsDeferredToTheStartCommand() {
        val session = gate.open()
        session.show(content(0))
        session.close()
        assertEquals(listOf("requestStart"), platform.events)
        deliverStart()
        assertEquals(listOf("requestStart", "startForeground 0", "stop"), platform.events)
    }

    @Test
    fun aStopAfterStartForegroundStopsOnce() {
        val session = gate.open()
        session.show(content(0))
        deliverStart()
        session.close()
        session.close()
        session.show(content(8))
        assertEquals(listOf("requestStart", "startForeground 0", "stop"), platform.events)
    }

    @Test
    fun aHandoverKeepsTheServiceAndNeedsNoNewStart() {
        val old = gate.open()
        old.show(content(0))
        deliverStart()
        val replacement = gate.open()
        old.close()
        replacement.show(content(3))
        replacement.close()
        assertEquals(listOf("requestStart", "startForeground 0", "post 3", "stop"), platform.events)
    }

    @Test
    fun aReplacementWithNothingToDoStillStopsTheService() {
        val old = gate.open()
        old.show(content(0))
        deliverStart()
        val replacement = gate.open()
        old.close()
        replacement.close()
        assertEquals(listOf("requestStart", "startForeground 0", "stop"), platform.events)
    }

    @Test
    fun aNewRunAfterAStopStartsAgain() {
        val first = gate.open()
        first.show(content(0))
        deliverStart()
        first.close()
        gate.onDestroy()
        val second = gate.open()
        second.show(content(0))
        deliverStart()
        assertEquals(
            listOf("requestStart", "startForeground 0", "stop", "requestStart", "startForeground 0"),
            platform.events,
        )
    }

    @Test
    fun anUpdateBetweenStopAndDestroyStartsAFreshCommand() {
        val first = gate.open()
        first.show(content(0))
        deliverStart()
        first.close()
        val second = gate.open()
        second.show(content(1))
        deliverStart()
        assertEquals(
            listOf("requestStart", "startForeground 0", "stop", "requestStart", "startForeground 1"),
            platform.events,
        )
    }

    @Test
    fun noUpdateIsPostedAfterTheTimeout() {
        val session = gate.open()
        session.show(content(0))
        deliverStart()
        gate.onTimeout { platform.events += "stop" }
        session.show(content(8))
        session.close()
        assertEquals(listOf("requestStart", "startForeground 0", "stop"), platform.events)
    }

    @Test
    fun aRefusedStartPropagatesAndTheNextUpdateTriesAgain() {
        val session = gate.open()
        platform.refuseStart = true
        assertFailsWith<IllegalStateException> { session.show(content(0)) }
        platform.refuseStart = false
        session.show(content(8))
        deliverStart()
        assertEquals(listOf("requestStart", "startForeground 8"), platform.events)
    }

    @Test
    fun aRefusedStartForegroundStopsAndLosesTheForegroundState() {
        val session = gate.open()
        session.show(content(0))
        assertFailsWith<IllegalStateException> { deliverStart(failForeground = true) }
        session.show(content(8))
        session.close()
        assertEquals(listOf("requestStart", "stop"), platform.events)
    }

    @Test
    fun aStartCommandWithNoRunStopsAtOnce() {
        deliverStart()
        assertEquals(listOf("startForeground 0", "stop"), platform.events)
    }

    @Test
    fun concurrentRunsNeverPostAfterTheLastStop() {
        repeat(200) { round ->
            val recorder = Recorder()
            val racing = AnalysisForegroundGate(recorder)
            val sessions = List(3) { racing.open() }
            val threads = sessions.mapIndexed { index, session ->
                Thread {
                    repeat(20) { step -> session.show(AnalysisForegroundGate.Content("t", "x", step, 20)) }
                    if (index == round % 3) {
                        racing.onStartCommand(
                            startForeground = { synchronized(recorder) { recorder.events += "startForeground" } },
                            stop = { synchronized(recorder) { recorder.events += "stop" } },
                        )
                    }
                    session.close()
                }
            }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)
            val events = synchronized(recorder) { recorder.events.toList() }
            assertEquals(1, events.count { it == "requestStart" }, "round $round: $events")
            assertEquals("stop", events.last(), "round $round: $events")
            assertEquals(1, events.count { it == "stop" }, "round $round: $events")
        }
    }
}
