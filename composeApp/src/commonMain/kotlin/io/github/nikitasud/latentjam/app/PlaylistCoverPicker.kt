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
internal fun coverDecodeSample(width: Int, height: Int, maxEdge: Int): Int? {
    if (width !in 1..100_000 || height !in 1..100_000) return null
    val edge = maxOf(width, height)
    var sample = 1
    while (edge / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

internal fun playlistCoverDecodeSample(width: Int, height: Int): Int? =
    coverDecodeSample(width, height, PLAYLIST_COVER_MAX_EDGE)

/** Enough of a file to hold a PNG's signature and its IHDR width and height. */
internal const val PNG_HEAD_BYTES = 24

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** The width and height a PNG's IHDR chunk states, or null when [head] is not the start of a PNG. */
internal fun pngSize(head: ByteArray): Pair<Int, Int>? {
    if (head.size < PNG_HEAD_BYTES) return null
    if (PNG_SIGNATURE.indices.any { head[it] != PNG_SIGNATURE[it] }) return null
    if (head.decodeToString(12, 16) != "IHDR") return null
    fun int(at: Int): Int = (0 until 4).fold(0) { value, i -> (value shl 8) or (head[at + i].toInt() and 0xFF) }
    return int(16) to int(20)
}
