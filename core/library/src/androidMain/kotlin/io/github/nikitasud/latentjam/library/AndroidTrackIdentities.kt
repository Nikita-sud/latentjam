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
    /** The text of the list of unchecked rows (see [unchecked]) as last written; null when there is none. */
    private val readUnchecked: () -> String? = { null },
    /** Replaces that list, atomically; an IOException when it cannot. */
    private val writeUnchecked: (String) -> Unit = {},
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

    /**
     * The published registry. Replaced by a new map on every scan; [remember] updates entries in place, under the
     * registry's monitor and only after they are on storage (see [commitInPlace]).
     */
    private var entries: MutableMap<String, Entry>? = null
    /** Source → app ID, only where they differ (a relocated file); everything else maps to itself. */
    private var sourceAliases = emptyMap<String, String>()
    /** One instance per distinct volume/extension: 10,000 rows share a handful of these. */
    private val shared = HashMap<String, String>()

    /**
     * The sources of registered entries that no scan has held since MediaStore last changed its database, so that
     * nothing has yet said whether their numbers still name their files. A scan under a new database checks the rows
     * it holds and records the version, but the provider may still be indexing, or a card may be out: the rows that
     * arrive later come under a database that is by then "the same", and were believed unchecked. Each waits here
     * until a scan holds its source, is judged then by the test of a new database ([sameFile]), and leaves.
     *
     * Null when no list is on record. The list is written before the caller records the version, and an empty list is
     * written as one, so a recorded version with no list (or a damaged one) means it was lost, or that the version
     * was recorded by a build that kept none: nothing says which rows were checked, and so every row is, once. That
     * costs little: a file unchanged since its last scan passes by its revision, and only one that was edited in place
     * with another length in between can lose its id, as at any change of the provider's version. With no version to
     * record (before Android 10) there is never a list, and the test of an unversioned provider is all there is.
     */
    private var unchecked: Set<String>? = null
    private var uncheckedRead = false

    private fun unchecked(): Set<String>? {
        if (!uncheckedRead) {
            unchecked = try {
                readUnchecked()?.let(::decodeUnchecked)
            } catch (_: IOException) {
                null
            }
            uncheckedRead = true
        }
        return unchecked
    }

    /** What is known of the row at [source] in a scan that knows [scan] of the provider: see [unchecked]. */
    private fun rowIdsFor(source: String, scan: RowIds): RowIds {
        if (scan == RowIds.NEW_DATABASE) return scan
        val waiting = unchecked()
        return when {
            waiting == null -> if (scan == RowIds.SAME_DATABASE) RowIds.NEW_DATABASE else scan
            source in waiting -> RowIds.NEW_DATABASE
            else -> scan
        }
    }

    /**
     * After a scan of the rows [scanned] is registered ([registered]): the entries it judged leave the list, and
     * under a new database (or with no list on record) every entry it did not hold joins it. An IOException when
     * the list cannot be saved: the caller must not record the provider's version after such a scan.
     */
    private fun recordUnchecked(registered: Map<String, Entry>, scanned: Set<String>, scan: RowIds) {
        val before = unchecked()
        val all = scan == RowIds.NEW_DATABASE || (scan == RowIds.SAME_DATABASE && before == null)
        if (!all && before.isNullOrEmpty()) return
        val after = HashSet<String>()
        for (entry in registered.values) {
            val source = entry.source
            if (source.isNotEmpty() && source !in scanned && (all || source in before!!)) after += source
        }
        if (after == before) return
        writeUnchecked(encodeUnchecked(after))
        unchecked = after
    }

    @Synchronized
    fun needsRelocationProbe(tracks: List<TrackDescriptor>, rowIds: RowIds = RowIds.UNVERSIONED): Boolean {
        val known = load()
        val ids = tracks.mapTo(HashSet()) { it.id.value }
        return known.values.any { it.tags?.releaseTrack != null && it.source !in ids } &&
            tracks.any { track -> entryForSource(known, sourceAliases, track.id.value)?.takeIf { sameFile(it, track, rowIds) } == null }
    }

    /**
     * Whether [track], the row now at [old]'s source, is the file [old] was registered for. A row id names one file
     * only for as long as MediaStore keeps its database: ids are never handed out twice within one, but a rebuilt
     * one (its data cleared in the system settings, a recovery after damage, the app's data restored onto another
     * phone) numbers the files anew, and playlists, favourites and history keyed by the old id would attach to
     * whatever file the number now names.
     *
     * What the scan knows of the provider ([scan]) decides, except for a row still waiting to be checked under the
     * provider's present database ([unchecked]), which is judged as under a new one:
     *
     * - [RowIds.SAME_DATABASE]: the row is believed, as it always was. An edit in place may change anything.
     * - [RowIds.NEW_DATABASE]: it is the same file when its revision (size, modified, generation) is the one
     *   registered, or when the format is and either the size in bytes is the same or the length is within a second.
     *   A retag in place changes the revision and may move the length a little; a file re-encoded to another format
     *   is another file. The size is there for a provider update, which changes the version with its schema while
     *   every row keeps its number: it reads the files again, and a length it now measures more than a second
     *   off, with the new generation it stamps, would cost that song its id. A rebuilt database handing the number
     *   to another file of the same format and exactly the same number of bytes is not a realistic collision (two
     *   constant-bitrate files that long to the byte are also the same length, which passed already).
     * - [RowIds.UNVERSIONED] (before Android 10 the provider cannot say which database it is): the test runs at
     *   every scan, so it must spare a file trimmed in place, which keeps its row and changes its length. The
     *   length is therefore believed only when the size also changed by more than half: a trim seldom takes that
     *   much, and a cover that doubles a file leaves its length alone. Two songs of one format and a similar size
     *   pass; that much reuse is not detected there.
     */
    private fun sameFile(old: Entry, track: TrackDescriptor, scan: RowIds): Boolean {
        if (old.volume != volume(track)) return false
        val rowIds = rowIdsFor(old.source, scan)
        if (rowIds == RowIds.SAME_DATABASE) return true
        val revision = revision(track)
        if (!revision.isEmpty() && old.revision == revision) return true
        val extension = extension(track)
        if (old.extension.isNotEmpty() && extension.isNotEmpty() && old.extension != extension) return false
        if (rowIds == RowIds.NEW_DATABASE && old.revision.sameSizeAs(revision)) return true
        if (rowIds == RowIds.UNVERSIONED && !old.revision.sizeChangedByMoreThanHalf(revision)) return true
        val before = old.duration
        val now = track.durationMs
        return before == null || now == null || kotlin.math.abs(before - now) <= SAME_FILE_DURATION_MS
    }

    /** Query and this call must be serialized by the library, so an older query cannot win. */
    @Synchronized
    fun resolve(
        tracks: List<TrackDescriptor>,
        allAudioIds: Set<String>?,
        storageAvailable: Boolean,
        rowIds: RowIds = RowIds.UNVERSIONED,
        cancellationCheck: () -> Unit = {},
    ): List<TrackDescriptor> {
        val previous = load()
        val aliases = sourceAliases
        // A query longer than one cursor window is refilled by running it again: rows inserted meanwhile (the system
        // scanner indexing a large copy) can deliver one row twice. The first stands; refusing the whole scan for it
        // ended the app on Refresh.
        val seenIds = HashSet<String>(tracks.size * 2)
        @Suppress("NAME_SHADOWING")
        val tracks = tracks.filter { seenIds.add(it.id.value) }
        val currentIds: Set<String> = seenIds
        fun bySource(source: String): Entry? = entryForSource(previous, aliases, source)
        fun retained(track: TrackDescriptor): Entry? = bySource(track.id.value)?.takeIf { sameFile(it, track, rowIds) }
        val unknown = tracks.filter { retained(it) == null }
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
                val old = retained(track)
                val current = revision(track)
                val tags = if (old?.checked != null &&
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
            val old = retained(track) ?: matches[source]
            // The scan's own strings where the values agree: the decoded ones are a private copy.
            val id = old?.id?.let { if (it == source) source else it }
                ?: freshId(source) { it in previous || it in currentIds || it in minted }
            if (id != source && id !in previous) minted += id
            // A source number reappearing on a different volume, or for another file, must not steal an old app ID.
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
        recordUnchecked(next, currentIds, rowIds)
        return resolved
    }

    /**
     * [tracks] under the app ids that are on storage already, for a scan whose registry [resolve] could not save
     * (storage full) or read (a damaged file): the library stays on screen, and nothing is shown that a restart
     * would take back. A row keeps the id of its registered entry. An unregistered one goes by its own number, as
     * [resolve] would name it, unless that number is an app id the registry gave to another song: such a row waits
     * for a scan that can be saved. Nothing is relocated, registered or written, so the caller must not take the
     * result for a complete scan.
     *
     * With no readable registry every row goes by its own number, and the file is left exactly as it is: a song
     * that had been relocated is then without what is kept under its old id, which nothing here can name.
     */
    @Synchronized
    fun durable(tracks: List<TrackDescriptor>, rowIds: RowIds = RowIds.UNVERSIONED): List<TrackDescriptor> {
        val known = try {
            load()
        } catch (_: IOException) {
            null
        }
        val aliases = sourceAliases
        val seen = HashSet<String>(tracks.size * 2)
        return tracks.mapNotNull { track ->
            val source = track.id.value
            if (!seen.add(source)) return@mapNotNull null
            if (known == null) return@mapNotNull track
            val entry = entryForSource(known, aliases, source)?.takeIf { sameFile(it, track, rowIds) }
            when {
                entry != null -> if (entry.id == source) track else track.copy(id = TrackId(entry.id))
                source in known -> null
                else -> track
            }
        }
    }

    /** Cheap launch first; learn metadata in the existing background enrichment phase. */
    fun remember(cancellationCheck: () -> Unit = {}) {
        // No copy of the published map: scans replace it with a new one, and only this pass's own
        // commits (on this thread, under the monitor) change it in place.
        val (rows, known, aliases) = synchronized(this) { Triple(currentRows, load(), sourceAliases) }
        val audio = synchronized(this) { currentAudio }
        val updates = ArrayList<Pair<Entry, TrackIdentityTags>>()
        val updatedRows = ArrayList<Int>()
        // Committed every REMEMBER_CHUNK reads and at the end, including completed reads before
        // cancellation. Do not hold the registry monitor across file reads or rewrite a large registry
        // per track; neither hold a first pass's reads for the whole library (~100 bytes a song).
        fun commitUpdates() {
            if (updates.isEmpty()) return
            synchronized(this) {
                val current = load()
                val changes = HashMap<String, Entry>()
                for ((old, facts) in updates) {
                    if (current[old.id] == old) changes[old.id] = old.copy(tags = facts, checked = old.revision)
                }
                commitInPlace(changes)
                // The scanned rows read the entries now registered, so the replaced ones can go.
                if (rows === currentRows) {
                    for ((k, index) in updatedRows.withIndex()) changes[updates[k].first.id]?.let { rows[index] = it }
                }
            }
            updates.clear()
            updatedRows.clear()
        }
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
                // A file whose tags do not fit in memory fails the same way at every launch: it is recorded as
                // read, with no tags, like a file that has none.
                val facts = safelyRead(probe, tooLarge = TrackIdentityTags()) ?: continue
                updates += old to facts
                updatedRows += index
                if (updates.size >= REMEMBER_CHUNK) commitUpdates()
            }
        } finally {
            commitUpdates()
        }
    }

    @Synchronized
    fun logicalId(source: String): TrackId {
        load()
        return TrackId(sourceAliases[source] ?: source)
    }

    @Synchronized
    fun sourceId(id: TrackId): String = load()[id.value]?.source ?: id.value

    /** The storage volumes the registered tracks are on, as a scan names them (lower case). */
    @Synchronized
    fun volumes(): Set<String> = load().values.mapNotNullTo(HashSet()) { it.volume.takeIf(String::isNotEmpty) }

    /**
     * [track]'s tags, or null when they could not be read. [tooLarge] is the answer for a file that
     * breaks the reader with an Error (a tag larger than the heap is an OutOfMemoryError): one file
     * another app wrote must not end the scan or the process.
     */
    private fun safelyRead(track: TrackDescriptor, tooLarge: TrackIdentityTags? = null): TrackIdentityTags? = try {
        readTags(track)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } catch (_: Throwable) {
        tooLarge
    }

    private fun load(): MutableMap<String, Entry> {
        entries?.let { return it }
        return (read()?.use { decode(it, ::shared) } ?: LinkedHashMap()).also(::publish)
    }

    /**
     * Stores [changes] (entries of ids already registered, with their ids and sources unchanged) and only then
     * replaces them in the published map, in place: a full copy of a 100,000-song registry per commit was as large
     * as the registry itself. Callers hold the monitor.
     */
    private fun commitInPlace(changes: Map<String, Entry>) {
        if (changes.isEmpty()) return
        val current = load()
        write { writer -> encode(current.values.map { changes[it.id] ?: it }, writer) }
        for ((id, entry) in changes) current[id] = entry
    }

    /** [next] must be a map this registry owns; only [commitInPlace] changes it afterwards. */
    private fun commit(next: MutableMap<String, Entry>) {
        // Publication follows durable storage. A failed save must never expose aliases that
        // disappear after a restart, or rewrite any of the user's other stores.
        if (next != entries) write { writer -> encode(next.values, writer) }
        // Published even when unchanged: these entries share their strings with the scan's rows,
        // where the decoded ones held a private copy of every ID, volume and revision.
        publish(next)
    }

    private fun publish(next: MutableMap<String, Entry>) {
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

        /** How far MediaStore's length for one file moves between two readings of it (a retag, another extractor). */
        private const val SAME_FILE_DURATION_MS = 1_000L

        /** Tag reads per registry commit in [remember]. */
        private const val REMEMBER_CHUNK = 16_384

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

        /** First line of the list of unchecked rows; one source on each line after it. */
        const val UNCHECKED_HEADER = "latentjam-unchecked-rows-v1"

        fun encodeUnchecked(sources: Set<String>): String =
            (listOf(UNCHECKED_HEADER) + sources.sortedWith(compareBy<String> { it.length }.thenBy { it })).joinToString("\n")

        /** Null for anything this did not write (an empty or cut file included): then no list is on record. */
        fun decodeUnchecked(text: String): Set<String>? {
            val lines = text.split('\n')
            if (lines.first() != UNCHECKED_HEADER) return null
            return lines.drop(1).filterTo(HashSet(), String::isNotEmpty)
        }

        /**
         * `HEADER \n sha256(body) \n body`, body = one line per entry joined by `\n`, sorted by ID.
         * Two passes over the entries (digest, then text) instead of the whole text in memory.
         */
        fun encode(values: Collection<Entry>, writer: Writer) {
            val sorted = values.sortedBy { it.id }
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
        fun decode(reader: Reader, shared: (String) -> String): MutableMap<String, Entry> {
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

/** What a scan knows of whether MediaStore's row ids still name the files they named when the registry was saved. */
internal enum class RowIds {
    /** The provider's database is the one the registry was last saved under. */
    SAME_DATABASE,

    /** Another database than at the last save, or none on record: every row id may name another file now. */
    NEW_DATABASE,

    /** The provider does not say which database it is (before Android 10, or it could not be asked). */
    UNVERSIONED,
}

/** [RowIds] from MediaStore's version now ([current], null when it has none) and the one last scanned under. */
internal fun rowIds(current: String?, recorded: String?): RowIds = when {
    current == null -> RowIds.UNVERSIONED
    current == recorded -> RowIds.SAME_DATABASE
    else -> RowIds.NEW_DATABASE
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

    /** Whether both name the same size in bytes, of a file that has any; false when either has none. */
    fun sameSizeAs(other: Revision): Boolean = text == null && other.text == null && size > 0 && size == other.size

    /** Whether both name a size and one is more than twice the other; false when either has none. */
    fun sizeChangedByMoreThanHalf(other: Revision): Boolean {
        if (text != null || other.text != null || size < 0 || other.size < 0) return false
        return minOf(size, other.size) < maxOf(size, other.size) / 2
    }

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
