/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.CoverEdit
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
 * Rows produced by one library scan and whether the platform source was read completely.
 *
 * [complete] is deliberately independent of media permission. A platform query may still fail
 * after permission was granted (for example, Android's provider can return a null cursor or throw
 * a late [SecurityException]). Only a complete snapshot may be used as evidence that previously
 * indexed rows disappeared.
 */
public data class LibraryScan(
    public val tracks: List<TrackDescriptor>,
    public val complete: Boolean,
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
     * Performs one visible-library scan and reports whether its platform source completed.
     *
     * The default preserves existing implementations whose [tracks] call either returns a full
     * result or throws. Platforms that encode an unavailable source as an empty result must
     * override this method and set [LibraryScan.complete] to false.
     */
    public suspend fun scan(): LibraryScan = LibraryScan(tracks = tracks(), complete = true)

    /**
     * Asks the platform's media index to take in music it has not indexed yet, and returns once it
     * has finished, so a [scan] right after sees it. Files can reach a device without the index
     * being told (a copy made as root, or by a tool that writes past the system's media layer),
     * and on Android they stay invisible to [scan] until something asks the system to index them,
     * which is what the user's Refresh means. The default does nothing, for platforms whose [scan]
     * reads the files themselves.
     */
    public suspend fun indexNewFiles() {}

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

    /**
     * Which of [ids] are certainly not on this device: no source holds them, and the source that
     * would hold each one answered in full. Hidden tracks and tracks of a disabled source are on
     * the device, so they are never reported.
     *
     * Everything that only makes a track look gone counts as "not certain": a missing media
     * grant, a failed query, an empty answer, storage that held music recently but is not mounted
     * now (an SD card taken out, which brings its songs back under the same ids when it returns).
     * Callers may offer to forget what this reports, so it errs towards reporting nothing. The
     * default knows of no source it can vouch for and reports nothing.
     */
    public suspend fun missingTrackIds(ids: Collection<TrackId>): Set<TrackId> = emptySet()

    /** Learns relocation evidence in the background; never changes a published track's ID. */
    public suspend fun rememberTrackIdentities(): Unit = Unit

    /** This source can retain an app ID while recovering a moved file under a new playback URI. */
    public val preservesRelocatedTrackIds: Boolean get() = false

    /**
     * Absolute file paths for [ids], for open-format export (M3U). Best-effort by contract:
     * platforms without stable user-visible file paths return an empty map and callers fall
     * back to metadata-only entries.
     */
    public suspend fun filePaths(ids: List<TrackId>): Map<TrackId, String> = emptyMap()

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

    /**
     * What the system calls each storage volume a track can be on, by the id
     * [TrackDescriptor.storageVolume] holds: "SanDisk SD card", "USB drive". The folder browser
     * names a volume's root with it. Empty where the platform names no volumes, which is also the
     * default.
     */
    public suspend fun storageVolumeNames(): Map<String, String> = emptyMap()

    /**
     * Whether [coverSaved] keeps anything: true where covers are per album (Android's MediaStore).
     * Where it is false, a cover saved into files that already held it changes nothing a scan shows.
     */
    public val recordsCovers: Boolean get() = false

    /**
     * LatentJam just saved [cover] into the files of [trackIds]; called before the scan that
     * follows the save. A platform whose covers are per album (Android's MediaStore) remembers it,
     * so each of these songs shows its own cover, and returns true when that changes what a scan
     * shows for one of them. Android also pins the other songs of their albums to the pictures
     * their own files hold, since MediaStore may regenerate the album's art from a saved file. The
     * default does nothing and returns false, for platforms that already show each file's own cover.
     */
    public suspend fun coverSaved(trackIds: Collection<TrackId>, cover: CoverEdit): Boolean = false

    /**
     * LatentJam just saved edits that kept the cover into the files of [saved], as the library held
     * them when the save began; called before the scan that follows the save. A platform that
     * remembers covers ([recordsCovers]) need not re-read those files to know their cover is still
     * the one it remembers. The default does nothing.
     */
    public suspend fun coverKept(saved: Collection<TrackDescriptor>) {}
}

/**
 * The [MusicLibrary.missingTrackIds] rule, apart from how a platform asks: an id is missing when
 * no source holds it ([present]) and the source that would hold it answered in full ([answered]).
 * Nothing is missing when no source answered with a single track, as an empty answer is also what
 * a provider gives without a grant or while it is still loading.
 */
internal fun provablyMissing(
    ids: Collection<TrackId>,
    present: Set<TrackId>,
    answered: (TrackId) -> Boolean,
): Set<TrackId> {
    if (present.isEmpty()) return emptySet()
    return ids.filterTo(LinkedHashSet()) { id -> id !in present && answered(id) }
}

/**
 * Koin bindings for [MusicLibrary] on this platform.
 *
 * Android's implementation resolves an `android.content.Context` from the
 * graph — the app's platform module must bind one (the composeApp entry point
 * does). The iOS actual has no extra requirements.
 */
public expect fun musicLibraryModule(): Module
