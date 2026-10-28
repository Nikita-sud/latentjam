/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.associateByIdValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import io.github.nikitasud.latentjam.history.ForYouImpression
import io.github.nikitasud.latentjam.history.SmartExclusionState
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.AutoPlaylist
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.LayoutPoint
import io.github.nikitasud.latentjam.smart.cluster.StoredLibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.WorldNamingGroups
import io.github.nikitasud.latentjam.smart.cluster.loadStoredLayout
import io.github.nikitasud.latentjam.smart.cluster.saveLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Shared-element key of the overflow button. */
internal const val OVERFLOW_KEY = "overflow-button"

/**
 * Root-pager state captured when a hide command starts and again when its Undo finishes.
 * [navigationRevision] is an invalidation token, not a count shown to users: it may advance both
 * when navigation is requested and when a swipe settles so neither input path can be missed.
 */
internal data class RootTabSnapshot(
    val navigationRevision: Long,
    val currentTab: Int,
    val settledTab: Int,
)

/**
 * A hide Undo may restore the persisted track regardless of navigation, but it may only restore
 * the detail-page snapshot if the listener has stayed on the exact root page that owned it.
 */
internal fun shouldRestoreCollectionAfterHideUndo(
    appliedCollectionRevision: Long?,
    currentCollectionRevision: Long,
    sourceRootTab: RootTabSnapshot,
    currentRootTab: RootTabSnapshot,
): Boolean =
    appliedCollectionRevision != null &&
        currentCollectionRevision == appliedCollectionRevision &&
        sourceRootTab.navigationRevision == currentRootTab.navigationRevision &&
        sourceRootTab.currentTab == sourceRootTab.settledTab &&
        currentRootTab.currentTab == sourceRootTab.currentTab &&
        currentRootTab.settledTab == sourceRootTab.settledTab

/**
 * What a set of discovered library worlds was built from.
 *
 * Clustering is deterministic, so equal keys mean an equal result and the work can be skipped.
 * The vector source is part of the key because upgrading metadata-only vectors to fused
 * audio+metadata ones genuinely changes the answer, and that rebuild must still happen.
 */
internal data class LibraryWorldDiscoveryKey(
    val trackIds: List<TrackId>,
    val source: LibraryVectorSource,
    /** Raw selected-vector contents, not just their population/source. */
    val vectorFingerprint: Long,
    /** Metadata that names/routes worlds even when its embedding happens to stay equal. */
    val descriptorIdentity: List<WorldTrackIdentity>,
    val semanticsCount: Int,
)

internal data class LibraryWorldsKey(
    val discovery: LibraryWorldDiscoveryKey,
    /** Ordered user names/membership used to name the already-discovered worlds. */
    val playlistIdentity: List<PlaylistPresentationIdentity>,
)

/** Playlist fields that can change a For You collection or a discovered-world title. */
internal data class PlaylistPresentationIdentity(
    val id: String,
    val name: String,
    val trackIds: List<String>,
)

internal fun List<Playlist>.presentationIdentity(): List<PlaylistPresentationIdentity> = map { playlist ->
    PlaylistPresentationIdentity(
        id = playlist.id,
        name = playlist.name,
        trackIds = playlist.trackIds.toList(),
    )
}

/** Membership policy consumed by both foreground and background SMART planners. */
/** Every playlist's ordered ids for the FULL ranker's playlist bonus and R8 (fast 3.3); one-track lists share nothing. */
internal fun List<Playlist>.smartUserPlaylists(): List<List<TrackId>> =
    map { playlist -> playlist.trackIds.map(::TrackId) }.filter { it.distinct().size >= 2 }

/** The playlists the chain's personal term reads (PERSONALISATION_RULES O): unmarked ones; a marked one acts only through keep together. */
internal fun List<Playlist>.smartPersonalPlaylists(): List<List<TrackId>> = filterNot(Playlist::includeInSmart).smartUserPlaylists()

internal fun List<Playlist>.smartCompanionMemberships(): List<Set<TrackId>> {
    val normalized = asSequence()
        .filter(Playlist::includeInSmart)
        .map { playlist -> playlist.trackIds.distinct().sorted().map(::TrackId) }
        // A singleton cannot keep anything together. Omitting it also avoids throwing away a
        // perfectly good generated future when toggling an ineffective one-track playlist.
        .filter { it.size >= 2 }
        // Core SMART deliberately collapses identical membership groups. Mirror its effective
        // policy here so adding a second playlist with the same tracks does not prune/refill an
        // unchanged future (or wake the lazy model) for no behavioral gain.
        .distinct()
        .toList()
    // Playlist drag order is presentation, not recommendation policy. Quota rotation is ordered,
    // so give core SMART a canonical membership order or merely reorganizing the Playlists tab
    // changes the next recommendation and needlessly refills the queue.
    return normalized.sortedWith { left, right ->
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = left[index].value.compareTo(right[index].value)
            if (comparison != 0) return@sortedWith comparison
        }
        left.size.compareTo(right.size)
    }.map { it.toCollection(LinkedHashSet()) }
}

/** Initial hydration reconstructs saved policy; only a later real change invalidates lookahead. */
internal fun shouldInvalidateSmartFuture(
    policyInitialized: Boolean,
    previous: List<Set<TrackId>>,
    updated: List<Set<TrackId>>,
): Boolean = policyInitialized && previous != updated

/** How long Artist variety must rest on a level before the SMART tracks already queued are replanned for it. */
internal const val ARTIST_VARIETY_SETTLE_MS: Long = 400L

/**
 * Applies the Artist variety setting to SMART, including the tracks SMART has already queued.
 *
 * The engine plans with a level's penalty from its next plan on, but SMART keeps a planned future: the rows ahead of
 * the playhead (the SMART queue length) and the chooser's unserved plan. Left alone, a new level would first be heard
 * twenty to thirty tracks later, so a change replans that future, as marking a playlist does. App runs [apply] for
 * each level the setting takes and cancels it when the next one arrives, so a drag across the slider replans once,
 * at the level it rests on.
 */
internal class ArtistVarietyApplier(
    private val setPenalty: (Float) -> Unit,
    private val invalidateSmartFuture: suspend () -> Unit,
) {
    /**
     * The level the queued SMART future follows. Null until the first [apply], which only restores the saved level:
     * replanning then would throw away a persisted SMART queue that should resume exactly.
     */
    private var plannedWith: Int? = null

    suspend fun apply(level: Int) {
        val sanitized = sanitizeArtistVariety(level)
        setPenalty(ARTIST_VARIETY_PENALTIES[sanitized])
        val previous = plannedWith
        val replan = previous != null && previous != sanitized
        if (replan) delay(ARTIST_VARIETY_SETTLE_MS)
        // Recorded before the replan: if the next level cuts it short, part of the queue already follows this one.
        plannedWith = sanitized
        if (replan) invalidateSmartFuture()
    }
}

/** An externally resumed platform queue always wins over reloading and pausing the saved queue. */
internal fun shouldApplySavedPlaybackAfterPlatformSync(now: NowPlaying): Boolean =
    now.track == null

/** Resolves a canonical resume source without mistaking generated SMART rows for that source. */
internal fun resolveResumeSourceQueue(
    saved: ResumePlayback,
    library: List<TrackDescriptor>,
    playlists: List<Playlist>,
    playlistsAvailable: Boolean = true,
    /** A rebuilt playlist plays in the order its page shows, as it did when it was started. */
    playlistOrders: PlaylistSortOrders = PlaylistSortOrders(),
    /** Listens per track, for a playlist sorted by plays; see [playsForResume]. */
    plays: Map<TrackId, Int> = emptyMap(),
): List<TrackDescriptor>? {
    val byId = library.associateByIdValue()
    return when {
        saved.sourceQueuePersisted -> saved.sourceQueueTrackIds.mapNotNull(byId::get)
        saved.sourceKind == QueueSourceKind.TRACKS.name -> library
        saved.sourceKind == QueueSourceKind.COLLECTION.name &&
            saved.sourceReference != null && playlistsAvailable ->
            playlists.firstOrNull { it.id == saved.sourceReference }
                ?.let { playlist ->
                    playlist.tracksInChosenOrder(playlist.trackIds.mapNotNull(byId::get), playlistOrders, plays)
                }
                .orEmpty()
        else -> null
    }
}

/**
 * The play counts [resolveResumeSourceQueue] needs for [saved]: read only when it rebuilds a
 * playlist sorted by plays, since the history's stats copy every track.
 */
internal suspend fun playsForResume(saved: ResumePlayback, playlistOrders: PlaylistSortOrders): Map<TrackId, Int> {
    val rebuilt = saved.sourceReference
        ?.takeIf { !saved.sourceQueuePersisted && saved.sourceKind == QueueSourceKind.COLLECTION.name }
    return playsWhenSortedByPlays(playlistOrders, listOfNotNull(rebuilt))
}

/** Listens per track when one of [playlistIds] is sorted by plays, else nothing (no history read). */
internal suspend fun playsWhenSortedByPlays(
    orders: PlaylistSortOrders,
    playlistIds: Collection<String>,
): Map<TrackId, Int> =
    if (playlistIds.none { orders.forPlaylist(it).sort == PlaylistSort.PLAYS }) {
        emptyMap()
    } else {
        try {
            // What the pages call plays: a start skipped within 30 seconds is not one.
            AppGraph.history.stats().mapValues { it.value.listens }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Best-effort, as every other history read: an unreadable history leaves the stored order.
            emptyMap()
        }
    }

internal data class ResumeFallbackQueue(
    val liveQueue: List<TrackDescriptor>,
    val currentIndex: Int,
)

/**
 * Reconstructs a coherent live queue when an old or oversized session did not persist one.
 *
 * SMART must restart from the current seed alone so the chooser plans a real recommendation
 * future; treating the complete source as a saved SMART plan would play it linearly. ON creates a
 * fresh traversal with current first, while OFF restores canonical source order. The injectable
 * shuffler keeps this state transition deterministic in tests.
 */
internal fun fallbackResumeQueue(
    mode: ShuffleMode?,
    current: TrackDescriptor,
    source: List<TrackDescriptor>,
    shuffle: (List<TrackDescriptor>) -> List<TrackDescriptor> = { it.shuffled() },
): ResumeFallbackQueue {
    val distinctSource = source.distinctBy(TrackDescriptor::id)
    return when (mode) {
        ShuffleMode.SMART -> ResumeFallbackQueue(listOf(current), 0)
        ShuffleMode.ON -> ResumeFallbackQueue(
            liveQueue = listOf(current) + shuffle(distinctSource.filterNot { it.id == current.id }),
            currentIndex = 0,
        )
        ShuffleMode.OFF, null -> {
            val currentIndex = distinctSource.indexOfFirst { it.id == current.id }
            if (currentIndex >= 0) {
                ResumeFallbackQueue(distinctSource, currentIndex)
            } else {
                ResumeFallbackQueue(listOf(current) + distinctSource, 0)
            }
        }
    }
}

internal data class WorldTrackIdentity(
    val id: TrackId,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?,
    val originalYear: Int?,
    // Whether an album may name a region reads its credits (WorldNamingGroups.looksLikeAlbum).
    val albumArtist: String?,
    val artists: List<String>,
)

internal fun TrackDescriptor.worldIdentity() = WorldTrackIdentity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    genre = genre,
    year = year,
    originalYear = originalYear,
    albumArtist = albumArtist,
    artists = artists,
)

/** The exact surface that raised a track menu; hidden screens never lend it their actions. */
internal data class TrackMenuRequest(
    val track: TrackDescriptor,
    val sourcePlaylistId: String? = null,
    val sourcePlaylistTitle: String? = null,
    /** Raised from the full player: "Information" turns the cover over instead of opening a sheet. */
    val fromPlayer: Boolean = false,
)

/** Exit-frame styling retained without observable state: the live branch never reads it. */
internal class LastMiniPresentation(
    var track: TrackDescriptor?,
    var accent: TrackAccent,
    var isPlaying: Boolean,
)

/** Starts from the accent as it is now, without the caller observing it. */
internal fun lastMiniPresentation(
    track: TrackDescriptor?,
    accent: State<TrackAccent>,
    isPlaying: Boolean,
): LastMiniPresentation =
    LastMiniPresentation(track, Snapshot.withoutReadObservation { accent.value }, isPlaying)

/**
 * Keeps the accent's latest frame while a track is [live]. The accent animates over several
 * frames and is consulted only after the live track disappears, so it is followed here rather
 * than by the shell, which would otherwise recompose for every frame of the fade.
 */
internal suspend fun LastMiniPresentation.followAccent(live: () -> Boolean, accent: State<TrackAccent>) {
    snapshotFlow { if (live()) accent.value else null }.collect { frame -> if (frame != null) this.accent = frame }
}

// The state holders below keep groups of App's state in one remembered object each instead of one
// local per field. App's theme content compiles to a single method in which every value kept alive
// takes a register. At 261 registers (the 0.8.1 candidate fe24e12c) R8 9.2.14 wrote v260 into an
// 8-bit operand as v4, and Android's verifier rejected the class at every release launch.
// tools/release/check-dex-registers.py fails a release build that has a method over 255 registers.

/**
 * The worlds the library falls into and what they were last built from. The two readiness flags say which vector tiers have covered the library.
 */
@Stable
internal class LibraryWorldsState {
    var memoryLimited by mutableStateOf(false)
    /** The last attempt to discover this library's worlds failed; the Map says so instead of waiting. */
    var discoveryFailed by mutableStateOf(false)
    var worlds by mutableStateOf<List<LibraryWorld>>(emptyList())
    var worldLibraryIds by mutableStateOf<List<TrackId>>(emptyList())
    var builtWorldsKey by mutableStateOf<LibraryWorldsKey?>(null)
    var builtWorldNoveltyPreference by mutableStateOf<Boolean?>(null)
    var builtWorldReadiness by mutableStateOf<Pair<Boolean, Boolean>?>(null)
    var builtWorldDiscoveryKey by mutableStateOf<LibraryWorldDiscoveryKey?>(null)
    var builtDiscoveredWorlds by mutableStateOf<List<LibraryWorld>?>(null)

    var metadataVectorsReady by mutableStateOf(false)
    var audioVectorsReady by mutableStateOf(false)
}

/** The For You page as last built, the inputs it was built from, and what it has had on screen. */
@Stable
internal class ForYouPageState {
    var forYou by mutableStateOf(ForYouPage())

    // What For You has already had on screen today, one entry per (card, section, day) — the
    // impression's own identity. The page rebuilds on every history change and the tab is left
    // and re-entered, so without it the same visible card would be written again on each of
    // those. The day in the key is what lets tomorrow's offer of the same card through.
    var forYouCardsShown by mutableStateOf(emptySet<ForYouImpression>())
    var builtWorlds by mutableStateOf<List<LibraryWorld>?>(null)
    var builtForYouLibraryIds by mutableStateOf<List<TrackId>?>(null)
    var builtForYouPlaylistIdentity by mutableStateOf<List<PlaylistPresentationIdentity>?>(null)
    var builtForYouExclusions by mutableStateOf<SmartExclusionState?>(null)
    var builtIncludeNoveltyMixes by mutableStateOf<Boolean?>(null)
    var personalizationRevision by mutableStateOf(0)
    var builtPersonalizationRevision by mutableStateOf(-1)
    var builtHistoryRevision by mutableStateOf(-1L)

    // The local day and phase the page was built in: a new day or phase is a reason to rebuild,
    // applied the next time the page comes into view rather than under the reader.
    var builtDay by mutableStateOf(Long.MIN_VALUE)
    var builtDaypart by mutableStateOf<ForYouDaypart?>(null)

    // True once the page has been built (or confirmed current) since it last came into view. While
    // it stays true, a listen does not rebuild the page on screen; it is cleared whenever the page
    // leaves the screen, and an interrupted build leaves it false so the next run still rebuilds.
    var currentSinceShown by mutableStateOf(false)
    var forYouRefreshing by mutableStateOf(false)

    // A build is running: an empty page then means "not ready yet", never "no history".
    var forYouBuilding by mutableStateOf(false)

    // The last history read failed and no page exists to keep; the snackbar says why.
    var forYouLoadFailed by mutableStateOf(false)

    // Row, mix and collection taps: a new one cancels the previous one's pending continuation.
    val playbackLauncher = ForYouPlaybackLauncher()
}

/** The library as scanned and what Settings says about it. */
@Stable
internal class LibraryScanState {
    // The library as scanned, files under repair included: Settings names them from it.
    var scannedTracks by mutableStateOf<List<TrackDescriptor>>(emptyList())

    // The files under repair the published library was last filtered with.
    var shownRepairingKeys by mutableStateOf(emptySet<String>())
    var hasHiddenTracks by mutableStateOf(false)
    var libraryRefreshing by mutableStateOf(false)
}

/**
 * Where the root pager is going: the page set its position was last committed with, a page it was
 * sent to and has not settled on yet, and the navigation under way. [rootTabNavigationRevision]
 * moves with every navigation, so a snapshot taken before one can tell it is stale.
 */
@Stable
internal class RootTabNavigation(initialPages: List<StartPage>) {
    var appliedPages by mutableStateOf(initialPages)
    var pendingPage by mutableStateOf<StartPage?>(null)
    var rootTabNavigationRevision by mutableLongStateOf(0L)
    var rootTabNavigationJob by mutableStateOf<Job?>(null)
}

/** The playlists as stored, whether they have loaded, and the listening facts auto playlists derive from. */
@Stable
internal class PlaylistsState {
    var playlists by mutableStateOf<List<Playlist>>(emptyList())

    /**
     * Whether launch is done with the playlists: read, or failed to read and shown as none. The
     * saved player waits for this and only this, so a failed read never holds it back.
     */
    var playlistsLoaded by mutableStateOf(false)
        private set

    /** Whether a read of the store has succeeded, so [playlists] began as its complete list. */
    var playlistsAvailableForSourceRestore by mutableStateOf(false)
        private set
    var companionGroupsInitialized by mutableStateOf(false)
    var playCounts by mutableStateOf<Map<TrackId, Int>>(emptyMap())
    var lastPlayedAt by mutableStateOf<Map<TrackId, Long>>(emptyMap())

    /** Whether [playCounts] came from the history (it starts empty, before the first read). */
    var playCountsLoaded by mutableStateOf(false)
        private set
    private var favoritesFollowedWithCounts = false

    fun publishStats(plays: Map<TrackId, Int>, lastListenedAt: Map<TrackId, Long>) {
        // A first read with no listens equals the empty counts every page was sorted with so far, and
        // re-runs nothing that would record it.
        if (!playCountsLoaded && plays.isEmpty()) favoritesFollowedWithCounts = true
        playCounts = plays
        lastPlayedAt = lastListenedAt
        playCountsLoaded = true
    }

    /**
     * Whether an open Favorites page sorted by plays keeps its order on this refresh of the auto lists:
     * only when the previous refresh already had loaded counts, so the page was sorted with them. A page
     * opened before the first read was sorted with every count at 0, so it re-sorts once when they arrive.
     */
    suspend fun favoritesPlaysOrderSettled(): Boolean {
        val settled = favoritesFollowedWithCounts
        val loaded = playCountsLoaded
        // Counted only for a refresh that ran to its end: one cancelled by the next sorted nothing.
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause == null && loaded) favoritesFollowedWithCounts = true
        }
        return settled
    }

    var autoPlaylists by mutableStateOf<List<AutoPlaylist>>(emptyList())

    /**
     * [playlists] when it is the store's whole list, else null. What is kept per playlist (its sort
     * order) may be forgotten for a playlist missing from it, never from the empty list a failed read
     * leaves behind.
     */
    val completePlaylists: List<Playlist>?
        get() = playlists.takeIf { playlistsAvailableForSourceRestore }

    /** The store's whole list, read. */
    fun published(value: List<Playlist>) {
        playlists = value
        playlistsLoaded = true
        playlistsAvailableForSourceRestore = true
    }

    /** The launch read failed: no playlists to show, and none known to the store. */
    fun launchReadFailed() {
        playlistsLoaded = true
        playlistsAvailableForSourceRestore = false
    }
}

/** The Map page as shown, the regions behind it, and the track a "Show on the Map" carries to it. */
@Stable
internal class MapPageModel {
    var mapState by mutableStateOf<MapPageState>(MapPageState.Indexing)

    // "Show on the Map" carries a track from any list to its dot; cleared by the next request.
    var mapFocusTrackId by mutableStateOf<TrackId?>(null)

    // The regions actually behind the currently shown MapPage. Not always `worlds` itself --
    // spec section 8's smallest-library fallback (mapFallbackRegions) can stand in for it -- so
    // the "Play region"/"SMART from here" callbacks read this, not `worlds` directly, or they
    // would silently no-op whenever the Map is showing that fallback.
    var mapRegions by mutableStateOf<List<LibraryWorld>>(emptyList())
}

/**
 * The sheets and dialogs raised over the shell, and the requests the full player takes. None of
 * them survives recreation; the saveable ones (tag editors, playlist naming) stay in App.
 */
@Stable
internal class ShellSheets {
    var addToPlaylistSelection by mutableStateOf<List<TrackDescriptor>?>(null)
    var artistChoices by mutableStateOf<List<ArtistGroup>>(emptyList())
    var playerDetailsRequest by mutableStateOf(0)
    var playerSleepTimerRequest by mutableStateOf(0)
    var playlistMutationInProgress by mutableStateOf(false)
    var playlistMutationFailed by mutableStateOf(false)
    var trackMenuRequest by mutableStateOf<TrackMenuRequest?>(null)
    var deleteTarget by mutableStateOf<TrackDescriptor?>(null)
    var deleteSelection by mutableStateOf<List<TrackDescriptor>?>(null)
    var showSelectionRemoval by mutableStateOf(false)
    var worldTarget by mutableStateOf<ForYouCard?>(null)

    // A playlist file read and matched, waiting for the listener to choose where it goes.
    var m3uImportOffer by mutableStateOf<M3uImportOffer?>(null)
}

/** Map positions are a derived cache: storage trouble must never make the app or Map unusable. */
internal suspend fun IndexStore.loadMapLayoutOrEmpty(): StoredLibraryLayout = try {
    loadStoredLayout()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    println("SMART: could not load the cached map layout: $failure")
    StoredLibraryLayout(positions = emptyMap(), fingerprint = null)
}

/** Returns after a best-effort cache write; the freshly computed in-memory layout remains valid. */
internal suspend fun IndexStore.saveMapLayoutBestEffort(
    points: List<LayoutPoint>,
    fingerprint: Long,
) {
    try {
        saveLayout(points, fingerprint)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        println("SMART: could not save the derived map layout: $failure")
    }
}

