/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.jvm.JvmInline

/**
 * Stable, opaque identifier of a track in the caller's library.
 *
 * The engine never interprets the value; it only uses it as a key. Callers map
 * their own domain identifiers (database row ids, content URIs, file hashes, …)
 * into [TrackId] at the boundary and back out when a result is returned. This
 * is the only "identity" concept shared between the engine and the app, which
 * is what keeps the engine free of any media-library or playback types.
 */
@JvmInline
public value class TrackId(public val value: String)

/**
 * Everything the engine may know about a single track.
 *
 * This is a pure data snapshot — deliberately NOT a reference to any player,
 * media-session, or library entity. All fields except [id] are optional so the
 * caller can provide whatever it has; backends degrade gracefully with less.
 *
 * @property id Stable identifier of the track. Required.
 * @property title Track title tag, if known.
 * @property artist Artist tag, if known.
 * @property album Album tag, if known.
 * @property genre Genre tag, if known.
 * @property durationMs Track duration in milliseconds, if known.
 * @property audioUri Platform-resolvable locator of the raw audio (a
 *   `content://` URI on Android, a file URL on iOS, …). Backends that embed
 *   raw audio (the CNN encoder) use this to decode the waveform; metadata-only
 *   backends ignore it. `null` means "audio not available for this track".
 * @property artworkUri Platform-resolvable locator of the track's cover art,
 *   for UI and media-notification display. The engine ignores it.
 * @property addedAtMs When the track appeared in the device library (epoch
 *   ms), for recency sorting. The engine ignores it.
 */
public data class TrackDescriptor(
    public val id: TrackId,
    public val title: String? = null,
    public val artist: String? = null,
    public val album: String? = null,
    public val genre: String? = null,
    public val durationMs: Long? = null,
    public val audioUri: String? = null,
    public val artworkUri: String? = null,
    public val addedAtMs: Long? = null,
    /** Release year. Feeds the chain's era penalty, so a missing value simply costs that signal. */
    public val year: Int? = null,
    /** Loudness in `[0, 1]`, or null when unmeasured. Feeds the chain's energy-smoothness term. */
    public val energy: Float? = null,
)

/**
 * The listening situation a next-track query is made from.
 *
 * This is the engine-facing projection of "what is happening right now",
 * expressed without any playback-state-machine types: the caller observes its
 * player however it likes and distills the result into this value object.
 *
 * @property seed The track to find a nearest neighbor for — typically the
 *   currently playing track.
 * @property recentTrackIds Recently played tracks, excluded from results to
 *   avoid immediate repeats. Order is irrelevant to the engine.
 * @property excludedTrackIds Additional tracks the caller never wants returned
 *   (e.g. user-banned tracks, tracks already queued).
 */
public data class ListeningContext(
    public val seed: TrackDescriptor,
    public val recentTrackIds: List<TrackId> = emptyList(),
    public val excludedTrackIds: Set<TrackId> = emptySet(),
)
