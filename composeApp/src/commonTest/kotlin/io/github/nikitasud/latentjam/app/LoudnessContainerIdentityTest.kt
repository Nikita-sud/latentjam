/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.IntegratedLoudness
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

/** A measured track's loudness survives an iOS container move; Android identities keep their bytes. */
internal class LoudnessContainerIdentityTest {
    private val oldRoot = "file:///private/var/mobile/Containers/Data/Application/0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9/"
    private val newRoot = "file:///private/var/mobile/Containers/Data/Application/FFFFFFFF-0000-1111-2222-333333333333/"

    private fun track(uri: String) = TrackDescriptor(id = TrackId("Imported/a.mp3"), audioUri = uri, durationMs = 200_000, sourceRevision = "ios-import-v1:1:2")

    /** The identity as builds before 0.9.4 wrote it: the URI as it was. */
    private fun legacy(uri: String) = "loudness-r128-v1-libebur128-1.2.6-hist|${uri.length}:$uri|6:200000|17:ios-import-v1:1:2"

    @Test
    fun aMeasurementWrittenInTheOldContainerMatchesTheTrackInTheNewOne() {
        val oldUri = "${oldRoot}Documents/Imported/a.mp3"
        val payload = encodeTrackLoudness(mapOf("Imported/a.mp3" to StoredTrackLoudness(IntegratedLoudness(-12f, -1f, null), legacy(oldUri))))
        val decoded = decodeTrackLoudness(payload).getValue("Imported/a.mp3")
        assertEquals(track("${newRoot}Documents/Imported/a.mp3").loudnessAudioIdentity(), decoded.audioIdentity)
        assertEquals(setOf(oldRoot), legacyContainerRoots(payload))
    }

    @Test
    fun aDamagedIdentityIsLeftAsItIs() {
        val head = "loudness-r128-v1-libebur128-1.2.6-hist|"
        for (bad in listOf(head, "${head}x:abc", "$head-5:abc", "${head}2147483647:abc", "${head}99:short", "other|5:abcde")) {
            assertEquals(bad, relocatedLoudnessIdentity(bad))
        }
    }

    @Test
    fun androidIdentitiesAreUnchanged() {
        for (uri in listOf("content://media/external/audio/media/1000012345", "file:///storage/emulated/0/Music/a.mp3")) {
            assertEquals(legacy(uri), track(uri).loudnessAudioIdentity())
            assertEquals(legacy(uri), relocatedLoudnessIdentity(legacy(uri)))
        }
        assertEquals(emptySet(), legacyContainerRoots(null))
    }
}
