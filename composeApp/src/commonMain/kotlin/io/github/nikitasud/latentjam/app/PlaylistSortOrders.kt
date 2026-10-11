/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AutoPlaylist
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.library.PlaylistSorting
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.defaultDirection
import io.github.nikitasud.latentjam.library.hasDirection
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/** A user playlist's page offers every order; Custom order, the playlist's own, is its default. */
internal val PLAYLIST_TRACK_SORTS: List<PlaylistSort> = PlaylistSort.entries.toList()

/** Favorites have no order of their own beyond when each heart was given, so no Custom order. */
internal val FAVORITES_TRACK_SORTS: List<PlaylistSort> = PlaylistSort.entries.filter { it != PlaylistSort.CUSTOM }

internal val DEFAULT_PLAYLIST_TRACK_SORT: SortChoice<PlaylistSort> =
    SortChoice(PlaylistSort.CUSTOM, PlaylistSort.CUSTOM.defaultDirection)

/** What Favorites have always shown: the newest heart on top. */
internal val DEFAULT_FAVORITES_TRACK_SORT: SortChoice<PlaylistSort> =
    SortChoice(PlaylistSort.RECENT, SongSortDirection.DESCENDING)

/** The route Favorites open under, from the Playlists tab and from "Playing from" alike. */
internal val FAVORITES_ROUTE_ID: String = "auto:${AutoPlaylistKind.FAVORITES.name}"

/**
 * How each user playlist's page and Favorites order their tracks. A view of the stored order only:
 * a playlist keeps its own order whatever its page shows, so Custom order always brings it back.
 *
 * Device-local, like a scroll position: local backups (format 8) carry the tab orders but not
 * these, because a backup with a record 0.8.0 does not know would be refused by 0.8.0.
 */
data class PlaylistSortOrders(
    val favorites: SortChoice<PlaylistSort> = DEFAULT_FAVORITES_TRACK_SORT,
    /** Playlist id to its order. A playlist in Custom order has no entry. */
    val playlists: Map<String, SortChoice<PlaylistSort>> = emptyMap(),
) {
    fun forPlaylist(id: String): SortChoice<PlaylistSort> = playlists[id] ?: DEFAULT_PLAYLIST_TRACK_SORT

    fun withPlaylist(id: String, choice: SortChoice<PlaylistSort>): PlaylistSortOrders {
        val normalized = choice.normalized()
        return copy(
            playlists = if (normalized == DEFAULT_PLAYLIST_TRACK_SORT) {
                playlists - id
            } else {
                playlists + (id to normalized)
            },
        )
    }

    /** Ignores Custom order, which Favorites do not offer. */
    fun withFavorites(choice: SortChoice<PlaylistSort>): PlaylistSortOrders =
        if (choice.sort in FAVORITES_TRACK_SORTS) copy(favorites = choice.normalized()) else this

    /** Forgets the orders of playlists that no longer exist, so a deleted playlist leaves nothing behind. */
    fun retainingPlaylists(ids: Collection<String>): PlaylistSortOrders {
        if (playlists.isEmpty()) return this
        val live = ids.toHashSet()
        val retained = playlists.filterKeys(live::contains)
        return if (retained.size == playlists.size) this else copy(playlists = retained)
    }
}

/** Custom order runs one way; storing a direction for it would only make equal choices unequal. */
private fun SortChoice<PlaylistSort>.normalized(): SortChoice<PlaylistSort> =
    if (sort.hasDirection) this else SortChoice(sort, sort.defaultDirection)

/**
 * `LJPS1|<favorites>|` then `<length>:<id>=<field>:<direction>;` per playlist. Ids are opaque, so each
 * is length-prefixed rather than escaped.
 */
internal fun encodePlaylistSortOrders(value: PlaylistSortOrders): String = buildString {
    append(PLAYLIST_SORT_ORDERS_PREFIX)
    append(encodeSortChoice(value.favorites))
    append('|')
    value.playlists.entries.sortedBy { it.key }.forEach { (id, choice) ->
        append(id.length)
        append(':')
        append(id)
        append('=')
        append(encodeSortChoice(choice))
        append(';')
    }
}

/**
 * A damaged value reads as the defaults: an order is never worth failing a launch over. A field a
 * later build added (or one Favorites do not offer) drops just that page back to its default.
 */
internal fun playlistSortOrdersFromPersisted(value: String?): PlaylistSortOrders {
    if (value == null || value.length > MAX_PLAYLIST_SORT_ORDERS_CHARS) return PlaylistSortOrders()
    if (!value.startsWith(PLAYLIST_SORT_ORDERS_PREFIX)) return PlaylistSortOrders()
    var offset = PLAYLIST_SORT_ORDERS_PREFIX.length
    val favoritesEnd = value.indexOf('|', offset).takeIf { it >= 0 } ?: return PlaylistSortOrders()
    val favorites = decodeSortChoice(
        value.substring(offset, favoritesEnd),
        FAVORITES_TRACK_SORTS,
        DEFAULT_FAVORITES_TRACK_SORT,
    ) { it.defaultDirection }
    offset = favoritesEnd + 1
    val playlists = LinkedHashMap<String, SortChoice<PlaylistSort>>()
    while (offset < value.length) {
        val colon = value.indexOf(':', offset).takeIf { it >= 0 } ?: return PlaylistSortOrders()
        val length = value.substring(offset, colon).toIntOrNull()?.takeIf { it >= 0 }
            ?: return PlaylistSortOrders()
        val idStart = colon + 1
        if (length > value.length - idStart) return PlaylistSortOrders()
        val id = value.substring(idStart, idStart + length)
        if (value.getOrNull(idStart + length) != '=') return PlaylistSortOrders()
        val end = value.indexOf(';', idStart + length).takeIf { it >= 0 } ?: return PlaylistSortOrders()
        val encoded = value.substring(idStart + length + 1, end)
        offset = end + 1
        if (playlists.size >= MAX_PLAYLIST_SORT_ENTRIES) return PlaylistSortOrders()
        val choice = decodeSortChoice(encoded, PLAYLIST_TRACK_SORTS, DEFAULT_PLAYLIST_TRACK_SORT) { it.defaultDirection }
        if (id.isNotEmpty() && choice != DEFAULT_PLAYLIST_TRACK_SORT) playlists[id] = choice.normalized()
    }
    return PlaylistSortOrders(favorites, playlists)
}

private const val PLAYLIST_SORT_ORDERS_PREFIX = "LJPS1|"
private const val MAX_PLAYLIST_SORT_ENTRIES = 10_000
private const val MAX_PLAYLIST_SORT_ORDERS_CHARS = 1_000_000

/** The order this page's tracks are chosen in; null for a page with no order to choose. */
internal fun CollectionSelection.trackSortChoice(orders: PlaylistSortOrders): SortChoice<PlaylistSort>? = when {
    playlistId != null -> orders.forPlaylist(playlistId)
    routeId == FAVORITES_ROUTE_ID -> orders.favorites
    else -> null
}

/** The orders this page offers; meaningful only where [trackSortChoice] is not null. */
internal fun CollectionSelection.trackSortOptions(): List<PlaylistSort> =
    if (playlistId != null) PLAYLIST_TRACK_SORTS else FAVORITES_TRACK_SORTS

/**
 * The ids of this page's tracks in the order the store keeps them: the playlist's own order, or
 * Favorites newest first. Null for any other page, and while the store's order is not known.
 */
internal fun CollectionSelection.storedTrackOrder(
    playlists: List<Playlist>,
    autoPlaylists: List<AutoPlaylist>,
): List<TrackId>? = when {
    playlistId != null -> playlists.firstOrNull { it.id == playlistId }?.trackIds?.map(::TrackId)
    routeId == FAVORITES_ROUTE_ID ->
        autoPlaylists.firstOrNull { it.kind == AutoPlaylistKind.FAVORITES }?.tracks?.map(TrackDescriptor::id)
    else -> null
}

/**
 * This page in its chosen order. Every way a playlist or Favorites page is opened or refreshed goes
 * through here, and play, shuffle, SMART, the selection's play order and the queue's source all read
 * [CollectionSelection.tracks]: what is played is always what is shown.
 *
 * [storedOrder] is the store's order (see [storedTrackOrder]), from which Custom order and Recently
 * added are read: a page shown A–Z no longer knows it. Tracks it does not name keep their place
 * after the rest. The same instance comes back when nothing changes.
 */
internal fun CollectionSelection.inChosenOrder(
    orders: PlaylistSortOrders,
    storedOrder: List<TrackId>?,
): CollectionSelection {
    val choice = trackSortChoice(orders) ?: return this
    // A playlist or Favorites page is one flat list; sections would need re-sorting each of them.
    if (sections != null) return this
    val stored = inStoredOrder(tracks, storedOrder)
    val sorted = PlaylistSorting.sort(
        stored = stored,
        sort = choice.sort,
        direction = choice.direction,
        storedNewestFirst = playlistId == null,
    )
    val unchanged = sorted.size == tracks.size && sorted.indices.all { sorted[it] === tracks[it] }
    // Only an A–Z title order gets the letter rail, the rule every other track list follows.
    val rail = if (choice.sort == PlaylistSort.TITLE) trackRailModeFor(sorted) else CollectionRailMode.NONE
    if (unchanged && rail == railMode && choice == trackSort) return this
    return copy(tracks = if (unchanged) tracks else sorted, railMode = rail, trackSort = choice)
}

/** [tracks] rearranged into [storedOrder]; a stable sort, so tracks it does not name keep their order, last. */
private fun inStoredOrder(tracks: List<TrackDescriptor>, storedOrder: List<TrackId>?): List<TrackDescriptor> {
    if (storedOrder == null) return tracks
    val rank = HashMap<TrackId, Int>(storedOrder.size * 2)
    storedOrder.forEachIndexed { index, id -> if (id !in rank) rank[id] = index }
    return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}

/** These orders with [choice] for the page [selection] shows; null for a page with no order to choose. */
internal fun PlaylistSortOrders.choosing(
    selection: CollectionSelection,
    choice: SortChoice<PlaylistSort>,
): PlaylistSortOrders? = when {
    selection.playlistId != null -> withPlaylist(selection.playlistId, choice)
    selection.routeId == FAVORITES_ROUTE_ID -> withFavorites(choice)
    else -> null
}

/** Remembers [choice] for the page [selection] shows; false for a page with no order to choose. */
internal fun AppSettings.chooseTrackSort(selection: CollectionSelection, choice: SortChoice<PlaylistSort>): Boolean {
    val current = playlistSortOrders.value
    val updated = current.choosing(selection, choice) ?: return false
    if (updated != current) setPlaylistSortOrders(updated)
    return true
}

/** Drops the orders of deleted playlists. Call only with the complete, loaded playlist list. */
internal fun AppSettings.forgetDeletedPlaylistOrders(playlists: List<Playlist>) {
    val current = playlistSortOrders.value
    val retained = current.retainingPlaylists(playlists.map(Playlist::id))
    if (retained !== current) setPlaylistSortOrders(retained)
}

/** A playlist's tracks in the order its page shows them, for queues built away from that page. */
internal fun Playlist.tracksInChosenOrder(
    resolved: List<TrackDescriptor>,
    orders: PlaylistSortOrders,
): List<TrackDescriptor> {
    val choice = orders.forPlaylist(id)
    return PlaylistSorting.sort(resolved, choice.sort, choice.direction, storedNewestFirst = false)
}
