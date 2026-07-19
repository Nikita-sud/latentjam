/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.action_next
import io.github.nikitasud.latentjam.app.generated.resources.action_pause
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_previous
import io.github.nikitasud.latentjam.app.generated.resources.action_track_options
import io.github.nikitasud.latentjam.app.generated.resources.cd_repeat_all
import io.github.nikitasud.latentjam.app.generated.resources.cd_repeat_off
import io.github.nikitasud.latentjam.app.generated.resources.cd_repeat_one
import io.github.nikitasud.latentjam.app.generated.resources.cd_shuffle_off
import io.github.nikitasud.latentjam.app.generated.resources.cd_shuffle_on
import io.github.nikitasud.latentjam.app.generated.resources.cd_shuffle_smart
import io.github.nikitasud.latentjam.app.generated.resources.now_playing_nothing
import io.github.nikitasud.latentjam.app.generated.resources.queue_title
import io.github.nikitasud.latentjam.app.generated.resources.queue_title_count
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How much of the queue sheet stays visible under the player. */
private val QueuePeekHeight = 84.dp

/**
 * Full-screen now-playing view. The background carries the track's accent —
 * sampled from the cover, or derived from its position in latent space when
 * there is no cover — so the screen belongs to the music that is playing.
 *
 * The queue is not hidden behind a button: it sits at the bottom as a sheet
 * that always peeks, so its presence (and the fact that it can be dragged up)
 * is visible without discovery. The transport keeps repeat and shuffle at the
 * outer edges with play/pause largest and centred (Fitts's law).
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playback: PlaybackController,
    accent: TrackAccent,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onClose: () -> Unit,
) {
    val now by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Local value while the thumb is being dragged, so the ticker doesn't
    // fight the user's finger; committed to the player on release.
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val sheetState = rememberBottomSheetScaffoldState()
    PlatformBackHandler(enabled = true, onBack = onClose)

    Surface(
        // Same shared container as the mini-player pill: the pill grows into
        // this screen instead of being swapped for it.
        modifier = with(sharedScope) {
            Modifier
                .fillMaxSize()
                .sharedBounds(rememberSharedContentState(PLAYER_SURFACE_KEY), animatedScope)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface,
                            accent.container,
                        ),
                    ),
                ),
        ) {
            // Continues the sheet's own colour through the system-bar strip beneath it.
            //
            // The scaffold below is lifted by navigationBarsPadding(), so without this the gradient
            // painted by the enclosing Box shows through in the gap — an artwork-tinted band that
            // changes colour every track. Behind Android's three-button bar that passes for a tinted
            // nav bar, but under an iPhone's home indicator it reads as a stray stripe.
            //
            // This is the rule the mini-player pill already follows: the SURFACE runs to the physical
            // edge, only the CONTENT is inset. Sizing from the same WindowInsets the scaffold pads by
            // means the two cannot drift — a home-button iPhone reports no inset and this draws
            // nothing at all, while a three-button Android phone gets the full 48dp.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )

            // The inset belongs on the SCAFFOLD, and it takes both modifiers to work. The app
            // draws edge to edge (no opting out from Android 15) and BottomSheetScaffold applies no
            // insets of its own, so the sheet was anchored to the raw bottom of the window and its
            // peek — the "Queue · n" label — sat behind the system buttons.
            //
            // Padding the sheet CONTENT cannot fix that, which is worth recording because it is the
            // obvious thing to reach for: a peeking sheet is taller than its container and
            // translated downwards, so the foot of its content is already far below the window and
            // padding there is invisible. navigationBarsPadding() moves the anchor the sheet hangs
            // from, which lifts the peek; clipToBounds() then cuts the part of the sheet that still
            // overhangs the container, which is what keeps queue rows out of the bar in both the
            // collapsed and the expanded state. The gradient behind the bar is unaffected — it is
            // painted by the Box outside this.
            BottomSheetScaffold(
                modifier = Modifier.navigationBarsPadding().clipToBounds(),
                scaffoldState = sheetState,
                sheetPeekHeight = QueuePeekHeight,
                sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                sheetShadowElevation = 0.dp,
                containerColor = Color.Transparent,
                sheetContent = {
                    QueueSheetContent(
                        queue = now.queue,
                        currentIndex = now.queueIndex,
                        isPlaying = now.isPlaying,
                        onPlayAt = { index -> scope.launch { playback.playAt(index) } },
                        onTrackMenu = onTrackMenu,
                    )
                },
            ) { sheetPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(bottom = sheetPadding.calculateBottomPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Matches the library's top bar geometry exactly, so the
                    // overflow lands in the same place before and after the morph.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(Res.string.action_close),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        OverflowButton(
                            sharedScope = sharedScope,
                            animatedScope = animatedScope,
                        ) { dismiss ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.action_track_options)) },
                                onClick = {
                                    dismiss()
                                    now.track?.let(onTrackMenu)
                                },
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LargeArtwork(
                            uri = now.track?.artworkUri,
                            modifier = with(sharedScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(ARTWORK_KEY),
                                    animatedScope,
                                )
                            },
                        )
                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = now.track?.title ?: stringResource(Res.string.now_playing_nothing),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(now.track?.artist, now.track?.album)
                                .joinToString(" — "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        val duration = now.durationMs.coerceAtLeast(1)
                        val sliderPosition = (dragPositionMs ?: now.positionMs).coerceIn(0, duration)
                        Slider(
                            value = sliderPosition.toFloat(),
                            onValueChange = { dragPositionMs = it.toLong() },
                            onValueChangeFinished = {
                                dragPositionMs?.let { target ->
                                    scope.launch { playback.seekTo(target) }
                                }
                                dragPositionMs = null
                            },
                            valueRange = 0f..duration.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.onSurface,
                                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatDuration(sliderPosition),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = formatDuration(now.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            RepeatButton(mode = now.repeatMode) {
                                scope.launch { playback.cycleRepeatMode() }
                            }
                            IconButton(
                                onClick = { scope.launch { playback.previous() } },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipPrevious,
                                    contentDescription = stringResource(Res.string.action_previous),
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            FilledIconButton(
                                onClick = { scope.launch { playback.togglePlayPause() } },
                                modifier = Modifier.size(72.dp),
                            ) {
                                Icon(
                                    imageVector = if (now.isPlaying) {
                                        Icons.Rounded.Pause
                                    } else {
                                        Icons.Rounded.PlayArrow
                                    },
                                    contentDescription = if (now.isPlaying) {
                                        stringResource(Res.string.action_pause)
                                    } else {
                                        stringResource(Res.string.action_play)
                                    },
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            IconButton(
                                onClick = { scope.launch { playback.next() } },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = stringResource(Res.string.action_next),
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            ShuffleButton(mode = now.shuffleMode) {
                                scope.launch { playback.cycleShuffleMode() }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LargeArtwork(uri: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Repeat is a three-state control, so the icon itself changes, not just its tint. */
@Composable
private fun RepeatButton(mode: RepeatMode, onClick: () -> Unit) {
    val active = mode != RepeatMode.OFF
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Icon(
            imageVector = if (mode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            contentDescription = stringResource(
                when (mode) {
                    RepeatMode.OFF -> Res.string.cd_repeat_off
                    RepeatMode.ALL -> Res.string.cd_repeat_all
                    RepeatMode.ONE -> Res.string.cd_repeat_one
                },
            ),
        )
    }
}

@Composable
private fun ShuffleButton(mode: ShuffleMode, onClick: () -> Unit) {
    val tint = when (mode) {
        ShuffleMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        ShuffleMode.ON -> MaterialTheme.colorScheme.primary
        ShuffleMode.SMART -> MaterialTheme.colorScheme.tertiary
    }
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            // SMART wears the app's own mark; plain shuffle keeps the
            // standard glyph, so the three states never rely on tint alone.
            imageVector = if (mode == ShuffleMode.SMART) LatentJamMark else Icons.Rounded.Shuffle,
            // One string per state rather than an interpolated enum name: the enum
            // is English source code, and a screen reader would have read it out.
            contentDescription = stringResource(
                when (mode) {
                    ShuffleMode.OFF -> Res.string.cd_shuffle_off
                    ShuffleMode.ON -> Res.string.cd_shuffle_on
                    ShuffleMode.SMART -> Res.string.cd_shuffle_smart
                },
            ),
            tint = tint,
        )
    }
}

/**
 * One queue entry.
 *
 * Three states have to be legible at a glance, because a queue is read while walking: what is
 * playing, what is behind you, and what is still to come. The current track keeps full contrast and
 * carries the equaliser over its cover; played tracks are dimmed as a whole — cover included — so
 * the boundary between past and future is a single visible edge in the list rather than something
 * to be inferred from a marker on one row.
 */
@Composable
private fun QueueRow(
    track: TrackDescriptor,
    isCurrent: Boolean,
    isPlayed: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Dim the whole row, not just the text: a full-contrast cover next to greyed labels
            // still reads as "up next".
            .alpha(if (isPlayed) 0.45f else 1f)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(uri = track.artworkUri, size = 48.dp)
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    // White rather than a theme colour: the scrim is always dark, but the artwork
                    // under it is anything at all, and the palette's primary is near-white here —
                    // it would sink into a pale cover.
                    PlayingBars(isPlaying = isPlaying, tint = Color.White)
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title ?: stringResource(Res.string.track_untitled),
                style = MaterialTheme.typography.bodyMedium,
                // Weight, not colour. The palette is deliberately neutral and its primary sits
                // close to onSurface, so a colour swap here would be almost invisible.
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist ?: stringResource(Res.string.track_unknown_artist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        track.durationMs?.let { duration ->
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMenu) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.action_track_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Three bars that rise and fall while audio is playing, and rest at a flat, even height when it is
 * paused — so a glance distinguishes "this is the current track" from "this is playing right now"
 * without a second icon.
 */
@Composable
private fun PlayingBars(isPlaying: Boolean, tint: Color) {
    val transition = rememberInfiniteTransition(label = "playing-bars")
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp),
    ) {
        // Staggered periods, so the bars never move as one block.
        listOf(620, 430, 780).forEachIndexed { index, period ->
            val fraction by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(period, easing = FastOutSlowInEasing),
                    repeatMode = AnimationRepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 130),
                ),
                label = "bar$index",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(if (isPlaying) fraction else 0.45f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

/**
 * The queue, always present at the bottom edge. The peek shows its handle and
 * label; dragging up reveals the list.
 */
@Composable
private fun QueueSheetContent(
    queue: List<TrackDescriptor>,
    currentIndex: Int,
    isPlaying: Boolean,
    onPlayAt: (Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (queue.isEmpty()) {
                stringResource(Res.string.queue_title)
            } else {
                stringResource(Res.string.queue_title_count, queue.size)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        LazyColumn(state = listState) {
            // Keyed by POSITION as well as identity. A queue may legitimately hold the same track
            // more than once — added by hand, or repeated across a long session — and a key that is
            // only the track id turns that into a crash during measurement.
            itemsIndexed(
                queue,
                key = { index, track -> "$index:${track.id.value}" },
            ) { index, track ->
                QueueRow(
                    track = track,
                    isCurrent = index == currentIndex,
                    // Everything above the playhead has been heard this session.
                    isPlayed = index < currentIndex,
                    isPlaying = isPlaying,
                    onClick = { onPlayAt(index) },
                    onMenu = { onTrackMenu(track) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
