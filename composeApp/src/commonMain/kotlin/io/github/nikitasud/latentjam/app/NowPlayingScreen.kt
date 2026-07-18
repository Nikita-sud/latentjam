/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch

/**
 * Full-screen now-playing view: large artwork, track block, drag-to-seek
 * slider on the ticker-fed position, transport row (shuffle / previous /
 * play-pause / next), and the queue as a modal sheet with jump-to-track.
 * Original Material 3 expression throughout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(playback: PlaybackController, onClose: () -> Unit) {
    val now by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showQueue by remember { mutableStateOf(false) }
    // Local value while the thumb is being dragged, so the ticker doesn't
    // fight the user's finger; committed to the player on release.
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }

    PlatformBackHandler(enabled = true, onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close")
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = "Queue")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LargeArtwork(uri = now.track?.artworkUri)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = now.track?.title ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(now.track?.artist, now.track?.album).joinToString(" — "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(24.dp))

            val duration = now.durationMs.coerceAtLeast(1)
            val sliderPosition = (dragPositionMs ?: now.positionMs).coerceIn(0, duration)
            Slider(
                value = sliderPosition.toFloat(),
                onValueChange = { dragPositionMs = it.toLong() },
                onValueChangeFinished = {
                    dragPositionMs?.let { target -> scope.launch { playback.seekTo(target) } }
                    dragPositionMs = null
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(sliderPosition), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(now.durationMs), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShuffleModeButton(mode = now.shuffleMode) {
                    scope.launch { playback.cycleShuffleMode() }
                }
                IconButton(onClick = { scope.launch { playback.previous() } }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
                FilledIconButton(
                    onClick = { scope.launch { playback.togglePlayPause() } },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (now.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { scope.launch { playback.next() } }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
                // Balances the row so the play button stays centered.
                Spacer(modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showQueue) {
        QueueSheet(
            queue = now.queue,
            currentIndex = now.queueIndex,
            onPlayAt = { index -> scope.launch { playback.playAt(index) } },
            onDismiss = { showQueue = false },
        )
    }
}

@Composable
private fun LargeArtwork(uri: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
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

@Composable
private fun ShuffleModeButton(mode: ShuffleMode, onClick: () -> Unit) {
    val tint = when (mode) {
        ShuffleMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        ShuffleMode.ON -> MaterialTheme.colorScheme.primary
        ShuffleMode.SMART -> MaterialTheme.colorScheme.tertiary
    }
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle mode: $mode", tint = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: List<TrackDescriptor>,
    currentIndex: Int,
    onPlayAt: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "Queue (${queue.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
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

