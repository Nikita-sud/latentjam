/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS [MusicLibrary] — currently a STUB returning an empty collection, so the
 * shared UI honestly shows "0 tracks" until the real source lands.
 *
 * ### Where the real implementation goes (TODO)
 * Two candidate sources, likely both eventually:
 * 1. **Local files** the app owns (Files-app import / iTunes file sharing):
 *    enumerate the app's Documents directory via `FileManager`, read tags with
 *    `AVAsset` metadata, expose `file://` URLs as [TrackDescriptor.audioUri].
 * 2. **MPMediaQuery / MusicKit** for the device's Apple Music library —
 *    requires the `NSAppleMusicUsageDescription` entitlement and only yields
 *    DRM-free items for raw-audio access.
 */
internal class StubMusicLibrary : MusicLibrary {
    override suspend fun tracks(): List<TrackDescriptor> = emptyList()
}

public actual fun musicLibraryModule(): Module = module {
    single<MusicLibrary> { StubMusicLibrary() }
}
