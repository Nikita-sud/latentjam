/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_alphabet_index
import io.github.nikitasud.latentjam.library.SongSorting
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Reserved width and touch target. The visible pill stays narrow inside it. */
internal val RailWidth = 48.dp
internal val RailGap = 4.dp
private val RailPillWidth = 26.dp
private val BubbleSize = 56.dp
private val RailLabelHeight = 18.dp

internal data class RailFinalJump(
    val generation: Int,
    val bucketIndex: Int,
)

/** Pure gesture state: live preview is cheap, while list navigation is committed once on UP. */
internal class RailScrubCoordinator {
    var generation: Int = 0
        private set
    private var finalBucketIndex: Int? = null

    fun begin() {
        generation += 1
        finalBucketIndex = null
    }

    fun preview(bucketIndex: Int) {
        finalBucketIndex = bucketIndex
    }

    fun finish(): RailFinalJump? {
        val bucketIndex = finalBucketIndex ?: return null
        finalBucketIndex = null
        return RailFinalJump(generation, bucketIndex)
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
    return startIndexes.indexOfLast { it <= itemIndex }.coerceAtLeast(0)
}

/**
 * Hosts a browse list beside the shared rail. The content lambda receives the padding that
 * keeps rows clear of the rail and the list state the rail jumps through; the rail hides
 * itself when the names collapse into a single bucket.
 */
@Composable
internal fun GroupListWithRail(
    names: List<String?>,
    contentPadding: PaddingValues,
    content: @Composable BoxScope.(railPadding: PaddingValues, listState: LazyListState) -> Unit,
) {
    val rail = remember(names) { railIndexOf(names) }
    val showRail = rail.buckets.size > 1
    val listState = rememberLazyListState()
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
    val inset = PaddingValues(
        end = if (showRail) RailWidth + RailGap else 0.dp,
        bottom = contentPadding.calculateBottomPadding(),
    )
    Box(modifier = Modifier.fillMaxSize()) {
        content(inset, listState)
        if (showRail) {
            AlphabetRailOverlay(
                buckets = rail.buckets,
                bottomPadding = contentPadding.calculateBottomPadding(),
                activeBucket = activeBucket,
                onJump = { bucketIndex ->
                    listState.scrollToItem(rail.startIndexes[bucketIndex])
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
    bottomPadding: Dp,
    activeBucket: String? = null,
    onJump: suspend (bucketIndex: Int) -> Unit,
) {
    var previewIndex by remember(buckets) { mutableStateOf<Int?>(null) }
    var touchY by remember { mutableStateOf(0f) }
    var railTopPx by remember { mutableStateOf(0f) }
    var railHeightPx by remember { mutableStateOf(0) }
    var finalJumpJob by remember { mutableStateOf<Job?>(null) }
    val latestOnJump by rememberUpdatedState(onJump)
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val previewBucket = previewIndex?.let(buckets::get)
    val scrubCoordinator = remember(buckets) { RailScrubCoordinator() }

    fun beginSelection() {
        // Advance first: a cancelled previous job must observe itself as stale before it can jump.
        scrubCoordinator.begin()
        finalJumpJob?.cancel()
    }

    fun select(bucketIndex: Int, y: Float) {
        touchY = y
        if (previewIndex == bucketIndex) return
        previewIndex = bucketIndex
        scrubCoordinator.preview(bucketIndex)
    }

    fun endSelection() {
        // Replacing a distant LazyColumn viewport is expensive even without animation. Keep the
        // bubble fully live during the gesture and perform exactly one such replacement on UP.
        val completion = scrubCoordinator.finish()
        previewIndex = null
        if (completion != null) {
            finalJumpJob?.cancel()
            finalJumpJob = scope.launch {
                if (scrubCoordinator.isCurrent(completion.generation)) {
                    latestOnJump(completion.bucketIndex)
                }
            }
        }
    }

    AlphabetRail(
        buckets = buckets,
        activeBucket = previewBucket ?: activeBucket,
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
    )

    // Letter bubble tracks the finger and sits beside the rail.
    previewBucket?.let { bucket ->
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val bubblePx = BubbleSize.roundToPx()
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
                .size(BubbleSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = bucket,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

/** Letter rail inside a soft pill; drag or tap maps y-position to a bucket. */
@Composable
private fun AlphabetRail(
    buckets: List<String>,
    activeBucket: String?,
    modifier: Modifier = Modifier,
    onSelectionStart: () -> Unit,
    onSelect: (index: Int, y: Float) -> Unit,
    onSelectionEnd: () -> Unit,
) {
    var railHeightPx by remember { mutableStateOf(0) }
    val activeIndex = buckets.indexOf(activeBucket).coerceAtLeast(0)
    val railDescription = stringResource(Res.string.cd_alphabet_index)
    val density = LocalDensity.current
    val minimumLabelHeightPx = with(density) { RailLabelHeight.roundToPx() }
    val condensed = railHeightPx > 0 && buckets.size * minimumLabelHeightPx > railHeightPx

    Box(
        modifier = modifier
            .width(RailWidth)
            .onSizeChanged { railHeightPx = it.height }
            .semantics {
                contentDescription = railDescription
                stateDescription = buckets.getOrNull(activeIndex).orEmpty()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = activeIndex.toFloat(),
                    range = 0f..buckets.lastIndex.coerceAtLeast(0).toFloat(),
                    steps = (buckets.size - 2).coerceAtLeast(0),
                )
                setProgress { target ->
                    val index = target.roundToInt().coerceIn(0, buckets.lastIndex)
                    onSelectionStart()
                    onSelect(index, (index + 0.5f) / buckets.size * railHeightPx)
                    onSelectionEnd()
                    true
                }
            }
            // One recognizer owns down/move/up. Separate tap and drag detectors race each other
            // and the surrounding pager, especially at Samsung's back-gesture edge.
            .pointerInput(buckets) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onSelectionStart()
                    try {
                        railBucketIndexAt(down.position.y, railHeightPx, buckets.size)?.let {
                            onSelect(it, down.position.y)
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            railBucketIndexAt(
                                change.position.y,
                                railHeightPx,
                                buckets.size,
                            )?.let { onSelect(it, change.position.y) }
                            change.consume()
                            // The UP event carries the finger's true final position. Process it
                            // before ending the gesture or a fast scrub can stop several buckets
                            // behind where the user lifted.
                            if (!change.pressed) break
                        }
                    } finally {
                        onSelectionEnd()
                    }
                }
            },
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(RailPillWidth)
                // The outer adjustable node is the single TalkBack target; visual letters are
                // decorative and must not become dozens of separate accessibility stops.
                .clearAndSetSemantics { },
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ) {
            if (condensed) {
                // Mixed-script libraries can have far more initials than readable text slots.
                // Keep Samsung Music's letter-index pattern, sample only as many labels as fit,
                // and always show the exact current initial at its proportional position. The
                // finger bubble exposes every intermediate initial while scrubbing.
                val activeFraction = if (buckets.lastIndex > 0) {
                    activeIndex.toFloat() / buckets.lastIndex
                } else {
                    0f
                }
                val verticalPaddingPx = with(density) { 12.dp.roundToPx() }
                val availablePx = (
                    railHeightPx - verticalPaddingPx - minimumLabelHeightPx
                    ).coerceAtLeast(0)
                val guideCount = (availablePx / minimumLabelHeightPx).coerceIn(2, 32)
                val guideIndexes = remember(buckets, guideCount) {
                    (0 until guideCount)
                        .map { guideIndex ->
                            (
                                guideIndex.toFloat() /
                                    (guideCount - 1) *
                                    buckets.lastIndex
                                ).roundToInt()
                        }
                        .distinct()
                }
                Box(modifier = Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                    guideIndexes.forEach { guideIndex ->
                        val guideFraction = guideIndex.toFloat() / buckets.lastIndex
                        val clearOfActive = abs(guideFraction - activeFraction) * availablePx >=
                            minimumLabelHeightPx
                        if (guideIndex != activeIndex && clearOfActive) {
                            Text(
                                text = buckets[guideIndex],
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(RailLabelHeight)
                                    .offset {
                                        IntOffset(
                                            x = 0,
                                            y = (guideFraction * availablePx).roundToInt(),
                                        )
                                    },
                            )
                        }
                    }
                    Text(
                        text = buckets.getOrNull(activeIndex).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(RailLabelHeight)
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (activeFraction * availablePx).roundToInt(),
                                )
                            },
                    )
                }
            } else {
                Column(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    buckets.forEach { bucket ->
                        val active = bucket == activeBucket
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = bucket,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
