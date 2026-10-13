/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** One explicit request from a platform file browser, retained until the library can resolve it. */
data class ExternalAudioRequest(
    val id: String,
    val uri: String,
    /** Bound by the request owner, so rebuilding the UI cannot make a superseded request current. */
    val userPlaybackRevisionAtRequest: Long = 0L,
)

/** The platform owns delivery and URI resolution; playback still uses the library's track identity. */
interface ExternalAudioOpener {
    val pending: StateFlow<ExternalAudioRequest?>
    val active: StateFlow<Boolean>

    suspend fun resolve(
        request: ExternalAudioRequest,
        visibleTracks: List<TrackDescriptor>,
        library: MusicLibrary,
    ): TrackDescriptor?

    /** Claims only the current request, once. A newer request makes an older result obsolete. */
    fun consume(request: ExternalAudioRequest): Boolean
}

@Composable
internal fun ExternalAudioOpenEffect(
    opener: ExternalAudioOpener?,
    library: MusicLibrary,
    tracks: List<TrackDescriptor>?,
    playbackReady: Boolean,
    userPlaybackRevision: () -> Long,
    onBegin: () -> Unit,
    onPlay: suspend (TrackDescriptor) -> Unit,
    onUnavailable: () -> Unit,
) {
    if (opener == null) return
    val request by opener.pending.collectAsState()
    val active by opener.active.collectAsState()
    // The platform owner retains the original selection revision across UI recreation too.
    // A later tap in the app wins over this request even if its URI lookup finishes last.
    val currentRevision by rememberUpdatedState(userPlaybackRevision)
    val currentBegin by rememberUpdatedState(onBegin)
    val currentPlay by rememberUpdatedState(onPlay)
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    LaunchedEffect(opener, request, active, tracks, playbackReady) {
        val selected = request ?: return@LaunchedEffect
        val loaded = tracks ?: return@LaunchedEffect
        if (!active || !playbackReady) return@LaunchedEffect
        currentBegin()
        openExternalAudio(
            opener, selected, loaded, library,
            stillCurrent = { currentRevision() == selected.userPlaybackRevisionAtRequest },
            onPlay = { currentPlay(it) },
            onUnavailable = { currentUnavailable() },
        )
    }
}

/** Resolving is cancellable; once claimed, dispatch survives the pending-flow update or rotation. */
internal suspend fun openExternalAudio(
    opener: ExternalAudioOpener,
    request: ExternalAudioRequest,
    tracks: List<TrackDescriptor>,
    library: MusicLibrary,
    stillCurrent: () -> Boolean = { true },
    onPlay: suspend (TrackDescriptor) -> Unit,
    onUnavailable: () -> Unit,
) {
    val track = try {
        opener.resolve(request, tracks, library)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    currentCoroutineContext().ensureActive()
    if (!opener.active.value) return
    val superseded = !stillCurrent()
    if (!opener.consume(request) || superseded) return
    if (track == null) {
        onUnavailable()
        return
    }
    withContext(NonCancellable) {
        try {
            onPlay(track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onUnavailable()
        }
    }
}
