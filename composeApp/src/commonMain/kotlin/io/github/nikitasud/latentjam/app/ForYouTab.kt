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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.smart.TrackDescriptor

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
    sections: List<ForYouSection>,
    contentPadding: PaddingValues,
    onPlay: (List<TrackDescriptor>, Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
) {
    if (sections.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Play a few tracks and this page will fill up with things worth returning to.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(sections, key = { it.id }) { section ->
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                items(section.cards, key = { it.track.id.value }) { card ->
                    ForYouCardItem(
                        card = card,
                        onClick = {
                            val tracks = section.cards.map { it.track }
                            onPlay(tracks, tracks.indexOfFirst { it.id == card.track.id })
                        },
                        onLongClick = { onTrackMenu(card.track) },
                    )
                }
            }
        }
    }
}

/**
 * A card is mostly its cover.
 *
 * Row titles are read about a quarter as often as the leftmost item is looked at, so the artwork
 * carries the row and the text underneath is support. Both text slots are fixed at one line so cards
 * keep a common baseline whatever the title length — ragged card heights were a recurring complaint
 * in the previous implementation, and a fixed slot is the fix rather than ellipsizing after layout.
 */
@Composable
private fun ForYouCardItem(
    card: ForYouCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(140.dp).clickable(onClick = onClick),
    ) {
        Artwork(uri = card.track.artworkUri, size = 140.dp, cornerRadius = 12.dp)
        Text(
            text = card.track.title ?: "Untitled",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            // The caption replaces the artist where there is one, because "10× before" is the reason
            // this card is here and the artist is already legible from the cover.
            text = card.reason ?: card.track.artist ?: "Unknown artist",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
