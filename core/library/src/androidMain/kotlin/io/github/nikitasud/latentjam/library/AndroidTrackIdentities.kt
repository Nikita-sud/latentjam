/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.TrackIdentityTags
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Durable aliases at the MediaStore boundary. Existing app IDs remain unchanged, so playlists,
 * history, hidden tracks, favorites and backups need no multi-store migration. Audio/artwork URIs
 * and revisions always come from the CURRENT row; this does not reuse an audio-analysis cache.
 *
 * A release-track MBID is evidence only: one old and one current instance, same mounted volume,
 * same format and compatible duration. Every known duplicate (even an offline/deleted one) vetoes
 * relocation. A partial scan, failed read, absent baseline or recording-only tag never guesses.
 */
internal class AndroidTrackIdentities(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val readTags: (TrackDescriptor) -> TrackIdentityTags?,
) {
    private data class Entry(
        val id: String,
        val source: String,
        val volume: String,
        val extension: String,
        val duration: Long?,
        val revision: String,
        val checked: String?,
        val tags: TrackIdentityTags?,
    )

    private var entries: Map<String, Entry>? = null
    private var sourceAliases = emptyMap<String, String>()
    private var currentTracks = emptyList<TrackDescriptor>()

    @Synchronized
    fun needsRelocationProbe(tracks: List<TrackDescriptor>): Boolean {
        val known = load()
        val ids = tracks.mapTo(HashSet()) { it.id.value }
        return known.values.any { it.tags?.releaseTrack != null && it.source !in ids } &&
            tracks.any { sourceAliases[it.id.value]?.let(known::get)?.volume != volume(it) }
    }

    /** Query and this call must be serialized by the library, so an older query cannot win. */
    @Synchronized
    fun resolve(
        tracks: List<TrackDescriptor>,
        allAudioIds: Set<String>?,
        storageAvailable: Boolean,
        cancellationCheck: () -> Unit = {},
    ): List<TrackDescriptor> {
        val previous = load()
        require(tracks.map { it.id }.distinct().size == tracks.size) { "Duplicate MediaStore rows" }
        val bySource = previous.values.filter { it.source.isNotEmpty() }.associateBy { it.source }
        val currentIds = tracks.mapTo(HashSet()) { it.id.value }
        val unknown = tracks.filter { bySource[it.id.value]?.volume != volume(it) }
        val missing = previous.values.filter { it.source !in currentIds }
        val canRelocate = storageAvailable && allAudioIds != null &&
            missing.any { it.tags?.releaseTrack != null && it.source !in allAudioIds } && unknown.isNotEmpty()
        val facts = HashMap<String, TrackIdentityTags?>()
        val failedVolumes = HashSet<String>()
        if (canRelocate) {
            // Read all stale rows, including hidden/excluded ones: an unread duplicate must not
            // make a seemingly unique match. Cached current revisions require no file I/O.
            for (track in tracks) {
                cancellationCheck()
                val old = bySource[track.id.value]
                val tags = if (old?.volume == volume(track) && old.checked != null &&
                    revision(track).isNotEmpty() && old.checked == revision(track)) old.tags
                else safelyRead(track)
                facts[track.id.value] = tags
                if (tags == null) failedVolumes += volume(track)
            }
        }
        val oldGroups = previous.values.filter { it.tags?.releaseTrack != null }
            .groupBy { it.tags!!.releaseTrack!! }
        val newGroups = tracks.filter { facts[it.id.value]?.releaseTrack != null }
            .groupBy { facts[it.id.value]!!.releaseTrack!! }
        val matches = HashMap<String, Entry>()
        if (canRelocate) for (track in unknown) {
            val tags = facts[track.id.value] ?: continue
            val key = tags.releaseTrack ?: continue
            val old = oldGroups[key]?.singleOrNull() ?: continue
            val duration = track.durationMs
            if (newGroups[key]?.size != 1 || old.source in allAudioIds ||
                volume(track) in failedVolumes || old.volume.isEmpty() || old.volume != volume(track) ||
                old.extension.isEmpty() || old.extension != extension(track) ||
                old.duration == null || duration == null || old.duration <= 0 || duration <= 0 ||
                kotlin.math.abs(old.duration - duration) > 1_000 ||
                (old.tags?.recording != null && tags.recording != null && old.tags.recording != tags.recording)
            ) continue
            matches[track.id.value] = old
        }

        val next = previous.toMutableMap()
        val reserved = (previous.keys + currentIds).toHashSet()
        val resolved = tracks.map { track ->
            cancellationCheck()
            val source = track.id.value
            val retained = bySource[source]?.takeIf { it.volume == volume(track) }
            val old = retained ?: matches[source]
            val id = old?.id ?: freshId(source, reserved)
            reserved += id
            // A source number reappearing on a different volume must not steal an old app ID.
            bySource[source]?.takeIf { it.id != id }?.let { next[it.id] = it.copy(source = "") }
            val tags = if (facts.containsKey(source)) facts[source] else old?.tags
            val checked = if (facts[source] != null) revision(track) else old?.checked
            next[id] = Entry(id, source, volume(track), extension(track), track.durationMs, revision(track), checked, tags)
            if (id == source) track else track.copy(id = TrackId(id))
        }
        commit(next)
        currentTracks = tracks.toList()
        return resolved
    }

    /** Cheap launch first; learn metadata in the existing background enrichment phase. */
    fun remember(cancellationCheck: () -> Unit = {}) {
        val (snapshot, sources) = synchronized(this) {
            currentTracks.toList() to load().values.filter { it.source.isNotEmpty() }.associateBy { it.source }
        }
        val updates = ArrayList<Pair<Entry, TrackIdentityTags>>()
        try {
            for (track in snapshot) {
                cancellationCheck()
                val old = sources[track.id.value] ?: continue
                if (old.volume != volume(track) || old.revision != revision(track) ||
                    (old.revision.isNotEmpty() && old.checked == old.revision)) continue
                val facts = safelyRead(track) ?: continue
                updates += old to facts
            }
        } finally {
            // One atomic write per pass, including completed reads before cancellation. Do not
            // hold the registry monitor across file reads or rewrite a large registry per track.
            synchronized(this) {
                val next = load().toMutableMap()
                for ((old, facts) in updates) {
                    if (next[old.id] == old) next[old.id] = old.copy(tags = facts, checked = old.revision)
                }
                commit(next)
            }
        }
    }

    @Synchronized
    fun logicalId(source: String): TrackId {
        load()
        return TrackId(sourceAliases[source] ?: source)
    }

    @Synchronized
    fun sourceId(id: TrackId): String = load()[id.value]?.source ?: id.value

    private fun safelyRead(track: TrackDescriptor): TrackIdentityTags? = try {
        readTags(track)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun load(): Map<String, Entry> {
        entries?.let { return it }
        return (read()?.let(::decode) ?: emptyMap()).also(::publish)
    }

    private fun commit(next: Map<String, Entry>) {
        if (next == entries) return
        // Publication follows durable storage. A failed save must never expose aliases that
        // disappear after a restart, or rewrite any of the user's other stores.
        write(encode(next))
        publish(next.toMap())
    }

    private fun publish(next: Map<String, Entry>) {
        entries = next
        sourceAliases = next.values.filter { it.source.isNotEmpty() }.associate { it.source to it.id }
    }

    private fun freshId(source: String, reserved: Set<String>): String {
        if (source !in load()) return source
        var suffix = 0
        var candidate: String
        do { candidate = "relocated:$source:${suffix++}" } while (candidate in reserved)
        return candidate
    }

    private companion object {
        const val HEADER = "latentjam-track-identities-v1"
        fun volume(track: TrackDescriptor): String = track.storageVolume.orEmpty().lowercase()
        fun extension(track: TrackDescriptor): String = track.fileName.orEmpty()
            .takeIf { '.' in it }?.substringAfterLast('.')?.lowercase().orEmpty()
        fun revision(track: TrackDescriptor): String = track.sourceRevision.orEmpty()

        const val HEX = "0123456789abcdef"
        fun hex(value: String): String = buildString(value.length * 2) {
            for (byte in value.encodeToByteArray()) {
                append(HEX[(byte.toInt() and 255) ushr 4])
                append(HEX[byte.toInt() and 15])
            }
        }
        fun unhex(value: String): String {
            if (value.length % 2 != 0) throw IOException("Invalid identity field")
            return try {
                ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    .decodeToString(throwOnInvalidSequence = true)
            } catch (failure: Exception) { throw IOException("Invalid identity field", failure) }
        }
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun encode(values: Map<String, Entry>): String {
            val body = values.values.sortedBy { it.id }.joinToString("\n") {
                listOf(it.id, it.source, it.volume, it.extension, it.duration?.toString().orEmpty(),
                    it.revision, it.checked.orEmpty(), if (it.tags != null) "1" else "0",
                    it.tags?.releaseTrack.orEmpty(), it.tags?.recording.orEmpty()).joinToString("|", transform = ::hex)
            }
            return "$HEADER\n${digest(body)}\n$body"
        }
        fun decode(payload: String): Map<String, Entry> {
            val header = payload.split('\n', limit = 3)
            if (header.size != 3 || header[0] != HEADER || digest(header[2]) != header[1]) {
                throw IOException("Unreadable track identity registry; preserving the original")
            }
            val result = LinkedHashMap<String, Entry>()
            val sources = HashSet<String>()
            for (line in header[2].lineSequence().filter(String::isNotEmpty)) {
                val fields = line.split('|').map(::unhex)
                if (fields.size != 10 || fields[0].isEmpty() || fields[0] in result ||
                    (fields[1].isNotEmpty() && !sources.add(fields[1]))) throw IOException("Invalid identity registry")
                val tags = if (fields[7] == "1") TrackIdentityTags(
                    fields[8].ifEmpty { null }, fields[9].ifEmpty { null },
                ) else null
                result[fields[0]] = Entry(fields[0], fields[1], fields[2], fields[3], fields[4].toLongOrNull(),
                    fields[5], if (tags != null) fields[6] else null, tags)
            }
            return result
        }
    }
}
