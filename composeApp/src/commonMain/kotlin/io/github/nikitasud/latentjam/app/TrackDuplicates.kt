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
import kotlin.math.roundToInt

/**
 * Two files closer than this in audio space are treated as the same recording. The audio
 * embeddings are L2-normalized, so different encodes of one recording land at ~0.995+, while
 * covers, remixes and live versions stay clearly below.
 */
internal const val DUPLICATE_SIMILARITY = 0.99f

/** An unordered pair of tracks; the listener's "not duplicates" verdicts are stored as these. */
internal data class DuplicatePair(val first: TrackId, val second: TrackId) {
    companion object {
        fun of(a: TrackId, b: TrackId): DuplicatePair =
            if (a.value <= b.value) DuplicatePair(a, b) else DuplicatePair(b, a)
    }
}

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
    dismissed: Set<DuplicatePair> = emptySet(),
    cancellationCheck: () -> Unit = {},
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): List<List<TrackId>> {
    val ids = vectors.keys.sortedBy(TrackId::value)
    val rows = ids.map { vectors.getValue(it) }
    val durations = ids.map { id -> durationsMs[id]?.takeIf { it > 0 } }

    fun areDuplicates(aIndex: Int, bIndex: Int): Boolean {
        if (dismissed.isNotEmpty() && DuplicatePair.of(ids[aIndex], ids[bIndex]) in dismissed) {
            return false
        }
        val a = rows[aIndex]
        val b = rows[bIndex]
        if (a.size != b.size) return false
        // For L2-normalized rows, ||a-b||² = 2 - 2*cos(a,b). Accumulated squared distance is
        // monotonic, so most unrelated tracks can be rejected after only a few dimensions.
        val maximumSquaredDistance = 2f * (1f - threshold.coerceIn(-1f, 1f))
        var squaredDistance = 0f
        for (dimension in a.indices) {
            val difference = a[dimension] - b[dimension]
            squaredDistance += difference * difference
            if (!squaredDistance.isFinite() || squaredDistance > maximumSquaredDistance) {
                return false
            }
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
    val key: String = copies.map { it.track.id.value }.sorted().joinToString("\n")

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
    if (x != null && y != null) x.compareTo(y) else a.value.compareTo(b.value)
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
 * The listener's "not duplicates" verdicts, stored as one line per pair. Ids are hex-encoded so a
 * path-shaped iOS id with any separator in it survives; the file is bounded, oldest verdicts first
 * to go, because a verdict about a pair that no longer exists costs nothing to lose.
 */
internal object DuplicateDismissals {
    private const val PREFIX = "v1|"
    private const val MAX_PAIRS = 2_000

    fun decode(payload: String?): Set<DuplicatePair> {
        if (payload.isNullOrEmpty()) return emptySet()
        return payload.lineSequence()
            .filter { it.startsWith(PREFIX) }
            .mapNotNull { line ->
                val fields = line.removePrefix(PREFIX).split('|')
                if (fields.size != 2) return@mapNotNull null
                val a = fields[0].hexToId() ?: return@mapNotNull null
                val b = fields[1].hexToId() ?: return@mapNotNull null
                DuplicatePair.of(a, b)
            }
            .toCollection(LinkedHashSet())
    }

    fun encode(pairs: Collection<DuplicatePair>): String =
        pairs.toCollection(LinkedHashSet()).toList().takeLast(MAX_PAIRS).joinToString("\n") { pair ->
            PREFIX + pair.first.value.toHex() + "|" + pair.second.value.toHex()
        }

    /** Every pair inside a group: dismissing a group means no two of its members can regroup. */
    fun pairsOf(group: List<TrackId>): Set<DuplicatePair> = buildSet {
        for (i in group.indices) {
            for (j in i + 1 until group.size) add(DuplicatePair.of(group[i], group[j]))
        }
    }

    private fun String.toHex(): String = encodeToByteArray().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun String.hexToId(): TrackId? {
        if (length % 2 != 0 || isEmpty()) return null
        val bytes = ByteArray(length / 2)
        for (index in bytes.indices) {
            val value = substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            bytes[index] = value.toByte()
        }
        return TrackId(bytes.decodeToString())
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
