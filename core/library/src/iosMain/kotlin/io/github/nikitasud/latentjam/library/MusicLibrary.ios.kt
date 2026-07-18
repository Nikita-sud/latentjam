/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

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

internal class InMemoryPlaylistStore : PlaylistStore {
    private var lines: List<String> = emptyList()
    override suspend fun read(): List<String> = lines
    override suspend fun write(lines: List<String>) { this.lines = lines }
}

public actual fun musicLibraryModule(): Module = module {
    single<MusicLibrary> { StubMusicLibrary() }
    single<PlaylistStore> { InMemoryPlaylistStore() }
    single<Playlists> { DefaultPlaylists(store = get()) }
}

public actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
