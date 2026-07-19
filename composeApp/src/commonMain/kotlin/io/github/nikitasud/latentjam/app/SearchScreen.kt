/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.launch
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
    onPlay: (queue: List<TrackDescriptor>, index: Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onClose: () -> Unit,
    /** Room at the foot of the results for the mini-player floating over this screen. */
    bottomInset: Dp = 0.dp,
) {
    PlatformBackHandler(enabled = true, onBack = onClose)

    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    val results = remember(songs, query) {
        val needle = query.trim()
        if (needle.isBlank()) emptyList() else songs.filter { it.matches(needle) }
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    suspend fun refreshRecent() {
        recent = AppGraph.recentSearches.recent()
    }

    LaunchedEffect(Unit) {
        refreshRecent()
        focusRequester.requestFocus()
    }

    fun remember(queryToKeep: String) {
        scope.launch {
            AppGraph.recentSearches.record(queryToKeep)
            refreshRecent()
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
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(Res.string.cd_clear_query),
                                )
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

            when {
                query.isNotBlank() && results.isEmpty() ->
                    CenteredHint(stringResource(Res.string.search_no_matches, query.trim()))

                query.isNotBlank() -> LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomInset),
                ) {
                    itemsIndexed(results, key = { _, track -> track.id.value }) { index, track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            onClick = {
                                remember(query)
                                onPlay(results, index)
                            },
                            onMenu = { onTrackMenu(track) },
                        )
                    }
                }

                recent.isNotEmpty() -> RecentSearchList(
                    recent = recent,
                    onPick = { picked ->
                        query = picked
                        focusManager.clearFocus()
                        remember(picked)
                    },
                    onRemove = { removed ->
                        scope.launch {
                            AppGraph.recentSearches.remove(removed)
                            refreshRecent()
                        }
                    },
                    onClearAll = {
                        scope.launch {
                            AppGraph.recentSearches.clear()
                            refreshRecent()
                        }
                    },
                )

                else -> CenteredHint(pluralStringResource(Res.plurals.search_hint_count, songs.size, songs.size))
            }
        }
    }
}

@Composable
private fun RecentSearchList(
    recent: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
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

private fun TrackDescriptor.matches(needle: String): Boolean =
    title?.contains(needle, ignoreCase = true) == true ||
        artist?.contains(needle, ignoreCase = true) == true ||
        album?.contains(needle, ignoreCase = true) == true
