/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Two files closer than this in audio space are treated as the same recording. The audio
 * embeddings are L2-normalized, so different encodes of one recording land at ~0.995+, while
 * covers, remixes and live versions stay clearly below.
 */
internal const val DUPLICATE_SIMILARITY = 0.99f

/**
 * Groups tracks whose stored audio embeddings are near-identical — the tag-blind duplicate
 * finder. Every pair in a group meets [threshold], so whichever row the listener keeps is a
 * near-duplicate of every row that will be hidden. Biggest group first; singletons are dropped.
 *
 * Pair checks short-circuit as soon as their squared distance exceeds the normalized-vector
 * threshold. This preserves exact cosine semantics while avoiding a full high-dimensional dot
 * product for the overwhelmingly common non-duplicate pair.
 *
 * [dismissed] pairs are never duplicates, whatever the vectors say: a group that would have
 * contained both splits so that neither side can hide the other. [onProgress] reports rows
 * processed against the total, from whichever thread the scan runs on.
 */
internal fun audioDuplicateGroups(
    vectors: Map<TrackId, FloatArray>,
    threshold: Float = DUPLICATE_SIMILARITY,
    durationsMs: Map<TrackId, Long?> = emptyMap(),
    dismissed: DuplicateExclusions = DuplicateExclusions(emptyList()),
    cancellationCheck: () -> Unit = {},
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): List<List<TrackId>> {
    require(threshold.isFinite()) { "Duplicate threshold must be finite" }
    cancellationCheck()
    val ids = vectors.keys.sortedBy(TrackId::value)
    // Rows are scaled to unit length ONCE, per row rather than per pair: the pair loop below
    // runs many times per row and stays a plain float subtraction. The index deliberately
    // permits zero vectors; they have no audio direction and must never become a destructive
    // recommendation just because their Euclidean distance is also zero, so they read as null.
    val rows = ids.mapIndexed { index, id ->
        if (index and 127 == 0) cancellationCheck()
        unitRowOrNull(vectors.getValue(id))
    }
    val maximumSquaredDistance = 2f * (1f - threshold.coerceIn(-1f, 1f))
    val durations = ids.map { id -> durationsMs[id]?.takeIf { it > 0 } }

    fun areDuplicates(aIndex: Int, bIndex: Int): Boolean {
        if (dismissed.excludes(ids[aIndex], ids[bIndex])) return false
        val a = rows[aIndex] ?: return false
        val b = rows[bIndex] ?: return false
        if (a.size != b.size) return false
        // For unit vectors, ||a-b||² = 2 - 2*cos(a,b). Accumulated squared distance is
        // monotonic, so most unrelated tracks can be rejected after only a few dimensions.
        var squaredDistance = 0f
        for (dimension in a.indices) {
            val difference = a[dimension] - b[dimension]
            squaredDistance += difference * difference
            if (squaredDistance > maximumSquaredDistance) return false
        }
        return true
    }

    // A connected component is unsafe here: A≈B and B≈C does not imply A≈C. Build deterministic
    // complete-link groups, but only decode vector pairs whose known durations can represent the
    // same recording. Duration buckets turn a normal large library from all-pairs work into local
    // windows; unknown durations remain conservative and compare against every earlier row.
    val durationToleranceMs = 2_000L
    val durationBucketWidthMs = durationToleranceMs + 1L
    val knownDurationBuckets = mutableMapOf<Long, MutableList<Int>>()
    val unknownDurationIndices = mutableListOf<Int>()
    val completeLinkGroups = mutableListOf<MutableList<Int>>()
    val groupOf = IntArray(ids.size) { -1 }
    var pairChecks = 0
    for (candidate in ids.indices) {
        if (candidate and 127 == 0) {
            cancellationCheck()
            onProgress(candidate, ids.size)
        }
        val duration = durations[candidate]
        val possibleMatches = if (duration == null) {
            (0 until candidate).toMutableList()
        } else {
            buildList {
                addAll(unknownDurationIndices)
                val bucket = duration / durationBucketWidthMs
                for (candidateBucket in (bucket - 1)..(bucket + 1)) {
                    knownDurationBuckets[candidateBucket].orEmpty().forEach { prior ->
                        val priorDuration = durations[prior] ?: return@forEach
                        val difference = if (duration >= priorDuration) {
                            duration - priorDuration
                        } else {
                            priorDuration - duration
                        }
                        if (difference <= durationToleranceMs) add(prior)
                    }
                }
            }.sorted()
        }
        val duplicatePrior = HashSet<Int>()
        for (prior in possibleMatches) {
            if (pairChecks++ and 1023 == 0) cancellationCheck()
            if (areDuplicates(candidate, prior)) duplicatePrior += prior
        }
        val compatibleGroupIndex = duplicatePrior.asSequence()
            .map { groupOf[it] }
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .firstOrNull { groupIndex ->
                completeLinkGroups[groupIndex].all { member -> member in duplicatePrior }
            }
        val groupIndex = if (compatibleGroupIndex == null) {
            completeLinkGroups += mutableListOf(candidate)
            completeLinkGroups.lastIndex
        } else {
            completeLinkGroups[compatibleGroupIndex] += candidate
            compatibleGroupIndex
        }
        groupOf[candidate] = groupIndex
        if (duration == null) {
            unknownDurationIndices += candidate
        } else {
            knownDurationBuckets.getOrPut(duration / durationBucketWidthMs, ::mutableListOf) +=
                candidate
        }
    }
    onProgress(ids.size, ids.size)

    return completeLinkGroups
        .filter { it.size > 1 }
        .map { group -> group.map { ids[it] } }
        .sortedWith(compareByDescending<List<TrackId>> { it.size }.thenBy { it.first().value })
}

/**
 * The row at unit length, or null for an empty, zero or non-finite row. A row the index already
 * stores at unit length is returned as is: normalisation then costs no copy at all.
 */
private fun unitRowOrNull(row: FloatArray): FloatArray? {
    if (row.isEmpty()) return null
    var squaredNorm = 0.0
    for (component in row) squaredNorm += component.toDouble() * component
    if (squaredNorm <= 0.0 || !squaredNorm.isFinite()) return null
    val scale = 1.0 / sqrt(squaredNorm)
    if (abs(scale - 1.0) < UNIT_ROW_TOLERANCE) return row
    return FloatArray(row.size) { (row[it] * scale).toFloat() }
}

private const val UNIT_ROW_TOLERANCE = 1e-4

/** One copy of a recording, with the facts that decide which copy deserves to stay. */
internal data class DuplicateCopy(
    val track: TrackDescriptor,
    /** Upper-case container/codec label from the file name ("FLAC", "MP3"); null when unknown. */
    val format: String?,
    val lossless: Boolean,
    /** Whole-file bitrate estimated from size and duration; the honest quality signal for lossy files. */
    val bitrateKbps: Int?,
    val plays: Int,
    val favorite: Boolean,
    val playlists: Int,
)

/** A duplicate group with the copy the finder would keep, and what keeping it frees. */
internal data class DuplicateGroup(
    val copies: List<DuplicateCopy>,
    val recommended: DuplicateCopy,
) {
    // Length-prefix opaque ids: imported file names may themselves contain newlines.
    val key: String = copies.map { it.track.id.value }.sorted().joinToString("") {
        "${it.length}:$it"
    }

    val reclaimableBytes: Long get() = reclaimableBytes(recommended.track.id)

    fun reclaimableBytes(survivor: TrackId): Long =
        copies.filter { it.track.id != survivor }.sumOf { it.track.sizeBytes ?: 0L }

    fun copy(id: TrackId): DuplicateCopy? = copies.firstOrNull { it.track.id == id }
}

private val LOSSLESS_FORMATS =
    setOf("FLAC", "ALAC", "WAV", "AIFF", "AIF", "APE", "WV", "TTA", "DSF", "DFF")

/** The format label comes from the file name, or from a file URI when the source has no name. */
internal fun copyFormat(track: TrackDescriptor): String? {
    val name = track.fileName
        ?: track.audioUri?.takeIf { it.startsWith("file:") }?.substringAfterLast('/')
        ?: return null
    val extension = name.substringAfterLast('.', "")
    if (extension.isBlank() || extension.length > 5 || extension.any { !it.isLetterOrDigit() }) {
        return null
    }
    return extension.uppercase()
}

/** Bits per millisecond equals kilobits per second, so the estimate needs no unit juggling. */
internal fun estimatedBitrateKbps(track: TrackDescriptor): Int? {
    val size = track.sizeBytes?.takeIf { it > 0 } ?: return null
    val duration = track.durationMs?.takeIf { it > 0 } ?: return null
    return (size * 8.0 / duration).roundToInt().takeIf { it > 0 }
}

/**
 * A lossy codec's bitrate only means something next to its codec: Opus at 128 kbps sounds like
 * MP3 at roughly 190. These factors put every lossy copy on the MP3 scale before comparing.
 */
private val CODEC_EFFICIENCY = mapOf(
    "OPUS" to 1.5,
    "AAC" to 1.3,
    "M4A" to 1.3,
    "MP4" to 1.3,
    "OGG" to 1.25,
    "OGA" to 1.25,
    "WMA" to 0.9,
)

internal fun effectiveBitrateKbps(copy: DuplicateCopy): Int? {
    val bitrate = copy.bitrateKbps ?: return null
    return (bitrate * (CODEC_EFFICIENCY[copy.format] ?: 1.0)).roundToInt()
}

/**
 * Numeric ids (MediaStore rows) order as numbers, so the copy scanned first — usually the
 * original the others were made from — wins a tie; path ids order as text.
 */
private val ID_ORDER = Comparator<TrackId> { a, b ->
    val x = a.value.toLongOrNull()
    val y = b.value.toLongOrNull()
    when {
        x != null && y != null -> x.compareTo(y).takeIf { it != 0 }
            ?: a.value.compareTo(b.value)
        x != null -> -1
        y != null -> 1
        else -> a.value.compareTo(b.value)
    }
}

/**
 * The copy to keep: lossless before lossy, then the higher codec-adjusted bitrate, then the
 * bigger file; among equals, the one the listener already loves (favourite, plays, playlists),
 * then the older file, which is usually the original the others were copied from. Playlists and
 * favourites follow the survivor, so a well-loved lossy copy does not outrank a pristine one.
 */
internal fun recommendedCopy(copies: List<DuplicateCopy>): DuplicateCopy = copies.sortedWith(
    compareByDescending<DuplicateCopy> { it.lossless }
        .thenByDescending { effectiveBitrateKbps(it) ?: -1 }
        .thenByDescending { it.track.sizeBytes ?: -1L }
        .thenByDescending { it.favorite }
        .thenByDescending { it.plays }
        .thenByDescending { it.playlists }
        .thenBy { it.track.addedAtMs ?: Long.MAX_VALUE }
        .thenBy(ID_ORDER) { it.track.id },
).first()

internal fun describeDuplicateGroup(
    group: List<TrackDescriptor>,
    stats: Map<TrackId, TrackStats>,
    favorites: Set<TrackId>,
    playlistCounts: Map<TrackId, Int>,
): DuplicateGroup {
    val copies = group.map { track ->
        val format = copyFormat(track)
        DuplicateCopy(
            track = track,
            format = format,
            lossless = format != null && format in LOSSLESS_FORMATS,
            bitrateKbps = estimatedBitrateKbps(track),
            plays = stats[track.id]?.plays ?: 0,
            favorite = track.id in favorites,
            playlists = playlistCounts[track.id] ?: 0,
        )
    }
    return DuplicateGroup(copies = copies, recommended = recommendedCopy(copies))
}

/**
 * Each verdict is a clique of ids the listener says must stay separate. Membership lookup keeps
 * a large dismissed group linear in storage, instead of expanding it to every possible pair.
 */
internal class DuplicateExclusions internal constructor(records: List<List<TrackId>>) {
    private val membership = buildMap<TrackId, MutableSet<Int>> {
        records.forEachIndexed { index, ids ->
            ids.forEach { id -> getOrPut(id, ::HashSet).add(index) }
        }
    }

    fun isEmpty(): Boolean = membership.isEmpty()

    fun excludes(a: TrackId, b: TrackId): Boolean {
        if (a == b) return false
        val left = membership[a] ?: return false
        val right = membership[b] ?: return false
        return if (left.size <= right.size) left.any { it in right } else right.any { it in left }
    }
}

/**
 * Bounded, hex-encoded "not duplicates" verdicts. v1 lines are legacy pairs; v2 lines keep a
 * whole dismissed group in one record. Eviction drops an entire old verdict, never part of the
 * group the listener just dismissed. Both decoding and encoding enforce the storage limits.
 */
internal object DuplicateDismissals {
    private const val LEGACY_PREFIX = "v1|"
    private const val PREFIX = "v2|"
    private const val MAX_VERDICTS = 2_000
    private const val MAX_PAYLOAD_CHARS = 262_144

    fun decode(payload: String?): DuplicateExclusions = DuplicateExclusions(readRecords(payload))

    /** Add one verdict without materializing n*(n-1)/2 pairs on the UI thread. */
    fun remember(payload: String?, group: List<TrackId>): String {
        val verdict = group.distinct().sortedBy(TrackId::value)
        if (verdict.size < 2) return encodeRecords(readRecords(payload))
        require(verdict.none { it.value.isEmpty() }) { "A duplicate verdict needs nonempty track ids" }
        require(recordLine(verdict).length <= MAX_PAYLOAD_CHARS) { "Duplicate verdict is too large" }
        val previous = readRecords(payload).filterNot { it == verdict }
        return encodeRecords(previous + listOf(verdict))
    }

    private fun readRecords(payload: String?): List<List<TrackId>> {
        if (payload.isNullOrEmpty()) return emptyList()
        val records = ArrayDeque<List<TrackId>>()
        // Imported or corrupt preferences cannot force an unbounded decode. A clipped first
        // line lacks its version prefix and is ignored; complete recent records are retained.
        for (line in payload.takeLast(MAX_PAYLOAD_CHARS).lineSequence()) {
            val legacy = line.startsWith(LEGACY_PREFIX)
            if (!legacy && !line.startsWith(PREFIX)) continue
            val fields = line.substring(PREFIX.length).split('|')
            if (fields.size < 2 || (legacy && fields.size != 2)) continue
            val ids = fields.map { it.hexToId() }
            if (ids.any { it == null }) continue
            val verdict = ids.filterNotNull().distinct().sortedBy(TrackId::value)
            if (verdict.size < 2) continue
            records.addLast(verdict)
            if (records.size > MAX_VERDICTS) records.removeFirst()
        }
        return records.toList()
    }

    private fun encodeRecords(records: List<List<TrackId>>): String {
        val kept = ArrayDeque<String>()
        var characters = 0
        for (record in records.asReversed()) {
            if (kept.size >= MAX_VERDICTS) break
            val line = recordLine(record)
            val needed = line.length + if (kept.isEmpty()) 0 else 1
            if (needed > MAX_PAYLOAD_CHARS - characters) break
            kept.addFirst(line)
            characters += needed
        }
        return kept.joinToString("\n")
    }

    private fun recordLine(ids: List<TrackId>): String =
        PREFIX + ids.joinToString("|") { it.value.toHex() }

    private fun String.toHex(): String = encodeToByteArray().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun String.hexToId(): TrackId? {
        if (length % 2 != 0 || isEmpty()) return null
        val bytes = ByteArray(length / 2)
        for (index in bytes.indices) {
            val high = this[index * 2].digitToIntOrNull(16) ?: return null
            val low = this[index * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return runCatching { TrackId(bytes.decodeToString(throwOnInvalidSequence = true)) }.getOrNull()
    }
}

/**
 * One membership list (playlist rows, favorites) after merging a duplicate group into
 * [survivor]: every duplicate occurrence becomes the survivor, later repeats collapse, order
 * is otherwise preserved. Null means the list never referenced the group and needs no write.
 */
internal fun mergedMembership(
    current: List<TrackId>,
    duplicates: Set<TrackId>,
    survivor: TrackId,
): List<TrackId>? {
    if (current.none { it in duplicates }) return null
    val rewritten = current.map { if (it in duplicates) survivor else it }.distinct()
    return rewritten.takeIf { it != current }
}

/**
 * Rewrites all durable references before retiring any duplicate. A concurrent playlist or
 * favorites edit aborts the merge; previously rewritten playlists remain valid because all
 * copies are still visible and no references have been discarded. [onHideTrack] receives each
 * loser once the references are safe; a caller that deletes files instead simply collects them.
 */
internal suspend fun mergeDuplicateGroup(
    group: List<TrackDescriptor>,
    survivor: TrackDescriptor,
    playlists: Playlists,
    favorites: Favorites,
    onHideTrack: suspend (TrackDescriptor) -> Unit,
) {
    val losers = group.filter { it.id != survivor.id }
    val duplicateIds = losers.mapTo(mutableSetOf(), TrackDescriptor::id)
    for (playlist in playlists.all()) {
        val current = playlist.trackIds.map(::TrackId)
        val replacement = mergedMembership(current, duplicateIds, survivor.id) ?: continue
        check(playlists.replaceTracksIfUnchanged(playlist.id, current, replacement)) {
            "Playlist changed during duplicate merge"
        }
    }

    val currentFavorites = favorites.all()
    mergedMembership(currentFavorites, duplicateIds, survivor.id)?.let { replacement ->
        check(favorites.replaceIfUnchanged(currentFavorites, replacement)) {
            "Favorites changed during duplicate merge"
        }
    }
    losers.forEach { onHideTrack(it) }
}

/** "34.2" for 34_200_000 bytes; whole numbers from 100 MB up, where a decimal is noise. */
internal fun megabytesLabel(bytes: Long): String {
    val megabytes = bytes / 1_000_000.0
    if (megabytes >= 100.0) return megabytes.roundToInt().toString()
    val tenths = (megabytes * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
