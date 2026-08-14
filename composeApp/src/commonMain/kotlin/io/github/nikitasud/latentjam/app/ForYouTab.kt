/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.foryou_caption_played_before
import io.github.nikitasud.latentjam.app.generated.resources.foryou_empty
import io.github.nikitasud.latentjam.app.generated.resources.foryou_kicker_never_played
import io.github.nikitasud.latentjam.app.generated.resources.foryou_kicker_played_times
import io.github.nikitasud.latentjam.app.generated.resources.foryou_kicker_resume
import io.github.nikitasud.latentjam.app.generated.resources.foryou_section_continue
import io.github.nikitasud.latentjam.app.generated.resources.foryou_section_never_played
import io.github.nikitasud.latentjam.app.generated.resources.foryou_section_worlds
import io.github.nikitasud.latentjam.app.generated.resources.foryou_section_worth_revisiting
import io.github.nikitasud.latentjam.app.generated.resources.foryou_world_open
import io.github.nikitasud.latentjam.app.generated.resources.foryou_world_smart
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * For You: what to play, drawn from what you own and how you have listened.
 *
 * This is not discovery — there is no catalogue to discover. It is rediscovery and decision support:
 * things you own but forgot, and a shorter path than scrolling 850 tracks. A row therefore earns its
 * place only by showing something you could not have reached by browsing; anything a plain sort
 * would produce belongs on the Tracks tab, not here.
 *
 * The page is built once per visit and does not re-rank underneath the reader. A surface that
 * reshuffles while being read is indistinguishable from a broken one, and repeated exposure is what
 * turns a suggestion into a play.
 */
@Composable
fun ForYouTab(
    page: ForYouPage,
    contentPadding: PaddingValues,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onPlay: (List<TrackDescriptor>, Int) -> Unit,
    onPlayHero: (ForYouHero) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onOpenWorld: (ForYouCard) -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (page.isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.foryou_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                page.hero?.let { hero ->
                    item(key = "hero") {
                        Box(
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(
                                    if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                                ),
                                placementSpec = if (reduceMotion) null else tween(Motion.APPEAR_MS),
                                fadeOutSpec = tween(
                                    if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                                ),
                            ),
                        ) {
                            HeroCard(hero, onPlay = { onPlayHero(hero) })
                        }
                    }
                }
                items(page.sections, key = { it.kind.name }) { section ->
                    Column(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                            ),
                            placementSpec = if (reduceMotion) null else tween(Motion.APPEAR_MS),
                            fadeOutSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                            ),
                        ),
                    ) {
                    Text(
                        text = section.kind.title(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 20.dp,
                            bottom = 10.dp,
                        ),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                    ) {
                        items(section.cards, key = { it.track.id.value }) { card ->
                            Box(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(
                                        if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                                    ),
                                    placementSpec = if (reduceMotion) {
                                        null
                                    } else {
                                        tween(Motion.APPEAR_MS)
                                    },
                                    fadeOutSpec = tween(
                                        if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                                    ),
                                ),
                            ) {
                            ForYouCardItem(
                                card = card,
                                onClick = {
                                    when {
                                        // A world is a hundred-odd tracks, and there are two
                                        // reasonable things to do with one. Guessing which was the
                                        // previous build's mistake: it played immediately, and the
                                        // tap that meant "show me this" was indistinguishable from
                                        // the tap that meant "play it".
                                        section.kind == ForYouSectionKind.WORLDS -> onOpenWorld(card)
                                        // A collection card plays its own contents; a track card
                                        // plays the row from that point, so the rest of the shelf
                                        // stays available.
                                        card.collection != null -> onPlay(card.collection.tracks, 0)
                                        else -> {
                                            val tracks = section.cards
                                                .filter { it.collection == null }
                                                .map { it.track }
                                            onPlay(
                                                tracks,
                                                tracks.indexOfFirst { it.id == card.track.id },
                                            )
                                        }
                                    }
                                },
                                onLongClick = { onTrackMenu(card.track) },
                            )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

/**
 * The builder emits a section KIND; the heading is written here.
 *
 * Keeping the two apart is what lets the page rules stay a pure, unit-tested function while the
 * words it implies still arrive in the reader's language.
 */
@Composable
private fun ForYouSectionKind.title(): String = stringResource(
    when (this) {
        ForYouSectionKind.CONTINUE -> Res.string.foryou_section_continue
        ForYouSectionKind.WORTH_REVISITING -> Res.string.foryou_section_worth_revisiting
        ForYouSectionKind.WORLDS -> Res.string.foryou_section_worlds
        ForYouSectionKind.NEVER_PLAYED -> Res.string.foryou_section_never_played
    },
)

/**
 * The two things a world is good for, asked rather than assumed.
 *
 * A region of the library is not one record, so tapping it cannot mean one thing. Offering the
 * choice costs a tap and buys the difference between browsing and committing — the previous
 * implementation played on touch, and the way back from a hundred-track queue you did not ask for
 * is long enough that people stopped touching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldActionsSheet(
    card: ForYouCard,
    onOpen: () -> Unit,
    onStartSmart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val world = card.collection ?: return
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissalInFlight by remember { mutableStateOf(false) }

    fun dismissThen(action: () -> Unit) {
        if (dismissalInFlight) return
        dismissalInFlight = true
        if (reduceMotion) {
            onDismiss()
            action()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
                action()
            }
        }
    }

    fun dismissWhile(action: () -> Unit) {
        if (dismissalInFlight) return
        dismissalInFlight = true
        action()
        if (reduceMotion) {
            onDismiss()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissThen {} },
        sheetState = sheetState,
        sheetGesturesEnabled = !dismissalInFlight,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Artwork(uri = card.track.artworkUri, size = 56.dp, cornerRadius = 12.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = world.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pluralStringResource(
                            Res.plurals.count_tracks,
                            world.tracks.size,
                            world.tracks.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SheetAction(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = stringResource(Res.string.foryou_world_open),
                onClick = { dismissThen(onOpen) },
            )
            // Seeded from the cover, which is the world's most central track — so the journey
            // starts from the middle of the region rather than from its edge.
            SheetAction(
                icon = Icons.Rounded.AutoAwesome,
                label = stringResource(Res.string.foryou_world_smart),
                onClick = { dismissWhile(onStartSmart) },
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Card captions. Both carry a count, so both go through the plural machinery rather than through
 * string concatenation — the difference between "1 трек" and "5 треков" is not a suffix.
 */
@Composable
private fun ForYouCaption.text(): String = when (this) {
    is ForYouCaption.PlayedBefore ->
        pluralStringResource(Res.plurals.foryou_caption_played_before, plays, plays)
    is ForYouCaption.TrackCount ->
        pluralStringResource(Res.plurals.count_tracks, tracks, tracks)
}

@Composable
private fun ForYouKicker.text(): String = when (this) {
    ForYouKicker.Resume -> stringResource(Res.string.foryou_kicker_resume)
    is ForYouKicker.PlayedTimes ->
        pluralStringResource(Res.plurals.foryou_kicker_played_times, plays, plays)
    ForYouKicker.NeverPlayed -> stringResource(Res.string.foryou_kicker_never_played)
}

/**
 * A card is mostly its cover.
 *
 * Row titles are read about a quarter as often as the leftmost item is looked at, so the artwork
 * carries the row and the text underneath is support. Both text slots are fixed at one line so cards
 * keep a common baseline whatever the title length — ragged card heights were a recurring complaint
 * in the previous implementation, and a fixed slot is the fix rather than ellipsizing after layout.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForYouCardItem(
    card: ForYouCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // The whole card is the touch target, but the press ripple is shown only on the cover — a
    // default clickable would paint the entire column, text and all, with a grey rectangle on
    // every tap. The interaction source is shared so the cover lights up while the column
    // handles the gesture. combinedClickable also finally wires the long-press: the old
    // clickable() dropped onLongClick, so opening a card's menu never worked.
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(140.dp)
            .scaleOnPress(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Artwork(
            uri = card.track.artworkUri,
            size = 140.dp,
            cornerRadius = 12.dp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .indication(interaction, ripple()),
        )
        Text(
            text = card.collection?.title
                ?: card.track.title
                ?: stringResource(Res.string.track_untitled),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        // The caption replaces the artist where there is one, because "10× before" is the reason
        // this card is here and the artist is already legible from the cover.
        val caption = card.caption
        Text(
            text = if (caption != null) {
                caption.text()
            } else {
                card.track.artist ?: stringResource(Res.string.track_unknown_artist)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The one-tap card.
 *
 * Sized so it and the first row's covers share the opening screen — the point is a decision made
 * without scrolling, and a card that fills the viewport would hide the alternatives that make the
 * choice feel like a choice. Playing it hands the track to SMART as a seed rather than queuing it
 * alone: the personal signal decides what to start from, the model decides what follows.
 */
@Composable
private fun HeroCard(hero: ForYouHero, onPlay: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .scaleOnPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onPlay,
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Artwork(uri = hero.track.artworkUri, size = 96.dp, cornerRadius = 14.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hero.kicker.text(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hero.track.title ?: stringResource(Res.string.track_untitled),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = hero.track.artist ?: stringResource(Res.string.track_unknown_artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Icon-only and fixed size: a labelled button here clips under translation and large
            // font scales, which is a mistake this project has already made three times.
            FilledIconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(Res.string.action_play),
                )
            }
        }
    }
}
