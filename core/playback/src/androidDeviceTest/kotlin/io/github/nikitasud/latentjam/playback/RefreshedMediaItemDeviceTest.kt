/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import org.junit.Assert.*
import org.junit.Test

class RefreshedMediaItemDeviceTest {
    private val original = MediaItem.Builder().setMediaId("old-id")
        .setUri("content://media/external/audio/media/10")
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(123).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Old")
            .setArtworkData(byteArrayOf(1, 2, 3), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build()

    @Test fun relocationUpdatesThePlaybackUriButPreservesIdAndQueueConfiguration() {
        val fresh = TrackDescriptor(TrackId("old-id"), title = "New", audioUri = "content://media/external/audio/media/20")
        val result = original.withRefreshedTrack(fresh, false)
        assertEquals(original.mediaId, result.mediaId)
        assertEquals(fresh.audioUri, result.localConfiguration!!.uri.toString())
        assertEquals(original.clippingConfiguration, result.clippingConfiguration)
        assertArrayEquals(original.mediaMetadata.artworkData, result.mediaMetadata.artworkData)
        assertEquals("New", result.mediaMetadata.title)
    }

    @Test fun tagOnlyAndMissingUriUpdatesPreserveTheExistingMediaSourceAndFallbackCover() {
        for (uri in listOf(original.localConfiguration!!.uri.toString(), null, "")) {
            val result = original.withRefreshedTrack(TrackDescriptor(TrackId("old-id"), title = "Retagged", audioUri = uri), false)
            assertEquals(original.localConfiguration, result.localConfiguration)
            assertArrayEquals(original.mediaMetadata.artworkData, result.mediaMetadata.artworkData)
        }
    }
}
