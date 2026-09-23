/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

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
import androidx.compose.material3.HorizontalDivider
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
        val columns = if (grid) (width / AlbumGridMinCellWidth.value).toInt().coerceAtLeast(1) else 1
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
        HorizontalDivider(modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    }
}
