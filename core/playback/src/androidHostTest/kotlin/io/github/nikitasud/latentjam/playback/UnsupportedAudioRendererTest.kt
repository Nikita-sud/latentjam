/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsupportedAudioRendererTest {
    private val renderer = UnsupportedAudioRenderer()

    private fun support(mime: String) =
        RendererCapabilities.getFormatSupport(renderer.supportsFormat(Format.Builder().setSampleMimeType(mime).build()))

    @Test
    fun anyAudioIsClaimedOnlyAsExceedingCapabilities() {
        // The weakest support the track selector still picks: any real decoder that handles the track wins.
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, support(MimeTypes.AUDIO_ALAC))
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, support(MimeTypes.AUDIO_MPEG))
    }

    @Test
    fun otherTrackTypesAreNotClaimed() {
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, support(MimeTypes.VIDEO_H264))
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, support(MimeTypes.IMAGE_JPEG))
    }
}
