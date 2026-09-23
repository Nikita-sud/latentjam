/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/** Only [Selected.reference] is persisted; absolute sandbox paths may change on iOS. */
internal sealed interface PlaylistCoverPickResult {
    data class Selected(val reference: String) : PlaylistCoverPickResult
    data object Cancelled : PlaylistCoverPickResult
    data object Failed : PlaylistCoverPickResult
}

/** Opens the system image picker on explicit invocation; results are delivered on the UI thread. */
@Composable
internal expect fun rememberPlaylistCoverPicker(
    onResult: (PlaylistCoverPickResult) -> Unit,
): () -> Unit

/** Resolves a validated app-owned reference without reading or decoding its file. */
internal expect fun playlistCoverUri(reference: String?): String?

/** Delete only after the playlist's replacement/reset/deletion has been durably saved. */
internal expect suspend fun deletePlaylistCover(reference: String): Boolean

internal const val PLAYLIST_COVER_MAX_EDGE = 768
internal const val PLAYLIST_COVER_DIRECTORY = "playlist-covers"

private val playlistCoverReferencePattern =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")

internal fun isPlaylistCoverReference(reference: String?): Boolean =
    reference != null && reference.length == 40 && playlistCoverReferencePattern.matches(reference)

/** Decode at no more than twice the output edge, then do the final orientation/scale together. */
internal fun playlistCoverDecodeSample(width: Int, height: Int): Int? {
    if (width !in 1..100_000 || height !in 1..100_000) return null
    val edge = maxOf(width, height)
    var sample = 1
    while (edge / (sample * 2) >= PLAYLIST_COVER_MAX_EDGE) sample *= 2
    return sample
}
