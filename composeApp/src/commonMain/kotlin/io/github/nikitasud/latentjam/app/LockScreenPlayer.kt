/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * When the lock-screen player opens. The Android host feeds it the screen and playback events;
 * nothing here touches the platform, so the rules are tested on every target.
 *
 * The player opens once per screen-off, only while music plays and the listener asked for it,
 * and never on top of itself. The guard is cleared when the screen comes back on.
 */
internal class LockPlayerTrigger {
    private var launchedThisScreenOff = false

    /** The screen went off; true when the lock player should be started now. */
    fun onScreenOff(enabled: Boolean, playing: Boolean, playerShowing: Boolean): Boolean {
        if (!enabled || !playing || playerShowing || launchedThisScreenOff) return false
        launchedThisScreenOff = true
        return true
    }

    /** The screen is on again, so the next screen-off may open the player again. */
    fun onScreenOn() {
        launchedThisScreenOff = false
    }
}

/**
 * Whether an open lock player has nothing left to show. A pause keeps it (the listener may have
 * paused from it); an empty player means playback stopped and its notification has gone too.
 */
internal fun lockPlayerShouldClose(now: NowPlaying): Boolean = now.track == null

/**
 * The full player as it appears above the lock screen. Everything that leads into the library
 * (menus, tag editing, going to an album, the map, saving the queue) is left out, because the
 * device is still locked; playback, the queue, lyrics and the heart work as in the app.
 */
@Composable
internal fun LockScreenPlayer(playback: PlaybackController, onClose: () -> Unit) {
    LatentJamAppTheme { darkTheme ->
        val settings = AppGraph.settings
        val trackColorMode by settings.trackColorMode.collectAsState()
        val appearance by settings.appearance.collectAsState()
        val smartQueueLength by settings.smartQueueLength.collectAsState()
        val track by remember(playback) {
            playback.state.map { it.track }.distinctUntilChanged()
        }.collectAsState(playback.state.value.track)
        val accent = rememberTrackAccent(
            track = track,
            mode = trackColorMode,
            darkTheme = darkTheme,
            customColor = Color(appearance.customAccent),
        )
        val sleepTimer = remember { AppGraph.sleepTimer }
        val sleepTimerState by sleepTimer.state.collectAsState()
        val queueSource by AppGraph.queueSource.collectAsState()
        val favorites = AppGraph.favorites
        val favoritesRevision by favorites.revision.collectAsState()
        var isFavorite by remember { mutableStateOf(false) }
        LaunchedEffect(track?.id, favoritesRevision) {
            val id = track?.id
            isFavorite = id != null && try {
                favorites.contains(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                println("Lock player: could not read favourites: $failure")
                false
            }
        }
        val scope = rememberCoroutineScope()
        NowPlayingScreen(
            playback = playback,
            accent = accent,
            queueSourceLabel = queueSource?.let { source ->
                source.name ?: source.kind.fallbackLabelRes()?.let { stringResource(it) }
            },
            sleepTimerState = sleepTimerState,
            onStartSleepTimer = sleepTimer::startCountdown,
            onSleepAtEndOfTrack = sleepTimer::startAtEndOfTrack,
            onCancelSleepTimer = sleepTimer::cancel,
            onTrackMenu = null,
            onAddQueueToPlaylist = null,
            onEditTags = null,
            isFavorite = isFavorite,
            onToggleFavorite = {
                val id = track?.id
                if (id != null) scope.launch {
                    // The revision moves after the write and re-reads the heart above.
                    runCatching { favorites.toggle(id) }
                        .onFailure { println("Lock player: could not toggle a favourite: $it") }
                }
            },
            smartQueueLength = smartQueueLength,
            onSmartQueueLength = settings::setSmartQueueLength,
            onClose = onClose,
        )
    }
}
