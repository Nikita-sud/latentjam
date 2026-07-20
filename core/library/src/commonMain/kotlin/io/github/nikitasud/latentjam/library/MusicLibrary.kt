/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import org.koin.core.module.Module

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

    /** Hides [trackId] in LatentJam without modifying the source file on the device. */
    public suspend fun hide(trackId: TrackId)

    /** Makes a previously hidden track visible again. */
    public suspend fun unhide(trackId: TrackId)

    /** Whether the app-only hidden list contains at least one track. */
    public suspend fun hasHiddenTracks(): Boolean

    /** Restores every app-hidden track without modifying any source files. */
    public suspend fun unhideAll()
}

/**
 * Koin bindings for [MusicLibrary] on this platform.
 *
 * Android's implementation resolves an `android.content.Context` from the
 * graph — the app's platform module must bind one (the composeApp entry point
 * does). The iOS actual has no extra requirements.
 */
public expect fun musicLibraryModule(): Module
