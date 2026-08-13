/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.tab_for_you
import io.github.nikitasud.latentjam.app.generated.resources.tab_map
import io.github.nikitasud.latentjam.app.generated.resources.tab_tracks
import org.jetbrains.compose.resources.StringResource

/**
 * The surface a queue was started from. Persisted by name, so a value saved by a build with
 * different kinds degrades to "no label" instead of crashing the restore.
 */
enum class QueueSourceKind { COLLECTION, TRACKS, SEARCH, MAP, FOR_YOU }

/**
 * What the current queue was started from; drives the player's "Playing from" line.
 *
 * [name] carries the display name when the source has one of its own — a collection title, a
 * search query. Kinds without a natural name fall back to their surface's label via
 * [fallbackLabelRes].
 */
data class QueueSource(
    val kind: QueueSourceKind,
    val name: String? = null,
)

/** Surface label for sources without a name of their own; null means "show nothing". */
internal fun QueueSourceKind.fallbackLabelRes(): StringResource? = when (this) {
    QueueSourceKind.TRACKS -> Res.string.tab_tracks
    QueueSourceKind.MAP -> Res.string.tab_map
    QueueSourceKind.FOR_YOU -> Res.string.tab_for_you
    // These always carry a name; a nameless one has nothing honest to show.
    QueueSourceKind.COLLECTION, QueueSourceKind.SEARCH -> null
}
