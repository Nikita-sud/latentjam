/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.info_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_title
import io.github.nikitasud.latentjam.app.generated.resources.info_year
import io.github.nikitasud.latentjam.app.generated.resources.sort_direction_ascending
import io.github.nikitasud.latentjam.app.generated.resources.sort_direction_descending
import io.github.nikitasud.latentjam.app.generated.resources.sort_recently_added
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import org.jetbrains.compose.resources.stringResource

/**
 * The sort control every sortable list shares: the active field in an outlined pill that opens a
 * menu of [options], and a direction arrow beside it. One component, so the Tracks tab, the Albums
 * tab and an artist's page all sort by the same gesture.
 */
@Composable
internal fun <S : Enum<S>> SortPill(
    options: List<S>,
    choice: SortChoice<S>,
    label: @Composable (S) -> String,
    /** The direction a newly chosen field starts in: the enum's own `defaultDirection`. */
    defaultDirection: (S) -> SongSortDirection,
    enabled: Boolean,
    onChoiceChange: (SortChoice<S>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { menuOpen = true }, enabled = enabled,
                contentPadding = PaddingValues(start = 14.dp, end = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(label(choice.sort), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp))
            }
            Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
            IconButton(
                onClick = { onChoiceChange(choice.copy(direction = choice.direction.toggled())) },
                enabled = enabled,
            ) { SortDirectionIcon(choice.direction) }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        menuOpen = false
                        onChoiceChange(choice.afterSelecting(option, defaultDirection(option)))
                    },
                    trailingIcon = if (option == choice.sort) { { SortDirectionIcon(choice.direction) } } else null,
                )
            }
        }
    }
}

@Composable
private fun SortDirectionIcon(direction: SongSortDirection) {
    Icon(
        imageVector = when (direction) {
            SongSortDirection.ASCENDING -> Icons.Rounded.ArrowUpward
            SongSortDirection.DESCENDING -> Icons.Rounded.ArrowDownward
        },
        contentDescription = stringResource(
            when (direction) {
                SongSortDirection.ASCENDING -> Res.string.sort_direction_ascending
                SongSortDirection.DESCENDING -> Res.string.sort_direction_descending
            },
        ),
        modifier = Modifier
            .padding(start = 4.dp)
            .size(16.dp),
    )
}

/**
 * A second tap on the active option reverses it. Moving to another option restores the useful
 * default for that field ([selectedDefault]: A–Z for text, newest first for dates).
 */
internal fun <S> directionAfterSortSelection(
    currentSort: S,
    currentDirection: SongSortDirection,
    selectedSort: S,
    selectedDefault: SongSortDirection,
): SongSortDirection = if (selectedSort == currentSort) {
    currentDirection.toggled()
} else {
    selectedDefault
}

/** The choice after tapping [selected] in the menu; see [directionAfterSortSelection]. */
internal fun <S : Enum<S>> SortChoice<S>.afterSelecting(
    selected: S,
    selectedDefault: SongSortDirection,
): SortChoice<S> = SortChoice(
    sort = selected,
    direction = directionAfterSortSelection(sort, direction, selected, selectedDefault),
)

@Composable
internal fun SongSort.label(): String = stringResource(
    when (this) {
        SongSort.TITLE -> Res.string.info_title
        SongSort.ARTIST -> Res.string.info_artist
        SongSort.RECENT -> Res.string.sort_recently_added
    },
)

@Composable
internal fun AlbumSort.label(): String = stringResource(
    when (this) {
        AlbumSort.TITLE -> Res.string.info_title
        AlbumSort.ARTIST -> Res.string.info_artist
        AlbumSort.YEAR -> Res.string.info_year
        AlbumSort.RECENT -> Res.string.sort_recently_added
    },
)
