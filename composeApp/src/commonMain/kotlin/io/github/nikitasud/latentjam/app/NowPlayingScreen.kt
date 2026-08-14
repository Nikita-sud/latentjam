/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_cancel
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.action_next
import io.github.nikitasud.latentjam.app.generated.resources.action_pause
import io.github.nikitasud.latentjam.app.generated.resources.action_add_favorite
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_favorite
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
import io.github.nikitasud.latentjam.app.generated.resources.now_playing_source
import io.github.nikitasud.latentjam.app.generated.resources.queue_title
import io.github.nikitasud.latentjam.app.generated.resources.queue_title_count
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer_active_end_of_track
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer_active_minutes
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer_end_of_track
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer_minutes
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer_off
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.SleepTimerState
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How much of the queue sheet stays visible under the player. */
private val QueuePeekHeight = 84.dp
private val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)
private data class TrackMetadataPresentation(
    val track: TrackDescriptor?,
    val sourceLabel: String?,
)

/**
 * One cheap identity token for a queue snapshot.
 *
 * The instance deliberately keeps reference equality: queue rows can use it as a Compose key to
 * reset gesture state after a structural change without hashing/comparing the whole queue once per
 * visible row. Duplicate detection is folded into the same single pass that creates the token.
 */
internal class QueueIdentitySnapshot internal constructor(
    internal val hasDuplicateTrackIds: Boolean,
)

internal fun queueIdentitySnapshot(queue: List<TrackDescriptor>): QueueIdentitySnapshot {
    val seen = HashSet<TrackId>()
    for (track in queue) {
        if (!seen.add(track.id)) return QueueIdentitySnapshot(hasDuplicateTrackIds = true)
    }
    return QueueIdentitySnapshot(hasDuplicateTrackIds = false)
}

/** Stable IDs animate safely; duplicate queues use guaranteed-unique positional keys instead. */
internal fun queueLazyItemKey(
    snapshot: QueueIdentitySnapshot,
    index: Int,
    track: TrackDescriptor,
): Any = if (snapshot.hasDuplicateTrackIds) index else track.id.value

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
    /** Resolved "Playing from" name — collection title, search query, or a surface label. */
    queueSourceLabel: String? = null,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    sleepTimerState: SleepTimerState,
    onStartSleepTimer: (minutes: Int) -> Unit,
    onSleepAtEndOfTrack: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    /** Raised by the queue sheet's save affordance with the CURRENT queue as the selection. */
    onAddQueueToPlaylist: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onClose: () -> Unit,
) {
    // Position is intentionally projected out. It changes twice per second, while artwork, queue,
    // sheet and transport controls normally do not; rebuilding this whole screen for each tick was
    // the largest steady-state source of Compose work during playback.
    val now by remember(playback) {
        playback.state.map { it.copy(positionMs = 0L) }.distinctUntilChanged()
    }.collectAsState(playback.state.value.copy(positionMs = 0L))
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetScaffoldState()
    var showSleepTimer by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    PlatformBackHandler(enabled = true, onBack = onClose)

    Surface(
        // Same shared container as the mini-player pill: the pill grows into
        // this screen instead of being swapped for it.
        modifier = if (reduceMotion) {
            Modifier.fillMaxSize()
        } else with(sharedScope) {
            Modifier
                .fillMaxSize()
                .sharedBounds(
                    rememberSharedContentState(PLAYER_SURFACE_KEY),
                    animatedScope,
                    boundsTransform = motionBoundsTransform(),
                )
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
                        canReorder = now.shuffleMode != ShuffleMode.ON,
                        onPlayAt = { index -> scope.launch { playback.playAt(index) } },
                        onTrackMenu = onTrackMenu,
                        onRemoveAt = { index -> scope.launch { playback.removeQueueItem(index) } },
                        onMove = { from, to ->
                            scope.launch { playback.moveQueueItem(from, to) }
                        },
                        onSaveQueue = onAddQueueToPlaylist,
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
                        if (now.track != null) {
                            IconButton(onClick = onToggleFavorite) {
                                // A like bounces once under the finger; removing one stays calm,
                                // and entering the screen with an old favourite stays still too.
                                val trackId = checkNotNull(now.track).id
                                val heartScale = remember(trackId) { Animatable(1f) }
                                var wasFavorite by remember(trackId) { mutableStateOf(isFavorite) }
                                LaunchedEffect(trackId, isFavorite, reduceMotion) {
                                    val turnedOn = isFavorite && !wasFavorite
                                    wasFavorite = isFavorite
                                    if (turnedOn && !reduceMotion) {
                                        heartScale.snapTo(0.6f)
                                        heartScale.animateTo(
                                            targetValue = 1f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                        )
                                    } else {
                                        // An unlike, a canceled bounce, or Reduce Motion changing
                                        // mid-flight always restores the stable resting scale.
                                        heartScale.snapTo(1f)
                                    }
                                }
                                Icon(
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = heartScale.value
                                        scaleY = heartScale.value
                                    },
                                    imageVector = if (isFavorite) {
                                        Icons.Rounded.Favorite
                                    } else {
                                        Icons.Rounded.FavoriteBorder
                                    },
                                    contentDescription = stringResource(
                                        if (isFavorite) {
                                            Res.string.action_remove_favorite
                                        } else {
                                            Res.string.action_add_favorite
                                        },
                                    ),
                                    tint = if (isFavorite) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                )
                            }
                        }
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
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(Res.string.sleep_timer))
                                        when (val timer = sleepTimerState) {
                                            SleepTimerState.Off -> Unit
                                            is SleepTimerState.Countdown -> Text(
                                                text = stringResource(
                                                    Res.string.sleep_timer_active_minutes,
                                                    timer.remainingMinutes,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            SleepTimerState.EndOfTrack -> Text(
                                                text = stringResource(
                                                    Res.string.sleep_timer_active_end_of_track,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    dismiss()
                                    showSleepTimer = true
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
                            modifier = if (reduceMotion) Modifier else with(sharedScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(ARTWORK_KEY),
                                    animatedScope,
                                    boundsTransform = motionBoundsTransform(),
                                )
                            },
                        )
                        Spacer(modifier = Modifier.weight(1f))

                        // Cover, colour and words now change as one event when the queue advances.
                        // The small fade-through keeps a skip legible without sending the whole
                        // player sideways like another page navigation.
                        AnimatedContent(
                            targetState = TrackMetadataPresentation(
                                track = now.track,
                                sourceLabel = queueSourceLabel,
                            ),
                            contentKey = { it.track?.id },
                            transitionSpec = { motionFadeThrough(reduceMotion) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "track-metadata",
                        ) { shown ->
                            val shownTrack = shown.track
                            Column(
                                modifier = Modifier.inactiveForMotion(
                                    shownTrack?.id != now.track?.id,
                                ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = shownTrack?.title
                                        ?: stringResource(Res.string.now_playing_nothing),
                                    style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(shownTrack?.artist, shownTrack?.album)
                                        .joinToString(" — "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (shown.sourceLabel != null && shownTrack != null) {
                                    Text(
                                        text = stringResource(
                                            Res.string.now_playing_source,
                                            shown.sourceLabel,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        PlaybackSeekBar(playback = playback, durationMs = now.durationMs)

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
                            val playPauseDescription = stringResource(
                                if (now.isPlaying) {
                                    Res.string.action_pause
                                } else {
                                    Res.string.action_play
                                },
                            )
                            FilledIconButton(
                                onClick = { scope.launch { playback.togglePlayPause() } },
                                modifier = Modifier
                                    .size(72.dp)
                                    .semantics { contentDescription = playPauseDescription },
                            ) {
                                AnimatedContent(
                                    targetState = now.isPlaying,
                                    transitionSpec = { motionIconTransform(reduceMotion) },
                                    label = "play-pause",
                                ) { playing ->
                                Icon(
                                    imageVector = if (playing) {
                                        Icons.Rounded.Pause
                                    } else {
                                        Icons.Rounded.PlayArrow
                                    },
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .inactiveForMotion(playing != now.isPlaying),
                                )
                                }
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

    if (showSleepTimer) {
        SleepTimerDialog(
            state = sleepTimerState,
            onStart = { minutes ->
                showSleepTimer = false
                onStartSleepTimer(minutes)
            },
            onEndOfTrack = {
                showSleepTimer = false
                onSleepAtEndOfTrack()
            },
            onTurnOff = {
                showSleepTimer = false
                onCancelSleepTimer()
            },
            onDismiss = { showSleepTimer = false },
        )
    }
}

/** The only expanded-player subtree that observes the coarse position ticker. */
@Composable
private fun PlaybackSeekBar(playback: PlaybackController, durationMs: Long) {
    val positionMs by remember(playback) {
        playback.state.map { it.positionMs }.distinctUntilChanged()
    }.collectAsState(playback.state.value.positionMs)
    val scope = rememberCoroutineScope()
    // Local value while the thumb is being dragged, so the ticker does not fight the finger.
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val sliderPosition = (dragPositionMs ?: positionMs).coerceIn(0, duration)

    Slider(
        value = sliderPosition.toFloat(),
        onValueChange = { dragPositionMs = it.toLong() },
        onValueChangeFinished = {
            dragPositionMs?.let { target -> scope.launch { playback.seekTo(target) } }
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
        Text(text = formatDuration(sliderPosition), style = MaterialTheme.typography.labelSmall)
        Text(text = formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
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
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun SleepTimerDialog(
    state: SleepTimerState,
    onStart: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sleep_timer)) },
        text = {
            Column {
                SLEEP_TIMER_MINUTES.forEach { minutes ->
                    SleepTimerChoice(
                        label = pluralStringResource(
                            Res.plurals.sleep_timer_minutes,
                            minutes,
                            minutes,
                        ),
                        onClick = { onStart(minutes) },
                    )
                }
                SleepTimerChoice(
                    label = stringResource(Res.string.sleep_timer_end_of_track),
                    onClick = onEndOfTrack,
                )
                if (state !is SleepTimerState.Off) {
                    SleepTimerChoice(
                        label = stringResource(Res.string.sleep_timer_off),
                        onClick = onTurnOff,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SleepTimerChoice(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Repeat is a three-state control, so the icon itself changes, not just its tint. */
@Composable
private fun RepeatButton(mode: RepeatMode, onClick: () -> Unit) {
    val active = mode != RepeatMode.OFF
    val reduceMotion = rememberReduceMotion()
    val tint by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(
            if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
        ),
        label = "repeat-tint",
    )
    val description = stringResource(
        when (mode) {
            RepeatMode.OFF -> Res.string.cd_repeat_off
            RepeatMode.ALL -> Res.string.cd_repeat_all
            RepeatMode.ONE -> Res.string.cd_repeat_one
        },
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
        ),
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = { motionIconTransform(reduceMotion) },
            label = "repeat-mode",
        ) { shownMode ->
        Icon(
            imageVector = if (shownMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            contentDescription = null,
            modifier = Modifier.inactiveForMotion(shownMode != mode),
        )
        }
    }
}

@Composable
private fun ShuffleButton(mode: ShuffleMode, onClick: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val tint by animateColorAsState(
        targetValue = when (mode) {
            ShuffleMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            ShuffleMode.ON -> MaterialTheme.colorScheme.primary
            ShuffleMode.SMART -> MaterialTheme.colorScheme.tertiary
        },
        animationSpec = tween(
            if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
        ),
        label = "shuffle-tint",
    )
    val description = stringResource(
        when (mode) {
            ShuffleMode.OFF -> Res.string.cd_shuffle_off
            ShuffleMode.ON -> Res.string.cd_shuffle_on
            ShuffleMode.SMART -> Res.string.cd_shuffle_smart
        },
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = { motionIconTransform(reduceMotion) },
            label = "shuffle-mode",
        ) { shownMode ->
        Icon(
            // SMART wears the app's own mark; plain shuffle keeps the
            // standard glyph, so the three states never rely on tint alone.
            imageVector = if (shownMode == ShuffleMode.SMART) LatentJamMark else Icons.Rounded.Shuffle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.inactiveForMotion(shownMode != mode),
        )
        }
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
    val reduceMotion = rememberReduceMotion()
    val rowAlpha by animateFloatAsState(
        targetValue = if (isPlayed) 0.45f else 1f,
        animationSpec = tween(
            if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
        ),
        label = "queue-row-alpha",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Dim the whole row, not just the text: a full-contrast cover next to greyed labels
            // still reads as "up next".
            .alpha(rowAlpha)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(uri = track.artworkUri, size = 48.dp)
            androidx.compose.animation.AnimatedVisibility(
                visible = isCurrent,
                enter = androidx.compose.animation.fadeIn(tween(
                    if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS,
                )),
                exit = androidx.compose.animation.fadeOut(tween(
                    if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS,
                )),
            ) {
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
    val reduceMotion = rememberReduceMotion()
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp),
    ) {
        if (!isPlaying || reduceMotion) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(0.45f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
            }
        } else {
            val transition = rememberInfiniteTransition(label = "playing-bars")
            // Staggered periods, so the bars never move as one block. The animated value is read
            // by the graphics layer rather than composition/layout, reducing this to a cheap draw
            // update instead of three remeasurements per frame.
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
                        .fillMaxHeight()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            scaleY = fraction
                        }
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
            }
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
    /** False under random shuffle: the sheet shows a traversal, not the player's list. */
    canReorder: Boolean,
    onPlayAt: (Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onSaveQueue: () -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0),
    )
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val queueIdentity = remember(queue) { queueIdentitySnapshot(queue) }
    val hasDuplicateIds = queueIdentity.hasDuplicateTrackIds
    var previouslyHadDuplicateIds by remember { mutableStateOf(hasDuplicateIds) }
    val ambiguousItemIdentity = hasDuplicateIds || previouslyHadDuplicateIds
    SideEffect { previouslyHadDuplicateIds = hasDuplicateIds }
    // Reordering floats the pressed row and commits ONE move on drop. Mutating the list mid-drag
    // would still replace the model under the finger and kill the gesture.
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            AnimatedContent(
                targetState = queue.size,
                transitionSpec = { motionFadeThrough(reduceMotion) },
                modifier = Modifier.align(Alignment.Center),
                label = "queue-count",
            ) { count ->
                Text(
                    text = if (count == 0) {
                        stringResource(Res.string.queue_title)
                    } else {
                        stringResource(Res.string.queue_title_count, count)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            AnimatedContent(
                targetState = queue.isNotEmpty(),
                transitionSpec = { motionIconTransform(reduceMotion) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp),
                label = "save-queue",
            ) { canSave ->
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (canSave) {
                        // A queue worth keeping — often SMART's work — becomes a playlist in two taps.
                        IconButton(onClick = onSaveQueue) {
                            Icon(
                                imageVector = Icons.Rounded.LibraryAdd,
                                contentDescription =
                                    stringResource(Res.string.action_add_to_playlist),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
        LazyColumn(state = listState) {
            // Stable IDs preserve row identity across ordinary reorders. Duplicate IDs cannot do
            // that safely, so those queues use positional keys and disable item animations.
            itemsIndexed(
                queue,
                key = { index, track -> queueLazyItemKey(queueIdentity, index, track) },
            ) { index, track ->
                // Duplicate occurrences cannot be distinguished by TrackId alone. Reset gesture
                // state on any structural queue change so a survivor never inherits a removed
                // duplicate's dismissed anchor. queueIdentity is reference-equal and therefore
                // avoids hashing/comparing the entire queue once for every visible row.
                androidx.compose.runtime.key(queueIdentity) {
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            onRemoveAt(index)
                            true
                        } else {
                            false
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = if (ambiguousItemIdentity) null else tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                            ),
                            placementSpec = if (reduceMotion || ambiguousItemIdentity) {
                                null
                            } else {
                                tween(Motion.APPEAR_MS)
                            },
                            fadeOutSpec = if (ambiguousItemIdentity) null else tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                            ),
                        )
                        .then(
                            if (draggingIndex == index) {
                                Modifier
                                    .zIndex(1f)
                                    .graphicsLayer { translationY = dragOffsetY }
                            } else {
                                Modifier
                            },
                        )
                        .pointerInput(canReorder, index, queue.size) {
                            if (!canReorder) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingIndex = index
                                    dragTargetIndex = index
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    val rowHeight = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == index }
                                        ?.size
                                        ?.takeIf { it > 0 }
                                    if (rowHeight != null) {
                                        val shift = (dragOffsetY / rowHeight).roundToInt()
                                        dragTargetIndex =
                                            (index + shift).coerceIn(0, queue.lastIndex)
                                    }
                                },
                                onDragEnd = {
                                    val from = draggingIndex
                                    val to = dragTargetIndex
                                    draggingIndex = null
                                    dragTargetIndex = null
                                    dragOffsetY = 0f
                                    if (from != null && to != null && from != to) onMove(from, to)
                                },
                                onDragCancel = {
                                    draggingIndex = null
                                    dragTargetIndex = null
                                    dragOffsetY = 0f
                                },
                            )
                        },
                ) {
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer),
                                contentAlignment = when (dismissState.dismissDirection) {
                                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                    else -> Alignment.CenterEnd
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        },
                    ) {
                        QueueRow(
                            track = track,
                            isCurrent = index == currentIndex,
                            // Everything above the playhead has been heard this session.
                            isPlayed = index < currentIndex,
                            isPlaying = isPlaying,
                            onClick = { onPlayAt(index) },
                            onMenu = { onTrackMenu(track) },
                            // Opaque, or the removal background bleeds through while swiping.
                            modifier = Modifier.background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        )
                    }
                }
                }
            }
        }
    }
}
