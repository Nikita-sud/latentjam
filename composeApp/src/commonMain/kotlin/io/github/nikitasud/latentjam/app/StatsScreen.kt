/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.smart.associateById
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_retry
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.privacy_history_listens
import io.github.nikitasud.latentjam.app.generated.resources.stats_activity
import io.github.nikitasud.latentjam.app.generated.resources.stats_activity_last_months
import io.github.nikitasud.latentjam.app.generated.resources.stats_activity_over_time
import io.github.nikitasud.latentjam.app.generated.resources.stats_activity_years
import io.github.nikitasud.latentjam.app.generated.resources.stats_afternoon
import io.github.nikitasud.latentjam.app.generated.resources.stats_artists_on_repeat
import io.github.nikitasud.latentjam.app.generated.resources.stats_calendar_days
import io.github.nikitasud.latentjam.app.generated.resources.stats_chart_day_summary
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_first
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_less
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_more
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_same
import io.github.nikitasud.latentjam.app.generated.resources.stats_days_ago
import io.github.nikitasud.latentjam.app.generated.resources.stats_days_of_music
import io.github.nikitasud.latentjam.app.generated.resources.stats_days_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_daypart_peak
import io.github.nikitasud.latentjam.app.generated.resources.stats_decade
import io.github.nikitasud.latentjam.app.generated.resources.stats_decades
import io.github.nikitasud.latentjam.app.generated.resources.stats_distinct_tracks
import io.github.nikitasud.latentjam.app.generated.resources.stats_empty
import io.github.nikitasud.latentjam.app.generated.resources.stats_evening
import io.github.nikitasud.latentjam.app.generated.resources.stats_familiar
import io.github.nikitasud.latentjam.app.generated.resources.stats_finish_note
import io.github.nikitasud.latentjam.app.generated.resources.stats_finished_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_history_note
import io.github.nikitasud.latentjam.app.generated.resources.stats_hours_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_library
import io.github.nikitasud.latentjam.app.generated.resources.stats_listening_style
import io.github.nikitasud.latentjam.app.generated.resources.stats_load_failed
import io.github.nikitasud.latentjam.app.generated.resources.stats_locked_plays
import io.github.nikitasud.latentjam.app.generated.resources.stats_locked_sessions
import io.github.nikitasud.latentjam.app.generated.resources.stats_log_age
import io.github.nikitasud.latentjam.app.generated.resources.stats_longest_session
import io.github.nikitasud.latentjam.app.generated.resources.stats_minutes_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_months_ago
import io.github.nikitasud.latentjam.app.generated.resources.stats_more_appears
import io.github.nikitasud.latentjam.app.generated.resources.stats_morning
import io.github.nikitasud.latentjam.app.generated.resources.stats_new_and_familiar
import io.github.nikitasud.latentjam.app.generated.resources.stats_new_artists_count
import io.github.nikitasud.latentjam.app.generated.resources.stats_new_note
import io.github.nikitasud.latentjam.app.generated.resources.stats_new_previous
import io.github.nikitasud.latentjam.app.generated.resources.stats_new_tracks
import io.github.nikitasud.latentjam.app.generated.resources.stats_new_tracks_count
import io.github.nikitasud.latentjam.app.generated.resources.stats_night
import io.github.nikitasud.latentjam.app.generated.resources.stats_of_100_picks
import io.github.nikitasud.latentjam.app.generated.resources.stats_on_days
import io.github.nikitasud.latentjam.app.generated.resources.stats_on_repeat
import io.github.nikitasud.latentjam.app.generated.resources.stats_part_failed
import io.github.nikitasud.latentjam.app.generated.resources.stats_partly_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_patterns
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_all
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_month
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_week
import io.github.nikitasud.latentjam.app.generated.resources.stats_picked_next
import io.github.nikitasud.latentjam.app.generated.resources.stats_played_once
import io.github.nikitasud.latentjam.app.generated.resources.stats_plays
import io.github.nikitasud.latentjam.app.generated.resources.stats_recent_empty
import io.github.nikitasud.latentjam.app.generated.resources.stats_recording_off
import io.github.nikitasud.latentjam.app.generated.resources.stats_same_artist_note
import io.github.nikitasud.latentjam.app.generated.resources.stats_same_artist_picks
import io.github.nikitasud.latentjam.app.generated.resources.stats_section_lately
import io.github.nikitasud.latentjam.app.generated.resources.stats_section_numbers
import io.github.nikitasud.latentjam.app.generated.resources.stats_section_over_time
import io.github.nikitasud.latentjam.app.generated.resources.stats_sequence_description
import io.github.nikitasud.latentjam.app.generated.resources.stats_show_less
import io.github.nikitasud.latentjam.app.generated.resources.stats_show_more
import io.github.nikitasud.latentjam.app.generated.resources.stats_skipped_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_slipping_away
import io.github.nikitasud.latentjam.app.generated.resources.stats_slipping_note
import io.github.nikitasud.latentjam.app.generated.resources.stats_smart_detail
import io.github.nikitasud.latentjam.app.generated.resources.stats_smart_mode_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_stuck_artists
import io.github.nikitasud.latentjam.app.generated.resources.stats_stuck_artists_recent
import io.github.nikitasud.latentjam.app.generated.resources.stats_streak_current
import io.github.nikitasud.latentjam.app.generated.resources.stats_streak_longest
import io.github.nikitasud.latentjam.app.generated.resources.stats_this_month
import io.github.nikitasud.latentjam.app.generated.resources.stats_this_year
import io.github.nikitasud.latentjam.app.generated.resources.stats_time_listened
import io.github.nikitasud.latentjam.app.generated.resources.stats_today
import io.github.nikitasud.latentjam.app.generated.resources.stats_typical_session
import io.github.nikitasud.latentjam.app.generated.resources.stats_under_minute
import io.github.nikitasud.latentjam.app.generated.resources.stats_unlock_on_repeat
import io.github.nikitasud.latentjam.app.generated.resources.stats_unlock_slipping
import io.github.nikitasud.latentjam.app.generated.resources.stats_week_days
import io.github.nikitasud.latentjam.app.generated.resources.stats_when_you_listen
import io.github.nikitasud.latentjam.app.generated.resources.stats_x_of_y
import io.github.nikitasud.latentjam.app.generated.resources.stats_year_ago
import io.github.nikitasud.latentjam.app.generated.resources.stats_years_ago
import io.github.nikitasud.latentjam.app.generated.resources.stats_yesterday
import io.github.nikitasud.latentjam.app.generated.resources.stats_your_top
import io.github.nikitasud.latentjam.app.generated.resources.tab_albums
import io.github.nikitasud.latentjam.app.generated.resources.tab_artists
import io.github.nikitasud.latentjam.app.generated.resources.tab_genres
import io.github.nikitasud.latentjam.app.generated.resources.tab_playlists
import io.github.nikitasud.latentjam.app.generated.resources.stats_playlist_row
import io.github.nikitasud.latentjam.app.generated.resources.stats_playlists_note
import io.github.nikitasud.latentjam.app.generated.resources.tab_tracks
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.history.ActivityScale
import io.github.nikitasud.latentjam.history.HabitInsights
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.ListeningInsights
import io.github.nikitasud.latentjam.history.ListeningNormalization
import io.github.nikitasud.latentjam.history.ListeningOverview
import io.github.nikitasud.latentjam.history.ListeningOverviews
import io.github.nikitasud.latentjam.history.NormalizedHistory
import io.github.nikitasud.latentjam.history.PeriodInsights
import io.github.nikitasud.latentjam.history.RankedName
import io.github.nikitasud.latentjam.history.RepeatPlaylist
import io.github.nikitasud.latentjam.history.RepeatTrack
import io.github.nikitasud.latentjam.history.StatsPlaylist
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.history.localTimePoint
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal enum class StatsPeriod(val days: Int?) { WEEK(7), MONTH(30), ALL(null) }

private enum class TopKind { ARTISTS, ALBUMS, GENRES, PLAYLISTS, TRACKS }

/** How the page names and pictures a playlist the log attributes plays to. */
internal class PlaylistLabel(val name: String?, val autoKind: AutoPlaylistKind?, val artworkUri: String?)

/** The listener's playlists as the log can attribute plays to them, with their labels. */
private suspend fun loadStatsPlaylists(
    tracksById: Map<TrackId, TrackDescriptor>,
): Pair<List<StatsPlaylist>, Map<String, PlaylistLabel>> {
    val attributable = ArrayList<StatsPlaylist>()
    val labels = HashMap<String, PlaylistLabel>()
    AppGraph.playlists.all().forEach { playlist ->
        val id = "playlist:${playlist.id}"
        val members = playlist.trackIds.map(::TrackId)
        attributable += StatsPlaylist(id, members.toSet())
        labels[id] = PlaylistLabel(
            name = playlist.name,
            autoKind = null,
            artworkUri = playlistCoverUri(playlist.customArtworkRef)
                ?: members.firstNotNullOfOrNull { tracksById[it]?.artworkUri },
        )
    }
    val favorites = AppGraph.favorites.all()
    AutoPlaylistKind.entries.forEach { kind ->
        val id = "auto:${kind.name}"
        // Favorites are a stable set; the other auto playlists change with every listen.
        attributable += StatsPlaylist(id, if (kind == AutoPlaylistKind.FAVORITES) favorites.toSet() else null)
        labels[id] = PlaylistLabel(
            name = null,
            autoKind = kind,
            artworkUri = if (kind == AutoPlaylistKind.FAVORITES) {
                favorites.firstNotNullOfOrNull { tracksById[it]?.artworkUri }
            } else {
                null
            },
        )
    }
    return attributable to labels
}

@Composable
private fun PlaylistLabel?.title(): String = when {
    this == null -> stringResource(Res.string.tab_playlists)
    autoKind != null -> stringResource(autoKind.titleRes())
    else -> name.orEmpty()
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Section 2: the chips' period. [selection] identifies the chip/source it was computed for. */
internal class PeriodLoad(
    val selection: Any,
    val overview: ListeningOverview,
    val insights: PeriodInsights,
    val logEmpty: Boolean,
) {
    /** The same numbers, shown for this composition's [selection]. */
    fun shownFor(selection: Any): PeriodLoad = PeriodLoad(selection, overview, insights, logEmpty)
}

/**
 * What the Statistics page last computed, kept above the page. The root pager composes only the
 * page on screen, so the page is disposed whenever it is swiped away; without this every return
 * read and summarised the whole log again behind a progress bar. A result is reused only for the
 * inputs it was computed from, so new listens, a new day, a retry, edited playlists or edited
 * favourites still recompute it. One memory serves one library: whoever keeps it starts a new one
 * when the library changes, so it never holds a superseded library alive. It keeps results and
 * labels, never the library's descriptors. Main-thread only, like the page itself.
 */
internal class StatsMemory {
    /**
     * Playlists and favourites compare by identity: each edit publishes a new list. A retry needs no
     * place here: only successful results are kept.
     */
    class Inputs(
        val historyRevision: Long,
        val localDay: Long,
        val playlists: Any?,
        val favorites: Any?,
    ) {
        override fun equals(other: Any?): Boolean = other is Inputs &&
            historyRevision == other.historyRevision && localDay == other.localDay &&
            playlists === other.playlists && favorites === other.favorites

        override fun hashCode(): Int = historyRevision.hashCode() * 31 + localDay.hashCode()
    }

    val periods = HashMap<StatsPeriod, Pair<Inputs, PeriodLoad>>()
    var habits: Pair<Inputs, HabitInsights>? = null
    var playlistLabels: Map<String, PlaylistLabel> = emptyMap()
}

/** A shared dashboard for Settings and the optional root page; never reads playback ticks. */
@Composable
internal fun ListeningStatsSettings(
    history: ListeningHistory,
    tracks: List<TrackDescriptor>,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    active: Boolean = true,
    accent: TrackAccent? = null,
    /**
     * Results that outlive this composition, for [tracks] only; the root page passes one kept above
     * the pager.
     */
    memory: StatsMemory? = null,
    /** The playlists as published: a rename or a new playlist recomputes what names them. */
    playlistsKey: Any? = null,
    /** The favourites as published: the Favorites playlist's plays follow its membership. */
    favoritesKey: Any? = null,
    /** [tracks] by id when the caller already holds that map, so returning rows are never blank. */
    knownTracksById: Map<TrackId, TrackDescriptor>? = null,
) {
    val memo = memory ?: remember(tracks) { StatsMemory() }
    val accentInk = accent?.let { browseAccentInk(it) } ?: MaterialTheme.colorScheme.primary
    var periodName by rememberSaveable { mutableStateOf(StatsPeriod.MONTH.name) }
    val period = StatsPeriod.entries.firstOrNull { it.name == periodName } ?: StatsPeriod.MONTH
    val historyRevision by AppGraph.historyRevision.collectAsState()
    val recordingHistory by AppGraph.settings.saveListeningHistory.collectAsState()
    val scope = rememberCoroutineScope()
    val snapshots = remember(history, scope) { StatsSnapshots(scope, readEvents = history::allEvents) }
    // Shared reads run under their own supervisor, so a failure cannot cancel the screen's scope.
    val supervisor = remember(scope) { SupervisorJob(scope.coroutineContext[Job]) }
    // The caller's own map when it has one: a second map of the whole library is a cost of its own.
    val catalog: Deferred<Map<TrackId, TrackDescriptor>> = remember(tracks, scope, knownTracksById) {
        knownTracksById?.let { CompletableDeferred(it) }
            ?: scope.async(supervisor + Dispatchers.Default, start = CoroutineStart.LAZY) { tracks.associateById() }
    }
    var retryRevision by remember { mutableIntStateOf(0) }
    // Both sections attribute plays to the same playlists: read them once per revision, off the main
    // thread, and again whenever the caller publishes its playlists or favourites anew. Null when
    // the read failed.
    val playlistsLoad = remember(scope, historyRevision, tracks, retryRevision, playlistsKey, favoritesKey) {
        scope.async(supervisor + Dispatchers.Default, start = CoroutineStart.LAZY) {
            try {
                loadStatsPlaylists(catalog.await())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
    }
    // "Today" rolls over at local midnight while the page stays open.
    val localDay by produceState(localTimePoint(epochMillis()).epochDay) {
        while (true) {
            delay(msUntilNextLocalDay(epochMillis()))
            value = localTimePoint(epochMillis()).epochDay
        }
    }
    val selection = remember(history, period, tracks) { Any() }
    // A return to the page shows what it last showed at once; the effects below refresh it only
    // when what it was computed from has changed.
    var periodLoad by remember { mutableStateOf(memo.periods[period]?.second?.shownFor(selection)) }
    var habitsLoad by remember { mutableStateOf(memo.habits?.second) }
    var periodFailed by remember { mutableStateOf(false) }
    var habitsFailed by remember { mutableStateOf(false) }
    // Built once off the main thread; rows read it after the first load.
    var tracksById by remember(tracks) { mutableStateOf(knownTracksById ?: emptyMap()) }
    var playlistLabels by remember { mutableStateOf(memo.playlistLabels) }
    fun inputs() = StatsMemory.Inputs(historyRevision, localDay, playlistsKey, favoritesKey)
    fun keep(catalogMap: Map<TrackId, TrackDescriptor>, labels: Map<String, PlaylistLabel>, read: Boolean) {
        tracksById = catalogMap
        playlistLabels = labels
        if (read) memo.playlistLabels = labels
    }

    /** Rows of a kept result need the tracks; a map that cannot be built fails like a load. */
    suspend fun ensureTracks(): Boolean {
        if (tracksById.isNotEmpty()) return true
        return try {
            tracksById = catalog.await()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    LaunchedEffect(active) {
        if (!active) snapshots.cancelInFlight()
    }
    // Section 2 follows the chips. New listens refresh it in place; a chip change keeps the old
    // cards dimmed until the new period is ready, so neither the layout nor the scroll jumps.
    LaunchedEffect(selection, historyRevision, retryRevision, active, localDay, playlistsKey, favoritesKey) {
        if (!active) return@LaunchedEffect
        val inputs = inputs()
        memo.periods[period]?.takeIf { it.first == inputs }?.let { (_, kept) ->
            periodLoad = kept.shownFor(selection)
            periodFailed = !ensureTracks()
            return@LaunchedEffect
        }
        try {
            val normalized = snapshots.await(historyRevision, retryRevision)
            val catalogMap = catalog.await()
            // Playlists only refine the lists; without them the page still tells the truth, but such
            // a result is not kept, so the next visit reads the playlists again.
            val playlists = playlistsLoad.await()
            val (statsPlaylists, labels) = playlists ?: (emptyList<StatsPlaylist>() to emptyMap())
            keep(catalogMap, labels, read = playlists != null)
            val loadContext = currentCoroutineContext()
            val now = epochMillis()
            periodLoad = withContext(Dispatchers.Default) {
                val since = period.days?.let { now - it * DAY_MS }
                PeriodLoad(
                    selection = selection,
                    overview = ListeningOverviews.summarize(
                        events = normalized.events,
                        artistOf = { catalogMap[it]?.artist },
                        sinceMs = since,
                        nowMs = now,
                        includeTrack = catalogMap::containsKey,
                        cancellationCheck = { loadContext.ensureActive() },
                        // All time on a log younger than two months shows its whole span by day.
                        chartDays = period.days ?: normalized.logStartMs?.let {
                            (localTimePoint(now).epochDay - localTimePoint(it).epochDay + 1).toInt().coerceIn(1, 59)
                        } ?: 30,
                    ),
                    insights = ListeningInsights.period(
                        history = normalized,
                        catalog = catalogMap::get,
                        sinceMs = since,
                        nowMs = now,
                        cancellationCheck = { loadContext.ensureActive() },
                        playlists = statsPlaylists,
                        // As the Genres tab counts them: "Rock; Pop" is Rock.
                        genreOf = { GenreTags.split(it.genre).firstOrNull() },
                    ),
                    logEmpty = normalized.size == 0,
                )
            }.also { if (playlists != null) memo.periods[period] = inputs to it }
            periodFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            periodFailed = true
        }
    }
    // Sections 1 and 3 describe the whole log as of today; the chips never touch them.
    LaunchedEffect(historyRevision, tracks, active, localDay, retryRevision, playlistsKey, favoritesKey) {
        if (!active) return@LaunchedEffect
        val inputs = inputs()
        memo.habits?.takeIf { it.first == inputs }?.let { (_, kept) ->
            habitsLoad = kept
            habitsFailed = !ensureTracks()
            return@LaunchedEffect
        }
        try {
            val normalized = snapshots.await(historyRevision, retryRevision)
            val catalogMap = catalog.await()
            // Playlists only refine the lists; without them the page still tells the truth, but such
            // a result is not kept, so the next visit reads the playlists again.
            val playlists = playlistsLoad.await()
            val (statsPlaylists, labels) = playlists ?: (emptyList<StatsPlaylist>() to emptyMap())
            keep(catalogMap, labels, read = playlists != null)
            val loadContext = currentCoroutineContext()
            habitsLoad = withContext(Dispatchers.Default) {
                ListeningInsights.habits(
                    history = normalized,
                    catalog = catalogMap::get,
                    librarySize = catalogMap.size,
                    nowMs = epochMillis(),
                    cancellationCheck = { loadContext.ensureActive() },
                    playlists = statsPlaylists,
                )
            }.also { if (playlists != null) memo.habits = inputs to it }
            habitsFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            habitsFailed = true
        }
    }

    val currentTracks by rememberUpdatedState(tracksById)
    val playRows: (List<TrackId>, Int) -> Unit = remember(scope) {
        { ids, index ->
            val rows = ids.mapNotNull { currentTracks[it] }
            val start = rows.indexOfFirst { it.id == ids.getOrNull(index) }
            if (start >= 0) {
                // A hand-picked set from this page has no single honest source to name.
                AppGraph.queueSource.value = null
                PersonalSignals.pick(PickSurface.COLLECTION)
                scope.launch { AppGraph.playback.play(rows, start) }
            }
        }
    }
    val periodShown = periodLoad
    val dimmed = periodShown != null && periodShown.selection !== selection
    val habits = habitsLoad

    FadingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!recordingHistory) {
            item(key = "recording-paused") {
                StatsCard { StatsNote(stringResource(Res.string.stats_recording_off)) }
            }
        }
        if (periodShown?.logEmpty == true && habits?.logStartMs == null && !dimmed) {
            item(key = "empty") { EmptyLogCard() }
            return@FadingLazyColumn
        }

        // 1 · You lately. Its heading is always the first item: when this section arrives after the
        // numbers below, the list keeps its first item in place, so the page still opens at the top.
        item(key = "lately-title") { StatsSectionHeader(stringResource(Res.string.stats_section_lately)) }
        if (habits != null) {
            val repeatShown = habits.onRepeat.isNotEmpty() || habits.onRepeatArtists.isNotEmpty()
            if (!repeatShown && habits.onRepeatLocked) {
                item(key = "more-appears") { MoreAppearsCard(habits) }
            }
            // A log of skips only has nothing to repeat and no record to state.
            if (repeatShown || habits.longestStreakDays > 0) {
                item(key = "on-repeat") {
                    OnRepeatCard(habits, tracksById, playlistLabels, accentInk, playRows)
                }
            }
        }

        // 2 · Your numbers
        item(key = "numbers-title") {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsSectionHeader(stringResource(Res.string.stats_section_numbers), padded = false)
                // The chips govern only this section; they are its header, not a page filter.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatsPeriod.entries.forEach { candidate ->
                        AppChoiceChip(
                            selected = candidate == period,
                            onClick = { periodName = candidate.name },
                            label = stringResource(candidate.titleRes()),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (dimmed || (periodShown == null && !periodFailed)) {
                        AppLinearProgress(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        // A failed chip change must not leave the old period's numbers under the new chip.
        if (periodShown != null && !(dimmed && periodFailed)) {
            val overview = periodShown.overview
            val insights = periodShown.insights
            val cardModifier = if (dimmed) {
                Modifier.alpha(0.4f).clearAndSetSemantics { }
            } else {
                Modifier
            }
            if (overview.plays == 0) {
                item(key = "n-empty") {
                    Box(cardModifier) {
                        StatsCard { StatsNote(stringResource(Res.string.stats_empty)) }
                    }
                }
            } else {
                item(key = "n0") { Box(cardModifier) { ListeningHero(overview, insights, period) } }
                item(key = "n1") { Box(cardModifier) { ActivityCard(overview, insights, accentInk) } }
                item(key = "n2") { Box(cardModifier) { NewAndFamiliarCard(overview, insights, period, accentInk) } }
                item(key = "n3") { Box(cardModifier) { WhenYouListenCard(insights, accentInk) } }
                item(key = "n4") {
                    Box(cardModifier) { YourTopCard(overview, insights, tracksById, playlistLabels, playRows) }
                }
                item(key = "n5") { Box(cardModifier) { HowYouListenCard(overview, accentInk) } }
            }
        }

        // 3 · Over time
        if (habits != null) {
            item(key = "over-time-title") { StatsSectionHeader(stringResource(Res.string.stats_section_over_time)) }
            if (habits.slippingAway.isNotEmpty()) {
                item(key = "t1") { SlippingAwayCard(habits, tracksById, playRows) }
            }
            if (habits.yearAgo.isNotEmpty()) {
                item(key = "t2") { YearAgoCard(habits, tracksById, playRows) }
            }
            if (habits.sameArtistShown || habits.sequences.isNotEmpty()) {
                item(key = "t3") { PatternsCard(habits, tracksById, accentInk) }
            }
            if (habits.librarySize > 0) {
                item(key = "t4") { LibraryCard(habits, accentInk) }
            }
            val repeatShown = habits.onRepeat.isNotEmpty() || habits.onRepeatArtists.isNotEmpty()
            if ((repeatShown || !habits.onRepeatLocked) && habits.slippingAwayLocked) {
                item(key = "t5") { MoreAppearsCard(habits) }
            }
        }

        if (periodFailed || habitsFailed) {
            item(key = "failed") {
                StatsCard {
                    Text(
                        text = stringResource(
                            if (periodFailed && habitsFailed) Res.string.stats_load_failed else Res.string.stats_part_failed,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // One retry re-reads the log and restarts both sections.
                    OutlinedButton(
                        onClick = { retryRevision += 1 },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }
        }
        item(key = "history-note") {
            StatsNote(
                stringResource(Res.string.stats_history_note),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

private fun StatsPeriod.titleRes() = when (this) {
    StatsPeriod.WEEK -> Res.string.stats_period_week
    StatsPeriod.MONTH -> Res.string.stats_period_month
    StatsPeriod.ALL -> Res.string.stats_period_all
}

@Composable
private fun EmptyLogCard() {
    StatsCard {
        Icon(
            imageVector = Icons.Rounded.BarChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(text = stringResource(Res.string.stats_empty), style = MaterialTheme.typography.bodyLarge)
    }
}

// ---- Section 1 ----------------------------------------------------------------------------------

@Composable
private fun OnRepeatCard(
    habits: HabitInsights,
    tracksById: Map<TrackId, TrackDescriptor>,
    playlistLabels: Map<String, PlaylistLabel>,
    accentInk: Color,
    playRows: (List<TrackId>, Int) -> Unit,
) {
    StatsCard {
        if (habits.onRepeat.isNotEmpty()) {
            StatsTitle(stringResource(Res.string.stats_on_repeat))
            val top = habits.onRepeat.first().weight
            val ids = habits.onRepeat.filterIsInstance<RepeatTrack>().map { it.trackId }
            habits.onRepeat.forEach { entry ->
                val detail = pluralStringResource(Res.plurals.stats_on_days, entry.days, entry.days) + " · " +
                    pluralStringResource(Res.plurals.privacy_history_listens, entry.plays, entry.plays)
                when (entry) {
                    is RepeatTrack -> {
                        val descriptor = tracksById[entry.trackId] ?: return@forEach
                        TrackRow(
                            descriptor = descriptor,
                            detail = detail,
                            share = entry.weight / top,
                            accentInk = accentInk,
                            onClick = { playRows(ids, ids.indexOf(entry.trackId)) },
                        )
                    }
                    // A playlist opens its page everywhere else, so here it is not a play button.
                    is RepeatPlaylist -> {
                        val label = playlistLabels[entry.playlistId]
                        PlaylistRow(
                            title = label.title(),
                            artworkUri = label?.artworkUri,
                            detail = detail,
                            share = entry.weight / top,
                            accentInk = accentInk,
                        )
                    }
                }
            }
        } else if (habits.onRepeatArtists.isNotEmpty()) {
            StatsTitle(stringResource(Res.string.stats_artists_on_repeat))
            val top = habits.onRepeatArtists.first().weight
            habits.onRepeatArtists.forEach { entry ->
                NameRow(
                    title = entry.artist.ifBlank { stringResource(Res.string.track_unknown_artist) },
                    detail = pluralStringResource(Res.plurals.stats_on_days, entry.days, entry.days) + " · " +
                        pluralStringResource(Res.plurals.privacy_history_listens, entry.plays, entry.plays),
                    share = entry.weight / top,
                    accentInk = accentInk,
                )
            }
        }
        StreakLine(habits)
    }
}

@Composable
private fun PlaylistRow(
    title: String,
    artworkUri: String?,
    detail: String,
    share: Float,
    accentInk: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(uri = artworkUri, size = 40.dp, cornerRadius = 12.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.metadata(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.stats_playlist_row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProportionBar(share, color = accentInk.copy(alpha = 0.62f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun StreakLine(habits: HabitInsights) {
    val days = stringResource(Res.string.stats_days_short)
    val record = "${stringResource(Res.string.stats_streak_longest)} · ${habits.longestStreakDays} $days"
    val text = when {
        habits.currentStreakDays >= 2 ->
            "${stringResource(Res.string.stats_streak_current)} · ${habits.currentStreakDays} $days   $record"
        // Weekday listeners break a daily streak every weekend; say how regular they are instead.
        habits.playDaysLastWeek > 0 -> "${stringResource(Res.string.stats_week_days, habits.playDaysLastWeek)}   $record"
        else -> record
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MoreAppearsCard(habits: HabitInsights) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_more_appears))
        val repeatShown = habits.onRepeat.isNotEmpty() || habits.onRepeatArtists.isNotEmpty()
        if (!repeatShown && habits.onRepeatLocked) StatsNote(stringResource(Res.string.stats_unlock_on_repeat))
        if (habits.slippingAwayLocked) StatsNote(stringResource(Res.string.stats_unlock_slipping))
    }
}

// ---- Section 2 ----------------------------------------------------------------------------------

@Composable
private fun ListeningHero(overview: ListeningOverview, insights: PeriodInsights, period: StatsPeriod) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text = (stringResource(Res.string.stats_time_listened) + " · " +
                stringResource(period.titleRes())).uppercaseForDisplay(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatListenedTime(overview.playedMs),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 52.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
        val previous = overview.previousPeriod
        val comparison = when {
            period.days == null -> {
                val days = (overview.playedMs / DAY_MS).toInt()
                if (days >= 1) pluralStringResource(Res.plurals.stats_days_of_music, days, days) else null
            }
            previous == null -> null
            // A log younger than the previous window has no honest comparison; the New and familiar
            // card states the log's age instead.
            !insights.burnedIn -> null
            previous.playedMs == 0L -> stringResource(Res.string.stats_comparison_first, period.days)
            overview.playedMs > previous.playedMs -> stringResource(
                Res.string.stats_comparison_more,
                formatListenedTime(overview.playedMs - previous.playedMs),
                period.days,
            )
            overview.playedMs < previous.playedMs -> stringResource(
                Res.string.stats_comparison_less,
                formatListenedTime(previous.playedMs - overview.playedMs),
                period.days,
            )
            else -> stringResource(Res.string.stats_comparison_same, period.days)
        }
        if (comparison != null) {
            Text(
                text = comparison,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 3,
        ) {
            HeroMetric(formatCount(overview.plays), stringResource(Res.string.stats_plays), Modifier.weight(1f))
            HeroMetric(formatCount(insights.playedTracks), stringResource(Res.string.stats_distinct_tracks), Modifier.weight(1f))
            HeroMetric(formatCount(insights.playedArtists), stringResource(Res.string.tab_artists), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String, modifier: Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 88.dp),
        shape = RoundedCornerShape(16.dp.corner()),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Metric(value, label, Modifier.padding(14.dp), large = true)
    }
}

/** One chart bar, whatever its calendar unit. */
private class ChartBar(val key: Long, val plays: Int, val playedMs: Long, val label: @Composable () -> String)

@Composable
private fun ActivityCard(overview: ListeningOverview, insights: PeriodInsights, accentInk: Color) {
    StatsCard {
        when (insights.activityScale) {
            ActivityScale.DAY -> {
                val logStartDay = insights.logStartDay
                // Days before the log began are not quiet days; they are not drawn.
                val days = overview.dailyListening.filter { logStartDay == null || it.epochDay >= logStartDay }
                    .ifEmpty { overview.dailyListening.takeLast(1) }
                val today = overview.dailyListening.last().epochDay
                StatsTitle(stringResource(Res.string.stats_activity))
                StatsNote(stringResource(Res.string.stats_calendar_days, days.size))
                ActivityBars(
                    bars = days.map { day ->
                        ChartBar(day.epochDay, day.plays, day.playedMs) { statsDayLabel((today - day.epochDay).toInt()) }
                    },
                    accentInk = accentInk,
                )
                if (days.none { it.plays > 0 }) StatsNote(stringResource(Res.string.stats_recent_empty))
            }
            ActivityScale.MONTH, ActivityScale.YEAR -> {
                val months = insights.activityScale == ActivityScale.MONTH
                StatsTitle(stringResource(Res.string.stats_activity_over_time))
                StatsNote(
                    // A log under a year old shows fewer than twelve months: say how many it shows.
                    if (months) {
                        pluralStringResource(
                            Res.plurals.stats_activity_last_months,
                            insights.activity.size,
                            insights.activity.size,
                        )
                    } else {
                        stringResource(Res.string.stats_activity_years)
                    },
                )
                ActivityBars(
                    bars = insights.activity.map { bucket ->
                        ChartBar(bucket.key.toLong(), bucket.plays, bucket.playedMs) {
                            when {
                                bucket.ago == 0 && months -> stringResource(Res.string.stats_this_month)
                                bucket.ago == 0 -> stringResource(Res.string.stats_this_year)
                                months -> pluralStringResource(Res.plurals.stats_months_ago, bucket.ago, bucket.ago)
                                else -> pluralStringResource(Res.plurals.stats_years_ago, bucket.ago, bucket.ago)
                            }
                        }
                    },
                    accentInk = accentInk,
                )
            }
        }
    }
}

@Composable
private fun ActivityBars(bars: List<ChartBar>, accentInk: Color) {
    if (bars.isEmpty()) return
    val maxDuration = bars.maxOf { it.playedMs }.coerceAtLeast(1L)
    var selectedKey by remember(bars.first().key, bars.size) { mutableStateOf<Long?>(null) }
    val selectedIndex = bars.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: bars.lastIndex
    val selectedBar = bars[selectedIndex]
    val selectedSummary = stringResource(
        Res.string.stats_chart_day_summary,
        selectedBar.label(),
        pluralStringResource(Res.plurals.privacy_history_listens, selectedBar.plays, selectedBar.plays),
        formatListenedTime(selectedBar.playedMs),
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(92.dp).semantics {
            stateDescription = selectedSummary
            progressBarRangeInfo = ProgressBarRangeInfo(
                selectedIndex.toFloat(), 0f..bars.lastIndex.toFloat().coerceAtLeast(1f), (bars.size - 2).coerceAtLeast(0),
            )
            setProgress { index ->
                // The framework's target is arbitrary here: roundToInt() throws on NaN.
                if (!index.isFinite()) return@setProgress false
                selectedKey = bars[index.roundToInt().coerceIn(0, bars.lastIndex)].key
                true
            }
        },
        horizontalArrangement = Arrangement.spacedBy(if (bars.size <= 12) 8.dp else 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { bar ->
            val isSelected = bar.key == selectedKey
            val description = stringResource(
                Res.string.stats_chart_day_summary,
                bar.label(),
                pluralStringResource(Res.plurals.privacy_history_listens, bar.plays, bar.plays),
                formatListenedTime(bar.playedMs),
            )
            // Zero activity is only a quiet baseline, never a visible invented listening amount.
            val height = if (bar.playedMs > 0) {
                (88f * (bar.playedMs.toDouble() / maxDuration).toFloat()).coerceAtLeast(3f)
            } else {
                2f
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .clickable(role = Role.Button) { selectedKey = bar.key }
                    .semantics {
                        contentDescription = description
                        selected = isSelected
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(height.dp)
                        .background(
                            color = when {
                                isSelected -> accentInk
                                bar.playedMs == 0L -> MaterialTheme.colorScheme.outlineVariant
                                else -> accentInk.copy(alpha = 0.62f)
                            },
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        )
                        .clearAndSetSemantics { },
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Text(
            text = bars.first().label(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (bars.size > 1) {
            Text(
                text = bars.last().label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (selectedKey != null) {
        Text(
            text = selectedSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NewAndFamiliarCard(
    overview: ListeningOverview,
    insights: PeriodInsights,
    period: StatsPeriod,
    accentInk: Color,
) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_new_and_familiar))
        if (overview.plays < PeriodInsights.MIN_NEW_PLAYS_BASE) {
            StatsNote(
                pluralStringResource(
                    Res.plurals.stats_locked_plays,
                    PeriodInsights.MIN_NEW_PLAYS_BASE,
                    PeriodInsights.MIN_NEW_PLAYS_BASE,
                ),
            )
            return@StatsCard
        }
        val plays = (insights.newPlays + insights.familiarPlays).coerceAtLeast(1)
        val familiarShare = insights.familiarPlays.toFloat() / plays
        val familiarText = percent(familiarShare)
        val newText = formatPercent(100 - percentValue(familiarShare))
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${stringResource(Res.string.stats_familiar)} $familiarText",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${stringResource(Res.string.stats_new_tracks)} $newText",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProportionBar(familiarShare, color = accentInk)
        if (period.days != null) {
            StatsNote(
                pluralStringResource(Res.plurals.stats_new_tracks_count, insights.newTracks, insights.newTracks) +
                    " · " +
                    pluralStringResource(Res.plurals.stats_new_artists_count, insights.newArtists, insights.newArtists),
            )
            val previous = insights.previousNewShare
            if (previous != null) {
                StatsNote(stringResource(Res.string.stats_new_previous, period.days, percent(previous)))
            }
        }
        if (insights.stuckArtists.isNotEmpty()) {
            Text(
                text = stringResource(
                    if (period.days == null) Res.string.stats_stuck_artists_recent else Res.string.stats_stuck_artists,
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            insights.stuckArtists.forEach { artist ->
                NameRow(
                    title = artist.name,
                    detail = pluralStringResource(Res.plurals.privacy_history_listens, artist.plays, artist.plays),
                )
            }
        }
        insights.logDaysInsidePeriod?.let { days ->
            StatsNote(pluralStringResource(Res.plurals.stats_log_age, days, days))
        }
        StatsNote(stringResource(Res.string.stats_new_note))
    }
}

@Composable
private fun WhenYouListenCard(insights: PeriodInsights, accentInk: Color) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_when_you_listen))
        val sessions = insights.sessions
        if (sessions == null) {
            StatsNote(stringResource(Res.string.stats_locked_sessions))
            return@StatsCard
        }
        HourBars(insights.listenedMsByHour, accentInk)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = 2,
        ) {
            Column(modifier = Modifier.weight(1f).widthIn(min = 120.dp).semantics(mergeDescendants = true) { }) {
                Metric(formatListenedTime(sessions.medianListenedMs), stringResource(Res.string.stats_typical_session))
                StatsNote(pluralStringResource(Res.plurals.count_tracks, sessions.medianPlays, sessions.medianPlays))
            }
            Column(modifier = Modifier.weight(1f).widthIn(min = 120.dp).semantics(mergeDescendants = true) { }) {
                Metric(formatListenedTime(sessions.longestListenedMs), stringResource(Res.string.stats_longest_session))
                val today = localTimePoint(epochMillis()).epochDay
                StatsNote(statsDayLabel((today - localTimePoint(sessions.longestStartMs).epochDay).toInt()))
            }
        }
    }
}

@Composable
private fun HourBars(listenedByHour: List<Long>, accentInk: Color) {
    val peak = listenedByHour.maxOrNull()?.takeIf { it > 0 } ?: return
    val total = listenedByHour.sum().coerceAtLeast(1)
    val dayparts = listOf(
        Res.string.stats_night to 0,
        Res.string.stats_morning to 6,
        Res.string.stats_afternoon to 12,
        Res.string.stats_evening to 18,
    ).map { (label, from) -> label to listenedByHour.subList(from, from + 6).sum().toFloat() / total }
    val (peakLabel, peakShare) = dayparts.maxBy { it.second }
    val chartDescription = stringResource(Res.string.stats_daypart_peak, stringResource(peakLabel), percent(peakShare))
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics { contentDescription = chartDescription },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listenedByHour.forEach { listened ->
            Box(
                modifier = Modifier.weight(1f)
                    .height(if (listened == 0L) 2.dp else (60f * listened / peak).coerceAtLeast(3f).dp)
                    .background(
                        color = if (listened == peak) accentInk else accentInk.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                    ),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        dayparts.forEach { (label, share) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = percent(share),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (label == peakLabel) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun YourTopCard(
    overview: ListeningOverview,
    insights: PeriodInsights,
    tracksById: Map<TrackId, TrackDescriptor>,
    playlistLabels: Map<String, PlaylistLabel>,
    playRows: (List<TrackId>, Int) -> Unit,
) {
    var savedKind by rememberSaveable { mutableStateOf(TopKind.ARTISTS.name) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val available = buildList {
        add(TopKind.ARTISTS)
        if (insights.topAlbums != null) add(TopKind.ALBUMS)
        if (insights.topGenres != null) add(TopKind.GENRES)
        if (insights.topPlaylists != null) add(TopKind.PLAYLISTS)
        add(TopKind.TRACKS)
    }
    // A chip missing in this period shows Artists but keeps the listener's choice for later.
    val kind = TopKind.entries.firstOrNull { it.name == savedKind }?.takeIf { it in available } ?: TopKind.ARTISTS
    val limit = if (expanded) 10 else 5
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_your_top))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            available.forEach { candidate ->
                AppChoiceChip(
                    selected = candidate == kind,
                    onClick = { savedKind = candidate.name },
                    label = stringResource(candidate.labelRes()),
                )
            }
        }
        val count = when (kind) {
            TopKind.TRACKS -> {
                val rows = overview.topTracks.filter { it.trackId in tracksById }
                val ids = rows.map { it.trackId }
                rows.take(limit).forEachIndexed { index, entry ->
                    val descriptor = tracksById.getValue(entry.trackId)
                    RankedRow(
                        rank = index + 1,
                        title = descriptor.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.track_untitled),
                        subtitle = descriptor.artist?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.track_unknown_artist),
                        artworkUri = descriptor.artworkUri,
                        showArtwork = true,
                        plays = entry.plays,
                        playedMs = entry.playedMs,
                        onClick = { playRows(ids, index) },
                    )
                }
                rows.size
            }
            else -> {
                val rows: List<RankedName> = when (kind) {
                    TopKind.ALBUMS -> insights.topAlbums.orEmpty()
                    TopKind.GENRES -> insights.topGenres.orEmpty()
                    TopKind.PLAYLISTS -> insights.topPlaylists.orEmpty()
                    else -> insights.topArtists
                }
                rows.take(limit).forEachIndexed { index, entry ->
                    val playlist = if (kind == TopKind.PLAYLISTS) playlistLabels[entry.name] else null
                    RankedRow(
                        rank = index + 1,
                        title = when {
                            kind == TopKind.PLAYLISTS -> playlist.title()
                            else -> entry.name.ifBlank { stringResource(Res.string.track_unknown_artist) }
                        },
                        subtitle = entry.subtitle,
                        artworkUri = if (kind == TopKind.PLAYLISTS) {
                            playlist?.artworkUri
                        } else {
                            entry.trackId?.let { tracksById[it]?.artworkUri }
                        },
                        showArtwork = kind == TopKind.ALBUMS || kind == TopKind.PLAYLISTS,
                        plays = entry.plays,
                        playedMs = entry.playedMs,
                    )
                }
                rows.size
            }
        }
        if (kind == TopKind.PLAYLISTS) StatsNote(stringResource(Res.string.stats_playlists_note))
        if (count > 5) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (expanded) Res.string.stats_show_less else Res.string.stats_show_more))
            }
        }
    }
}

private fun TopKind.labelRes() = when (this) {
    TopKind.ARTISTS -> Res.string.tab_artists
    TopKind.ALBUMS -> Res.string.tab_albums
    TopKind.GENRES -> Res.string.tab_genres
    TopKind.PLAYLISTS -> Res.string.tab_playlists
    TopKind.TRACKS -> Res.string.tab_tracks
}

@Composable
private fun HowYouListenCard(overview: ListeningOverview, accentInk: Color) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_listening_style))
        // The three shares of all starts always add up to 100 after rounding.
        val finished = percentValue(overview.completionRate)
        val skipped = percentValue(overview.skipRate)
        val partly = (100 - finished - skipped).coerceAtLeast(0)
        ShareMeter(stringResource(Res.string.stats_finished_share), finished, color = accentInk)
        ShareMeter(stringResource(Res.string.stats_partly_share), partly, color = accentInk)
        ShareMeter(stringResource(Res.string.stats_skipped_share), skipped, color = accentInk)
        StatsNote(stringResource(Res.string.stats_finish_note))
        AppDivider(Modifier.padding(vertical = 4.dp))
        ShareMeter(
            label = stringResource(Res.string.stats_smart_mode_share),
            percent = percentValue(overview.smartPlays.toFloat() / overview.plays.coerceAtLeast(1)),
            color = MaterialTheme.colorScheme.tertiary,
        )
        StatsNote(stringResource(Res.string.stats_smart_detail))
    }
}

// ---- Section 3 ----------------------------------------------------------------------------------

@Composable
private fun SlippingAwayCard(
    habits: HabitInsights,
    tracksById: Map<TrackId, TrackDescriptor>,
    playRows: (List<TrackId>, Int) -> Unit,
) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_slipping_away))
        val ids = habits.slippingAway.map { it.trackId }
        val now = epochMillis()
        habits.slippingAway.forEachIndexed { index, entry ->
            val descriptor = tracksById[entry.trackId] ?: return@forEachIndexed
            val months = ((now - entry.lastStartMs) / (30 * DAY_MS)).toInt().coerceAtLeast(2)
            TrackRow(
                descriptor = descriptor,
                detail = pluralStringResource(Res.plurals.privacy_history_listens, entry.chosenPlays, entry.chosenPlays) +
                    " · " + pluralStringResource(Res.plurals.stats_months_ago, months, months),
                onClick = { playRows(ids, index) },
            )
        }
        StatsNote(stringResource(Res.string.stats_slipping_note))
    }
}

@Composable
private fun YearAgoCard(
    habits: HabitInsights,
    tracksById: Map<TrackId, TrackDescriptor>,
    playRows: (List<TrackId>, Int) -> Unit,
) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_year_ago))
        val ids = habits.yearAgo.map { it.trackId }
        habits.yearAgo.forEachIndexed { index, entry ->
            val descriptor = tracksById[entry.trackId] ?: return@forEachIndexed
            TrackRow(
                descriptor = descriptor,
                detail = pluralStringResource(Res.plurals.privacy_history_listens, entry.plays, entry.plays),
                onClick = { playRows(ids, index) },
            )
        }
    }
}

@Composable
private fun PatternsCard(habits: HabitInsights, tracksById: Map<TrackId, TrackDescriptor>, accentInk: Color) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_patterns))
        if (habits.sameArtistShown) {
            val per100 = percentValue(habits.sameArtistPicks.toFloat() / habits.pickSteps)
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(Res.string.stats_same_artist_picks),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(Res.string.stats_of_100_picks, per100),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProportionBar(per100 / 100f, color = accentInk)
            }
            StatsNote(stringResource(Res.string.stats_same_artist_note))
        }
        if (habits.sequences.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.stats_picked_next),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            habits.sequences.forEach { sequence ->
                val from = tracksById[sequence.from] ?: return@forEach
                val to = tracksById[sequence.to] ?: return@forEach
                SequenceRow(from, to, sequence.count, sequence.total)
            }
        }
    }
}

@Composable
private fun SequenceRow(from: TrackDescriptor, to: TrackDescriptor, count: Int, total: Int) {
    val fromTitle = from.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.track_untitled)
    val toTitle = to.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.track_untitled)
    val description = stringResource(Res.string.stats_sequence_description, fromTitle, toTitle, count, total)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.width(52.dp).height(40.dp)) {
            Artwork(uri = from.artworkUri, size = 32.dp, cornerRadius = 8.dp)
            Artwork(
                uri = to.artworkUri,
                size = 32.dp,
                cornerRadius = 8.dp,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fromTitle,
                style = MaterialTheme.typography.bodyMedium.metadata(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = toTitle,
                    style = MaterialTheme.typography.bodyMedium.metadata(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Text(
            text = "$count/$total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryCard(habits: HabitInsights, accentInk: Color) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_library))
        Metric(
            value = stringResource(
                Res.string.stats_x_of_y,
                formatCount(habits.libraryTracksPlayed),
                formatCount(habits.librarySize),
            ),
            label = stringResource(Res.string.stats_played_once),
        )
        if (habits.decades.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.stats_decades),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            val total = habits.decades.sumOf { it.plays }.coerceAtLeast(1)
            habits.decades.sortedByDescending { it.plays }.take(5).sortedBy { it.decade }.forEach { decade ->
                ShareMeter(
                    label = stringResource(Res.string.stats_decade, decade.decade),
                    percent = percentValue(decade.plays.toFloat() / total),
                    color = accentInk.copy(alpha = 0.62f),
                )
            }
        }
    }
}

// ---- Shared rows and parts -----------------------------------------------------------------------

/** A track that plays when tapped; [share] draws an optional strength bar under it. */
@Composable
private fun TrackRow(
    descriptor: TrackDescriptor,
    detail: String,
    onClick: () -> Unit,
    share: Float? = null,
    accentInk: Color = MaterialTheme.colorScheme.primary,
) {
    val title = descriptor.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.track_untitled)
    val artist = descriptor.artist?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.track_unknown_artist)
    val playLabel = stringResource(Res.string.action_play)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = playLabel, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(uri = descriptor.artworkUri, size = 40.dp, cornerRadius = 12.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.metadata(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                artist,
                style = MaterialTheme.typography.bodySmall.metadata(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (share != null) ProportionBar(share, color = accentInk.copy(alpha = 0.62f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** An artist or other name; not interactive. */
@Composable
private fun NameRow(
    title: String,
    detail: String,
    share: Float? = null,
    accentInk: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium.metadata(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (share != null) ProportionBar(share, color = accentInk.copy(alpha = 0.62f), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun RankedRow(
    rank: Int,
    title: String,
    plays: Int,
    playedMs: Long,
    subtitle: String? = null,
    artworkUri: String? = null,
    showArtwork: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val listeningSummary = pluralStringResource(Res.plurals.privacy_history_listens, plays, plays) +
        " · " + formatListenedTime(playedMs)
    val playLabel = stringResource(Res.string.action_play)
    val interaction = if (onClick != null) {
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = playLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (onClick != null) 48.dp else 0.dp)
            .then(interaction)
            .padding(vertical = 7.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.widthIn(min = with(LocalDensity.current) { 20.sp.toDp() }),
        )
        if (showArtwork) Artwork(uri = artworkUri, size = 40.dp, cornerRadius = 12.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.metadata(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.metadata(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = plays.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { contentDescription = listeningSummary },
        )
    }
}

@Composable
private fun StatsCard(
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp.corner()),
        color = color,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier = modifier.semantics(mergeDescendants = true) { }) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = if (large) 24.sp else 22.sp,
                lineHeight = if (large) 32.sp else 28.sp,
            ),
            fontWeight = FontWeight.Normal,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        modifier = modifier.semantics { heading() },
    )
}

/** A page section's heading; the cards below it belong to it. */
@Composable
private fun StatsSectionHeader(text: String, padded: Boolean = true) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium,
        modifier = (if (padded) Modifier.padding(horizontal = 20.dp) else Modifier)
            .padding(top = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun StatsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun ShareMeter(
    label: String,
    percent: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(formatPercent(percent), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ProportionBar(percent / 100f, color = color)
    }
}

/** Decorative only: the adjacent text carries an exact, accessible value. */
@Composable
private fun ProportionBar(
    share: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            .clearAndSetSemantics { },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
    }
}

private fun percentValue(share: Float): Int = (share.coerceIn(0f, 1f) * 100).roundToInt()

private fun percent(share: Float): String = formatPercent(percentValue(share))

/** Milliseconds until the device's local date changes after [nowMs] (to within a second). */
private fun msUntilNextLocalDay(nowMs: Long): Long {
    val today = localTimePoint(nowMs).epochDay
    var low = nowMs
    var high = nowMs + 26L * 60 * 60 * 1000
    while (high - low > 1_000) {
        val middle = low + (high - low) / 2
        if (localTimePoint(middle).epochDay > today) high = middle else low = middle
    }
    return (high - nowMs).coerceAtLeast(1_000)
}

@Composable
private fun statsDayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> stringResource(Res.string.stats_today)
    1 -> stringResource(Res.string.stats_yesterday)
    else -> pluralStringResource(Res.plurals.stats_days_ago, daysAgo, daysAgo)
}

@Composable
private fun formatListenedTime(playedMs: Long): String {
    if (playedMs in 1..59_999) return stringResource(Res.string.stats_under_minute)
    val totalMinutes = playedMs.coerceAtLeast(0) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val hoursShort = stringResource(Res.string.stats_hours_short)
    val minutesShort = stringResource(Res.string.stats_minutes_short)
    return if (hours > 0) "$hours $hoursShort $minutes $minutesShort" else "$minutes $minutesShort"
}
