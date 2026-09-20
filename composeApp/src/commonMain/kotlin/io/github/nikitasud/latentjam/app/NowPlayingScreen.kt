/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
import io.github.nikitasud.latentjam.app.generated.resources.info_lyrics
import io.github.nikitasud.latentjam.app.generated.resources.lyrics_not_found
import io.github.nikitasud.latentjam.app.generated.resources.now_playing_next
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
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.PREVIOUS_RESTART_THRESHOLD_MS
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.SleepTimerState
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How much of the queue sheet stays visible under the player. */
private val QueueTitleRowHeight = 40.dp
private val QueueHandleHeight = 18.dp

// Exactly the handle and the title row. Anything more shows a sliver of the first queue
// row under the title, which reads as a stray strip above the navigation bar.
private val QueuePeekHeight = QueueHandleHeight + QueueTitleRowHeight
private val PLAY_PAUSED_RADIUS = 36.dp
private val NEXT_UP_HEIGHT = 36.dp
/** Long enough that skipping through the queue reads no tag on the way; short enough to feel instant. */
private const val LYRICS_PROBE_DELAY_MS = 400L
private val PLAY_PLAYING_RADIUS = 24.dp
private val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)
private data class TrackMetadataPresentation(
    val track: TrackDescriptor?,
    val sourceLabel: String?,
)
private enum class PlayerLyricsContentState { LOADING, MISSING, AVAILABLE }

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
    /** Queue rows keep their own information target rather than turning the current cover. */
    onQueueTrackMenu: (TrackDescriptor) -> Unit = onTrackMenu,
    /** Raised by the queue sheet's save affordance with the CURRENT queue as the selection. */
    onAddQueueToPlaylist: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    /** Bumped by the app when "Information" is chosen from the player's own actions sheet. */
    detailsRequest: Int = 0,
    /** Bumped by the app when "Sleep timer" is chosen from the player's own actions sheet. */
    sleepTimerRequest: Int = 0,
    onGoToAlbum: ((TrackDescriptor) -> Unit)? = null,
    onGoToArtist: ((TrackDescriptor) -> Unit)? = null,
    /** Opens the place the queue came from; false when it cannot be found again. */
    onOpenSource: (() -> Boolean)? = null,
    smartQueueLength: Int = DEFAULT_SMART_QUEUE_LENGTH,
    onSmartQueueLength: (Int) -> Unit = {},
    onEditTags: (TrackDescriptor) -> Unit = {},
    onShowOnMap: ((TrackDescriptor) -> Unit)? = null,
    onClose: () -> Unit,
) {
    // Position is intentionally projected out. It changes twice per second, while artwork, queue,
    // sheet and transport controls normally do not; rebuilding this whole screen for each tick was
    // the largest steady-state source of Compose work during playback.
    val now by remember(playback) {
        playback.state.map { it.copy(positionMs = 0L) }.distinctUntilChanged()
    }.collectAsState(playback.state.value.copy(positionMs = 0L))
    // Only the threshold crossing matters for the backward gesture, never each position tick.
    val previousRestarts by remember(playback) {
        playback.state.map { it.positionMs > PREVIOUS_RESTART_THRESHOLD_MS }.distinctUntilChanged()
    }.collectAsState(playback.state.value.positionMs > PREVIOUS_RESTART_THRESHOLD_MS)
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetScaffoldState()
    var showSleepTimer by remember { mutableStateOf(false) }
    val currentTrack = now.track
    val lyricsSource = currentTrack?.lyricsSourceIdentity()
    val readLyrics = rememberLyricsReader()
    var lyrics by remember(lyricsSource) { mutableStateOf<Lyrics?>(null) }
    var lyricsReadComplete by remember(lyricsSource) { mutableStateOf(false) }
    var showLyrics by remember(lyricsSource) { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    // The cover's back face. Its play count is read once per turn, not observed: the history
    // changes on every listen, and the player must not rebuild for that.
    val currentTrackId = currentTrack?.id
    var flipped by remember(currentTrackId) { mutableStateOf(false) }
    var trackStats by remember(currentTrackId) { mutableStateOf<TrackStats?>(null) }
    // Counters belong to App and outlive this screen. Only new requests should open a surface;
    // returning to the player must not replay the last Information or Sleep timer action.
    val initialDetailsRequest = remember { detailsRequest }
    val initialSleepTimerRequest = remember { sleepTimerRequest }
    LaunchedEffect(detailsRequest) {
        if (detailsRequest != initialDetailsRequest) flipped = true
    }
    LaunchedEffect(sleepTimerRequest) {
        if (sleepTimerRequest != initialSleepTimerRequest) showSleepTimer = true
    }
    LaunchedEffect(flipped, currentTrackId) {
        if (flipped && currentTrackId != null) trackStats = AppGraph.history.stats()[currentTrackId]
    }
    // Pulling the cover down carries the whole surface; read only by the layer below, so the
    // drag never recomposes the screen. On release it springs home or hands over to the morph.
    val collapseOffset = remember { mutableFloatStateOf(0f) }
    var collapseJob by remember { mutableStateOf<Job?>(null) }
    fun settleCollapse(durationMs: Int) {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            animate(
                initialValue = collapseOffset.floatValue,
                targetValue = 0f,
                animationSpec = tween(durationMs),
            ) { value, _ -> collapseOffset.floatValue = value }
        }
    }
    // The lyrics button exists only for songs that carry lyrics, so every track is probed for
    // them once it has settled: a bounded off-main read, delayed past rapid skipping so a run
    // through the queue does not read a tag for every stop on the way. Until the probe answers
    // the button is simply absent, and a song without lyrics never shows it at all.
    LaunchedEffect(lyricsSource) {
        val track = currentTrack ?: return@LaunchedEffect
        delay(LYRICS_PROBE_DELAY_MS)
        lyrics = readLyrics(track)?.takeIf { read -> read.lines.any { it.text.isNotBlank() } }
        lyricsReadComplete = true
    }
    // While lyrics are open, the modal sheet owns Back so it can finish its exit before the parent
    // removes it. Otherwise Back collapses the full player as usual.
    PlatformBackHandler(enabled = !showLyrics, onBack = onClose)

    Surface(
        // Same shared container as the mini-player pill: the pill grows into
        // this screen instead of being swapped for it.
        modifier = if (reduceMotion) {
            Modifier.fillMaxSize().collapsePull(collapseOffset)
        } else with(sharedScope) {
            Modifier
                .fillMaxSize()
                .collapsePull(collapseOffset)
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
                .background(MaterialTheme.colorScheme.surface)
                .playerCloud(accent = accent, playing = now.isPlaying),
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
                // The stock handle pads itself to 48 dp; the peek is a strip, not a toolbar.
                sheetDragHandle = { CompactDragHandle() },
                sheetContent = {
                    QueueSheetContent(
                        queue = now.queue,
                        onExpand = { scope.launch { sheetState.bottomSheetState.expand() } },
                        currentIndex = now.queueIndex,
                        isPlaying = now.isPlaying,
                        canReorder = now.shuffleMode != ShuffleMode.ON,
                        onPlayAt = { index -> scope.launch { playback.playAt(index) } },
                        onTrackMenu = onQueueTrackMenu,
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
                        // Where the sound is going, before the first note says so.
                        val outputRoute = rememberAudioOutputRoute()
                        if (outputRoute != null) {
                            AudioOutputStatus(
                                route = outputRoute,
                                onOpen = rememberAudioOutputChooser(),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (now.track != null) {
                            AnimatedVisibility(
                                visible = lyrics != null,
                                enter = fadeIn(tween(if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS)),
                                exit = fadeOut(tween(if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS)),
                            ) {
                                IconButton(onClick = { showLyrics = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lyrics,
                                        contentDescription = stringResource(Res.string.info_lyrics),
                                    )
                                }
                            }
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
                        // One tap to the full list of actions; the sleep timer lives there too.
                        OverflowButton(
                            sharedScope = sharedScope,
                            animatedScope = animatedScope,
                            onClick = {
                                haptics.play(PlayerHaptic.TAP)
                                now.track?.let(onTrackMenu)
                            },
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The cover owns whatever height is spare and gives it up first: on a
                        // short screen or with large text it shrinks below the width rather than
                        // squeezing the words and controls packed underneath it.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            PlayerArtworkCard(
                                track = now.track,
                                queueIndex = now.queueIndex,
                                skipChangesTrack = { forward ->
                                    val live = playback.state.value
                                    if (forward) live.queue.size > 1 || live.shuffleMode == ShuffleMode.SMART
                                    else live.positionMs <= PREVIOUS_RESTART_THRESHOLD_MS && live.queue.size > 1
                                },
                                flipped = flipped,
                                onFlip = { flipped = it },
                                onHold = { now.track?.let(onTrackMenu) },
                                onSkip = { forward ->
                                    scope.launch { if (forward) playback.next() else playback.previous() }
                                },
                                canSkipForward = now.queueIndex in 0 until now.queue.lastIndex ||
                                    now.repeatMode == RepeatMode.ALL ||
                                    now.shuffleMode == ShuffleMode.SMART,
                                canSkipBackward = now.track != null &&
                                    (previousRestarts || now.queueIndex > 0 || now.repeatMode == RepeatMode.ALL),
                                neighbourArtwork = { forward ->
                                    queueNeighbour(playback.state.value, forward)?.artworkUri
                                },
                                onCollapseDrag = { pulled ->
                                    if (pulled == 0f) {
                                        settleCollapse(Motion.APPEAR_MS)
                                    } else {
                                        collapseJob?.cancel()
                                        collapseOffset.floatValue = pulled
                                    }
                                },
                                onCollapse = {
                                    onClose()
                                    settleCollapse(Motion.EMPHASIZED_MS)
                                },
                                details = {
                                    now.track?.let { track ->
                                        TrackDetailsFace(
                                            track = track,
                                            stats = trackStats,
                                            onEditTags = { onEditTags(track) },
                                            onShowOnMap = onShowOnMap?.let { show -> { show(track) } },
                                            onClose = { flipped = false },
                                        )
                                    }
                                },
                                modifier = if (reduceMotion) Modifier else with(sharedScope) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(ARTWORK_KEY),
                                        animatedScope,
                                        boundsTransform = motionBoundsTransform(),
                                    )
                                },
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

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
                                // Artist and album are places, so they are links: each opens
                                // its own collection. The dash between them stays plain.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                ) {
                                    val artist = shownTrack?.artist
                                    val album = shownTrack?.album?.takeIf { it.isNotBlank() }
                                    MetadataLink(
                                        text = artist,
                                        onClick = if (shownTrack != null && artist != null) {
                                            onGoToArtist?.let { go -> { go(shownTrack) } }
                                        } else null,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (artist != null && album != null) {
                                        Text(
                                            text = " — ",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (album != null) {
                                        MetadataLink(
                                            text = album,
                                            onClick = if (shownTrack != null) {
                                                onGoToAlbum?.let { go -> { go(shownTrack) } }
                                            } else null,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                    }
                                }
                                if (shown.sourceLabel != null && shownTrack != null) {
                                    // A quiet chip: the source reads as "where this track came
                                    // from", and a tap goes there. A source that cannot be found
                                    // again raises the queue it produced instead.
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable(role = Role.Button) {
                                                haptics.play(PlayerHaptic.TAP)
                                                if (onOpenSource?.invoke() != true) {
                                                    scope.launch { sheetState.bottomSheetState.expand() }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = stringResource(
                                                Res.string.now_playing_source,
                                                shown.sourceLabel,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        PlayerSeekBar(playback = playback, durationMs = now.durationMs, lyrics = lyrics)

                        // The transport follows the time labels directly: the labels sit at the
                        // edges and the play button in the middle, so nothing touches. What matters
                        // is the line-to-button distance, kept equal to the button-to-next-up one.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            RepeatButton(mode = now.repeatMode) {
                                scope.launch { playback.cycleRepeatMode() }
                            }
                            SkipButton(forward = false, playback = playback, durationMs = now.durationMs)
                            val playPauseDescription = stringResource(
                                if (now.isPlaying) {
                                    Res.string.action_pause
                                } else {
                                    Res.string.action_play
                                },
                            )
                            // The button says what it is doing with its shape: a circle waits,
                            // a rounded square plays. Colour stays the same, so the state never
                            // depends on it.
                            val playInteraction = remember { MutableInteractionSource() }
                            val playCorner by animateDpAsState(
                                targetValue = if (now.isPlaying) PLAY_PLAYING_RADIUS else PLAY_PAUSED_RADIUS,
                                animationSpec = tween(
                                    if (reduceMotion) Motion.REDUCED_MS else Motion.EMPHASIZED_MS,
                                ),
                                label = "play-shape",
                            )
                            FilledIconButton(
                                onClick = {
                                    haptics.play(PlayerHaptic.TAP)
                                    scope.launch { playback.togglePlayPause() }
                                },
                                shape = RoundedCornerShape(playCorner),
                                interactionSource = playInteraction,
                                modifier = Modifier
                                    .size(72.dp)
                                    .scaleOnPress(playInteraction)
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
                            SkipButton(forward = true, playback = playback, durationMs = now.durationMs)
                            ModeButton(
                                mode = now.shuffleMode,
                                onCycle = { scope.launch { playback.cycleShuffleMode() } },
                                onSelect = { chosen -> scope.launch { playback.setShuffleMode(chosen) } },
                                smartQueueLength = smartQueueLength,
                                onSmartQueueLength = onSmartQueueLength,
                            )
                        }

                        Spacer(modifier = Modifier.height(34.dp))

                        // What comes next, without opening the queue; a tap opens it anyway.
                        NextUpRow(
                            next = nextUpTrack(now),
                            onOpenQueue = {
                                haptics.play(PlayerHaptic.TAP)
                                scope.launch { sheetState.bottomSheetState.expand() }
                            },
                        )

                        Spacer(modifier = Modifier.height(6.dp))
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

    if (showLyrics) {
        val shownTrack = currentTrack
        if (shownTrack != null) {
            PlayerLyricsSheet(
                track = shownTrack,
                lyrics = lyrics,
                loading = !lyricsReadComplete,
                playback = playback,
                onDismiss = { showLyrics = false },
            )
        }
    }
}

/** A focused lyrics surface reached directly from the full player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerLyricsSheet(
    track: TrackDescriptor,
    lyrics: Lyrics?,
    loading: Boolean,
    playback: PlaybackController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissalInFlight by remember { mutableStateOf(false) }
    val contentState = when {
        loading -> PlayerLyricsContentState.LOADING
        lyrics == null -> PlayerLyricsContentState.MISSING
        else -> PlayerLyricsContentState.AVAILABLE
    }

    fun dismiss() {
        if (dismissalInFlight) return
        dismissalInFlight = true
        if (reduceMotion) {
            // Reduced motion deliberately removes the surface in one state change.
            onDismiss()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = !dismissalInFlight,
    ) {
        if (contentState == PlayerLyricsContentState.AVAILABLE && lyrics != null && lyrics.synced) {
            SyncedLyricsBody(
                track = track,
                lyrics = lyrics,
                playback = playback,
                reduceMotion = reduceMotion,
                dismissEnabled = !dismissalInFlight,
                onDismiss = ::dismiss,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 12.dp, bottom = 32.dp),
            ) {
                LyricsSheetHeader(
                    track = track,
                    dismissEnabled = !dismissalInFlight,
                    onDismiss = ::dismiss,
                )
                AnimatedContent(
                    targetState = contentState,
                    transitionSpec = { motionFadeThrough(reduceMotion) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "player-lyrics-content",
                ) { shownState ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .inactiveForMotion(shownState != contentState),
                    ) {
                        when (shownState) {
                            PlayerLyricsContentState.LOADING -> Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                            PlayerLyricsContentState.MISSING -> Text(
                                text = stringResource(Res.string.lyrics_not_found),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp, bottom = 24.dp),
                            )
                            PlayerLyricsContentState.AVAILABLE -> SelectionContainer {
                                Text(
                                    // The outgoing AVAILABLE subtree can live for one final frame
                                    // if its parent is disposed during a source change; keep that
                                    // frame drawable without asserting against the newer state.
                                    text = lyrics?.text.orEmpty(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSheetHeader(
    track: TrackDescriptor,
    dismissEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.info_lyrics),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, enabled = dismissEnabled) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.action_close),
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, end = 12.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Artwork(uri = track.artworkUri, size = 56.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title ?: stringResource(Res.string.track_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist ?: stringResource(Res.string.track_unknown_artist),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Timed lyrics follow the song: the line being sung is emphasised, the list keeps it about a
 * third of the way down the sheet, and a tap on any timed line seeks there. The player's
 * position ticker is coarse (half a second), so the shown position is interpolated between
 * ticks; a finger on the list pauses the auto-scroll for a few seconds so reading ahead is
 * not fought.
 */
@Composable
private fun SyncedLyricsBody(
    track: TrackDescriptor,
    lyrics: Lyrics,
    playback: PlaybackController,
    reduceMotion: Boolean,
    dismissEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val positionMs by remember(playback) {
        playback.state.map { it.positionMs }.distinctUntilChanged()
    }.collectAsState(playback.state.value.positionMs)
    val isPlaying by remember(playback) {
        playback.state.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsState(playback.state.value.isPlaying)
    var shownPositionMs by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        shownPositionMs = positionMs
        if (!isPlaying) return@LaunchedEffect
        val since = TimeSource.Monotonic.markNow()
        while (true) {
            delay(LYRICS_TICK_MS)
            shownPositionMs = positionMs + since.elapsedNow().inWholeMilliseconds
        }
    }
    val lines = lyrics.lines
    val timeline = remember(lines) { LyricsTimeline(lines) }
    val activeIndex by remember(timeline) {
        derivedStateOf { timeline.activeIndexAt(shownPositionMs) }
    }

    val listState = rememberLazyListState()
    var touching by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableStateOf<TimeMark?>(null) }
    LaunchedEffect(listState, reduceMotion, timeline) {
        followLyrics(
            requests = snapshotFlow {
                LyricsFollowRequest(
                    activeIndex = activeIndex,
                    interacting = touching,
                    interactionEndedAt = lastTouch,
                    viewportHeight = listState.layoutInfo.viewportSize.height,
                    reduceMotion = reduceMotion,
                )
            },
        ) { request ->
            val offset = -(request.viewportHeight * LYRICS_ACTIVE_LINE_FRACTION).toInt()
            val target = LYRICS_HEADER_ITEMS + request.activeIndex
            if (request.reduceMotion) {
                listState.scrollToItem(target, offset)
            } else {
                listState.animateScrollToItem(target, offset)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(LYRICS_SHEET_HEIGHT_FRACTION)
            .navigationBarsPadding()
            .pointerInput(Unit) {
                // Observe from the first finger contact, without consuming taps or scrolling.
                // Drag interactions arrive after touch slop and miss a held, stationary finger.
                // A tap is neither: it seeks to a line and wants the list to follow at once,
                // so only a drag or a finger held past the long-press timeout arms the hold.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val pressedAt = TimeSource.Monotonic.markNow()
                    var moved = false
                    touching = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (!moved) {
                                moved = event.changes.any { change ->
                                    (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        touching = false
                        val held = pressedAt.elapsedNow().inWholeMilliseconds >=
                            viewConfiguration.longPressTimeoutMillis
                        if (moved || held) lastTouch = TimeSource.Monotonic.markNow()
                    }
                }
            },
        contentPadding = PaddingValues(start = 24.dp, end = 12.dp, bottom = 160.dp),
    ) {
        item(key = "lyrics-header") {
            LyricsSheetHeader(track = track, dismissEnabled = dismissEnabled, onDismiss = onDismiss)
        }
        itemsIndexed(items = lines, key = { index, _ -> index }) { index, line ->
            val active = index == activeIndex
            val color by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                label = "lyric-line-colour",
            )
            val seekTo = line.timeMs
            Text(
                // A timed but wordless line is an instrumental gap; show that it is one.
                text = line.text.ifBlank { if (seekTo != null) "♪" else "" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (seekTo != null) {
                            Modifier.clickable { scope.launch { playback.seekTo(seekTo) } }
                        } else {
                            Modifier
                        },
                    )
                    .padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
}

private const val LYRICS_TICK_MS = 120L

private const val LYRICS_HEADER_ITEMS = 1
private const val LYRICS_ACTIVE_LINE_FRACTION = 0.35f
private const val LYRICS_SHEET_HEIGHT_FRACTION = 0.85f


/**
 * The next track as one quiet line under the transport: its cover, "Next: title — artist", and a
 * chevron. Absent at a hard end of the queue. Changes fade through like the metadata above.
 */
@Composable
private fun NextUpRow(next: TrackDescriptor?, onOpenQueue: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    AnimatedContent(
        targetState = next,
        contentKey = { it?.id },
        transitionSpec = { motionFadeThrough(reduceMotion) },
        modifier = Modifier.fillMaxWidth(),
        label = "next-up",
    ) { shown ->
        if (shown == null) {
            Spacer(modifier = Modifier.height(NEXT_UP_HEIGHT))
        } else {
            val title = shown.title ?: stringResource(Res.string.track_untitled)
            val artist = shown.artist ?: stringResource(Res.string.track_unknown_artist)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NEXT_UP_HEIGHT)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onOpenQueue)
                    .inactiveForMotion(shown.id != next?.id)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(uri = shown.artworkUri, size = 28.dp, cornerRadius = 6.dp)
                Text(
                    text = stringResource(Res.string.now_playing_next, "$title — $artist"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A 4 dp pill with just enough room to find it, instead of the stock handle's 48 dp of padding. */
@Composable
private fun CompactDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueHandleHeight)
            .padding(top = 8.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/** Artist or album under the title: a link when there is somewhere to go, plain text otherwise. */
@Composable
private fun MetadataLink(text: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textDecoration = if (onClick != null) TextDecoration.Underline else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (onClick != null) {
            modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        } else {
            modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        },
    )
}

/** The track a swipe in that direction reaches, honouring queue repeat; null at a hard end. */
internal fun queueNeighbour(now: NowPlaying, forward: Boolean): TrackDescriptor? {
    val size = now.queue.size
    if (now.queueIndex !in now.queue.indices) return null
    if (!forward && now.positionMs > PREVIOUS_RESTART_THRESHOLD_MS) return now.track
    // At a SMART tail the next recommendation may not exist yet. Do not promise a wrap that
    // the chooser can replace before the gesture or the natural transition lands.
    if (forward && now.shuffleMode == ShuffleMode.SMART && now.queueIndex == now.queue.lastIndex) {
        return null
    }
    val step = if (forward) 1 else -1
    val raw = now.queueIndex + step
    val index = if (now.repeatMode == RepeatMode.ALL) ((raw % size) + size) % size else raw
    return now.queue.getOrNull(index)
}

/** Natural completion repeats the current track in repeat-one; a manual skip still advances. */
internal fun nextUpTrack(now: NowPlaying): TrackDescriptor? =
    if (now.repeatMode == RepeatMode.ONE) now.track else queueNeighbour(now, forward = true)

/** The pull-down: the surface follows the finger and shrinks a little towards its bottom edge. */
private fun Modifier.collapsePull(offset: androidx.compose.runtime.MutableFloatState): Modifier =
    graphicsLayer {
        val pulled = offset.floatValue
        translationY = pulled
        val scale = 1f - (pulled / COLLAPSE_SCALE_DIVISOR).coerceIn(0f, COLLAPSE_MAX_SHRINK)
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0.5f, 1f)
    }

private const val COLLAPSE_SCALE_DIVISOR = 3_200f
private const val COLLAPSE_MAX_SHRINK = 0.1f

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
    onExpand: () -> Unit = {},
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
        val expandLabel = stringResource(Res.string.queue_title)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(QueueTitleRowHeight)
                .clickable(role = Role.Button, onClickLabel = expandLabel, onClick = onExpand)
                .padding(bottom = 4.dp),
        ) {
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
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                label = "save-queue",
            ) { canSave ->
                Box(modifier = Modifier.size(QueueTitleRowHeight), contentAlignment = Alignment.Center) {
                    if (canSave) {
                        // A queue worth keeping — often SMART's work — becomes a playlist in two taps.
                        IconButton(onClick = onSaveQueue, modifier = Modifier.size(QueueTitleRowHeight)) {
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
