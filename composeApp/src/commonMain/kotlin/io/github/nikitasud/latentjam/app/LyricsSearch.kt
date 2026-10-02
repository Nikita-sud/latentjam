/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** A rebuildable, private on-device cache. Implementations use atomic writes off the UI thread. */
internal interface LyricsSearchStorage {
    suspend fun read(): String?
    suspend fun write(payload: String)
}

@Composable
internal expect fun rememberLyricsSearchStorage(): LyricsSearchStorage

/** Lives for the process; reopening Search neither reopens files nor renormalizes known lyrics. */
internal class LyricsSearchCache(private val clock: TimeSource = TimeSource.Monotonic) {
    /** [sidecar] is the fingerprint the song's `.lrc` files had when [document] was read. */
    private data class Entry(val revision: String, val sidecar: String, val document: LyricSearchDocument?)
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null
    private var dirty = false
    private var lastSidecarPass: TimeMark? = null

    /**
     * Cancellation stops between files, retaining completed work in memory. Missing lyrics are
     * cached; failed reads are retried next time. A revision/URI change invalidates either result,
     * and so does a change of [sourcesRevision] (see [rememberLyricsSourcesRevision]): lyrics can
     * come from outside the song's file, and granting a folder must re-read the songs cached as
     * having none. Batches publish useful results while a large library is being scanned for the
     * first time.
     *
     * A `.lrc` added, edited or deleted beside a song changes none of that, so each entry also
     * keeps the song's [sidecarFingerprints] value from when it was read, and a song whose
     * fingerprint differs now is read again — that song alone. The fingerprints are metadata
     * lookups (see [rememberSidecarFingerprints]), never a read of the audio, but over a whole
     * library they are still work, so the batch runs after the cached results are published and
     * [reading] has said false, and at most once per [SIDECAR_RECHECK] in this process; between
     * those passes only the songs about to be read are fingerprinted. When the fingerprints
     * cannot be taken, or one song's is missing from the answer, the entry stays as it is: a
     * failed lookup is not a changed file.
     *
     * [reading] is true only while songs are actually being read, so Search does not show a
     * lyrics search as pending while the index merely checks its sidecars.
     */
    suspend fun load(
        songs: List<TrackDescriptor>,
        storage: LyricsSearchStorage,
        readLyrics: suspend (TrackDescriptor) -> Lyrics?,
        sourcesRevision: String,
        sidecarFingerprints: suspend (List<TrackDescriptor>) -> Map<TrackId, String> = { emptyMap() },
        reading: (Boolean) -> Unit = {},
        publish: (Map<TrackId, LyricSearchDocument>) -> Unit,
    ) = mutex.withLock {
        val cache = entries ?: try {
            decode(storage.read()).also { entries = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableMapOf<String, Entry>().also { entries = it }
        }
        val ids = songs.mapTo(HashSet()) { it.id.value }
        if (cache.keys.retainAll(ids)) dirty = true
        // Don't publish a stale lyric match while the replacement file is being read.
        songs.forEach { track ->
            if (cache[track.id.value]?.revision != track.lyricsRevision(sourcesRevision)) {
                if (cache.remove(track.id.value) != null) dirty = true
            }
        }
        fun snapshot(): Map<TrackId, LyricSearchDocument> = songs.mapNotNull { track ->
            cache[track.id.value]?.document?.let { track.id to it }
        }.toMap()
        publish(snapshot())
        // Songs already known to need a read keep "Loading" on through the sidecar check before it,
        // rather than flashing "No matches" for its length.
        reading(songs.any { it.id.value !in cache })
        suspend fun fingerprints(of: List<TrackDescriptor>): Map<TrackId, String>? = try {
            sidecarFingerprints(of)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val due = lastSidecarPass?.let { it.elapsedNow() >= SIDECAR_RECHECK } ?: true
        var sidecars = if (due) fingerprints(songs) else null
        if (sidecars != null) {
            lastSidecarPass = clock.markNow()
            var shown = false
            songs.forEach { track ->
                val entry = cache[track.id.value] ?: return@forEach
                val now = sidecars[track.id] ?: return@forEach
                if (entry.sidecar != now) {
                    cache.remove(track.id.value)
                    dirty = true
                    if (entry.document != null) shown = true
                }
            }
            if (shown) publish(snapshot())
        }
        val pending = songs.filter { it.id.value !in cache }
        if (pending.isEmpty()) {
            save(storage, cache)
            return@withLock
        }
        if (!due) sidecars = fingerprints(pending)
        reading(true)
        var scanned = 0
        for (track in pending) {
            currentCoroutineContext().ensureActive()
            try {
                val lyrics = readLyrics(track)
                cache[track.id.value] = Entry(
                    track.lyricsRevision(sourcesRevision),
                    // Without a fingerprint, the entry records no sidecar; a later pass that sees
                    // one reads the song once more.
                    sidecars?.get(track.id).orEmpty(),
                    lyrics?.let { LyricSearchDocument.build(it.text) },
                )
                dirty = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unreadable file must not permanently become a cached "no lyrics".
            }
            scanned++
            if (scanned % 64 == 0) {
                publish(snapshot())
                if (scanned % 256 == 0) save(storage, cache)
            }
            yield()
        }
        publish(snapshot())
        reading(false)
        save(storage, cache)
    }

    private suspend fun save(storage: LyricsSearchStorage, cache: Map<String, Entry>) {
        if (!dirty) return
        try {
            storage.write(encode(cache))
            dirty = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Results remain usable; a later pass retries the derived-cache write.
        }
    }

    private fun encode(cache: Map<String, Entry>): String = buildString {
        append("lyrics-v2\n")
        cache.forEach { (id, entry) ->
            append(Base64.encode(id.encodeToByteArray())).append('\t')
            append(Base64.encode(entry.revision.encodeToByteArray())).append('\t')
            append(Base64.encode(entry.document?.text.orEmpty().encodeToByteArray())).append('\t')
            append(Base64.encode(entry.sidecar.encodeToByteArray())).append('\n')
        }
    }

    private fun decode(payload: String?): MutableMap<String, Entry> {
        val result = mutableMapOf<String, Entry>()
        // v1 predates sidecar fingerprints: its entries read as having seen no `.lrc`.
        val columns = when {
            payload == null -> return result
            payload.startsWith("lyrics-v2\n") -> 4
            payload.startsWith("lyrics-v1\n") -> 3
            else -> return result
        }
        payload.lineSequence().drop(1).forEach { line ->
            val fields = line.split('\t')
            if (fields.size != columns) return@forEach
            try {
                val id = Base64.decode(fields[0]).decodeToString()
                val revision = Base64.decode(fields[1]).decodeToString()
                val text = Base64.decode(fields[2]).decodeToString()
                val sidecar = if (columns == 4) Base64.decode(fields[3]).decodeToString() else ""
                result[id] = Entry(revision, sidecar, LyricSearchDocument.build(text))
            } catch (_: IllegalArgumentException) {
                // An individual corrupt entry is rebuilt without discarding the whole library.
            }
        }
        return result
    }
}

/**
 * How long a sidecar check of the whole library stays fresh in this process. Opening Search again
 * sooner reuses it: a `.lrc` copied in is found within minutes, and flipping between tabs does not
 * cost a pass over every song each time.
 */
private val SIDECAR_RECHECK = 5.minutes

/**
 * Bumped when reading a file's own lyrics changes what it finds, so every entry cached by the
 * older reader is read again once. "read2": M4A lyrics, and UTF-8 text in Latin-1 ID3 frames
 * decoded as such — 0.6.0 cached those songs as having no lyrics, or with garbled ones.
 */
private const val LYRICS_READER_VERSION = "read2"

/**
 * The file's identity, [LYRICS_READER_VERSION], and [sourcesRevision] when anything beyond the
 * file can supply lyrics.
 */
private fun TrackDescriptor.lyricsRevision(sourcesRevision: String): String =
    "${audioUri.orEmpty()}\u0000${sourceRevision ?: "${sizeBytes.orEmptyRevision()}:${durationMs.orEmptyRevision()}"}" +
        "\u0000$LYRICS_READER_VERSION" +
        (if (sourcesRevision.isEmpty()) "" else "\u0000$sourcesRevision")

private fun Long?.orEmptyRevision(): String = this?.toString().orEmpty()

/** Pre-normalized phrase search. Never runs fuzzy edit distance over thousands of lyric words. */
internal class LyricSearchDocument private constructor(
    val text: String,
    private val normalized: String,
    private val words: List<LyricWord>,
) {
    private data class LyricWord(val normalizedStart: Int, val sourceStart: Int, val sourceEnd: Int)

    /** Match whole words, permitting the final word to be unfinished while the listener types. */
    fun snippet(query: String): String? = snippetNormalized(normalizeLyricQuery(query))

    internal fun snippetNormalized(needle: String): String? {
        val unspacedScript = needle.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' || it in '\uac00'..'\ud7af' }
        if (needle.length < if (unspacedScript) 2 else 3) return null
        var from = 0
        while (from < normalized.length) {
            val at = normalized.indexOf(needle, from)
            if (at < 0) return null
            if (unspacedScript || at == 0 || normalized[at - 1] == ' ') {
                val found = words.binarySearch { it.normalizedStart.compareTo(at) }
                val first = if (found >= 0) found else -found - 2
                if (first >= 0) {
                    val end = at + needle.length
                    var last = first
                    while (last + 1 < words.size && words[last + 1].normalizedStart < end) last++
                    val startChar = words[first].sourceStart +
                        (if (unspacedScript) at - words[first].normalizedStart else 0)
                            .coerceAtMost(words[first].sourceEnd - words[first].sourceStart)
                    val endChar = if (unspacedScript) {
                        (startChar + needle.length).coerceAtMost(text.length)
                    } else {
                        words[last].sourceEnd
                    }
                    val excerptStart = (startChar - 36).coerceAtLeast(0)
                    val excerptEnd = maxOf(endChar, startChar + 124).coerceAtMost(text.length)
                    return (if (excerptStart > 0) "…" else "") +
                        text.substring(excerptStart, excerptEnd).trim() +
                        (if (excerptEnd < text.length) "…" else "")
                }
            }
            from = at + 1
        }
        return null
    }

    companion object {
        // Malformed embedded tags must not retain megabytes per track in the search index.
        private const val MAX_TEXT_CHARS = 64 * 1024

        fun build(raw: String): LyricSearchDocument? {
            val text = raw.take(MAX_TEXT_CHARS).replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return null
            val words = ArrayList<LyricWord>()
            val normalized = buildString {
                lyricWordRanges(text).forEach { range ->
                    val word = normalizeSearchText(text.substring(range))
                    if (word.isNotEmpty()) {
                        if (isNotEmpty()) append(' ')
                        words += LyricWord(length, range.first, range.last + 1)
                        append(word)
                    }
                }
            }
            return normalized.takeIf { it.isNotEmpty() }?.let { LyricSearchDocument(text, it, words) }
        }
    }
}

private fun lyricWordRanges(text: String): Sequence<IntRange> = sequence {
    var start = -1
    text.forEachIndexed { index, ch ->
        // Keep apostrophes inside contractions; punctuation and line breaks separate words.
        val partOfWord = ch.isLetterOrDigit() || ch in "'’‘ʼ" ||
            ch.category == CharCategory.NON_SPACING_MARK
        if (partOfWord && start < 0) start = index
        if (!partOfWord && start >= 0) {
            yield(start until index)
            start = -1
        }
    }
    if (start >= 0) yield(start until text.length)
}

private fun normalizeLyricQuery(query: String): String =
    lyricWordRanges(query).map { normalizeSearchText(query.substring(it)) }
        .filter { it.isNotEmpty() }.joinToString(" ")

internal fun searchLyrics(
    documents: Map<TrackId, LyricSearchDocument>,
    query: String,
    checkCancelled: () -> Unit = {},
): Map<TrackId, String> {
    val needle = normalizeLyricQuery(query)
    return buildMap {
        documents.forEach { (id, document) ->
            checkCancelled()
            document.snippetNormalized(needle)?.let { put(id, it) }
        }
    }
}
