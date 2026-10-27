/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analysis_stalled
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analysis_stalled_track
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * A running analysis that has not moved for [ANALYSIS_STALL_AFTER] (issue #27): [trackId] is the track
 * it is waiting on when the engine knows it.
 */
internal data class AnalysisStall(val minutes: Int, val trackId: TrackId?)

/**
 * Longer than a track's whole analysis budget (90 s) plus a chunk's ordinary work, so a stall shown is
 * one the deadline did not end.
 */
internal val ANALYSIS_STALL_AFTER: Duration = 2.minutes

/**
 * Whether a [running] analysis is stalled: on one track for [onTrackFor] (when the engine names one),
 * otherwise without progress for [sinceProgress].
 */
internal fun analysisStall(
    running: Boolean,
    trackId: TrackId?,
    onTrackFor: Duration?,
    sinceProgress: Duration?,
): AnalysisStall? {
    if (!running) return null
    val waited = if (trackId != null && onTrackFor != null) onTrackFor else sinceProgress ?: return null
    if (waited < ANALYSIS_STALL_AFTER) return null
    return AnalysisStall(waited.inWholeMinutes.toInt(), trackId.takeIf { onTrackFor != null })
}

/** [analysisStall] for the current analysis, re-checked every few seconds while shown. */
@Composable
internal fun rememberAnalysisStall(engine: SimilarityEngine, indexing: AutomaticIndexingState): AnalysisStall? {
    val analysing by engine.analysing.collectAsState()
    val stall by produceState<AnalysisStall?>(null, indexing.running, indexing.progressAt, analysing) {
        while (true) {
            value = analysisStall(
                running = indexing.running,
                trackId = analysing?.trackId,
                onTrackFor = analysing?.started?.elapsedNow(),
                sinceProgress = indexing.progressAt?.elapsedNow(),
            )
            delay(STALL_RECHECK_MS)
        }
    }
    return stall
}

/** "Analysis has been waiting on “Title — Artist” for N min", or without a name when none is known. */
@Composable
internal fun analysisStallText(stall: AnalysisStall, tracks: List<TrackDescriptor>): String {
    val track = stall.trackId?.let { id -> tracks.firstOrNull { it.id == id } }
        ?: return stringResource(Res.string.intelligence_analysis_stalled, stall.minutes)
    val title = track.title ?: stringResource(Res.string.track_untitled)
    val name = joinBidiWrapped(listOfNotNull(title, track.artist), " — ")
    return stringResource(Res.string.intelligence_analysis_stalled_track, name, stall.minutes)
}

private const val STALL_RECHECK_MS = 15_000L
