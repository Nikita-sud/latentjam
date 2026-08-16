/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_clear
import io.github.nikitasud.latentjam.app.generated.resources.cd_clear_query
import io.github.nikitasud.latentjam.app.generated.resources.cd_close_search
import io.github.nikitasud.latentjam.app.generated.resources.cd_forget_search
import io.github.nikitasud.latentjam.app.generated.resources.search_hint_count
import io.github.nikitasud.latentjam.app.generated.resources.search_no_matches
import io.github.nikitasud.latentjam.app.generated.resources.search_placeholder
import io.github.nikitasud.latentjam.app.generated.resources.search_recent_title
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.smart.ScoredTrack
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import io.github.nikitasud.latentjam.smart.text.SearchFold
import kotlin.math.pow
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen library search: auto-focused field, live filtering over
 * title / artist / album, results playable in place (the result list becomes
 * the queue).
 *
 * With an empty field it shows what you searched for before, so returning to
 * search offers a way back into a previous thread instead of a blank page.
 * Each entry can be dismissed individually — a history you can't prune stops
 * being useful.
 */
@Composable
internal fun SearchScreen(
    songs: List<TrackDescriptor>,
    currentTrackId: TrackId?,
    /** Whether the player is audibly running; animates the current row's badge. */
    currentTrackPlaying: Boolean = false,
    selectedTrackIds: Set<TrackId> = emptySet(),
    onToggleSelection: (TrackDescriptor) -> Unit = {},
    onStartSelection: (TrackDescriptor) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onPlay: (queue: List<TrackDescriptor>, index: Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onClose: () -> Unit,
    /** Room at the foot of the results for the mini-player floating over this screen. */
    bottomInset: Dp = 0.dp,
) {
    // The same long-press promise as every other track list: back leaves selection first.
    val selectionMode = selectedTrackIds.isNotEmpty()
    PlatformBackHandler(enabled = true) {
        if (selectionMode) onClearSelection() else onClose()
    }

    val scope = rememberCoroutineScope()
    // Search lives inside the browse stack, which leaves composition during the player morph.
    // Save the text alongside its result-list scroll position so returning never becomes a new
    // search session merely because the listener checked Now Playing.
    var query by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var semantic by remember(songs) { mutableStateOf<List<ScoredTrack>>(emptyList()) }
    var semanticQuery by remember(songs) { mutableStateOf("") }
    var searchIndex by remember(songs) { mutableStateOf<SearchIndex?>(null) }
    var results by remember(songs) { mutableStateOf<List<TrackDescriptor>>(emptyList()) }
    var resultQuery by remember(songs) { mutableStateOf("") }
    var isSearching by remember(songs) { mutableStateOf(false) }
    var trackStats by remember { mutableStateOf<Map<TrackId, TrackStats>>(emptyMap()) }
    // A slower read from an older action must not replace the result of a newer record/remove/clear.
    var recentOperationRevision by remember { mutableLongStateOf(0L) }
    val entityResolver = AppGraph.musicEntities
    val rememberSearches by AppGraph.settings.rememberSearches.collectAsState()
    val historyRevision by AppGraph.historyRevision.collectAsState()

    // Normalize/tokenize the library once, off the UI thread. The old path rebuilt all fields,
    // tokens and edit-distance inputs synchronously in composition for every typed character.
    LaunchedEffect(songs) {
        searchIndex = withContext(Dispatchers.Default) { SearchIndex.build(songs) }
    }
    // Per-track play aggregates for affinity ordering; reloaded whenever the log gains an event.
    LaunchedEffect(historyRevision) {
        val loaded = try {
            withContext(Dispatchers.Default) { AppGraph.history.stats() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Affinity is an enhancement. Keep the last good ordering if its local file is
            // temporarily unreadable instead of letting a persistence exception kill Search.
            println("Search: could not load listening affinity: $failure")
            null
        }
        if (loaded != null) trackStats = loaded
    }
    LaunchedEffect(searchIndex, query, semantic, semanticQuery, entityResolver, trackStats) {
        val needle = query.trim()
        val index = searchIndex
        if (needle.isBlank()) {
            isSearching = false
            results = emptyList()
            resultQuery = needle
        } else if (index == null) {
            isSearching = true
            results = emptyList()
            resultQuery = ""
        } else {
            isSearching = true
            val matchingSemantic = semantic.takeIf { semanticQuery == needle }.orEmpty()
            val nowMs = epochMillis()
            val calculated = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                hybridSearch(
                    index = index,
                    query = needle,
                    semantic = matchingSemantic,
                    entityResolver = entityResolver,
                    stats = trackStats,
                    nowMs = nowMs,
                ) {
                    context.ensureActive()
                }
            }
            results = calculated
            resultQuery = needle
            isSearching = false
        }
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val reduceMotion = rememberReduceMotion()

    suspend fun refreshRecent(expectedRevision: Long = recentOperationRevision) {
        val loaded = try {
            AppGraph.recentSearches.recent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The last successfully rendered shortcuts remain usable. Search itself does not own a
            // snackbar surface, so report diagnostically without replacing them with a false empty.
            println("Search: could not load recent searches: $failure")
            null
        }
        if (loaded != null && recentOperationRevision == expectedRevision) recent = loaded
    }

    LaunchedEffect(Unit) {
        refreshRecent(recentOperationRevision)
        focusRequester.requestFocus()
    }

    // Encoder inference is local and fast, but still unnecessary on every keypress. Exact metadata
    // matches update immediately above; semantic expansion follows after the user pauses typing.
    LaunchedEffect(songs, query) {
        semantic = emptyList()
        semanticQuery = ""
        val needle = query.trim()
        if (needle.length < MIN_SEMANTIC_QUERY_CHARS) return@LaunchedEffect
        delay(SEMANTIC_DEBOUNCE_MS)
        val started = TimeSource.Monotonic.markNow()
        semantic = AppGraph.engine.semanticSearch(needle, SEMANTIC_CANDIDATE_LIMIT)
        semanticQuery = needle
        println(
            "SMART search: ${semantic.size} local candidates in " +
                "${started.elapsedNow().inWholeMilliseconds} ms",
        )
    }

    fun remember(queryToKeep: String) {
        if (!rememberSearches) return
        val operationRevision = ++recentOperationRevision
        scope.launch {
            try {
                AppGraph.recentSearches.record(queryToKeep)
                refreshRecent(operationRevision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // A failed write publishes no in-memory store change; leave the visible list alone.
                println("Search: could not record recent search: $failure")
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.cd_close_search),
                    )
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.search_placeholder),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    textStyle = MaterialTheme.typography.titleMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            remember(query)
                        },
                    ),
                    trailingIcon = {
                        AnimatedContent(
                            targetState = query.isNotEmpty(),
                            transitionSpec = { motionIconTransform(reduceMotion) },
                            label = "clear-query",
                        ) { showClear ->
                            // Keep both animation states the same size: the field text should not
                            // slide sideways when the affordance appears or disappears.
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (showClear) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription =
                                                stringResource(Res.string.cd_clear_query),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    // A bare field: the row itself is the search affordance, so
                    // a filled box and underline would only add furniture.
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }

            val trimmedQuery = query.trim()
            val searchPending = query.isNotBlank() &&
                (isSearching || resultQuery != trimmedQuery)
            var showSearchProgress by remember { mutableStateOf(false) }
            LaunchedEffect(searchPending, trimmedQuery) {
                showSearchProgress = false
                if (searchPending) {
                    // Most local metadata searches finish within a frame. Only disclose work that
                    // lasts long enough to be perceived, otherwise the spinner itself is flicker.
                    delay(SEARCH_PROGRESS_DELAY_MS)
                    showSearchProgress = true
                }
            }
            val contentMode = when {
                // Preserve the last settled answer while a replacement is calculated. Its actions
                // are freshness-gated below, and a thin progress line communicates the refresh.
                searchPending && resultQuery.isNotBlank() -> if (results.isEmpty()) {
                    SearchContentMode.NoMatches
                } else {
                    SearchContentMode.Results
                }
                searchPending && showSearchProgress -> SearchContentMode.Loading
                searchPending && recent.isNotEmpty() -> SearchContentMode.Recent
                searchPending -> SearchContentMode.Blank
                query.isNotBlank() && results.isEmpty() -> SearchContentMode.NoMatches
                query.isNotBlank() -> SearchContentMode.Results
                recent.isNotEmpty() -> SearchContentMode.Recent
                else -> SearchContentMode.Blank
            }
            // The mode is deliberately the animation key. Results can be recomputed or reordered
            // many times while somebody types; those updates stay immediate instead of repeatedly
            // fading the whole list. Only a meaningful content-state boundary gets motion.
            val presentation = SearchPresentation(
                mode = contentMode,
                query = if (searchPending && resultQuery.isNotBlank()) {
                    resultQuery
                } else {
                    trimmedQuery
                },
                results = results,
                recent = recent,
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AnimatedContent(
                    targetState = presentation,
                    contentKey = { it.mode },
                    transitionSpec = { motionFadeThrough(reduceMotion) },
                    modifier = Modifier.fillMaxSize(),
                    label = "search-content-mode",
                ) { shown ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .inactiveForMotion(shown.mode != presentation.mode),
                ) {
                when (shown.mode) {
                    SearchContentMode.Loading -> SearchLoading()

                    SearchContentMode.NoMatches ->
                        CenteredHint(stringResource(Res.string.search_no_matches, shown.query))

                    SearchContentMode.Results -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomInset),
                    ) {
                        // Do not use animateItem here: search rank is allowed to change while the
                        // query and semantic expansion settle, and rows chasing their old position
                        // would make typing feel unstable.
                        itemsIndexed(
                            shown.results,
                            key = { _, track -> track.id.value },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                isCurrent = track.id == currentTrackId,
                                isPlaying = currentTrackPlaying,
                                highlightQuery = shown.query,
                                onClick = {
                                    // A pending query deliberately keeps the settled snapshot
                                    // usable. The visible row therefore plays the visible queue,
                                    // rather than accepting a tap that silently does nothing.
                                    if (selectionMode) {
                                        onToggleSelection(track)
                                    } else {
                                        remember(shown.query)
                                        AppGraph.queueSource.value = QueueSource(
                                            QueueSourceKind.SEARCH,
                                            shown.query.takeIf { it.isNotEmpty() },
                                        )
                                        onPlay(shown.results, index)
                                    }
                                },
                                onLongClick = {
                                    if (selectionMode) {
                                        onToggleSelection(track)
                                    } else {
                                        onStartSelection(track)
                                    }
                                },
                                selectionState =
                                    if (selectionMode) track.id in selectedTrackIds else null,
                                onMenu = if (selectionMode) {
                                    null
                                } else {
                                    { onTrackMenu(track) }
                                },
                            )
                        }
                    }

                    SearchContentMode.Recent -> RecentSearchList(
                        recent = shown.recent,
                        onPick = { picked ->
                            query = picked
                            focusManager.clearFocus()
                            remember(picked)
                        },
                        onRemove = { removed ->
                            val operationRevision = ++recentOperationRevision
                            scope.launch {
                                try {
                                    AppGraph.recentSearches.remove(removed)
                                    refreshRecent(operationRevision)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    println("Search: could not remove recent search: $failure")
                                }
                            }
                        },
                        onClearAll = {
                            val operationRevision = ++recentOperationRevision
                            scope.launch {
                                try {
                                    AppGraph.recentSearches.clear()
                                    refreshRecent(operationRevision)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    println("Search: could not clear recent searches: $failure")
                                }
                            }
                        },
                    )

                    SearchContentMode.Blank -> CenteredHint(
                        pluralStringResource(
                            Res.plurals.search_hint_count,
                            songs.size,
                            songs.size,
                        ),
                    )
                }
                }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = searchPending && showSearchProgress && resultQuery.isNotBlank(),
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = androidx.compose.animation.fadeIn(tween(
                        if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS,
                    )),
                    exit = androidx.compose.animation.fadeOut(tween(
                        if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS,
                    )),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
            }
        }
    }
}

private enum class SearchContentMode {
    Blank,
    Recent,
    Loading,
    NoMatches,
    Results,
}

/** Payload retained with an outgoing mode so its exit never recomposes into empty content. */
private data class SearchPresentation(
    val mode: SearchContentMode,
    val query: String,
    val results: List<TrackDescriptor>,
    val recent: List<String>,
)

@Composable
private fun RecentSearchList(
    recent: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.search_recent_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearAll) { Text(stringResource(Res.string.action_clear)) }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            LazyColumn {
                items(recent, key = { it }) { entry ->
                    Row(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = tween(
                                    if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                                ),
                                placementSpec = if (reduceMotion) {
                                    null
                                } else {
                                    tween(Motion.APPEAR_MS)
                                },
                                fadeOutSpec = tween(
                                    if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS,
                                ),
                            )
                            .fillMaxWidth()
                            .clickable { onPick(entry) }
                            .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        IconButton(onClick = { onRemove(entry) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(Res.string.cd_forget_search, entry),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Exact search stays authoritative; cosine hits only expand it when the model is confident. */
internal fun hybridSearch(
    songs: List<TrackDescriptor>,
    query: String,
    semantic: List<ScoredTrack>,
    entityResolver: MusicEntityResolver? = null,
    stats: Map<TrackId, TrackStats> = emptyMap(),
    nowMs: Long = 0L,
): List<TrackDescriptor> = hybridSearch(
    index = SearchIndex.build(songs),
    query = query,
    semantic = semantic,
    entityResolver = entityResolver,
    stats = stats,
    nowMs = nowMs,
)

private fun hybridSearch(
    index: SearchIndex,
    query: String,
    semantic: List<ScoredTrack>,
    entityResolver: MusicEntityResolver?,
    stats: Map<TrackId, TrackStats> = emptyMap(),
    nowMs: Long = 0L,
    checkCancelled: () -> Unit = {},
): List<TrackDescriptor> {
    val needle = query.trim()
    val normalizedNeedle = normalizeSearchText(needle)
    if (normalizedNeedle.isEmpty()) return emptyList()
    val queryTokens = searchTokens(normalizedNeedle)
    // Within a lexical-rank tier, order by play affinity (plays × recency decay) then name — a
    // frequently/recently played match floats to the top of its group. The rank itself keeps the
    // exact/prefix tiers pinned above deeper substring/fuzzy matches, so an exact title is never
    // buried under a more-played substring hit.
    val lexical = index.documents.mapIndexedNotNull { documentIndex, document ->
        if (documentIndex % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        document.lexicalRank(normalizedNeedle, queryTokens)?.let { rank ->
            val stat = stats[document.track.id]
            RankedTrack(
                track = document.track,
                rank = rank,
                affinity = SearchAffinity.affinity(stat?.plays ?: 0, stat?.lastPlayedAtMs ?: 0L, nowMs),
                nameKey = document.fields.firstOrNull { it != null } ?: "",
                libraryOrder = document.libraryOrder,
            )
        }
    }.sortedWith(
        compareBy<RankedTrack> { it.rank }
            .thenByDescending { it.affinity }
            .thenBy { it.nameKey }
            .thenBy { it.libraryOrder },
    )

    val used = lexical.mapTo(HashSet()) { it.track.id }
    val entities = entityResolver?.let { resolver ->
        index.songs.asSequence()
            .onEach { checkCancelled() }
            .filter { it.id !in used && resolver.matches(needle, it.artist) }
            .onEach { used += it.id }
    }.orEmpty()
    val expanded = SemanticGate.gate(semantic).asSequence()
        .onEach { checkCancelled() }
        .mapNotNull { index.byId[it.trackId] }
        .filter { used.add(it.id) }
        .take(SEMANTIC_RESULT_LIMIT)

    return (lexical.asSequence().map { it.track } + entities + expanded)
        .take(SEARCH_RESULT_LIMIT)
        .toList()
}

/**
 * Confidence gate for the semantic ("Sounds like") tier, ported from the validated absolute rule:
 * show the section only when `cos_top1 >= MIN_TOP1` AND `cos_top1 − mean(cos[BG_LO:BG_HI]) >=
 * MIN_MARGIN`. The margin against the deep background band — not the near neighbours — is what kills
 * diffuse "blob" false positives (a diffuse match has a high background mean; a real cluster sits
 * far above its tail). With fewer than [BG_HI] scored candidates the band can't be measured, so the
 * section declines rather than guess. [ranked] must be sorted by score descending (as the engine
 * returns it); when the gate fires the top [TOP_K] rows are shown.
 */
internal object SemanticGate {
    const val MIN_TOP1 = 0.45f
    const val MIN_MARGIN = 0.22f
    const val BG_LO = 50
    const val BG_HI = 150
    const val TOP_K = 10

    fun gate(ranked: List<ScoredTrack>): List<ScoredTrack> {
        if (ranked.size < BG_HI) return emptyList()
        val top1 = ranked[0].score
        var bgSum = 0.0
        for (i in BG_LO until BG_HI) bgSum += ranked[i].score
        val bgMean = (bgSum / (BG_HI - BG_LO)).toFloat()
        if (top1 < MIN_TOP1 || top1 - bgMean < MIN_MARGIN) return emptyList()
        return ranked.take(TOP_K)
    }
}

/**
 * Affinity for a lexical result group: `plays × 2^(−ageDays / HALF_LIFE_DAYS)`. Recent plays count
 * for more, and a never-played track scores exactly 0 so unheard matches keep their alphabetical
 * order among themselves.
 */
internal object SearchAffinity {
    const val HALF_LIFE_DAYS = 30.0
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun affinity(plays: Int, lastPlayedAtMs: Long, nowMs: Long): Double {
        if (plays <= 0) return 0.0
        val ageDays = ((nowMs - lastPlayedAtMs).coerceAtLeast(0L)).toDouble() / DAY_MS
        return plays * 2.0.pow(-ageDays / HALF_LIFE_DAYS)
    }
}

/**
 * Field-major rank: WHERE the query matched outranks HOW well it matched. A query in the TITLE
 * beats any artist/album match, which beats a genre match — someone typing "young" wants
 * "Forever Young" before the discography of an artist named Young. Within a field tier the
 * match quality orders as before: 0 exact, 1 prefix, 2 token-prefix, 3 word-family/typo,
 * 4 substring. Smaller is better; null is no match anywhere.
 */
private fun SearchDocument.lexicalRank(
    normalizedNeedle: String,
    queryTokens: List<String>,
): Int? {
    var best: Int? = null
    for (fieldIndex in fields.indices) {
        val field = fields[fieldIndex] ?: continue
        val quality = fieldQuality(field, fieldTokens[fieldIndex], normalizedNeedle, queryTokens)
            ?: continue
        val rank = FIELD_TIERS[fieldIndex] * QUALITY_SPAN + quality
        if (best == null || rank < best) best = rank
    }
    return best
}

/** Title first; artist and album share a tier ("or" in the spec is deliberate); genre last. */
private val FIELD_TIERS = intArrayOf(0, 1, 1, 2)
private const val QUALITY_SPAN = 5

private fun fieldQuality(
    field: String,
    tokens: List<String>,
    normalizedNeedle: String,
    queryTokens: List<String>,
): Int? {
    if (field == normalizedNeedle) return 0
    if (field.startsWith(normalizedNeedle)) return 1
    if (tokens.any { it.startsWith(normalizedNeedle) }) return 2
    if (queryTokens.isNotEmpty() &&
        queryTokens.all { queryToken ->
            tokens.any { fieldToken -> relatedSearchTokens(queryToken, fieldToken) }
        }
    ) {
        return 3
    }
    if (field.contains(normalizedNeedle)) return 4
    return null
}

// Fold first (NFD diacritic strip + Cyrillic→Latin transliteration, applied symmetrically to the
// index and the query), then collapse the folded text to single-spaced alphanumeric tokens. The
// fold is what lets a Latin-keyboard query reach non-Latin metadata; the collapse keeps the tokens
// and edit-distance inputs the fuzzy matcher already expects.
private fun normalizeSearchText(value: String): String = buildString(value.length) {
    var previousSpace = true
    SearchFold.fold(value).forEach { character ->
        when {
            character.isLetterOrDigit() -> {
                append(character)
                previousSpace = false
            }
            !previousSpace -> {
                append(' ')
                previousSpace = true
            }
        }
    }
    if (isNotEmpty() && last() == ' ') setLength(length - 1)
}

private fun searchTokens(value: String): List<String> =
    value.split(' ').filter { it.isNotBlank() }

private fun relatedSearchTokens(left: String, right: String): Boolean {
    if (left == right || left.startsWith(right) || right.startsWith(left)) return true
    if (left.length < 4 || right.length < 4 || left.first() != right.first()) return false
    val allowedEdits = if (maxOf(left.length, right.length) >= 8) 2 else 1
    return editDistanceAtMost(left, right, allowedEdits)
}

private fun editDistanceAtMost(left: String, right: String, limit: Int): Boolean {
    if (kotlin.math.abs(left.length - right.length) > limit) return false
    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        var rowMinimum = current[0]
        for (rightIndex in right.indices) {
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1,
            )
            rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
        }
        if (rowMinimum > limit) return false
        previous = current
    }
    return previous[right.length] <= limit
}

private data class RankedTrack(
    val track: TrackDescriptor,
    val rank: Int,
    val affinity: Double,
    val nameKey: String,
    val libraryOrder: Int,
)

private data class SearchDocument(
    val track: TrackDescriptor,
    val fields: List<String?>,
    val fieldTokens: List<List<String>>,
    val libraryOrder: Int,
)

private class SearchIndex private constructor(
    val songs: List<TrackDescriptor>,
    val documents: List<SearchDocument>,
    val byId: Map<TrackId, TrackDescriptor>,
) {
    companion object {
        fun build(songs: List<TrackDescriptor>): SearchIndex {
            val documents = songs.mapIndexed { index, track ->
                // POSITIONAL on purpose: index 0 is always the title, 1 the artist, 2 the album,
                // 3 the genre — the rank is field-major and must know which field matched.
                val fields = listOf(track.title, track.artist, track.album, track.genre)
                    .map { value -> value?.let(::normalizeSearchText)?.takeIf { it.isNotEmpty() } }
                SearchDocument(
                    track = track,
                    fields = fields,
                    fieldTokens = fields.map { it?.let(::searchTokens).orEmpty() },
                    libraryOrder = index,
                )
            }
            return SearchIndex(songs, documents, songs.associateBy { it.id })
        }
    }
}

private const val MIN_SEMANTIC_QUERY_CHARS = 2
private const val SEMANTIC_DEBOUNCE_MS = 180L

/** Avoid flashing progress UI for local searches that complete before somebody can perceive it. */
private const val SEARCH_PROGRESS_DELAY_MS = 140L
// The confidence gate measures a background band up to rank [SemanticGate.BG_HI]; fetch enough
// candidates to fill it, or the section always declines. 200 gives the band real headroom.
private const val SEMANTIC_CANDIDATE_LIMIT = 200
private const val SEMANTIC_RESULT_LIMIT = 24
private const val SEARCH_RESULT_LIMIT = 80
private const val CANCELLATION_CHECK_INTERVAL = 32
