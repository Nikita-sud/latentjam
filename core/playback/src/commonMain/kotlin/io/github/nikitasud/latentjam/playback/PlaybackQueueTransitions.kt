/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/** A queue reorder together with the row that must remain current. */
internal data class PlaybackQueueOrder(
    val tracks: List<TrackDescriptor>,
    val currentIndex: Int,
)

/** Non-current rows to splice around a platform player's already-loaded current item. */
internal data class PlaybackQueueSplice(
    val beforeCurrent: List<TrackDescriptor>,
    val current: TrackDescriptor,
    val afterCurrent: List<TrackDescriptor>,
)

/**
 * Plans a playlist replacement that never removes/reloads the current media item.
 *
 * Platform controllers apply [beforeCurrent] and [afterCurrent] around their existing native
 * current item. That preserves decoder state, position and playWhenReady while SMART's generated
 * queue is exchanged for source order (or source/shuffle history is exchanged for SMART order).
 */
internal fun playbackQueueSplice(order: PlaybackQueueOrder): PlaybackQueueSplice? {
    if (order.currentIndex !in order.tracks.indices) return null
    return PlaybackQueueSplice(
        beforeCurrent = order.tracks.take(order.currentIndex),
        current = order.tracks[order.currentIndex],
        afterCurrent = order.tracks.drop(order.currentIndex + 1),
    )
}

/** Result policy for materializing the exact traversal of a persisted ON queue. */
internal enum class RestoredOnShuffleOrder {
    SAVED_IDENTITY,
    NATIVE_RANDOM_FALLBACK,
}

internal data class RestoredOnShufflePolicy(
    val order: RestoredOnShuffleOrder,
    val nativeShuffleEnabled: Boolean,
)

/**
 * Native shuffle stays enabled in either result. The fallback may lose exact saved Next order, but
 * remains a coherent ON queue and never turns a recoverable resume into a launch exception.
 */
internal fun restoredOnShufflePolicy(identityOrderInstalled: Boolean): RestoredOnShufflePolicy =
    RestoredOnShufflePolicy(
        order = if (identityOrderInstalled) {
            RestoredOnShuffleOrder.SAVED_IDENTITY
        } else {
            RestoredOnShuffleOrder.NATIVE_RANDOM_FALLBACK
        },
        nativeShuffleEnabled = true,
    )

/** Physical permutation installed after the persisted ON traversal is materialized as the queue. */
internal fun restoredOnIdentityTraversal(queueSize: Int): IntArray =
    IntArray(queueSize.coerceAtLeast(0)) { it }

/** Complete launch-resume inputs shared by both platform controllers. */
internal data class PlaybackResumePlan(
    val liveQueue: List<TrackDescriptor>,
    val sourceQueue: List<TrackDescriptor>,
    val currentIndex: Int,
)

/**
 * Keeps a persisted SMART queue separate from its canonical source during launch restore.
 *
 * Sessions written before source persistence pass a null [sourceQueue] and deliberately fall back
 * to the live queue. An explicitly empty source remains empty so deleting every original playlist
 * row cannot turn a generated SMART tail into the new source. Copying both lists prevents a mutable
 * caller from changing a committed player/source pair after the restore returns.
 */
internal fun playbackResumePlan(
    liveQueue: List<TrackDescriptor>,
    currentIndex: Int,
    sourceQueue: List<TrackDescriptor>?,
): PlaybackResumePlan {
    val stableLive = liveQueue.toList()
    val stableSource = sourceQueue?.toList() ?: stableLive
    return PlaybackResumePlan(
        liveQueue = stableLive,
        sourceQueue = stableSource,
        currentIndex = if (stableLive.isEmpty()) -1 else currentIndex.coerceIn(stableLive.indices),
    )
}

/**
 * Restores source order without inventing a different current track.
 *
 * A manual or SMART-generated current item need not belong to [source]. In that case it remains at
 * the playhead and the source follows it; coercing a missing lookup to index zero would make state
 * claim the first source row while the backend continues playing [current].
 */
internal fun sourceOrderKeepingCurrent(
    source: List<TrackDescriptor>,
    current: TrackDescriptor,
): PlaybackQueueOrder {
    val sourceIndex = source.indexOfFirst { it.id == current.id }
    return if (sourceIndex >= 0) {
        PlaybackQueueOrder(tracks = source, currentIndex = sourceIndex)
    } else {
        PlaybackQueueOrder(
            tracks = listOf(current) + source.filterNot { it.id == current.id },
            currentIndex = 0,
        )
    }
}

/** The two user-facing ways to extend the durable OFF/ON source queue. */
internal enum class SourceQueueInsertion { PLAY_NEXT, APPEND }

/**
 * Incorporates a manual queue command into the source order used by future mode transitions.
 *
 * The live backend queue is edited separately because ON shuffle has its own traversal order. This
 * helper updates the canonical natural source without duplicating an existing identity: Play Next
 * inserts a genuinely new row after the current source row (or appends when current is external),
 * while Add to Queue appends it. Supplying an existing id is deliberately a no-op; one queue row is
 * one track identity, and otherwise SMART-to-OFF reconstruction could manufacture duplicates.
 */
internal fun sourceQueueAfterManualInsert(
    source: List<TrackDescriptor>,
    currentId: TrackId?,
    track: TrackDescriptor,
    insertion: SourceQueueInsertion,
): List<TrackDescriptor> {
    val stable = source.distinctBy { it.id }
    if (stable.any { it.id == track.id }) return stable
    if (insertion == SourceQueueInsertion.APPEND) return stable + track

    val currentIndex = currentId?.let { id -> stable.indexOfFirst { it.id == id } } ?: -1
    val insertAt = if (currentIndex >= 0) currentIndex + 1 else stable.size
    return stable.toMutableList().apply { add(insertAt, track) }
}

/**
 * Applies a visible queue reorder to the canonical source without leaking SMART recommendations.
 *
 * Under OFF every visible row is the source, so its new order can be adopted directly. ON exposes
 * a shuffled traversal and SMART exposes generated/history rows, so neither is allowed to rewrite
 * the natural source order merely because the listener rearranged that mode's live queue.
 */
internal fun sourceQueueAfterVisibleReorder(
    source: List<TrackDescriptor>,
    visibleQueue: List<TrackDescriptor>,
    mode: ShuffleMode,
): List<TrackDescriptor> = when (mode) {
    ShuffleMode.ON, ShuffleMode.SMART -> source
    ShuffleMode.OFF -> visibleQueue.toList()
}

/**
 * Retains actual shuffle history through the current row before SMART takes ownership.
 *
 * A Media3 playlist is stored in physical source order, while its user-visible/current traversal is
 * a permutation. Truncating physical indices when entering SMART therefore either forgets already
 * played rows or keeps future rows as fake history. This projection uses the traversal snapshot and
 * degrades to the current row alone when that row cannot be resolved.
 */
internal fun <T : Any> traversalHistoryThroughCurrent(
    rows: List<T>,
    currentRowIndex: Int,
): List<T> = if (currentRowIndex in rows.indices) {
    rows.take(currentRowIndex + 1)
} else {
    emptyList()
}

/**
 * Drops only the unplayed SMART future when recommendation policy changes.
 *
 * A temporarily missing backend index still preserves the first queued seed. This matches the
 * controller invariant used while a media backend is preparing: a non-empty SMART queue always has
 * one current-or-cued row, and policy invalidation must never turn that into an empty transport.
 */
internal fun <T : Any> smartHistoryThroughCurrent(
    rows: List<T>,
    currentRowIndex: Int,
): List<T> {
    if (rows.isEmpty()) return emptyList()
    val keepThrough = currentRowIndex.takeIf { it in rows.indices } ?: 0
    return rows.take(keepThrough + 1)
}

/** Whether a restored/active SMART queue is short enough to require another chooser call. */
internal fun smartQueueNeedsTopUp(
    queueSize: Int,
    currentIndex: Int,
    lookahead: Int,
): Boolean = queueSize > 0 &&
    currentIndex in 0 until queueSize &&
    queueSize - 1 - currentIndex < lookahead.coerceAtLeast(1)

/**
 * Bounded forward recovery candidates after a backend fails its current media item.
 *
 * The failed item is never returned. Repeat-all may wrap, but every other row appears at most once,
 * so a queue made entirely from unreadable files cannot enter an infinite prepare/error loop.
 */
internal fun playbackErrorRecoveryTraversal(
    queueSize: Int,
    failedIndex: Int,
    repeatAll: Boolean,
    traversalOrder: IntArray = IntArray(queueSize) { it },
): IntArray {
    if (queueSize <= 1 || failedIndex !in 0 until queueSize) return IntArray(0)
    val natural = IntArray(queueSize) { it }
    val validTraversal = traversalOrder.size == queueSize &&
        traversalOrder.all { it in 0 until queueSize } &&
        traversalOrder.toSet().size == queueSize
    val stableTraversal = if (validTraversal) traversalOrder else natural
    val failedPosition = stableTraversal.indexOf(failedIndex)
    if (failedPosition < 0) return IntArray(0)

    val result = ArrayList<Int>(queueSize - 1)
    for (offset in 1 until queueSize) {
        val position = failedPosition + offset
        if (position >= queueSize && !repeatAll) break
        result += stableTraversal[position % queueSize]
    }
    return result.toIntArray()
}

/** Physical queue identities tried by an error chain, stable across row removals/reordering. */
internal fun nextPlaybackErrorRecoveryIndex(
    mediaIds: List<String>,
    failedIndex: Int,
    repeatAll: Boolean,
    traversalOrder: IntArray,
    failedIds: Set<String>,
): Int? {
    if (failedIndex !in mediaIds.indices) return null
    return playbackErrorRecoveryTraversal(
        queueSize = mediaIds.size,
        failedIndex = failedIndex,
        repeatAll = repeatAll,
        traversalOrder = traversalOrder,
    ).firstOrNull { candidate -> mediaIds[candidate] !in failedIds }
}

/**
 * Source-order replacement needed when a backend leaves SMART shuffle.
 *
 * SMART owns and truncates the live queue, so merely disabling a platform shuffle flag cannot
 * recover source rows it removed. ON also requests a replacement: ordinary ON yields the same
 * physical source order, while a restored ON session may have materialized its saved traversal
 * physically and must therefore reconstruct canonical source order on the way back to OFF.
 */
internal fun sourceOrderForShuffleTransition(
    previousMode: ShuffleMode,
    requestedMode: ShuffleMode,
    source: List<TrackDescriptor>,
    current: TrackDescriptor?,
): PlaybackQueueOrder? =
    if (
        previousMode != ShuffleMode.OFF &&
        requestedMode == ShuffleMode.OFF &&
        current != null
    ) {
        sourceOrderKeepingCurrent(source, current)
    } else {
        null
    }

/** State captured immediately before SMART suspends in the local chooser. */
internal data class SmartAppendGuard(
    val queueGeneration: Long,
    val queueSize: Int,
    val tailId: TrackId,
)

/**
 * True only when a recommendation still belongs to the queue for which it was computed.
 *
 * The chooser is suspending work. A play request, shuffle change, manual edit, or eligibility
 * refresh can replace its inputs while it runs, so every structural assumption is checked again
 * before committing the result.
 */
internal fun canCommitSmartAppend(
    mode: ShuffleMode,
    queueGeneration: Long,
    queue: List<TrackDescriptor>,
    guard: SmartAppendGuard,
    chosenId: TrackId,
): Boolean =
    mode == ShuffleMode.SMART &&
        queueGeneration == guard.queueGeneration &&
        queue.size == guard.queueSize &&
        queue.lastOrNull()?.id == guard.tailId &&
        queue.none { it.id == chosenId }

/**
 * Bounded candidate order for a backend load attempt.
 *
 * Each row appears at most once, including when [wrap] is enabled, so an entirely unreadable queue
 * always terminates instead of repeatedly retrying the same dead files.
 */
internal fun playbackQueueTraversal(
    queueSize: Int,
    startIndex: Int,
    direction: Int,
    wrap: Boolean,
): IntArray {
    require(direction == -1 || direction == 1) { "direction must be -1 or 1" }
    if (queueSize <= 0) return IntArray(0)

    var index = startIndex
    if (index !in 0 until queueSize) {
        if (!wrap) return IntArray(0)
        index = if (direction > 0) 0 else queueSize - 1
    }

    val traversal = IntArray(queueSize)
    var count = 0
    while (count < queueSize && index in 0 until queueSize) {
        traversal[count++] = index
        index += direction
        if (wrap && index !in 0 until queueSize) {
            index = if (direction > 0) 0 else queueSize - 1
        }
    }
    return if (count == traversal.size) traversal else traversal.copyOf(count)
}

/**
 * Materialises a backend's linked traversal as one bounded permutation.
 *
 * Media3 exposes shuffle as `first` plus `next(index)`, while the app queue is an indexed list.
 * A corrupt, stale, or temporarily incomplete backend traversal must not leak duplicate/out-of-
 * range row indices into that list, so any malformed chain falls back to natural order.
 */
internal fun boundedQueueOrder(
    queueSize: Int,
    firstIndex: Int,
    nextIndex: (Int) -> Int,
): IntArray {
    if (queueSize <= 0) return IntArray(0)
    val natural = IntArray(queueSize) { it }
    val seen = BooleanArray(queueSize)
    val order = IntArray(queueSize)
    var index = firstIndex
    for (position in 0 until queueSize) {
        if (index !in 0 until queueSize || seen[index]) return natural
        seen[index] = true
        order[position] = index
        if (position < queueSize - 1) index = nextIndex(index)
    }
    return order
}

/** Queue rows plus their corresponding indices in the platform player's physical playlist. */
internal data class PlaybackQueueSnapshot<T : Any>(
    val rows: List<T>,
    val mediaItemIndices: List<Int>,
    val currentRowIndex: Int,
)

/**
 * Projects physical playlist entries into the order the transport actually traverses.
 *
 * Null entries are deliberately omitted together with their media-index mapping. This keeps
 * [currentRowIndex] aligned even if an old media session contains an id the current library can no
 * longer resolve.
 */
internal fun <T : Any> playbackQueueSnapshot(
    physicalRows: List<T?>,
    traversalOrder: IntArray,
    currentMediaItemIndex: Int,
): PlaybackQueueSnapshot<T> {
    val rows = ArrayList<T>(physicalRows.size)
    val mediaItemIndices = ArrayList<Int>(physicalRows.size)
    for (mediaItemIndex in traversalOrder) {
        val row = physicalRows.getOrNull(mediaItemIndex) ?: continue
        rows += row
        mediaItemIndices += mediaItemIndex
    }
    return PlaybackQueueSnapshot(
        rows = rows,
        mediaItemIndices = mediaItemIndices,
        currentRowIndex = mediaItemIndices.indexOf(currentMediaItemIndex),
    )
}

/**
 * Extends a valid shuffle permutation so an item appended physically is traversed next.
 *
 * ExoPlayer's default `cloneAndInsert` intentionally chooses a random traversal position, which
 * does not implement a Play Next command. The service appends at physical index `size`, then uses
 * this order to place that index immediately after the current item while preserving every other
 * relative traversal position.
 */
internal fun shuffleOrderAppendingNext(
    existingOrder: IntArray,
    currentMediaItemIndex: Int,
): IntArray {
    val size = existingOrder.size
    val valid = existingOrder.all { it in 0 until size } && existingOrder.toSet().size == size
    val stableOrder = if (valid) existingOrder else IntArray(size) { it }
    val currentPosition = stableOrder.indexOf(currentMediaItemIndex)
    val insertAt = if (currentPosition >= 0) currentPosition + 1 else stableOrder.size
    return IntArray(size + 1) { position ->
        when {
            position < insertAt -> stableOrder[position]
            position == insertAt -> size
            else -> stableOrder[position - 1]
        }
    }
}

/** Pure state behind restarting an AVAudioPlayerNode segment after natural completion. */
internal fun shouldRestartConsumedSegment(pausedFrame: Long, fileLength: Long): Boolean =
    fileLength >= 0L && pausedFrame >= fileLength

/** True only for an end/notification callback issued by the still-current playback item. */
internal fun isCurrentPlaybackItemGeneration(expected: Long, current: Long): Boolean =
    expected == current
