/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import androidx.compose.runtime.Composable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberPlaylistCoverPicker(onResult: (PlaylistCoverPickResult) -> Unit): () -> Unit =
    rememberSystemCoverPicker(
        key = "playlist-cover",
        import = { resolver, uri ->
            importCoverImage(resolver, uri, File(AndroidAppContext.value.filesDir, PLAYLIST_COVER_DIRECTORY), PLAYLIST_COVER_RULE)
        },
        isReference = ::isPlaylistCoverReference,
        delete = { deletePlaylistCover(it) },
    ) { outcome ->
        onResult(
            when (outcome) {
                is CoverPickOutcome.Picked -> PlaylistCoverPickResult.Selected(outcome.reference)
                CoverPickOutcome.Cancelled -> PlaylistCoverPickResult.Cancelled
                CoverPickOutcome.Failed -> PlaylistCoverPickResult.Failed
            },
        )
    }

internal actual fun playlistCoverUri(reference: String?): String? =
    reference?.takeIf(::isPlaylistCoverReference)?.let { Uri.fromFile(coverFile(it)).toString() }

internal actual suspend fun deletePlaylistCover(reference: String): Boolean = withContext(Dispatchers.IO) {
    if (!isPlaylistCoverReference(reference)) return@withContext false
    val file = coverFile(reference)
    !file.exists() || (file.isFile && file.delete())
}

private fun coverFile(reference: String): File =
    File(File(AndroidAppContext.value.filesDir, PLAYLIST_COVER_DIRECTORY), reference)
