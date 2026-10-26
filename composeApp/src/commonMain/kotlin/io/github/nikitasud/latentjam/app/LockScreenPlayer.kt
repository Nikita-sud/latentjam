/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.lock_player_open_app
import kotlinx.coroutines.flow.MutableStateFlow
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
 * and never on top of itself. The guard is cleared when the screen comes back on. A player the
 * listener swiped away stays away until the device is next unlocked.
 */
internal class LockPlayerTrigger {
    private var launchedThisScreenOff = false
    private var dismissedUntilUnlock = false

    /** The screen went off; true when the lock player should be started now. */
    fun onScreenOff(enabled: Boolean, playing: Boolean, playerShowing: Boolean): Boolean {
        if (!enabled || !playing || playerShowing || launchedThisScreenOff || dismissedUntilUnlock) return false
        launchedThisScreenOff = true
        return true
    }

    /** The screen is on again, so the next screen-off may open the player again. */
    fun onScreenOn() {
        launchedThisScreenOff = false
    }

    /** The listener swiped the player away to the plain lock screen. */
    fun onDismissed() {
        dismissedUntilUnlock = true
    }

    /** The device was unlocked. */
    fun onUnlocked() {
        dismissedUntilUnlock = false
    }
}

/**
 * A way to act from the full player. [needsUnlock] marks the ones that open a dialog, sheet,
 * picker or another screen, or lead into the library: those wait for an unlocked device.
 */
enum class PlayerEntry(val needsUnlock: Boolean) {
    TRANSPORT(false),
    SEEK(false),
    REPEAT(false),
    MODE_CYCLE(false),
    FAVOURITE(false),
    QUEUE_PAGE(false),
    QUEUE_PLAY_REORDER_REMOVE(false),
    LYRICS(false),
    TRACK_MENU(true),
    QUEUE_ROW_MENU(true),
    ADD_QUEUE_TO_PLAYLIST(true),
    /** The cover's back face: track information, tag editing, map and equaliser links. */
    COVER_DETAILS(true),
    ARTIST_ALBUM_LINKS(true),
    SOURCE_LINK(true),
    OUTPUT_CHOOSER(true),
    /** The mode button's hold menu with the named modes and SMART queue settings. */
    MODE_MENU(true),
    SLEEP_TIMER(true),
}

/** Where the full player is shown; the lock screen offers only what needs no unlock. */
enum class PlayerSurface {
    APP,
    LOCK_SCREEN,
    ;

    fun allows(entry: PlayerEntry): Boolean = this == APP || !entry.needsUnlock
}

/**
 * Whether an open lock player has nothing left to show. A pause keeps it (the listener may have
 * paused from it); an empty player means playback stopped and its notification has gone too.
 */
internal fun lockPlayerShouldClose(now: NowPlaying): Boolean = now.track == null

/**
 * Asks the app's full player to open, from outside its composition: the lock player's LatentJam
 * button sets it after the unlock, and the app consumes it once ([PlaybackRestoreEffect]).
 */
internal object PlayerOpenRequest {
    val pending: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

/**
 * The full player as it appears above the lock screen: the same content, minus every entry point
 * that would need an unlock ([PlayerSurface.LOCK_SCREEN]). The seek bar stays, unlike Samsung
 * Music's lock player: it opens nothing.
 *
 * [onClose] (the close button, Back) asks for the unlock; [onOpenApp] (the LatentJam button) asks
 * for it too and then opens the player in the app; a downward swipe calls [onDismiss], which goes
 * back to the plain lock screen and leaves the device locked.
 */
@Composable
internal fun LockScreenPlayer(
    playback: PlaybackController,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
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
        // The swipe down moves the whole player with the finger; read only while drawing, so a
        // drag never recomposes the screen.
        var pull by remember { mutableFloatStateOf(0f) }
        var height by remember { mutableIntStateOf(0) }
        val flingPx = with(LocalDensity.current) { DISMISS_FLING.toPx() }
        fun settle(to: Float, then: () -> Unit = {}) {
            scope.launch {
                animate(pull, to) { value, _ -> pull = value }
                then()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { height = it.height }
                .graphicsLayer { translationY = pull },
        ) {
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
                surface = PlayerSurface.LOCK_SCREEN,
                onBrand = onOpenApp,
                onCollapseDrag = { delta -> pull = (pull + delta).coerceAtLeast(0f) },
                onCollapseRelease = { velocity ->
                    val closes = lockPlayerSwipeCloses(pull, velocity, height.toFloat(), flingPx)
                    if (closes) settle(height.toFloat(), onDismiss) else settle(0f)
                    closes
                },
                onCollapseAbandon = { settle(0f) },
                onClose = onClose,
            )
        }
    }
}

/** A swipe down past a quarter of the screen, or flung down, closes the lock player. */
internal fun lockPlayerSwipeCloses(pulledPx: Float, velocityPxPerS: Float, heightPx: Float, flingPxPerS: Float): Boolean =
    heightPx > 0f && (pulledPx > heightPx * DISMISS_FRACTION || (velocityPxPerS > flingPxPerS && pulledPx > 0f))

private const val DISMISS_FRACTION = 0.25f
private val DISMISS_FLING = 1000.dp

/** The lock player's small LatentJam button: unlock, then the player in the app. */
@Composable
internal fun BrandButton(onClick: () -> Unit) {
    val description = stringResource(Res.string.lock_player_open_app)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(end = 8.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp.corner()))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
    ) {
        Icon(
            imageVector = LatentJamMark,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            // The product name, the same in every language.
            text = "LatentJam",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
