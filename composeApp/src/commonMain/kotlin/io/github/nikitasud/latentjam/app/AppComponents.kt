/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_add
import io.github.nikitasud.latentjam.app.generated.resources.action_next
import io.github.nikitasud.latentjam.app.generated.resources.action_pause
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_previous
import io.github.nikitasud.latentjam.app.generated.resources.action_favorite_short
import io.github.nikitasud.latentjam.app.generated.resources.action_unfavorite_short
import io.github.nikitasud.latentjam.app.generated.resources.cd_more_options
import io.github.nikitasud.latentjam.app.generated.resources.count_albums
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.library_empty
import io.github.nikitasud.latentjam.app.generated.resources.library_import_action
import io.github.nikitasud.latentjam.app.generated.resources.library_import_hint
import io.github.nikitasud.latentjam.app.generated.resources.settings_title
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_album
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_genre
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.history.ForYouImpression
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.AlbumSorting
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.FolderGroup
import io.github.nikitasud.latentjam.library.GenreGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.layout.layout

@Composable
internal fun EmptyLibraryPage(
    contentPadding: PaddingValues,
    canImportAudio: Boolean,
    onImportAudio: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.library_empty))
        if (canImportAudio) {
            Text(
                text = stringResource(Res.string.library_import_hint),
                modifier = Modifier.padding(start = 32.dp, top = 8.dp, end = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onImportAudio,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(stringResource(Res.string.library_import_action))
            }
        }
    }
}

/** Whether the player has already begun to cover the library for the current open. */
internal class CoverStart {
    var begun = false
}

/** Swallows every touch while [blocked] holds; read per event, so it never recomposes. */
internal fun Modifier.blockTouchesWhile(blocked: () -> Boolean): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (blocked()) event.changes.forEach { it.consume() }
        }
    }
}

/** Outgoing full-screen content stays drawable but cannot retain focus or receive a late tap. */
internal fun Modifier.inactiveDuringTransition(inactive: Boolean): Modifier = if (!inactive) {
    this
} else {
    unfocusable()
        .clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
}

/** Static modal floor beneath a moving page, preventing taps through temporarily uncovered space. */
@Composable
internal fun ModalPointerBlocker() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/** Map data may refresh within READY; only a real status boundary should fade the surface. */
internal fun MapPageState.presentationKey(): String = when (this) {
    MapPageState.Indexing -> "indexing"
    MapPageState.Building -> "building"
    is MapPageState.TooLarge -> "too-large"
    MapPageState.MemoryLimited -> "memory-limited"
    MapPageState.Unavailable -> "unavailable"
    is MapPageState.Ready -> "ready"
}

/** Group pages support track selection regardless of their position in the carousel. */
internal val GROUP_TABS = setOf(StartPage.ALBUMS, StartPage.ARTISTS, StartPage.GENRES, StartPage.FOLDERS)

/**
 * Pages that list or recommend the library carry the floating Shuffle all. Statistics and the Map
 * describe the library rather than lead into it, so the button stays off them.
 */
internal val SHUFFLE_ALL_PAGES = GROUP_TABS + setOf(StartPage.FOR_YOU, StartPage.PLAYLISTS, StartPage.TRACKS)

internal fun StartPage.showsShuffleAll(): Boolean = this in SHUFFLE_ALL_PAGES

/** Persist-and-report granularity for library indexing. */
// Weeks of context, not days: the daypart and phase sections fold over this window, and at a
// heavy listener's ~80 events/day, 500 events was six days — too short to know what a morning is.
internal const val RECENT_EVENTS_FOR_YOU = 4000

/** How much of the upcoming queue For You leaves out: about the next hour and a half of music. */
internal const val FOR_YOU_QUEUED_EXCLUSION = 25

// The full player owns precise seeking; the browse pill updates this glanceable hint less often so
// playback does not invalidate the browse shell every 500 ms.
internal const val MINI_PLAYER_PROGRESS_STEP_MS = 1_000L

/**
 * The pill's own height, above whatever navigation-bar inset it is sitting on.
 *
 * Lists add this to the inset so their last row clears the pill instead of hiding under it. A
 * constant rather than a measurement: the pill is a fixed piece of furniture, and measuring it
 * would make every list's padding depend on a layout pass it does not otherwise wait for.
 */
internal val MINI_PLAYER_HEIGHT = 88.dp

/** Height of the contextual action row above the system navigation inset. */
internal val SELECTION_ACTION_BAR_HEIGHT = 72.dp

// A selection is assembled in a click handler, which is not composition — so these resolve their
// strings through the suspending resource API and are called from a coroutine. The alternative,
// keeping English in the model and translating it on the way out, is what this pass exists to undo.

internal suspend fun AlbumGroup.toSelection(): CollectionSelection {
    // The catalog already holds the album in release order; re-sorting by title here is what
    // used to play every album alphabetically.
    val ordered = tracks
    return CollectionSelection(
        title = title ?: getString(Res.string.track_unknown_album),
        subtitle = artist,
        artworkUri = artworkUri,
        tracks = ordered,
        railMode = trackRailModeFor(ordered),
        allowsTrackSelection = true,
        routeId = "album:$key",
    )
}

internal suspend fun ArtistGroup.toSelection(albumOrder: SortChoice<AlbumSort>): CollectionSelection {
    // Albums are an artist's natural chapters. The flat list stays exactly the sections'
    // concatenation, so playback and selection keep working in flat indices.
    val unknownAlbum = getString(Res.string.track_unknown_album)
    val sections = withContext(Dispatchers.Default) {
        artistAlbumSections(tracks, albumOrder, unknownAlbum)
    }
    val ordered = sections.flatMap { it.tracks }
    return CollectionSelection(
        title = name ?: getString(Res.string.track_unknown_artist),
        subtitle = getPluralString(Res.plurals.count_tracks, tracks.size, tracks.size) +
            SUBTITLE_SEPARATOR +
            getPluralString(Res.plurals.count_albums, albumCount, albumCount),
        artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
        tracks = ordered,
        // A single bucket is a flat list wearing a pointless header.
        sections = sections.takeIf { it.size > 1 },
        railMode = artistRailMode(sections, albumOrder, ordered),
        allowsTrackSelection = true,
        routeId = "artist:${name.orEmpty()}",
    )
}

/**
 * An open artist page re-sorted to [albumOrder]; everything else about it — its cover, its title,
 * its route — stays, so the page neither replays its entrance nor swaps its artwork.
 */
internal suspend fun CollectionSelection.withArtistAlbumOrder(
    albumOrder: SortChoice<AlbumSort>,
): CollectionSelection {
    val unknownAlbum = getString(Res.string.track_unknown_album)
    val sections = withContext(Dispatchers.Default) {
        artistAlbumSections(tracks, albumOrder, unknownAlbum)
    }
    val ordered = sections.flatMap { it.tracks }
    return copy(
        tracks = ordered,
        sections = sections.takeIf { it.size > 1 },
        railMode = artistRailMode(sections, albumOrder, ordered),
    )
}

/**
 * An artist's [tracks] as one chapter per album, the chapters in [albumOrder] and each album's
 * tracks in disc and track order. Two releases sharing a title ("Weezer" and "Weezer") are two
 * chapters, each dated by its own tracks.
 */
internal fun artistAlbumSections(
    tracks: List<TrackDescriptor>,
    albumOrder: SortChoice<AlbumSort>,
    unknownAlbum: String,
): List<CollectionSection> {
    val chapters = tracks
        .groupBy { it.album }
        .flatMap { (album, grouped) ->
            LibraryCatalog.separateReleases(grouped).map { release -> album to LibraryCatalog.inAlbumOrder(release) }
        }
    // Every chapter is this artist's, so the album sort's artist tie-break is moot; its final
    // tie-break, the key, keeps chapters that tie on everything in the order they were found.
    val albums = chapters.mapIndexed { index, (album, release) ->
        AlbumGroup(
            key = index.toString().padStart(10, '0'),
            title = album,
            artist = null,
            artworkUri = null,
            tracks = release,
        )
    }
    // Loose tracks are not an album of the artist's, so they close the page whatever the order:
    // dated by their own tags, they would otherwise land mid-discography under Year.
    return AlbumSorting.sort(albums, albumOrder.sort, albumOrder.direction)
        .sortedBy { it.title == null }
        .map { album ->
            CollectionSection(
                title = album.title ?: unknownAlbum,
                tracks = album.tracks,
                railTitle = album.title,
            )
        }
}

/** Album titles index an alphabetical discography; release years are not letters. */
internal fun artistRailMode(
    sections: List<CollectionSection>,
    albumOrder: SortChoice<AlbumSort>,
    ordered: List<TrackDescriptor>,
): CollectionRailMode = when {
    sections.size <= 1 -> trackRailModeFor(ordered)
    albumOrder.sort == AlbumSort.TITLE -> CollectionRailMode.SECTION_TITLES
    else -> CollectionRailMode.NONE
}

internal suspend fun GenreGroup.toSelection(): CollectionSelection {
    val ordered = withContext(Dispatchers.Default) {
        SongSorting.sort(tracks, SongSort.TITLE, SongSortDirection.ASCENDING)
    }
    return CollectionSelection(
        title = name ?: getString(Res.string.track_unknown_genre),
        subtitle = trackCountLabel(tracks.size),
        artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
        tracks = ordered,
        railMode = CollectionRailMode.TRACK_TITLES,
        allowsTrackSelection = true,
        routeId = "genre:${name.orEmpty()}",
    )
}

internal suspend fun FolderGroup.toSelection(): CollectionSelection {
    val ordered = withContext(Dispatchers.Default) {
        SongSorting.sort(tracks, SongSort.TITLE, SongSortDirection.ASCENDING)
    }
    return CollectionSelection(
        title = name,
        subtitle = path + SUBTITLE_SEPARATOR + trackCountLabel(tracks.size),
        artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
        tracks = ordered,
        railMode = CollectionRailMode.TRACK_TITLES,
        allowsTrackSelection = true,
        routeId = "folder:$path",
    )
}

internal suspend fun trackCountLabel(count: Int): String =
    getPluralString(Res.plurals.count_tracks, count, count)

/**
 * Joins two counted phrases in a subtitle.
 *
 * Punctuation rather than a word, so it needs no translation and survives being read
 * right-to-left; the two halves around it are each separately pluralised.
 */
internal const val SUBTITLE_SEPARATOR = " • "

@Composable
internal fun artistSubtitle(tracks: Int, albums: Int): String =
    pluralStringResource(Res.plurals.count_tracks, tracks, tracks) +
        SUBTITLE_SEPARATOR +
        pluralStringResource(Res.plurals.count_albums, albums, albums)

// ---------------------------------------------------------------- components

/**
 * The page header, in the [style] chosen in Settings > Appearance.
 *
 * - [PageHeaderStyle.DOTS]: one title, centred, and a row of dots for where it sits among the
 *   pages. Neighbouring titles rest off the strip and only slide in under the finger.
 * - [PageHeaderStyle.NEIGHBOURS]: the neighbours sit beside the title, close to it, and each
 *   shrinks only as far as it must to fit whole between the title and the strip's edge.
 * - [PageHeaderStyle.TABS]: every page's name in one row that scrolls to keep the current one
 *   centred over a short bar.
 * - [PageHeaderStyle.PILLS]: the same row as outlined pills, the current one filled.
 * - [PageHeaderStyle.MENU]: one centred title with a chevron; tapping it lists every page.
 *
 * Neighbouring titles once sat beside the active one at a fixed scale and, measured across the
 * 18 locales, fit whole only about two times in three at 390 dp: long words were sliced by the edge.
 * Every style here keeps whole words, or moves the words somewhere they have room.
 */
@Composable
internal fun BrowseCarousel(
    pagerState: PagerState,
    pages: List<StartPage>,
    onSelect: (StartPage) -> Unit,
    modifier: Modifier = Modifier,
    style: PageHeaderStyle = PageHeaderStyle.DOTS,
) {
    val dots = style == PageHeaderStyle.DOTS
    val row = style == PageHeaderStyle.TABS || style == PageHeaderStyle.PILLS
    // Dots: title line, a 7 dp gap, the 5 dp dots, 8 dp above and below. Others: one line, centred.
    val headerHeight = with(LocalDensity.current) {
        maxOf(56.dp, 28.sp.toDp() + if (dots) 28.dp else 20.dp)
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val pillCorner = (PagePillHeight / 2).corner()
    var menuOpen by remember { mutableStateOf(false) }
    // Pill widths from the last measure, for the moving fill drawn behind them.
    val pillWidths = remember { ArrayList<Float>() }
    val titleStyle = when (style) {
        PageHeaderStyle.TABS -> MaterialTheme.typography.titleMedium
        PageHeaderStyle.PILLS -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    }
    Box(modifier) {
        // Only these few labels participate in pager motion. Measure once, then read the finger's
        // position during placement/drawing so swiping never recomposes the browsing lists.
        Layout(
            modifier = Modifier.testTag("latentjam_browse_header").fillMaxWidth().height(headerHeight).clipToBounds()
                // Only this small header strip is isolated; the scrolling page never needs an
                // offscreen texture. Masking alpha reveals the existing song-coloured background.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val fadeWidth = if (row) PageRowEdgeFade else PageEdgeFade
                    val edge = (fadeWidth.toPx() / size.width.coerceAtLeast(1f)).coerceAtMost(0.25f)
                    val mask = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        edge to Color.Black,
                        (1f - edge) to Color.Black,
                        1f to Color.Transparent,
                    )
                    val dot = PageDotSize.toPx()
                    val gap = PageDotGap.toPx()
                    val stretch = PageDotActiveWidth.toPx() - dot
                    val dotsY = size.height - PageDotsBottom.toPx() - dot
                    val bar = Size(PageTabBarWidth.toPx(), PageTabBarHeight.toPx())
                    val barY = size.height / 2f + PageTabBarOffset.toPx()
                    val pillHeight = PagePillHeight.toPx()
                    val pillRadius = pillCorner.toPx()
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    onDrawWithContent {
                        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        if (style == PageHeaderStyle.PILLS) {
                            // The current page's fill, always centred; its width follows the finger.
                            val widths = pillWidths
                            if (widths.isNotEmpty()) {
                                val p = position.coerceIn(0f, widths.lastIndex.toFloat())
                                val from = p.toInt()
                                val to = (from + 1).coerceAtMost(widths.lastIndex)
                                val w = widths[from] + (widths[to] - widths[from]) * (p - from)
                                drawRoundRect(
                                    color = ink.copy(alpha = 0.16f),
                                    topLeft = Offset((size.width - w) / 2f, (size.height - pillHeight) / 2f),
                                    size = Size(w, pillHeight),
                                    cornerRadius = CornerRadius(pillRadius),
                                )
                            }
                        }
                        drawContent()
                        if (style == PageHeaderStyle.TABS) {
                            drawRoundRect(
                                color = ink,
                                topLeft = Offset((size.width - bar.width) / 2f, barY),
                                size = bar,
                                cornerRadius = CornerRadius(bar.height / 2f),
                            )
                        }
                        drawRect(mask, blendMode = BlendMode.DstIn)
                        if (dots && pages.size > 1) {
                            // The stretched width moves between two dots, so the row's width never changes.
                            val total = pages.size * dot + (pages.size - 1) * gap + stretch
                            var x = (size.width - total) / 2f
                            for (i in pages.indices) {
                                val nearness = 1f - abs(i - position).coerceIn(0f, 1f)
                                val width = dot + stretch * nearness
                                val left = if (rtl) size.width - x - width else x
                                drawRoundRect(
                                    color = ink.copy(alpha = 0.28f + 0.72f * nearness),
                                    topLeft = Offset(left, dotsY),
                                    size = Size(width, dot),
                                    cornerRadius = CornerRadius(dot / 2f),
                                )
                                x += width + gap
                            }
                        }
                    }
                }
                .browseHeaderSwipe(pagerState, enabled = pages.size > 1),
            content = {
                pages.forEachIndexed { index, page ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val label = Modifier
                        .testTag("latentjam_page_${page.name.lowercase()}")
                        .then(
                            // In Menu style the current title opens the list of pages: a drop-down,
                            // not a selected tab, and it says whether the list is open.
                            if (style == PageHeaderStyle.MENU && index == pagerState.currentPage) {
                                Modifier.pageMenuAnchor(menuOpen, interactionSource) { menuOpen = it }
                            } else {
                                Modifier.selectable(
                                    selected = index == pagerState.currentPage,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onSelect(page) },
                                )
                            },
                        )
                        // A full 48 dp to hit; the drawn pill or title stays its own size, centred.
                        // Pills grow in height only: their widths drive the sliding fill, which
                        // must stay as wide as a one-glyph pill such as Japanese 曲.
                        .then(
                            if (style == PageHeaderStyle.PILLS) Modifier.minimumTouchHeight()
                            else Modifier.minimumInteractiveComponentSize(),
                        )
                    when (style) {
                        PageHeaderStyle.PILLS -> Text(
                            text = stringResource(page.titleResource()),
                            style = titleStyle,
                            maxLines = 1,
                            color = ink,
                            modifier = label
                                .height(PagePillHeight)
                                .border(1.dp, outline, RoundedCornerShape(pillCorner))
                                .wrapContentHeight()
                                .padding(horizontal = 14.dp),
                        )
                        PageHeaderStyle.MENU -> Row(
                            modifier = label.padding(start = 12.dp, end = 4.dp, top = PageTitlePadding, bottom = PageTitlePadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(page.titleResource()),
                                style = titleStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = ink,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> Text(
                            text = stringResource(page.titleResource()),
                            style = titleStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = ink,
                            modifier = label.padding(
                                horizontal = if (style == PageHeaderStyle.TABS) 12.dp else PageTitleSidePadding,
                                vertical = PageTitlePadding,
                            ),
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val labels = measurables.map {
                it.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
            }
            pillWidths.clear()
            if (style == PageHeaderStyle.PILLS) labels.mapTo(pillWidths) { it.width.toFloat() }
            val width = constraints.maxWidth.toFloat()
            val fade = PageEdgeFade.toPx()
            val spacing = if (style == PageHeaderStyle.PILLS) PagePillGap.toPx() else 0f
            val top = if (dots) {
                // The 48 dp hit area is taller than the title; keep the title itself where it was.
                val natural = if (titleStyle.lineHeight.isSp) {
                    titleStyle.lineHeight.toPx() + 2 * PageTitlePadding.toPx()
                } else {
                    Float.MAX_VALUE
                }
                val grown = ((labels.maxOfOrNull { it.height } ?: 0) - natural).coerceAtLeast(0f)
                (PageTitleTop - PageTitlePadding).roundToPx() - (grown / 2f).roundToInt()
            } else {
                (constraints.maxHeight - (labels.maxOfOrNull { it.height } ?: 0)) / 2
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (labels.isEmpty()) return@layout
                val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, labels.lastIndex.toFloat())
                val from = position.toInt()
                val to = (from + 1).coerceAtMost(labels.lastIndex)
                val fraction = position - from
                if (dots || style == PageHeaderStyle.MENU) {
                    labels.forEachIndexed { index, label ->
                        val distance = abs(index - position).coerceIn(0f, 1f)
                        val centre = width / 2f + (index - position) * width
                        label.placeRelativeWithLayer((centre - label.width / 2f).roundToInt(), top) {
                            scaleX = 1f - 0.26f * distance
                            scaleY = scaleX
                            alpha = 1f - distance
                        }
                    }
                    return@layout
                }
                // A neighbour of [active] shrinks to fit whole between that title and the faded edge.
                fun scaleBeside(index: Int, active: Int): Float = when {
                    row || index == active -> 1f
                    else -> {
                        val room = width / 2f - labels[active].width / 2f - fade
                        (room / labels[index].width).coerceIn(MIN_NEIGHBOUR_SCALE, NEIGHBOUR_SCALE)
                    }
                }
                // Blending the at-rest scales of both pages keeps every size continuous under the finger.
                val scales = labels.indices.map { index ->
                    val a = scaleBeside(index, from)
                    a + (scaleBeside(index, to) - a) * fraction
                }
                // Chain the titles by their drawn widths, so the gaps stay the same at every scale.
                var run = 0f
                val centres = labels.mapIndexed { index, label ->
                    val drawn = label.width * scales[index]
                    (run + drawn / 2f).also { run += drawn + spacing }
                }
                val anchor = centres[from] + (centres[to] - centres[from]) * fraction
                labels.forEachIndexed { index, label ->
                    val centre = width / 2f + centres[index] - anchor
                    val distance = abs(index - position)
                    label.placeRelativeWithLayer((centre - label.width / 2f).roundToInt(), top) {
                        scaleX = scales[index]
                        scaleY = scaleX
                        alpha = if (row) {
                            // The whole row stays readable; the current name is simply brighter.
                            1f - 0.38f * distance.coerceAtMost(1f)
                        } else if (distance <= 1f) {
                            1f - 0.45f * distance
                        } else {
                            // Only the two neighbours show at rest; the next ones fade in under the
                            // finger instead of peeking as a stray letter past a neighbour.
                            (0.55f * (2f - distance)).coerceAtLeast(0f)
                        }
                    }
                }
            }
        }
        if (style == PageHeaderStyle.MENU) {
            // A centred anchor as wide as the menu, so the list opens under the title.
            Box(Modifier.align(Alignment.BottomCenter).width(PageMenuWidth)) {
                AppDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.width(PageMenuWidth),
                ) {
                    pages.forEachIndexed { index, page ->
                        val current = index == pagerState.currentPage
                        AppMenuItem(
                            text = stringResource(page.titleResource()),
                            selected = current,
                            onClick = {
                                menuOpen = false
                                onSelect(page)
                            },
                            trailing = if (current) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp)) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

/** The Menu style's current title: a drop-down anchor that reports and toggles [open]. */
private fun Modifier.pageMenuAnchor(
    open: Boolean,
    interactionSource: MutableInteractionSource,
    onOpenChange: (Boolean) -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    role = Role.DropdownList,
    onClick = { onOpenChange(true) },
).semantics {
    if (open) collapse { onOpenChange(false); true } else expand { onOpenChange(true); true }
}

private val PageRowEdgeFade = 24.dp
private val PageTabBarWidth = 18.dp
private val PageTabBarHeight = 3.dp
private val PageTabBarOffset = 15.dp
private val PagePillHeight = 34.dp
private val PagePillGap = 6.dp
private val PageMenuWidth = 240.dp
private const val NEIGHBOUR_SCALE = 0.74f
private const val MIN_NEIGHBOUR_SCALE = 0.55f
private val PageEdgeFade = 14.dp
private val PageTitleSidePadding = 8.dp
private val PageTitleTop = 8.dp
private val PageTitlePadding = 6.dp
private val PageDotSize = 5.dp
private val PageDotActiveWidth = 14.dp
private val PageDotGap = 5.dp
private val PageDotsBottom = 8.dp

/**
 * Thumb-zone actions sit above playback while music remains loaded: the three a selection is made
 * for, with room for their full labels. Editing, sharing and the destructive action wait in the
 * contextual top bar ([SelectionTopAppBar]), driven by the same [commands].
 */
@Composable
internal fun SelectionActionBar(
    commands: SelectionCommands,
    favoriteAction: SelectionFavoriteAction,
    height: androidx.compose.ui.unit.Dp = SELECTION_ACTION_BAR_HEIGHT,
    includeNavigationPadding: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (includeNavigationPadding) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp.corner()))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp.corner()))
            .height(height - 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SelectionAction(
            icon = Icons.Rounded.PlayArrow,
            label = stringResource(Res.string.action_play),
            enabled = commands.canAct,
            onClick = commands.onPlay,
            modifier = Modifier.weight(1f),
        )
        SelectionAction(
            icon = Icons.Rounded.LibraryAdd,
            label = stringResource(Res.string.action_add),
            enabled = commands.canAct,
            onClick = commands.onAdd,
            modifier = Modifier.weight(1f),
        )
        val removesFavorites = favoriteAction == SelectionFavoriteAction.REMOVE
        SelectionAction(
            icon = if (removesFavorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = stringResource(
                if (removesFavorites) Res.string.action_unfavorite_short else Res.string.action_favorite_short,
            ),
            enabled = commands.canAct,
            onClick = { commands.onFavorite(favoriteAction) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurface
    val contentAlpha = if (enabled) 1f else 0.38f
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = contentAlpha),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The app's overflow affordance. Rendered from the same shared element in
 * both the library and the player and placed in the same slot, so the morph
 * between them leaves it untouched — a fixed point the eye can hold on to
 * while everything else moves.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun OverflowButton(
    /** Both null (as in the player, which no longer morphs) leave the button unshared. */
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    /** Set, the button goes straight to this instead of dropping a menu. */
    onClick: (() -> Unit)? = null,
    menuItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    Box {
        IconButton(
            onClick = { if (onClick != null) onClick() else open = true },
            modifier = if (reduceMotion || sharedScope == null || animatedScope == null) {
                Modifier
            } else with(sharedScope) {
                Modifier.sharedElement(
                    rememberSharedContentState(OVERFLOW_KEY),
                    animatedScope,
                )
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.cd_more_options),
            )
        }
        if (onClick == null) {
            AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menuItems { open = false }
            }
        }
    }
}

/** Direct access to the app control centre; maintenance actions live in Settings > Library. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedSettingsButton(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    shareElement: Boolean,
    onClick: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    IconButton(
        onClick = onClick,
        modifier = if (reduceMotion || !shareElement) {
            Modifier
        } else with(sharedScope) {
            Modifier.sharedElement(
                rememberSharedContentState(OVERFLOW_KEY),
                animatedScope,
            )
        },
    ) {
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = stringResource(Res.string.settings_title),
        )
    }
}

/** The shared browse-group gesture: tap, plus [TrackRow]'s long-press haptic when selectable. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.groupClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    /** A card sinks under the finger like the For You cards; rows keep the ripple alone. */
    pressScale: Float? = null,
): Modifier {
    if (pressScale != null) {
        val press = rememberPressSource()
        return scaleOnPress(press, pressScale).combinedClickable(
            interactionSource = press,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    return combinedClickable(
        onClick = onClick,
        // combinedClickable provides the long-press haptic once the hold is recognized.
        onLongClick = onLongClick,
    )
}

/** The leading check ring group rows share with [TrackRow]; absent outside selection mode. */
@Composable
internal fun GroupSelectionMark(selectionState: Boolean?) {
    if (selectionState == null) return
    Icon(
        imageVector = if (selectionState) {
            Icons.Rounded.CheckCircle
        } else {
            Icons.Rounded.RadioButtonUnchecked
        },
        contentDescription = null,
        tint = if (selectionState) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(28.dp),
    )
}

/** Stable, saveable Lazy item identity; null and an actual empty name never alias. */
internal fun stableGroupKey(kind: String, name: String?): String =
    if (name == null) "$kind:null" else "$kind:value:$name"

/**
 * Drops For You impressions already on record.
 *
 * An impression's identity already carries its local day, so a card offered again tomorrow is a
 * different entry and passes through. The filter is what keeps a page rebuild, or a return to the
 * tab, from rewriting the store for a card the listener has already been shown today.
 */
internal fun unrecordedForYouCardsShown(
    shown: List<ForYouImpression>,
    recorded: Set<ForYouImpression>,
): List<ForYouImpression> = shown.filterNot(recorded::contains)

/**
 * Whether the Albums tab re-sorts [albumCount] albums on the main thread, in the frame of the tap.
 *
 * Off the main thread the new order waits for a frame to start the derivation, a hop to a
 * background thread and back, and a frame to show it: 110-350 ms on an emulator, for a sort of 678
 * albums that takes 4-8 ms inline there (debug build). Up to this many albums the sort fits in a
 * frame, so it runs inline and the new order shows in the next frame; above it the background
 * keeps a big library's re-sort from dropping frames.
 */
internal fun albumResortRunsInline(albumCount: Int): Boolean = albumCount <= INLINE_ALBUM_RESORT_LIMIT

internal const val INLINE_ALBUM_RESORT_LIMIT = 1000

/** The Albums tab derived from one catalog in one order; tracks in the grid's order, for selection. */
internal class AlbumBrowseDerivation(
    val catalog: LibraryCatalog,
    val choice: SortChoice<AlbumSort>,
    val albumSections: List<AlbumRailSection>,
    val albumTabTracks: List<TrackDescriptor>,
) {
    companion object {
        fun of(catalog: LibraryCatalog, choice: SortChoice<AlbumSort>): AlbumBrowseDerivation {
            val sections = albumRailSections(catalog.albums, choice)
            return AlbumBrowseDerivation(
                catalog = catalog,
                choice = choice,
                albumSections = sections,
                albumTabTracks = sections.flatMap { section -> section.albums.flatMap { it.tracks } },
            )
        }
    }
}

internal data class AlbumRailSection(
    val bucket: String,
    val albums: List<AlbumGroup>,
    /** Index of this section's full-span header in LazyVerticalGrid emission order. */
    val emitStartIndex: Int,
)

/**
 * The Albums tab in [choice]'s order, one global order so one rail letter always has one anchor.
 * Title and artist sorts get letter sections; year and recency one unlabeled section, which the
 * grid shows without headers or a rail.
 */
internal fun albumRailSections(
    albums: List<AlbumGroup>,
    choice: SortChoice<AlbumSort> = DEFAULT_ALBUM_SORT,
): List<AlbumRailSection> {
    var emitIndex = 0
    return AlbumSorting.sections(albums, choice.sort, choice.direction)
        .map { section ->
            AlbumRailSection(
                bucket = section.bucket,
                albums = section.albums,
                emitStartIndex = emitIndex,
            ).also {
                emitIndex += 1 + section.albums.size
            }
        }
}

@Composable
internal fun AlbumCard(
    album: AlbumGroup,
    /** Rail mirrors cap decode work; the actual card still resolves from its layout constraints. */
    railPreview: Boolean = false,
    onArtworkLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** `null` outside selection mode; otherwise whether every track of this album is selected. */
    selectionState: Boolean? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val platformContext = LocalPlatformContext.current
    val year = remember(album.tracks) { albumYearLabel(album.tracks) }
    Column(
        modifier = modifier
            .then(
                if (selectionState != null) {
                    Modifier.semantics {
                        selected = selectionState
                        role = Role.Checkbox
                    }
                } else {
                    Modifier
                },
            )
            .groupClickable(onClick = onClick, onLongClick = onLongClick, pressScale = Motion.PRESS_SCALE)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp.corner()))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CoverPlaceholder(Modifier.matchParentSize())
            album.artworkUri?.let { requestUri ->
                val imageModel = remember(requestUri, railPreview, platformContext) {
                    if (railPreview) {
                        ImageRequest.Builder(platformContext)
                            .data(requestUri)
                            .size(ALBUM_RAIL_PREVIEW_PX)
                            .build()
                    } else {
                        requestUri
                    }
                }
                AsyncImage(
                    model = imageModel,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onLoading = {
                        onArtworkLoadStateChanged?.invoke(
                            requestUri,
                            ArtworkLoadState.LOADING,
                        )
                    },
                    onSuccess = {
                        onArtworkLoadStateChanged?.invoke(
                            requestUri,
                            ArtworkLoadState.TERMINAL,
                        )
                    },
                    onError = {
                        onArtworkLoadStateChanged?.invoke(
                            requestUri,
                            ArtworkLoadState.TERMINAL,
                        )
                    },
                )
            }
            if (selectionState != null) {
                // A grid card has no leading slot, so the check sits on the artwork; the scrim
                // circle keeps the unchecked outline visible over any cover.
                Icon(
                    imageVector = if (selectionState) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (selectionState) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            CircleShape,
                        )
                        .padding(2.dp)
                        .size(24.dp),
                )
            }
        }
        Text(
            text = album.title ?: stringResource(Res.string.track_unknown_album),
            style = MaterialTheme.typography.bodyLarge.metadata(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = joinBidiWrapped(
                listOfNotNull(
                    album.artist ?: stringResource(Res.string.track_unknown_artist),
                    year,
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Deliberately modest: enough for a transient card, far cheaper than source-resolution art. */
internal const val ALBUM_RAIL_PREVIEW_PX = 256

@Composable
internal fun GroupRow(
    title: String,
    subtitle: String,
    artworkUri: String?,
    onArtworkLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** `null` outside selection mode; otherwise whether every track of this group is selected. */
    selectionState: Boolean? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectionState != null) {
                    Modifier.semantics {
                        selected = selectionState
                        role = Role.Checkbox
                    }
                } else {
                    Modifier
                },
            )
            .groupClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GroupSelectionMark(selectionState)
        Artwork(
            uri = artworkUri,
            size = 56.dp,
            cornerRadius = 12.dp,
            onLoadStateChanged = onArtworkLoadStateChanged,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.metadata(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectionState == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A floating, artwork-tinted mini-player with an adaptive height for larger text.
 * Shell insets and selection actions use the same measured text-height budget. The player sheet
 * draws the surface that grows out of this card and flies its thumbnail to the full cover.
 */
@Composable
internal fun MiniPlayerPill(
    track: TrackDescriptor,
    accent: TrackAccent,
    isPlaying: Boolean,
    playback: PlaybackController,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = MINI_PLAYER_HEIGHT,
    /** Zero while the cover's copy flies out of (or back into) this thumbnail. */
    artworkAlpha: () -> Float = { 1f },
    /** False while the full player covers the pill: its playhead stops following. */
    live: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    // Only queue-entry changes cancel a swipe; playback-position ticks stay out of this UI.
    val queueIndex by remember(playback) {
        playback.state.map { it.queueIndex }.distinctUntilChanged()
    }.collectAsState(playback.state.value.queueIndex)
    val appearance = LocalAppearance.current
    val bar = appearance.miniPlayer == MiniPlayerStyle.BAR
    val playerShape = if (bar) RectangleShape else RoundedCornerShape(24.dp.corner())
    val miniSurface = miniPlayerSurfaceColor(accent)
    val edge = browseAccentInk(accent).copy(alpha = 0.14f)
    val miniInk = MaterialTheme.colorScheme.onSurface
    val fontScale = LocalDensity.current.fontScale
    val previousDescription = stringResource(Res.string.action_previous)
    CompositionLocalProvider(LocalContentColor provides miniInk) {
        BoxWithConstraints(
            modifier = modifier
                .testTag("latentjam_mini_player")
                .fillMaxWidth()
                .then(
                    if (bar) {
                        // A bar runs under the navigation bar to the screen's edge, with a hairline
                        // on top; only its content is lifted above the system inset.
                        Modifier.padding(top = 16.dp)
                            .background(miniSurface)
                            .drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                            .navigationBarsPadding()
                    } else {
                        Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)
                    },
                )
                .height(height - 16.dp)
                // Material Surface still creates a separate rendered surface at zero elevation. On
                // some Android renderers its boundary is visible as a full-width grey hairline.
                // A shaped background paints the pill without that extra surface boundary.
                .miniPlayerSwipe(
                    playback = playback,
                    trackId = track.id,
                    queueIndex = queueIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
                .clip(playerShape)
                .then(if (bar) Modifier else Modifier.background(miniSurface, playerShape).border(1.dp, edge, playerShape))
                .clickable(onClick = onOpen),
        ) {
            // At compact widths or large text, give the song name the room it needs. Previous
            // remains available by swipe, through accessibility, and in the expanded player.
            val showPrevious = maxWidth >= 340.dp && fontScale < 1.5f
            Box(modifier = Modifier.fillMaxSize().semantics {
                if (!showPrevious) {
                    customActions = listOf(CustomAccessibilityAction(previousDescription) {
                        onPrevious()
                        true
                    })
                }
            }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 6.dp)
                        .height(height - 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RetainedArtwork(
                        uri = track.artworkUri,
                        size = 48.dp,
                        cornerRadius = 12.dp,
                        modifier = Modifier.graphicsLayer { alpha = artworkAlpha() },
                    )
                    Spacer(Modifier.width(4.dp))
                    AnimatedContent(
                        targetState = track,
                        contentKey = { it.id },
                        transitionSpec = { motionFadeThrough(reduceMotion) },
                        modifier = Modifier.weight(1f),
                        label = "mini-track-metadata",
                    ) { shownTrack ->
                        Column(
                            // The surrounding mini-player owns taps while its text crossfades.
                            modifier = if (shownTrack.id != track.id) {
                                Modifier.clearAndSetSemantics { }
                            } else Modifier,
                        ) {
                            Text(
                                text = shownTrack.title
                                    ?: stringResource(Res.string.track_untitled),
                                style = MaterialTheme.typography.bodyMedium.metadata(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = shownTrack.artist
                                    ?: stringResource(Res.string.track_unknown_artist),
                                style = MaterialTheme.typography.bodySmall.metadata(),
                                color = miniInk.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Previous, play and next keep time's direction in every language
                    // (TimeDirection), like the pill's swipe: leftward is next.
                    TimeDirection {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (showPrevious) {
                                val previousPress = rememberPressSource()
                                IconButton(
                                    onClick = onPrevious,
                                    interactionSource = previousPress,
                                    modifier = Modifier.scaleOnPress(previousPress, Motion.BUTTON_PRESS_SCALE),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SkipPrevious,
                                        contentDescription = previousDescription,
                                    )
                                }
                            }
                            val playPauseDescription = stringResource(
                                if (isPlaying) Res.string.action_pause else Res.string.action_play,
                            )
                            val playPress = rememberPressSource()
                            IconButton(
                                onClick = onTogglePlayPause,
                                interactionSource = playPress,
                                modifier = Modifier
                                    .scaleOnPress(playPress, Motion.BUTTON_PRESS_SCALE)
                                    .semantics { contentDescription = playPauseDescription },
                            ) {
                                AnimatedContent(
                                    targetState = isPlaying,
                                    transitionSpec = { motionIconTransform(reduceMotion) },
                                    label = "mini-play-pause",
                                ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                )
                                }
                            }
                            val nextPress = rememberPressSource()
                            IconButton(
                                onClick = onNext,
                                interactionSource = nextPress,
                                modifier = Modifier.scaleOnPress(nextPress, Motion.BUTTON_PRESS_SCALE),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = stringResource(Res.string.action_next),
                                )
                            }
                        }
                    }
                }
                MiniPlayerProgress(
                    playback = playback,
                    accent = accent,
                    live = live,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

/** The pill's card: the track's colour over the container tone; the player sheet grows from it. */
@Composable
internal fun miniPlayerSurfaceColor(accent: TrackAccent): Color =
    lerp(MaterialTheme.colorScheme.surfaceContainer, accent.container, 0.72f)

/** Isolates the playhead ticker from the rest of the browse hierarchy. */
@Composable
internal fun MiniPlayerProgress(
    playback: PlaybackController,
    accent: TrackAccent,
    modifier: Modifier = Modifier,
    /** Behind the full player the pill is kept composed; its playhead does not tick there. */
    live: Boolean = true,
) {
    val liveProgressSample by remember(playback) {
        playback.state.map { now ->
            now.track?.id to if (now.durationMs > 0) {
                val coarsePosition =
                    now.positionMs / MINI_PLAYER_PROGRESS_STEP_MS * MINI_PLAYER_PROGRESS_STEP_MS
                (coarsePosition.toFloat() / now.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }.distinctUntilChanged()
    }.collectPlayerState(
        playback.state.value.let { now ->
            now.track?.id to if (now.durationMs > 0) {
                val coarsePosition =
                    now.positionMs / MINI_PLAYER_PROGRESS_STEP_MS * MINI_PLAYER_PROGRESS_STEP_MS
                (coarsePosition.toFloat() / now.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        },
        active = live,
    )
    var progressSample by remember { mutableStateOf(liveProgressSample) }
    LaunchedEffect(liveProgressSample) {
        if (liveProgressSample.first != null) progressSample = liveProgressSample
    }
    val reduceMotion = rememberReduceMotion()
    androidx.compose.runtime.key(progressSample.first) {
        val animatedProgress = remember { Animatable(progressSample.second) }
        LaunchedEffect(progressSample.second, reduceMotion) {
            if (reduceMotion || progressSample.second < animatedProgress.value) {
                // Track replacement, repeat-one and explicit restart all regress. Sweeping the
                // bar backward for a second misrepresents playback, so regressions snap.
                animatedProgress.snapTo(progressSample.second)
            } else {
                animatedProgress.animateTo(
                    targetValue = progressSample.second,
                    animationSpec = tween(
                        MINI_PLAYER_PROGRESS_STEP_MS.toInt(),
                        easing = LinearEasing,
                    ),
                )
            }
        }
        // A timeline fills from the left in every language (TimeDirection).
        TimeDirection {
            LinearProgressIndicator(
                progress = { animatedProgress.value },
                modifier = modifier,
                color = accent.onContainer.copy(alpha = 0.9f),
                trackColor = Color.Transparent,
                drawStopIndicator = {},
            )
        }
    }
}

/**
 * A 48 dp tall hit area around content that keeps its own drawn size: the content is measured
 * without the minimum and centred in the taller slot, as minimumInteractiveComponentSize does for
 * both axes. A plain heightIn would stretch a fixed-height pill and its outline instead.
 */
internal fun Modifier.minimumTouchHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val height = maxOf(placeable.height, 48.dp.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
}
