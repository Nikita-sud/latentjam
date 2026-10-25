/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Sheets, dialogs and snackbars share the player's language: the queue sheet's colour, its 24 dp
 * corner and its handle. Stock Material gave each of them its own radius (28, 28, 4) and the
 * sheets a lighter container than the player's, so every overlay looked borrowed.
 */
internal val OverlayShape: Shape
    @Composable @ReadOnlyComposable get() = RoundedCornerShape(24.dp.corner())
private val SheetShape: Shape
    @Composable @ReadOnlyComposable get() = RoundedCornerShape(topStart = 24.dp.corner(), topEnd = 24.dp.corner())

/** The queue sheet's handle: 32 x 4 on an 18 dp strip. */
@Composable
internal fun SheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetGesturesEnabled: Boolean = true,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.navigationBarFade(MaterialTheme.colorScheme.surfaceContainerHigh),
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = OverlayShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/**
 * Undo and notices: a dark card with the mini player's corner, instead of Material's light
 * rectangle that flashed against the dark library right above the player.
 */
@Composable
internal fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = OverlayShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.colorScheme.onSurface,
            actionContentColor = MaterialTheme.colorScheme.onSurface,
            dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Where this element passes under the navigation bar, its content fades into [colour]. A sheet
 * opened half way leaves its lower rows under the bar; with no system scrim there (the app turns it
 * off), three buttons sat right on their text. Behind three buttons the rows show through murkily
 * and the buttons sit on [colour]; the gesture pill needs no protection, so nothing is drawn.
 */
@Composable
internal fun Modifier.navigationBarFade(colour: Color): Modifier {
    val density = LocalDensity.current
    val bar = WindowInsets.navigationBars.getBottom(density).toFloat()
    if (bar < with(density) { THREE_BUTTON_BAR_MIN.toPx() }) return this
    var top by remember { mutableFloatStateOf(0f) }
    var windowHeight by remember { mutableFloatStateOf(0f) }
    return onGloballyPositioned {
        top = it.positionInRoot().y
        windowHeight = it.findRootCoordinates().size.height.toFloat()
    }.drawWithContent {
        drawContent()
        val barTop = windowHeight - bar - top
        if (windowHeight <= 0f || barTop >= size.height) return@drawWithContent
        drawRect(
            Brush.verticalGradient(
                0f to colour.copy(alpha = 0.4f),
                1f to colour,
                startY = barTop,
                endY = barTop + bar,
            ),
            topLeft = Offset(0f, barTop),
            size = Size(size.width, bar),
        )
    }
}

/** A navigation bar this tall holds three buttons; the gesture pill's is about 24 dp. */
internal val THREE_BUTTON_BAR_MIN = 32.dp
