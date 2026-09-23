/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_now_playing
import io.github.nikitasud.latentjam.app.generated.resources.cd_track_options_for
import io.github.nikitasud.latentjam.app.generated.resources.cd_track_options_generic
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.jetbrains.compose.resources.stringResource

internal enum class ArtworkLoadState { LOADING, TERMINAL }

/** Square, rounded artwork with a music-note placeholder behind it. */
@Composable
internal fun Artwork(
    uri: String?,
    size: Dp,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier,
    onLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Scrolling-list artwork stays direct: animating the URI can fade to an AsyncImage that is
        // still loading, producing a blank frame followed by a bitmap pop. Player handoffs retain
        // their old composed content and animate at the player level instead.
        if (uri != null) {
            val requestUri = uri
            AsyncImage(
                model = requestUri,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onLoading = {
                    onLoadStateChanged?.invoke(requestUri, ArtworkLoadState.LOADING)
                },
                onSuccess = {
                    onLoadStateChanged?.invoke(requestUri, ArtworkLoadState.TERMINAL)
                },
                // A terminal error is ready to reveal too: the music-note fallback is the
                // correct final state and must never keep a rail handoff waiting indefinitely.
                onError = {
                    onLoadStateChanged?.invoke(requestUri, ArtworkLoadState.TERMINAL)
                },
            )
        }
    }
}

/** Player artwork that keeps the old bitmap until the replacement has decoded successfully. */
@Composable
internal fun RetainedArtwork(
    uri: String?,
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val requestedUri by rememberUpdatedState(uri)
    var displayedUri by remember { mutableStateOf(uri) }
    LaunchedEffect(uri) {
        if (uri == null) displayedUri = null
    }
    Box(modifier = modifier.size(size)) {
        if (uri != null && uri != displayedUri) {
            val pendingUri = uri
            AsyncImage(
                model = pendingUri,
                contentDescription = null,
                modifier = Modifier.matchParentSize().alpha(0f),
                contentScale = ContentScale.Crop,
                onSuccess = {
                    if (requestedUri == pendingUri) displayedUri = pendingUri
                },
                onError = {
                    if (requestedUri == pendingUri) displayedUri = null
                },
            )
        }
        Crossfade(
            targetState = displayedUri,
            // During collapse the shared element already has the large cover's animated
            // bounds. Its bitmap must fill those bounds instead of immediately drawing at
            // the pill's final 48dp size inside a still-large shared placeholder.
            modifier = Modifier.matchParentSize(),
            animationSpec = tween(
                if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
            ),
            label = "retained-artwork",
        ) { shownUri ->
            Artwork(
                uri = shownUri,
                size = size,
                cornerRadius = cornerRadius,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Standard track row: artwork, title/artist, duration and an optional
 * overflow button that raises the track-actions sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    track: TrackDescriptor,
    isCurrent: Boolean,
    onClick: () -> Unit,
    /** Whether audio is actually running; drives the badge's motion on the current row. */
    isPlaying: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** `null` outside selection mode; otherwise whether this row is selected. */
    selectionState: Boolean? = null,
    onMenu: (() -> Unit)? = null,
    /** Optional request-state callback used by the alphabet-rail reveal gate. */
    onArtworkLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)? = null,
    /** When set, occurrences of this query in the title/artist render emphasized. */
    highlightQuery: String? = null,
    lyricSnippet: String? = null,
    /** Accessible artwork-derived accent shared with the player; null uses the theme accent. */
    currentAccent: Color? = null,
    rowEndInset: Dp = 8.dp,
    secondaryText: String? = null,
    showDuration: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    val accent = currentAccent ?: MaterialTheme.colorScheme.primary
    // The badge is visual; this is the same fact for ears. Resolved before the modifier chain
    // because stringResource is composable.
    val nowPlayingDescription = if (isCurrent) {
        stringResource(Res.string.cd_now_playing)
    } else {
        null
    }
    var lastSelectionValue by remember { mutableStateOf(selectionState ?: false) }
    val shownSelectionValue = selectionState ?: lastSelectionValue
    SideEffect {
        selectionState?.let { lastSelectionValue = it }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = rowEndInset)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) accent.copy(alpha = 0.10f) else Color.Transparent)
            .then(
                if (selectionState != null || nowPlayingDescription != null) {
                    Modifier.semantics {
                        if (selectionState != null) {
                            selected = selectionState
                            role = Role.Checkbox
                        }
                        if (nowPlayingDescription != null) {
                            stateDescription = nowPlayingDescription
                        }
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = onClick,
                // combinedClickable supplies the platform long-press feedback itself. A manual
                // pulse here doubles it and can outlive the action that opened selection mode.
                onLongClick = onLongClick,
            )
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedVisibility(
            visible = selectionState != null,
            enter = if (reduceMotion) {
                fadeIn(tween(Motion.REDUCED_MS))
            } else {
                fadeIn(tween(Motion.APPEAR_MS)) +
                    expandHorizontally(tween(Motion.APPEAR_MS))
            },
            exit = if (reduceMotion) {
                fadeOut(tween(Motion.REDUCED_MS))
            } else {
                fadeOut(tween(Motion.REPLACE_MS)) +
                    shrinkHorizontally(tween(Motion.REPLACE_MS))
            },
        ) {
            // The tick itself pops when toggled; the container above handles mode enter/exit.
            AnimatedContent(
                targetState = shownSelectionValue,
                transitionSpec = { motionIconTransform(reduceMotion) },
                label = "row-check",
            ) { checked ->
            Icon(
                imageVector = if (checked) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
            }
        }
        Artwork(
            uri = track.artworkUri,
            size = 48.dp,
            onLoadStateChanged = onArtworkLoadStateChanged,
        )
        Column(modifier = Modifier.weight(1f)) {
            // Research-standard result emphasis: BOLD the matched term (the convention search
            // UIs settled on), plus a step up in tone so the emphasis survives bold-ish fonts.
            val highlight = SpanStyle(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            val title = track.title ?: stringResource(Res.string.track_untitled)
            val secondary = secondaryText ?: track.artist ?: stringResource(Res.string.track_unknown_artist)
            AnimatedTrackTitle(
                text = remember(title, highlightQuery, highlight) {
                    emphasized(title, highlightQuery, highlight)
                },
                targetColor = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                reduceMotion = reduceMotion,
            )
            Text(
                text = remember(secondary, highlightQuery, highlight) {
                    emphasized(secondary, highlightQuery, highlight)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            lyricSnippet?.let { snippet ->
                Text(
                    text = remember(snippet, highlightQuery, highlight) {
                        emphasized(snippet, highlightQuery, highlight)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent && selectionState == null) {
            NowPlayingIndicator(animating = isPlaying, tint = accent)
        }
        if (showDuration) {
            track.durationMs?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 34.dp),
                )
            }
        }
        if (selectionState == null && onMenu != null) {
            val title = track.title
            val menuDescription = if (title != null) {
                stringResource(Res.string.cd_track_options_for, title)
            } else {
                stringResource(Res.string.cd_track_options_generic)
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = menuDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Color motion only recomposes this label, leaving artwork, gestures and search matching idle. */
@Composable
private fun AnimatedTrackTitle(text: AnnotatedString, targetColor: Color, reduceMotion: Boolean) {
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS),
        label = "track-title-color",
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * The "this row is the player's track" badge: three trailing bars, moving while audio
 * runs and frozen mid-pose while paused. The distinction is deliberate — motion promises sound,
 * and a paused player keeping a dancing row would promise wrong.
 *
 * The trailing badge uses the same readable accent as the current title. Its bar heights are
 * read only by the graphics layer so playback never remeasures the row each frame.
 */
@Composable
internal fun NowPlayingIndicator(animating: Boolean, tint: Color) {
    val reduceMotion = rememberReduceMotion()
    val transition = if (animating && !reduceMotion) {
        rememberInfiniteTransition(label = "now-playing")
    } else null
    Row(
        modifier = Modifier.height(16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BAR_PHASES_MS.forEachIndexed { index, phase ->
            val fraction = transition?.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(phase),
                ),
                label = "bar",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleY = fraction?.value ?: PAUSED_BAR_FRACTIONS[index]
                    }
                    .background(tint, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Staggered starts keep the three bars out of phase, like a level meter rather than a blink. */
private val BAR_PHASES_MS = listOf(0, 140, 280)

/** A believable frozen pose: unequal heights read as "stopped mid-song", not as a glyph. */
private val PAUSED_BAR_FRACTIONS = listOf(0.55f, 0.3f, 0.75f)

/** The text with every folded occurrence of [query] wearing [style]; plain when none. */
private fun emphasized(
    text: String,
    query: String?,
    style: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    append(text)
    val needle = query?.trim()?.takeIf { it.isNotEmpty() } ?: return@buildAnnotatedString
    for (range in searchHighlightRanges(text, needle)) {
        addStyle(style, range.first, range.last + 1)
    }
}
