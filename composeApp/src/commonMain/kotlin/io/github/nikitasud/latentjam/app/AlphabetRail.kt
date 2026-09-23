/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_alphabet_index
import io.github.nikitasud.latentjam.library.SongSorting
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Compact index column; normal list scrolling remains the larger gesture target. */
internal val RailWidth = 30.dp
internal val RailGap = 4.dp
private val RailPillWidth = 26.dp
private val BubbleSize = 56.dp
private val RailLabelHeight = 18.dp

internal data class RailFinalJump(
    val generation: Int,
    val bucketIndex: Int,
)

/** One gesture's letter ticks. Rapidly skipped letters never queue up delayed vibrations. */
internal class RailHapticState {
    private var previousBucket: Int? = null
    private var lastTickTimeMs: Long? = null

    fun select(bucketIndex: Int, timeMs: Long): Boolean {
        if (bucketIndex == previousBucket) return false
        previousBucket = bucketIndex
        val lastTick = lastTickTimeMs
        if (lastTick != null && timeMs - lastTick < 70L) return false
        lastTickTimeMs = timeMs
        return true
    }
}

/** Pure gesture state: preview is cheap, while real list navigation is committed once on UP. */
internal class RailScrubCoordinator {
    var generation: Int = 0
        private set
    private var finalBucketIndex: Int? = null
    private var previewActive: Boolean = false

    fun begin() {
        generation += 1
        finalBucketIndex = null
        previewActive = true
    }

    /** Replaces any unrendered preview while always retaining the exact final finger position. */
    fun preview(bucketIndex: Int) {
        if (!previewActive) return
        finalBucketIndex = bucketIndex
    }

    fun finish(): RailFinalJump? {
        if (!previewActive) return null
        previewActive = false
        val bucketIndex = finalBucketIndex ?: return null
        finalBucketIndex = null
        return RailFinalJump(generation, bucketIndex)
    }

    /** Invalidates pointer/final work when the rail or its catalog leaves composition. */
    fun cancel() {
        generation += 1
        finalBucketIndex = null
        previewActive = false
    }

    fun isCurrent(completedGeneration: Int): Boolean = completedGeneration == generation

}

/** A rail's worth of navigation over an already-ordered list of names. */
internal data class RailIndex(
    val buckets: List<String>,
    /** The list row each bucket jumps to; same size and order as [buckets]. */
    val startIndexes: List<Int>,
)

/**
 * Distinct letter buckets of a SORTED name list, each pointing at its first row. A bucket that
 * would re-appear later keeps only its first anchor — a rail must never jump backwards
 * mid-alphabet.
 */
internal fun railIndexOf(names: List<String?>): RailIndex {
    val buckets = mutableListOf<String>()
    val starts = mutableListOf<Int>()
    val seen = HashSet<String>()
    names.forEachIndexed { index, name ->
        val bucket = SongSorting.bucket(name)
        if (seen.add(bucket)) {
            buckets += bucket
            starts += index
        }
    }
    return RailIndex(buckets, starts)
}

/** Maps a local rail position to one stable bucket, clamping drags beyond either end. */
internal fun railBucketIndexAt(y: Float, height: Int, bucketCount: Int): Int? {
    if (height <= 0 || bucketCount <= 0) return null
    return ((y / height) * bucketCount).toInt().coerceIn(0, bucketCount - 1)
}

/** Bucket whose first item is at or before the viewport, with the final bucket pinned at list end. */
internal fun currentRailBucketIndex(
    itemIndex: Int,
    startIndexes: List<Int>,
    atEnd: Boolean = false,
): Int? {
    if (startIndexes.isEmpty()) return null
    if (atEnd) return startIndexes.lastIndex
    // Anchors are sorted by emitted row. An upper-bound search also handles equal anchors and
    // avoids walking thousands of mixed-script buckets each time the viewport changes rows.
    var low = 0
    var high = startIndexes.size
    while (low < high) {
        val middle = low + (high - low) / 2
        if (startIndexes[middle] <= itemIndex) low = middle + 1 else high = middle
    }
    return (low - 1).coerceAtLeast(0)
}

/** Convenience adapter for a one-item-per-name alphabetic group list. */
@Composable
internal fun GroupListWithRail(
    names: List<String?>,
    /** Optional artwork identities, one per row, used only for the bounded release handoff. */
    artworkKeys: List<ArtworkLoadKey?> = emptyList(),
    contentPadding: PaddingValues,
    indexEnabled: Boolean = true,
    onScrubbingChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(
        railPadding: PaddingValues,
        listState: LazyListState,
        artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)?,
    ) -> Unit,
) {
    require(artworkKeys.isEmpty() || artworkKeys.size == names.size) {
        "artworkKeys must be empty or aligned one-to-one with names"
    }
    val rail = remember(names) { railIndexOf(names) }
    ListWithRail(
        rail = rail,
        catalogKey = Triple(names, artworkKeys, indexEnabled),
        artworkKeys = artworkKeys,
        contentPadding = contentPadding,
        indexEnabled = indexEnabled,
        onScrubbingChange = onScrubbingChange,
        content = { railPadding, listState, artworkReporter, _ ->
            content(railPadding, listState, artworkReporter)
        },
    )
}

/**
 * Hosts an explicitly indexed list beside the shared live-scrub rail. The same content is
 * composed once for the real viewport and only while scrubbing for its exact visual mirror.
 */
@Composable
internal fun ListWithRail(
    rail: RailIndex,
    catalogKey: Any,
    artworkKeys: List<ArtworkLoadKey?>,
    contentPadding: PaddingValues,
    indexEnabled: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    onScrubbingChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(
        railPadding: PaddingValues,
        listState: LazyListState,
        artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)?,
        isPreview: Boolean,
    ) -> Unit,
) {
    require(rail.buckets.size == rail.startIndexes.size) {
        "rail buckets and anchors must stay aligned"
    }
    val railCandidate = indexEnabled && rail.buckets.size > 1
    val showRail by remember(railCandidate, listState) {
        derivedStateOf {
            railCandidate &&
                listState.layoutInfo.totalItemsCount > 0 &&
                (listState.canScrollForward || listState.canScrollBackward)
        }
    }
    val previewListState = rememberLazyListState()
    // Identity matters: labels/anchors may stay equal while backing rows or covers change.
    val railCatalogKey = remember(catalogKey, rail, artworkKeys) { Any() }
    val artworkLoadGate = remember(railCatalogKey) { ArtworkLoadGate() }
    var railScrubbing by remember(railCatalogKey) { mutableStateOf(false) }
    val reportScrubbing by rememberUpdatedState(onScrubbingChange)
    DisposableEffect(railCatalogKey) {
        onDispose { reportScrubbing(false) }
    }
    var previewBucketIndex by remember(railCatalogKey) { mutableStateOf<Int?>(null) }
    val previewRequested = railScrubbing && previewBucketIndex != null
    val hasArtwork = remember(artworkKeys) { artworkKeys.any { it != null } }
    val activeBucket by remember(rail, listState) {
        derivedStateOf {
            currentRailBucketIndex(
                itemIndex = listState.firstVisibleItemIndex,
                startIndexes = rail.startIndexes,
                atEnd = !listState.canScrollForward && listState.firstVisibleItemIndex > 0,
            )
                ?.let(rail.buckets::get)
        }
    }
    val layoutDirection = LocalLayoutDirection.current
    val inset = PaddingValues(
        start = contentPadding.calculateStartPadding(layoutDirection),
        top = contentPadding.calculateTopPadding(),
        // The gutter belongs to a rail that is actually on screen. Reserving it for every list
        // that merely qualified left short album and artist pages with an empty strip down
        // the right-hand side and rows that stopped short of their own header's buttons.
        end = contentPadding.calculateEndPadding(layoutDirection) +
            if (showRail) RailWidth + RailGap else 0.dp,
        bottom = contentPadding.calculateBottomPadding(),
    )
    val artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)? =
        if (railScrubbing && hasArtwork) {
            { key, state -> artworkLoadGate.record(key, state) }
        } else {
            null
        }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (previewRequested) 0f else 1f }
                .fadingListTop { listState.canScrollBackward }
                .inactiveForMotion(previewRequested),
        ) {
            content(inset, listState, artworkReporter, false)
        }

        // Swap the two viewports atomically: at most one draws, and neither paints over the
        // page's artwork glow. The real list remains measured for the bounded artwork gate.
        if (previewRequested) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .fadingListTop { previewListState.canScrollBackward }
                    .inactiveForMotion(true),
            ) {
                // This is the exact same list/layout as the real surface. Only its state differs,
                // so bottom clamping and large-font row heights cannot shift during handoff.
                content(inset, previewListState, null, true)
            }
        }

        if (showRail) {
            AlphabetRailOverlay(
                buckets = rail.buckets,
                catalogKey = railCatalogKey,
                bottomPadding = contentPadding.calculateBottomPadding(),
                activeBucket = activeBucket,
                onPreviewJump = { bucketIndex ->
                    rail.startIndexes.getOrNull(bucketIndex)?.let { targetIndex ->
                        previewBucketIndex = bucketIndex
                        // Requests before the next frame naturally coalesce to the latest finger.
                        previewListState.requestScrollToItem(targetIndex)
                    }
                },
                onJump = onJump@ { bucketIndex, _ ->
                    val targetIndex = rail.startIndexes.getOrNull(bucketIndex)
                        ?: return@onJump
                    val loadCycle = artworkLoadGate.begin()
                    try {
                        withFrameNanos { }
                        listState.scrollToItem(targetIndex)
                        withFrameNanos { }
                        val expectedArtwork = listState.layoutInfo.visibleItemsInfo
                            .mapNotNull { itemInfo -> artworkKeys.getOrNull(itemInfo.index) }
                            .toSet()
                        artworkLoadGate.awaitFinished(
                            cycle = loadCycle,
                            expected = expectedArtwork,
                            minimumWaitMillis = Motion.QUICK_MS.toLong(),
                            maximumWaitMillis = Motion.APPEAR_MS.toLong(),
                        )
                        withFrameNanos { }
                    } finally {
                        artworkLoadGate.end(loadCycle)
                    }
                },
                onScrubbingChange = {
                    railScrubbing = it
                    reportScrubbing(it)
                },
            )
        }
    }
}

/**
 * Grid counterpart of [GroupListWithRail]. Callers provide emitted-item anchors because a
 * two-column alphabetic grid needs full-span section headers to keep every letter on a new row.
 */
@Composable
internal fun GridListWithRail(
    rail: RailIndex,
    catalogKey: Any,
    artworkKeys: List<ArtworkLoadKey?>,
    contentPadding: PaddingValues,
    onScrubbingChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(
        railPadding: PaddingValues,
        gridState: LazyGridState,
        artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)?,
        isPreview: Boolean,
    ) -> Unit,
) {
    require(rail.buckets.size == rail.startIndexes.size) {
        "rail buckets and anchors must stay aligned"
    }
    val gridState = rememberLazyGridState()
    val railCandidate = rail.buckets.size > 1
    val showRail by remember(railCandidate, gridState) {
        derivedStateOf {
            railCandidate &&
                gridState.layoutInfo.totalItemsCount > 0 &&
                (gridState.canScrollForward || gridState.canScrollBackward)
        }
    }
    val previewGridState = rememberLazyGridState()
    val railCatalogKey = remember(catalogKey, rail, artworkKeys) { Any() }
    val artworkLoadGate = remember(railCatalogKey) { ArtworkLoadGate() }
    var railScrubbing by remember(railCatalogKey) { mutableStateOf(false) }
    val reportScrubbing by rememberUpdatedState(onScrubbingChange)
    DisposableEffect(railCatalogKey) {
        onDispose { reportScrubbing(false) }
    }
    var previewBucketIndex by remember(railCatalogKey) { mutableStateOf<Int?>(null) }
    val previewRequested = railScrubbing && previewBucketIndex != null
    val hasArtwork = remember(artworkKeys) { artworkKeys.any { it != null } }
    val activeBucket by remember(rail, gridState) {
        derivedStateOf {
            currentRailBucketIndex(
                itemIndex = gridState.firstVisibleItemIndex,
                startIndexes = rail.startIndexes,
                atEnd = !gridState.canScrollForward && gridState.firstVisibleItemIndex > 0,
            )
                ?.let(rail.buckets::get)
        }
    }
    val layoutDirection = LocalLayoutDirection.current
    val inset = PaddingValues(
        start = contentPadding.calculateStartPadding(layoutDirection),
        top = contentPadding.calculateTopPadding(),
        // The gutter belongs to a rail that is actually on screen. Reserving it for every list
        // that merely qualified left short album and artist pages with an empty strip down
        // the right-hand side and rows that stopped short of their own header's buttons.
        end = contentPadding.calculateEndPadding(layoutDirection) +
            if (showRail) RailWidth + RailGap else 0.dp,
        bottom = contentPadding.calculateBottomPadding(),
    )
    val artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)? =
        if (railScrubbing && hasArtwork) {
            { key, state -> artworkLoadGate.record(key, state) }
        } else {
            null
        }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (previewRequested) 0f else 1f }
                .fadingListTop { gridState.canScrollBackward }
                .inactiveForMotion(previewRequested),
        ) {
            content(inset, gridState, artworkReporter, false)
        }

        if (previewRequested) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .fadingListTop { previewGridState.canScrollBackward }
                    .inactiveForMotion(true),
            ) {
                content(inset, previewGridState, null, true)
            }
        }

        if (showRail) {
            AlphabetRailOverlay(
                buckets = rail.buckets,
                catalogKey = railCatalogKey,
                bottomPadding = contentPadding.calculateBottomPadding(),
                activeBucket = activeBucket,
                onPreviewJump = { bucketIndex ->
                    rail.startIndexes.getOrNull(bucketIndex)?.let { targetIndex ->
                        previewBucketIndex = bucketIndex
                        previewGridState.requestScrollToItem(targetIndex)
                    }
                },
                onJump = onJump@ { bucketIndex, _ ->
                    val targetIndex = rail.startIndexes.getOrNull(bucketIndex)
                        ?: return@onJump
                    val loadCycle = artworkLoadGate.begin()
                    try {
                        withFrameNanos { }
                        gridState.scrollToItem(targetIndex)
                        withFrameNanos { }
                        val expectedArtwork = gridState.layoutInfo.visibleItemsInfo
                            .mapNotNull { itemInfo -> artworkKeys.getOrNull(itemInfo.index) }
                            .toSet()
                        artworkLoadGate.awaitFinished(
                            cycle = loadCycle,
                            expected = expectedArtwork,
                            minimumWaitMillis = Motion.QUICK_MS.toLong(),
                            maximumWaitMillis = Motion.APPEAR_MS.toLong(),
                        )
                        withFrameNanos { }
                    } finally {
                        artworkLoadGate.end(loadCycle)
                    }
                },
                onScrubbingChange = {
                    railScrubbing = it
                    reportScrubbing(it)
                },
            )
        }
    }
}

/**
 * The A–Z rail plus its finger bubble, hosted over any list: position-based navigation instead
 * of endless flinging. The bubble sits beside the rail rather than over the list, so the rows
 * being scrubbed past stay readable. One shared component keeps the gesture identical on every
 * surface it appears.
 */
@Composable
internal fun BoxScope.AlphabetRailOverlay(
    buckets: List<String>,
    catalogKey: Any = buckets,
    bottomPadding: Dp,
    activeBucket: String? = null,
    onPreviewJump: (bucketIndex: Int) -> Unit = {},
    onJump: suspend (bucketIndex: Int, animated: Boolean) -> Unit,
    onScrubbingChange: (Boolean) -> Unit = {},
) {
    val pageSlot = LocalPageRailSlot.current
    if (pageSlot != null) {
        PublishPageRail(
            pageSlot, buckets, catalogKey, bottomPadding, activeBucket,
            onPreviewJump, onJump, onScrubbingChange,
        )
    } else {
        StandaloneAlphabetRailOverlay(
            buckets, catalogKey, bottomPadding, activeBucket,
            onPreviewJump, onJump, onScrubbingChange,
        )
    }
}

@Composable
internal fun BoxScope.StandaloneAlphabetRailOverlay(
    buckets: List<String>,
    catalogKey: Any = buckets,
    bottomPadding: Dp,
    activeBucket: String? = null,
    onPreviewJump: (bucketIndex: Int) -> Unit = {},
    onJump: suspend (bucketIndex: Int, animated: Boolean) -> Unit,
    onScrubbingChange: (Boolean) -> Unit = {},
    interactive: Boolean = true,
) {
    var previewIndex by remember(catalogKey) { mutableStateOf<Int?>(null) }
    var settlingIndex by remember(catalogKey) { mutableStateOf<Int?>(null) }
    var touchY by remember { mutableStateOf(0f) }
    var railTopPx by remember { mutableStateOf(0f) }
    var railHeightPx by remember { mutableStateOf(0) }
    var finalJumpJob by remember { mutableStateOf<Job?>(null) }
    val latestOnPreviewJump by rememberUpdatedState(onPreviewJump)
    val latestOnJump by rememberUpdatedState(onJump)
    val latestOnScrubbingChange by rememberUpdatedState(onScrubbingChange)
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val bubbleDiameter = maxOf(
        BubbleSize,
        with(density) { MaterialTheme.typography.headlineSmall.fontSize.toDp() * 1.4f },
    )
    val previewBucket = previewIndex?.let { buckets.getOrNull(it) }
    val settlingBucket = settlingIndex?.let { buckets.getOrNull(it) }
    val scrubCoordinator = remember(catalogKey) { RailScrubCoordinator() }
    val reduceMotion = rememberReduceMotion()

    DisposableEffect(catalogKey) {
        val finishScrubbing = onScrubbingChange
        onDispose {
            // Invalidate before cancelling: the cancelled job's finally block must not clear a
            // newer gesture that may begin after a catalog/sort replacement.
            scrubCoordinator.cancel()
            finalJumpJob?.cancel()
            finishScrubbing(false)
        }
    }

    fun beginSelection() {
        // Advance first: a cancelled previous job must observe itself as stale before it can jump.
        scrubCoordinator.begin()
        finalJumpJob?.cancel()
        settlingIndex = null
        latestOnScrubbingChange(true)
    }

    fun select(bucketIndex: Int, y: Float) {
        touchY = y
        if (previewIndex == bucketIndex) return
        previewIndex = bucketIndex
        scrubCoordinator.preview(bucketIndex)
        // This callback updates only a lightweight visual mirror. Keeping it synchronous makes
        // even a sub-frame top-to-bottom gesture show its exact target while the interactive
        // LazyColumn remains frozen.
        latestOnPreviewJump(bucketIndex)
    }

    fun endSelection() {
        // The host keeps its lightweight preview visible while exactly one underlying list jump
        // and bounded artwork warm-up complete. Its callback returns only when it is safe to
        // reveal the real list.
        val completion = scrubCoordinator.finish()
        previewIndex = null
        if (completion != null) {
            settlingIndex = completion.bucketIndex
            finalJumpJob?.cancel()
            finalJumpJob = scope.launch {
                try {
                    if (scrubCoordinator.isCurrent(completion.generation)) {
                        latestOnJump(completion.bucketIndex, !reduceMotion)
                    }
                } finally {
                    if (scrubCoordinator.isCurrent(completion.generation)) {
                        settlingIndex = null
                        latestOnScrubbingChange(false)
                    }
                }
            }
        } else {
            settlingIndex = null
            latestOnScrubbingChange(false)
        }
    }

    fun cancelSelection() {
        // Losing the pointer (navigation, catalog replacement, interruption) is not a drop.
        // Invalidate before cancelling so an older settling job cannot reveal a newer preview.
        scrubCoordinator.cancel()
        finalJumpJob?.cancel()
        previewIndex = null
        settlingIndex = null
        latestOnScrubbingChange(false)
    }

    AlphabetRail(
        buckets = buckets,
        interactive = interactive,
        gestureKey = catalogKey,
        activeBucket = previewBucket ?: settlingBucket ?: activeBucket,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(bottom = bottomPadding)
            .padding(vertical = 8.dp)
            .onGloballyPositioned {
                railTopPx = it.positionInParent().y
                railHeightPx = it.size.height
            },
        onSelectionStart = ::beginSelection,
        onSelect = ::select,
        onSelectionEnd = ::endSelection,
        onSelectionCancel = ::cancelSelection,
    )

    // Finger position remains immediate; all animation lives inside this small overlay.
    key(catalogKey) {
        RailLetterBubble(
            bucket = previewBucket,
            bucketIndex = previewIndex,
            reduceMotion = reduceMotion,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val bubblePx = bubbleDiameter.roundToPx()
                    val localTop = (touchY - bubblePx / 2f)
                        .roundToInt()
                        .coerceIn(0, (railHeightPx - bubblePx).coerceAtLeast(0))
                    IntOffset(
                        x = if (layoutDirection == LayoutDirection.Ltr) {
                            -(RailWidth + RailGap).roundToPx()
                        } else {
                            (RailWidth + RailGap).roundToPx()
                        },
                        y = railTopPx.roundToInt() + localTop,
                    )
                }
                .size(bubbleDiameter),
        )
    }
}

/** A single live letter: fast scrubs never accumulate a stack of outgoing animated labels. */
@Composable
private fun RailLetterBubble(
    bucket: String?,
    bucketIndex: Int?,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    var retainedBucket by remember { mutableStateOf<String?>(null) }
    val shownBucket = bucket ?: retainedBucket
    SideEffect { if (bucket != null) retainedBucket = bucket }

    val letterProgress = remember { Animatable(1f) }
    var previousIndex by remember { mutableStateOf<Int?>(null) }
    var letterDirection by remember { mutableStateOf(0f) }
    LaunchedEffect(bucketIndex, reduceMotion) {
        val previous = previousIndex
        previousIndex = bucketIndex
        if (bucketIndex != null && previous != null && bucketIndex != previous && !reduceMotion) {
            letterDirection = if (bucketIndex > previous) 1f else -1f
            letterProgress.snapTo(0f)
            letterProgress.animateTo(1f, tween(Motion.QUICK_MS, easing = LinearOutSlowInEasing))
        } else {
            letterProgress.snapTo(1f)
        }
    }

    AnimatedVisibility(
        visible = bucket != null,
        modifier = modifier.clearAndSetSemantics { },
        enter = if (reduceMotion) {
            fadeIn(tween(Motion.REDUCED_MS))
        } else {
            fadeIn(tween(Motion.QUICK_MS)) +
                scaleIn(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f),
                    initialScale = 0.86f,
                )
        },
        exit = if (reduceMotion) {
            fadeOut(tween(Motion.REDUCED_MS))
        } else {
            fadeOut(tween(Motion.REPLACE_MS)) +
                scaleOut(tween(Motion.REPLACE_MS), targetScale = 0.94f)
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = shownBucket.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.graphicsLayer {
                        // Read animation state only while drawing this label; lazy lists and
                        // the rail's hit area never remeasure or recompose for these frames.
                        val remaining = if (reduceMotion) 0f else 1f - letterProgress.value
                        translationY = letterDirection * 5.dp.toPx() * remaining
                        alpha = 1f - 0.28f * remaining
                    },
                )
            }
        }
    }
}

/** Letter rail inside a soft pill; drag or tap maps y-position to a bucket. */
@Composable
private fun AlphabetRail(
    buckets: List<String>,
    interactive: Boolean,
    gestureKey: Any,
    activeBucket: String?,
    modifier: Modifier = Modifier,
    onSelectionStart: () -> Unit,
    onSelect: (index: Int, y: Float) -> Unit,
    onSelectionEnd: () -> Unit,
    onSelectionCancel: () -> Unit,
) {
    var railHeightPx by remember { mutableStateOf(0) }
    val activeIndex = buckets.indexOf(activeBucket).coerceAtLeast(0)
    val railDescription = stringResource(Res.string.cd_alphabet_index)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val latestOnSelectionStart by rememberUpdatedState(onSelectionStart)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnSelectionEnd by rememberUpdatedState(onSelectionEnd)
    val latestOnSelectionCancel by rememberUpdatedState(onSelectionCancel)
    val labelHeight = maxOf(
        RailLabelHeight,
        with(density) { MaterialTheme.typography.labelSmall.fontSize.toDp() * 1.45f },
    )
    val railPillWidth = maxOf(
        RailPillWidth,
        with(density) { MaterialTheme.typography.labelSmall.fontSize.toDp() * 1.35f },
    ).coerceAtMost(RailWidth)

    Box(
        modifier = modifier
            .width(RailWidth)
            .onSizeChanged { railHeightPx = it.height }
            .then(if (!interactive) Modifier.clearAndSetSemantics { } else Modifier.semantics {
                contentDescription = railDescription
                stateDescription = buckets.getOrNull(activeIndex).orEmpty()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = activeIndex.toFloat(),
                    range = 0f..buckets.lastIndex.coerceAtLeast(0).toFloat(),
                    steps = (buckets.size - 2).coerceAtLeast(0),
                )
                setProgress { target ->
                    val index = target.roundToInt().coerceIn(0, buckets.lastIndex)
                    if (index != activeIndex) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    onSelectionStart()
                    onSelect(index, (index + 0.5f) / buckets.size * railHeightPx)
                    onSelectionEnd()
                    true
                }
            }
            // One recognizer owns down/move/up. Separate tap and drag detectors race each other
            // and the surrounding pager, especially at Samsung's back-gesture edge.
            .pointerInput(buckets, gestureKey, haptics) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val feedback = RailHapticState()
                    var released = false
                    down.consume()
                    latestOnSelectionStart()
                    try {
                        railBucketIndexAt(down.position.y, railHeightPx, buckets.size)?.let {
                            if (feedback.select(it, down.uptimeMillis)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            latestOnSelect(it, down.position.y)
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            railBucketIndexAt(
                                change.position.y,
                                railHeightPx,
                                buckets.size,
                            )?.let {
                                if (feedback.select(it, change.uptimeMillis)) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                latestOnSelect(it, change.position.y)
                            }
                            change.consume()
                            // The UP event carries the finger's true final position. Process it
                            // before ending the gesture or a fast scrub can stop several buckets
                            // behind where the user lifted.
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }
                    } finally {
                        if (released) latestOnSelectionEnd() else latestOnSelectionCancel()
                    }
                }
            }),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(railPillWidth)
                // The outer adjustable node is the single TalkBack target; visual letters are
                // decorative and must not become dozens of separate accessibility stops.
                .clearAndSetSemantics { },
            shape = RoundedCornerShape(percent = 50),
            // Pages slide behind this stationary rail. Pre-composite the tint so moving
            // artwork cannot show through its letters during the page handoff.
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                .compositeOver(MaterialTheme.colorScheme.background),
        ) {
            MorphingRailLetters(
                buckets = buckets,
                activeBucket = activeBucket,
                railHeightPx = railHeightPx,
                labelHeight = labelHeight,
            )
        }
    }
}
