/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_retry
import io.github.nikitasud.latentjam.app.generated.resources.stats_by_hour
import io.github.nikitasud.latentjam.app.generated.resources.stats_days_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_distinct_tracks
import io.github.nikitasud.latentjam.app.generated.resources.stats_empty
import io.github.nikitasud.latentjam.app.generated.resources.stats_finished_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_load_failed
import io.github.nikitasud.latentjam.app.generated.resources.stats_hours_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_minutes_short
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_all
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_month
import io.github.nikitasud.latentjam.app.generated.resources.stats_period_week
import io.github.nikitasud.latentjam.app.generated.resources.stats_plays
import io.github.nikitasud.latentjam.app.generated.resources.stats_skipped_share
import io.github.nikitasud.latentjam.app.generated.resources.stats_streak_current
import io.github.nikitasud.latentjam.app.generated.resources.stats_streak_longest
import io.github.nikitasud.latentjam.app.generated.resources.stats_time_listened
import io.github.nikitasud.latentjam.app.generated.resources.stats_top_artists
import io.github.nikitasud.latentjam.app.generated.resources.stats_top_tracks
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.ListeningOverview
import io.github.nikitasud.latentjam.history.ListeningOverviews
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private enum class StatsPeriod(val days: Int?) { WEEK(7), MONTH(30), ALL(null) }

/**
 * The listening log, summarized: how much, when in the day, whom, and the streak.
 *
 * Every figure comes from [ListeningOverviews] so the page contains wording and layout only.
 */
@Composable
internal fun ListeningStatsSettings(
    history: ListeningHistory,
    tracks: List<TrackDescriptor>,
) {
    var periodName by rememberSaveable { mutableStateOf(StatsPeriod.MONTH.name) }
    val period = StatsPeriod.entries.firstOrNull { it.name == periodName } ?: StatsPeriod.MONTH
    val historyRevision by AppGraph.historyRevision.collectAsState()
    val tracksById = remember(tracks) { tracks.associateBy { it.id } }
    var overview by remember { mutableStateOf<ListeningOverview?>(null) }
    var loadedRequest by remember { mutableStateOf<Any?>(null) }
    var failedRequest by remember { mutableStateOf<Any?>(null) }
    var retryRevision by remember { mutableIntStateOf(0) }
    // Object identity makes the rendered result belong to one exact chip/log/catalog request.
    // The old period can never appear under a newly selected chip, even for one frame.
    val request = remember(historyRevision, period, tracksById, retryRevision) { Any() }

    LaunchedEffect(request) {
        failedRequest = null
        try {
            val now = epochMillis()
            val loadContext = kotlinx.coroutines.currentCoroutineContext()
            val loaded = withContext(Dispatchers.Default) {
                ListeningOverviews.summarize(
                    events = history.allEvents(),
                    artistOf = { tracksById[it]?.artist },
                    sinceMs = period.days?.let { now - it * 24L * 60 * 60 * 1000 },
                    nowMs = now,
                    includeTrack = tracksById::containsKey,
                    cancellationCheck = { loadContext.ensureActive() },
                )
            }
            overview = loaded
            loadedRequest = request
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failedRequest = request
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            FlowRow(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
        if (loadedRequest !== request && failedRequest !== request) {
            item {
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
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.stats_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { retryRevision += 1 }) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }
            return@LazyColumn
        }
        val current = overview.takeIf { loadedRequest === request } ?: return@LazyColumn
        if (current.plays == 0) {
            item {
                Text(
                    text = stringResource(Res.string.stats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
            return@LazyColumn
        }
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Headline(
                    value = formatListenedTime(current.playedMs),
                    label = stringResource(Res.string.stats_time_listened),
                    modifier = Modifier.width(140.dp),
                )
                Headline(
                    value = current.plays.toString(),
                    label = stringResource(Res.string.stats_plays),
                    modifier = Modifier.width(140.dp),
                )
                Headline(
                    value = current.distinctTracks.toString(),
                    label = stringResource(Res.string.stats_distinct_tracks),
                    modifier = Modifier.width(140.dp),
                )
            }
        }
        item {
            val days = stringResource(Res.string.stats_days_short)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Headline(
                    value = "${current.currentStreakDays} $days",
                    label = stringResource(Res.string.stats_streak_current),
                    modifier = Modifier.width(140.dp),
                )
                Headline(
                    value = "${current.longestStreakDays} $days",
                    label = stringResource(Res.string.stats_streak_longest),
                    modifier = Modifier.width(140.dp),
                )
                Headline(
                    value = "${(current.completionRate * 100).roundToInt()} %",
                    label = stringResource(Res.string.stats_finished_share),
                    modifier = Modifier.width(140.dp),
                )
                Headline(
                    value = "${(current.skipRate * 100).roundToInt()} %",
                    label = stringResource(Res.string.stats_skipped_share),
                    modifier = Modifier.width(140.dp),
                )
            }
        }
        item {
            SectionTitle(stringResource(Res.string.stats_by_hour))
            HourBars(current.playsByHour)
        }
        if (current.topArtists.isNotEmpty()) {
            item { SectionTitle(stringResource(Res.string.stats_top_artists)) }
            items(current.topArtists.size) { index ->
                val artist = current.topArtists[index]
                RankedRow(
                    rank = index + 1,
                    title = artist.artist,
                    plays = artist.plays,
                    playedMs = artist.playedMs,
                )
            }
        }
        val visibleTopTracks = current.topTracks
        if (visibleTopTracks.isNotEmpty()) {
            item { SectionTitle(stringResource(Res.string.stats_top_tracks)) }
            items(visibleTopTracks.size) { index ->
                val entry = visibleTopTracks[index]
                val descriptor = tracksById.getValue(entry.trackId)
                RankedRow(
                    rank = index + 1,
                    title = descriptor.title ?: descriptor.artist
                        ?: stringResource(Res.string.track_unknown_artist),
                    subtitle = descriptor.title?.let { descriptor.artist },
                    plays = entry.plays,
                    playedMs = entry.playedMs,
                )
            }
        }
    }
}

private fun StatsPeriod.titleRes() = when (this) {
    StatsPeriod.WEEK -> Res.string.stats_period_week
    StatsPeriod.MONTH -> Res.string.stats_period_month
    StatsPeriod.ALL -> Res.string.stats_period_all
}

@Composable
private fun Headline(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val reduceMotion = rememberReduceMotion()
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(90)) togetherWith fadeOut(tween(60))
                } else {
                    (
                        fadeIn(tween(Motion.APPEAR_MS)) +
                            slideInVertically(tween(Motion.APPEAR_MS)) { it / 3 }
                        ) togetherWith (
                        fadeOut(tween(Motion.REPLACE_MS)) +
                            slideOutVertically(tween(Motion.REPLACE_MS)) { -it / 3 }
                        )
                }
            },
            label = "stat-headline",
        ) { shown ->
            Text(
                text = shown,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .semantics { heading() }
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun HourBars(playsByHour: List<Int>) {
    val max = playsByHour.maxOrNull()?.takeIf { it > 0 } ?: return
    val chartLabel = stringResource(Res.string.stats_by_hour)
    val playsLabel = stringResource(Res.string.stats_plays)
    val chartDescription = playsByHour.mapIndexedNotNull { hour, plays ->
        if (plays > 0) "$hour: $plays $playsLabel" else null
    }.joinToString(", ")
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .clearAndSetSemantics { contentDescription = "$chartLabel. $chartDescription" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            playsByHour.forEach { plays ->
                val share = plays.toFloat() / max
                val height by animateDpAsState(
                    targetValue = (70 * share).coerceAtLeast(if (plays > 0) 3f else 1f).dp,
                    animationSpec = tween(Motion.APPEAR_MS * 2, easing = FastOutSlowInEasing),
                    label = "hour-bar",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(height)
                        .background(
                            color = if (plays > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                        ),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18).forEach { hour ->
                Text(
                    text = hour.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle?.let { "$it · × $plays" } ?: "× $plays",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatListenedTime(playedMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun formatListenedTime(playedMs: Long): String {
    val totalMinutes = playedMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val hoursShort = stringResource(Res.string.stats_hours_short)
    val minutesShort = stringResource(Res.string.stats_minutes_short)
    return if (hours > 0) "$hours $hoursShort $minutes $minutesShort" else "$minutes $minutesShort"
}
