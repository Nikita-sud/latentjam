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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.info_album
import io.github.nikitasud.latentjam.app.generated.resources.info_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_duration
import io.github.nikitasud.latentjam.app.generated.resources.info_title
import io.github.nikitasud.latentjam.app.generated.resources.info_year
import io.github.nikitasud.latentjam.app.generated.resources.sort_direction_ascending
import io.github.nikitasud.latentjam.app.generated.resources.sort_direction_descending
import io.github.nikitasud.latentjam.app.generated.resources.sort_custom_order
import io.github.nikitasud.latentjam.app.generated.resources.sort_name
import io.github.nikitasud.latentjam.app.generated.resources.sort_recently_added
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The sort control every sortable list shares: the active field in an outlined pill that opens a
 * menu of [options], and a direction arrow beside it. One component, so the Tracks tab, the Albums
 * tab and an artist's page all sort by the same gesture. A list with one field uses [NameSortPill].
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
    /** False for a field that runs one way only (a playlist's own order): no arrow is offered for it. */
    hasDirection: (S) -> Boolean = { true },
    testTagPrefix: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SortPillFrame(
        label = label(choice.sort),
        direction = choice.direction.takeIf { hasDirection(choice.sort) },
        enabled = enabled,
        onLabelClick = { menuOpen = true },
        onDirectionClick = { onChoiceChange(choice.copy(direction = choice.direction.toggled())) },
        modifier = modifier,
        testTagPrefix = testTagPrefix,
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    modifier = testTagPrefix?.let { Modifier.testTag("${it}_${option.name.lowercase()}") }
                        ?: Modifier,
                    onClick = {
                        menuOpen = false
                        onChoiceChange(choice.afterSelecting(option, defaultDirection(option), hasDirection(option)))
                    },
                    trailingIcon = when {
                        option != choice.sort -> null
                        hasDirection(option) -> { { SortDirectionIcon(choice.direction) } }
                        else -> { { Icon(Icons.Rounded.Check, null, Modifier.padding(start = 4.dp).size(16.dp)) } }
                    },
                )
            }
        }
    }
}

/**
 * [SortPill] for a list that sorts by name only (artists, genres, folders). With one field there is
 * no menu to open: the entire pill is one target that reverses the order.
 */
@Composable
internal fun NameSortPill(
    direction: SongSortDirection,
    enabled: Boolean,
    onDirectionChange: (SongSortDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = { onDirectionChange(direction.afterSingleFieldTap()) },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = modifier.heightIn(min = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    ) {
        Icon(Icons.AutoMirrored.Rounded.Sort, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(stringResource(Res.string.sort_name), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
        SortDirectionIcon(direction)
    }
}

/**
 * A multi-field sort pill: the [label] opens a menu, and the separate arrow reverses [direction].
 * [menu] is anchored to the pill.
 */
@Composable
private fun SortPillFrame(
    label: String,
    /** Null for a field that runs one way only: no divider and no arrow. */
    direction: SongSortDirection?,
    enabled: Boolean,
    onLabelClick: () -> Unit,
    onDirectionClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String? = null,
    menu: @Composable () -> Unit = {},
) {
    Box(modifier) {
        Row(
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onLabelClick, enabled = enabled,
                contentPadding = PaddingValues(start = 14.dp, end = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp)
                    .then(testTagPrefix?.let { Modifier.testTag(it) } ?: Modifier),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp))
            }
            if (direction != null) {
                Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                IconButton(onClick = onDirectionClick, enabled = enabled) { SortDirectionIcon(direction) }
            }
        }
        menu()
    }
}

/**
 * The row above a browse list: its sort pill at the start, its item count at the far end, then any
 * [trailing] control (the Folders tab's tree/list toggle). The count gives way first: on a narrow
 * screen it ellipsizes rather than push [trailing] off the row.
 */
@Composable
private fun SortHeaderRow(
    count: Int,
    countPlural: PluralStringResource,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)?,
    pill: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // [trailing] dims itself while disabled, so only the pill and the count are dimmed here.
        Row(
            modifier = Modifier.weight(1f).graphicsLayer { alpha = if (enabled) 1f else 0.38f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pill()
            // The rest of the row, with the count at its end at its own width.
            Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterEnd) {
                Text(pluralStringResource(countPlural, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A list's [SortPill], with its item count at the far end: above the Tracks and Albums tabs. */
@Composable
internal fun <S : Enum<S>> SortHeader(
    options: List<S>,
    choice: SortChoice<S>,
    label: @Composable (S) -> String,
    defaultDirection: (S) -> SongSortDirection,
    /** How many items the list shows, worded by [countPlural]. */
    count: Int,
    countPlural: PluralStringResource,
    enabled: Boolean = true,
    onChoiceChange: (SortChoice<S>) -> Unit,
    testTagPrefix: String? = null,
) {
    SortHeaderRow(count = count, countPlural = countPlural, enabled = enabled, trailing = null) {
        SortPill(
            options = options,
            choice = choice,
            label = label,
            defaultDirection = defaultDirection,
            enabled = enabled,
            onChoiceChange = onChoiceChange,
            testTagPrefix = testTagPrefix,
        )
    }
}

/**
 * [SortHeader] for a list that sorts by name only, with a [NameSortPill]: above the Artists, Genres
 * and Folders tabs. [trailing] sits after the count.
 */
@Composable
internal fun NameSortHeader(
    direction: SongSortDirection,
    /** How many items the list shows, worded by [countPlural]. */
    count: Int,
    countPlural: PluralStringResource,
    enabled: Boolean,
    onDirectionChange: (SongSortDirection) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    SortHeaderRow(count = count, countPlural = countPlural, enabled = enabled, trailing = trailing) {
        NameSortPill(direction = direction, enabled = enabled, onDirectionChange = onDirectionChange)
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

/**
 * The direction after tapping the label of a list that sorts by one field. That label is the only
 * option, so every tap is a second tap on the active one: [directionAfterSortSelection] reverses it.
 */
internal fun SongSortDirection.afterSingleFieldTap(): SongSortDirection =
    directionAfterSortSelection(currentSort = Unit, currentDirection = this, selectedSort = Unit, selectedDefault = this)

/**
 * The choice after tapping [selected] in the menu; see [directionAfterSortSelection]. A field without
 * a direction ([selectedHasDirection] false) always takes its default, so a second tap changes nothing.
 */
internal fun <S : Enum<S>> SortChoice<S>.afterSelecting(
    selected: S,
    selectedDefault: SongSortDirection,
    selectedHasDirection: Boolean = true,
): SortChoice<S> = SortChoice(
    sort = selected,
    direction = if (selectedHasDirection) {
        directionAfterSortSelection(sort, direction, selected, selectedDefault)
    } else {
        selectedDefault
    },
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
internal fun PlaylistSort.label(): String = stringResource(
    when (this) {
        PlaylistSort.CUSTOM -> Res.string.sort_custom_order
        PlaylistSort.RECENT -> Res.string.sort_recently_added
        PlaylistSort.TITLE -> Res.string.info_title
        PlaylistSort.ARTIST -> Res.string.info_artist
        PlaylistSort.ALBUM -> Res.string.info_album
        PlaylistSort.DURATION -> Res.string.info_duration
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
