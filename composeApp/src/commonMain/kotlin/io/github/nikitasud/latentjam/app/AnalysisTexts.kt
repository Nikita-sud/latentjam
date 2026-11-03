/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.analysis_action_continue
import io.github.nikitasud.latentjam.app.generated.resources.analysis_action_pause
import io.github.nikitasud.latentjam.app.generated.resources.analysis_paused_at
import io.github.nikitasud.latentjam.app.generated.resources.analysis_paused_by_you
import io.github.nikitasud.latentjam.app.generated.resources.analysis_pausing
import io.github.nikitasud.latentjam.app.generated.resources.analysis_waiting_battery
import io.github.nikitasud.latentjam.app.generated.resources.analysis_waiting_hot
import io.github.nikitasud.latentjam.app.generated.resources.analysis_waiting_screen
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The notification texts the analysis pass asks for, in the app's locale. */
internal suspend fun analysisNoticeText(notice: AnalysisNotice): String = when (notice) {
    is AnalysisNotice.Waiting -> getString(phaseText(notice.phase))
    is AnalysisNotice.PausedByYou -> getString(Res.string.analysis_paused_by_you, notice.done, notice.total)
    is AnalysisNotice.PausedAt -> getString(Res.string.analysis_paused_at, notice.done, notice.total)
    AnalysisNotice.PauseAction -> getString(Res.string.analysis_action_pause)
    AnalysisNotice.ContinueAction -> getString(Res.string.analysis_action_continue)
}

/** What a waiting or pausing pass shows instead of its progress and ETA. */
@Composable
internal fun analysisPhaseText(phase: AnalysisPhase): String = stringResource(phaseText(phase))

private fun phaseText(phase: AnalysisPhase): StringResource = when (phase) {
    AnalysisPhase.WAITING_BATTERY -> Res.string.analysis_waiting_battery
    AnalysisPhase.WAITING_HOT -> Res.string.analysis_waiting_hot
    AnalysisPhase.WAITING_SCREEN -> Res.string.analysis_waiting_screen
    AnalysisPhase.PAUSING, AnalysisPhase.RUNNING -> Res.string.analysis_pausing
}
