/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.TrackIdentityTags
import io.github.nikitasud.latentjam.smart.PayloadHex
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.io.Writer
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
    private val read: () -> Reader?,
    private val write: ((Writer) -> Unit) -> Unit,
    private val readTags: (TrackDescriptor) -> TrackIdentityTags?,
) {
    private data class Entry(
        val id: String,
        val source: String,
        val volume: String,
        val extension: String,
        /** The duration, unboxed: [NO_DURATION] for none. */
        private val durationValue: Long,
        val revision: Revision,
        val checked: Revision?,
        val tags: TrackIdentityTags?,
    ) {
        val duration: Long? get() = durationValue.takeIf { it != NO_DURATION }
    }

    /**
     * What [remember] needs of the last scanned rows: their locator and the revision they were
     * registered under, which is each row's (immutable) entry as the scan registered it. Not the rows
     * themselves: the app republishes richer copies of every descriptor, so a retained list here was a
     * second copy of a 10,000-song library.
     */
    private var currentRows = emptyArray<Entry>()

    /**
     * The scanned rows' audio URIs where one is not MediaStore's own for its source (then
     * [MEDIA_STORE_URI]); null when none is, the usual case.
     */
    private var currentAudio: Array<String?>? = null

    private var entries: Map<String, Entry>? = null
    /** Source → app ID, only where they differ (a relocated file); everything else maps to itself. */
    private var sourceAliases = emptyMap<String, String>()
    /** One instance per distinct volume/extension: 10,000 rows share a handful of these. */
    private val shared = HashMap<String, String>()

    @Synchronized
    fun needsRelocationProbe(tracks: List<TrackDescriptor>): Boolean {
        val known = load()
        val ids = tracks.mapTo(HashSet()) { it.id.value }
        return known.values.any { it.tags?.releaseTrack != null && it.source !in ids } &&
            tracks.any { entryForSource(known, sourceAliases, it.id.value)?.volume != volume(it) }
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
        val aliases = sourceAliases
        val currentIds = tracks.mapTo(HashSet(tracks.size * 2)) { it.id.value }
        require(currentIds.size == tracks.size) { "Duplicate MediaStore rows" }
        fun bySource(source: String): Entry? = entryForSource(previous, aliases, source)
        val unknown = tracks.filter { bySource(it.id.value)?.volume != volume(it) }
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
                val old = bySource(track.id.value)
                val current = revision(track)
                val tags = if (old?.volume == volume(track) && old.checked != null &&
                    !current.isEmpty() && old.checked == current) old.tags
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
            val oldDuration = old.duration
            if (newGroups[key]?.size != 1 || old.source in allAudioIds ||
                volume(track) in failedVolumes || old.volume.isEmpty() || old.volume != volume(track) ||
                old.extension.isEmpty() || old.extension != extension(track) ||
                oldDuration == null || duration == null || oldDuration <= 0 || duration <= 0 ||
                kotlin.math.abs(oldDuration - duration) > 1_000 ||
                (old.tags?.recording != null && tags.recording != null && old.tags.recording != tags.recording)
            ) continue
            matches[track.id.value] = old
        }

        val next = LinkedHashMap<String, Entry>(previous)
        // IDs taken: every registered one, every current source, and those minted in this pass.
        val minted = HashSet<String>()
        val rows = arrayOfNulls<Entry>(tracks.size)
        var audio: Array<String?>? = null
        val resolved = tracks.mapIndexed { index, track ->
            cancellationCheck()
            val source = track.id.value
            val retained = bySource(source)?.takeIf { it.volume == volume(track) }
            val old = retained ?: matches[source]
            // The scan's own strings where the values agree: the decoded ones are a private copy.
            val id = old?.id?.let { if (it == source) source else it }
                ?: freshId(source) { it in previous || it in currentIds || it in minted }
            if (id != source && id !in previous) minted += id
            // A source number reappearing on a different volume must not steal an old app ID.
            bySource(source)?.takeIf { it.id != id }?.let { next[it.id] = it.copy(source = "") }
            val tags = if (facts.containsKey(source)) facts[source] else old?.tags
            // One instance for the entry, its check mark and the row: a compact descriptor forms a new string per read.
            val revision = revision(track)
            val checked = if (facts[source] != null) revision else old?.checked?.let {
                if (it == revision) revision else it
            }
            val entry = Entry(
                id, source, volume(track), extension(track), track.durationMs ?: NO_DURATION, revision, checked, tags,
            )
            next[id] = entry
            rows[index] = entry
            val audioUri = track.audioUri
            if (audioUri != "$MEDIA_STORE_SONGS$source") {
                val own = audio ?: arrayOfNulls<String>(tracks.size).also { created ->
                    created.fill(MEDIA_STORE_URI)
                    audio = created
                }
                own[index] = audioUri
            }
            if (id == source) track else track.copy(id = TrackId(id))
        }
        commit(next)
        @Suppress("UNCHECKED_CAST")
        currentRows = rows as Array<Entry>
        currentAudio = audio
        return resolved
    }

    /** Cheap launch first; learn metadata in the existing background enrichment phase. */
    fun remember(cancellationCheck: () -> Unit = {}) {
        // Published maps are never mutated, so the snapshot needs no copy.
        val (rows, known, aliases) = synchronized(this) { Triple(currentRows, load(), sourceAliases) }
        val audio = synchronized(this) { currentAudio }
        val updates = ArrayList<Pair<Entry, TrackIdentityTags>>()
        try {
            for ((index, row) in rows.withIndex()) {
                cancellationCheck()
                val old = entryForSource(known, aliases, row.source) ?: continue
                if (old.volume != row.volume || old.revision != row.revision ||
                    (!old.revision.isEmpty() && old.checked == old.revision)) continue
                val probe = TrackDescriptor(
                    id = TrackId(row.source),
                    audioUri = (audio?.get(index) ?: MEDIA_STORE_URI).let {
                        if (it === MEDIA_STORE_URI) "$MEDIA_STORE_SONGS${row.source}" else it
                    },
                    sourceRevision = row.revision.toString(),
                    storageVolume = row.volume,
                )
                val facts = safelyRead(probe) ?: continue
                updates += old to facts
            }
        } finally {
            // One atomic write per pass, including completed reads before cancellation. Do not
            // hold the registry monitor across file reads or rewrite a large registry per track.
            synchronized(this) {
                val next = LinkedHashMap(load())
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
        return (read()?.use { decode(it, ::shared) } ?: emptyMap()).also(::publish)
    }

    /** [next] must be a map this registry owns and never mutates again. */
    private fun commit(next: Map<String, Entry>) {
        // Publication follows durable storage. A failed save must never expose aliases that
        // disappear after a restart, or rewrite any of the user's other stores.
        if (next != entries) write { writer -> encode(next, writer) }
        // Published even when unchanged: these entries share their strings with the scan's rows,
        // where the decoded ones held a private copy of every ID, volume and revision.
        publish(next)
    }

    private fun publish(next: Map<String, Entry>) {
        entries = next
        val aliases = HashMap<String, String>()
        for (entry in next.values) if (entry.source.isNotEmpty() && entry.source != entry.id) aliases[entry.source] = entry.id
        sourceAliases = aliases
    }

    private fun freshId(source: String, taken: (String) -> Boolean): String {
        if (source !in load()) return source
        var suffix = 0
        var candidate: String
        do { candidate = "relocated:$source:${suffix++}" } while (taken(candidate))
        return candidate
    }

    private fun shared(value: String): String = shared.getOrPut(value) { value }

    private fun volume(track: TrackDescriptor): String = shared(track.storageVolume.orEmpty().lowercase())

    private fun extension(track: TrackDescriptor): String = shared(
        track.fileName.orEmpty().takeIf { '.' in it }?.substringAfterLast('.')?.lowercase().orEmpty(),
    )

    private companion object {
        const val HEADER = "latentjam-track-identities-v1"
        private const val MEDIA_STORE_SONGS = "content://media/external/audio/media/"
        private const val NO_DURATION = Long.MIN_VALUE

        /** Marks a scanned row whose audio URI is MediaStore's own for its source (formed when read, not held). */
        private val MEDIA_STORE_URI = String(charArrayOf('-'))

        fun revision(track: TrackDescriptor): Revision = Revision.of(track.sourceRevision.orEmpty())

        /** The registered entry whose source is [source]: sources are unique across entries. */
        fun entryForSource(known: Map<String, Entry>, aliases: Map<String, String>, source: String): Entry? =
            (aliases[source]?.let(known::get) ?: known[source])?.takeIf { it.source == source }

        fun line(it: Entry): String = buildString {
            val fields = arrayOf(it.id, it.source, it.volume, it.extension, it.duration?.toString().orEmpty(),
                it.revision.toString(), it.checked?.toString().orEmpty(), if (it.tags != null) "1" else "0",
                it.tags?.releaseTrack.orEmpty(), it.tags?.recording.orEmpty())
            for ((index, field) in fields.withIndex()) {
                if (index > 0) append('|')
                append(PayloadHex.encode(field))
            }
        }

        fun hexDigest(digest: MessageDigest): String {
            val bytes = digest.digest()
            val out = CharArray(bytes.size * 2)
            for (index in bytes.indices) {
                val byte = bytes[index].toInt() and 255
                out[index * 2] = HEX_DIGITS[byte ushr 4]
                out[index * 2 + 1] = HEX_DIGITS[byte and 15]
            }
            return out.concatToString()
        }

        const val HEX_DIGITS = "0123456789abcdef"

        /**
         * `HEADER \n sha256(body) \n body`, body = one line per entry joined by `\n`, sorted by ID.
         * Two passes over the entries (digest, then text) instead of the whole text in memory.
         */
        fun encode(values: Map<String, Entry>, writer: Writer) {
            val sorted = values.values.sortedBy { it.id }
            val digest = MessageDigest.getInstance("SHA-256")
            for ((index, entry) in sorted.withIndex()) {
                if (index > 0) digest.update('\n'.code.toByte())
                digest.update(line(entry).encodeToByteArray())
            }
            writer.write(HEADER)
            writer.write("\n")
            writer.write(hexDigest(digest))
            writer.write("\n")
            for ((index, entry) in sorted.withIndex()) {
                if (index > 0) writer.write("\n")
                writer.write(line(entry))
            }
        }

        /**
         * Streams the registry: lines split on `\n` only, the body digested as it is read, each
         * field decoded in place. Any damage, including a digest mismatch, is an IOException.
         */
        fun decode(reader: Reader, shared: (String) -> String): Map<String, Entry> {
            val input = if (reader is BufferedReader) reader else BufferedReader(reader, 16 * 1024)
            val digest = MessageDigest.getInstance("SHA-256")
            val result = LinkedHashMap<String, Entry>()
            val sources = HashSet<String>()
            val line = StringBuilder()
            var header: String? = null
            var expected: String? = null
            fun field(start: Int, end: Int): String =
                PayloadHex.decode(line, start, end) ?: throw IOException("Invalid identity field")
            fun entry() {
                digest.update(line.toString().encodeToByteArray())
                if (line.isEmpty()) return
                val fields = ArrayList<String>(10)
                var start = 0
                for (index in 0..line.length) {
                    if (index == line.length || line[index] == '|') {
                        fields += field(start, index)
                        start = index + 1
                    }
                }
                if (fields.size != 10 || fields[0].isEmpty() || fields[0] in result ||
                    (fields[1].isNotEmpty() && !sources.add(fields[1]))) throw IOException("Invalid identity registry")
                val tags = if (fields[7] == "1") TrackIdentityTags(
                    fields[8].ifEmpty { null }, fields[9].ifEmpty { null },
                ) else null
                val revision = Revision.of(fields[5])
                val checked = if (tags == null) null else if (fields[6] == fields[5]) revision else Revision.of(fields[6])
                result[fields[0]] = Entry(fields[0], fields[1], shared(fields[2]), shared(fields[3]),
                    fields[4].toLongOrNull() ?: NO_DURATION, revision, checked, tags)
            }
            while (true) {
                val char = input.read()
                if (char < 0) break
                if (char != '\n'.code) {
                    line.append(char.toChar())
                    continue
                }
                when {
                    header == null -> header = line.toString()
                    expected == null -> expected = line.toString()
                    else -> {
                        entry()
                        digest.update('\n'.code.toByte())
                    }
                }
                line.setLength(0)
            }
            // The body is everything after the second newline (possibly nothing).
            if (expected == null) throw IOException("Unreadable track identity registry; preserving the original")
            entry()
            if (header != HEADER || hexDigest(digest) != expected) {
                throw IOException("Unreadable track identity registry; preserving the original")
            }
            return result
        }
    }
}

/**
 * A song's source revision as the registry keeps it: MediaStore's `android-mediastore-v1:<size>:<modified>:<generation>`
 * as its three numbers, any other string as itself. Parsing is canonical (a number is kept only when it prints back
 * as written), so two revisions are equal exactly when their strings are, and [toString] is the string.
 */
internal class Revision private constructor(
    private val size: Long,
    private val modified: Long,
    private val generation: Long,
    private val text: String?,
) {
    fun isEmpty(): Boolean = text != null && text.isEmpty()

    override fun toString(): String = text ?: buildString {
        append(PREFIX)
        append(part(size)); append(':'); append(part(modified)); append(':'); append(part(generation))
    }

    override fun equals(other: Any?): Boolean = other is Revision &&
        other.size == size && other.modified == modified && other.generation == generation && other.text == text

    override fun hashCode(): Int =
        ((size.hashCode() * 31 + modified.hashCode()) * 31 + generation.hashCode()) * 31 + (text?.hashCode() ?: 0)

    companion object {
        private const val PREFIX = "android-mediastore-v1:"
        private const val ABSENT = Long.MIN_VALUE
        private val EMPTY = Revision(0, 0, 0, "")

        private fun part(value: Long): String = if (value == ABSENT) "-" else value.toString()

        /** The number [part] prints as itself, [ABSENT] for "-", null for anything else. */
        private fun number(part: String): Long? {
            if (part == "-") return ABSENT
            val value = part.toLongOrNull() ?: return null
            return value.takeIf { it != ABSENT && it.toString() == part }
        }

        fun of(text: String): Revision {
            if (text.isEmpty()) return EMPTY
            if (!text.startsWith(PREFIX)) return Revision(0, 0, 0, text)
            val parts = text.substring(PREFIX.length).split(':')
            if (parts.size != 3) return Revision(0, 0, 0, text)
            val size = number(parts[0])
            val modified = number(parts[1])
            val generation = number(parts[2])
            if (size == null || modified == null || generation == null) return Revision(0, 0, 0, text)
            return Revision(size, modified, generation, null)
        }
    }
}
