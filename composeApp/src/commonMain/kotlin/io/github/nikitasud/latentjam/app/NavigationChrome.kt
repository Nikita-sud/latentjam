/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.settings_title
import io.github.nikitasud.latentjam.app.generated.resources.cd_search_library
import io.github.nikitasud.latentjam.app.generated.resources.cd_close_search
import org.jetbrains.compose.resources.stringResource

/** The press starts the gear moving; navigation continues it without delaying the action. */
@Composable
internal fun SettingsGearButton(
    openProgress: () -> Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val reduced = rememberReduceMotion()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val haptics = LocalHapticFeedback.current
    val press = animateFloatAsState(
        targetValue = if (pressed.value && !reduced) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(Motion.QUICK_MS, easing = Motion.NavigationEasing),
        label = "settings-gear-press",
    )
    IconButton(
        onClick = {
            haptics.play(PlayerHaptic.TAP)
            onClick()
        },
        enabled = enabled,
        modifier = modifier,
        interactionSource = interaction,
    ) {
        Icon(
            Icons.Rounded.Settings,
            contentDescription = stringResource(Res.string.settings_title),
            modifier = Modifier.graphicsLayer {
                rotationZ = if (reduced) 0f else (180f * openProgress() - 18f * press.value) * direction
                scaleX = 1f - 0.08f * press.value
                scaleY = scaleX
            },
        )
    }
}

/** Continue the same gear turn in the destination, then reveal its back affordance. */
@Composable
internal fun SettingsBackIcon(openProgress: () -> Float, origin: Rect? = null) {
    val reduced = rememberReduceMotion()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val back = stringResource(Res.string.action_back)
    var destination by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.size(24.dp).onGloballyPositioned {
        destination = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
    }.semantics { contentDescription = back }) {
        Icon(
            Icons.Rounded.Settings,
            contentDescription = null,
            modifier = Modifier.graphicsLayer {
                val progress = if (reduced) 1f else openProgress().coerceIn(0f, 1f)
                rotationZ = 180f * progress * direction
                val from = origin?.center
                val to = destination
                translationX = if (from != null && to != null) (from.x - to.x) * (1f - progress) else 0f
                translationY = if (from != null && to != null) (from.y - to.y) * (1f - progress) else 0f
                alpha = 1f - navigationPhase(progress, 0.2f, 0.8f)
            },
        )
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            modifier = Modifier.graphicsLayer {
                val progress = if (reduced) 1f else openProgress().coerceIn(0f, 1f)
                rotationZ = -30f * (1f - progress) * direction
                val from = origin?.center
                val to = destination
                translationX = if (from != null && to != null) (from.x - to.x) * (1f - progress) else 0f
                translationY = if (from != null && to != null) (from.y - to.y) * (1f - progress) else 0f
                alpha = navigationPhase(progress, 0.35f, 1f)
            },
        )
    }
}

/** A bounded phase of one reversible navigation clock; never launches per-row animations. */
internal fun navigationPhase(progress: Float, start: Float, end: Float): Float =
    ((progress.coerceIn(0f, 1f) - start) / (end - start)).coerceIn(0f, 1f)

internal fun navigationRevealBounds(origin: Rect, target: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    fun between(a: Float, b: Float) = a + (b - a) * p
    return Rect(between(origin.left, target.left), between(origin.top, target.top),
        between(origin.right, target.right), between(origin.bottom, target.bottom))
}

private data class NavigationRevealShape(val bounds: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}

/** A transient rounded reveal, removed entirely once the page has settled. Text never scales. */
@Composable
internal fun Modifier.settingsButtonReveal(progress: () -> Float, origin: Rect?): Modifier {
    val reduced = rememberReduceMotion()
    var pageOffset by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { pageOffset = it.positionInRoot() }
        .graphicsLayer {
            val p = progress().coerceIn(0f, 1f)
            alpha = if (reduced) 1f else navigationPhase(p, 0f, 0.15f)
            if (!reduced && origin != null && p < 1f) {
                val from = origin.translate(-pageOffset)
                shape = NavigationRevealShape(
                    navigationRevealBounds(from, Rect(Offset.Zero, size), p),
                    24.dp.toPx() * (1f - p),
                )
                clip = true
            } else {
                clip = false
            }
        }
}

@Composable
internal fun SearchLaunchButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    IconButton(
        enabled = enabled,
        onClick = { haptics.play(PlayerHaptic.TAP); onClick() },
        interactionSource = interaction,
        modifier = modifier.scaleOnPress(interaction),
    ) {
        Icon(Icons.Rounded.Search, stringResource(Res.string.cd_search_library))
    }
}

/** Only the field background and navigation glyph move. The real editor stays at its final bounds. */
@Composable
internal fun SearchNavigationBar(
    progress: () -> Float,
    origin: Rect?,
    onClose: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val reduced = rememberReduceMotion()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val background = MaterialTheme.colorScheme.surfaceContainer
    var headerBounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { headerBounds = it.boundsInRoot() }
            .drawBehind {
                val p = if (reduced) 1f else progress().coerceIn(0f, 1f)
                val insetX = 20.dp.toPx()
                val insetY = 8.dp.toPx()
                val target = Rect(insetX, insetY, size.width - insetX, size.height - insetY)
                val source = origin?.translate(-headerBounds.topLeft) ?: target
                val bounds = navigationRevealBounds(source, target, p)
                drawRoundRect(background, bounds.topLeft, bounds.size, CornerRadius(24.dp.toPx()),
                    alpha = navigationPhase(p, 0f, 0.3f))
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Box(
                    Modifier.size(24.dp)
                        .graphicsLayer {
                            val p = if (reduced) 1f else progress().coerceIn(0f, 1f)
                            val targetX = if (rtl) headerBounds.width - 44.dp.toPx() else 44.dp.toPx()
                            val targetY = headerBounds.height / 2f
                            val source = origin?.center?.minus(headerBounds.topLeft)
                                ?: Offset(targetX, targetY)
                            translationX = (source.x - targetX) * (1f - p)
                            translationY = (source.y - targetY) * (1f - p)
                        },
                ) {
                    Icon(Icons.Rounded.Search, null, Modifier.graphicsLayer {
                        val p = if (reduced) 1f else progress()
                        alpha = 1f - navigationPhase(p, 0.2f, 0.65f)
                        rotationZ = -35f * p * if (rtl) -1f else 1f
                    })
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack,
                        stringResource(Res.string.cd_close_search), Modifier.graphicsLayer {
                            val p = if (reduced) 1f else progress()
                            alpha = navigationPhase(p, 0.4f, 0.9f)
                            rotationZ = 35f * (1f - p) * if (rtl) -1f else 1f
                        })
                }
            }
            Row(
                modifier = Modifier.weight(1f).graphicsLayer {
                    alpha = if (reduced) 1f else navigationPhase(progress(), 0.35f, 0.95f)
                },
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
