/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.library.SongSorting
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal val RailWidth = 26.dp
internal val RailGap = 10.dp
private val BubbleSize = 56.dp

/** A rail's worth of navigation over an already-ordered list of names. */
internal data class RailIndex(
    val buckets: List<String>,
    /** The list row each bucket jumps to; same size and order as [buckets]. */
    val startIndexes: List<Int>,
)

/**
 * Distinct letter buckets of a SORTED name list, each pointing at its first row. A bucket that
 * would re-appear later (digits bucket to "#" at the front, nameless entries to "#" at the end)
 * keeps only its first anchor — a rail must never jump backwards mid-alphabet.
 */
internal fun railIndexOf(names: List<String?>): RailIndex {
    val buckets = mutableListOf<String>()
    val starts = mutableListOf<Int>()
    names.forEachIndexed { index, name ->
        val bucket = SongSorting.bucket(name)
        if (bucket !in buckets) {
            buckets += bucket
            starts += index
        }
    }
    return RailIndex(buckets, starts)
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
    val scope = rememberCoroutineScope()
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
                onJump = { bucketIndex ->
                    scope.launch { listState.scrollToItem(rail.startIndexes[bucketIndex]) }
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
    onJump: (bucketIndex: Int) -> Unit,
) {
    var previewBucket by remember { mutableStateOf<String?>(null) }
    var touchY by remember { mutableStateOf(0f) }
    var railTopPx by remember { mutableStateOf(0f) }

    AlphabetRail(
        buckets = buckets,
        activeBucket = previewBucket,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(bottom = bottomPadding)
            .padding(vertical = 8.dp, horizontal = 2.dp)
            .onGloballyPositioned { railTopPx = it.positionInParent().y },
        onSelect = { bucketIndex, y ->
            touchY = y
            previewBucket = buckets[bucketIndex]
            onJump(bucketIndex)
        },
        onSelectionEnd = { previewBucket = null },
    )

    // Letter bubble tracks the finger and sits beside the rail.
    previewBucket?.let { bucket ->
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    IntOffset(
                        x = -(RailWidth + RailGap).roundToPx(),
                        y = (railTopPx + touchY - (BubbleSize / 2).toPx()).roundToInt(),
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
    onSelect: (index: Int, y: Float) -> Unit,
    onSelectionEnd: () -> Unit,
) {
    var railHeightPx by remember { mutableStateOf(0) }

    fun bucketIndexAt(y: Float): Int? {
        if (railHeightPx <= 0 || buckets.isEmpty()) return null
        return ((y / railHeightPx) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
    }

    Surface(
        modifier = modifier.width(RailWidth),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .onSizeChanged { railHeightPx = it.height }
                .pointerInput(buckets) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            bucketIndexAt(offset.y)?.let { onSelect(it, offset.y) }
                        },
                        onDrag = { change, _ ->
                            bucketIndexAt(change.position.y)?.let { onSelect(it, change.position.y) }
                        },
                        onDragEnd = onSelectionEnd,
                        onDragCancel = onSelectionEnd,
                    )
                }
                .pointerInput(buckets) {
                    detectTapGestures(
                        onPress = { offset ->
                            bucketIndexAt(offset.y)?.let { onSelect(it, offset.y) }
                            tryAwaitRelease()
                            onSelectionEnd()
                        },
                    )
                }
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            buckets.forEach { bucket ->
                val active = bucket == activeBucket
                Text(
                    text = bucket,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
