/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_retry
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.privacy_history_listens
import io.github.nikitasud.latentjam.app.generated.resources.stats_active_days
import io.github.nikitasud.latentjam.app.generated.resources.stats_activity
import io.github.nikitasud.latentjam.app.generated.resources.stats_average_active_day
import io.github.nikitasud.latentjam.app.generated.resources.stats_by_hour
import io.github.nikitasud.latentjam.app.generated.resources.stats_calendar_days
import io.github.nikitasud.latentjam.app.generated.resources.stats_chart_day_summary
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_first
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_less
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_more
import io.github.nikitasud.latentjam.app.generated.resources.stats_comparison_same
import io.github.nikitasud.latentjam.app.generated.resources.stats_coverage
import io.github.nikitasud.latentjam.app.generated.resources.stats_coverage_detail
import io.github.nikitasud.latentjam.app.generated.resources.stats_dashboard_title
import io.github.nikitasud.latentjam.app.generated.resources.stats_days_ago
import io.github.nikitasud.latentjam.app.generated.resources.stats_days_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_distinct_tracks
import io.github.nikitasud.latentjam.app.generated.resources.stats_empty
import io.github.nikitasud.latentjam.app.generated.resources.stats_finished_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_habits
import io.github.nikitasud.latentjam.app.generated.resources.stats_habits_note
import io.github.nikitasud.latentjam.app.generated.resources.stats_history_note
import io.github.nikitasud.latentjam.app.generated.resources.stats_hours_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_listening_style
import io.github.nikitasud.latentjam.app.generated.resources.stats_load_failed
import io.github.nikitasud.latentjam.app.generated.resources.stats_minutes_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_new_tracks
import io.github.nikitasud.latentjam.app.generated.resources.stats_peak_hour
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_all
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_month
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_week
import io.github.nikitasud.latentjam.app.generated.resources.stats_plays
import io.github.nikitasud.latentjam.app.generated.resources.stats_recent_empty
import io.github.nikitasud.latentjam.app.generated.resources.stats_recording_off
import io.github.nikitasud.latentjam.app.generated.resources.stats_repeat_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_skipped_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_smart_detail
import io.github.nikitasud.latentjam.app.generated.resources.stats_smart_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_streak_current
import io.github.nikitasud.latentjam.app.generated.resources.stats_streak_longest
import io.github.nikitasud.latentjam.app.generated.resources.stats_time_listened
import io.github.nikitasud.latentjam.app.generated.resources.stats_today
import io.github.nikitasud.latentjam.app.generated.resources.stats_top_artists
import io.github.nikitasud.latentjam.app.generated.resources.stats_top_tracks
import io.github.nikitasud.latentjam.app.generated.resources.stats_under_minute
import io.github.nikitasud.latentjam.app.generated.resources.stats_yesterday
import io.github.nikitasud.latentjam.app.generated.resources.tab_artists
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.history.DailyListening
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.ListeningOverview
import io.github.nikitasud.latentjam.history.ListeningOverviews
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class StatsPeriod(val days: Int?) { WEEK(7), MONTH(30), ALL(null) }

private data class StatsHistorySnapshot(val revision: Long, val events: List<ListenEvent>)

private data class StatsLoad(
    val overview: ListeningOverview,
    val tracksById: Map<TrackId, TrackDescriptor>,
    val history: StatsHistorySnapshot,
)

/** A shared dashboard for Settings and the optional root page; never reads playback ticks. */
@Composable
internal fun ListeningStatsSettings(
    history: ListeningHistory,
    tracks: List<TrackDescriptor>,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    active: Boolean = true,
) {
    var periodName by rememberSaveable { mutableStateOf(StatsPeriod.MONTH.name) }
    val period = StatsPeriod.entries.firstOrNull { it.name == periodName } ?: StatsPeriod.MONTH
    val historyRevision by AppGraph.historyRevision.collectAsState()
    val recordingHistory by AppGraph.settings.saveListeningHistory.collectAsState()
    var historySnapshot by remember(history) { mutableStateOf<StatsHistorySnapshot?>(null) }
    var catalogSnapshot by remember(tracks) { mutableStateOf<Map<TrackId, TrackDescriptor>?>(null) }
    var loaded by remember { mutableStateOf<StatsLoad?>(null) }
    var loadedSelection by remember { mutableStateOf<Any?>(null) }
    var failedRequest by remember { mutableStateOf<Any?>(null) }
    var retryRevision by remember { mutableIntStateOf(0) }
    // Filter/source changes must not show mislabeled old data. New recorded listens and page
    // activation refresh that same selection in place, preserving lazy items and scroll position.
    val selection = remember(history, period, tracks) { Any() }
    val request = remember(selection, historyRevision, retryRevision) { Any() }
    val displayed = loaded.takeIf { loadedSelection === selection || !active }

    LaunchedEffect(request, active) {
        if (!active) return@LaunchedEffect
        failedRequest = null
        try {
            val now = epochMillis()
            val loadContext = kotlinx.coroutines.currentCoroutineContext()
            val cachedEvents = historySnapshot?.takeIf { it.revision == historyRevision }
            val cachedCatalog = catalogSnapshot
            val result = withContext(Dispatchers.Default) {
                val events = cachedEvents ?: StatsHistorySnapshot(historyRevision, history.allEvents())
                val tracksById = cachedCatalog ?: tracks.associateBy { it.id }
                loadContext.ensureActive()
                StatsLoad(
                    overview = ListeningOverviews.summarize(
                        events = events.events,
                        artistOf = { tracksById[it]?.artist },
                        sinceMs = period.days?.let { now - it * 24L * 60 * 60 * 1000 },
                        nowMs = now,
                        includeTrack = tracksById::containsKey,
                        cancellationCheck = { loadContext.ensureActive() },
                        chartDays = period.days ?: 30,
                    ),
                    tracksById = tracksById,
                    history = events,
                )
            }
            historySnapshot = result.history
            catalogSnapshot = result.tracksById
            loaded = result
            loadedSelection = selection
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failedRequest = request
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "filters") {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 20.dp)) {
                Text(
                    text = stringResource(Res.string.stats_dashboard_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatsPeriod.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate == period,
                            onClick = { periodName = candidate.name },
                            label = { Text(stringResource(candidate.titleRes())) },
                        )
                    }
                }
            }
        }
        if (!recordingHistory) {
            item(key = "recording-paused") {
                StatsCard {
                    StatsNote(stringResource(Res.string.stats_recording_off))
                }
            }
        }
        if (displayed == null && failedRequest !== request) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }
        if (failedRequest === request) {
            item(key = "error") {
                StatsCard {
                    Text(
                        text = stringResource(Res.string.stats_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(onClick = { retryRevision += 1 }) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }
            if (displayed == null) return@LazyColumn
        }
        val result = displayed ?: return@LazyColumn
        val current = result.overview
        item(key = "listening-summary") { ListeningHero(current, period) }
        if (current.plays == 0) {
            item(key = "empty") {
                StatsCard {
                    Icon(
                        imageVector = Icons.Rounded.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = stringResource(Res.string.stats_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    StatsNote(stringResource(Res.string.stats_history_note))
                }
            }
            return@LazyColumn
        }
        item(key = "daily-activity") {
            StatsCard {
                StatsTitle(stringResource(Res.string.stats_activity))
                StatsNote(stringResource(Res.string.stats_calendar_days, current.dailyListening.size))
                DailyBars(current.dailyListening)
            }
        }
        item(key = "habits") { ListeningHabits(current) }
        item(key = "listening-style") {
            StatsCard {
                StatsTitle(stringResource(Res.string.stats_listening_style))
                ShareMeter(stringResource(Res.string.stats_finished_share), current.completionRate)
                ShareMeter(stringResource(Res.string.stats_skipped_share), current.skipRate)
                ShareMeter(
                    stringResource(Res.string.stats_repeat_share),
                    current.repeatPlays.toFloat() / current.plays,
                )
            }
        }
        if (result.tracksById.isNotEmpty()) {
            item(key = "library-coverage") {
                LibraryCoverage(current, result.tracksById.size)
            }
        }
        item(key = "time-of-day") {
            StatsCard {
                StatsTitle(stringResource(Res.string.stats_by_hour))
                HourBars(current.playsByHour)
            }
        }
        if (current.topArtists.isNotEmpty()) {
            item(key = "artists-title") {
                StatsSectionTitle(stringResource(Res.string.stats_top_artists))
            }
            items(
                count = current.topArtists.size,
                key = { index -> "artist:${current.topArtists[index].artist}" },
            ) { index ->
                val artist = current.topArtists[index]
                RankedRow(
                    rank = index + 1,
                    title = artist.artist,
                    plays = artist.plays,
                    playedMs = artist.playedMs,
                    share = artist.plays.toFloat() / current.topArtists.first().plays,
                )
            }
        }
        if (current.topTracks.isNotEmpty()) {
            item(key = "tracks-title") {
                StatsSectionTitle(stringResource(Res.string.stats_top_tracks))
            }
            items(
                count = current.topTracks.size,
                key = { index -> "track:${current.topTracks[index].trackId}" },
            ) { index ->
                val entry = current.topTracks[index]
                val descriptor = result.tracksById[entry.trackId] ?: return@items
                RankedRow(
                    rank = index + 1,
                    title = descriptor.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.track_untitled),
                    subtitle = descriptor.artist?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.track_unknown_artist),
                    artworkUri = descriptor.artworkUri,
                    showArtwork = true,
                    plays = entry.plays,
                    playedMs = entry.playedMs,
                    share = entry.plays.toFloat() / current.topTracks.first().plays,
                )
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
private fun ListeningHero(overview: ListeningOverview, period: StatsPeriod) {
    StatsCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text = stringResource(Res.string.stats_time_listened),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatListenedTime(overview.playedMs),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        val previous = overview.previousPeriod
        if (previous != null && period.days != null) {
            val comparison = when {
                previous.playedMs == 0L && overview.playedMs > 0L ->
                    stringResource(Res.string.stats_comparison_first, period.days)
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
            StatsNote(comparison)
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
        ) {
            Metric(
                value = overview.plays.toString(),
                label = stringResource(Res.string.stats_plays),
                modifier = Modifier.weight(1f).widthIn(min = 80.dp),
            )
            Metric(
                value = overview.distinctTracks.toString(),
                label = stringResource(Res.string.stats_distinct_tracks),
                modifier = Modifier.weight(1f).widthIn(min = 80.dp),
            )
            Metric(
                value = overview.distinctArtists.toString(),
                label = stringResource(Res.string.tab_artists),
                modifier = Modifier.weight(1f).widthIn(min = 80.dp),
            )
        }
    }
}

@Composable
private fun ListeningHabits(overview: ListeningOverview) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_habits))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            maxItemsInEachRow = 2,
        ) {
            Metric(
                value = overview.activeDays.toString(),
                label = stringResource(Res.string.stats_active_days),
                modifier = Modifier.weight(1f).widthIn(min = 108.dp),
            )
            Metric(
                value = formatListenedTime(overview.playedMs / overview.activeDays.coerceAtLeast(1)),
                label = stringResource(Res.string.stats_average_active_day),
                modifier = Modifier.weight(1f).widthIn(min = 108.dp),
            )
            Metric(
                value = overview.newTracks.toString(),
                label = stringResource(Res.string.stats_new_tracks),
                modifier = Modifier.weight(1f).widthIn(min = 108.dp),
            )
            Column(modifier = Modifier.weight(1f).widthIn(min = 108.dp)) {
                val days = stringResource(Res.string.stats_days_short)
                Metric(
                    value = "${overview.currentStreakDays} $days",
                    label = stringResource(Res.string.stats_streak_current),
                )
                Text(
                    text = "${stringResource(Res.string.stats_streak_longest)} · ${overview.longestStreakDays} $days",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        StatsNote(stringResource(Res.string.stats_habits_note))
    }
}

@Composable
private fun LibraryCoverage(overview: ListeningOverview, librarySize: Int) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_coverage))
        val share = overview.libraryTracksHeard.toFloat() / librarySize.coerceAtLeast(1)
        Text(
            text = percent(share),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        StatsNote(
            stringResource(
                Res.string.stats_coverage_detail,
                overview.libraryTracksHeard,
                pluralStringResource(Res.plurals.count_tracks, librarySize, librarySize),
            ),
        )
        ProportionBar(share)
        ShareMeter(
            label = stringResource(Res.string.stats_smart_share),
            share = overview.smartPlays.toFloat() / overview.plays.coerceAtLeast(1),
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 8.dp),
        )
        StatsNote(stringResource(Res.string.stats_smart_detail))
    }
}

@Composable
private fun DailyBars(days: List<DailyListening>) {
    if (days.isEmpty()) return
    val maxDuration = days.maxOf { it.playedMs }.coerceAtLeast(1L)
    val today = days.last().epochDay
    var selectedDay by remember(days) { mutableStateOf(today) }
    Row(
        modifier = Modifier.fillMaxWidth().height(104.dp),
        horizontalArrangement = Arrangement.spacedBy(if (days.size <= 7) 8.dp else 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val isSelected = day.epochDay == selectedDay
            val description = stringResource(
                Res.string.stats_chart_day_summary,
                statsDayLabel((today - day.epochDay).toInt()),
                pluralStringResource(Res.plurals.privacy_history_listens, day.plays, day.plays),
                formatListenedTime(day.playedMs),
            )
            // Zero activity is only a quiet baseline, never a visible invented listening amount.
            val height = if (day.playedMs > 0) {
                (100f * (day.playedMs.toDouble() / maxDuration).toFloat()).coerceAtLeast(3f)
            } else {
                2f
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    )
                    .clickable(role = Role.Button) { selectedDay = day.epochDay }
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
                                isSelected -> MaterialTheme.colorScheme.primary
                                day.playedMs == 0L -> MaterialTheme.colorScheme.outlineVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
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
            text = statsDayLabel(days.size - 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.stats_today),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
    val selected = days.firstOrNull { it.epochDay == selectedDay } ?: days.last()
    Text(
        text = stringResource(
            Res.string.stats_chart_day_summary,
            statsDayLabel((today - selected.epochDay).toInt()),
            pluralStringResource(Res.plurals.privacy_history_listens, selected.plays, selected.plays),
            formatListenedTime(selected.playedMs),
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (days.none { it.plays > 0 }) StatsNote(stringResource(Res.string.stats_recent_empty))
}

@Composable
private fun HourBars(playsByHour: List<Int>) {
    val peakPlays = playsByHour.maxOrNull()?.takeIf { it > 0 } ?: return
    val peakHour = playsByHour.indexOf(peakPlays)
    StatsNote(stringResource(Res.string.stats_peak_hour, "${peakHour.toString().padStart(2, '0')}:00"))
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        playsByHour.forEachIndexed { hour, plays ->
            val description = "${hour.toString().padStart(2, '0')}:00 · " +
                pluralStringResource(Res.plurals.privacy_history_listens, plays, plays)
            Box(
                modifier = Modifier.weight(1f)
                    .height(if (plays == 0) 2.dp else (60f * plays / peakPlays).coerceAtLeast(3f).dp)
                    .background(
                        color = if (plays == peakPlays) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                    )
                    .clearAndSetSemantics { contentDescription = description },
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        listOf("00", "06", "12", "18").forEach { hour ->
            Text(
                text = hour,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RankedRow(
    rank: Int,
    title: String,
    plays: Int,
    playedMs: Long,
    share: Float,
    subtitle: String? = null,
    artworkUri: String? = null,
    showArtwork: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp).semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showArtwork) Artwork(uri = artworkUri, size = 40.dp, cornerRadius = 8.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = pluralStringResource(Res.plurals.privacy_history_listens, plays, plays) +
                        " · " + formatListenedTime(playedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProportionBar(share, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun StatsCard(
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        color = color,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) { }) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
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
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun StatsSectionTitle(text: String) {
    StatsTitle(text, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp))
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
    share: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(percent(share), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        ProportionBar(share, color = color)
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
        modifier = modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            .clearAndSetSemantics { },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
    }
}

private fun percent(share: Float): String = "${(share.coerceIn(0f, 1f) * 100).roundToInt()}%"

@Composable
private fun statsDayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> stringResource(Res.string.stats_today)
    1 -> stringResource(Res.string.stats_yesterday)
    else -> stringResource(Res.string.stats_days_ago, daysAgo)
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
