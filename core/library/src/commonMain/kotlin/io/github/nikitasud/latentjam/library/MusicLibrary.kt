/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import org.koin.core.module.Module

/** A user-manageable place from which LatentJam discovers music. */
public data class LibrarySource(
    /** Stable platform-owned identity. Never shown directly to the user. */
    public val id: String,
    /** Human-readable folder or collection name; null means the platform's unnamed root. */
    public val name: String?,
    /** Number of currently discoverable tracks in this source. */
    public val trackCount: Int,
    /** Disabled sources stay on the device but disappear from LatentJam. */
    public val enabled: Boolean,
    /** Some platform collections are informational and cannot be toggled. */
    public val canToggle: Boolean = true,
)

/**
 * Read-only port onto the device's music collection.
 *
 * Deliberately tiny: the engine and UI only need track descriptors — how they
 * are obtained (MediaStore on Android, MusicKit/files on iOS, a fake in tests)
 * is a platform detail behind this interface. Like [io.github.nikitasud.latentjam.smart.SimilarityEngine],
 * this port never exposes platform or playback types.
 */
public interface MusicLibrary {

    /**
     * Snapshot of all music tracks currently visible to the app, in stable
     * (title-sorted) order. Main-safe: implementations do their I/O on a
     * background dispatcher.
     *
     * Requires whatever platform permission grants media access
     * (`READ_MEDIA_AUDIO` on Android 13+); with no grant, implementations
     * return an empty list rather than throwing.
     */
    public suspend fun tracks(): List<TrackDescriptor>

    /**
     * Every currently discoverable track, independent of app-only hidden-track and source
     * visibility settings. This is intentionally separate from [tracks]: backup/recovery needs
     * metadata for referenced tracks even while their source is disabled, but playback and UI
     * callers must continue to respect the user's visibility choices.
     */
    public suspend fun allKnownTracks(): List<TrackDescriptor> =
        (tracks() + hiddenTracks()).distinctBy { it.id }

    /** Hides [trackId] in LatentJam without modifying the source file on the device. */
    public suspend fun hide(trackId: TrackId)

    /** Hides a selection in one platform transaction when the backend supports it. */
    public suspend fun hide(trackIds: Collection<TrackId>) {
        trackIds.forEach { hide(it) }
    }

    /** Makes a previously hidden track visible again. */
    public suspend fun unhide(trackId: TrackId)

    /** Restores a selection in one platform transaction when the backend supports it. */
    public suspend fun unhide(trackIds: Collection<TrackId>) {
        trackIds.forEach { unhide(it) }
    }

    /** Tracks hidden only inside LatentJam, including their last readable metadata. */
    public suspend fun hiddenTracks(): List<TrackDescriptor>

    /** Every app-only hidden id, including tracks whose source is temporarily unavailable. */
    public suspend fun hiddenTrackIds(): Set<TrackId> = hiddenTracks().mapTo(linkedSetOf()) { it.id }

    /** Whether the app-only hidden list contains at least one track. */
    public suspend fun hasHiddenTracks(): Boolean

    /** Restores every app-hidden track without modifying any source files. */
    public suspend fun unhideAll()

    /** Replaces the app-only hidden set without modifying any source files. */
    public suspend fun replaceHidden(trackIds: Set<TrackId>) {
        unhideAll()
        trackIds.forEach { hide(it) }
    }

    /** Folder/library sources known to the platform, with their current visibility. */
    public suspend fun sources(): List<LibrarySource>

    /** Includes or excludes one source without changing any files on the device. */
    public suspend fun setSourceEnabled(sourceId: String, enabled: Boolean)
}

/**
 * Koin bindings for [MusicLibrary] on this platform.
 *
 * Android's implementation resolves an `android.content.Context` from the
 * graph — the app's platform module must bind one (the composeApp entry point
 * does). The iOS actual has no extra requirements.
 */
public expect fun musicLibraryModule(): Module
