/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.personal.PersonalListen
import io.github.nikitasud.latentjam.smart.personal.PersonalStart
import io.github.nikitasud.latentjam.smart.personal.PersonalSurface
import io.github.nikitasud.latentjam.smart.personal.PersonalTrack
import io.github.nikitasud.latentjam.smart.ranker.SmartRanker

// The history log as SMART's personal evidence (PersonalModel) reads it. One converter for SMART and the Statistics
// page, so the page's strong links are the links SMART learns from.

/** The parent id a For You queue logs (QueueSource.parentCollectionId of FOR_YOU). */
public const val FOR_YOU_PARENT: String = "foryou"

/** A library track as the personal evidence keys it: its version group, artists and release. */
public fun personalTrack(track: TrackDescriptor, artists: List<String>): PersonalTrack = PersonalTrack(
    id = track.id,
    key = SmartRanker.versionKey(track, artists.firstOrNull().orEmpty(), v2 = true),
    artists = artists,
    album = track.rankingAlbum?.trim()?.takeIf(String::isNotEmpty)?.let { "${it.lowercase()}|${track.albumArtist?.trim()?.lowercase().orEmpty()}" },
    trackNumber = track.trackNumber,
    discNumber = track.discNumber,
    durationMs = track.durationMs,
)

/** The shuffle mode SMART playback logs (ShuffleMode.SMART.name). */
private const val SMART_SHUFFLE_MODE = "SMART"

/** A history listen as the personal evidence reads it. */
public fun personalListen(event: ListenEvent): PersonalListen {
    val origin = event.origin
    val start = when {
        event.isImported -> PersonalStart.IMPORTED
        origin == null -> PersonalStart.UNKNOWN
        else -> when (origin.start) {
            ListenStart.USER_PICK -> PersonalStart.PICK
            ListenStart.QUEUED -> PersonalStart.QUEUED
            ListenStart.AUTO_ADVANCE -> PersonalStart.AUTO
            ListenStart.SKIP_NEXT -> PersonalStart.SKIP_NEXT
            ListenStart.SKIP_PREVIOUS -> PersonalStart.SKIP_PREVIOUS
            ListenStart.REPEAT -> PersonalStart.REPEAT
            null -> PersonalStart.UNKNOWN
        }
    }
    // A For You suggestion: started from For You without a plan position, unless queued by hand (PERSONALISATION_RULES v5.5).
    val forYou = origin?.parentId == FOR_YOU_PARENT && origin.smartPlanPosition == null && origin.start != ListenStart.QUEUED
    return PersonalListen(
        trackId = event.trackId,
        startedAtMs = event.startedAtMs,
        listenedMs = event.listenedMs,
        completed = event.completed,
        skipped = event.skipped,
        start = start,
        surface = when (origin?.pickSurface) {
            PickSurface.SEARCH -> PersonalSurface.SEARCH
            PickSurface.QUEUE -> PersonalSurface.QUEUE
            PickSurface.COLLECTION -> PersonalSurface.COLLECTION
            PickSurface.FOR_YOU -> PersonalSurface.FOR_YOU
            PickSurface.EXTERNAL -> PersonalSurface.EXTERNAL
            null -> null
        },
        smartSourced = origin?.smartPlanPosition != null || forYou ||
            (origin == null && event.shuffleMode == SMART_SHUFFLE_MODE && start != PersonalStart.IMPORTED),
        forYou = forYou,
        albumParent = origin?.parentId?.startsWith("album:") == true,
        shuffleOff = event.shuffleMode == "OFF",
        playedMs = event.playedMs,
        personalChanged = origin?.personalChanged == true,
    )
}
