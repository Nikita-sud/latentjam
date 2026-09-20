/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.tab_for_you
import io.github.nikitasud.latentjam.app.generated.resources.tab_map
import io.github.nikitasud.latentjam.app.generated.resources.tab_tracks
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.FolderGroup
import io.github.nikitasud.latentjam.library.GenreGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import org.jetbrains.compose.resources.StringResource

/**
 * The surface a queue was started from. Persisted by name, so a value saved by a build with
 * different kinds degrades to "no label" instead of crashing the restore.
 */
enum class QueueSourceKind { COLLECTION, TRACKS, SEARCH, MAP, FOR_YOU, LIBRARY_GROUP }

/**
 * What the current queue was started from; drives the player's "Playing from" line.
 *
 * [name] carries the display name when the source has one of its own — a collection title, a
 * search query. Kinds without a natural name fall back to their surface's label via
 * [fallbackLabelRes]. [reference] is a user playlist id for COLLECTION or a kind-qualified
 * collection route for LIBRARY_GROUP. Keeping those kinds distinct preserves playlist resume
 * compatibility and prevents same-named albums, artists, genres or folders from being confused.
 */
data class QueueSource(
    val kind: QueueSourceKind,
    val name: String? = null,
    val reference: String? = null,
)

/** Surface label for sources without a name of their own; null means "show nothing". */
internal fun QueueSourceKind.fallbackLabelRes(): StringResource? = when (this) {
    QueueSourceKind.TRACKS -> Res.string.tab_tracks
    QueueSourceKind.MAP -> Res.string.tab_map
    QueueSourceKind.FOR_YOU -> Res.string.tab_for_you
    // These always carry a name; a nameless one has nothing honest to show.
    QueueSourceKind.COLLECTION, QueueSourceKind.SEARCH, QueueSourceKind.LIBRARY_GROUP -> null
}

/** Persist the collection's identity at playback time, before the browsing surface disappears. */
internal fun CollectionSelection.queueSource(): QueueSource = when {
    playlistId != null -> QueueSource(QueueSourceKind.COLLECTION, title, playlistId)
    routeId.substringBefore(':') in LIBRARY_GROUP_PREFIXES ->
        QueueSource(QueueSourceKind.LIBRARY_GROUP, title, routeId)
    else -> QueueSource(QueueSourceKind.COLLECTION, title)
}

private val LIBRARY_GROUP_PREFIXES = setOf("album", "artist", "genre", "folder", "auto")

/** An auto playlist is identified by its kind, never by its localized title. */
internal fun QueueSource.autoPlaylistKind(): AutoPlaylistKind? {
    if (kind != QueueSourceKind.LIBRARY_GROUP) return null
    val route = reference ?: return null
    if (route.substringBefore(':') != "auto") return null
    val name = route.substringAfter(':')
    return AutoPlaylistKind.entries.firstOrNull { it.name == name }
}

internal sealed interface QueueSourceGroup {
    data class Album(val group: AlbumGroup) : QueueSourceGroup
    data class Artist(val group: ArtistGroup) : QueueSourceGroup
    data class Genre(val group: GenreGroup) : QueueSourceGroup
    data class Folder(val group: FolderGroup) : QueueSourceGroup
}

/** Missing and legacy identities fall back to the queue, never to a similarly named place. */
internal fun QueueSource.resolveGroup(catalog: LibraryCatalog?): QueueSourceGroup? {
    if (kind != QueueSourceKind.LIBRARY_GROUP || catalog == null) return null
    val route = reference ?: return null
    return when (route.substringBefore(':')) {
        "album" -> catalog.albums.firstOrNull { "album:${it.key}" == route }
            ?.let(QueueSourceGroup::Album)
        "artist" -> catalog.artists.firstOrNull { "artist:${it.name.orEmpty()}" == route }
            ?.let(QueueSourceGroup::Artist)
        "genre" -> catalog.genres.firstOrNull { "genre:${it.name.orEmpty()}" == route }
            ?.let(QueueSourceGroup::Genre)
        "folder" -> catalog.folders.firstOrNull { "folder:${it.path}" == route }
            ?.let(QueueSourceGroup::Folder)
        else -> null
    }
}
