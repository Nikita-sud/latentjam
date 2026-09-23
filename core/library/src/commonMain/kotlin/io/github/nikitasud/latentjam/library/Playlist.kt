/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A user-made playlist: a name, ordered track ids, and an optional private cover reference. */
public data class Playlist(
    public val id: String,
    public val name: String,
    public val trackIds: List<String> = emptyList(),
    public val createdAtMs: Long = 0,
    /** The listener asked SMART to keep this playlist's tracks together (an explicit opt-in). */
    public val includeInSmart: Boolean = false,
    /** App-owned cover token, resolved by the platform; null uses artwork from the tracks. */
    public val customArtworkRef: String? = null,
)

/** Exact ordered membership around one durable playlist edit. */
public data class PlaylistTrackChange(
    public val before: List<TrackId>,
    public val after: List<TrackId>,
)

/**
 * The device's playlists. Ids rather than descriptors are stored, so a
 * playlist survives a rescan and never holds stale metadata.
 */
public interface Playlists {
    public suspend fun all(): List<Playlist>
    /** Creates a playlist and its initial membership in one durable transaction. */
    public suspend fun create(
        name: String,
        trackIds: List<TrackId> = emptyList(),
    ): Playlist
    public suspend fun rename(id: String, name: String)
    /**
     * Stores a durable, platform-owned cover reference. Null or blank restores automatic artwork.
     * Returns false if the playlist was deleted while a picker was open. The caller owns image
     * files and may discard an unused new image after false or a failed write.
     */
    public suspend fun setArtwork(id: String, artworkRef: String?): Boolean
    public suspend fun delete(id: String)
    public suspend fun addTracks(id: String, trackIds: List<TrackId>)
    public suspend fun removeTracks(
        id: String,
        trackIds: Collection<TrackId>,
    ): PlaylistTrackChange?

    public suspend fun removeTrack(id: String, trackId: TrackId): PlaylistTrackChange? =
        removeTracks(id, listOf(trackId))

    /** Exact ordered compare-and-set, used to Undo without clobbering a newer edit. */
    public suspend fun replaceTracksIfUnchanged(
        id: String,
        expected: List<TrackId>,
        replacement: List<TrackId>,
    ): Boolean

    /** Moves playlist [id] to [toIndex] in the user's ordering; out-of-range indices clamp. */
    public suspend fun move(id: String, toIndex: Int)

    /** Flips whether SMART keeps this playlist's tracks together. @return the new state. */
    public suspend fun toggleIncludeInSmart(id: String): Boolean

    /** Replaces all user playlists in one durable write, primarily for local backup restore. */
    public suspend fun replaceAll(playlists: List<Playlist>)
}

/** Whole-list storage; playlists are few and small. */
public interface PlaylistStore {
    public suspend fun read(): List<String>
    public suspend fun write(lines: List<String>)
}

/** Wall-clock milliseconds, for playlist ids and creation stamps. */
public expect fun nowMillis(): Long

/**
 * Default [Playlists]: an in-memory list written through to [PlaylistStore]
 * on every change. Duplicate tracks are ignored on add, so dragging the same
 * song in twice is a no-op rather than a surprise.
 */
public class DefaultPlaylists(
    private val store: PlaylistStore,
) : Playlists {

    private val mutex = Mutex()
    private var loaded = false
    private val playlists = mutableListOf<Playlist>()

    override suspend fun all(): List<Playlist> = mutex.withLock {
        ensureLoaded()
        playlists.toList()
    }

    override suspend fun create(
        name: String,
        trackIds: List<TrackId>,
    ): Playlist = mutex.withLock {
        ensureLoaded()
        val created = Playlist(
            id = "pl-${nowMillis()}-${playlists.size}",
            name = name.trim().ifEmpty { "Untitled playlist" },
            trackIds = trackIds.map(TrackId::value).filter(String::isNotBlank).distinct(),
            createdAtMs = nowMillis(),
        )
        persist(listOf(created) + playlists)
        created
    }

    override suspend fun rename(id: String, name: String): Unit = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index >= 0) {
            val changed = playlists[index].copy(name = name.trim().ifEmpty { "Untitled playlist" })
            persist(playlists.toMutableList().also { it[index] = changed })
        }
    }

    override suspend fun delete(id: String): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = playlists.filterNot { it.id == id }
        if (replacement.size != playlists.size) {
            persist(replacement)
        }
    }

    override suspend fun setArtwork(id: String, artworkRef: String?): Boolean = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false
        val normalized = artworkRef?.trim()?.takeIf(String::isNotEmpty)
        if (playlists[index].customArtworkRef == normalized) return@withLock true
        val changed = playlists[index].copy(customArtworkRef = normalized)
        persist(playlists.toMutableList().also { it[index] = changed })
        true
    }

    override suspend fun move(id: String, toIndex: Int): Unit = mutex.withLock {
        ensureLoaded()
        val from = playlists.indexOfFirst { it.id == id }
        if (from < 0 || playlists.isEmpty()) return@withLock
        val target = toIndex.coerceIn(0, playlists.lastIndex)
        if (from == target) return@withLock
        val reordered = playlists.toMutableList().apply { add(target, removeAt(from)) }
        persist(reordered)
    }

    override suspend fun toggleIncludeInSmart(id: String): Boolean = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false
        val changed = playlists[index].copy(includeInSmart = !playlists[index].includeInSmart)
        persist(playlists.toMutableList().also { it[index] = changed })
        changed.includeInSmart
    }

    override suspend fun addTracks(id: String, trackIds: List<TrackId>): Unit = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return@withLock
        val existing = playlists[index].trackIds
        // One call can contain the same selected track more than once. Seed the
        // set with the playlist's current contents, then let `add` both test and
        // reserve each id so duplicates in this batch are ignored as well.
        val seen = existing.toHashSet()
        val additions = trackIds.map { it.value }.filter(String::isNotBlank).filter(seen::add)
        if (additions.isNotEmpty()) {
            val changed = playlists[index].copy(trackIds = existing + additions)
            persist(playlists.toMutableList().also { it[index] = changed })
        }
    }

    override suspend fun removeTracks(
        id: String,
        trackIds: Collection<TrackId>,
    ): PlaylistTrackChange? = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        val before = playlists[index].trackIds
        val removedIds = trackIds.mapTo(HashSet(trackIds.size), TrackId::value)
        val remaining = before.filterNot(removedIds::contains)
        if (remaining.size != before.size) {
            val changed = playlists[index].copy(trackIds = remaining)
            persist(playlists.toMutableList().also { it[index] = changed })
        }
        PlaylistTrackChange(before.map(::TrackId), remaining.map(::TrackId))
    }

    override suspend fun replaceTracksIfUnchanged(
        id: String,
        expected: List<TrackId>,
        replacement: List<TrackId>,
    ): Boolean = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false
        val expectedValues = expected.map(TrackId::value)
        if (playlists[index].trackIds != expectedValues) return@withLock false
        val replacementValues = replacement
            .map(TrackId::value)
            .filter(String::isNotBlank)
            .distinct()
        if (replacementValues != expectedValues) {
            val changed = playlists[index].copy(trackIds = replacementValues)
            persist(playlists.toMutableList().also { it[index] = changed })
        }
        true
    }

    override suspend fun replaceAll(playlists: List<Playlist>): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = playlists.map { it.normalizedForRestore() }
        require(replacement.map(Playlist::id).toSet().size == replacement.size) {
            "Playlist ids must be unique"
        }
        persist(replacement)
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val seenIds = mutableSetOf<String>()
        val restored = store.read()
            .mapNotNull(PlaylistSerializer::parse)
            .filter { it.id.isNotBlank() }
            .map { playlist -> playlist.normalizedForRestore() }
            // A legacy/corrupt store can contain the same playlist more than
            // once. Keep the first valid occurrence in storage order so ids
            // remain unique for UI keys and mutations always target one item.
            .filter { playlist -> seenIds.add(playlist.id) }
        playlists += restored
        loaded = true
    }

    /**
     * Called with the mutex held: commit durable state and its cache together. A cancelled caller
     * may have already written to disk before returning from the store's dispatcher; publishing
     * the same state here prevents a later edit from silently overwriting that successful save.
     * A failed write still leaves the cache untouched.
     */
    private suspend fun persist(replacement: List<Playlist>): Unit = withContext(NonCancellable) {
        store.write(replacement.map(PlaylistSerializer::serialize))
        playlists.clear()
        playlists += replacement
    }

    private fun Playlist.normalizedForRestore(): Playlist {
        require(id.isNotBlank()) { "Playlist id cannot be blank" }
        return copy(
            name = name.trim().ifEmpty { "Untitled playlist" },
            trackIds = trackIds.filter(String::isNotBlank).distinct(),
            customArtworkRef = customArtworkRef?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}

/**
 * One playlist per line. Fields are separated by a unit separator rather than
 * a printable character, so a playlist called "Rock | Metal" survives.
 */
internal object PlaylistSerializer {

    private const val FIELD = ''
    private const val TRACK = ','
    private const val FORMAT_V2 = "v2"
    private const val FORMAT_V3 = "v3"
    private const val FORMAT_V4 = "v4"

    fun serialize(playlist: Playlist): String = listOf(
        FORMAT_V4,
        playlist.id.encodeHex(),
        playlist.name.encodeHex(),
        playlist.createdAtMs.toString(),
        playlist.trackIds.joinToString(TRACK.toString()) { it.encodeHex() },
        if (playlist.includeInSmart) "1" else "0",
        playlist.customArtworkRef.orEmpty().encodeHex(),
    ).joinToString(FIELD.toString())

    fun parse(line: String): Playlist? {
        val parts = line.split(FIELD)
        return when (parts.firstOrNull()) {
            FORMAT_V4 -> parseV4(parts)
            FORMAT_V3 -> parseV3(parts)
            FORMAT_V2 -> parseV2(parts)
            else -> parseV1(parts)
        }
    }

    /** Missing or damaged optional artwork falls back to automatic without losing the playlist. */
    private fun parseV4(parts: List<String>): Playlist? {
        if (parts.size !in 6..7) return null
        return parseV3(parts.subList(0, 6))?.copy(
            customArtworkRef = parts.getOrNull(6)?.decodeHex()?.takeIf(String::isNotBlank),
        )
    }

    /** v3 = v2 plus the SMART opt-in flag; a v2 line simply reads as "not opted in". */
    private fun parseV3(parts: List<String>): Playlist? {
        if (parts.size != 6) return null
        if (parts[5] != "0" && parts[5] != "1") return null
        return parseV2(parts.subList(0, 5))?.copy(includeInSmart = parts[5] == "1")
    }

    private fun parseV2(parts: List<String>): Playlist? {
        if (parts.size != 5) return null
        return Playlist(
            id = parts[1].decodeHex()?.ifEmpty { return null } ?: return null,
            name = parts[2].decodeHex() ?: return null,
            createdAtMs = parts[3].toLongOrNull() ?: return null,
            trackIds = parts[4].split(TRACK).filter(String::isNotEmpty).map { encoded ->
                encoded.decodeHex() ?: return null
            },
        )
    }

    /** Reader for the delimiter-based format written by older app versions. */
    private fun parseV1(parts: List<String>): Playlist? {
        if (parts.size != 4) return null
        return Playlist(
            id = parts[0].ifEmpty { return null },
            name = parts[1],
            createdAtMs = parts[2].toLongOrNull() ?: return null,
            trackIds = parts[3].split(TRACK).filter { it.isNotEmpty() },
        )
    }

    private fun String.encodeHex(): String = encodeToByteArray().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun String.decodeHex(): String? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
    }
}
