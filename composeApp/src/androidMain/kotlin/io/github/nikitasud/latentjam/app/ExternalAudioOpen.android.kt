/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Small saved metadata survives a permission prompt or process recreation; consumed intents do not. */
internal class AndroidExternalAudioOpener(
    private val handle: SavedStateHandle,
    private val userPlaybackRevision: () -> Long = { 0L },
    private val readPlaylist: suspend (String) -> ImportedLocalDocument = {
        error("This opener reads no playlists")
    },
    private val lookup: suspend (String, List<TrackDescriptor>, MusicLibrary) -> TrackDescriptor?,
) : ViewModel(), ExternalAudioOpener, ExternalPlaylistOpener {
    private val pendingRequest = MutableStateFlow(
        handle.get<ArrayList<String>>(PENDING_KEY)?.takeIf { it.size == 2 }?.let {
            // The process owns the playback counter. A new process starts from its current
            // counter; Activity recreation keeps this ViewModel and its original baseline.
            ExternalAudioRequest(it[0], it[1], userPlaybackRevision())
        },
    )
    override val pending: StateFlow<ExternalAudioRequest?> = pendingRequest
    private val foreground = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = foreground

    fun setActive(active: Boolean) { foreground.value = active }

    // Held in memory only: the text may be large, and a read result needs no second read after a
    // recreation, while the sender's grant may be gone by the time a new process could retry it.
    private val playlistResult = MutableStateFlow<LocalBackupFileResult<ImportedLocalDocuments>?>(null)
    override val pendingPlaylist: StateFlow<LocalBackupFileResult<ImportedLocalDocuments>?> = playlistResult
    private var playlistRead: Job? = null

    override fun consumePlaylist(result: LocalBackupFileResult<ImportedLocalDocuments>): Boolean =
        playlistResult.compareAndSet(result, null)

    fun submit(action: String?, uri: String?, mimeType: String? = null) {
        if (action != "android.intent.action.VIEW" || uri.isNullOrBlank()) return
        if (isPlaylistRequest(mimeType, uri)) {
            // Read now, while the activity that received the grant is alive: a playlist is never
            // played, so it neither replaces a pending track request nor waits for the library.
            playlistRead?.cancel()
            playlistRead = viewModelScope.launch {
                playlistResult.value = try {
                    LocalBackupFileResult.Success(ImportedLocalDocuments(listOf(readPlaylist(uri))))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LocalBackupFileResult.Failure(failure.message ?: "Could not read the playlist")
                } catch (_: OutOfMemoryError) {
                    LocalBackupFileResult.Failure("Playlist exceeds available memory")
                }
            }
            return
        }
        val request = ExternalAudioRequest(UUID.randomUUID().toString(), uri, userPlaybackRevision())
        handle[PENDING_KEY] = arrayListOf(request.id, request.uri)
        pendingRequest.value = request
    }

    override suspend fun resolve(
        request: ExternalAudioRequest,
        visibleTracks: List<TrackDescriptor>,
        library: MusicLibrary,
    ): TrackDescriptor? = lookup(request.uri, visibleTracks, library)

    override fun consume(request: ExternalAudioRequest): Boolean {
        if (pendingRequest.value != request) return false
        handle.remove<ArrayList<String>>(PENDING_KEY)
        pendingRequest.value = null
        return true
    }

    private companion object { const val PENDING_KEY = "external-audio-request" }
}

/** Never creates a track from an arbitrary URI: hidden, unindexed and inaccessible files fail closed. */
internal suspend fun resolveExternalAudio(
    context: Context,
    source: String,
    visibleTracks: List<TrackDescriptor>,
    library: MusicLibrary,
): TrackDescriptor? = withContext(Dispatchers.IO) {
    val target = externalAudioTarget(source) ?: return@withContext null
    var track = matchExternalAudio(target, visibleTracks)
    if (track == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.scheme == "content") {
        // The platform can translate document aliases to a MediaStore row; a provider failure
        // must not prevent the exact volume/path fallback supported by older Android versions.
        val mediaUri = try {
            MediaStore.getMediaUri(context, Uri.parse(source))?.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        track = mediaUri?.let(::externalAudioTarget)?.let { matchExternalAudio(it, visibleTracks) }
    }
    if (track == null && target.absolutePath != null) {
        val paths = library.filePaths(visibleTracks.map { it.id })
        track = matchExternalAudio(target, visibleTracks, paths)
    }
    val matched = track ?: return@withContext null
    currentCoroutineContext().ensureActive()
    val playable = matched.audioUri ?: return@withContext null
    // A row may still exist after an SD card disappears. Probe the URI playback will actually
    // use, not the sender's temporary grant, before replacing the listener's current queue.
    val readable = try {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(playable), "r")?.use { true } ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (readable) matched else null
}
