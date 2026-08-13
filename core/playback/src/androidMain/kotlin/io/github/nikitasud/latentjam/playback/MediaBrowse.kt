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

/** Saved player state that Media3 can restore when a transport control starts a cold process. */
public class MediaPlaybackResume(
    public val tracks: List<TrackDescriptor>,
    public val startIndex: Int,
    public val positionMs: Long,
    public val shuffleMode: ShuffleMode,
    /** Canonical natural order used when leaving SMART; null means the live queue is the source. */
    public val sourceTracks: List<TrackDescriptor>?,
)

/**
 * Announced rather than injected, like [AudioSessionRegistry]: the service is built by the
 * system and cannot see the app's scoped Koin graph. The provider runs per browse request, so
 * Auto always sees the current library without any push-refresh plumbing.
 */
public object MediaBrowseRegistry {
    @Volatile
    public var catalog: (suspend () -> MediaBrowseCatalog?)? = null

    /**
     * Supplies the last durable queue to Media3's playback-resumption callback. Without this,
     * Play from a widget, Quick Settings, Bluetooth, or System UI is a no-op after process death.
     */
    @Volatile
    public var resumption: (suspend () -> MediaPlaybackResume?)? = null

    /** Queue currently being installed by Media3, consumed by the in-app controller on connect. */
    @Volatile
    private var activeResumption: ActiveResumption? = null

    /** Publishes a queue that the in-app MediaController should adopt after Media3 installs it. */
    @Synchronized
    public fun announceActiveResumption(resume: MediaPlaybackResume): Unit {
        // The durable settings cap saved queues at 10k. Apply the same bound here so a malformed
        // provider cannot pin an unbounded URI/metadata graph in this process-global handoff.
        val referencedTracks = sequenceOf(resume.tracks, resume.sourceTracks.orEmpty())
            .flatten()
            .map { it.id }
            .distinct()
            .take(MAX_ACTIVE_RESUMPTION_TRACKS + 1)
            .count()
        activeResumption = if (referencedTracks <= MAX_ACTIVE_RESUMPTION_TRACKS) {
            ActiveResumption(
                resume = resume,
                expiresAtNanos = System.nanoTime() + ACTIVE_RESUMPTION_TTL_NANOS,
            )
        } else {
            null
        }
    }

    @Synchronized
    internal fun currentActiveResumption(): MediaPlaybackResume? {
        val active = activeResumption ?: return null
        if (System.nanoTime() >= active.expiresAtNanos) {
            activeResumption = null
            return null
        }
        return active.resume
    }

    @Synchronized
    internal fun clearActiveResumption(expected: MediaPlaybackResume? = null): Unit {
        if (expected == null || activeResumption?.resume === expected) activeResumption = null
    }

    private class ActiveResumption(
        val resume: MediaPlaybackResume,
        val expiresAtNanos: Long,
    )

    private const val MAX_ACTIVE_RESUMPTION_TRACKS = 10_000
    private const val ACTIVE_RESUMPTION_TTL_NANOS = 60L * 1_000_000_000L
}
