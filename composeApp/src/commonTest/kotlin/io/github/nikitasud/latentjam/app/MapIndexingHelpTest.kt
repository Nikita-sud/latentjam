/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

class MapIndexingHelpTest {

    private val library = List(3) { TrackId("t$it") }
    private val ready = EngineState.Ready(indexedCount = 0)

    @Test
    fun aRunningAnalysisShowsHowFarItHasGot() {
        val running = AutomaticIndexingState(trackIds = library, running = true, done = 1, progressKnown = true)
        assertEquals(MapIndexingHelp.Progress(1, 3), mapIndexingHelp(running, ready))
        // Once the pass can estimate it, the time left travels with the count.
        assertEquals(MapIndexingHelp.Progress(1, 3, 12), mapIndexingHelp(running.copy(etaMinutes = 12), ready))
        // Not counted yet: no "0 of N" first.
        assertEquals(MapIndexingHelp.Waiting, mapIndexingHelp(running.copy(progressKnown = false), ready))
    }

    @Test
    fun aPassThatStoppedBeforeTheMetadataPhaseOffersARetry() {
        // The endless "still reading": the pass ended (running false) with nothing ready.
        val stopped = AutomaticIndexingState(trackIds = library, running = false)
        assertEquals(MapIndexingHelp.Stopped(ready), mapIndexingHelp(stopped, ready))
    }

    @Test
    fun aFailedEngineOffersARetryWhateverThePassSays() {
        val failed = EngineState.Failed(EngineError.ModelUnavailable)
        assertEquals(MapIndexingHelp.Stopped(failed), mapIndexingHelp(AutomaticIndexingState(), failed))
    }

    @Test
    fun startingUpOrPastTheMetadataPhaseIsOrdinaryWaiting() {
        // No pass reported for the library yet.
        assertEquals(MapIndexingHelp.Waiting, mapIndexingHelp(AutomaticIndexingState(), EngineState.Initializing))
        // Metadata is ready; the regions are being worked out from it.
        val metadata = AutomaticIndexingState(trackIds = library, running = false, metadataReady = true)
        assertEquals(MapIndexingHelp.Waiting, mapIndexingHelp(metadata, ready))
    }
}
