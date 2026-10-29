/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.isImported
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.text.ArtistSets
import io.github.nikitasud.latentjam.smart.text.KnownArtists
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
 * 4. Only then the credit as a set of artists (ArtistSets), first as submitted, then as mapped: a separator-free whole
 *    credit ("A, B", "A & B", "A x B" and "A feat. B" all read "a b"), and the credit split at its separators into the
 *    library's own artists (and the artist list's, when given). Each artist is looked up with the loose title; a track
 *    qualifies only when it credits the listen's whole credit or its lead artist, and the qualifying tracks sharing the
 *    most artists are the candidates.
 *
 * A listen is only credited to a track of its own version: the version markers of the title the
 * lookup used (live, instrumental, remix, acoustic, sped up …, see [versionMarks]) must equal the
 * candidate's; a mapped lookup wants the markers of the submitted and the mapped title together.
 * A remaster or a single edit is no other version.
 *
 * Several candidates (copies of one song, an album and a compilation, a live version) are narrowed
 * by the release name, then by duration: with a duration on the row the closest candidate within
 * [DURATION_TOLERANCE_MS] wins; without one the candidates must all last about as long (copies of
 * one recording), and the first in id order is taken. Anything else is ambiguous and matches none —
 * a listen credited to the wrong version teaches SMART a transition that never happened.
 */
internal class ListenBrainzMatcher(
    library: List<TrackDescriptor>,
    /** The artist list ArtistSets may split credits into, besides the library's own artists. */
    known: KnownArtists = KnownArtists.NONE,
) {
    /** Library tracks in id order, so ties resolve the same way on every run. */
    val tracks: List<TrackDescriptor> = library.distinctBy { it.id }.sortedBy { it.id.value }
    private val strict = HashMap<String, IntArray>()
    private val loose = HashMap<String, IntArray>()
    /** Artist key (ArtistSets) and loose title → the tracks crediting that artist. */
    private val credited = HashMap<String, IntArray>()
    private val albums = arrayOfNulls<String>(tracks.size)
    private val marks = Array(tracks.size) { versionMarks(tracks[it].title) }
    private val lookups = HashMap<String, Lookup.Found>()
    private val setLookups = HashMap<String, Lookup.Found>()

    /** The lookups that found nothing, as ArtistSets.keyHash of the step's tag and the raw names (both steps share it). */
    private val misses = HashSet<Long>()

    /** Remembered found lookups of both steps (tests: misses never fill the string caches). */
    internal val rememberedLookups: Int get() = lookups.size + setLookups.size

    /** The library's individual artists (each credit's artists, its feat. pieces, its whole credit) as ArtistSets keys. */
    private val atoms = HashSet<Long>()

    /** What a credit may split into: an artist of this library, or else one of [known]. */
    private val splitInto: KnownArtists = KnownArtists { key -> if (key in atoms) key else known.canonical(key) }

    /**
     * Per track, the lowest index of the tracks sharing one of its strict artist and title keys:
     * copies of one song (an album and a compilation) share a group. Duplicates are found per
     * group, so a listen already credited to one copy is not added again for another.
     */
    val groups: IntArray = IntArray(tracks.size) { it }

    init {
        for (track in tracks) {
            for (credit in listOfNotNull(track.artist) + track.artists) {
                atoms += ArtistSets.keyHash(ArtistSets.normalize(credit))
                atoms += ArtistSets.parse(credit).artists
            }
        }
        atoms -= 0L
        val creditedLists = HashMap<String, MutableList<Int>>()
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
            val creditKeys = LinkedHashSet<Long>()
            for (artist in artists) creditKeys += trackKeys(artist)
            for (artistKey in creditKeys) creditedLists.getOrPut(key(artistKey.toString(), looseTitle)) { mutableListOf() }.addDistinct(index)
        }
        creditedLists.forEach { (key, list) -> credited[key] = list.toIntArray() }
        strictLists.forEach { (key, list) ->
            strict[key] = list.toIntArray()
            val lowest = list.min()
            for (index in list) if (lowest < groups[index]) groups[index] = lowest
        }
        looseLists.forEach { (key, list) -> loose[key] = list.toIntArray() }
    }

    /** The index into [tracks] the row names, or [NONE] / [AMBIGUOUS]. */
    fun match(row: ListenBrainzRow): Int {
        val isMapped = row.mappedArtist != null || row.mappedTitle != null
        val mappedArtist = row.mappedArtist ?: row.artist
        val mappedTitle = row.mappedTitle ?: row.title
        val submittedMarks = versionMarks(row.title)
        // MusicBrainz leaves "live" or a remix credit out of recording names: a mapped lookup wants the marks of both titles.
        val mappedMarks = (submittedMarks.split(',') + versionMarks(mappedTitle).split(','))
            .filter(String::isNotEmpty).distinct().sorted().joinToString(",")
        sameVersion(candidates(row.artist, row.title), submittedMarks)?.let { return choose(it, row) }
        if (isMapped) sameVersion(candidates(mappedArtist, mappedTitle), mappedMarks)?.let { return choose(it, row) }
        setCandidates(row.artist, row.title).let { set -> sameVersion(set, submittedMarks)?.let { return chooseFromSet(set, it, row) } }
        if (isMapped) {
            setCandidates(mappedArtist, mappedTitle).let { set -> sameVersion(set, mappedMarks)?.let { return chooseFromSet(set, it, row) } }
        }
        return NONE
    }

    /**
     * A set match by the lead name of an unsplit credit ("Simon" of "Simon & Garfunkel", or "Karan Aujla" of "Karan
     * Aujla, Seshnolan" whose guest the library does not know) is the same song only when its length agrees closely too.
     */
    private fun chooseFromSet(set: Lookup, candidates: IntArray, row: ListenBrainzRow): Int {
        if (!(set as Lookup.Found).byLeadNameOnly) return choose(candidates, row)
        val listened = row.durationMs ?: return NONE
        val close = candidates.filter { index ->
            tracks[index].durationMs?.takeIf { it > 0 }?.let { kotlin.math.abs(it - listened) <= LEAD_NAME_TOLERANCE_MS } == true
        }
        return if (close.isEmpty()) NONE else choose(close.toIntArray(), row)
    }

    /** The found candidates with the [wanted] version marks, or null when none has them (a live take is not the studio track). */
    private fun sameVersion(lookup: Lookup, wanted: String): IntArray? {
        if (lookup !is Lookup.Found) return null
        val same = lookup.indices.filter { marks[it] == wanted }
        return if (same.isEmpty()) null else same.toIntArray()
    }

    /**
     * The credit as a set (step 4): the tracks crediting the most of the listen's artists under the loose title. Only a
     * track that credits the listen's whole credit or its lead artist qualifies ([leadKey]): a guest, a second artist
     * or a word of a band name ("Sebastian" of "Belle and Sebastian") alone does not make another artist's song this
     * one. Among those, the listen's artists ([creditKeys]) are counted.
     */
    private fun setCandidates(artist: String?, title: String?): Lookup {
        if (artist == null || title == null) return Lookup.Missing
        val raw = artist + '\u0000' + title
        setLookups[raw]?.let { return it }
        val miss = ArtistSets.keyHash("set\u0000" + raw)
        if (miss in misses) return Lookup.Missing
        val found = run {
            val looseTitle = looseForm(title, isTitle = true) ?: return@run Lookup.Missing
            val qualifying = HashSet<Int>()
            val lead = leadKey(artist)
            for (artistKey in listOf(wholeKey(artist), if (lead.split) lead.key else 0L)) {
                if (artistKey != 0L) credited[key(artistKey.toString(), looseTitle)]?.forEach { qualifying += it }
            }
            // An unsplit credit's first name alone qualifies only under a close length ([chooseFromSet]).
            val byLeadNameOnly = qualifying.isEmpty()
            if (byLeadNameOnly && lead.key != 0L) credited[key(lead.key.toString(), looseTitle)]?.forEach { qualifying += it }
            if (qualifying.isEmpty()) return@run Lookup.Missing
            val shared = LinkedHashMap<Int, Int>()
            for (artistKey in creditKeys(artist)) {
                credited[key(artistKey.toString(), looseTitle)]?.forEach { if (it in qualifying) shared[it] = (shared[it] ?: 0) + 1 }
            }
            val most = shared.values.maxOrNull() ?: return@run Lookup.Missing
            Lookup.Found(shared.filterValues { it == most }.keys.sorted().toIntArray(), byLeadNameOnly)
        }
        remember(setLookups, raw, miss, found)
        return found
    }

    /** Keeps a found lookup under its names, a miss only as its hash: an export of unknown songs must not fill memory. */
    private fun remember(cache: HashMap<String, Lookup.Found>, raw: String, miss: Long, found: Lookup) {
        when (found) {
            is Lookup.Found -> if (cache.size < MAX_REMEMBERED_LOOKUPS) cache[raw] = found
            Lookup.Missing -> if (misses.size < MAX_REMEMBERED_MISSES) misses += miss
        }
    }

    /**
     * A listen's artists as ArtistSets keys, for counting: its split, its separator-free whole credit, and the pieces
     * between its separators, so a listen crediting more artists than the track ("A, B" for a track by A) still shares A
     * with it. A title's feat. credit is not used: a shared guest alone does not make another artist's song this one.
     */
    private fun creditKeys(credit: String): Set<Long> {
        val keys = LinkedHashSet<Long>()
        keys += ArtistSets.parse(credit, known = splitInto).artists
        keys += ArtistSets.keyHash(ArtistSets.normalize(credit.replace(WORD_SEPARATOR, " ")))
        for (piece in credit.split(CREDIT_SEPARATOR)) keys += ArtistSets.keyHash(ArtistSets.normalize(piece))
        keys -= 0L
        return keys
    }

    /**
     * A track credit's artists as ArtistSets keys, what a listen must name: its split and its separator-free whole
     * credit. Not the raw pieces between separators ("Fire" of "Earth, Wind & Fire"), no placeholder and no one-letter
     * name ("X").
     */
    private fun trackKeys(credit: String): Set<Long> {
        val keys = LinkedHashSet<Long>()
        keys += ArtistSets.parse(credit, known = splitInto).artists
        keys += wholeKey(credit)
        keys -= unusableKeys(credit)
        keys -= 0L
        return keys
    }

    /** The separator-free whole credit ("A, B", "A & B", "A x B" and "A feat. B" all read "a b"); 0 when unusable. */
    private fun wholeKey(credit: String): Long = usableKey(credit.replace(WORD_SEPARATOR, " "))

    /**
     * The listen's lead artist: the first of its ArtistSets split when the credit split, else its first piece before a
     * separator ArtistSets splits at, or a spaced feat. ("and" and "/" are no separators: "Belle and Sebastian", "AC/DC").
     * 0 when that is a placeholder or a single letter: then only the whole credit may qualify a track.
     */
    private fun leadKey(credit: String): Lead {
        val set = ArtistSets.parse(credit, known = splitInto)
        if ("cond_split" in set.rules || "feat_split" in set.rules) {
            val lead = set.artists.firstOrNull() ?: return Lead(0L, split = true)
            return Lead(if (lead in unusableKeys(credit)) 0L else lead, split = true)
        }
        return Lead(usableKey(credit.split(LEAD_SEPARATOR).first()), split = false)
    }

    /** A credit's lead artist key; [split] when ArtistSets split the credit, not only its first separator. */
    private class Lead(val key: Long, val split: Boolean)

    /** [raw]'s ArtistSets key, or 0 for a placeholder or a name under two characters. */
    private fun usableKey(raw: String): Long {
        val normalized = ArtistSets.normalize(raw)
        if (normalized.length < 2 || ArtistSets.isPlaceholder(raw)) return 0L
        return ArtistSets.keyHash(normalized)
    }

    /** The keys a credit's placeholder or one-letter pieces could take (as written, or through the artist list). */
    private fun unusableKeys(credit: String): Set<Long> {
        val unusable = HashSet<Long>()
        for (piece in credit.split(LEAD_SEPARATOR)) {
            if (usableKey(piece) != 0L) continue
            unusable += ArtistSets.keyHash(ArtistSets.normalize(piece))
            splitInto.canonicalOf(piece)?.let { unusable += it }
        }
        unusable -= 0L
        return unusable
    }

    private sealed interface Lookup {
        data object Missing : Lookup
        class Found(val indices: IntArray, val byLeadNameOnly: Boolean = false) : Lookup
    }

    /**
     * The candidates for one artist and title. An export repeats the same few thousand songs many
     * times, so the normalised lookups are remembered (bounded by [MAX_REMEMBERED_LOOKUPS], misses by
     * [MAX_REMEMBERED_MISSES]).
     */
    private fun candidates(artist: String?, title: String?): Lookup {
        if (artist == null || title == null) return Lookup.Missing
        val raw = artist + '\u0000' + title
        lookups[raw]?.let { return it }
        val miss = ArtistSets.keyHash("text\u0000" + raw)
        if (miss in misses) return Lookup.Missing
        val found = lookup(artist, title)
        remember(lookups, raw, miss, found)
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
        if (candidates.size == 1) return plausible(candidates[0], row)
        var pool = candidates.toList()
        strictForm(row.release)?.let { release ->
            val sameRelease = pool.filter { albums[it] == release }
            if (sameRelease.isNotEmpty()) pool = sameRelease
        }
        if (pool.size == 1) return plausible(pool[0], row)
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

    /**
     * A lone candidate is taken unless both lengths are known and far apart: an extended mix or a
     * live take whose suffix the loose form dropped is not the studio track the library holds. The
     * bound is generous, unlike [DURATION_TOLERANCE_MS], because submitted durations are often
     * rounded or come from another edition.
     */
    private fun plausible(index: Int, row: ListenBrainzRow): Int {
        val listened = row.durationMs ?: return index
        val own = tracks[index].durationMs?.takeIf { it > 0 } ?: return index
        val apart = kotlin.math.abs(own - listened)
        return if (apart > LONE_CANDIDATE_TOLERANCE_MS && apart * 4 > own) AMBIGUOUS else index
    }

    companion object {
        const val NONE: Int = -1
        const val AMBIGUOUS: Int = -2

        /** How far apart two durations may be and still describe one recording. */
        const val DURATION_TOLERANCE_MS: Long = 10_000L

        /** See [plausible]: a lone candidate is refused only more than 30 s and a quarter apart. */
        const val LONE_CANDIDATE_TOLERANCE_MS: Long = 30_000L

        /** See [chooseFromSet]: a match by an unsplit credit's first name wants the lengths this close. */
        const val LEAD_NAME_TOLERANCE_MS: Long = 3_000L

        private const val MAX_REMEMBERED_LOOKUPS = 200_000
        private const val MAX_REMEMBERED_MISSES = 100_000

        private val WHITESPACE = Regex("\\s+")
        private val FEATURING = Regex("\\s*[(\\[]?\\s*\\b(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
        private val BRACKETED_SUFFIX = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")
        private val DASH_SUFFIX = Regex(
            "\\s+[-–—]\\s+(from\\s[^\"]*\".*|from\\s+(the\\s+)?(original\\s+)?(motion\\s+picture|film|movie|series|musical|soundtrack)\\b.*|[^-–—]*\\b(remaster(ed)?|live|version|edit|mix|remix|mono|stereo|deluxe|bonus|single|acoustic|demo|instrumental)\\b[^-–—]*)$",
            RegexOption.IGNORE_CASE,
        )
        private val PUNCTUATION = Regex("[^\\p{L}\\p{N} ]+")

        // ArtistSets' whitespace (Python's \s) spelled out, so the JVM, Android and iOS split a credit alike.
        private const val WS = "[\\t\\n\\u000B\\f\\r\\u001C-\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

        /** Word separators of a credit ("A x B", "A with B", "A feat. B"); ArtistSets.normalize drops the punctuation ones. */
        private val WORD_SEPARATOR = Regex("(?i)$WS+(x|×|with|vs\\.?|and|feat\\.?|ft\\.?|featuring)$WS+")

        /** ArtistSets' conditional separators and a spaced feat.: where a credit's lead artist ends ([leadKey]). */
        private val LEAD_SEPARATOR = Regex(
            "(?i)$WS*[,;，；、／＆&+×]$WS*|$WS+(?:with|vs\\.?|x)$WS+|$WS+[(\\[（［]?$WS*(?:feat\\.?|ft\\.?|featuring)$WS+",
        )

        /** Every separator between a credit's artists, punctuation ones too. */
        private val CREDIT_SEPARATOR = Regex("(?i)$WS*[,;，；、／/＆&+×]$WS*|$WS+(x|with|vs\\.?|and|feat\\.?|ft\\.?|featuring)$WS+")

        /** A title's version parts: what is in brackets, and what follows a spaced dash. */
        private val VERSION_PART = Regex("[(\\[]([^()\\[\\]]*)[)\\]]|\\s[-–—]\\s(.*)$")

        /** Version markers (canonical word → its spellings). A remaster, a mono mix or a single edit is no other version. */
        private val VERSION_WORDS: List<Pair<String, List<String>>> = listOf(
            "live" to listOf("live"),
            "instrumental" to listOf("instrumental", "inst"),
            "remix" to listOf("remix", "remixed", "rmx"),
            "acoustic" to listOf("acoustic"),
            "demo" to listOf("demo"),
            "karaoke" to listOf("karaoke"),
            "cover" to listOf("cover"),
            "unplugged" to listOf("unplugged"),
            "sped up" to listOf("sped up", "spedup", "speed up"),
            "slowed" to listOf("slowed"),
            "reverb" to listOf("reverb"),
            "nightcore" to listOf("nightcore"),
            "8d" to listOf("8d"),
            "extended" to listOf("extended"),
            "rework" to listOf("rework"),
            "bootleg" to listOf("bootleg"),
            "mashup" to listOf("mashup", "mash up"),
            "orchestral" to listOf("orchestral"),
            "piano" to listOf("piano version"),
        )
        private val VERSION_PATTERNS: List<Pair<String, Regex>> = VERSION_WORDS.flatMap { (word, spellings) ->
            spellings.map { word to Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])") }
        }
        /** Markers that are versions wherever they stand ("Me Gustas Tu Sped Up"). */
        private val ANYWHERE = setOf("sped up", "slowed", "nightcore", "8d")

        /** A title's version markers, sorted and joined: "" for an unmarked (studio, or remastered) title. */
        internal fun versionMarks(title: String?): String {
            val text = strictForm(title) ?: return ""
            val parts = VERSION_PART.findAll(text).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toList()
            val found = LinkedHashSet<String>()
            for ((word, pattern) in VERSION_PATTERNS) {
                if (parts.any { pattern.containsMatchIn(it) } || (word in ANYWHERE && pattern.containsMatchIn(text))) found += word
            }
            return found.sorted().joinToString(",")
        }

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
 * Every matched listen is written in the shape the rest of the app reads as an import (ListenEvent.isImported): no
 * origin, no listen time, not skipped (see HabitSlotsPlanner, TransGraph and SMART's personal evidence), starting at `listened_at`,
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
                val fold = fold(existing, candidates, matcher.tracks, matcher.groups)
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
    private fun fold(
        existing: List<ListenEvent>,
        candidates: LongArray,
        tracks: List<TrackDescriptor>,
        groups: IntArray,
    ): Fold {
        val indexById = HashMap<String, Int>(tracks.size * 2)
        tracks.forEachIndexed { index, track -> indexById[track.id.value] = index }
        // Per song (its copies grouped): every start (exact duplicates) and the starts of plays
        // recorded in the app.
        val allStarts = HashMap<Int, PackedLongs>()
        val inAppStarts = HashMap<Int, PackedLongs>()
        val importedStarts = HashMap<Int, PackedLongs>()
        for (event in existing) {
            val index = indexById[event.trackId.value]?.let { groups[it] } ?: continue
            allStarts.getOrPut(index) { PackedLongs() }.add(event.startedAtMs)
            if (!event.isImported) inAppStarts.getOrPut(index) { PackedLongs() }.add(event.startedAtMs)
            else importedStarts.getOrPut(index) { PackedLongs() }.add(event.startedAtMs)
        }
        val sortedAll = allStarts.mapValues { it.value.sorted() }
        val sortedInApp = inAppStarts.mapValues { it.value.sorted() }
        val sortedImported = importedStarts.mapValues { it.value.sorted() }
        val fresh = ArrayList<Long>()
        var alreadyPresent = 0
        // The last kept start per song: two scrobblers submit one listen twice a few seconds apart (L1).
        val lastKept = HashMap<Int, Long>()
        for (packed in candidates) {
            val track = unpackIndex(packed)
            val index = groups[track]
            val startMs = unpackSeconds(packed) * 1000
            val exact = sortedAll[index]?.anyWithin(startMs, 0L) == true
            val nearInApp = sortedInApp[index]?.let { starts -> starts.anyWithin(startMs, IN_APP_WINDOW_MS) } == true
            val window = doubleScrobbleWindow(tracks[track])
            val doubled = lastKept[index]?.let { startMs - it <= window } == true ||
                sortedImported[index]?.anyWithin(startMs, window) == true
            if (exact || nearInApp || doubled) {
                alreadyPresent++
            } else {
                fresh += packed
                lastKept[index] = startMs
            }
        }
        // A little room is left for the plays recorded after the import: a log filled to the bound
        // would pass it with the next listen, and backup export refuses a log past it.
        val room = (maxHistoryEvents - maxHistoryEvents / PLAY_HEADROOM_DIVISOR - existing.size).coerceAtLeast(0)
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
     * How close two imported listens of [track] may start and still be one listen submitted twice: [DOUBLE_SCROBBLE_MS],
     * and less than the track lasts, so a back-to-back repeat (a whole play apart) stays two listens.
     */
    private fun doubleScrobbleWindow(track: TrackDescriptor): Long {
        val duration = track.durationMs?.takeIf { it > 0 } ?: return DOUBLE_SCROBBLE_MS
        return minOf(DOUBLE_SCROBBLE_MS, duration - 1)
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

        /** The share of the bound kept free for later plays: 1/50, so 10,000 listens at 500,000. */
        private const val PLAY_HEADROOM_DIVISOR = 50

        /** A row this close to a play recorded in the app is that play, scrobbled. */
        const val IN_APP_WINDOW_MS: Long = 2 * 60_000L

        /** See [doubleScrobbleWindow]. */
        const val DOUBLE_SCROBBLE_MS: Long = 120_000L

        private const val INDEX_BITS = 30
        private const val INDEX_MASK = (1L shl INDEX_BITS) - 1
        private const val MAX_SECONDS = 1L shl 33
        /** A row this far ahead of now is a clock error, not a listen (the LB-import check, L4). */
        private const val FUTURE_TOLERANCE_MS = 10 * 60_000L
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
