/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch

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
    onTrackMenu: () -> Unit,
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
            BottomSheetScaffold(
                scaffoldState = sheetState,
                sheetPeekHeight = QueuePeekHeight,
                sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                sheetShadowElevation = 0.dp,
                containerColor = Color.Transparent,
                sheetContent = {
                    QueueSheetContent(
                        queue = now.queue,
                        currentIndex = now.queueIndex,
                        onPlayAt = { index -> scope.launch { playback.playAt(index) } },
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
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        OverflowButton(
                            sharedScope = sharedScope,
                            animatedScope = animatedScope,
                        ) { dismiss ->
                            DropdownMenuItem(
                                text = { Text("Track options") },
                                onClick = {
                                    dismiss()
                                    onTrackMenu()
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
                            text = now.track?.title ?: "Nothing playing",
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
                                    contentDescription = "Previous",
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
                                    contentDescription = if (now.isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            IconButton(
                                onClick = { scope.launch { playback.next() } },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "Next",
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
            contentDescription = when (mode) {
                RepeatMode.OFF -> "Repeat off. Tap to repeat the queue."
                RepeatMode.ALL -> "Repeating the queue. Tap to repeat one track."
                RepeatMode.ONE -> "Repeating one track. Tap to turn repeat off."
            },
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
            contentDescription = "Shuffle: ${mode.name.lowercase()}. Tap to change.",
            tint = tint,
        )
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
    onPlayAt: (Int) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (queue.isEmpty()) "Queue" else "Queue · ${queue.size}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        LazyColumn(state = listState) {
            itemsIndexed(queue, key = { _, track -> track.id.value }) { index, track ->
                val isCurrent = index == currentIndex
                Row(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clickable { onPlayAt(index) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title ?: "Untitled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist ?: "Unknown artist",
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
                }
            }
        }
    }
}
