/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.Playlists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

/** One file of the playlist cover folder: its name and when it was written (0 or less when unknown). */
internal class StoredPlaylistCover(val reference: String, val modifiedAtMs: Long)

/** Every file in the playlist cover folder; empty when there is no folder yet. */
internal expect suspend fun storedPlaylistCovers(): List<StoredPlaylistCover>

/** A cover no playlist names is left alone this long: a pick being saved, or a delete that Undo can still take back. */
internal const val PLAYLIST_COVER_ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L

/**
 * The covers among [stored] that are left over: named by none of the playlists ([referenced]) and written at least
 * [PLAYLIST_COVER_ORPHAN_AGE_MS] before [nowMs]. A file that is not a cover, or whose age is unknown, is never one.
 */
internal fun orphanPlaylistCovers(
    stored: List<StoredPlaylistCover>,
    referenced: Set<String>,
    nowMs: Long,
): List<String> = stored.filter { cover ->
    isPlaylistCoverReference(cover.reference) && cover.reference !in referenced &&
        cover.modifiedAtMs > 0 && nowMs - cover.modifiedAtMs >= PLAYLIST_COVER_ORPHAN_AGE_MS
}.map { it.reference }

/**
 * Removes the cover files no playlist names any more: a process that ended while a delete could still be undone, or
 * a restore that left a playlist out, leaves its image behind. Once per start, after the playlists were read: a read
 * that fails throws here, so nothing is removed against a list that is not the store's.
 */
internal suspend fun sweepOrphanPlaylistCovers(playlists: Playlists, nowMs: Long) {
    val referenced = playlists.all().mapNotNullTo(HashSet()) { it.customArtworkRef }
    orphanPlaylistCovers(storedPlaylistCovers(), referenced, nowMs).forEach { deletePlaylistCover(it) }
}

/** [sweepOrphanPlaylistCovers] as the app starts: a failure costs the clean-up, never the start. */
internal fun CoroutineScope.launchPlaylistCoverSweep(playlists: () -> Playlists, now: () -> Long): Job = launch {
    try {
        sweepOrphanPlaylistCovers(playlists(), now())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        println("Playlists: could not clear left-over covers: $failure")
    }
}

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
