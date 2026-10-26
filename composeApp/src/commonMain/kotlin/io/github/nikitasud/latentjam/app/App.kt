/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_create
import io.github.nikitasud.latentjam.app.generated.resources.action_rename
import io.github.nikitasud.latentjam.app.generated.resources.action_undo
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle_all
import io.github.nikitasud.latentjam.app.generated.resources.count_albums
import io.github.nikitasud.latentjam.app.generated.resources.count_artists
import io.github.nikitasud.latentjam.app.generated.resources.count_genres
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_progress
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_progress_eta
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_title
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_discovery
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_instrumental
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_meme_viral
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_sound_effects
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_spoken_audio
import io.github.nikitasud.latentjam.app.generated.resources.library_import_none
import io.github.nikitasud.latentjam.app.generated.resources.library_import_partial
import io.github.nikitasud.latentjam.app.generated.resources.library_imported
import io.github.nikitasud.latentjam.app.generated.resources.playlist_imported_name
import io.github.nikitasud.latentjam.app.generated.resources.playlist_new
import io.github.nikitasud.latentjam.app.generated.resources.snack_m3u_import_failed
import io.github.nikitasud.latentjam.app.generated.resources.playlist_rename_title
import io.github.nikitasud.latentjam.app.generated.resources.playlist_cover_error
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_manage_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_added_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.snack_artist_excluded_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_artist_included_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_created
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_track_removed
import io.github.nikitasud.latentjam.app.generated.resources.snack_smart_exclusion_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_delete_denied
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_delete_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_delete_summary
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_excluded_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_included_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_removed_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.snack_audio_open_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.snack_library_refreshed
import io.github.nikitasud.latentjam.app.generated.resources.tags_saved
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_genre
import io.github.nikitasud.latentjam.history.localTimePoint
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.history.excludes
import io.github.nikitasud.latentjam.history.excludesArtist
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.AutoPlaylists
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistTrackChange
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.defaultDirection
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.withLock
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import io.github.nikitasud.latentjam.smart.cluster.WorldNamingGroups
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Root composable, shared by Android and iOS: the player shell.
 *
 * A carousel switches between library sections, the content sits on one
 * full-bleed artwork-tinted background, and the mini-player floats over it —
 * expanding into the now-playing screen through a shared-element morph, so
 * the pill visibly becomes the player rather than being replaced by it.
 * All Material 3, all original expression — the legacy app's look is never
 * consulted.
 */
@Composable
fun App(
    engine: SimilarityEngine,
    library: MusicLibrary,
    playback: PlaybackController,
    externalAudioOpener: ExternalAudioOpener? = null,
) {
    LatentJamAppTheme { darkTheme ->
        AppShell(engine, library, playback, externalAudioOpener, darkTheme)
    }
}

/**
 * The listener's theme, appearance and control ink around [content], which receives whether the
 * theme is dark. Shared by the app and the lock-screen player so both wear the same colours.
 */
@Composable
internal fun LatentJamAppTheme(content: @Composable (darkTheme: Boolean) -> Unit) {
    val themeMode by AppGraph.settings.themeMode.collectAsState()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val appearance by AppGraph.settings.appearance.collectAsState()
    val trackColorMode by AppGraph.settings.trackColorMode.collectAsState()
    PlatformThemeEffect(darkTheme)
    val scheme = latentJamColorScheme(darkTheme).let { if (darkTheme && appearance.pureBlack) it.pureBlack() else it }
    // The listener's own accent also inks the controls; any other colour mode keeps them neutral.
    val controlInk = if (trackColorMode == TrackColorMode.CUSTOM) {
        val custom = Color(appearance.customAccent)
        if (darkTheme) liftForDarkSurface(custom) else inkForLightSurface(custom, scheme.surface)
    } else null
    CompositionLocalProvider(
        LocalReduceMotion provides rememberPlatformReduceMotion(),
        LocalAppearance provides appearance,
        LocalControlInk provides controlInk,
    ) {
        MaterialTheme(colorScheme = scheme, typography = LatentJamTypography) {
            // The app draws edge to edge, and its pages keep from the top and bottom bars on their
            // own, but nothing kept the sides: a landscape iPhone's camera, a foldable iPhone's
            // status column (an 84 pt reserved strip down one side), an Android side navigation bar
            // or cutout. The sides are kept once, here, for everything inside; the theme's
            // background still runs under them.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                content(darkTheme)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun AppShell(
    engine: SimilarityEngine,
    library: MusicLibrary,
    playback: PlaybackController,
    externalAudioOpener: ExternalAudioOpener? = null,
    darkTheme: Boolean,
) {
    val settings = AppGraph.settings
    val startPage by settings.startPage.collectAsState()
    val pageLayout by settings.pageLayout.collectAsState()
    val visiblePages = remember(pageLayout) { pageLayout.normalized().visiblePages }
    val trackColorMode by settings.trackColorMode.collectAsState()
    val pageHeaderStyle by settings.pageHeaderStyle.collectAsState()
    val smartQueueLength by settings.smartQueueLength.collectAsState()
    val artistVariety by settings.artistVariety.collectAsState()
    val includeNoveltyMixes by settings.includeNoveltyMixes.collectAsState()
    val recordedHistoryRevision by AppGraph.historyRevision.collectAsState()
    // The recorder's revision moves only when a listen is recorded, but clearing the log or
    // restoring a backup replaces it wholesale without recording anything. Those two settings
    // actions bump this epoch instead, and the sum is what every cache below is keyed on; both
    // halves only grow, so neither counter can mask the other.
    var replacedHistoryEpoch by remember { mutableLongStateOf(0L) }
    val historyRevision = recordedHistoryRevision + replacedHistoryEpoch
    val smartExclusions = AppGraph.smartExclusions
    val smartExclusionState by smartExclusions.state.collectAsState()
    val reduceMotion = rememberReduceMotion()
    val layoutDirection = LocalLayoutDirection.current
        val scope = rememberCoroutineScope()
        val sleepTimer = remember { AppGraph.sleepTimer }
        val sleepTimerState by sleepTimer.state.collectAsState()
        LaunchedEffect(playback, smartQueueLength) {
            playback.setSmartQueueLength(smartQueueLength)
        }
        val artistVarietyApplier = remember(engine, playback) {
            ArtistVarietyApplier(
                setPenalty = engine::setArtistRunPenalty,
                invalidateSmartFuture = { playback.invalidateSmartFuture() },
            )
        }
        LaunchedEffect(artistVarietyApplier, artistVariety) {
            artistVarietyApplier.apply(artistVariety)
        }
        val snackbar = remember { SnackbarHostState() }
        var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        val libraryScan = remember { LibraryScanState() }
        // SMART reconciliation needs more than the rows: an unavailable media permission is
        // represented by an empty/partial list, which must not be mistaken for deletion. Keep the
        // authority bit bound to the scan that produced these exact rows so a later permission
        // transition cannot retroactively reclassify an older empty result.
        var libraryIndexingRequest by remember {
            mutableStateOf<AutomaticIndexingRequest?>(null)
        }
        val genreEnrichment = remember { GenreEnrichment(AppGraph.settings) }
        val tagAccess = rememberTagWriteAccess()
        val interruptedSaves = tagAccess?.coordinator?.pendingRecovery?.collectAsState()?.value.orEmpty()
        val repairingKeys = remember(interruptedSaves) {
            interruptedSaves.filter { it.state == JournalState.REPLACING }.mapTo(HashSet()) { it.target }
        }
        val currentRepairingKeys = rememberUpdatedState(repairingKeys)
        val couldNotFinish = tagAccess?.coordinator?.couldNotFinish?.collectAsState()?.value.orEmpty()
        val missingAtFinish = tagAccess?.coordinator?.missingAtFinish?.collectAsState()?.value.orEmpty()
        val scannedTracks = libraryScan.scannedTracks
        val interruptedSaveRows = remember(interruptedSaves, scannedTracks, tagAccess, couldNotFinish, missingAtFinish) {
            tagAccess?.let { access ->
                interruptedSavesOf(interruptedSaves, scannedTracks, couldNotFinish, missingAtFinish, access::keyOf)
            }.orEmpty()
        }
        fun publishLibraryTracks(
            value: List<TrackDescriptor>,
            authoritative: Boolean,
        ) {
            tracks = value
            libraryIndexingRequest = AutomaticIndexingRequest(value, authoritative)
        }
        suspend fun scanLibrary(): List<TrackDescriptor> {
            val scan = library.scan()
            // iOS may resolve its Music-library prompt inside scan(); Android refreshes here as
            // a second line of defence around a return from system settings.
            AppGraph.permissions.refresh()
            // Remembered embedded genres upgrade the scan before anything downstream sees it:
            // the system scanner keeps one genre per track, the files themselves keep them all.
            val enriched = genreEnrichment.apply(scan.tracks)
            // A file mid-replace is neither its old bytes nor its new ones: not offered until recovered.
            val repairing = currentRepairingKeys.value
            val playable = withoutFilesUnderRepair(enriched, repairing) { tagAccess?.keyOf(it) }
            libraryScan.shownRepairingKeys = repairing
            libraryScan.scannedTracks = enriched
            publishLibraryTracks(
                value = playable,
                authoritative = authoritativeLibrarySnapshot(
                    scanCompleted = scan.complete,
                    permissionStatus = AppGraph.permissions.audioLibraryStatus.value,
                ),
            )
            // A recovered app ID may now point at a different MediaStore row. Only relocated
            // queued tracks need a media-source replacement; an ordinary scan leaves playback alone.
            if (library.preservesRelocatedTrackIds) {
                val queued = playback.state.value
                val queuedUris = (queued.queue + queued.sourceQueue).associate { it.id to it.audioUri }
                val relocated = playable.filter { fresh ->
                    !fresh.audioUri.isNullOrBlank() && queuedUris[fresh.id]?.let { it != fresh.audioUri } == true
                }
                if (relocated.isNotEmpty()) playback.refreshTracks(relocated)
            }
            return playable
        }
        // Rescans when the files under repair differ from those the library was filtered with. Keyed
        // on the first load too: the journal is read in the background and can land before it.
        val libraryLoaded = tracks != null
        LaunchedEffect(repairingKeys, libraryLoaded) {
            if (libraryLoaded && repairingKeys != libraryScan.shownRepairingKeys) scanLibrary()
        }
        suspend fun buildCatalog(from: List<TrackDescriptor>): LibraryCatalog = withContext(Dispatchers.Default) {
            // Collaborations split only where the MusicBrainz list can tell a band named
            // "Earth, Wind & Fire" from two artists; without it, only at semicolons.
            val entities = AppGraph.musicEntities
            LibraryCatalog.build(
                from,
                isKnownArtist = if (entities.isAvailable) { name -> entities.resolve(name).isNotEmpty() } else null,
            )
        }
        // Files not yet read (or retagged since) get their embedded genres read in the
        // background; anything learned republishes the same snapshot, richer. The identity
        // check keeps a superseding scan from being overwritten with an older list.
        // Counts the enrichment's republishes, which an open page follows (see refreshOpenCollection).
        var enrichedLibraryRevision by remember { mutableLongStateOf(0L) }
        LaunchedEffect(libraryIndexingRequest) {
            if (libraryIndexingRequest?.librarySnapshotAuthoritative == true) {
                library.rememberTrackIdentities()
            }
        }
        LaunchedEffect(libraryIndexingRequest) {
            val request = libraryIndexingRequest ?: return@LaunchedEffect
            if (request.tracks.isEmpty()) return@LaunchedEffect
            if (genreEnrichment.backfill(request.tracks) && libraryIndexingRequest === request) {
                val richer = genreEnrichment.apply(request.tracks)
                // apply reads the cache off the main thread: a scan or a hide may have published a
                // newer library meanwhile, which this older one must not replace.
                if (libraryIndexingRequest !== request) return@LaunchedEffect
                // With no file under repair the scan's list IS the published one; replace both, or
                // the scan state keeps the whole pre-enrichment library alive beside the new one.
                if (libraryScan.scannedTracks === request.tracks) libraryScan.scannedTracks = richer
                publishLibraryTracks(
                    value = richer,
                    authoritative = request.librarySnapshotAuthoritative,
                )
                // A playing or soon-playing song that turned out to name no album loses a borrowed
                // cover (issue #12): the player, notification and widget follow at once. Only the
                // rows about to play: each refresh is a player command, and the first pass after
                // an update can change thousands of queued rows; a later row takes its new cover
                // as it starts to play (see playingTrackId).
                val queued = playback.state.value
                val soon = queued.queue.drop(queued.queueIndex.coerceAtLeast(0)).take(SOON_QUEUED_ROWS)
                val soonCovers = soon.associate { it.id to it.artworkUri }
                val recovered = richer.filter { fresh ->
                    fresh.id in soonCovers && soonCovers[fresh.id] != fresh.artworkUri
                }
                if (recovered.isNotEmpty()) playback.refreshTracks(recovered)
                enrichedLibraryRevision++
            }
        }

        // Keep both the pager and its item provider alive as the page set changes. Stable item
        // keys preserve scroll state; the saved identity resolves a removed current page.
        var savedActivePage by rememberSaveable { mutableStateOf(startPage.name) }
        val activePage = resolveActiveBrowsePage(visiblePages, savedActivePage, startPage)
        // AnimatedContent can retain an older content lambda while the shell updates the count.
        // All pager readers must observe one list, including lambdas retained by those layers.
        val pagerPages = rememberUpdatedState(visiblePages)
        val pagerState = rememberPagerState(initialPage = visiblePages.indexOf(activePage)) { pagerPages.value.size }
        val tabNavigation = remember { RootTabNavigation(visiblePages) }
        val pageLayoutChanged = tabNavigation.appliedPages != visiblePages
        val pendingDestination = if (pageLayoutChanged) activePage else tabNavigation.pendingPage
        val selectedTab = pendingDestination ?: run {
            visiblePages.getOrElse(pagerState.currentPage) { activePage }
        }
        val settledTab = pendingDestination ?: run {
            visiblePages.getOrElse(pagerState.settledPage) { activePage }
        }
        SideEffect {
            if (pageLayoutChanged) {
                // Commit the new position with the data-set update at the next measurement.
                // Replacing PagerState independently can pair an old provider count with new
                // keys; replacing the whole pager discards the current page's scroll position.
                tabNavigation.rootTabNavigationJob?.cancel()
                tabNavigation.pendingPage = activePage
                pagerState.requestScrollToPage(visiblePages.indexOf(activePage))
                tabNavigation.appliedPages = visiblePages
                tabNavigation.rootTabNavigationRevision++
            }
            savedActivePage = selectedTab.name
        }
        LaunchedEffect(tabNavigation.pendingPage, visiblePages) {
            val target = tabNavigation.pendingPage ?: return@LaunchedEffect
            val targetIndex = visiblePages.indexOf(target)
            if (targetIndex < 0) return@LaunchedEffect
            snapshotFlow {
                !pagerState.isScrollInProgress &&
                    pagerState.currentPage == targetIndex && pagerState.settledPage == targetIndex &&
                    pagerState.currentPageOffsetFraction == 0f
            }.first { it }
            tabNavigation.pendingPage = null
        }
        // These two local functions travel into memoized callbacks (the carousel's onSelect, the
        // track sheet's "show on map", hide-undo snapshots) that outlive the composition which
        // created them. They must read the page list through state, never through the captured
        // `visiblePages` value: after a live Settings → Pages change that snapshot names the wrong
        // index and a tap lands on the neighbouring page.
        val currentActivePage = rememberUpdatedState(activePage)
        val currentReduceMotion = rememberUpdatedState(reduceMotion)
        fun rootTabSnapshot(): RootTabSnapshot {
            val pages = pagerPages.value
            val fallback = currentActivePage.value
            val settling = tabNavigation.appliedPages != pages
            return RootTabSnapshot(
                navigationRevision = tabNavigation.rootTabNavigationRevision,
                currentTab = if (settling) fallback.ordinal else {
                    (tabNavigation.pendingPage ?: pages.getOrElse(pagerState.currentPage) { fallback }).ordinal
                },
                settledTab = if (settling) fallback.ordinal else {
                    (tabNavigation.pendingPage ?: pages.getOrElse(pagerState.settledPage) { fallback }).ordinal
                },
            )
        }
        fun navigateToRootTab(tab: StartPage) {
            val index = pagerPages.value.indexOf(tab)
            if (index < 0) return
            if (index == pagerState.currentPage && index == pagerState.settledPage &&
                !pagerState.isScrollInProgress && tabNavigation.rootTabNavigationJob?.isActive != true &&
                pagerState.currentPageOffsetFraction == 0f
            ) return
            // Invalidate immediately so a racing Undo cannot restore the old page's detail.
            tabNavigation.rootTabNavigationRevision++
            tabNavigation.pendingPage = null
            tabNavigation.rootTabNavigationJob?.cancel()
            tabNavigation.rootTabNavigationJob = scope.launch {
                if (currentReduceMotion.value) {
                    pagerState.scrollToPage(index)
                } else {
                    pagerState.animateScrollToPage(
                        index,
                        animationSpec = tween(Motion.NAVIGATION_MS, easing = Motion.NavigationEasing),
                    )
                }
            }
        }
        LaunchedEffect(pagerState) {
            var previousSettledTab = pagerState.settledPage
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { settledIndex ->
                    if (settledIndex != previousSettledTab) {
                        previousSettledTab = settledIndex
                        tabNavigation.rootTabNavigationRevision++
                    }
                }
        }
        val playlistsState = remember { PlaylistsState() }
        fun publishPlaylists(value: List<Playlist>) {
            // On first hydration, publish the planning policy before allowing queue resume. A
            // short saved SMART queue may top itself up immediately; letting restore win this race
            // would generate that future with an empty policy and then preserve it as “initial”.
            AppGraph.smartUserPlaylists.value = value.smartUserPlaylists()
            if (!playlistsState.playlistsLoaded) {
                AppGraph.smartCompanionGroups.value = value.smartCompanionMemberships()
                playlistsState.companionGroupsInitialized = true
            }
            playlistsState.published(value)
        }
        var showCreatePlaylist by rememberSaveable { mutableStateOf(false) }
        var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
        val sheets = remember { ShellSheets() }
        // Store only identities across recreation; a playlist draft must retain its selected
        // tracks without putting the whole library's metadata in saved instance state.
        var pendingPlaylistTrackIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
        var selectedTrackIds by remember { mutableStateOf<Set<TrackId>>(emptySet()) }
        var selectionRevision by remember { mutableLongStateOf(0L) }
        fun updateTrackSelection(value: Set<TrackId>) {
            if (selectedTrackIds != value) {
                selectedTrackIds = value
                selectionRevision++
            }
        }
        // Sort choices live in settings, not saved instance state: a listener who sorted by year
        // expects the list sorted by year after a cold start too.
        val songSortChoice by settings.songSort.collectAsState()
        val albumSortChoice by settings.albumSort.collectAsState()
        val groupSortOrders by settings.groupSortOrders.collectAsState()
        val artistAlbumSortChoice by settings.artistAlbumSort.collectAsState()
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var settingsRoute by rememberSaveable { mutableStateOf<String?>(null) }
        var infoTargetId by rememberSaveable { mutableStateOf<String?>(null) }
        // An "Edit tags" action opens the sheet on its editor; "Information" opens the info view.
        var infoStartsEditing by rememberSaveable { mutableStateOf(false) }
        fun showTrackInfo(id: TrackId, editing: Boolean) {
            infoStartsEditing = editing
            infoTargetId = id.value
        }
        var showNowPlaying by rememberSaveable { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var selectedCollection by remember { mutableStateOf<CollectionSelection?>(null) }
        val openPageRefresh = remember { Mutex() }
        var collectionRevision by remember { mutableLongStateOf(0L) }
        var collectionOpenJob by remember { mutableStateOf<Job?>(null) }
        fun applySelectedCollection(value: CollectionSelection?) {
            // However a playlist or Favorites page was built or refreshed, it shows (and so plays)
            // the order the listener chose for it.
            val shown = value?.inChosenOrder(
                AppGraph.settings.playlistSortOrders.value,
                playlistsState.playlists, playlistsState.autoPlaylists,
            )
            if (selectedCollection !== shown) {
                selectedCollection = shown
                collectionRevision++
            }
        }
        fun updateSelectedCollection(value: CollectionSelection?) {
            collectionOpenJob?.cancel()
            collectionOpenJob = null
            applySelectedCollection(value)
        }
        // A new album order re-sorts an open artist page in place. Keyed on the collection revision
        // too: a rescan that replaces the page mid-sort restarts this against the replacement, so the
        // shown order always converges on the chosen one. A page already in that order sorts to
        // itself and is left alone, which is also how this settles after its own update.
        LaunchedEffect(artistAlbumSortChoice, collectionRevision) {
            val open = selectedCollection?.takeIf { it.routeId.startsWith("artist:") } ?: return@LaunchedEffect
            val resorted = open.withArtistAlbumOrder(artistAlbumSortChoice)
            if (selectedCollection === open && resorted != open) applySelectedCollection(resorted)
        }
        fun openCollection(
            build: suspend () -> CollectionSelection,
            afterOpen: () -> Unit = {},
        ) {
            val sourceRoot = rootTabSnapshot()
            val sourceTracks = tracks
            val sourceSearch = showSearch
            val sourceSettings = showSettings
            val sourcePlayer = showNowPlaying
            collectionOpenJob?.cancel()
            collectionOpenJob = scope.launch {
                val built = build()
                if (
                    !isActive ||
                    rootTabSnapshot() != sourceRoot ||
                    tracks !== sourceTracks ||
                    showSearch != sourceSearch ||
                    showSettings != sourceSettings ||
                    showNowPlaying != sourcePlayer
                ) return@launch
                applySelectedCollection(built)
                afterOpen()
            }
        }
        var bulkEditIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
        var bulkEditScope by rememberSaveable { mutableStateOf(BulkEditScope.SELECTION) }

        /** One song gets the full editor, with the per-track fields the N-track editor leaves out. */
        fun openTagEditor(targets: List<TrackDescriptor>, editScope: BulkEditScope) {
            when (targets.size) {
                0 -> Unit
                1 -> showTrackInfo(targets.single().id, editing = true)
                else -> {
                    bulkEditScope = editScope
                    bulkEditIds = targets.map { it.id.value }
                }
            }
        }

        /**
         * An open page after an edit, and again once the enrichment republishes the library. An
         * album or artist page follows its tracks to where they now group. Any other page swaps in
         * fresh descriptors. The route identity is kept, so this is the same page updated, never a
         * new one pushed.
         */
        suspend fun refreshOpenCollection() = openPageRefresh.withLock {
            // The library as last published, read under the lock: of two refreshes, the later one
            // builds from the newer library, so an older one can never land over it.
            val fresh = tracks ?: return@withLock
            val open = selectedCollection ?: return@withLock
            val pageIds = open.tracks.mapTo(HashSet()) { it.id }
            val rebuilt = when {
                open.routeId.startsWith("album:") -> refreshedAlbum(buildCatalog(fresh), pageIds)?.toSelection()
                open.routeId.startsWith("artist:") ->
                    refreshedArtist(buildCatalog(fresh), pageIds)?.toSelection(artistAlbumSortChoice)
                else -> open.withFreshTracks(fresh.associateBy { it.id })
            }
            if (selectedCollection === open && rebuilt != null) {
                updateSelectedCollection(rebuilt.copy(routeId = open.routeId))
            }
        }

        // A save's rescan has not re-read the saved files yet: their year (which Android 16's
        // scanner leaves empty) and original year come from the enrichment, which re-reads them and
        // republishes. An open page follows that too, or it shows another year than its album card.
        LaunchedEffect(enrichedLibraryRevision) {
            if (enrichedLibraryRevision > 0) refreshOpenCollection()
        }

        /**
         * Everything finished saves change outside the files (spec §6.5). [edits] pairs each edit's
         * tracks, as the library held them before the save, with its result; [results] are all the
         * saves, recoveries included. First SMART's carry-overs, before the reload that would
         * otherwise start a sync without them. Then the saved covers, which the library must know of
         * before it reloads: Android's covers are per album, and only a recorded one is the song's
         * own; and the kept ones, so the reload need not re-read those files. Then one library
         * reload, unless no file changed and no recorded cover changed what it shows. Then the queue
         * and the open page, which hold descriptors by value. Nothing runs when no file changed and
         * no cover is to be recorded; else the job.
         *
         * On the app's scope, not this composition's: an Activity recreated within the second this
         * takes (a rotation) would otherwise cancel it, leaving the carry-overs and covers unstored
         * and the queue on the old descriptors. Those are process-wide, so they finish whatever happens
         * to this composition. The library and the open page are this composition's; a new one
         * loads its own library at start, after the save, and opens no page from before.
         */
        fun followTagSaves(
            edits: List<Pair<List<TrackDescriptor>, TagSaveResult>>,
            results: List<TagSaveResult>,
        ): Job? {
            val access = tagAccess ?: return null
            val followUp = TagSaveFollowUp(edits, results, access::keyOf, library.recordsCovers)
            if (!followUp.needed) return null
            return AppGraph.appScope.launch(Dispatchers.Main) {
                for ((saved, result) in edits) {
                    AppGraph.audioCarryOvers.add(audioCarryOversOf(saved, result, access::keyOf))
                }
                var recorded = false
                for ((ids, cover) in followUp.covers) {
                    if (library.coverSaved(ids, cover)) recorded = true
                }
                if (followUp.kept.isNotEmpty()) library.coverKept(followUp.kept)
                if (!followUp.rescanAfter(recorded)) return@launch
                val fresh = scanLibrary()
                playback.refreshTracks(followUp.refreshed(fresh, access::keyOf))
                refreshOpenCollection()
            }
        }

        fun afterTagSave(saved: List<TrackDescriptor>, result: TagSaveResult) {
            followTagSaves(listOf(saved to result), listOf(result))
        }

        LaunchedEffect(tagAccess?.coordinator) {
            val coordinator = tagAccess?.coordinator ?: return@LaunchedEffect
            coordinator.rescanRevision.collect { revision ->
                if (revision == 0L) return@collect
                try {
                    // Large saves report before MediaScanner finishes. Refresh again from its
                    // final rows, including the queue and the collection already open on screen.
                    val fresh = scanLibrary()
                    playback.refreshTracks(fresh)
                    refreshOpenCollection()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The files are safe; the next foreground scan retries an unavailable index.
                    println("Library: could not refresh after tag indexing")
                }
            }
        }
        // The player emits position twice per second. The browse shell only needs to know when the
        // TRACK changes; collecting the complete snapshot here used to invalidate this entire
        // pager and every visible list for each seek-bar tick.
        val currentTrack by remember(playback) {
            playback.state.map { it.track }.distinctUntilChanged()
        }.collectAsState(playback.state.value.track)
        // Play/pause transitions only — never the position ticks — so the now-playing badge's
        // motion state recomposes exactly when sound starts or stops.
        val currentTrackPlaying by remember(playback) {
            playback.state.map { it.isPlaying }.distinctUntilChanged()
        }.collectAsState(playback.state.value.isPlaying)
        val showPauseButton by remember(playback) {
            playback.state.map { it.showPauseButton }.distinctUntilChanged()
        }.collectAsState(playback.state.value.showPauseButton)
        val selectionMode = selectedTrackIds.isNotEmpty() && (
            selectedTab == StartPage.TRACKS || selectedTab in GROUP_TABS || showSearch ||
                selectedCollection?.allowsTrackSelection == true
        )
        val accent = rememberTrackAccent(
            track = currentTrack,
            mode = trackColorMode,
            darkTheme = darkTheme,
            customColor = Color(LocalAppearance.current.customAccent),
        )
        // AnimatedVisibility retains its composition for exit frames. Keep the last presentation
        // in an ordinary main-thread holder: while a live track exists none of these fallback
        // fields are read, so updating them must not itself invalidate the shell.
        val lastMiniPresentation = remember {
            LastMiniPresentation(currentTrack, accent, showPauseButton)
        }
        SideEffect {
            if (currentTrack != null) {
                // Accent animates over several frames. This value is only consulted after the
                // live track disappears, so ordinary fields retain the latest exit frame without
                // scheduling a second whole-shell recomposition for every colour-animation tick.
                lastMiniPresentation.track = currentTrack
                lastMiniPresentation.accent = accent
                lastMiniPresentation.isPlaying = showPauseButton
            }
        }
        val shareTracks = rememberTrackSharer()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val miniPlayerHeight = with(LocalDensity.current) {
            (40.sp.toDp() + 48.dp).coerceAtLeast(MINI_PLAYER_HEIGHT)
        }
        val selectionActionBarHeight = with(LocalDensity.current) {
            (16.sp.toDp() + 48.dp).coerceAtLeast(SELECTION_ACTION_BAR_HEIGHT)
        }
        val dockFadeHeight = if (currentTrack != null) 24.dp else 0.dp
        // The lists run to the bottom edge of an edge-to-edge window, so the navigation bar is
        // theirs to clear — nothing below them does it. The pill adds its own height on top of
        // that when it is up; it sits ON the content rather than beside it, so the two add.
        val listPadding = PaddingValues(
            bottom = navBottom + dockFadeHeight + when {
                selectionMode -> selectionActionBarHeight +
                    if (currentTrack != null) miniPlayerHeight else 0.dp
                currentTrack != null -> miniPlayerHeight
                else -> 12.dp
            },
        )
        // Screens that already inset themselves against the navigation bar only need the pill's
        // own height on top.
        val floatingPlayerInset = (if (currentTrack != null) miniPlayerHeight + dockFadeHeight else 0.dp) +
            (if (selectionMode) selectionActionBarHeight else 0.dp)

        val importAudio = rememberAudioImporter { result ->
            scope.launch {
                scanLibrary()
                val message = when {
                    result.failed > 0 || result.skipped > 0 -> getString(
                        Res.string.library_import_partial,
                        result.imported,
                        result.skipped,
                        result.failed,
                    )
                    result.imported > 0 -> getPluralString(
                        Res.plurals.library_imported,
                        result.imported,
                        result.imported,
                    )
                    else -> getString(Res.string.library_import_none)
                }
                snackbar.showSnackbar(message)
            }
        }

        var favoriteIds by remember { mutableStateOf<List<TrackId>>(emptyList()) }
        // Hearts live in private storage, and both its read and its write can fail. A failure must
        // leave the heart where it was and say so, instead of escaping the scope that launched it.
        suspend fun loadFavorites() {
            try {
                favoriteIds = AppGraph.favorites.all()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                println("Favorites: could not read: $failure")
                snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
            }
        }
        LaunchedEffect(Unit) {
            AppGraph.favorites.revision.collect { loadFavorites() }
        }
        fun toggleFavorite(id: TrackId) {
            scope.launch {
                try {
                    // Written to the store before memory changes, so a failed write leaves the
                    // visible heart exactly as it was.
                    AppGraph.favorites.toggle(id)
                    loadFavorites()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    println("Favorites: could not toggle: $failure")
                    snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                }
            }
        }
        // Many hearts at once (a selection, a playlist, an imported file): one write, then a
        // snackbar with Undo. [onFailure] runs when nothing could be written.
        fun editFavorites(
            action: SelectionFavoriteAction,
            ids: List<TrackId>,
            onFailure: () -> Unit = {},
        ) {
            scope.launch {
                if (!runBulkFavoriteEdit(AppGraph.favorites, action, ids, snackbar) { favoriteIds = it }) {
                    onFailure()
                }
            }
        }
        LaunchedEffect(Unit) {
            try {
                scanLibrary()
                libraryScan.hasHiddenTracks = library.hasHiddenTracks()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // A media-store or database failure must degrade to the loading/empty library with a
                // retry, not escape the effect: an uncaught throwable here kills the whole process.
                println("Library: could not scan at launch: $failure")
                snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
            }
            loadFavorites()
            // Playlists load at launch, not first tab visit: the SMART companion groups and mix
            // names derive from them, and both must work in a session that never opens the tab.
            try {
                publishPlaylists(AppGraph.playlists.all())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                println("Playlists: could not load at launch: $failure")
                // Playlist storage is optional to transport restore. Mark an empty policy ready so
                // a read failure cannot suppress the saved player for the whole session; if a
                // later retry succeeds, the initialized-policy effect prunes/refills SMART once.
                if (!playlistsState.playlistsLoaded) {
                    AppGraph.smartCompanionGroups.value = emptyList()
                    playlistsState.companionGroupsInitialized = true
                    playlistsState.launchReadFailed()
                }
            }
        }
        // A track downloaded while the app sat in the background shows up on return instead of
        // on the next cold start. An unchanged library re-queries into an equal list, which the
        // state holder swallows — no downstream effect re-runs, so the no-change case costs one
        // MediaStore query and nothing else.
        PlatformForegroundEffect(
            // Leaving while paused ends the sitting: finalize the in-progress listening session
            // so the last track of the day stops vanishing from the history log.
            onLeave = { AppGraph.flushListeningSession() },
        ) {
            scope.launch {
                if (selectedTab == StartPage.PLAYLISTS) playlistsState.autoPlaylistClockMs = epochMillis()
                // Not while a tag save is queued or running. The return from its consent dialog lands
                // here just as the writes start, and a reload that already sees a written file's new
                // revision, before the save's SMART carry-over exists, discards its audio analysis.
                // The save reloads the library itself once it has finished.
                //
                // Guarded for the same reason as the launch scan: a media-store failure on return
                // must leave the current library in place, not kill the process from this scope.
                try {
                    if (tagAccess?.coordinator?.active?.value != true) scanLibrary()
                    libraryScan.hasHiddenTracks = library.hasHiddenTracks()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    println("Library: could not rescan on return: $failure")
                }
            }
        }
        // Whenever the library changes, drop queue entries for tracks that no longer EXIST.
        // Reconciled against all known tracks — visible plus hidden — deliberately: hiding a
        // track means "stop recommending it", not "yank it out of the queue mid-session";
        // only genuine deletion removes it. Authority belongs to the exact scan: a permission
        // change must reconcile even when it returns the same rows as the preceding partial scan.
        LaunchedEffect(libraryIndexingRequest) {
            collectionOpenJob?.cancel()
            collectionOpenJob = null
            val request = libraryIndexingRequest ?: return@LaunchedEffect
            val loaded = request.tracks
            // The visible half of allKnownTracks() is the list already in hand; only the hidden
            // half needs a query. Partial scans cannot prove deletion, even when app-owned files
            // still make their result nonempty. A confirmed empty scan can remove the final row.
            val hidden = if (request.librarySnapshotAuthoritative) library.hiddenTracks() else emptyList()
            queueRetentionIds(request, hidden)?.let { known -> playback.retainQueue(known) }
            // An open collection screen is a snapshot from when it was opened; a track deleted
            // meanwhile must fall out of it (and its count) the same way it falls out of the
            // queue. Filtered against the VISIBLE library: this runs for albums and playlists
            // alike, and a track hidden mid-visit should also stop being shown.
            selectedCollection?.let { selection ->
                val visible = loaded.mapTo(mutableSetOf()) { it.id }
                val reconciled = selection.filterTracksForCollection { it.id in visible }
                if (reconciled != selection) {
                    updateSelectedCollection(
                        reconciled?.copy(subtitle = trackCountLabel(reconciled.tracks.size)),
                    )
                }
            }
        }
        LaunchedEffect(smartExclusions) {
            try {
                smartExclusions.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A corrupt/unreadable preference must never strand first-run library loading.
                // Keep the safe empty in-memory state and expose a retryable error unobtrusively.
                snackbar.showSnackbar(getString(Res.string.snack_smart_exclusion_failed))
            }
        }

        val smartEligibleTracks = remember(tracks, smartExclusionState) {
            tracks.orEmpty().filterNot { smartExclusionState.excludes(it) }
        }

        LaunchedEffect(tabNavigation.rootTabNavigationRevision, showSearch, showSettings, showNowPlaying) {
            // A pending background sort belongs to the page/overlay route that launched it.
            // Navigating elsewhere must not install that old collection underneath the new route.
            collectionOpenJob?.cancel()
            collectionOpenJob = null
        }

        LaunchedEffect(selectedTab, selectedCollection) {
            // A swipe between root tabs ends a Tracks-page selection. A collection detail is a
            // full-screen child of its source tab, however; entering playlist selection there must
            // not be cancelled merely because that source tab is not Tracks.
            if (selectedCollection == null && selectedTab != StartPage.TRACKS) {
                updateTrackSelection(emptySet())
            }
        }

        // A selection survives under the full player; Back there belongs to the player.
        PlatformBackHandler(enabled = selectionMode && !showNowPlaying) { updateTrackSelection(emptySet()) }

        // Arm SMART in the background as soon as the library is known: restore persisted vectors
        // and backfill genuinely new/changed rows. The audio model remains lazy when everything is
        // already indexed or remembered as an unchanged decode failure, so a permanently bad file
        // cannot wake tens of MB of model state on every launch.
        val forYouPage = remember { ForYouPageState() }
        val libraryWorlds = remember { LibraryWorldsState() }
        val discoveryMixLabel = stringResource(Res.string.foryou_mix_discovery)
        val memeViralMixLabel = stringResource(Res.string.foryou_mix_meme_viral)
        val soundEffectsMixLabel = stringResource(Res.string.foryou_mix_sound_effects)
        val spokenAudioMixLabel = stringResource(Res.string.foryou_mix_spoken_audio)
        val instrumentalMixLabel = stringResource(Res.string.foryou_mix_instrumental)
        val semanticMixLabels = remember(
            memeViralMixLabel,
            soundEffectsMixLabel,
            spokenAudioMixLabel,
            instrumentalMixLabel,
        ) {
            mapOf(
                LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO to memeViralMixLabel,
                LibraryWorldSemanticTitle.SOUND_EFFECTS to soundEffectsMixLabel,
                LibraryWorldSemanticTitle.SPOKEN_AUDIO to spokenAudioMixLabel,
                LibraryWorldSemanticTitle.INSTRUMENTAL to instrumentalMixLabel,
            )
        }
        val playlistPresentationIdentity = remember(playlistsState.playlists) {
            playlistsState.playlists.presentationIdentity()
        }

        val browsePageVisible = !showSettings && !showSearch && !showNowPlaying && selectedCollection == null
        val forYouPageActive = browsePageVisible && settledTab == StartPage.FOR_YOU
        // In view means the app is in the foreground too: a listener whose start page is For You
        // and who comes back to the app hours later has "come back to the page", and must not keep
        // yesterday's page (and phase header) because the tab itself never changed.
        val appInForeground by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val forYouPageInView = forYouPageActive && appInForeground.isAtLeast(Lifecycle.State.STARTED)
        val mapPageActive = browsePageVisible && settledTab == StartPage.MAP

        LaunchedEffect(
            tracks,
            forYouPageInView,
            libraryWorlds.worlds,
            libraryWorlds.worldLibraryIds,
            discoveryMixLabel,
            semanticMixLabels,
            includeNoveltyMixes,
            forYouPage.personalizationRevision,
            historyRevision,
            smartExclusionState,
            playlistPresentationIdentity,
        ) {
            if (!forYouPageInView) {
                // Off screen: whatever changes now is applied when the page comes back into view.
                forYouPage.currentSinceShown = false
                return@LaunchedEffect
            }
            val loaded = tracks ?: return@LaunchedEffect
            val loadedIds = loaded.map(TrackDescriptor::id)
            val requestedWorlds = if (libraryWorlds.worldLibraryIds == loadedIds) libraryWorlds.worlds else emptyList()
            val clock = localTimePoint(epochMillis())
            val pageDaypart = ForYouRhythm.daypartOf(clock.hourOfDay)
            // Built once, and again only when its inputs change — the worlds arrive later on a cold
            // library, because the text index fills in the background. What the listener does and
            // the clock (a listen, a new phase, a new day) never re-lay the page while it is on
            // screen: a page that regroups itself while being read is indistinguishable from a
            // broken one. They are applied the next time the page comes into view.
            if (
                !forYouPage.forYou.isEmpty &&
                forYouPage.builtWorlds == requestedWorlds &&
                forYouPage.builtForYouLibraryIds == loadedIds &&
                forYouPage.builtForYouPlaylistIdentity == playlistPresentationIdentity &&
                forYouPage.builtForYouExclusions == smartExclusionState &&
                forYouPage.builtIncludeNoveltyMixes == includeNoveltyMixes &&
                forYouPage.builtPersonalizationRevision == forYouPage.personalizationRevision &&
                (
                    forYouPage.currentSinceShown ||
                        (
                            forYouPage.builtHistoryRevision == historyRevision &&
                                forYouPage.builtDay == clock.epochDay &&
                                forYouPage.builtDaypart == pageDaypart
                            )
                    )
            ) {
                forYouPage.currentSinceShown = true
                forYouPage.forYouRefreshing = false
                forYouPage.forYouBuilding = false
                return@LaunchedEffect
            }
            forYouPage.forYouBuilding = true
            val historySnapshot = try {
                Triple(
                    AppGraph.history.stats(),
                    AppGraph.history.recentEvents(RECENT_EVENTS_FOR_YOU),
                    AppGraph.history.firstListenAtMs(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Keep the last good page and retry on the next keyed refresh. Treating a failed
                // read as empty history would silently replace personalized cards with cold-start
                // recommendations and mark that false page as successfully built.
                println("For You: could not load listening history: $failure")
                forYouPage.forYouRefreshing = false
                forYouPage.forYouBuilding = false
                forYouPage.forYouLoadFailed = forYouPage.forYou.isEmpty
                snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                return@LaunchedEffect
            }
            val (stats, recentEvents, firstListenAtMs) = historySnapshot
            val excluded = buildSet {
                loaded.asSequence()
                    .filter { smartExclusionState.excludes(it) }
                    .mapTo(this) { it.id }
                currentTrack?.id?.let(::add)
                // What is already queued to play next is not offered again (for-you-ux.md §8.3) —
                // only the near part of the queue, so playing the whole library does not empty the page.
                val now = playback.state.value
                now.queue.asSequence()
                    .drop(now.queueIndex + 1)
                    .take(FOR_YOU_QUEUED_EXCLUSION)
                    .mapTo(this) { it.id }
            }
            // Discovery offers ignored on recent prior days yield their slots today. Same-day
            // offers never cool anything — the page stays stable within its day.
            val nowForPage = epochMillis()
            val today = localTimePoint(nowForPage).epochDay
            val cooled = try {
                AppGraph.forYouImpressions.lastShownDays(beforeEpochDay = today)
                    .filterValues { it >= today - ForYouRhythm.FRESH_COOLDOWN_DAYS }
                    .keys
                    .filterTo(HashSet()) { (stats[it]?.plays ?: 0) == 0 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // A cooldown is an optimization, never a gate: on a failed read the page simply
                // may repeat an offer.
                emptySet()
            }
            // What each row showed on prior days (see ForYouShownHistory). Like the cooldown, an
            // optimization: a failed read just means nothing is demoted or retired.
            val shown = try {
                ForYouShownHistory.from(
                    AppGraph.forYouImpressions.shownDaysBySection(beforeEpochDay = today),
                    stats,
                    today,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                ForYouShownHistory()
            }
            val rebuiltPage = withContext(Dispatchers.Default) {
                ForYouBuilder.build(
                    library = loaded,
                    stats = stats,
                    recentEvents = recentEvents,
                    nowMs = nowForPage,
                    excluded = excluded,
                    playlists = playlistsState.playlists,
                    worlds = requestedWorlds,
                    discoveryMixLabel = discoveryMixLabel,
                    includeNoveltyMixes = includeNoveltyMixes,
                    semanticMixLabels = semanticMixLabels,
                    cooledDiscoveries = cooled,
                    revisitShownDays = shown.revisit,
                    daypartShownDays = shown.daypart,
                    retiredDiscoveries = shown.retired,
                    logStartMs = firstListenAtMs,
                )
            }
            // Commit the page and its input key together. If this effect is cancelled while the
            // background build runs, neither value advances and the next visit retries normally.
            forYouPage.forYou = rebuiltPage
            // No impression is written here. Building a page offers nothing: the rows below compose
            // only the couple of cards a phone fits, and the hero is not part of
            // discoveryOffers at all. The tab reports what actually reached the viewport instead
            // (see ForYouTab's onCardShown), which is the only honest unit of "shown".
            forYouPage.builtWorlds = requestedWorlds
            forYouPage.builtForYouLibraryIds = loadedIds
            forYouPage.builtForYouPlaylistIdentity = playlistPresentationIdentity
            forYouPage.builtForYouExclusions = smartExclusionState
            forYouPage.builtIncludeNoveltyMixes = includeNoveltyMixes
            forYouPage.builtPersonalizationRevision = forYouPage.personalizationRevision
            forYouPage.builtHistoryRevision = historyRevision
            forYouPage.builtDay = localTimePoint(nowForPage).epochDay
            forYouPage.builtDaypart = ForYouRhythm.daypartOf(localTimePoint(nowForPage).hourOfDay)
            forYouPage.currentSinceShown = true
            forYouPage.forYouRefreshing = false
            forYouPage.forYouBuilding = false
            forYouPage.forYouLoadFailed = false
        }

        LaunchedEffect(libraryIndexingRequest, smartEligibleTracks) {
            val request = libraryIndexingRequest ?: return@LaunchedEffect
            val loaded = request.tracks
            // Search, playlists, and collection screens often start playback from a filtered
            // subset. SMART is a library journey, so keep its candidate universe independent of
            // whichever small list happened to contain the track the listener tapped.
            playback.setSmartLibrary(smartEligibleTracks)
            AppGraph.smartLibraryTracks.value = smartEligibleTracks
            val indexing = AppGraph.automaticIndexing.value
            libraryWorlds.metadataVectorsReady =
                indexing.trackIds == loaded.map(TrackDescriptor::id) && indexing.metadataReady
            libraryWorlds.audioVectorsReady =
                indexing.trackIds == loaded.map(TrackDescriptor::id) && indexing.complete
            val notificationTitle = getString(Res.string.indexing_notification_title)
            AppGraph.ensureAutomaticIndexing(
                tracks = loaded,
                librarySnapshotAuthoritative = request.librarySnapshotAuthoritative,
                notificationTitle = notificationTitle,
            ) { done, total, etaMinutes ->
                if (etaMinutes == null) {
                    getString(Res.string.indexing_notification_progress, done, total)
                } else {
                    getString(
                        Res.string.indexing_notification_progress_eta,
                        done,
                        total,
                        etaMinutes,
                    )
                }
            }
        }

        // The UI observes the app-lifetime worker; it does not own it. Recreating MainActivity
        // therefore reconnects to the same scan instead of starting a duplicate or cancelling it.
        LaunchedEffect(libraryIndexingRequest, smartEligibleTracks) {
            val loaded = libraryIndexingRequest?.tracks ?: return@LaunchedEffect
            val eligible = smartEligibleTracks
            val trackIds = loaded.map(TrackDescriptor::id)
            AppGraph.automaticIndexing.collect { indexing ->
                if (indexing.trackIds != trackIds) return@collect
                if (libraryWorlds.metadataVectorsReady != indexing.metadataReady) {
                    libraryWorlds.metadataVectorsReady = indexing.metadataReady
                }
                if (libraryWorlds.audioVectorsReady != indexing.complete) {
                    libraryWorlds.audioVectorsReady = indexing.complete
                }
                // Failure formatting and the opt-in quality audit are only useful once the run is
                // complete. Keeping per-batch progress out of Compose avoids a second source of
                // whole-shell invalidation while the background worker is busy.
                if (!indexing.complete) return@collect
                println("SMART: progressive local index complete (${engine.state.value})")

                smartQualityDiagnosticSeeds().forEach { query ->
                    val seed = eligible.firstOrNull { track ->
                        track.title.orEmpty().contains(query, ignoreCase = true) ||
                            "${track.artist.orEmpty()} - ${track.title.orEmpty()}"
                                .contains(query, ignoreCase = true)
                    }
                    if (seed == null) {
                        println("[SMART_QUALITY] missing seed=$query")
                    } else {
                        val queue = engine.smartQueue(
                            seed,
                            eligible,
                            smartQueueLength,
                            smartHistoryFor(AppGraph.history, seed),
                            AppGraph.smartCompanionGroups.value,
                        )
                        val byId = eligible.associateBy { it.id }
                        val recommendations = queue.mapNotNull(byId::get)
                            .joinToString(" | ") { track ->
                                "${track.artist.orEmpty()} — ${track.title.orEmpty()}"
                            }
                        println(
                            "[SMART_QUALITY] seed=${seed.artist.orEmpty()} — " +
                                "${seed.title.orEmpty()} queue=$recommendations",
                        )
                    }
                }
            }
        }

        // The regions the library falls into. Metadata gives a fast first-launch answer. Once the
        // acoustic scan covers the library, conservative late fusion adds how the tracks actually
        // sound; if either encoder is sparse, LibraryVectorFusion retains the better-covered
        // single space instead of manufacturing zero vectors or hiding most of the collection.
        // Readiness is OBSERVED here rather than keyed on. Keying the effect on the two flags
        // restarted it the moment the second one flipped — which lands mid-clustering, so the
        // coroutine was cancelled before it could record what it had just built, and the next
        // pass repeated the identical work (measured: 280 ms then a redundant 217 ms on a cold
        // launch). Collecting instead lets each pass finish and remember its inputs, so the
        // metadata -> audio upgrade still rebuilds while a same-inputs repeat does not.
        // Puts the previous session back in the player — same track, same shuffle mode, PAUSED —
        // so SMART survives an app restart without being re-armed by hand. One attempt per
        // process: a restore that raced a user tap must not fire again later and yank the queue
        // out from under whatever the user chose instead.
        PlaybackRestoreEffect(
            playback, library, tracks, playlistsState, externalAudioOpener,
            onOpenPlayer = { showNowPlaying = true },
            onUnavailable = {
                scope.launch { snackbar.showSnackbar(getString(Res.string.snack_audio_open_unavailable)) }
            },
        )

        // Library clustering serves these pages only. Starting playback or browsing
        // Tracks must not allocate a vector space or run discovery for an unopened/hidden page.
        LaunchedEffect(tracks, playlistPresentationIdentity, includeNoveltyMixes, forYouPageActive, mapPageActive) {
            if (!forYouPageActive && !mapPageActive) return@LaunchedEffect
            val loaded = tracks ?: return@LaunchedEffect
            val loadedIds = loaded.map(TrackDescriptor::id)
            // Observe the readiness tier, not a boolean OR. Metadata becomes ready first; when
            // audio later completes, `true || true` is still true and used to suppress the fused
            // rebuild forever.
            snapshotFlow { libraryWorlds.metadataVectorsReady to libraryWorlds.audioVectorsReady }
                .distinctUntilChanged()
                .collect { (metadataReady, audioReady) ->
                    if (!metadataReady && !audioReady) return@collect
                    try {
                        mapWorkGate.withLock {
                        // Check raw-vector identity without allocating the fused matrix on a warm
                        // page return. A changed vector, tag, playlist, preference or readiness tier
                        // still invalidates discovery, even if the library population is unchanged.
                        val coverage = engine.libraryMixCoverage(loadedIds) ?: return@collect
                        val descriptorIdentity = loaded.map(TrackDescriptor::worldIdentity)
                        val cachedKey = libraryWorlds.builtWorldsKey
                        if (
                            cachedKey != null &&
                            libraryWorlds.builtWorldNoveltyPreference == includeNoveltyMixes &&
                            libraryWorlds.builtWorldReadiness == (metadataReady to audioReady) &&
                            cachedKey.playlistIdentity == playlistPresentationIdentity &&
                            cachedKey.discovery.trackIds == loadedIds &&
                            cachedKey.discovery.source == coverage.source &&
                            cachedKey.discovery.vectorFingerprint == coverage.fingerprint &&
                            cachedKey.discovery.descriptorIdentity == descriptorIdentity
                        ) {
                            // A refused newer population must not hide reusable regions if the
                            // library returns to this cached population. Projection checks its own budget.
                            libraryWorlds.memoryLimited = false
                            return@collect
                        }
                        // Measured on the map lane, where a short reading may collect garbage first.
                        libraryWorlds.memoryLimited = withContext(AppGraph.mapLayoutDispatcher) {
                            !mapWorkFits(4L * coverage.size * 1344 + 8L * 1024 * 1024)
                        }
                        if (libraryWorlds.memoryLimited) return@collect
                        val features = engine.libraryMixFeatures(
                            ids = loadedIds,
                            // This preference is the listener's explicit request for semantic novelty
                            // and effect routing, so loading the shared model here is useful work. The
                            // default path keeps restored/failed-only libraries completely lazy.
                            loadMissingSemantics = includeNoveltyMixes,
                        ) ?: return@collect
                        val discoveryKey = LibraryWorldDiscoveryKey(
                            trackIds = loadedIds,
                            source = features.vectorSpace.source,
                            vectorFingerprint = features.vectorSpace.fingerprint,
                            descriptorIdentity = descriptorIdentity,
                            semanticsCount = features.semantics.size,
                        )
                        val worldsKey = LibraryWorldsKey(
                            discovery = discoveryKey,
                            playlistIdentity = playlistPresentationIdentity,
                        )
                        if (worldsKey == libraryWorlds.builtWorldsKey) {
                            libraryWorlds.builtWorldNoveltyPreference = includeNoveltyMixes
                            libraryWorlds.builtWorldReadiness = metadataReady to audioReady
                            return@collect
                        }
                        // Snapshot for the background pass; a world mostly inside one named group
                        // takes that group's name. Playlists come first — the listener's own word
                        // outranks the artist's on a tie — then real (multi-track, one artist's)
                        // albums, whose titles are the artist's own curation.
                        val playlistGroups = playlistsState.playlists.map { playlist ->
                            playlist.name to playlist.trackIds.map(::TrackId)
                        }
                        val cachedDiscovery = libraryWorlds.builtDiscoveredWorlds
                            ?.takeIf { libraryWorlds.builtWorldDiscoveryKey == discoveryKey }
                        // Discovery is derived library work, not an interaction prerequisite. Keep it
                        // on the Map's low-priority serial lane so a cold restore cannot make an
                        // immediately opened page compete with clustering at normal CPU priority.
                        val discovered = cachedDiscovery ?: withContext(AppGraph.mapLayoutDispatcher) {
                            val started = TimeSource.Monotonic.markNow()
                            LibraryWorlds.discover(
                                library = loaded,
                                vectorSpace = features.vectorSpace,
                                semantics = features.semantics,
                                isActive = { isActive },
                                // Discovery takes seconds; one collection first keeps released
                                // matrices from pushing a large library onto the k-means fallback.
                                workBudgetBytes = collectMapGarbage().let { availableMapWorkBytes() },
                            ).also { mixes ->
                                val routed = mixes.groupingBy { it.content }.eachCount()
                                println(
                                    "SMART: built ${mixes.size} ${features.vectorSpace.source} " +
                                        "local mixes (semantic=${features.semantics.size}, " +
                                        "routes=$routed) in " +
                                        "${started.elapsedNow().inWholeMilliseconds} ms",
                                )
                            }
                        }
                        val namedWorlds = withContext(AppGraph.mapLayoutDispatcher) {
                            // Counted over the tracks that exist (a playlist keeps the ids of
                            // deleted files) and over real albums only; the map replay builds the
                            // same groups the same way.
                            val naming = WorldNamingGroups.of(
                                library = loadedIds.toHashSet(),
                                playlists = playlistGroups,
                                albums = LibraryCatalog.build(loaded).albums.map { it.title to it.tracks },
                            )
                            LibraryWorlds.namedAfterGroups(discovered, naming)
                        }
                        libraryWorlds.worldLibraryIds = loadedIds
                        libraryWorlds.worlds = namedWorlds
                        libraryWorlds.builtWorldDiscoveryKey = discoveryKey
                        libraryWorlds.builtDiscoveredWorlds = discovered
                        libraryWorlds.builtWorldsKey = worldsKey
                        libraryWorlds.builtWorldNoveltyPreference = includeNoveltyMixes
                        libraryWorlds.builtWorldReadiness = metadataReady to audioReady
                        libraryWorlds.discoveryFailed = false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        // Derived suggestions are retryable on the next page entry. A history or
                        // model read failure must not take down the player or erase the warm page,
                        // and neither may a heap too small for this library: the fused vector space
                        // (tracks x 1,344 floats) or discovery's clustering can run out of memory on
                        // a low-RAM phone. The page keeps what it had, as the Map layout below does;
                        // whatever the failed pass allocated is unreachable once this lambda returns.
                        println("SMART: could not refresh library suggestions: $failure")
                        libraryWorlds.discoveryFailed = true
                    }
                }
        }

        val mapPage = rememberMapPageModel(
            engine, tracks, libraryWorlds, mapPageActive, historyRevision, discoveryMixLabel, semanticMixLabels,
        )

        var catalog by remember { mutableStateOf<LibraryCatalog?>(null) }
        // SMART colours follow the library: fit its colour axes as tracks are indexed.
        val paletteIds = remember(catalog) { catalog?.songs?.map { it.id }.orEmpty() }
        LatentPaletteUpdater(engine, paletteIds)
        var albumBrowse by remember { mutableStateOf<AlbumBrowseDerivation?>(null) }
        var songsBrowse by remember { mutableStateOf<SongsBrowseDerivation?>(null) }
        val albumSections = albumBrowse?.albumSections.orEmpty()
        val albumTabTracks = albumBrowse?.albumTabTracks.orEmpty()
        LaunchedEffect(tracks) {
            val loaded = tracks
            // Rebuild in place — assign the finished catalog rather than clearing it first. Nulling
            // it swapped the whole songs list for a loading box mid-rescan, which disposed the
            // list's scroll state, so deleting a track teleported the list back to the top. The
            // finished catalog is swapped in atomically instead, and stable row keys keep every
            // surviving row exactly where it was. The sliver of time where a tap could land on a
            // just-deleted row resolves to a harmless no-op — a far better trade than the jump.
            if (loaded != null) {
                val derived = withContext(Dispatchers.Default) {
                    val albums = AlbumBrowseDerivation.of(buildCatalog(loaded), settings.albumSort.value)
                    albums to prepareSongsBrowse(albums.catalog.songs, settings.songSort.value)
                }
                catalog = derived.first.catalog
                albumBrowse = derived.first
                songsBrowse = derived.second
            } else {
                catalog = null
                albumBrowse = null
                songsBrowse = null
            }
        }
        // A new album sort re-derives the Albums tab from the catalog in hand, never rebuilding the
        // catalog. A sort chosen while a rebuild was running is caught here too: the rebuild derived
        // its albums in the order current when it read it. A small library was already re-sorted by
        // the sort control itself (see albumResortRunsInline), so only a large one gets here.
        LaunchedEffect(catalog, albumSortChoice) {
            val current = catalog ?: return@LaunchedEffect
            val shown = albumBrowse
            if (shown != null && shown.catalog === current && shown.choice == albumSortChoice) return@LaunchedEffect
            albumBrowse = withContext(Dispatchers.Default) { AlbumBrowseDerivation.of(current, albumSortChoice) }
        }
        // Keep the last complete snapshot while a new choice is prepared. Cancellation of this
        // effect prevents an older sort (or catalog) from replacing a newer publication.
        LaunchedEffect(catalog, songSortChoice) {
            val current = catalog ?: return@LaunchedEffect
            val shown = songsBrowse
            if (shown != null && shown.source === current.songs && shown.choice == songSortChoice) {
                return@LaunchedEffect
            }
            val requested = songSortChoice
            val prepared = prepareSongsBrowse(current.songs, requested)
            // An already queued continuation can beat the recomposition that cancels this
            // effect. Check live identities too before replacing the visible snapshot.
            if (catalog === current && settings.songSort.value == requested) songsBrowse = prepared
        }
        val groupOrderedCatalog = remember(catalog, groupSortOrders) { catalog?.inGroupOrder(groupSortOrders) }
        val tracksById = remember(catalog) { catalog?.songs?.associateBy { it.id }.orEmpty() }
        // A queued song whose cover changed after it was queued (the tag pass learning that it
        // names no album, issue #12) takes its new descriptor as it starts to play: one player
        // command per song, beyond the rows the tag pass refreshes at once.
        val playingTrackId by remember(playback) {
            playback.state.map { it.track?.id }.distinctUntilChanged()
        }.collectAsState(initial = null)
        // Read from the published library, not the catalog built from it later: a catalog that
        // still lags a refresh would put the old descriptor back into the queue.
        LaunchedEffect(playingTrackId, tracks) {
            val playing = playback.state.value.track ?: return@LaunchedEffect
            if (playing.id != playingTrackId) return@LaunchedEffect
            val fresh = tracks?.firstOrNull { it.id == playing.id } ?: return@LaunchedEffect
            if (fresh.artworkUri != playing.artworkUri) playback.refreshTracks(listOf(fresh))
        }
        val selectedTracks = remember(
            catalog, groupSortOrders, albumTabTracks, songsBrowse, selectedTrackIds,
            selectedCollection, selectedTab,
        ) {
            // Selection is empty during ordinary browsing. Avoid duplicating the Songs list's
            // whole-library O(n log n) sort on every direction change just to produce emptyList().
            if (selectedTrackIds.isEmpty()) return@remember emptyList()
            // On a group tab the selection was made album-by-album (folder-by-folder, …), so it
            // plays in group order — a selected album keeps its own track order instead of being
            // reshuffled into the Songs sort.
            val groupOrderedSource = selectedCollection
                ?.takeIf { it.allowsTrackSelection }
                ?.tracks
                ?: when (selectedTab) {
                    StartPage.ALBUMS -> albumTabTracks
                    StartPage.ARTISTS -> groupOrderedCatalog?.artists?.flatMap { it.tracks }
                    StartPage.GENRES -> groupOrderedCatalog?.genres?.flatMap { it.tracks }
                    StartPage.FOLDERS -> groupOrderedCatalog?.let { shown ->
                        folderTabSelectionOrder(shown, groupSortOrders.folders, selectedTrackIds, settings.folderView.value)
                    }
                    else -> null
                }
            // A choice may still be sorting in the background: selected tracks follow the
            // order visible on screen, just like tapping a row, until that snapshot is replaced.
            (groupOrderedSource ?: songsBrowse?.displayOrder.orEmpty())
                .filter { it.id in selectedTrackIds }
        }
        LaunchedEffect(catalog) {
            updateTrackSelection(selectedTrackIds.intersect(tracksById.keys))
        }

        suspend fun refreshPlaylistMemberships() {
            publishPlaylists(AppGraph.playlists.all())
        }

        suspend fun refreshPlaylistStats() {
            val stats = AppGraph.history.stats()
            // What the listener calls plays: a start skipped within 30 seconds is not one.
            playlistsState.playCounts = stats.mapValues { it.value.listens }
            playlistsState.lastPlayedAt = stats.mapValues { it.value.lastPlayedAtMs }
        }

        suspend fun refreshPlaylistStatsBestEffort() {
            try {
                refreshPlaylistStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Membership was already durably changed and reflected locally. Statistics are a
                // derived enhancement, so preserve the last good values and retry on tab return.
                println("Playlists: could not refresh listening statistics: $failure")
            }
        }

        suspend fun refreshPlaylistMembershipsBestEffort() {
            try {
                refreshPlaylistMemberships()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                println("Playlists: could not refresh memberships: $failure")
            }
        }

        suspend fun refreshPlaylistsWithFeedback() {
            var failed = false
            try {
                refreshPlaylistMemberships()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failed = true
            }
            try {
                refreshPlaylistStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failed = true
            }
            if (failed) {
                snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
            }
        }

        suspend fun runPlaylistMutation(
            showFailureSnackbar: Boolean = true,
            change: suspend () -> Unit,
        ): Boolean {
            return try {
                change()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (showFailureSnackbar) {
                    snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                }
                false
            }
        }

        LaunchedEffect(Unit) { refreshPlaylistsWithFeedback() }
        // Auto playlists are derived from listening, so refresh them whenever
        // the user comes back to the tab rather than only at startup.
        LaunchedEffect(selectedTab) {
            if (selectedTab == StartPage.PLAYLISTS) {
                playlistsState.autoPlaylistClockMs = epochMillis()
                refreshPlaylistsWithFeedback()
            }
        }
        // A listen can complete while this tab stays visible. Republish the derived auto lists
        // immediately instead of requiring a tab round-trip before Never Played/Rediscover move.
        LaunchedEffect(historyRevision) {
            if (selectedTab == StartPage.PLAYLISTS) refreshPlaylistStatsBestEffort()
        }

        // Every SMART planner (in-app and the playback chooser) reads the marked playlists from
        // this flow; kept in lockstep with the playlists state.
        LaunchedEffect(
            playlistsState.playlistsLoaded,
            playlistsState.playlists,
            playlistsState.playlistsAvailableForSourceRestore,
        ) {
            // The first successful read reconstructs the policy that produced a persisted SMART
            // future. Treat it as launch state, not as a user edit: pruning the saved lookahead at
            // this point would defeat exact resume and could load the lazy model for no reason.
            if (!playlistsState.playlistsLoaded) return@LaunchedEffect
            AppGraph.smartUserPlaylists.value = playlistsState.playlists.smartUserPlaylists()
            // Not against the empty list a failed launch read leaves: that would forget every order.
            AppGraph.settings.forgetDeletedPlaylistOrders(playlistsState.completePlaylists)
            val groups = playlistsState.playlists.smartCompanionMemberships()
            val changed = groups != AppGraph.smartCompanionGroups.value
            val shouldInvalidateFuture = shouldInvalidateSmartFuture(
                policyInitialized = playlistsState.companionGroupsInitialized,
                previous = AppGraph.smartCompanionGroups.value,
                updated = groups,
            )
            playlistsState.companionGroupsInitialized = true
            if (changed) {
                AppGraph.smartCompanionGroups.value = groups
            }
            if (shouldInvalidateFuture) {
                // Controllers keep a bounded generated lookahead. A policy or membership change
                // must prune that stale future now; clearing only the chooser's unserved plan
                // would postpone the user's explicit choice for roughly twenty tracks.
                playback.invalidateSmartFuture()
            }
        }
        LaunchedEffect(
            catalog,
            playlistsState.playCounts,
            playlistsState.lastPlayedAt,
            favoriteIds,
            playlistsState.autoPlaylistClockMs,
        ) {
            val songs = catalog?.songs
            playlistsState.autoPlaylists = if (songs == null) {
                emptyList()
            } else {
                withContext(Dispatchers.Default) {
                    AutoPlaylists.build(
                        songs,
                        playlistsState.playCounts,
                        playlistsState.lastPlayedAt,
                        favoriteIds,
                        playlistsState.autoPlaylistClockMs,
                    )
                }
            }
            // An open Favorites page lists the hearts, so it follows them; a library still loading
            // has no tracks to show them with.
            val openPage = selectedCollection?.takeIf { songs != null } ?: return@LaunchedEffect
            val hearted = playlistsState.autoPlaylists
                .firstOrNull { it.kind == AutoPlaylistKind.FAVORITES }?.tracks.orEmpty()
            val followed = openPage.followingFavorites(hearted) { page ->
                page.inChosenOrder(
                    AppGraph.settings.playlistSortOrders.value,
                    playlistsState.playlists, playlistsState.autoPlaylists,
                )
            } ?: return@LaunchedEffect
            val subtitle = trackCountLabel(hearted.size)
            if (selectedCollection === openPage) {
                applySelectedCollection(followed.copy(subtitle = subtitle))
                val shown = hearted.mapTo(HashSet()) { it.id }
                updateTrackSelection(selectedTrackIds.filterTo(HashSet()) { it in shown })
            }
        }

        fun tracksOf(playlist: Playlist): List<TrackDescriptor> =
            playlist.trackIds.mapNotNull { tracksById[TrackId(it)] }

        val playlistArtworkUris = remember(playlistsState.playlists) {
            playlistsState.playlists.mapNotNull { playlist ->
                playlistCoverUri(playlist.customArtworkRef)?.let { playlist.id to it }
            }.toMap()
        }
        var coverEditTargetId by rememberSaveable { mutableStateOf<String?>(null) }
        var coverSaveInProgress by remember { mutableStateOf(false) }
        suspend fun discardUnusedPlaylistCover(reference: String?) {
            if (reference == null) return
            try {
                if (AppGraph.playlists.all().none { it.customArtworkRef == reference }) {
                    deletePlaylistCover(reference)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Cleanup is best effort; a saved playlist must not be rolled back because an
                // obsolete app-owned image could not be removed.
            }
        }
        suspend fun savePlaylistCover(id: String, reference: String?) {
            var saved = false
            var interrupted = false
            try {
                val previous = AppGraph.playlists.all().firstOrNull { it.id == id }
                if (previous == null) return
                saved = AppGraph.playlists.setArtwork(id, reference)
                if (!saved) return
                publishPlaylists(AppGraph.playlists.all())
                selectedCollection?.takeIf { it.playlistId == id }?.let { selection ->
                    updateSelectedCollection(selection.copy(
                        artworkUri = playlistCoverUri(reference)
                            ?: selection.tracks.firstNotNullOfOrNull { it.artworkUri },
                    ))
                }
                discardUnusedPlaylistCover(previous.customArtworkRef)
            } catch (cancelled: CancellationException) {
                // A cancelled durable write can already have committed. Leave its file intact.
                interrupted = true
                throw cancelled
            } catch (_: Throwable) {
                snackbar.showSnackbar(getString(Res.string.playlist_cover_error))
            } finally {
                coverSaveInProgress = false
                if (!saved && !interrupted) discardUnusedPlaylistCover(reference)
            }
        }
        val pickPlaylistCover = rememberPlaylistCoverPicker { result ->
            val target = coverEditTargetId
            when (result) {
                is PlaylistCoverPickResult.Selected -> {
                    // The native request has completed. Clear its saveable ownership before
                    // launching a cancellable composition job, so rotation during the store
                    // write cannot restore a phantom picker and leave editing disabled.
                    coverEditTargetId = null
                    coverSaveInProgress = target != null
                    scope.launch {
                        if (target != null) {
                            savePlaylistCover(target, result.reference)
                        } else {
                            discardUnusedPlaylistCover(result.reference)
                        }
                    }
                }
                PlaylistCoverPickResult.Cancelled -> coverEditTargetId = null
                PlaylistCoverPickResult.Failed -> {
                    coverEditTargetId = null
                    scope.launch { snackbar.showSnackbar(getString(Res.string.playlist_cover_error)) }
                }
            }
        }
        fun changePlaylistCover(playlist: Playlist) {
            if (coverEditTargetId != null || coverSaveInProgress) return
            coverEditTargetId = playlist.id
            pickPlaylistCover()
        }
        fun resetPlaylistCover(playlist: Playlist) {
            if (coverEditTargetId != null || coverSaveInProgress || playlist.customArtworkRef == null) return
            coverSaveInProgress = true
            scope.launch { savePlaylistCover(playlist.id, null) }
        }
        fun togglePlaylistSmart(playlist: Playlist) {
            scope.launch {
                if (!runPlaylistMutation {
                        AppGraph.playlists.toggleIncludeInSmart(playlist.id)
                    }
                ) return@launch
                refreshPlaylistMembershipsBestEffort()
            }
        }

        // Playlists as an open format: export goes through the platform's document picker with
        // real file paths where the platform has them; import matches entries back against the
        // library by what survives a device change (the path below its root, then metadata).
        val m3uExchange = rememberLocalBackupFileExchange(
            exportMimeType = M3U_MIME_TYPE,
            importMimeTypes = listOf(
                M3U_MIME_TYPE,
                "audio/mpegurl",
                "application/x-mpegurl",
                "application/vnd.apple.mpegurl",
                "text/plain",
                "application/octet-stream",
            ),
            // Several files at once, each its own playlist (#21).
            allowMultipleImport = true,
            onExportResult = { result ->
                if (result is LocalBackupFileResult.Failure) {
                    scope.launch {
                        snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                    }
                }
            },
            onImportResult = { result ->
                when (result) {
                    is LocalBackupFileResult.Success -> scope.launch {
                        val imports = importM3uFiles(
                            result.value.documents,
                            catalog?.songs.orEmpty(),
                            library::filePaths,
                        ).filter { it.entryCount > 0 }
                        if (imports.none { it.tracks.isNotEmpty() }) {
                            snackbar.showSnackbar(getString(Res.string.snack_m3u_import_failed))
                            return@launch
                        }
                        // New playlists, or Favorites: chosen knowing how much of the files was found.
                        sheets.m3uImportOffer = M3uImportOffer(
                            imports,
                            skippedFiles = result.value.documents.size + result.value.unreadableCount - imports.size,
                        )
                    }
                    is LocalBackupFileResult.Failure -> scope.launch {
                        snackbar.showSnackbar(getString(Res.string.snack_m3u_import_failed))
                    }
                    LocalBackupFileResult.Cancelled -> Unit
                }
            },
        )
        suspend fun importM3uAsPlaylists(offer: M3uImportOffer) {
            val created = createM3uPlaylists(offer, getString(Res.string.playlist_imported_name)) { name, ids ->
                AppGraph.playlists.create(name, ids)
            }
            // A failure part-way still leaves the earlier files' playlists: show them either way,
            // and say how many were made.
            refreshPlaylistMembershipsBestEffort()
            snackbar.showSnackbar(m3uPlaylistsCreatedMessage(offer, created))
        }
        // A playlist, or Favorites newest first: one exporter, one relative-paths setting.
        fun exportAsM3u(name: String, tracks: () -> List<TrackDescriptor>) {
            scope.launch {
                exportTracksAsM3u(name, tracks(), library, settings.relativePlaylistPaths.value, m3uExchange)
            }
        }
        fun exportPlaylistAsM3u(playlist: Playlist) = exportAsM3u(playlist.name) { tracksOf(playlist) }

        // The open playlist's entries that are not on this device.
        val playlistAvailability = rememberOpenPlaylistAvailability(
            openPlaylistId = selectedCollection?.playlistId,
            playlists = playlistsState.playlists,
            library = libraryIndexingRequest,
            missing = library::missingTrackIds,
        )

        fun removeUnavailablePlaylistTracks(playlistId: String, title: String) {
            val request = libraryIndexingRequest ?: return
            val shown = playlistAvailability.unavailable?.takeIf { it.playlistId == playlistId }?.ids.orEmpty()
            if (playlistAvailability.removalInFlight || shown.isEmpty()) return
            playlistAvailability.removalInFlight = true
            scope.launch {
                val removal = try {
                    Result.success(
                        removeUnavailablePlaylistEntries(
                            AppGraph.playlists, playlistId, shown, request, library::missingTrackIds,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Result.failure(failure)
                } finally {
                    playlistAvailability.removalInFlight = false
                }
                if (removal.isFailure) {
                    snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                    return@launch
                }
                // Null: every entry came back since the notice was drawn. The list already shows
                // them; only the notice is out of date.
                val change = removal.getOrNull() ?: run {
                    playlistAvailability.dismiss(playlistId)
                    return@launch
                }
                // The rows were never shown, so the open page keeps its list as it is. The notice
                // goes at once; the membership change below asks the device again.
                playlistAvailability.dismiss(playlistId)
                playlistsState.playlists = playlistsState.playlists.map { stored ->
                    if (stored.id == playlistId) change.appliedTo(stored) else stored
                }
                val result = snackbar.showSnackbar(
                    message = getString(
                        Res.string.snack_playlist_track_removed,
                        bidiWrap(title, layoutDirection),
                    ),
                    actionLabel = getString(Res.string.action_undo),
                    withDismissAction = true,
                )
                if (result != SnackbarResult.ActionPerformed) return@launch
                val restored = try {
                    AppGraph.playlists.restoreTracksIfUnchanged(playlistId, change)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    false
                }
                if (!restored) {
                    snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                    return@launch
                }
                playlistsState.playlists = playlistsState.playlists.map { stored ->
                    if (stored.id == playlistId) change.revertedIn(stored) else stored
                }
            }
        }

        val deleteTrack = rememberTrackDeleter { report ->
            scope.launch {
                if (report.deleted > 0) {
                    // Retain the library's permission-aware pruning after any successful removal.
                    scanLibrary()
                    playback.retainQueue(
                        library.allKnownTracks().mapTo(mutableSetOf()) { track -> track.id },
                    )
                }
                val message = when {
                    report.isSilent -> null
                    report.needsSummary -> getString(
                        Res.string.snack_track_delete_summary,
                        report.deleted, report.denied, report.failed, report.cancelled,
                    )
                    report.denied > 0 -> getString(Res.string.snack_track_delete_denied)
                    report.failed > 0 -> getString(Res.string.snack_track_delete_failed)
                    else -> getString(Res.string.snack_track_deleted)
                }
                message?.let { snackbar.showSnackbar(it) }
            }
        }

        fun retryAutomaticIndexing() {
            val loaded = tracks ?: return
            scope.launch {
                val notificationTitle = getString(Res.string.indexing_notification_title)
                AppGraph.ensureAutomaticIndexing(
                    tracks = loaded,
                    librarySnapshotAuthoritative =
                        libraryIndexingRequest?.librarySnapshotAuthoritative ?: false,
                    notificationTitle = notificationTitle,
                    force = true,
                ) { done, total, etaMinutes ->
                    if (etaMinutes == null) {
                        getString(Res.string.indexing_notification_progress, done, total)
                    } else {
                        getString(
                            Res.string.indexing_notification_progress_eta,
                            done,
                            total,
                            etaMinutes,
                        )
                    }
                }
            }
        }

        // The page assembles on arrival at the tab, and MapTab's focus effect fires as soon as
        // it is ready — so the action works even before the first Map visit.
        fun showTrackOnMap(track: TrackDescriptor) {
            mapPage.mapFocusTrackId = track.id
            updateTrackSelection(emptySet())
            updateSelectedCollection(null)
            showSearch = false
            showNowPlaying = false
            navigateToRootTab(StartPage.MAP)
        }

        // "Playing from …" names a place, so a tap goes there. A collection is found again by the
        // stable playlist or library-group identity; a
        // surface becomes the current tab. What cannot be found again — an auto playlist, a
        // hand-picked selection — returns false, and the chip raises the queue instead.
        fun openQueueSource(source: QueueSource): Boolean {
            fun leaveForRoot(tab: StartPage): Boolean {
                if (tab !in visiblePages) return false
                updateTrackSelection(emptySet())
                updateSelectedCollection(null)
                showSearch = false
                showNowPlaying = false
                navigateToRootTab(tab)
                return true
            }
            return when (source.kind) {
                QueueSourceKind.TRACKS -> leaveForRoot(StartPage.TRACKS)
                QueueSourceKind.MAP -> leaveForRoot(StartPage.MAP)
                QueueSourceKind.FOR_YOU -> leaveForRoot(StartPage.FOR_YOU)
                QueueSourceKind.SEARCH -> {
                    updateTrackSelection(emptySet())
                    updateSelectedCollection(null)
                    showNowPlaying = false
                    showSearch = true
                    true
                }
                QueueSourceKind.COLLECTION, QueueSourceKind.LIBRARY_GROUP -> {
                    val build: suspend () -> CollectionSelection = when (source.kind) {
                        QueueSourceKind.COLLECTION -> {
                            val playlist = source.reference?.let { id ->
                                playlistsState.playlists.firstOrNull { it.id == id }
                            } ?: return false
                            {
                                val resolved = tracksOf(playlist)
                                CollectionSelection(
                                    title = playlist.name,
                                    subtitle = trackCountLabel(resolved.size),
                                    artworkUri = playlistCoverUri(playlist.customArtworkRef)
                                        ?: resolved.firstNotNullOfOrNull { it.artworkUri },
                                    tracks = resolved,
                                    allowsTrackSelection = true,
                                    playlistId = playlist.id,
                                )
                            }
                        }
                        else -> {
                            // An auto playlist is rebuilt from its kind, exactly as the Playlists
                            // tab opens it; its tracks are the current ones, not a stale copy.
                            val auto = source.autoPlaylistKind()
                                ?.let { kind -> playlistsState.autoPlaylists.firstOrNull { it.kind == kind } }
                            if (auto != null) {
                                {
                                    CollectionSelection(
                                        title = getString(auto.kind.titleRes()),
                                        subtitle = trackCountLabel(auto.tracks.size),
                                        artworkUri = auto.tracks.firstNotNullOfOrNull { it.artworkUri },
                                        tracks = auto.tracks,
                                        allowsTrackSelection = true,
                                        routeId = "auto:${auto.kind.name}",
                                    )
                                }
                            } else when (val target = source.resolveGroup(catalog)) {
                                is QueueSourceGroup.Album -> { { target.group.toSelection() } }
                                is QueueSourceGroup.Artist -> { { target.group.toSelection(settings.artistAlbumSort.value) } }
                                is QueueSourceGroup.Genre -> { { target.group.toSelection() } }
                                is QueueSourceGroup.Folder -> { { target.group.toSelection() } }
                                is QueueSourceGroup.FolderBranch -> {
                                    { target.node.toTreeSelection(settings.groupSortOrders.value.folders) }
                                }
                                is QueueSourceGroup.FolderTracks -> { { target.node.toTracksSelection() } }
                                null -> return false
                            }
                        }
                    }
                    openCollection(
                        build = build,
                        afterOpen = {
                            updateTrackSelection(emptySet())
                            showNowPlaying = false
                            showSearch = false
                        },
                    )
                    true
                }
            }
        }

        fun showAlbumOf(track: TrackDescriptor) {
            val album = catalog?.albums
                ?.firstOrNull { group -> group.tracks.any { it.id == track.id } } ?: return
            openCollection(
                build = { album.toSelection() },
                afterOpen = {
                // Going somewhere must also leave where you were: the collection renders in the
                // browse branch, so a full player or search screen left open would keep covering
                // the destination — the tap would look like it did nothing.
                showNowPlaying = false
                showSearch = false
                },
            )
        }

        fun showArtist(artist: ArtistGroup) {
            openCollection(
                build = { artist.toSelection(settings.artistAlbumSort.value) },
                afterOpen = {
                // Same as showAlbumOf: navigation closes the surfaces above the destination.
                showNowPlaying = false
                showSearch = false
                },
            )
        }

        fun showArtistOf(track: TrackDescriptor) {
            val artists = artistDestinations(track, catalog)
            if (artists.size == 1) {
                showArtist(artists.single())
            } else if (artists.isNotEmpty()) {
                sheets.artistChoices = artists
            }
        }

        fun invalidateSmartRecommendationCaches() {
            // The exclusion store publishes only after its durable write succeeds. Clearing these
            // keys at that point removes stale cards immediately, while the state-keyed effect
            // above rebuilds the page from the latest rules even when a change came from elsewhere.
            forYouPage.forYou = ForYouPage()
            forYouPage.builtWorlds = null
            forYouPage.builtForYouLibraryIds = null
            forYouPage.builtForYouPlaylistIdentity = null
            forYouPage.builtForYouExclusions = null
            forYouPage.forYouRefreshing = selectedTab == StartPage.FOR_YOU
        }

        suspend fun applySmartExclusion(change: suspend () -> Unit): Boolean {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                snackbar.showSnackbar(getString(Res.string.snack_smart_exclusion_failed))
                return false
            }

            invalidateSmartRecommendationCaches()
            // Recomposition will run the state-keyed synchronization effect as well. Updating the
            // controller here closes the small window in which an active SMART queue could ask for
            // another candidate using the old eligible library.
            try {
                val state = smartExclusions.state.value
                val eligible = tracks.orEmpty().filterNot { state.excludes(it) }
                playback.setSmartLibrary(eligible)
                AppGraph.smartLibraryTracks.value = eligible
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The observable state remains authoritative; its LaunchedEffect retries this sync.
            }
            return true
        }

        fun changeSmartExclusion(
            successMessage: StringResource,
            change: suspend () -> Unit,
            undo: suspend () -> Unit,
        ) {
            scope.launch {
                if (!applySmartExclusion(change)) return@launch
                val result = snackbar.showSnackbar(
                    message = getString(successMessage),
                    actionLabel = getString(Res.string.action_undo),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    applySmartExclusion(undo)
                }
            }
        }

        val settingsVisibilityState = remember { MutableTransitionState(false) }
        settingsVisibilityState.targetState = showSettings
        val settingsOverlayActive = settingsVisibilityState.currentState ||
            settingsVisibilityState.targetState
        var settingsButtonBounds by remember { mutableStateOf<Rect?>(null) }
        val settingsTransition = rememberTransition(settingsVisibilityState, label = "settings-surface")
        // Read this clock only in tiny glyphs and graphics layers, never in the library's rows.
        val settingsOpenProgress = settingsTransition.animateFloat(
            transitionSpec = {
                chromeNavigationSpec(reduceMotion, targetState)
            },
            label = "settings-navigation",
        ) { if (it) 1f else 0f }

        // The full player is an overlay on a library that never leaves composition, the way every
        // mainstream player does it: pulling the player down shows the very page it returns to,
        // and closing it costs no rebuild of the browse shell, its scroll positions or its pages.
        // One progress, 0 for the mini player and 1 for the full one, carries the whole way.
        val playerExpansion = remember { PlayerExpansion(showNowPlaying, scope) }
        // A covered library is still composed, so it would still be drawn under every frame of the
        // player's own motion. At full expansion skip it. Derived, so a drag invalidates the
        // library's draw only when it leaves or reaches the top.
        val libraryDrawn = remember {
            derivedStateOf { libraryDrawnUnderPlayer(playerExpansion.progress) }
        }
        // The library's row badges hold still while the player covers them.
        val libraryCovered = remember {
            derivedStateOf { playerExpansion.progress >= 1f && !playerExpansion.dragging }
        }
        // A field focused in the library (search, a playlist name) would keep the keyboard up over
        // the player now that the field is no longer disposed with the library. It goes as soon as
        // the player starts to cover the page, on a tap or at the first move of a drag. Search
        // takes the keyboard back when the player closes only if it had it.
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val imeInsets = WindowInsets.ime
        val imeDensity = LocalDensity.current
        var searchWantsInput by remember { mutableStateOf(true) }
        LaunchedEffect(showSearch) { if (showSearch) searchWantsInput = true }
        val coverStart = remember { CoverStart() }
        fun beginCover() {
            if (coverStart.begun) return
            coverStart.begun = true
            // This runs on the first frame of a drag: the keyboard is only asked to go if it is up.
            val imeUp = imeInsets.getBottom(imeDensity) > 0
            if (showSearch && searchWantsInput != imeUp) searchWantsInput = imeUp
            focusManager.clearFocus(force = true)
            if (imeUp) keyboard?.hide()
        }
        LaunchedEffect(showNowPlaying) {
            if (showNowPlaying) {
                beginCover()
            } else {
                coverStart.begun = false
                // Focus moved inside the player by a keyboard (Enter on the chevron, Esc) must not
                // stay on its hidden controls; search takes its field back on its own if it had it.
                focusManager.clearFocus(force = true)
            }
        }

        // Opaque floor under the whole shell: during the morph the animating
        // content is smaller than the window, and without this the platform
        // window background shows through as a flash.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
        Box(
            modifier = Modifier.graphicsLayer {
                // Leave the initiating icon anchored while its destination unfolds over it.
                val p = settingsOpenProgress.value
                alpha = 1f - 0.18f * 4f * p * (1f - p)
            },
        ) {
            // The library: always composed, inert and hidden from accessibility while the player is
            // up (or Settings covers both), so neither a touch through an uncovered gap nor
            // TalkBack can reach it. The scrim that dims it as the player rises is drawn in a small
            // layer of its own, so a frame of motion re-records that and not the library, and the
            // gate skips the library entirely while the player covers it.
            //
            // While the sheet is between its ends, the library and its scrim are drawn only where
            // the sheet's opaque surface has uncovered them, one plain rectangle at a time: a
            // rectangle clip lets the renderer skip the page's drawing outside it, which a cut-out
            // clip would not. On a slow GPU drawing the whole page under the player every frame of
            // a pull was the pull's entire extra cost.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawWithContent {
                        if (!libraryDrawn.value) return@drawWithContent
                        val progress = playerExpansion.progress
                        if (progress <= 0f) {
                            // At rest under the pill: the pill draws itself (and nudges sideways
                            // under a swipe), so nothing of the page may be cut away.
                            drawContent()
                            return@drawWithContent
                        }
                        // Measured in the whole window, side safe areas included: the page's
                        // backdrop runs under them, and the open sheet covers them.
                        val window = playerExpansion.sideBleed.window(size)
                        val surface = playerExpansion.surfaceBounds(size).translate(-window.left, 0f)
                        val radius = playerExpansion.surfaceRadius()
                        if (sheetCoversWindow(surface, radius, window.width, size.height)) {
                            return@drawWithContent
                        }
                        val scrim = libraryScrimAlpha(progress)
                        val pieces = sheetUncoveredPieces(
                            surface, radius, inset = 1.dp.toPx(), width = window.width, height = size.height,
                        )
                        for (windowPiece in pieces) {
                            val piece = windowPiece.translate(window.left, 0f)
                            clipRect(piece.left, piece.top, piece.right, piece.bottom) {
                                this@drawWithContent.drawContent()
                                drawRect(Color.Black, topLeft = piece.topLeft, size = piece.size, alpha = scrim)
                            }
                        }
                    }
                    .graphicsLayer()
                    // Inert from the first move of a drag up too: a second finger must not reach
                    // the page under the rising sheet.
                    .blockTouchesWhile { playerExpansion.dragging }
                    .inactiveDuringTransition(showNowPlaying || settingsOverlayActive),
            ) {
                CompositionLocalProvider(LocalEndlessMotionHeld provides libraryCovered) {
                        // Playlist covers belong to the browsing transition, not the player.
                        // A shared scope promotes every matched element into its overlay while
                        // any match animates. Keeping covers here lets the entire playlist fade
                        // behind the opening player instead of leaving its cover floating on top.
                        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                        val playlistSharedScope = this
                        // Everything that is NOT the full player shares one stack, and the pill is the
                        // last thing in it. Search and collection detail are full-screen surfaces laid
                        // OVER the browse shell rather than pages swapped into it, so anything drawn
                        // after them stays visible — which is how the pill reaches screens it used to
                        // disappear behind.
                        Box(modifier = Modifier.fillMaxSize().browseCloud(
                            accent.takeIf { currentTrack != null && trackColorMode != TrackColorMode.THEME },
                        )) {
                    var railScrubbing by remember { mutableStateOf(false) }
                    val pageRailSlots = remember {
                        StartPage.entries.associateWith { PageRailSlot() }
                    }
                    // One set of selection commands drives the bottom action bar and every contextual
                    // top bar (the browse tabs, Search, a collection's page), so they agree on what is
                    // enabled and on which destructive action a page offers.
                    val selectionCommands = SelectionCommands(
                        selected = selectedTracks,
                        playlistId = selectedCollection?.playlistId,
                        onPlay = {
                            val selection = selectedTracks
                            // The surface the selection was made on: a hand-picked set from
                            // search or a group tab has no single honest name.
                            AppGraph.queueSource.value = when {
                                selectedCollection?.allowsTrackSelection == true ->
                                    selectedCollection?.queueSource()
                                showSearch || selectedTab in GROUP_TABS -> null
                                else -> QueueSource(QueueSourceKind.TRACKS)
                            }
                            updateTrackSelection(emptySet())
                            scope.launch { playback.play(selection, 0) }
                        },
                        onAdd = {
                            sheets.addToPlaylistSelection = selectedTracks
                        },
                        onFavorite = { action ->
                            val ids = selectedTracks.map(TrackDescriptor::id)
                            val selectedBefore = selectedTrackIds
                            updateTrackSelection(emptySet())
                            val commandSelectionRevision = selectionRevision
                            editFavorites(action, ids) {
                                // Nothing was written: give the selection back, unless the
                                // listener has already made another one.
                                if (selectionRevision == commandSelectionRevision) {
                                    updateTrackSelection(selectedBefore)
                                }
                            }
                        },
                        onShare = {
                            shareTracks(selectedTracks)
                            updateTrackSelection(emptySet())
                        },
                        onEditTags = {
                            val selection = selectedTracks
                            updateTrackSelection(emptySet())
                            openTagEditor(selection, BulkEditScope.SELECTION)
                        },
                        onRemove = {
                            val playlist = selectedCollection?.takeIf { it.playlistId != null }
                            if (playlist != null) {
                                val selection = selectedTracks
                                val removedIds = selection.mapTo(LinkedHashSet()) { it.id }
                                val playlistId = checkNotNull(playlist.playlistId)
                                val sourceCollectionRevision = collectionRevision
                                // Freeze the command before the first suspension. A user can
                                // otherwise change selection while the playlist store writes,
                                // making persistence remove A while the visible list hides B.
                                updateTrackSelection(emptySet())
                                val commandSelectionRevision = selectionRevision
                                scope.launch {
                                    var membershipChange: PlaylistTrackChange? = null
                                    val persisted = try {
                                        membershipChange = AppGraph.playlists.removeTracks(
                                            playlistId,
                                            selection.map(TrackDescriptor::id),
                                        )
                                        membershipChange != null
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Throwable) {
                                        false
                                    }
                                    if (!persisted) {
                                        if (
                                            collectionRevision == sourceCollectionRevision &&
                                            selectionRevision == commandSelectionRevision &&
                                            selectedCollection?.playlistId == playlistId
                                        ) {
                                            updateTrackSelection(removedIds)
                                        }
                                        snackbar.showSnackbar(
                                            getString(Res.string.settings_library_manage_failed),
                                        )
                                        return@launch
                                    }
                                    val currentPlaylist = selectedCollection
                                        ?.takeIf { it.playlistId == playlistId }
                                    if (currentPlaylist != null) {
                                        val remaining = currentPlaylist.tracks
                                            .filterNot { it.id in removedIds }
                                        updateSelectedCollection(currentPlaylist.copy(
                                            subtitle = trackCountLabel(remaining.size),
                                            tracks = remaining,
                                        ))
                                        // Preserve a newer selection made while the store was
                                        // writing, but never leave it pointing at a removed row.
                                        updateTrackSelection(selectedTrackIds - removedIds)
                                    }
                                    val change = checkNotNull(membershipChange)
                                    playlistsState.playlists = playlistsState.playlists.map { stored ->
                                        if (stored.id == playlistId) {
                                            change.appliedTo(stored)
                                        } else {
                                            stored
                                        }
                                    }
                                    refreshPlaylistStatsBestEffort()
                                    val result = snackbar.showSnackbar(
                                        message = getString(
                                            Res.string.snack_playlist_track_removed,
                                            bidiWrap(playlist.title, layoutDirection),
                                        ),
                                        actionLabel = if (change.before != change.after) {
                                            getString(Res.string.action_undo)
                                        } else {
                                            null
                                        },
                                        withDismissAction = change.before != change.after,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        val restored = try {
                                            AppGraph.playlists.restoreTracksIfUnchanged(playlistId, change)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Throwable) {
                                            false
                                        }
                                        if (!restored) {
                                            snackbar.showSnackbar(
                                                getString(
                                                    Res.string.settings_library_manage_failed,
                                                ),
                                            )
                                            return@launch
                                        }
                                        playlistsState.playlists = playlistsState.playlists.map { stored ->
                                            if (stored.id == playlistId) {
                                                change.revertedIn(stored)
                                            } else {
                                                stored
                                            }
                                        }
                                        selectedCollection
                                            ?.takeIf { it.playlistId == playlistId }
                                            ?.let { current ->
                                                val restoredTracks = change.before
                                                    .mapNotNull(tracksById::get)
                                                updateSelectedCollection(current.copy(
                                                    subtitle = trackCountLabel(
                                                        restoredTracks.size,
                                                    ),
                                                    tracks = restoredTracks,
                                                ))
                                            }
                                    }
                                }
                            } else {
                                sheets.showSelectionRemoval = true
                            }
                        },
                    )
                    val searchVisibilityState = remember {
                        MutableTransitionState(showSearch)
                    }
                    searchVisibilityState.targetState = showSearch
                    val searchOverlayActive = searchVisibilityState.currentState ||
                        searchVisibilityState.targetState
                    var searchButtonBounds by remember { mutableStateOf<Rect?>(null) }
                    val searchTransition = rememberTransition(searchVisibilityState, label = "search-surface")
                    val searchOpenProgress = searchTransition.animateFloat(
                        transitionSpec = {
                            chromeNavigationSpec(reduceMotion, targetState)
                        },
                        label = "search-navigation",
                    ) { if (it) 1f else 0f }
                    // Fade the old page out before the search text appears, avoiding doubled
                    // headings through a translucent overlay. The mini-player stays in place.
                    val browseSearchAlpha = {
                        1f - navigationPhase(searchOpenProgress.value, 0f, 0.45f)
                    }
                    val collectionTransition = updateTransition(
                        targetState = selectedCollection,
                        label = "collection-detail-state",
                    )
                    val collectionOverlayActive = collectionTransition.currentState != null ||
                        collectionTransition.targetState != null
                    val collectionOpenProgress = collectionTransition.animateFloat(
                        transitionSpec = {
                            if (reduceMotion) snap() else
                                tween(Motion.NAVIGATION_MS, easing = Motion.NavigationEasing)
                        },
                        label = "collection-background-shift",
                    ) { selection -> if (selection == null) 0f else 1f }
                    Scaffold(
                        // Collection detail and Search are full-screen layers drawn after this
                        // browse shell. Hide the covered tree from accessibility so TalkBack never
                        // reaches duplicate/underlying tabs and selection controls.
                        modifier = Modifier
                            .graphicsLayer {
                                // Let the artwork stay crisp in the shared overlay while
                                // the covered list recedes without doubled headings.
                                alpha = browseSearchAlpha() * (1f - collectionOpenProgress.value)
                                val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                                translationX = if (reduceMotion) 0f else
                                    -20.dp.toPx() * direction * collectionOpenProgress.value
                            }
                            .then(if (collectionOverlayActive || searchOverlayActive) {
                                Modifier.clearAndSetSemantics { }
                            } else {
                                Modifier
                            }),
                        topBar = {
                            Column {
                                AnimatedContent(
                                    targetState = SelectionBarPresentation(
                                        selecting = selectionMode && selectedCollection == null,
                                        count = selectedTrackIds.size,
                                        allSelected =
                                            selectedTrackIds.size == catalog?.songs?.size,
                                        commands = selectionCommands,
                                    ),
                                    contentKey = { it.selecting },
                                    transitionSpec = { motionFadeThrough(reduceMotion) },
                                    label = "root-contextual-app-bar",
                                ) { bar ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .inactiveDuringTransition(
                                            bar.selecting != (
                                                selectionMode && selectedCollection == null
                                            ),
                                        ),
                                ) {
                                if (bar.selecting) {
                                    SelectionTopAppBar(
                                        count = bar.count,
                                        allSelected = bar.allSelected,
                                        commands = bar.commands,
                                        onClose = { updateTrackSelection(emptySet()) },
                                        onToggleAll = {
                                            updateTrackSelection(if (
                                                selectedTrackIds.size == catalog?.songs?.size
                                            ) {
                                                emptySet()
                                            } else {
                                                catalog?.songs.orEmpty()
                                                    .mapTo(LinkedHashSet()) { it.id }
                                            })
                                        },
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .statusBarsPadding().heightIn(min = 56.dp)
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SettingsGearButton(
                                            modifier = Modifier.onGloballyPositioned {
                                                settingsButtonBounds = it.boundsInRoot()
                                            },
                                            openProgress = { settingsOpenProgress.value },
                                            enabled = !settingsOverlayActive,
                                            onClick = { showSettings = true },
                                        )
                                        BrowseCarousel(
                                            pagerState = pagerState,
                                            pages = visiblePages,
                                            onSelect = ::navigateToRootTab,
                                            modifier = Modifier.weight(1f),
                                            style = pageHeaderStyle,
                                        )
                                        SearchLaunchButton(
                                            enabled = !searchOverlayActive && !settingsOverlayActive,
                                            onClick = { showSearch = true },
                                            modifier = Modifier.onGloballyPositioned {
                                                searchButtonBounds = it.boundsInRoot()
                                            },
                                        )
                                    }
                                }
                                }
                                }
                            }
                        },
                        // The root floor already paints the window. Leaving Scaffold transparent
                        // avoids a second full-screen fill underneath every page.
                        containerColor = Color.Transparent,
                        // A transparent container has no inferable Material content colour.
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) { padding ->
                        // Snapshot the asynchronously-built catalog once for this
                        // composition. Delegated state cannot be smart-cast after a
                        // null check because it may change between reads.
                        val visibleCatalog = groupOrderedCatalog
                        // One full-bleed surface: it runs to the bottom edge so the
                        // mini-player floats ON the content rather than sitting on a
                        // separate band of background.
                        // A background shape is enough here. Material Surface also clips every
                        // descendant to this screen-sized rounded path, which is expensive on
                        // high-resolution devices while a list is moving.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding()),
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val presentation = when {
                                    visibleCatalog == null -> LibraryPresentation.LOADING
                                    else -> LibraryPresentation.CONTENT
                                }
                                AnimatedContent(
                                    // Keep the catalog that belongs to each transition frame. If
                                    // the live value becomes null, the outgoing CONTENT page still
                                    // has its rows available while it fades away.
                                    targetState = presentation to visibleCatalog,
                                    contentKey = { it.first },
                                    transitionSpec = { motionFadeThrough(reduceMotion) },
                                    modifier = Modifier.fillMaxSize(),
                                    label = "library-presentation",
                                ) { (shownPresentation, shownCatalog) ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .inactiveDuringTransition(shownPresentation != presentation),
                                ) {
                                when (shownPresentation) {
                                    LibraryPresentation.LOADING -> Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) { AppSpinner() }

                                    // Pages follow the finger. The carousel strip above reads the
                                    // same pager position, so label and content move together
                                    // rather than one chasing the other after the fact.
                                    LibraryPresentation.CONTENT -> shownCatalog?.let { visibleCatalog ->
                                    // The Statistics page's results outlive the page itself (see
                                    // [StatsMemory]); a new library, or a cleared or restored log
                                    // (which re-keys the page), starts them over.
                                    val statsMemory = remember(replacedHistoryEpoch, visibleCatalog.songs) { StatsMemory() }
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize(),
                                        flingBehavior = PagerDefaults.flingBehavior(
                                            state = pagerState,
                                            snapAnimationSpec = if (reduceMotion) snap<Float>() else
                                                tween(Motion.NAVIGATION_MS, easing = Motion.NavigationEasing),
                                        ),
                                        // Inline Map reserves two fingers for pan/zoom. One finger
                                        // reaches this pager or the page's vertical scroll container.
                                        userScrollEnabled = !selectionMode,
                                        // Compose the destination as the gesture reaches it. Keeping
                                        // both neighbours alive eagerly built album grids and loaded
                                        // their covers while the user was merely scrolling Tracks.
                                        beyondViewportPageCount = 0,
                                        key = { page -> pagerPages.value[page].persistedValue },
                                    ) { page ->
                                        val tab = pagerPages.value[page]
                                        // History remains useful after the last library track is
                                        // removed. Keep navigation mounted for empty catalogs too.
                                        if (visibleCatalog.songs.isEmpty() && tab != StartPage.STATISTICS) {
                                            EmptyLibraryPage(
                                                contentPadding = listPadding,
                                                canImportAudio = audioImportAvailable,
                                                onImportAudio = importAudio,
                                            )
                                            return@HorizontalPager
                                        }
                                        CompositionLocalProvider(LocalPageRailSlot provides pageRailSlots.getValue(tab)) {
                                        when (tab) {
                                        // Re-keyed on the replacement epoch alone, not on the whole
                                        // revision: new listens refresh this page in place, while a
                                        // cleared or restored log must drop its cached snapshot.
                                        StartPage.STATISTICS -> androidx.compose.runtime.key(replacedHistoryEpoch) {
                                            ListeningStatsSettings(
                                                accent = accent,
                                                history = AppGraph.history,
                                                tracks = visibleCatalog.songs,
                                                contentPadding = listPadding,
                                                active = browsePageVisible && settledTab == StartPage.STATISTICS,
                                                memory = statsMemory,
                                                playlistsKey = playlistsState.playlists,
                                                favoritesKey = favoriteIds,
                                                knownTracksById = tracksById.takeIf { catalog?.songs === visibleCatalog.songs },
                                            )
                                        }

                                        StartPage.FOR_YOU -> AnimatedContent(
                                            targetState = forYouPage.forYou,
                                            contentKey = { if (it.isEmpty) "empty" else "content" },
                                            transitionSpec = { motionFadeThrough(reduceMotion) },
                                            modifier = Modifier.fillMaxSize(),
                                            label = "for-you-presentation",
                                        ) { shownForYou ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .inactiveDuringTransition(
                                                    shownForYou.isEmpty != forYouPage.forYou.isEmpty,
                                                ),
                                        ) {
                                        ForYouTab(
                                            playlistArtworkUris = playlistArtworkUris,
                                            accent = accent,
                                            page = shownForYou,
                                            contentPadding = listPadding,
                                            isRefreshing = forYouPage.forYouRefreshing,
                                            // Never built yet (or emptied by an exclusion change) and
                                            // not failed: an empty page means "not ready", not "no history".
                                            isLoading = forYouPage.forYouBuilding ||
                                                (forYouPage.builtForYouLibraryIds == null && !forYouPage.forYouLoadFailed),
                                            loadFailed = forYouPage.forYouLoadFailed,
                                            // An offer is recorded when its card is on screen, not
                                            // when the page is assembled: LazyRow composes what
                                            // fits, and the builder's own discoveryOffers — which
                                            // the build effect used to write — never contained the
                                            // hero at all. Best-effort by design: the record only
                                            // shapes FUTURE days, and a lost write merely lets an
                                            // offer repeat.
                                            onCardShown = { impression ->
                                                val fresh = unrecordedForYouCardsShown(
                                                    listOf(impression),
                                                    forYouPage.forYouCardsShown,
                                                )
                                                if (fresh.isNotEmpty()) {
                                                    forYouPage.forYouCardsShown = forYouPage.forYouCardsShown + fresh
                                                    scope.launch {
                                                        runCatching {
                                                            AppGraph.forYouImpressions.record(fresh)
                                                        }
                                                    }
                                                }
                                            },
                                            epochDay = { localTimePoint(epochMillis()).epochDay },
                                            onRefresh = {
                                                if (!forYouPage.forYouRefreshing) {
                                                    forYouPage.forYouRefreshing = true
                                                    forYouPage.personalizationRevision += 1
                                                }
                                            },
                                            onPlay = { list, index ->
                                                AppGraph.queueSource.value =
                                                    QueueSource(QueueSourceKind.FOR_YOU)
                                                forYouPage.playbackLauncher.launch(scope) { isCurrent ->
                                                    playFromForYou(
                                                        playback, engine, AppGraph.history, list, index,
                                                        smartEligibleTracks, smartQueueLength,
                                                        AppGraph.smartCompanionGroups.value, isCurrent,
                                                    )
                                                }
                                            },
                                            onPlayCollection = { collection ->
                                                AppGraph.queueSource.value =
                                                    QueueSource(QueueSourceKind.FOR_YOU, name = collection.title)
                                                forYouPage.playbackLauncher.launch(scope) { isCurrent ->
                                                    playFromForYou(
                                                        playback, engine, AppGraph.history, collection.tracks, 0,
                                                        smartEligibleTracks, smartQueueLength,
                                                        AppGraph.smartCompanionGroups.value, isCurrent,
                                                    )
                                                }
                                            },
                                            onPlayHero = { hero ->
                                                scope.launch {
                                                    // Personal signal picked the seed; SMART decides
                                                    // what follows it.
                                                    val queue = engine.smartQueue(
                                                        hero.track,
                                                        smartEligibleTracks,
                                                        smartQueueLength,
                                                        smartHistoryFor(AppGraph.history, hero.track),
                                                        AppGraph.smartCompanionGroups.value,
                                                    )
                                                    val byId = visibleCatalog.songs.associateBy { it.id }
                                                    val tail = queue.mapNotNull(byId::get)
                                                    AppGraph.queueSource.value =
                                                        QueueSource(QueueSourceKind.FOR_YOU)
                                                    playback.play(
                                                        listOf(hero.track) + tail,
                                                        0,
                                                        smartPlanPositions(queue),
                                                    )
                                                    hero.resumeAtMs?.let { playback.seekTo(it) }
                                                }
                                            },
                                            onTrackMenu = { sheets.trackMenuRequest = TrackMenuRequest(it) },
                                            onOpenCollection = { collection ->
                                                openCollection(build = {
                                                    val playlist = playlistsState.playlists
                                                        .firstOrNull { it.id == collection.playlistId }
                                                    val resolved = playlist?.let(::tracksOf) ?: collection.tracks
                                                    CollectionSelection(
                                                        title = playlist?.name ?: collection.title,
                                                        subtitle = trackCountLabel(resolved.size),
                                                        artworkUri = playlistCoverUri(playlist?.customArtworkRef)
                                                            ?: resolved.firstNotNullOfOrNull { it.artworkUri },
                                                        tracks = resolved,
                                                        allowsTrackSelection = true,
                                                        playlistId = playlist?.id,
                                                    )
                                                })
                                            },
                                            onOpenWorld = { sheets.worldTarget = it },
                                        )
                                        }
                                        }

                                        StartPage.MAP -> AnimatedContent(
                                            targetState = mapPage.mapState,
                                            contentKey = { state: MapPageState ->
                                                state.presentationKey()
                                            },
                                            transitionSpec = { motionFadeThrough(reduceMotion) },
                                            modifier = Modifier.fillMaxSize(),
                                            label = "map-presentation",
                                        ) { shownMapState ->
                                        val shownRegionNames = (shownMapState as? MapPageState.Ready)
                                            ?.page?.regionNames.orEmpty()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .inactiveDuringTransition(
                                                    shownMapState.presentationKey() !=
                                                        mapPage.mapState.presentationKey(),
                                                ),
                                        ) {
                                        MapTab(
                                            onRetryIndexing = ::retryAutomaticIndexing,
                                            currentAccent = browseAccentInk(accent),
                                            state = shownMapState,
                                            contentPadding = listPadding,
                                            focusTrackId = mapPage.mapFocusTrackId,
                                            onPlayRegion = { region ->
                                                mapPage.mapRegions.getOrNull(region)?.let { world ->
                                                    val members = world.tracks.mapNotNull { tracksById[it.id] }
                                                    if (members.isNotEmpty()) {
                                                        AppGraph.queueSource.value = QueueSource(
                                                            QueueSourceKind.MAP,
                                                            shownRegionNames.getOrNull(region) ?: world.name,
                                                        )
                                                        scope.launch { playback.play(members, 0) }
                                                    }
                                                }
                                            },
                                            onPlayUnheardRegion = { region ->
                                                mapPage.mapRegions.getOrNull(region)?.let { world ->
                                                    scope.launch {
                                                        val stats = AppGraph.history.stats()
                                                        val unheard = world.tracks
                                                            .mapNotNull { tracksById[it.id] }
                                                            .filter { (stats[it.id]?.plays ?: 0) == 0 }
                                                        if (unheard.isNotEmpty()) {
                                                            AppGraph.queueSource.value = QueueSource(
                                                                QueueSourceKind.MAP,
                                                                shownRegionNames.getOrNull(region) ?: world.name,
                                                            )
                                                            playback.play(unheard, 0)
                                                        }
                                                    }
                                                }
                                            },
                                            onOpenRegion = { region ->
                                                mapPage.mapRegions.getOrNull(region)?.let { world ->
                                                    scope.launch {
                                                        val members = world.tracks.mapNotNull { tracksById[it.id] }
                                                        updateTrackSelection(emptySet())
                                                        updateSelectedCollection(CollectionSelection(
                                                            title = shownRegionNames.getOrNull(region) ?: world.name,
                                                            subtitle = trackCountLabel(members.size),
                                                            artworkUri = world.representative.artworkUri,
                                                            tracks = members,
                                                            allowsTrackSelection = true,
                                                            routeId = "map:${world.representative.id.value}",
                                                        ))
                                                    }
                                                }
                                            },
                                            onSmartFromRegion = { region ->
                                                mapPage.mapRegions.getOrNull(region)?.let { world ->
                                                    scope.launch {
                                                        val seed = tracksById[world.representative.id] ?: return@launch
                                                        val queue = engine.smartQueue(
                                                            seed,
                                                            smartEligibleTracks,
                                                            smartQueueLength,
                                                            smartHistoryFor(AppGraph.history, seed),
                                                            AppGraph.smartCompanionGroups.value,
                                                        )
                                                        val tail = queue.mapNotNull(tracksById::get)
                                                        AppGraph.queueSource.value = QueueSource(
                                                            QueueSourceKind.MAP,
                                                            shownRegionNames.getOrNull(region) ?: world.name,
                                                        )
                                                        playback.play(
                                                            listOf(seed) + tail,
                                                            0,
                                                            smartPlanPositions(queue),
                                                        )
                                                    }
                                                }
                                            },
                                            onOpenTrack = { id ->
                                                tracksById[id]?.let {
                                                    sheets.trackMenuRequest = TrackMenuRequest(it)
                                                }
                                            },
                                        )
                                        }
                                        }

                                        StartPage.PLAYLISTS -> PlaylistsTabContent(
                                            currentAccent = browseAccentInk(accent),
                                            onCreate = { showCreatePlaylist = true },
                                            onImport = { m3uExchange.import() },
                                            autoPlaylists = playlistsState.autoPlaylists,
                                            playlists = playlistsState.playlists,
                                            artworkModifier = { routeId ->
                                                if (reduceMotion || selectedTab != StartPage.PLAYLISTS) {
                                                    Modifier
                                                } else with(playlistSharedScope) {
                                                    // Keep the source's layout slot while its cover
                                                    // travels above the incoming page, then return
                                                    // to that same slot on Back.
                                                    Modifier.sharedElementWithCallerManagedVisibility(
                                                        rememberSharedContentState("playlist-cover:$routeId"),
                                                        visible = selectedCollection?.routeId != routeId,
                                                        boundsTransform = motionBoundsTransform(),
                                                    )
                                                }
                                            },
                                            tracksOf = ::tracksOf,
                                            contentPadding = listPadding,
                                            // Building a selection resolves a track count, and a
                                            // count is a plural — so it happens in a coroutine
                                            // rather than in the click handler itself.
                                            onOpenAuto = { auto ->
                                                scope.launch {
                                                    updateTrackSelection(emptySet())
                                                    updateSelectedCollection(CollectionSelection(
                                                        title = getString(auto.kind.titleRes()),
                                                        subtitle = trackCountLabel(auto.tracks.size),
                                                        artworkUri = auto.tracks
                                                            .firstNotNullOfOrNull { it.artworkUri },
                                                        tracks = auto.tracks,
                                                        allowsTrackSelection = true,
                                                        routeId = "auto:${auto.kind.name}",
                                                    ))
                                                }
                                            },
                                            onOpenPlaylist = { playlist ->
                                                scope.launch {
                                                    updateTrackSelection(emptySet())
                                                    val resolved = tracksOf(playlist)
                                                    updateSelectedCollection(CollectionSelection(
                                                        title = playlist.name,
                                                        // Resolved count: stored ids may include
                                                        // tracks deleted from the device, and the
                                                        // subtitle must match the list below it.
                                                        subtitle = trackCountLabel(resolved.size),
                                                        artworkUri = playlistCoverUri(playlist.customArtworkRef)
                                                            ?: resolved.firstNotNullOfOrNull { it.artworkUri },
                                                        tracks = resolved,
                                                        allowsTrackSelection = true,
                                                        playlistId = playlist.id,
                                                    ))
                                                }
                                            },
                                            onExport = ::exportPlaylistAsM3u,
                                            onAddToFavorites = { playlist ->
                                                editFavorites(
                                                    SelectionFavoriteAction.ADD,
                                                    tracksOf(playlist).map(TrackDescriptor::id),
                                                )
                                            },
                                            canAddToFavorites = { playlist ->
                                                anyNotFavorite(tracksOf(playlist), favoriteIds)
                                            },
                                            onToggleSmart = ::togglePlaylistSmart,
                                            onMove = { from, to ->
                                                val moving = playlistsState.playlists.getOrNull(from)
                                                if (moving != null) {
                                                    // Optimistic: the row lands where it was
                                                    // dropped; a failed write restores truth.
                                                    playlistsState.playlists = playlistsState.playlists.toMutableList()
                                                        .apply { add(to, removeAt(from)) }
                                                    scope.launch {
                                                        if (!runPlaylistMutation {
                                                                AppGraph.playlists.move(
                                                                    moving.id,
                                                                    to,
                                                                )
                                                            }
                                                        ) {
                                                            refreshPlaylistMembershipsBestEffort()
                                                        }
                                                    }
                                                }
                                            },
                                            onRename = { renameTargetId = it.id },
                                            onChangeCover = ::changePlaylistCover,
                                            onResetCover = ::resetPlaylistCover,
                                            coverEditBusy = coverEditTargetId != null || coverSaveInProgress,
                                            onDelete = { playlist ->
                                                scope.launch {
                                                    if (!runPlaylistMutation {
                                                            AppGraph.playlists.delete(playlist.id)
                                                        }
                                                    ) return@launch
                                                    playlistsState.playlists = playlistsState.playlists.filterNot {
                                                        it.id == playlist.id
                                                    }
                                                    discardUnusedPlaylistCover(playlist.customArtworkRef)
                                                    snackbar.showSnackbar(
                                                        getString(
                                                            Res.string.snack_playlist_deleted,
                                                            bidiWrap(playlist.name, layoutDirection),
                                                        ),
                                                    )
                                                }
                                            },
                                        )

                                        StartPage.TRACKS -> Column {
                                            val songsListState = rememberLazyListState()
                                            val scrollSongsToTopOnSort = rememberScrollToTopOnSort(
                                                shown = songsBrowse?.choice,
                                            ) { songsListState.requestScrollToItem(0) }
                                            SortHeader(
                                                options = SongSort.entries,
                                                testTagPrefix = "latentjam_song_sort",
                                                choice = songSortChoice,
                                                label = { it.label() },
                                                defaultDirection = { it.defaultDirection },
                                                count = visibleCatalog.songs.size,
                                                countPlural = Res.plurals.count_tracks,
                                                enabled = !selectionMode,
                                                onChoiceChange = { choice ->
                                                    scrollSongsToTopOnSort(choice)
                                                    settings.setSongSort(choice)
                                                },
                                            )
                                            songsBrowse?.let { browse -> SectionedSongsList(
                                                browse = browse,
                                                listState = songsListState,
                                                onRailScrubbingChange = { railScrubbing = it },
                                                currentAccent = browseAccentInk(accent),
                                                currentTrackId = currentTrack?.id,
                                                currentTrackPlaying = currentTrackPlaying,
                                                contentPadding = listPadding,
                                                selectedTrackIds = selectedTrackIds,
                                                onToggleSelection = { track ->
                                                    updateTrackSelection(if (track.id in selectedTrackIds) {
                                                        selectedTrackIds - track.id
                                                    } else {
                                                        selectedTrackIds + track.id
                                                    })
                                                },
                                                onStartSelection = { track ->
                                                    updateTrackSelection(setOf(track.id))
                                                },
                                                onPlay = { queue, index ->
                                                    AppGraph.queueSource.value =
                                                        QueueSource(QueueSourceKind.TRACKS)
                                                    scope.launch { playback.play(queue, index) }
                                                },
                                                onTrackMenu = {
                                                    sheets.trackMenuRequest = TrackMenuRequest(it)
                                                },
                                            ) }
                                        }

                                        StartPage.ALBUMS -> Column {
                                            val albumsGridState = rememberLazyGridState()
                                            // Each arrival on the Albums page plays the grid's wave once.
                                            val reduceGridMotion = rememberReduceMotion()
                                            val albumWave = remember { Animatable(if (reduceGridMotion) 1f else 0f) }
                                            LaunchedEffect(Unit) {
                                                if (!reduceGridMotion) albumWave.animateTo(1f, tween(GRID_WAVE_MS, easing = LinearEasing))
                                            }
                                            // The grid shows the derived order, which can lag the setting.
                                            val scrollAlbumsToTopOnSort = rememberScrollToTopOnSort(
                                                shown = albumBrowse?.choice,
                                            ) { albumsGridState.requestScrollToItem(0) }
                                            SortHeader(
                                                options = AlbumSort.entries,
                                                choice = albumSortChoice,
                                                label = { it.label() },
                                                defaultDirection = { it.defaultDirection },
                                                count = visibleCatalog.albums.size,
                                                countPlural = Res.plurals.count_albums,
                                                enabled = !selectionMode,
                                                onChoiceChange = { choice ->
                                                    scrollAlbumsToTopOnSort(choice)
                                                    // A small library re-sorts in the tap's own frame; the
                                                    // effect below skips a choice already derived.
                                                    catalog?.takeIf { albumResortRunsInline(it.albums.size) }?.let {
                                                        albumBrowse = AlbumBrowseDerivation.of(it, choice)
                                                    }
                                                    settings.setAlbumSort(choice)
                                                },
                                            )
                                            val albumPadding = PaddingValues(
                                                start = 12.dp, end = 12.dp, top = 8.dp,
                                                bottom = listPadding.calculateBottomPadding(),
                                            )
                                            AlbumGridPinch(
                                                columns = LocalAppearance.current.albumColumns,
                                                onColumnsChange = { n -> settings.setAppearance(settings.appearance.value.copy(albumColumns = n)) },
                                            ) {
                                            AdaptiveCollectionIndex(
                                                itemCount = visibleCatalog.albums.size,
                                                bucketCount = albumSections.size,
                                                contentPadding = albumPadding,
                                                grid = true,
                                                fixedColumns = LocalAppearance.current.albumColumns,
                                            ) { indexed ->
                                            val albumRail = remember(albumSections, indexed) {
                                                if (!indexed) RailIndex(emptyList(), emptyList()) else RailIndex(
                                                    buckets = albumSections.map { it.bucket },
                                                    startIndexes = albumSections.map {
                                                        it.emitStartIndex
                                                    },
                                                )
                                            }
                                            val albumArtworkKeys = remember(albumSections, indexed) {
                                                buildList<ArtworkLoadKey?> {
                                                    albumSections.forEach { section ->
                                                        if (indexed) add(null) // Full-span section header.
                                                        section.albums.forEach { album ->
                                                            add(album.artworkUri?.let { uri ->
                                                                ArtworkLoadKey(
                                                                    itemId = "album:${album.key}",
                                                                    uri = uri,
                                                                )
                                                            })
                                                        }
                                                    }
                                                }
                                            }
                                            GridListWithRail(
                                                gridState = albumsGridState,
                                                onScrubbingChange = { railScrubbing = it },
                                                rail = albumRail,
                                                catalogKey = albumSections to indexed,
                                                artworkKeys = albumArtworkKeys,
                                                contentPadding = albumPadding,
                                            ) { railPadding, gridState, artworkReporter, isPreview ->
                                                LazyVerticalGrid(
                                                    columns = LocalAppearance.current.albumColumns
                                                        .takeIf { it > 0 }?.let { GridCells.Fixed(it) }
                                                        ?: GridCells.Adaptive(AlbumGridMinCellWidth),
                                                    state = gridState,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentPadding = railPadding,
                                                ) {
                                                    albumSections.forEach { section ->
                                                        if (indexed) item(
                                                            key = "album-header-${section.bucket}",
                                                            span = { GridItemSpan(maxLineSpan) },
                                                            contentType = "header",
                                                        ) {
                                                            CollectionSectionHeader(section.bucket, horizontalPadding = 8.dp)
                                                        }
                                                        items(
                                                            section.albums,
                                                            key = { it.key },
                                                            contentType = { "album" },
                                                        ) { album ->
                                                            val expectedKey = album.artworkUri
                                                                ?.let { uri ->
                                                                    ArtworkLoadKey(
                                                                        itemId = "album:${album.key}",
                                                                        uri = uri,
                                                                    )
                                                                }
                                                            AlbumCard(
                                                                album = album,
                                                                railPreview = isPreview,
                                                                // A pinch to more or fewer columns glides
                                                                // each cover to its new cell.
                                                                modifier = if (isPreview || rememberReduceMotion()) Modifier else Modifier.animateItem(
                                                                    fadeInSpec = null,
                                                                    fadeOutSpec = null,
                                                                    placementSpec = tween(Motion.NAVIGATION_MS, easing = Motion.NavigationEasing),
                                                                ).gridWave({ albumWave.value }, gridState, album.key),
                                                                onArtworkLoadStateChanged =
                                                                    if (isPreview) {
                                                                        null
                                                                    } else artworkReporter?.let { report ->
                                                                        expectedKey?.let { key ->
                                                                            { requestUri, state ->
                                                                                report(
                                                                                    key.copy(
                                                                                        uri = requestUri,
                                                                                    ),
                                                                                    state,
                                                                                )
                                                                            }
                                                                        }
                                                                    },
                                                                onLongClick = {
                                                                    updateTrackSelection(
                                                                        selectedTrackIds
                                                                            .toggleTracks(
                                                                                album.tracks,
                                                                            ),
                                                                    )
                                                                },
                                                                selectionState =
                                                                    if (selectionMode) {
                                                                        selectedTrackIds
                                                                            .selectsAllOf(
                                                                                album.tracks,
                                                                            )
                                                                    } else {
                                                                        null
                                                                    },
                                                            ) {
                                                                if (selectionMode) {
                                                                    updateTrackSelection(
                                                                        selectedTrackIds
                                                                            .toggleTracks(
                                                                                album.tracks,
                                                                            ),
                                                                    )
                                                                } else {
                                                                    openCollection(
                                                                        build = { album.toSelection() },
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

                                        StartPage.ARTISTS -> Column {
                                            NameSortHeader(
                                                direction = groupSortOrders.artists,
                                                count = visibleCatalog.artists.size,
                                                countPlural = Res.plurals.count_artists,
                                                enabled = !selectionMode,
                                                onDirectionChange = {
                                                    settings.setGroupSortOrders(groupSortOrders.copy(artists = it))
                                                },
                                            )
                                            SectionedGroupListWithRail(
                                            onScrubbingChange = { railScrubbing = it },
                                            names = remember(visibleCatalog.artists) {
                                                visibleCatalog.artists.map { it.name }
                                            },
                                            artworkKeys = remember(visibleCatalog.artists) {
                                                visibleCatalog.artists.map { artist ->
                                                    artist.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri }
                                                        ?.let { uri ->
                                                            ArtworkLoadKey(
                                                                itemId = stableGroupKey(
                                                                    "artist",
                                                                    artist.name,
                                                                ),
                                                                uri = uri,
                                                            )
                                                        }
                                                }
                                            },
                                            contentPadding = listPadding,
                                        ) { railPadding, listState, artworkReporter, headers ->
                                            var previousOrder by remember { mutableStateOf(groupSortOrders.artists) }
                                            LaunchedEffect(groupSortOrders.artists) {
                                                if (previousOrder != groupSortOrders.artists) listState.scrollToItem(0)
                                                previousOrder = groupSortOrders.artists
                                            }
                                            LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = railPadding,
                                        ) {
                                            itemsIndexed(
                                                visibleCatalog.artists,
                                                key = { _, artist -> stableGroupKey("artist", artist.name) },
                                            ) { rowIndex, artist ->
                                                val artworkKey = remember(artist) {
                                                    artist.tracks.firstNotNullOfOrNull { it.artworkUri }
                                                        ?.let { uri ->
                                                            ArtworkLoadKey(
                                                                itemId = stableGroupKey("artist", artist.name),
                                                                uri = uri,
                                                            )
                                                        }
                                                }
                                                Column {
                                                headers[rowIndex]?.let { CollectionSectionHeader(it) }
                                                GroupRow(
                                                    title = artist.name
                                                        ?: stringResource(Res.string.track_unknown_artist),
                                                    subtitle = artistSubtitle(
                                                        tracks = artist.tracks.size,
                                                        albums = artist.albumCount,
                                                    ),
                                                    artworkUri = artworkKey?.uri,
                                                    onArtworkLoadStateChanged =
                                                        artworkReporter?.let { report ->
                                                            artworkKey?.let { expectedKey ->
                                                                { requestUri, state ->
                                                                    report(
                                                                        expectedKey.copy(uri = requestUri),
                                                                        state,
                                                                    )
                                                                }
                                                            }
                                                        },
                                                    onLongClick = {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(artist.tracks),
                                                        )
                                                    },
                                                    selectionState = if (selectionMode) {
                                                        selectedTrackIds.selectsAllOf(artist.tracks)
                                                    } else {
                                                        null
                                                    },
                                                ) {
                                                    if (selectionMode) {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(artist.tracks),
                                                        )
                                                    } else {
                                                        openCollection(
                                                            build = { artist.toSelection(settings.artistAlbumSort.value) },
                                                        )
                                                    }
                                                }
                                                }
                                            }
                                        }
                                        }
                                        }

                                        StartPage.GENRES -> Column {
                                            NameSortHeader(
                                                direction = groupSortOrders.genres,
                                                count = visibleCatalog.genres.size,
                                                countPlural = Res.plurals.count_genres,
                                                enabled = !selectionMode,
                                                onDirectionChange = {
                                                    settings.setGroupSortOrders(groupSortOrders.copy(genres = it))
                                                },
                                            )
                                            SectionedGroupListWithRail(
                                            onScrubbingChange = { railScrubbing = it },
                                            names = remember(visibleCatalog.genres) {
                                                visibleCatalog.genres.map { it.name }
                                            },
                                            artworkKeys = remember(visibleCatalog.genres) {
                                                visibleCatalog.genres.map { genre ->
                                                    genre.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri }
                                                        ?.let { uri ->
                                                            ArtworkLoadKey(
                                                                itemId = stableGroupKey(
                                                                    "genre",
                                                                    genre.name,
                                                                ),
                                                                uri = uri,
                                                            )
                                                        }
                                                }
                                            },
                                            contentPadding = listPadding,
                                        ) { railPadding, listState, artworkReporter, headers ->
                                            var previousOrder by remember { mutableStateOf(groupSortOrders.genres) }
                                            LaunchedEffect(groupSortOrders.genres) {
                                                if (previousOrder != groupSortOrders.genres) listState.scrollToItem(0)
                                                previousOrder = groupSortOrders.genres
                                            }
                                            LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = railPadding,
                                        ) {
                                            itemsIndexed(
                                                visibleCatalog.genres,
                                                key = { _, genre -> stableGroupKey("genre", genre.name) },
                                            ) { rowIndex, genre ->
                                                val artworkKey = remember(genre) {
                                                    genre.tracks.firstNotNullOfOrNull { it.artworkUri }
                                                        ?.let { uri ->
                                                            ArtworkLoadKey(
                                                                itemId = stableGroupKey("genre", genre.name),
                                                                uri = uri,
                                                            )
                                                        }
                                                }
                                                Column {
                                                headers[rowIndex]?.let { CollectionSectionHeader(it) }
                                                GroupRow(
                                                    title = genre.name
                                                        ?: stringResource(Res.string.track_unknown_genre),
                                                    subtitle = pluralStringResource(
                                                        Res.plurals.count_tracks,
                                                        genre.tracks.size,
                                                        genre.tracks.size,
                                                    ),
                                                    artworkUri = artworkKey?.uri,
                                                    onArtworkLoadStateChanged =
                                                        artworkReporter?.let { report ->
                                                            artworkKey?.let { expectedKey ->
                                                                { requestUri, state ->
                                                                    report(
                                                                        expectedKey.copy(uri = requestUri),
                                                                        state,
                                                                    )
                                                                }
                                                            }
                                                        },
                                                    onLongClick = {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(genre.tracks),
                                                        )
                                                    },
                                                    selectionState = if (selectionMode) {
                                                        selectedTrackIds.selectsAllOf(genre.tracks)
                                                    } else {
                                                        null
                                                    },
                                                ) {
                                                    if (selectionMode) {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(genre.tracks),
                                                        )
                                                    } else {
                                                        openCollection(
                                                            build = { genre.toSelection() },
                                                        )
                                                    }
                                                }
                                                }
                                            }
                                        }
                                        }
                                        }

                                        StartPage.FOLDERS -> FoldersPage(
                                            catalog = visibleCatalog,
                                            playback = playback,
                                            active = browsePageVisible && settledTab == StartPage.FOLDERS,
                                            selectionMode = selectionMode,
                                            selectedTrackIds = selectedTrackIds,
                                            onSelectionChange = { updateTrackSelection(it) },
                                            onOpenFolder = { folder ->
                                                openCollection(build = { folder.toSelection() })
                                            },
                                            onTrackMenu = { sheets.trackMenuRequest = TrackMenuRequest(it) },
                                            currentTrackId = currentTrack?.id,
                                            currentTrackPlaying = currentTrackPlaying,
                                            currentAccent = browseAccentInk(accent),
                                            contentPadding = listPadding,
                                            onRailScrubbingChange = { railScrubbing = it },
                                        )

                                        }
                                        } // page rail provider
                                    } // pager
                                    PagerAlphabetRail(
                                        slot = pagerPages.value.getOrNull(pagerState.currentPage)
                                            ?.let(pageRailSlots::get),
                                        paging = pagerState.isScrollInProgress,
                                    )
                                    }
                                }
                                }
                                }
                            }
                        }
                    }

                    val shuffleAtStart = handControlsAtStart()
                    val showShuffle = !selectionMode && !railScrubbing && !searchOverlayActive && !collectionOverlayActive &&
                        selectedTab.showsShuffleAll() &&
                        catalog?.songs?.isNotEmpty() == true
                    AnimatedVisibility(
                        visible = showShuffle,
                        modifier = Modifier.align(if (shuffleAtStart) Alignment.BottomStart else Alignment.BottomEnd)
                            // Selection adds its own toolbar inset, but an exiting Shuffle
                            // button must keep its original anchor while it fades away.
                            .padding(start = if (shuffleAtStart) 42.dp else 0.dp, end = if (shuffleAtStart) 0.dp else 42.dp, bottom = navBottom +
                                (if (currentTrack != null) miniPlayerHeight else 12.dp) + 20.dp),
                        enter = if (reduceMotion) fadeIn(tween(Motion.REDUCED_MS)) else {
                            fadeIn(tween(Motion.APPEAR_MS)) +
                                scaleIn(tween(Motion.APPEAR_MS), initialScale = 0.86f)
                        },
                        exit = if (reduceMotion) fadeOut(tween(Motion.REDUCED_MS)) else {
                            fadeOut(tween(Motion.QUICK_MS)) +
                                scaleOut(tween(Motion.QUICK_MS), targetScale = 0.86f)
                        },
                    ) {
                        val shufflePress = rememberPressSource()
                        FilledIconButton(
                            interactionSource = shufflePress,
                            onClick = {
                                val songs = catalog?.songs.orEmpty()
                                if (songs.isNotEmpty()) scope.launch {
                                    AppGraph.queueSource.value = QueueSource(QueueSourceKind.TRACKS)
                                    // The library action chooses a random starting track, not a
                                    // playback mode. In SMART this track seeds a fresh smart queue.
                                    playback.play(songs, songs.indices.random())
                                }
                            },
                            modifier = Modifier.size(56.dp)
                                .scaleOnPress(shufflePress, Motion.BUTTON_PRESS_SCALE)
                                .inactiveForMotion(!showShuffle),
                            colors = playButtonColors(),
                        ) {
                            Icon(Icons.Rounded.Shuffle,
                                contentDescription = stringResource(Res.string.action_shuffle_all),
                                modifier = Modifier.size(24.dp))
                        }
                    }

                    // Drilling into a collection slides forward; backing out releases the same
                    // way. AnimatedContent keeps the outgoing screen alive for its exit frames.
                    if (collectionOverlayActive) ModalPointerBlocker()
                    collectionTransition.AnimatedContent(
                        // Track removal, hide/undo, and subtitle reconciliation copy this value.
                        // Route identity stays fixed so those data updates never replay a page push.
                        contentKey = { it?.routeId },
                        transitionSpec = {
                            motionPageTransform(
                                forward = targetState != null,
                                reduceMotion = reduceMotion,
                                layoutDirection = layoutDirection,
                            )
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = browseSearchAlpha() },
                    ) collectionContent@ { animatedSelection ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .inactiveDuringTransition(
                                animatedSelection?.routeId != selectedCollection?.routeId,
                            ),
                    ) {
                    animatedSelection?.let { selection ->
                        val editablePlaylist = playlistsState.playlists.firstOrNull { it.id == selection.playlistId }
                        CollectionDetailScreen(
                            playlist = editablePlaylist,
                            onPlaylistOrderChanged = {
                                refreshPlaylistMemberships()
                                selectedCollection?.let { applySelectedCollection(it) }
                            },
                            artworkModifier = if (
                                reduceMotion || selectedTab != StartPage.PLAYLISTS ||
                                !(selection.routeId.startsWith("playlist:") ||
                                    selection.routeId.startsWith("auto:"))
                            ) {
                                Modifier
                            } else with(playlistSharedScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState("playlist-cover:${selection.routeId}"),
                                    this@collectionContent,
                                    boundsTransform = motionBoundsTransform(),
                                )
                            },
                            active = selection.routeId == selectedCollection?.routeId &&
                                !showNowPlaying && !showSearch && !showSettings,
                            entrySettled = collectionTransition.currentState?.routeId == selection.routeId &&
                                !collectionTransition.isRunning && !playlistSharedScope.isTransitionActive,
                            onToggleSmart = editablePlaylist?.let { playlist ->
                                { togglePlaylistSmart(playlist) }
                            },
                            onAddAllToFavorites = editablePlaylist?.let {
                                {
                                    editFavorites(
                                        SelectionFavoriteAction.ADD,
                                        selection.tracks.map(TrackDescriptor::id),
                                    )
                                }
                            },
                            canAddAllToFavorites = { anyNotFavorite(selection.tracks, favoriteIds) },
                            onExportM3u = if (selection.routeId == FAVORITES_ROUTE_ID) {
                                { exportAsM3u(selection.title) { playlistsState.autoPlaylists.favoriteTracks() } }
                            } else {
                                null
                            },
                            onEditTags = when {
                                selection.routeId.startsWith("album:") -> {
                                    { openTagEditor(selection.tracks, BulkEditScope.ALBUM) }
                                }
                                selection.routeId.startsWith("artist:") -> {
                                    { openTagEditor(selection.tracks, BulkEditScope.ARTIST) }
                                }
                                else -> null
                            },
                            includeInSmart = editablePlaylist?.includeInSmart == true,
                            unavailableCount = playlistAvailability.unavailable
                                ?.takeIf { editablePlaylist != null && it.playlistId == editablePlaylist.id }
                                ?.ids?.size ?: 0,
                            onRemoveUnavailable = editablePlaylist?.let { playlist ->
                                { removeUnavailablePlaylistTracks(playlist.id, playlist.name) }
                            },
                            unavailableRemovalBusy = playlistAvailability.removalInFlight,
                            onChangeCover = editablePlaylist?.let { playlist ->
                                { changePlaylistCover(playlist) }
                            },
                            onResetCover = editablePlaylist
                                ?.takeIf { it.customArtworkRef != null }
                                ?.let { playlist -> { resetPlaylistCover(playlist) } },
                            coverEditBusy = coverEditTargetId != null || coverSaveInProgress,
                            accent = accent,
                            onStartSmart = {
                                scope.launch {
                                    AppGraph.queueSource.value = selection.queueSource()
                                    playback.setShuffleMode(ShuffleMode.SMART)
                                    playback.play(selection.tracks, 0)
                                }
                            },
                            currentAccent = browseAccentInk(accent),
                            selection = selection,
                            // Only a page with albums to order offers the choice.
                            albumSort = artistAlbumSortChoice.takeIf {
                                selection.routeId.startsWith("artist:") && selection.sections != null
                            },
                            onAlbumSortChange = settings::setArtistAlbumSort,
                            onTrackSortChange = { choice ->
                                if (AppGraph.settings.chooseTrackSort(selection, choice)) {
                                    selectedCollection
                                        ?.takeIf { it.routeId == selection.routeId }
                                        ?.let { open -> applySelectedCollection(open) }
                                }
                            },
                            currentTrackId = currentTrack?.id,
                            currentTrackPlaying = currentTrackPlaying,
                            selectedTrackIds = selectedTrackIds,
                            selectionCommands = selectionCommands,
                            onToggleSelection = { track ->
                                updateTrackSelection(if (track.id in selectedTrackIds) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                })
                            },
                            onStartSelection = { track ->
                                updateTrackSelection(setOf(track.id))
                            },
                            onClearSelection = { updateTrackSelection(emptySet()) },
                            onToggleAllSelection = {
                                val playlistIds = selection.tracks.mapTo(LinkedHashSet()) { it.id }
                                updateTrackSelection(if (playlistIds.all(selectedTrackIds::contains)) {
                                    emptySet()
                                } else {
                                    playlistIds
                                })
                            },
                            onPlay = {
                                if (selection.tracks.isNotEmpty()) scope.launch {
                                    AppGraph.queueSource.value = selection.queueSource()
                                    playback.setShuffleMode(ShuffleMode.OFF)
                                    playback.play(selection.tracks, 0)
                                }
                            },
                            onPlayTrack = { index ->
                                AppGraph.queueSource.value =
                                    selection.queueSource()
                                scope.launch { playback.play(selection.tracks, index) }
                            },
                            onShuffle = {
                                if (selection.tracks.isNotEmpty()) scope.launch {
                                    AppGraph.queueSource.value = selection.queueSource()
                                    playback.setShuffleMode(ShuffleMode.ON)
                                    playback.play(selection.tracks, selection.tracks.indices.random())
                                }
                            },
                            onTrackMenu = { track ->
                                sheets.trackMenuRequest = TrackMenuRequest(
                                    track = track,
                                    sourcePlaylistId = selection.playlistId,
                                    sourcePlaylistTitle = selection.title
                                        .takeIf { selection.playlistId != null },
                                )
                            },
                            onClose = {
                                updateTrackSelection(emptySet())
                                updateSelectedCollection(null)
                            },
                            bottomInset = floatingPlayerInset,
                        )
                    }
                    }
                    }

                    // Keep the field anchored while the IME animates. Sliding a focused editor
                    // can make Android pan the entire window to its transient coordinates.
                    if (searchOverlayActive) ModalPointerBlocker()
                    searchTransition.AnimatedVisibility(
                        visible = { it },
                        modifier = Modifier.fillMaxSize(),
                        enter = if (reduceMotion) fadeIn(tween(Motion.REDUCED_MS)) else EnterTransition.None,
                        exit = if (reduceMotion) fadeOut(tween(Motion.REDUCED_MS)) else ExitTransition.None,
                    ) {
                        // Register the morph on AnimatedVisibility itself so interrupted exits
                        // retain the real editor until the returning field has finished drawing.
                        val fieldProgress = transition.animateFloat(
                            transitionSpec = { chromeNavigationSpec(reduceMotion, targetState == EnterExitState.Visible) },
                            label = "search-field-morph",
                        ) { if (it == EnterExitState.Visible) 1f else 0f }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .inactiveDuringTransition(!searchVisibilityState.targetState),
                        ) {
                        SearchScreen(
                            accent = accent,
                            // Search stays composed under an open player: it must not own Back or
                            // the keyboard there, and it focuses its field again on return.
                            active = showSearch && !showNowPlaying,
                            openProgress = { fieldProgress.value },
                            buttonBounds = searchButtonBounds,
                            readyForInput = showSearch && !showNowPlaying && searchVisibilityState.isIdle,
                            focusOnReady = searchWantsInput,
                            songs = catalog?.songs.orEmpty(),
                            currentTrackId = currentTrack?.id,
                            currentTrackPlaying = currentTrackPlaying,
                            selectedTrackIds = selectedTrackIds,
                            selectionCommands = selectionCommands,
                            onToggleSelection = { track ->
                                updateTrackSelection(if (track.id in selectedTrackIds) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                })
                            },
                            onStartSelection = { track ->
                                updateTrackSelection(setOf(track.id))
                            },
                            onClearSelection = { updateTrackSelection(emptySet()) },
                            onPlay = { queue, index -> scope.launch { playback.play(queue, index) } },
                            onTrackMenu = { sheets.trackMenuRequest = TrackMenuRequest(it) },
                            // A selection made among search results has no surface to live on once
                            // the results are gone.
                            onClose = {
                                updateTrackSelection(emptySet())
                                showSearch = false
                            },
                            bottomInset = floatingPlayerInset,
                        )
                        }
                    }

                    AnimatedVisibility(
                        visible = selectionMode,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = if (currentTrack != null) miniPlayerHeight + navBottom else 0.dp)
                            // The player's fade extends above its card; selection actions stay
                            // in front of that fade throughout their own exit animation.
                            .zIndex(2f),
                        enter = if (reduceMotion) {
                            fadeIn(tween(Motion.REDUCED_MS))
                        } else {
                            fadeIn(tween(Motion.APPEAR_MS)) +
                                slideInVertically(tween(Motion.APPEAR_MS)) { it / 2 }
                        },
                        exit = if (reduceMotion) {
                            fadeOut(tween(Motion.REDUCED_MS))
                        } else {
                            fadeOut(tween(Motion.REPLACE_MS)) +
                                slideOutVertically(tween(Motion.REPLACE_MS)) { it / 2 }
                        },
                    ) {
                        Box(
                            modifier = Modifier.inactiveDuringTransition(!selectionMode),
                        ) {
                        SelectionActionBar(
                            commands = selectionCommands,
                            favoriteAction = selectionFavoriteAction(selectedTracks, favoriteIds),
                            height = selectionActionBarHeight,
                            includeNavigationPadding = currentTrack == null,
                        )
                        }
                    }
                    AnimatedVisibility(
                        visible = currentTrack != null,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .zIndex(1f),
                        enter = if (reduceMotion) {
                            fadeIn(tween(Motion.REDUCED_MS))
                        } else {
                            fadeIn(tween(Motion.APPEAR_MS)) +
                                slideInVertically(tween(Motion.APPEAR_MS)) { it / 3 }
                        },
                        exit = if (reduceMotion) {
                            fadeOut(tween(Motion.REDUCED_MS))
                        } else {
                            fadeOut(tween(Motion.REPLACE_MS)) +
                                slideOutVertically(tween(Motion.REPLACE_MS)) { it / 3 }
                        },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .height(miniPlayerHeight + navBottom + 24.dp)
                                .playbackDockFade()
                                .inactiveDuringTransition(
                                currentTrack == null,
                            ),
                        ) {
                        // The opaque dock floor covers list rows, so it must not let taps reach
                        // those hidden rows. The player controls are composed above this layer.
                        Box(
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth().height(miniPlayerHeight + navBottom)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                                .changes.forEach { it.consume() }
                                        }
                                    }
                                },
                        )
                        }
                        }
                    }
                }
                }
            }
            // The mini player and the full player are one surface over the library, moved by one
            // progress: dragged by the finger, settled by a fling, animated by a tap or Back.
            PlayerSheet(
                expansion = playerExpansion,
                open = showNowPlaying,
                onOpenChange = { opens ->
                    showNowPlaying = opens
                    if (!opens) coverStart.begun = false
                },
                onCoverStart = { beginCover() },
                hasTrack = currentTrack != null,
                pillHeight = miniPlayerHeight,
                pillColor = miniPlayerSurfaceColor(
                    if (currentTrack != null) accent else lastMiniPresentation.accent,
                ),
                coverUri = (currentTrack ?: lastMiniPresentation.track)?.artworkUri,
                mini = { live, thumbnailAlpha ->
                    (currentTrack ?: lastMiniPresentation.track)?.let { current ->
                        MiniPlayerPill(
                            height = miniPlayerHeight,
                            track = current,
                            accent = if (currentTrack != null) {
                                accent
                            } else {
                                lastMiniPresentation.accent
                            },
                            isPlaying = if (currentTrack != null) {
                                showPauseButton
                            } else {
                                lastMiniPresentation.isPlaying
                            },
                            playback = playback,
                            artworkAlpha = thumbnailAlpha,
                            live = live,
                            onTogglePlayPause = { scope.launch { playback.togglePlayPause() } },
                            onPrevious = { scope.launch { playback.previous() } },
                            onNext = { scope.launch { playback.next() } },
                            onOpen = { showNowPlaying = true },
                        )
                    }
                },
                full = { revealed, artworkModifier, onCollapseDrag, onCollapseRelease, onCollapseAbandon ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .inactiveDuringTransition(!showNowPlaying || settingsOverlayActive),
                ) {
                    val queueSource by AppGraph.queueSource.collectAsState()
                    NowPlayingScreen(
                        playback = playback,
                        accent = accent,
                        // Kept composed below the pill once used; it follows playback only while
                        // it is on screen or a finger may be about to bring it there.
                        active = revealed && !showSettings,
                        ownsBack = showNowPlaying && !showSettings,
                        parked = !revealed,
                        queueSourceLabel = queueSource?.let { source ->
                            source.name ?: source.kind.fallbackLabelRes()?.let { stringResource(it) }
                        },
                        sleepTimerState = sleepTimerState,
                        onStartSleepTimer = sleepTimer::startCountdown,
                        onSleepAtEndOfTrack = sleepTimer::startAtEndOfTrack,
                        onCancelSleepTimer = sleepTimer::cancel,
                        onTrackMenu = { track ->
                            sheets.trackMenuRequest = TrackMenuRequest(track, fromPlayer = true)
                        },
                        onQueueTrackMenu = { track -> sheets.trackMenuRequest = TrackMenuRequest(track) },
                        detailsRequest = sheets.playerDetailsRequest,
                        sleepTimerRequest = sheets.playerSleepTimerRequest,
                        // Both close the player themselves once the collection is built; closing
                        // it first would change the root the open is guarded against mid-flight.
                        onGoToAlbum = { track -> showAlbumOf(track) },
                        onGoToArtist = { track -> showArtistOf(track) },
                        onOpenSource = queueSource?.let { source -> { openQueueSource(source) } },
                        smartQueueLength = smartQueueLength,
                        onSmartQueueLength = settings::setSmartQueueLength,
                        onEditTags = { showTrackInfo(it.id, editing = true) },
                        onShowOnMap = if (StartPage.MAP in visiblePages) {
                            { track -> showTrackOnMap(track) }
                        } else null,
                        onOpenEqualizer = {
                            settingsRoute = SETTINGS_ROUTE_EQUALIZER
                            showSettings = true
                        },
                        onAddQueueToPlaylist = {
                            playback.state.value.queue
                                .takeIf { it.isNotEmpty() }
                                ?.let { sheets.addToPlaylistSelection = it }
                        },
                        isFavorite = currentTrack?.id?.let { it in favoriteIds } == true,
                        onToggleFavorite = { currentTrack?.id?.let(::toggleFavorite) },
                        onCollapseDrag = onCollapseDrag,
                        onCollapseRelease = onCollapseRelease,
                        onCollapseAbandon = onCollapseAbandon,
                        artworkModifier = artworkModifier,
                        onClose = { showNowPlaying = false },
                    )
                }
                },
            )
        }
            // One host above both AnimatedContent branches: collection/search surfaces and the
            // full player otherwise cover the Scaffold-owned host, making Undo technically exist
            // but impossible to see or tap.
            if (!settingsOverlayActive) AppSnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(10f)
                    .padding(
                        bottom = if (
                            !settingsOverlayActive && !showNowPlaying
                        ) {
                            floatingPlayerInset + navBottom
                        } else {
                            navBottom
                        },
                    ),
            )
        }

        if (sheets.artistChoices.isNotEmpty()) {
            ArtistChooserSheet(
                artists = sheets.artistChoices,
                onSelect = { showArtist(it) },
                onDismiss = { sheets.artistChoices = emptyList() },
            )
        }

        sheets.trackMenuRequest?.let { request ->
            val target = request.track
            val trackExcluded = target.id in smartExclusionState.trackIds
            val artist = target.artist?.trim()?.takeIf(String::isNotEmpty)
            val artistExcluded = smartExclusionState.excludesArtist(artist)
            TrackActionsSheet(
                track = target,
                onPlay = {
                    // A one-track queue from the menu has no browsing source to point at.
                    AppGraph.queueSource.value = null
                    scope.launch { playback.play(listOf(target), 0) }
                },
                onPlayNext = {
                    scope.launch {
                        if (playback.state.value.queue.isEmpty()) AppGraph.queueSource.value = null
                        playback.playNext(target)
                    }
                },
                onAddToQueue = {
                    scope.launch {
                        if (playback.state.value.queue.isEmpty()) AppGraph.queueSource.value = null
                        playback.addToQueue(target)
                    }
                },
                onShare = if (target.audioUri != null) {
                    { shareTracks(listOf(target)) }
                } else null,
                isFavorite = target.id in favoriteIds,
                onToggleFavorite = { toggleFavorite(target.id) },
                onShowOnMap = if (StartPage.MAP in visiblePages) {
                    { showTrackOnMap(target) }
                } else null,
                onAddToPlaylist = { sheets.addToPlaylistSelection = listOf(target) },
                // Only from inside a user playlist whose list actually holds this track. The
                // sheet can be raised over a playlist from other surfaces (the queue sheet, For
                // You); removal must never appear there and silently edit an unrelated playlist.
                onRemoveFromPlaylist = request.sourcePlaylistId?.let { playlistId ->
                    {
                        scope.launch {
                            var membershipChange: PlaylistTrackChange? = null
                            val persisted = try {
                                membershipChange = AppGraph.playlists.removeTrack(
                                    playlistId,
                                    target.id,
                                )
                                membershipChange != null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                false
                            }
                            if (!persisted) {
                                snackbar.showSnackbar(
                                    getString(Res.string.settings_library_manage_failed),
                                )
                                return@launch
                            }
                            // The open screen keeps its own copy of the list; drop the row
                            // immediately after the durable write succeeds.
                            val currentPlaylist = selectedCollection
                                ?.takeIf { it.playlistId == playlistId }
                            if (currentPlaylist != null) {
                                val remaining = currentPlaylist.tracks
                                    .filterNot { it.id == target.id }
                                updateSelectedCollection(currentPlaylist.copy(
                                    subtitle = trackCountLabel(remaining.size),
                                    tracks = remaining,
                                ))
                                updateTrackSelection(selectedTrackIds - target.id)
                            }
                            val change = checkNotNull(membershipChange)
                            playlistsState.playlists = playlistsState.playlists.map { stored ->
                                if (stored.id == playlistId) {
                                    change.appliedTo(stored)
                                } else {
                                    stored
                                }
                            }
                            refreshPlaylistStatsBestEffort()
                            val result = snackbar.showSnackbar(
                                message = getString(
                                    Res.string.snack_playlist_track_removed,
                                    bidiWrap(request.sourcePlaylistTitle.orEmpty(), layoutDirection),
                                ),
                                actionLabel = if (change.before != change.after) {
                                    getString(Res.string.action_undo)
                                } else {
                                    null
                                },
                                withDismissAction = change.before != change.after,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                val restored = try {
                                    AppGraph.playlists.restoreTracksIfUnchanged(playlistId, change)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    false
                                }
                                if (!restored) {
                                    snackbar.showSnackbar(
                                        getString(Res.string.settings_library_manage_failed),
                                    )
                                    return@launch
                                }
                                playlistsState.playlists = playlistsState.playlists.map { stored ->
                                    if (stored.id == playlistId) {
                                        change.revertedIn(stored)
                                    } else {
                                        stored
                                    }
                                }
                                selectedCollection
                                    ?.takeIf { it.playlistId == playlistId }
                                    ?.let { current ->
                                        val restoredTracks = change.before
                                            .mapNotNull(tracksById::get)
                                        updateSelectedCollection(current.copy(
                                            subtitle = trackCountLabel(restoredTracks.size),
                                            tracks = restoredTracks,
                                        ))
                                    }
                            }
                        }
                    }
                },
                onGoToAlbum = target.album?.takeIf(String::isNotBlank)?.let {
                    { showAlbumOf(target) }
                },
                onGoToArtist = target.artist?.takeIf(String::isNotBlank)?.let {
                    { showArtistOf(target) }
                },
                onInfo = {
                    if (request.fromPlayer && target.id == playback.state.value.track?.id) {
                        sheets.playerDetailsRequest++
                    } else showTrackInfo(target.id, editing = false)
                },
                // The sleep timer belongs to the player's own sheet; other surfaces keep the
                // shorter list they always had.
                onSleepTimer = if (request.fromPlayer) {
                    { sheets.playerSleepTimerRequest++ }
                } else null,
                sleepTimerState = sleepTimerState,
                isTrackExcludedFromSmart = trackExcluded,
                isArtistExcludedFromSmart = artistExcluded,
                onToggleTrackSmartExclusion = {
                    if (trackExcluded) {
                        changeSmartExclusion(
                            successMessage = Res.string.snack_track_included_in_smart,
                            change = { smartExclusions.includeTrack(target.id) },
                            undo = { smartExclusions.excludeTrack(target.id) },
                        )
                    } else {
                        changeSmartExclusion(
                            successMessage = Res.string.snack_track_excluded_from_smart,
                            change = { smartExclusions.excludeTrack(target.id) },
                            undo = { smartExclusions.includeTrack(target.id) },
                        )
                    }
                },
                onToggleArtistSmartExclusion = artist?.let { artistName ->
                    {
                        if (artistExcluded) {
                            changeSmartExclusion(
                                successMessage = Res.string.snack_artist_included_in_smart,
                                change = { smartExclusions.includeArtist(artistName) },
                                undo = { smartExclusions.excludeArtist(artistName) },
                            )
                        } else {
                            changeSmartExclusion(
                                successMessage = Res.string.snack_artist_excluded_from_smart,
                                change = { smartExclusions.excludeArtist(artistName) },
                                undo = { smartExclusions.includeArtist(artistName) },
                            )
                        }
                    }
                },
                onHide = {
                    scope.launch {
                        val collectionBeforeHide = selectedCollection
                        val sourceCollectionRevision = collectionRevision
                        val sourceRootTab = rootTabSnapshot()
                        val hidden = try {
                            library.hide(target.id)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                        if (!hidden) {
                            snackbar.showSnackbar(
                                getString(Res.string.settings_library_manage_failed),
                            )
                            return@launch
                        }
                        tracks?.filterNot { it.id == target.id }?.let { remaining ->
                            publishLibraryTracks(
                                remaining,
                                libraryIndexingRequest?.librarySnapshotAuthoritative ?: false,
                            )
                        }
                        libraryScan.hasHiddenTracks = true
                        val collectionAfterHide = collectionBeforeHide?.let { selection ->
                            selection.filterTracksForCollection { it.id != target.id }
                                ?.let { remaining ->
                                    remaining.copy(
                                        subtitle = trackCountLabel(remaining.tracks.size),
                                    )
                                }
                        }
                        val appliedCollectionRevision =
                            if (collectionRevision == sourceCollectionRevision) {
                                updateSelectedCollection(collectionAfterHide)
                                collectionRevision
                            } else {
                                null
                            }
                        val result = snackbar.showSnackbar(
                            message = getString(Res.string.snack_removed_from_latentjam),
                            actionLabel = getString(Res.string.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            val restored = try {
                                library.unhide(target.id)
                                scanLibrary()
                                libraryScan.hasHiddenTracks = library.hasHiddenTracks()
                                true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                false
                            }
                            if (!restored) {
                                snackbar.showSnackbar(
                                    getString(Res.string.settings_library_manage_failed),
                                )
                                return@launch
                            }
                            // Persisted visibility and page navigation are independent. The track
                            // is unhidden above, but a root-tab journey while the snackbar was up
                            // must not reopen the auto-playlist detail that this hide emptied.
                            if (shouldRestoreCollectionAfterHideUndo(
                                    appliedCollectionRevision = appliedCollectionRevision,
                                    currentCollectionRevision = collectionRevision,
                                    sourceRootTab = sourceRootTab,
                                    currentRootTab = rootTabSnapshot(),
                                )
                            ) {
                                updateSelectedCollection(collectionBeforeHide)
                            }
                        }
                    }
                },
                canDelete = canDeleteTrack(target),
                onDelete = { sheets.deleteTarget = target },
                onDismiss = { sheets.trackMenuRequest = null },
            )
        }

        if (sheets.showSelectionRemoval) {
            val selection = selectedTracks
            SelectionRemovalSheet(
                count = selection.size,
                canDeleteFromDevice = selection.isNotEmpty() && selection.all(::canDeleteTrack),
                onHide = {
                    scope.launch {
                        val ids = selection.map { it.id }
                        val collectionBeforeHide = selectedCollection
                        val sourceCollectionRevision = collectionRevision
                        val sourceRootTab = rootTabSnapshot()
                        updateTrackSelection(emptySet())
                        val commandSelectionRevision = selectionRevision
                        val hidden = try {
                            // Platform implementations persist the whole membership change in one
                            // atomic write, so disk failure cannot hide only half the selection.
                            library.hide(ids)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                        if (!hidden) {
                            if (
                                collectionRevision == sourceCollectionRevision &&
                                selectionRevision == commandSelectionRevision
                            ) {
                                updateTrackSelection(ids.toSet())
                            }
                            snackbar.showSnackbar(
                                getString(Res.string.settings_library_manage_failed),
                            )
                            return@launch
                        }
                        val hiddenIds = ids.toHashSet()
                        tracks?.filterNot { it.id in hiddenIds }?.let { remaining ->
                            publishLibraryTracks(
                                remaining,
                                libraryIndexingRequest?.librarySnapshotAuthoritative ?: false,
                            )
                        }
                        libraryScan.hasHiddenTracks = true
                        val collectionAfterHide = collectionBeforeHide?.let { collection ->
                            collection.filterTracksForCollection { it.id !in hiddenIds }
                                ?.let { remaining ->
                                    remaining.copy(
                                        subtitle = trackCountLabel(remaining.tracks.size),
                                    )
                                }
                        }
                        val appliedCollectionRevision =
                            if (collectionRevision == sourceCollectionRevision) {
                                updateSelectedCollection(collectionAfterHide)
                                collectionRevision
                            } else {
                                null
                            }
                        val result = snackbar.showSnackbar(
                            message = getString(Res.string.snack_removed_from_latentjam),
                            actionLabel = getString(Res.string.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            val restored = try {
                                library.unhide(ids)
                                scanLibrary()
                                libraryScan.hasHiddenTracks = library.hasHiddenTracks()
                                true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                false
                            }
                            if (!restored) {
                                snackbar.showSnackbar(
                                    getString(Res.string.settings_library_manage_failed),
                                )
                                return@launch
                            }
                            if (shouldRestoreCollectionAfterHideUndo(
                                    appliedCollectionRevision = appliedCollectionRevision,
                                    currentCollectionRevision = collectionRevision,
                                    sourceRootTab = sourceRootTab,
                                    currentRootTab = rootTabSnapshot(),
                                )
                            ) {
                                updateSelectedCollection(collectionBeforeHide)
                            }
                        }
                    }
                },
                onDeleteFromDevice = { sheets.deleteSelection = selection },
                onDismiss = { sheets.showSelectionRemoval = false },
            )
        }

        sheets.worldTarget?.let { target ->
            val world = target.collection
            WorldActionsSheet(
                card = target,
                onOpen = {
                    scope.launch {
                        updateSelectedCollection(CollectionSelection(
                            title = world?.title.orEmpty(),
                            subtitle = trackCountLabel(world?.tracks?.size ?: 0),
                            artworkUri = target.track.artworkUri,
                            tracks = world?.tracks.orEmpty(),
                            routeId = "world:${target.track.id.value}",
                        ))
                    }
                },
                onStartSmart = {
                    scope.launch {
                        // The same track the card showed, so the journey starts from the record
                        // the listener was looking at when they chose this.
                        val songs = catalog?.songs.orEmpty()
                        val queue = engine.smartQueue(
                            target.track,
                            smartEligibleTracks,
                            smartQueueLength,
                            smartHistoryFor(AppGraph.history, target.track),
                            AppGraph.smartCompanionGroups.value,
                        )
                        val byId = songs.associateBy { it.id }
                        AppGraph.queueSource.value = QueueSource(QueueSourceKind.FOR_YOU)
                        playback.play(
                            listOf(target.track) + queue.mapNotNull(byId::get),
                            0,
                            smartPlanPositions(queue),
                        )
                    }
                },
                onDismiss = { sheets.worldTarget = null },
            )
        }

        sheets.m3uImportOffer?.let { offer ->
            M3uImportTargetSheet(
                offer = offer,
                onNewPlaylist = { scope.launch { importM3uAsPlaylists(offer) } },
                onFavorites = { editFavorites(SelectionFavoriteAction.ADD, offer.favoriteIds) },
                onDismiss = { sheets.m3uImportOffer = null },
            )
        }

        val playlistMutationErrorMessage = if (sheets.playlistMutationFailed) {
            stringResource(Res.string.settings_library_manage_failed)
        } else {
            null
        }

        sheets.addToPlaylistSelection?.let { selection ->
            AddToPlaylistSheet(
                tracks = selection,
                playlists = playlistsState.playlists,
                resolvedSize = { tracksOf(it).size },
                onAddTo = { playlist ->
                    if (sheets.playlistMutationInProgress) return@AddToPlaylistSheet
                    val selectedBefore = selectedTrackIds
                    val sourceCollectionRevision = collectionRevision
                    updateTrackSelection(emptySet())
                    val commandSelectionRevision = selectionRevision
                    sheets.playlistMutationInProgress = true
                    sheets.playlistMutationFailed = false
                    scope.launch {
                        val added = try {
                            runPlaylistMutation(showFailureSnackbar = false) {
                                AppGraph.playlists.addTracks(
                                    playlist.id,
                                    selection.map(TrackDescriptor::id),
                                )
                            }
                        } finally {
                            // Also runs for CancellationException; a cancelled child must never
                            // leave every playlist dialog disabled in a still-live composition.
                            sheets.playlistMutationInProgress = false
                        }
                        if (!added) {
                            sheets.playlistMutationFailed = true
                            if (
                                selectionRevision == commandSelectionRevision &&
                                collectionRevision == sourceCollectionRevision
                            ) {
                                updateTrackSelection(selectedBefore)
                            }
                            return@launch
                        }
                        sheets.playlistMutationFailed = false
                        sheets.addToPlaylistSelection = null
                        refreshPlaylistMembershipsBestEffort()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_added_to_playlist, bidiWrap(playlist.name, layoutDirection)),
                        )
                    }
                },
                onCreateNew = {
                    // Remember the selection so the new playlist starts with every chosen track.
                    pendingPlaylistTrackIds = selection.map { it.id.value }
                    sheets.playlistMutationFailed = false
                    showCreatePlaylist = true
                },
                onDismiss = {
                    sheets.addToPlaylistSelection = null
                    sheets.playlistMutationFailed = false
                },
                busy = sheets.playlistMutationInProgress,
                errorMessage = playlistMutationErrorMessage,
            )
        }

        if (showCreatePlaylist) {
            val tracksToSeed = pendingPlaylistTrackIds
            PlaylistNameDialog(
                title = stringResource(Res.string.playlist_new),
                confirmLabel = stringResource(Res.string.action_create),
                onConfirm = { name ->
                    if (sheets.playlistMutationInProgress) return@PlaylistNameDialog
                    val selectedBefore = selectedTrackIds
                    val sourceCollectionRevision = collectionRevision
                    updateTrackSelection(emptySet())
                    val commandSelectionRevision = selectionRevision
                    sheets.playlistMutationInProgress = true
                    sheets.playlistMutationFailed = false
                    scope.launch {
                        var created: Playlist? = null
                        val saved = try {
                            runPlaylistMutation(showFailureSnackbar = false) {
                                created = AppGraph.playlists.create(
                                    name,
                                    tracksToSeed.map(::TrackId),
                                )
                            }
                        } finally {
                            sheets.playlistMutationInProgress = false
                        }
                        if (!saved) {
                            sheets.playlistMutationFailed = true
                            if (
                                selectionRevision == commandSelectionRevision &&
                                collectionRevision == sourceCollectionRevision
                            ) {
                                updateTrackSelection(selectedBefore)
                            }
                            return@launch
                        }
                        sheets.playlistMutationFailed = false
                        val persisted = checkNotNull(created)
                        playlistsState.playlists = listOf(persisted) + playlistsState.playlists.filterNot {
                            it.id == persisted.id
                        }
                        showCreatePlaylist = false
                        pendingPlaylistTrackIds = emptyList()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_playlist_created, bidiWrap(persisted.name, layoutDirection)),
                        )
                    }
                },
                onDismiss = {
                    showCreatePlaylist = false
                    pendingPlaylistTrackIds = emptyList()
                    sheets.playlistMutationFailed = false
                },
                busy = sheets.playlistMutationInProgress,
                errorMessage = playlistMutationErrorMessage,
            )
        }

        playlistsState.playlists.firstOrNull { it.id == renameTargetId }?.let { target ->
            PlaylistNameDialog(
                title = stringResource(Res.string.playlist_rename_title),
                initialName = target.name,
                confirmLabel = stringResource(Res.string.action_rename),
                onConfirm = { name ->
                    if (sheets.playlistMutationInProgress) return@PlaylistNameDialog
                    sheets.playlistMutationInProgress = true
                    sheets.playlistMutationFailed = false
                    scope.launch {
                        val renamed = try {
                            runPlaylistMutation(showFailureSnackbar = false) {
                                AppGraph.playlists.rename(target.id, name)
                            }
                        } finally {
                            sheets.playlistMutationInProgress = false
                        }
                        if (!renamed) {
                            sheets.playlistMutationFailed = true
                            return@launch
                        }
                        sheets.playlistMutationFailed = false
                        val normalizedName = name.trim().ifEmpty { "Untitled playlist" }
                        playlistsState.playlists = playlistsState.playlists.map { playlist ->
                            if (playlist.id == target.id) {
                                playlist.copy(name = normalizedName)
                            } else {
                                playlist
                            }
                        }
                        renameTargetId = null
                    }
                },
                onDismiss = {
                    renameTargetId = null
                    sheets.playlistMutationFailed = false
                },
                busy = sheets.playlistMutationInProgress,
                errorMessage = playlistMutationErrorMessage,
            )
        }

        // Once, always composed: it launches the tag-save prompts every editor's saves wait on.
        TagWriteHost()

        ShellDeleteDialogs(sheets, deleteTrack, clearSelection = { updateTrackSelection(emptySet()) })

        // Restored editor fields are consumed once the catalog has hydrated after rotation.
        // Resolving the saved identity also avoids holding stale metadata after a rescan.
        val infoTarget = infoTargetId?.let { tracksById[TrackId(it)] }
        infoTarget?.let { target ->
            TrackInfoSheet(
                track = target,
                initiallyEditing = infoStartsEditing,
                // Without this the list keeps the old title until relaunch — the write lands, the
                // rescan finishes, and the UI is still holding the pre-edit snapshot.
                onSaved = { saved, result -> afterTagSave(listOf(saved), result) },
                onDismiss = { infoTargetId = null },
            )
        }
        // Resolved once the catalog has hydrated; kept while the sheet is open, since a reload after
        // a partial save must not restart the sheet's file reading.
        val bulkTargets = remember(bulkEditIds, tracksById.isNotEmpty()) {
            bulkEditIds?.mapNotNull { tracksById[TrackId(it)] }?.takeIf { it.isNotEmpty() }
        }
        bulkTargets?.let { targets ->
            BulkTagEditorSheet(
                tracks = targets,
                scope = bulkEditScope,
                onSaved = { saved, result ->
                    afterTagSave(saved, result)
                    if (result.notChanged.isEmpty()) {
                        scope.launch {
                            snackbar.showSnackbar(
                                getPluralString(Res.plurals.tags_saved, result.savedCount, result.savedCount),
                            )
                        }
                    }
                },
                onDismiss = { bulkEditIds = null },
            )
        }

        val unclaimedTagReports = tagAccess?.coordinator?.unclaimed?.collectAsState()?.value.orEmpty()
        // An editor shown, or one restored that the catalog has yet to resolve. A saved target that
        // no longer resolves once it has (the song was deleted) shows nothing, so it holds nothing.
        val tagEditorOpen = (infoTargetId != null || bulkEditIds != null) &&
            (catalog == null || infoTarget != null || bulkTargets != null)
        // Reports no editor claimed. They are acknowledged at once, because acknowledging restarts this
        // effect, and followed outside it so a restart cannot cut a reload or snackbar short.
        LaunchedEffect(unclaimedTagReports, tagEditorOpen, libraryLoaded) {
            val access = tagAccess ?: return@LaunchedEffect
            // An open editor claims its own report; the others wait until it closes. And until the
            // library has loaded: the carry-overs are resolved from it, and a report restored at a
            // cold start arrives before it.
            if (tagEditorOpen || unclaimedTagReports.isEmpty() || !libraryLoaded) return@LaunchedEffect
            val reports = unclaimedTagReports
            reports.forEach(access.coordinator::acknowledge)
            val results = reports.map { it to TagSaveResult.of(it, access.readOnlyIsMusicLibrary) }
            // Resolved before the reload: the library still holds each file's pre-edit revision,
            // which SMART's carry-overs are keyed by.
            val held = tracks.orEmpty()
            val edits = results
                .filter { (report, _) -> report.kind == TagWriteKind.EDIT }
                // Every file the report names, as the editors pass theirs: one that already held a
                // new cover gets it recorded too. The carry-overs pick the written files themselves.
                .map { (_, result) -> tracksWithKeys(held, result.keys(), access::keyOf) to result }
            val follow = followTagSaves(edits, results.map { it.second })
            // The notices belong to this composition's snackbar host; they wait for the follow-up.
            scope.launch {
                follow?.join()
                for ((report, result) in results) {
                    for (notice in tagReportNotices(report.kind, result)) {
                        snackbar.showSnackbar(tagReportNoticeText(notice))
                    }
                }
            }
        }

        settingsTransition.AnimatedVisibility(
            visible = { it },
            // One reversible clock owns the reveal, glyph, title and content. Keep the surface
            // measured at full size and remove its transient clip when navigation settles.
            enter = if (reduceMotion) fadeIn(tween(Motion.REDUCED_MS)) else EnterTransition.None,
            exit = if (reduceMotion) fadeOut(tween(Motion.REDUCED_MS)) else ExitTransition.None,
        ) {
            val revealProgress = transition.animateFloat(
                transitionSpec = { chromeNavigationSpec(reduceMotion, targetState == EnterExitState.Visible) },
                label = "settings-button-reveal",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .settingsButtonReveal({ revealProgress.value }, settingsButtonBounds)
                    .inactiveDuringTransition(!settingsVisibilityState.targetState),
            ) {
            SettingsScreen(
                active = showSettings,
                startRoute = settingsRoute,
                accent = accent,
                rootOpenProgress = { revealProgress.value },
                rootButtonBounds = settingsButtonBounds,
                settings = settings,
                equalizer = AppGraph.equalizer,
                engine = engine,
                history = AppGraph.history,
                recentSearches = AppGraph.recentSearches,
                tracks = tracks.orEmpty(),
                interruptedSaves = interruptedSaveRows,
                onFinishInterruptedSaves = { tagAccess?.coordinator?.enqueueRecovery() },
                onForgetInterruptedSave = { save ->
                    scope.launch {
                        // A file can hold more than one open record; the row stands for all of them.
                        interruptedSaves.filter { it.target == save.record.target }.forEach { tagAccess?.coordinator?.forget(it) }
                    }
                },
                libraryLoading = tracks == null,
                libraryRefreshing = libraryScan.libraryRefreshing,
                hasHiddenTracks = libraryScan.hasHiddenTracks,
                canImportAudio = audioImportAvailable,
                onRefreshLibrary = {
                    if (!libraryScan.libraryRefreshing) {
                        libraryScan.libraryRefreshing = true
                        scope.launch {
                            try {
                                // Music copied in without the system being told is in no index yet
                                // (#8): the system indexes it first, then the scan below sees it.
                                library.indexNewFiles()
                                scanLibrary()
                                libraryScan.hasHiddenTracks = library.hasHiddenTracks()
                                snackbar.showSnackbar(getString(Res.string.snack_library_refreshed))
                            } finally {
                                libraryScan.libraryRefreshing = false
                            }
                        }
                    }
                },
                onImportAudio = importAudio,
                onRetryIndexing = ::retryAutomaticIndexing,
                onRebuildAnalysis = {
                    engine.clearAnalysis()
                    libraryWorlds.metadataVectorsReady = false
                    libraryWorlds.audioVectorsReady = false
                    libraryWorlds.worlds = emptyList()
                    libraryWorlds.worldLibraryIds = emptyList()
                    // The key is the cache of the very thing being invalidated: leaving it set
                    // lets a same-inputs rebuild short-circuit, and worlds would stay empty.
                    libraryWorlds.builtWorldsKey = null
                    invalidateSmartRecommendationCaches()
                    retryAutomaticIndexing()
                },
                onHideTrack = { target ->
                    hideTracksAndRefresh(
                        hide = { library.hide(target.id) },
                        refresh = {
                            libraryScan.hasHiddenTracks = true
                            scanLibrary()
                            updateSelectedCollection(selectedCollection?.let { selection ->
                                selection.filterTracksForCollection { it.id != target.id }
                                    ?.let { remaining ->
                                        remaining.copy(
                                            subtitle = trackCountLabel(remaining.tracks.size),
                                        )
                                    }
                            })
                            invalidateSmartRecommendationCaches()
                        },
                    )
                },
                onHideTracks = { targets ->
                    // One scan for the whole batch: a bulk duplicate merge would otherwise
                    // re-query the library once per hidden copy.
                    val ids = targets.mapTo(HashSet()) { it.id }
                    hideTracksAndRefresh(
                        hide = { library.hide(ids) },
                        refresh = {
                            libraryScan.hasHiddenTracks = true
                            scanLibrary()
                            updateSelectedCollection(selectedCollection?.let { selection ->
                                selection.filterTracksForCollection { it.id !in ids }
                                    ?.let { remaining ->
                                        remaining.copy(
                                            subtitle = trackCountLabel(remaining.tracks.size),
                                        )
                                    }
                            })
                            invalidateSmartRecommendationCaches()
                        },
                    )
                },
                onDeleteTracks = deleteTrack,
                onDuplicateDataChanged = {
                    favoriteIds = AppGraph.favorites.all()
                    refreshPlaylistMemberships()
                },
                onBackupRestored = {
                    scanLibrary()
                    // A restore can bring hearts back; the shell's copy follows the store.
                    favoriteIds = AppGraph.favorites.all()
                    libraryScan.hasHiddenTracks = library.hasHiddenTracks()
                    updateTrackSelection(emptySet())
                    updateSelectedCollection(null)
                    libraryWorlds.metadataVectorsReady = false
                    libraryWorlds.audioVectorsReady = false
                    libraryWorlds.worlds = emptyList()
                    libraryWorlds.worldLibraryIds = emptyList()
                    libraryWorlds.builtWorldsKey = null
                    forYouPage.personalizationRevision += 1
                    // The restored log replaces the old one without recording a listen, so the
                    // recorder's revision cannot report it: advance the replacement epoch.
                    replacedHistoryEpoch += 1
                    invalidateSmartRecommendationCaches()
                    refreshPlaylistsWithFeedback()
                },
                onClearListeningHistory = {
                    AppGraph.history.clear()
                    AppGraph.smartImpressionLog?.clear()
                    AppGraph.forgetSmartPresses()
                    forYouPage.forYou = ForYouPage()
                    forYouPage.builtWorlds = null
                    forYouPage.builtForYouLibraryIds = null
                    forYouPage.builtForYouPlaylistIdentity = null
                    forYouPage.personalizationRevision += 1
                    // Emptied log, same problem: the caches keyed on history must not survive it.
                    replacedHistoryEpoch += 1
                    refreshPlaylistsWithFeedback()
                },
                onClearRecentSearches = {
                    AppGraph.recentSearches.clear()
                },
                snackbarHostState = snackbar,
                onClose = {
                    showSettings = false
                    settingsRoute = null
                },
            )
        }
    }
}

/** Coarse library states only; catalog refreshes within CONTENT must never replay its entrance. */
private enum class LibraryPresentation { LOADING, CONTENT }

/** Queue rows, from the playing one on, whose descriptors the tag pass refreshes in the player. */
private const val SOON_QUEUED_ROWS = 16
