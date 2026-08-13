/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** One browsable collection (a user playlist) offered to external browsers. */
public class MediaBrowseCollection(
    public val id: String,
    public val title: String,
    public val tracks: List<TrackDescriptor>,
)

/**
 * The catalog offered to external media browsers (Android Auto).
 *
 * Section titles arrive already localized: the app shell owns the words, this module only
 * arranges them into the browse tree.
 */
public class MediaBrowseCatalog(
    public val collectionsTitle: String,
    public val tracksTitle: String,
    public val collections: List<MediaBrowseCollection>,
    public val tracks: List<TrackDescriptor>,
)

/**
 * Announced rather than injected, like [AudioSessionRegistry]: the service is built by the
 * system and cannot see the app's scoped Koin graph. The provider runs per browse request, so
 * Auto always sees the current library without any push-refresh plumbing.
 */
public object MediaBrowseRegistry {
    @Volatile
    public var catalog: (suspend () -> MediaBrowseCatalog?)? = null
}
