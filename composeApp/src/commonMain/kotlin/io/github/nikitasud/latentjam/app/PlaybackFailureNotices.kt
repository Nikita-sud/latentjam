/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.playback_skipped_unreadable
import io.github.nikitasud.latentjam.app.generated.resources.playback_skipped_unsupported
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.playback.PlaybackController
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.getString

/**
 * Says why a track did not play. Playback moves past a file it cannot read, or one the device has no decoder for, by
 * itself: without a word a tap on one track simply starts another. The newest failure replaces the notice of the one
 * before, so a queue of such files shows one line, not a line each.
 */
@Composable
internal fun PlaybackFailureNotices(playback: PlaybackController, snackbar: SnackbarHostState) {
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(playback, snackbar, layoutDirection) {
        playback.playbackFailures.collectLatest { failure ->
            val title = playback.state.value.queue.firstOrNull { it.id == failure.trackId }?.title
                ?: getString(Res.string.track_untitled)
            val text = getString(
                if (failure.unsupportedFormat) Res.string.playback_skipped_unsupported else Res.string.playback_skipped_unreadable,
                bidiWrap(title, layoutDirection),
            )
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
}
