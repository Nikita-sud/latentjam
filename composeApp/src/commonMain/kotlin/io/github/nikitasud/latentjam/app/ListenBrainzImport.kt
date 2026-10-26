/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.text.UnicodeForm
import io.github.nikitasud.latentjam.smart.text.normalizeUnicode
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Finds the library track a ListenBrainz row names. The index is built once, so a match costs a
 * few hash lookups rather than a pass over the library.
 *
 * LatentJam does not carry recording MBIDs on its tracks (the Android tag reader keeps them only
 * for relocating moved files, and iOS never reads them), so matching is by text:
 *
 * 1. Artist and title after a strict normalisation: Unicode NFKC, lowercase, typographic quotes
 *    made plain, whitespace collapsed.
 * 2. Only when step 1 finds nothing: a loose form of both, with diacritics dropped, "feat./ft./
 *    featuring" credits removed, trailing bracketed or dash suffixes such as "(Remastered 2011)" or
 *    " - Live" removed, "&" read as "and" and punctuation dropped. The loose artist is also indexed
 *    under each credited individual of the track's own tags.
 * 3. Steps 1 and 2 again with MusicBrainz's names (`mbid_mapping`) when ListenBrainz mapped the
 *    listen and the names as submitted found nothing.
 *
 * Several candidates (copies of one song, an album and a compilation, a live version) are narrowed
 * by the release name, then by duration: with a duration on the row the closest candidate within
 * [DURATION_TOLERANCE_MS] wins; without one the candidates must all last about as long (copies of
 * one recording), and the first in id order is taken. Anything else is ambiguous and matches none —
 * a listen credited to the wrong version teaches SMART a transition that never happened.
 */
internal class ListenBrainzMatcher(library: List<TrackDescriptor>) {
    /** Library tracks in id order, so ties resolve the same way on every run. */
    val tracks: List<TrackDescriptor> = library.distinctBy { it.id }.sortedBy { it.id.value }
    private val strict = HashMap<String, IntArray>()
    private val loose = HashMap<String, IntArray>()
    private val albums = arrayOfNulls<String>(tracks.size)
    private val lookups = HashMap<String, Lookup>()

    init {
        val strictLists = HashMap<String, MutableList<Int>>()
        val looseLists = HashMap<String, MutableList<Int>>()
        tracks.forEachIndexed { index, track ->
            albums[index] = strictForm(track.album)
            val title = track.title ?: return@forEachIndexed
            val strictTitle = strictForm(title)
            val artists = listOfNotNull(track.artist) + track.artists
            if (strictTitle != null) {
                for (artist in artists) {
                    val key = strictForm(artist)?.let { key(it, strictTitle) } ?: continue
                    strictLists.getOrPut(key) { mutableListOf() }.addDistinct(index)
                }
            }
            val looseTitle = looseForm(title, isTitle = true) ?: return@forEachIndexed
            for (artist in artists) {
                val key = looseForm(artist, isTitle = false)?.let { key(it, looseTitle) } ?: continue
                looseLists.getOrPut(key) { mutableListOf() }.addDistinct(index)
            }
        }
        strictLists.forEach { (key, list) -> strict[key] = list.toIntArray() }
        looseLists.forEach { (key, list) -> loose[key] = list.toIntArray() }
    }

    /** The index into [tracks] the row names, or [NONE] / [AMBIGUOUS]. */
    fun match(row: ListenBrainzRow): Int {
        val submitted = candidates(row.artist, row.title)
        if (submitted is Lookup.Found) return choose(submitted.indices, row)
        val mapped = if (row.mappedArtist != null || row.mappedTitle != null) {
            candidates(row.mappedArtist ?: row.artist, row.mappedTitle ?: row.title)
        } else {
            Lookup.Missing
        }
        return if (mapped is Lookup.Found) choose(mapped.indices, row) else NONE
    }

    private sealed interface Lookup {
        data object Missing : Lookup
        class Found(val indices: IntArray) : Lookup
    }

    /**
     * The candidates for one artist and title. An export repeats the same few thousand songs many
     * times, so the normalised lookups are remembered (bounded by [MAX_REMEMBERED_LOOKUPS]).
     */
    private fun candidates(artist: String?, title: String?): Lookup {
        if (artist == null || title == null) return Lookup.Missing
        val raw = artist + '\u0000' + title
        lookups[raw]?.let { return it }
        val found = lookup(artist, title)
        if (lookups.size < MAX_REMEMBERED_LOOKUPS) lookups[raw] = found
        return found
    }

    private fun lookup(artist: String, title: String): Lookup {
        val strictArtist = strictForm(artist)
        val strictTitle = strictForm(title)
        if (strictArtist != null && strictTitle != null) {
            strict[key(strictArtist, strictTitle)]?.let { return Lookup.Found(it) }
        }
        val looseArtist = looseForm(artist, isTitle = false) ?: return Lookup.Missing
        val looseTitle = looseForm(title, isTitle = true) ?: return Lookup.Missing
        return loose[key(looseArtist, looseTitle)]?.let { Lookup.Found(it) } ?: Lookup.Missing
    }

    private fun choose(candidates: IntArray, row: ListenBrainzRow): Int {
        if (candidates.size == 1) return candidates[0]
        var pool = candidates.toList()
        strictForm(row.release)?.let { release ->
            val sameRelease = pool.filter { albums[it] == release }
            if (sameRelease.isNotEmpty()) pool = sameRelease
        }
        if (pool.size == 1) return pool[0]
        val duration = row.durationMs
        if (duration != null) {
            return pool
                .mapNotNull { index -> tracks[index].durationMs?.let { index to kotlin.math.abs(it - duration) } }
                .filter { it.second <= DURATION_TOLERANCE_MS }
                .minWithOrNull(compareBy<Pair<Int, Long>> { it.second }.thenBy { it.first })
                ?.first
                ?: AMBIGUOUS
        }
        val durations = pool.mapNotNull { tracks[it].durationMs }
        val sameLength = durations.isEmpty() || durations.max() - durations.min() <= DURATION_TOLERANCE_MS
        return if (sameLength) pool.min() else AMBIGUOUS
    }

    companion object {
        const val NONE: Int = -1
        const val AMBIGUOUS: Int = -2

        /** How far apart two durations may be and still describe one recording. */
        const val DURATION_TOLERANCE_MS: Long = 10_000L

        private const val MAX_REMEMBERED_LOOKUPS = 200_000

        private val WHITESPACE = Regex("\\s+")
        private val FEATURING = Regex("\\s*[(\\[]?\\s*\\b(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
        private val BRACKETED_SUFFIX = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")
        private val DASH_SUFFIX = Regex(
            "\\s+[-–—]\\s+[^-–—]*\\b(remaster(ed)?|live|version|edit|mix|remix|mono|stereo|deluxe|bonus|single|acoustic|demo|instrumental)\\b[^-–—]*$",
            RegexOption.IGNORE_CASE,
        )
        private val PUNCTUATION = Regex("[^\\p{L}\\p{N} ]+")

        private fun key(artist: String, title: String) = artist + '\u0000' + title

        private fun MutableList<Int>.addDistinct(index: Int) {
            if (lastOrNull() != index) add(index)
        }

        /** NFKC, lowercase, plain quotes, single spaces; null when nothing is left. */
        internal fun strictForm(value: String?): String? {
            if (value == null) return null
            val normalized = normalizeUnicode(value, UnicodeForm.NFKC).lowercase()
                .replace('’', '\'').replace('‘', '\'').replace('`', '\'').replace('´', '\'')
                .replace('“', '"').replace('”', '"')
                .trim()
                .replace(WHITESPACE, " ")
            return normalized.takeIf(String::isNotEmpty)
        }

        /** [strictForm] without diacritics, credits, version suffixes or punctuation. */
        internal fun looseForm(value: String?, isTitle: Boolean): String? {
            var text = strictForm(value) ?: return null
            text = buildString {
                for (char in normalizeUnicode(text, UnicodeForm.NFKD)) {
                    if (char.category != CharCategory.NON_SPACING_MARK) append(char)
                }
            }
            text = text.replace(FEATURING, "")
            if (isTitle) {
                while (true) {
                    val stripped = text.replace(BRACKETED_SUFFIX, "").replace(DASH_SUFFIX, "")
                    if (stripped == text || stripped.isBlank()) break
                    text = stripped
                }
            }
            text = text.replace("&", " and ").replace(PUNCTUATION, " ").trim().replace(WHITESPACE, " ")
            return text.takeIf(String::isNotEmpty)
        }
    }
}

/** What one ListenBrainz import did. */
internal data class ListenBrainzImportReport(
    /** Listens the export holds (rows read). */
    val listensRead: Long,
    /** Listens added to the history. */
    val imported: Int,
    /** Library songs those listens belong to. */
    val songs: Int,
    /** Listens of songs this library does not hold (or holds too ambiguously to pick one). */
    val notInLibrary: Long,
    /** Listens the history already had: the same file imported again, or a play recorded here. */
    val alreadyPresent: Int,
    /** The oldest listens left out because the history holds at most [ListenBrainzImporter.MAX_HISTORY_EVENTS]. */
    val overLimit: Int,
    /** Records that could not be read as a listen. */
    val unreadable: Long,
    /** True when the export held more rows than [ListenBrainzExportReader.MAX_ROWS]. */
    val truncated: Boolean,
)

/** The library returned no tracks, so nothing could match (no permission yet, or a failed scan). */
internal class ListenBrainzLibraryEmptyException : IllegalStateException("The library holds no tracks")

/**
 * Loads a ListenBrainz export into the listening history.
 *
 * Every matched listen is written in the shape the rest of the app reads as an import: no origin,
 * no listen time, not skipped (see HabitSlotsPlanner and TransGraph), starting at `listened_at`,
 * and lasting the library track's duration, which ListenBrainz's own rule (half the track or four
 * minutes) makes the honest estimate. With a known duration it counts as completed.
 *
 * Duplicates are dropped against the log as it is when the merge runs, under the history's lock:
 * the same track at the same start (so importing the same file twice adds nothing), and a listen
 * within [IN_APP_WINDOW_MS] of a play LatentJam recorded itself (scrobbled from this device). The
 * history keeps at most [MAX_HISTORY_EVENTS] listens, the bound local backup uses; when the import
 * would pass it, the newest listens are kept.
 */
internal class ListenBrainzImporter(
    private val history: ListeningHistory,
    private val library: MusicLibrary,
    private val now: () -> Long = ::epochMillis,
    /** The most listens the history may hold after the import. */
    private val maxHistoryEvents: Int = MAX_HISTORY_EVENTS,
) {
    /**
     * Reads [source] (closed here), matches and merges. Parsing runs on the default dispatcher;
     * [onProgress] is called from there with the rows read so far.
     */
    suspend fun import(
        source: () -> ExportByteSource,
        onProgress: (rowsRead: Long) -> Unit = {},
    ): ListenBrainzImportReport = importMutex.withLock {
        withContext(Dispatchers.Default) { importLocked(source, onProgress) }
    }

    private suspend fun importLocked(
        source: () -> ExportByteSource,
        onProgress: (Long) -> Unit,
    ): ListenBrainzImportReport {
        val matcher = ListenBrainzMatcher(library.allKnownTracks())
        if (matcher.tracks.isEmpty()) throw ListenBrainzLibraryEmptyException()
        val job = coroutineContext[Job]
        val latestSecond = (now() + FUTURE_TOLERANCE_MS) / 1000
        val matched = PackedListens(maxHistoryEvents)
        var notInLibrary = 0L
        var unreadableRows = 0L
        val opened = source()
        val summary = try {
            ListenBrainzExportReader.read(opened) { row ->
                if (row.listenedAtSeconds > latestSecond || row.listenedAtSeconds >= MAX_SECONDS) {
                    unreadableRows++
                } else {
                    val index = matcher.match(row)
                    if (index >= 0) matched.add(row.listenedAtSeconds, index) else notInLibrary++
                }
                val read = matched.added + notInLibrary + unreadableRows
                if (read % PROGRESS_EVERY == 0L) {
                    job?.ensureActive()
                    onProgress(read)
                }
            }
        } finally {
            opened.close()
        }
        onProgress(summary.rows)
        coroutineContext.ensureActive()
        val candidates = matched.finish()
        var imported = 0
        var songs = 0
        var alreadyPresent = matched.duplicates.toInt()
        var overLimit = matched.trimmed.toInt()
        if (candidates.isNotEmpty()) {
            history.merge(imported = emptyList(), maxEvents = maxHistoryEvents) { existing, _ ->
                val fold = fold(existing, candidates, matcher.tracks)
                imported = fold.added.size
                songs = fold.songs
                alreadyPresent = matched.duplicates.toInt() + fold.alreadyPresent
                overLimit = matched.trimmed.toInt() + fold.overLimit
                // A stable sort: a listen recorded here keeps its place before an import at the same millisecond.
                if (fold.added.isEmpty()) existing else (existing + fold.added).sortedBy(ListenEvent::startedAtMs)
            }
        }
        return ListenBrainzImportReport(
            listensRead = summary.rows,
            imported = imported,
            songs = songs,
            notInLibrary = notInLibrary,
            alreadyPresent = alreadyPresent,
            overLimit = overLimit,
            unreadable = summary.unreadable + unreadableRows,
            truncated = summary.truncated,
        )
    }

    private class Fold(val added: List<ListenEvent>, val songs: Int, val alreadyPresent: Int, val overLimit: Int)

    /** Pure and quick: it runs under the history's lock. */
    private fun fold(existing: List<ListenEvent>, candidates: LongArray, tracks: List<TrackDescriptor>): Fold {
        val indexById = HashMap<String, Int>(tracks.size * 2)
        tracks.forEachIndexed { index, track -> indexById[track.id.value] = index }
        // Per track: every start (exact duplicates) and the starts of plays recorded in the app.
        val allStarts = HashMap<Int, PackedLongs>()
        val inAppStarts = HashMap<Int, PackedLongs>()
        for (event in existing) {
            val index = indexById[event.trackId.value] ?: continue
            allStarts.getOrPut(index) { PackedLongs() }.add(event.startedAtMs)
            val importShaped = event.origin == null && event.listenedMs == null && !event.skipped
            if (!importShaped) inAppStarts.getOrPut(index) { PackedLongs() }.add(event.startedAtMs)
        }
        val sortedAll = allStarts.mapValues { it.value.sorted() }
        val sortedInApp = inAppStarts.mapValues { it.value.sorted() }
        val fresh = ArrayList<Long>()
        var alreadyPresent = 0
        for (packed in candidates) {
            val index = unpackIndex(packed)
            val startMs = unpackSeconds(packed) * 1000
            val exact = sortedAll[index]?.anyWithin(startMs, 0L) == true
            val nearInApp = sortedInApp[index]?.let { starts -> starts.anyWithin(startMs, IN_APP_WINDOW_MS) } == true
            if (exact || nearInApp) alreadyPresent++ else fresh += packed
        }
        val room = (maxHistoryEvents - existing.size).coerceAtLeast(0)
        // Candidates are in start order, so the newest are at the end.
        val kept = if (fresh.size > room) fresh.subList(fresh.size - room, fresh.size) else fresh
        val songs = HashSet<Int>()
        val added = kept.map { packed ->
            val index = unpackIndex(packed)
            songs += index
            val track = tracks[index]
            val duration = track.durationMs?.takeIf { it > 0 }
            ListenEvent(
                trackId = track.id,
                startedAtMs = unpackSeconds(packed) * 1000,
                playedMs = duration ?: 0L,
                trackDurationMs = duration,
                completed = duration != null,
                skipped = false,
            )
        }
        return Fold(added, songs.size, alreadyPresent, fresh.size - kept.size)
    }

    /**
     * Matched listens packed as `seconds << 30 | track index` into growable chunks: eight bytes a
     * listen, sortable as plain longs (start order, then track), with no object per row.
     */
    private class PackedListens(private val keep: Int) {
        private val values = PackedLongs()
        var added = 0L
            private set

        /** Rows that repeated another row of the export (same track, same second). */
        var duplicates = 0L
            private set

        /** The oldest rows dropped while compacting, beyond what the history could keep. */
        var trimmed = 0L
            private set

        fun add(seconds: Long, index: Int) {
            added++
            values.add((seconds shl INDEX_BITS) or index.toLong())
            // A huge export repeats listens and outgrows the history; compact before memory grows
            // past what the history could keep anyway.
            if (values.size >= maxOf(2 * keep, MIN_COMPACT_AT)) {
                val distinct = finish()
                val kept = if (distinct.size <= keep) distinct else distinct.copyOfRange(distinct.size - keep, distinct.size)
                trimmed += distinct.size - kept.size
                values.replaceWith(kept)
            }
        }

        /** The rows in start order without repeats. */
        fun finish(): LongArray {
            val sorted = values.sorted()
            if (sorted.isEmpty()) return sorted
            var write = 1
            for (read in 1 until sorted.size) if (sorted[read] != sorted[write - 1]) sorted[write++] = sorted[read]
            duplicates += sorted.size - write
            values.replaceWith(LongArray(0))
            return sorted.copyOf(write)
        }
    }

    /** A growable list of longs without boxing. */
    private class PackedLongs {
        private var data = LongArray(16)
        var size = 0
            private set

        fun add(value: Long) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun sorted(): LongArray = data.copyOf(size).also { it.sort() }

        fun replaceWith(values: LongArray) {
            data = if (values.size >= 16) values.copyOf(values.size * 2) else values.copyOf(16)
            size = values.size
        }
    }

    companion object {
        /** The history's bound; local backup restores against the same one. */
        const val MAX_HISTORY_EVENTS: Int = 500_000

        /** A row this close to a play recorded in the app is that play, scrobbled. */
        const val IN_APP_WINDOW_MS: Long = 2 * 60_000L

        private const val INDEX_BITS = 30
        private const val INDEX_MASK = (1L shl INDEX_BITS) - 1
        private const val MAX_SECONDS = 1L shl 33
        private const val FUTURE_TOLERANCE_MS = 24 * 60 * 60_000L
        private const val PROGRESS_EVERY = 5_000L
        private const val MIN_COMPACT_AT = 1024

        private fun unpackIndex(packed: Long): Int = (packed and INDEX_MASK).toInt()
        private fun unpackSeconds(packed: Long): Long = packed ushr INDEX_BITS

        private fun LongArray.anyWithin(target: Long, window: Long): Boolean {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (this[middle] < target - window) low = middle + 1 else high = middle
            }
            return low < size && this[low] <= target + window
        }

        /** One import at a time: two would each fold against a log missing the other's rows. */
        private val importMutex = Mutex()
    }
}
