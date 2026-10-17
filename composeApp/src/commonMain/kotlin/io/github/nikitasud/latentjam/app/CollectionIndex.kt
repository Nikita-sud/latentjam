/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

internal val AlbumGridMinCellWidth = 136.dp

/** Decide from the compact layout, never from the extra height/gutter introduced by the index. */
internal fun collectionNeedsAlphabetIndex(
    itemCount: Int,
    bucketCount: Int,
    columns: Int,
    itemHeight: Float,
    viewportHeight: Float,
): Boolean = itemCount >= 12 && bucketCount > 1 && columns > 0 &&
    itemHeight.isFinite() && itemHeight > 0f &&
    viewportHeight.isFinite() && viewportHeight > 0f &&
    ceil(itemCount.toDouble() / columns) * itemHeight >= viewportHeight * 2f

/** Window size and scaled typography determine the threshold; scrolling cannot toggle it. */
@Composable
internal fun AdaptiveCollectionIndex(
    itemCount: Int,
    bucketCount: Int,
    contentPadding: PaddingValues,
    grid: Boolean = false,
    /** A grid's pinched column count; 0 keeps the adaptive count. */
    fixedColumns: Int = 0,
    content: @Composable (indexed: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val typography = MaterialTheme.typography
    val textHeight = with(density) {
        (typography.bodyLarge.lineHeight.toDp() + typography.bodySmall.lineHeight.toDp()).value
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = (maxWidth - contentPadding.calculateStartPadding(direction) -
            contentPadding.calculateEndPadding(direction)).value.coerceAtLeast(1f)
        val viewport = (maxHeight - contentPadding.calculateTopPadding() -
            contentPadding.calculateBottomPadding()).value
        val columns = when {
            !grid -> 1
            fixedColumns > 0 -> fixedColumns
            else -> (width / AlbumGridMinCellWidth.value).toInt().coerceAtLeast(1)
        }
        // AlbumCard: square art + 16dp outer padding + 8dp text gap + two text lines.
        // GroupRow: 56dp art or two text lines, whichever is taller, plus 16dp padding.
        val itemHeight = if (grid) width / columns + 8f + textHeight else 16f + max(56f, textHeight)
        content(collectionNeedsAlphabetIndex(itemCount, bucketCount, columns, itemHeight, viewport))
    }
}

@Composable
internal fun SectionedGroupListWithRail(
    names: List<String?>,
    artworkKeys: List<ArtworkLoadKey?>,
    contentPadding: PaddingValues,
    onScrubbingChange: (Boolean) -> Unit,
    content: @Composable BoxScope.(
        railPadding: PaddingValues,
        listState: LazyListState,
        artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)?,
        headers: Map<Int, String>,
    ) -> Unit,
) {
    val index = remember(names) { railIndexOf(names) }
    val headers = remember(index) { index.startIndexes.zip(index.buckets).toMap() }
    AdaptiveCollectionIndex(names.size, index.buckets.size, contentPadding) { indexed ->
        GroupListWithRail(
            names = names,
            artworkKeys = artworkKeys,
            contentPadding = contentPadding,
            indexEnabled = indexed,
            onScrubbingChange = onScrubbingChange,
        ) { padding, state, reporter ->
            content(padding, state, reporter, if (indexed) headers else emptyMap())
        }
    }
}

/** A header is part of its first group row, keeping rail and artwork indexes aligned. */
@Composable
internal fun CollectionSectionHeader(bucket: String, horizontalPadding: Dp = 20.dp) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)
            .padding(horizontal = horizontalPadding).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(bucket, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppDivider(Modifier.weight(1f))
    }
}

/**
 * Two fingers resize the album grid a column at a time (2 to 4); one finger still scrolls, and the
 * pager still swipes. The pinch is claimed the way the Map claims it, before the grid's own scroll
 * can take the second finger.
 */
@Composable
internal fun AlbumGridPinch(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val adaptive = ((maxWidth - 24.dp) / AlbumGridMinCellWidth).toInt().coerceAtLeast(1)
        val shown by rememberUpdatedState(if (columns > 0) columns else adaptive)
        val change by rememberUpdatedState(onColumnsChange)
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var zoom = 1f
                detectMapTransformGestures { _, _, step ->
                    zoom *= step
                    val next = when {
                        zoom > PINCH_STEP -> shown - 1
                        zoom < 1f / PINCH_STEP -> shown + 1
                        else -> return@detectMapTransformGestures
                    }.coerceIn(ALBUM_COLUMN_CHOICES.first(), ALBUM_COLUMN_CHOICES.last())
                    zoom = 1f
                    if (next != shown) change(next)
                }
            },
        ) { content() }
    }
}

/** How far fingers must spread or close, as a scale, before the grid changes by one column. */
private const val PINCH_STEP = 1.3f
