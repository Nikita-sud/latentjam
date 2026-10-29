/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.text.intl.Locale
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.library.tags.TrackRecordingIds
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.playback.SoundSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.withLock
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
 * How much lower the similarity of two files may be when their tags name one recording (an equal
 * MusicBrainz recording id or a shared ISRC): a low-bitrate copy of a FLAC can fall just under
 * [DUPLICATE_SIMILARITY]. The audio still has to agree — tags can be wrong.
 */
internal const val SAME_RECORDING_SIMILARITY_SLACK = 0.01f

/** Two copies of one recording differ in length by at most this much. */
internal const val DUPLICATE_LENGTH_TOLERANCE_MS = 2_000L

/**
 * The length gap tags naming one recording can explain: one master fades out a few seconds later,
 * a digital release adds a pause. MusicBrainz gives a different edit its own recording.
 */
internal const val SAME_RECORDING_LENGTH_TOLERANCE_MS = 10_000L

/**
 * The widest gap between two files' declared lengths the finder ever looks across. Past it no
 * silence at the edges or shared tag makes two files one copy: the scan's duration windows stay
 * this narrow, so a large library is never compared all against all.
 */
internal const val DUPLICATE_MAX_LENGTH_GAP_MS = 15_000L

/**
 * What the finder knows of one file beyond its audio vector. Every field is optional: unknown
 * evidence never makes a pair a duplicate, and never stops one the audio and lengths alone accept.
 */
internal data class DuplicateEvidence(
    /** Length from the first to the last sound, without the silence at either edge; null when unmeasured. */
    val soundMs: Long? = null,
    /** Tag identity; null when not read (or unreadable). */
    val ids: TrackRecordingIds? = null,
)

/**
 * Whether two files' tags name one recording. An equal MusicBrainz recording id says so. A shared
 * ISRC says so too, unless the two recording ids are both present and differ: a compilation can
 * reuse an ISRC, while MusicBrainz recording ids are curated. Different recording ids do not veto
 * the audio itself — MusicBrainz still holds unmerged duplicate recordings, and labels reissue the
 * same audio under new ISRCs — they only withhold this extra trust.
 */
internal fun namesSameRecording(a: TrackRecordingIds?, b: TrackRecordingIds?): Boolean {
    if (a == null || b == null) return false
    val left = a.recording
    val right = b.recording
    if (left != null && right != null) return left == right
    return a.isrcs.any { it in b.isrcs }
}

/**
 * The finder's rule for one pair of files, given their audio [similarity] (cosine), declared
 * durations and [DuplicateEvidence]:
 * - [threshold] similarity and declared lengths within [DUPLICATE_LENGTH_TOLERANCE_MS] (or a
 *   length unknown): the rule as it always was;
 * - [threshold] similarity and sound lengths within the same tolerance: copies that differ only in
 *   the silence before the first sound or after the last one, such as a digital release with two
 *   more seconds of silence at its end;
 * - tags naming one recording ([namesSameRecording]): similarity down to [threshold] minus
 *   [SAME_RECORDING_SIMILARITY_SLACK] and lengths (declared or sound) within
 *   [SAME_RECORDING_LENGTH_TOLERANCE_MS].
 *
 * Declared lengths further apart than [DUPLICATE_MAX_LENGTH_GAP_MS] are never one copy.
 */
internal fun isDuplicatePair(
    similarity: Float,
    threshold: Float,
    durationA: Long?,
    durationB: Long?,
    evidenceA: DuplicateEvidence?,
    evidenceB: DuplicateEvidence?,
): Boolean = similarity.isFinite() && isDuplicatePair(
    audioMatches = similarity >= threshold,
    audioMatchesSameRecording = similarity >= threshold - SAME_RECORDING_SIMILARITY_SLACK,
    durationA = durationA,
    durationB = durationB,
    evidenceA = evidenceA,
    evidenceB = evidenceB,
)

/**
 * [isDuplicatePair] with the similarity already compared: [audioMatches] against the threshold,
 * [audioMatchesSameRecording] against the threshold less [SAME_RECORDING_SIMILARITY_SLACK]. The
 * scan compares squared distances, exactly as it always did, and hands over only the verdicts.
 */
internal fun isDuplicatePair(
    audioMatches: Boolean,
    audioMatchesSameRecording: Boolean,
    durationA: Long?,
    durationB: Long?,
    evidenceA: DuplicateEvidence?,
    evidenceB: DuplicateEvidence?,
): Boolean {
    if (!audioMatchesSameRecording) return false
    val declaredGap = if (durationA != null && durationB != null) abs(durationA - durationB) else null
    if (declaredGap != null && declaredGap > DUPLICATE_MAX_LENGTH_GAP_MS) return false
    val soundA = evidenceA?.soundMs
    val soundB = evidenceB?.soundMs
    val soundGap = if (soundA != null && soundB != null) abs(soundA - soundB) else null
    fun lengthsWithin(tolerance: Long): Boolean =
        declaredGap == null || declaredGap <= tolerance || (soundGap != null && soundGap <= tolerance)
    if (audioMatches && lengthsWithin(DUPLICATE_LENGTH_TOLERANCE_MS)) return true
    return namesSameRecording(evidenceA?.ids, evidenceB?.ids) &&
        lengthsWithin(SAME_RECORDING_LENGTH_TOLERANCE_MS)
}

/** Two files the audio nearly matches but that the rule could not accept with what it knew. */
internal data class UndecidedDuplicatePair(val a: TrackId, val b: TrackId, val similarity: Float)

/** A scan's complete-link groups, and the pairs more evidence might still turn into duplicates. */
internal class DuplicateScan(
    val groups: List<List<TrackId>>,
    val undecided: List<UndecidedDuplicatePair>,
)

/**
 * Groups tracks whose stored audio embeddings are near-identical — the tag-blind duplicate
 * finder; [isDuplicatePair] is the rule, with [threshold] its similarity. Every pair in a group
 * meets the rule, so whichever row the listener keeps is a near-duplicate of every row that will
 * be hidden. Biggest group first; singletons are dropped.
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
    evidence: Map<TrackId, DuplicateEvidence> = emptyMap(),
    cancellationCheck: () -> Unit = {},
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): List<List<TrackId>> = scanAudioDuplicates(
    vectors = vectors,
    threshold = threshold,
    durationsMs = durationsMs,
    dismissed = dismissed,
    evidence = evidence,
    cancellationCheck = cancellationCheck,
    onProgress = onProgress,
).groups

/** The most undecided pairs one scan keeps; past it the rest stay as the plain rule left them. */
internal const val MAX_UNDECIDED_DUPLICATE_PAIRS = 2_000

/** [audioDuplicateGroups], also returning the undecided pairs (at most [maxUndecided]). */
internal fun scanAudioDuplicates(
    vectors: Map<TrackId, FloatArray>,
    threshold: Float = DUPLICATE_SIMILARITY,
    durationsMs: Map<TrackId, Long?> = emptyMap(),
    dismissed: DuplicateExclusions = DuplicateExclusions(emptyList()),
    evidence: Map<TrackId, DuplicateEvidence> = emptyMap(),
    maxUndecided: Int = MAX_UNDECIDED_DUPLICATE_PAIRS,
    cancellationCheck: () -> Unit = {},
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): DuplicateScan {
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
    // The loosest similarity any evidence can make acceptable bounds the distance computation;
    // the rule itself then decides with the exact similarity.
    val strictSquaredDistance = 2f * (1f - threshold.coerceIn(-1f, 1f))
    val maximumSquaredDistance =
        2f * (1f - (threshold - SAME_RECORDING_SIMILARITY_SLACK).coerceIn(-1f, 1f))
    val durations = ids.map { id -> durationsMs[id]?.takeIf { it > 0 } }
    val facts = ids.map { id -> evidence[id] }
    val undecided = ArrayList<UndecidedDuplicatePair>()

    /** The pair's squared distance when it is at most [bound], else null. */
    fun squaredDistanceOrNull(aIndex: Int, bIndex: Int, bound: Float): Float? {
        if (dismissed.excludes(ids[aIndex], ids[bIndex])) return null
        val a = rows[aIndex] ?: return null
        val b = rows[bIndex] ?: return null
        if (a.size != b.size) return null
        // For unit vectors, ||a-b||² = 2 - 2*cos(a,b). Accumulated squared distance is
        // monotonic, so most unrelated tracks can be rejected after only a few dimensions. It is
        // checked every four dimensions, which only delays a rejection: the sum is the same.
        var squaredDistance = 0f
        var dimension = 0
        val blocked = a.size - a.size % 4
        while (dimension < blocked) {
            val d0 = a[dimension] - b[dimension]
            val d1 = a[dimension + 1] - b[dimension + 1]
            val d2 = a[dimension + 2] - b[dimension + 2]
            val d3 = a[dimension + 3] - b[dimension + 3]
            squaredDistance += d0 * d0
            squaredDistance += d1 * d1
            squaredDistance += d2 * d2
            squaredDistance += d3 * d3
            if (squaredDistance > bound) return null
            dimension += 4
        }
        while (dimension < a.size) {
            val difference = a[dimension] - b[dimension]
            squaredDistance += difference * difference
            dimension++
        }
        return squaredDistance.takeIf { it <= bound }
    }

    fun areDuplicates(aIndex: Int, bIndex: Int): Boolean {
        val durationA = durations[aIndex]
        val durationB = durations[bIndex]
        // Past the id rule's length tolerance only the plain similarity can still be won (by
        // sound lengths), so the cheaper bound suffices there.
        val bound = if (durationA != null && durationB != null &&
            abs(durationA - durationB) > SAME_RECORDING_LENGTH_TOLERANCE_MS
        ) strictSquaredDistance else maximumSquaredDistance
        val squaredDistance = squaredDistanceOrNull(aIndex, bIndex, bound) ?: return false
        val duplicate = isDuplicatePair(
            audioMatches = squaredDistance <= strictSquaredDistance,
            audioMatchesSameRecording = true,
            durationA = durationA,
            durationB = durationB,
            evidenceA = facts[aIndex],
            evidenceB = facts[bIndex],
        )
        if (!duplicate && undecided.size < maxUndecided) {
            undecided += UndecidedDuplicatePair(ids[bIndex], ids[aIndex], 1f - squaredDistance / 2f)
        }
        return duplicate
    }

    // A connected component is unsafe here: A≈B and B≈C does not imply A≈C. Build deterministic
    // complete-link groups, but only decode vector pairs whose known durations can represent the
    // same recording. Rows sorted by duration turn a normal large library from all-pairs work into
    // local windows, found by binary search with no per-row allocation; unknown durations remain
    // conservative and compare against every earlier row.
    val window = DUPLICATE_MAX_LENGTH_GAP_MS
    val byDuration = ids.indices.filter { durations[it] != null }
        .sortedWith(compareBy<Int> { durations[it]!! }.thenBy { it })
        .toIntArray()
    val sortedDurations = LongArray(byDuration.size) { durations[byDuration[it]]!! }
    val unknownDurationIndices = IntArray(ids.size)
    var unknownCount = 0
    val completeLinkGroups = mutableListOf<MutableList<Int>>()
    val groupOf = IntArray(ids.size) { -1 }
    var pairChecks = 0
    for (candidate in ids.indices) {
        if (candidate and 127 == 0) {
            cancellationCheck()
            onProgress(candidate, ids.size)
        }
        val duration = durations[candidate]
        val duplicatePrior = HashSet<Int>()
        fun consider(prior: Int) {
            if (pairChecks++ and 1023 == 0) cancellationCheck()
            if (areDuplicates(candidate, prior)) duplicatePrior += prior
        }
        if (duration == null) {
            for (prior in 0 until candidate) consider(prior)
        } else {
            for (unknown in 0 until unknownCount) consider(unknownDurationIndices[unknown])
            var position = lowerBound(sortedDurations, duration - window)
            while (position < sortedDurations.size && sortedDurations[position] <= duration + window) {
                val prior = byDuration[position++]
                if (prior < candidate) consider(prior)
            }
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
        if (duration == null) unknownDurationIndices[unknownCount++] = candidate
    }
    onProgress(ids.size, ids.size)

    val groups = completeLinkGroups
        .filter { it.size > 1 }
        .map { group -> group.map { ids[it] } }
        .sortedWith(compareByDescending<List<TrackId>> { it.size }.thenBy { it.first().value })
    return DuplicateScan(groups, undecided)
}

/** The first position in ascending [values] holding at least [key]. */
private fun lowerBound(values: LongArray, key: Long): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (values[middle] < key) low = middle + 1 else high = middle
    }
    return low
}

/** Per-scan bounds on the evidence the finder gathers for undecided pairs. */
internal object DuplicateEvidenceLimits {
    /** Files whose tags are read (each read is bounded to the metadata, at most 8 MiB). */
    const val MAX_TAG_READS = 256

    /** Files decoded to find where their sound begins and ends. */
    const val MAX_SOUND_MEASUREMENTS = 24

    /** One file's decode; a slower one stays unmeasured. */
    const val SOUND_MEASUREMENT_TIMEOUT_MS = 30_000L

    /** All decodes of one scan together. */
    const val SOUND_BUDGET_MS = 90_000L
}

/** A measured end may run this far past the file's declared duration (container rounding). */
private const val SOUND_SPAN_SLACK_MS = 2_000L

/**
 * The length of [span]'s sound — from its first sound to its last, the silence at both edges left
 * out — or null when there is no span or it does not fit a file of [durationMs].
 */
internal fun soundLengthMs(span: SoundSpan?, durationMs: Long?): Long? {
    if (span == null || !span.isValid()) return null
    if (durationMs != null && durationMs > 0 && span.endMs > durationMs + SOUND_SPAN_SLACK_MS) return null
    return span.endMs - span.startMs
}

/**
 * The duplicate finder's whole scan: [scanAudioDuplicates] on the audio and declared lengths, then,
 * only for the pairs the audio nearly matches but that rule could not accept, evidence gathered on
 * demand — the files' recording ids and ISRCs ([readIds]), then, where the declared lengths still
 * disagree, the length of their sound without edge silence ([knownSoundMs] first, which costs
 * nothing, else [measureSoundMs], a decode) — and one more scan with it.
 *
 * Gathering is bounded by [DuplicateEvidenceLimits] and cancellable; a read or decode that fails,
 * times out or is over budget leaves its file unknown, which only means the plain rule decides.
 * [onProgress] runs from 0 to 1 over the whole scan.
 */
internal suspend fun findAudioDuplicates(
    vectors: Map<TrackId, FloatArray>,
    durationsMs: Map<TrackId, Long?>,
    dismissed: DuplicateExclusions,
    readIds: suspend (TrackId) -> TrackRecordingIds?,
    measureSoundMs: suspend (TrackId) -> Long?,
    knownSoundMs: (TrackId) -> Long? = { null },
    threshold: Float = DUPLICATE_SIMILARITY,
    timeSource: TimeSource = TimeSource.Monotonic,
    onProgress: (Float) -> Unit = {},
): List<List<TrackId>> {
    val context = currentCoroutineContext()
    val cancellationCheck = { context.ensureActive() }
    val first = scanAudioDuplicates(
        vectors = vectors,
        threshold = threshold,
        durationsMs = durationsMs,
        dismissed = dismissed,
        cancellationCheck = cancellationCheck,
        onProgress = { done, total -> if (total > 0) onProgress(FIRST_SCAN_SHARE * done / total) },
    )
    if (first.undecided.isEmpty()) {
        onProgress(1f)
        return first.groups
    }

    val ids = HashMap<TrackId, TrackRecordingIds>()
    first.undecided.asSequence().flatMap { sequenceOf(it.a, it.b) }.distinct()
        .take(DuplicateEvidenceLimits.MAX_TAG_READS)
        .forEach { id ->
            context.ensureActive()
            gathered { readIds(id) }?.let { ids[id] = it }
        }

    fun duration(id: TrackId): Long? = durationsMs[id]?.takeIf { it > 0 }
    val wantsSound = LinkedHashSet<TrackId>()
    for (pair in first.undecided) {
        val a = DuplicateEvidence(ids = ids[pair.a])
        val b = DuplicateEvidence(ids = ids[pair.b])
        val durationA = duration(pair.a) ?: continue
        val durationB = duration(pair.b) ?: continue
        if (isDuplicatePair(pair.similarity, threshold, durationA, durationB, a, b)) continue
        // Only a pair whose audio passes, or whose tags name one recording, can be won by sound lengths.
        if (pair.similarity < threshold && !namesSameRecording(a.ids, b.ids)) continue
        wantsSound += pair.a
        wantsSound += pair.b
    }
    val sound = HashMap<TrackId, Long>()
    val started = timeSource.markNow()
    var measured = 0
    for (id in wantsSound) {
        context.ensureActive()
        gathered { knownSoundMs(id) }?.takeIf { it > 0 }?.let { sound[id] = it; continue }
        if (measured >= DuplicateEvidenceLimits.MAX_SOUND_MEASUREMENTS) continue
        val left = DuplicateEvidenceLimits.SOUND_BUDGET_MS - started.elapsedNow().inWholeMilliseconds
        if (left <= 0L) break
        measured++
        val length = withTimeoutOrNull(minOf(left, DuplicateEvidenceLimits.SOUND_MEASUREMENT_TIMEOUT_MS)) {
            gathered { measureSoundMs(id) }
        }
        length?.takeIf { it > 0 }?.let { sound[id] = it }
        onProgress(FIRST_SCAN_SHARE + EVIDENCE_SHARE * measured / wantsSound.size.coerceAtLeast(1))
    }

    val evidence = HashMap<TrackId, DuplicateEvidence>()
    (ids.keys + sound.keys).forEach { id -> evidence[id] = DuplicateEvidence(soundMs = sound[id], ids = ids[id]) }
    if (evidence.isEmpty()) {
        onProgress(1f)
        return first.groups
    }
    val secondStart = FIRST_SCAN_SHARE + EVIDENCE_SHARE
    return scanAudioDuplicates(
        vectors = vectors,
        threshold = threshold,
        durationsMs = durationsMs,
        dismissed = dismissed,
        evidence = evidence,
        maxUndecided = 0,
        cancellationCheck = cancellationCheck,
        onProgress = { done, total ->
            if (total > 0) onProgress(secondStart + (1f - secondStart) * done / total)
        },
    ).groups
}

private const val FIRST_SCAN_SHARE = 0.8f
private const val EVIDENCE_SHARE = 0.1f

/** [block]'s result, or null where it failed: evidence that cannot be gathered is unknown. */
private suspend fun <T> gathered(block: suspend () -> T?): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
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

    /** Whether every pair of [ids] is already kept apart: a verdict on them would add nothing. */
    fun excludesAll(ids: List<TrackId>): Boolean =
        ids.indices.all { first -> (first + 1 until ids.size).all { excludes(ids[first], ids[it]) } }
}

/**
 * Bounded, hex-encoded "not duplicates" verdicts. v1 lines are legacy pairs; v2 lines keep a
 * whole dismissed group in one record. Eviction drops an entire old verdict, never part of the
 * group the listener just dismissed. Both decoding and encoding enforce the storage limits.
 */
internal object DuplicateDismissals {
    private const val LEGACY_PREFIX = "v1|"
    private const val PREFIX = "v2|"

    /** The most verdicts kept; past it the oldest leave first. A local backup carries no more. */
    const val MAX_VERDICTS = 2_000
    private const val MAX_PAYLOAD_CHARS = 262_144

    /**
     * Every verdict is written by reading the whole payload, changing it and writing it back: the
     * finder's "Not duplicates" and a backup restore both do. A restore outlives its screen, so the
     * two can overlap, and two interleaved passes would each drop the other's verdicts.
     */
    private val updateLock = Mutex()

    fun decode(payload: String?): DuplicateExclusions = DuplicateExclusions(readRecords(payload))

    /** The stored verdicts, oldest first, each its tracks once and in id order. */
    fun verdicts(payload: String?): List<List<TrackId>> = readRecords(payload)

    /** Stores the finder's verdict on [group]; see [remember]. */
    suspend fun rememberIn(settings: AppSettings, group: List<TrackId>): Unit = updateLock.withLock {
        settings.writeDuplicateDismissalsPayload(remember(settings.readDuplicateDismissalsPayload(), group))
    }

    /**
     * Writes the verdicts a backup restore resolved on this device: they become the stored ones
     * when [replace], and are merged into them otherwise ([merged] says how).
     *
     * @return how many of [imported] the store keeps after the write, written or already said by a
     *   stored verdict. The others did not fit: a verdict whose record outgrows the store's bound
     *   under this device's ids (on iOS an imported file's id is its path, far longer than Android's
     *   numeric ids, so a verdict that fitted where the backup was made may not fit here), or one a
     *   merge left out of a full store.
     */
    suspend fun restore(
        settings: AppSettings,
        imported: List<List<TrackId>>,
        replace: Boolean,
    ): Int = updateLock.withLock {
        val stored = if (replace) emptyList() else readRecords(settings.readDuplicateDismissalsPayload())
        val restored = restored(stored, imported)
        settings.writeDuplicateDismissalsPayload(restored.payload)
        restored.kept
    }

    /**
     * [payload] with restored verdicts merged in, without duplicates and without overwriting
     * anything. Every verdict says the same kind of thing — these tracks are different recordings
     * — and the finder stores nothing else (a merge of copies travels as the playlists, favourites
     * and hidden tracks it rewrote), so two verdicts never contradict each other: the union is
     * the merge.
     *
     * - A restored verdict that is already said is not added again: one equal to a stored verdict,
     *   one repeated in the file, or one whose every pair the stored verdicts already keep apart.
     * - The store keeps the newest [MAX_VERDICTS] within its size bound, so the restored verdicts
     *   are placed before the stored ones, as older: a large backup can never push out a verdict
     *   this device made. If the bound is reached, it is the restored ones that are left out.
     */
    fun merged(payload: String?, imported: List<List<TrackId>>): String =
        restored(readRecords(payload), imported).payload

    /** A restore's payload, and how many of the verdicts it was given that payload keeps. */
    private class Restored(val payload: String, val kept: Int)

    /** [imported] merged into [stored] as [merged] describes; an empty [stored] is a replace. */
    private fun restored(stored: List<List<TrackId>>, imported: List<List<TrackId>>): Restored {
        val known = DuplicateExclusions(stored)
        val verdicts = imported.map(::storableVerdict)
        val alreadySaid = HashSet<List<TrackId>>()
        val added = LinkedHashSet<List<TrackId>>()
        for (verdict in verdicts) {
            if (verdict == null || verdict in alreadySaid || verdict in added) continue
            if (known.excludesAll(verdict)) alreadySaid += verdict else added += verdict
        }
        val payload = encodeRecords(added.toList() + stored)
        // Counted from what the payload decodes to, so every bound the encoder applies is included.
        val written = readRecords(payload).toHashSet()
        val kept = verdicts.count { verdict -> verdict != null && (verdict in alreadySaid || verdict in written) }
        return Restored(payload, kept)
    }

    /** [verdict] as the store keeps it, or null where it cannot be one. */
    private fun storableVerdict(verdict: List<TrackId>): List<TrackId>? =
        verdict.distinct().sortedBy(TrackId::value).takeIf { normalized ->
            normalized.size >= 2 && normalized.none { it.value.isEmpty() } &&
                recordLine(normalized).length <= MAX_PAYLOAD_CHARS
        }

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
        val added = playlist.addedTrackIds.map(::TrackId)
        val replacementAdded = mergedMembership(added, duplicateIds, survivor.id) ?: added
        check(playlists.replaceTracksIfUnchanged(playlist.id, current, replacement, added, replacementAdded)) {
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

/**
 * "34.2" for 34_200_000 bytes; whole numbers from 100 MB up, where a decimal is noise. The
 * separator is [currentDecimalSeparator]: the label is dropped into a translated sentence
 * ("34,2 МБ"), so a hard-coded full stop reads wrong in most of the languages this app ships in.
 * It stays a parameter so a caller that knows its own separator — a test, a report — can pin it.
 */
internal fun megabytesLabel(
    bytes: Long,
    separator: Char = currentDecimalSeparator(),
): String {
    val megabytes = bytes / 1_000_000.0
    if (megabytes >= 100.0) return megabytes.roundToInt().toString()
    val tenths = (megabytes * 10).roundToInt()
    return "${tenths / 10}$separator${tenths % 10}"
}

/**
 * The decimal separator CLDR gives an ISO 639 language code: a comma in the European languages, in
 * Indonesian and in Turkish, the Arabic decimal separator U+066B in Arabic (what CLDR puts into a
 * number even when it is written with Western digits), a full stop everywhere else. `in` is the
 * code Android reports for Indonesian up to API 34 and `id` from API 35; both folders ship, so both
 * codes are listed.
 */
internal fun decimalSeparatorFor(language: String): Char = when (language.lowercase()) {
    "ar" -> '\u066B' // ARABIC DECIMAL SEPARATOR
    "de", "es", "fr", "id", "in", "it", "pl", "pt", "ro", "ru", "tr", "uk" -> ','
    else -> '.'
}

/**
 * The decimal separator for the language the UI is being written in right now — the one rule behind
 * every size label the app prints, so the duplicate finder's "34,2" and the track details'
 * "34,2 MB" can never disagree. [Locale.current] answers with the per-app language on Android 13+
 * and with the system language everywhere else.
 */
internal fun currentDecimalSeparator(): Char = decimalSeparatorFor(appLanguage())

/**
 * The language Compose lays the UI out in: the per-app language where the system has one, the
 * system language otherwise. A lookup that cannot answer — host tests run against a stubbed
 * android.jar — falls back to English, which is also the resources' own fallback.
 */
private fun appLanguage(): String = runCatching { Locale.current.language }.getOrDefault("en")
