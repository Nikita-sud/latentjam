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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    accent: TrackAccent? = null,
) {
    val accentInk = accent?.let { browseAccentInk(it) } ?: MaterialTheme.colorScheme.primary
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

    FadingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "filters") {
            // The browse carousel and Settings top bar already name this page. Start with the
            // reporting period so the content has one hierarchy in either entry point.
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 3.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatsPeriod.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate == period,
                            onClick = { periodName = candidate.name },
                            label = { Text(stringResource(candidate.titleRes())) },
                            modifier = Modifier.heightIn(min = 34.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                                selectedLabelColor = MaterialTheme.colorScheme.surface,
                            ),
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
            return@FadingLazyColumn
        }
        if (failedRequest === request) {
            item(key = "error") {
                StatsCard {
                    Text(
                        text = stringResource(Res.string.stats_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(
                        onClick = { retryRevision += 1 },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }
            if (displayed == null) return@FadingLazyColumn
        }
        val result = displayed ?: return@FadingLazyColumn
        val current = result.overview
        item(key = "listening-summary") { ListeningHero(current, period, accentInk) }
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
            return@FadingLazyColumn
        }
        item(key = "habits") { ListeningHabits(current) }
        if (result.tracksById.isNotEmpty()) {
            item(key = "library-coverage") {
                LibraryCoverage(current, result.tracksById.size, accentInk)
            }
        }
        item(key = "daily-activity") {
            StatsCard {
                StatsTitle(stringResource(Res.string.stats_activity))
                StatsNote(stringResource(Res.string.stats_calendar_days, current.dailyListening.size))
                DailyBars(current.dailyListening, accentInk)
            }
        }
        item(key = "listening-style") {
            StatsCard {
                StatsTitle(stringResource(Res.string.stats_listening_style))
                ShareMeter(stringResource(Res.string.stats_finished_share), current.completionRate, color = accentInk)
                ShareMeter(stringResource(Res.string.stats_skipped_share), current.skipRate, color = accentInk)
                ShareMeter(
                    stringResource(Res.string.stats_repeat_share),
                    current.repeatPlays.toFloat() / current.plays,
                    color = accentInk,
                )
            }
        }
        item(key = "time-of-day") {
            StatsCard {
                StatsTitle(stringResource(Res.string.stats_by_hour))
                HourBars(current.playsByHour, accentInk)
            }
        }
        if (current.topArtists.isNotEmpty()) {
            item(key = "top-artists") {
                StatsCard {
                    StatsTitle(stringResource(Res.string.stats_top_artists))
                    current.topArtists.forEachIndexed { index, artist ->
                        RankedRow(
                            rank = index + 1,
                            title = artist.artist,
                            plays = artist.plays,
                            playedMs = artist.playedMs,
                        )
                    }
                }
            }
        }
        if (current.topTracks.isNotEmpty()) {
            item(key = "top-tracks") {
                StatsCard {
                    StatsTitle(stringResource(Res.string.stats_top_tracks))
                    current.topTracks.forEachIndexed { index, entry ->
                        val descriptor = result.tracksById[entry.trackId] ?: return@forEachIndexed
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
                        )
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
private fun ListeningHero(overview: ListeningOverview, period: StatsPeriod, accentInk: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text = (stringResource(Res.string.stats_time_listened) + " · " +
                stringResource(period.titleRes())).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 0.5.sp,
            ),
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
            Text(
                text = comparison,
                style = MaterialTheme.typography.bodySmall,
                color = accentInk,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 3,
        ) {
            HeroMetric(overview.plays.toString(), stringResource(Res.string.stats_plays), Modifier.weight(1f))
            HeroMetric(overview.distinctTracks.toString(), stringResource(Res.string.stats_distinct_tracks), Modifier.weight(1f))
            HeroMetric(overview.distinctArtists.toString(), stringResource(Res.string.tab_artists), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String, modifier: Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 88.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Metric(value, label, Modifier.padding(14.dp), large = true)
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        StatsNote(stringResource(Res.string.stats_habits_note))
    }
}

@Composable
private fun LibraryCoverage(overview: ListeningOverview, librarySize: Int, accentInk: Color) {
    StatsCard {
        StatsTitle(stringResource(Res.string.stats_coverage))
        val share = overview.libraryTracksHeard.toFloat() / librarySize.coerceAtLeast(1)
        StatsNote(
            stringResource(
                Res.string.stats_coverage_detail,
                overview.libraryTracksHeard,
                pluralStringResource(Res.plurals.count_tracks, librarySize, librarySize),
            ),
        )
        ProportionBar(share, color = accentInk)
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
private fun DailyBars(days: List<DailyListening>, accentInk: Color) {
    if (days.isEmpty()) return
    val maxDuration = days.maxOf { it.playedMs }.coerceAtLeast(1L)
    val today = days.last().epochDay
    var selectedDay by remember(days) { mutableStateOf<Long?>(null) }
    val selectedIndex = days.indexOfFirst { it.epochDay == selectedDay }.takeIf { it >= 0 } ?: days.lastIndex
    val selectedEntry = days[selectedIndex]
    val selectedSummary = stringResource(
        Res.string.stats_chart_day_summary,
        statsDayLabel((today - selectedEntry.epochDay).toInt()),
        pluralStringResource(Res.plurals.privacy_history_listens, selectedEntry.plays, selectedEntry.plays),
        formatListenedTime(selectedEntry.playedMs),
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(92.dp).semantics {
            stateDescription = selectedSummary
            progressBarRangeInfo = ProgressBarRangeInfo(
                selectedIndex.toFloat(), 0f..days.lastIndex.toFloat(), (days.size - 2).coerceAtLeast(0),
            )
            setProgress { index ->
                selectedDay = days[index.roundToInt().coerceIn(0, days.lastIndex)].epochDay
                true
            }
        },
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
                (88f * (day.playedMs.toDouble() / maxDuration).toFloat()).coerceAtLeast(3f)
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
                                isSelected -> accentInk
                                day.playedMs == 0L -> MaterialTheme.colorScheme.outlineVariant
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
    if (selectedDay != null) {
        Text(
            text = selectedSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (days.none { it.plays > 0 }) StatsNote(stringResource(Res.string.stats_recent_empty))
}

@Composable
private fun HourBars(playsByHour: List<Int>, accentInk: Color) {
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
                        color = if (plays == peakPlays) accentInk
                        else accentInk.copy(alpha = 0.5f),
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
    subtitle: String? = null,
    artworkUri: String? = null,
    showArtwork: Boolean = false,
) {
    val listeningSummary = pluralStringResource(Res.plurals.privacy_history_listens, plays, plays) +
        " · " + formatListenedTime(playedMs)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)
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
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
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
        shape = RoundedCornerShape(16.dp),
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
            Text(percent(share), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
