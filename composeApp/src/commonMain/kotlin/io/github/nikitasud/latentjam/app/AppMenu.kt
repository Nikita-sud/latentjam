/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Every menu in the app has one look: the player's sheet colour and radius, a hairline edge so it
 * still reads as a surface over the cover cloud, and rows inset from the edge with concentric
 * corners. Stock Material menus (4 dp corners, flush full-width rows, labelLarge) looked like a
 * different app wherever they appeared.
 */
private val MenuShape: Shape
    @Composable @ReadOnlyComposable get() = RoundedCornerShape(24.dp.corner())

/** Rows sit this far in from the menu's edge, so their corners nest inside [MenuShape]. */
private val MenuItemInset = 8.dp
internal val MenuItemShape: Shape
    @Composable @ReadOnlyComposable get() = RoundedCornerShape((24.dp - MenuItemInset).corner())

/** Opens a little below its anchor rather than on top of it, so the anchor stays readable. */
private val MenuOffset = DpOffset(0.dp, 8.dp)

@Composable
internal fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** Zero for a menu that lays out its own full-width blocks (the playback-mode chooser). */
    contentInset: Dp = MenuItemInset,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 200.dp).padding(horizontal = contentInset),
        offset = MenuOffset,
        shape = MenuShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/**
 * One row of an [AppDropdownMenu]. [selected] marks the current choice of a chooser (a sort field,
 * a region) with a faint wash and a heavier label, the same way the playback-mode menu does.
 */
@Composable
internal fun AppMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** A second, quieter line (a region's track count). */
    supporting: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** Removes or deletes something: the label and icon take the error colour. */
    destructive: Boolean = false,
    /** Library text (titles, artists, places) rather than interface copy: laid out as metadata. */
    metadata: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge.let { if (metadata) it.metadata() else it }
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = text,
                    style = textStyle,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        onClick = onClick,
        modifier = modifier
            .clip(MenuItemShape)
            .background(if (selected) colors.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .semantics { if (selected) this.selected = true },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) } },
        trailingIcon = trailing,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = if (destructive) colors.error else colors.onSurface,
            leadingIconColor = if (destructive) colors.error else colors.onSurfaceVariant,
            trailingIconColor = colors.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

/** Separates groups of rows; inset like the rows so it never touches the menu's edge. */
@Composable
internal fun AppMenuDivider() {
    AppDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}
