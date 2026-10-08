/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.SongSortDirection

data class GroupSortOrders(
    val artists: SongSortDirection = SongSortDirection.ASCENDING,
    val genres: SongSortDirection = SongSortDirection.ASCENDING,
    val folders: SongSortDirection = SongSortDirection.ASCENDING,
)

internal fun encodeGroupSortOrders(value: GroupSortOrders): String =
    listOf(value.artists, value.genres, value.folders).joinToString(",") { it.name }

internal fun groupSortOrdersFromPersisted(value: String?): GroupSortOrders {
    val parts = value?.split(',')?.takeIf { it.size == 3 } ?: return GroupSortOrders()
    val directions = parts.map { name -> SongSortDirection.entries.firstOrNull { it.name == name } ?: return GroupSortOrders() }
    return GroupSortOrders(directions[0], directions[1], directions[2])
}

/** Catalog groups are already alphabetic; reversing keeps untagged entries at the end. */
internal fun <T> reverseNamedGroups(groups: List<T>, direction: SongSortDirection, name: (T) -> String?): List<T> =
    if (direction == SongSortDirection.ASCENDING) groups else {
        val (named, unknown) = groups.partition { !name(it).isNullOrBlank() }
        named.asReversed() + unknown
    }

internal fun LibraryCatalog.inGroupOrder(order: GroupSortOrders): LibraryCatalog = copy(
    artists = reverseNamedGroups(artists, order.artists) { it.name },
    genres = reverseNamedGroups(genres, order.genres) { it.name },
    folders = reverseNamedGroups(folders, order.folders) { it.name },
)
