/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource

/**
 * Turns "no decoder for this audio" into a playback error instead of silence.
 *
 * When no audio renderer can play a file's audio, Media3 plays the file anyway, with its clock
 * running and nothing heard (issue #34: Apple Lossless before LatentJam decoded it). This renderer
 * claims any audio only as exceeding its capabilities, the weakest support the track selector still
 * picks, so it is chosen only when the real audio renderer cannot take the track at all. Once
 * chosen, it fails with [PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED], and the
 * controller's error recovery skips the row as it does any unreadable file.
 */
@OptIn(UnstableApi::class)
internal class UnsupportedAudioRenderer : BaseRenderer(C.TRACK_TYPE_AUDIO) {
    private var format: Format? = null

    override fun getName(): String = "UnsupportedAudioRenderer"

    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
        if (MimeTypes.isAudio(format.sampleMimeType)) C.FORMAT_EXCEEDS_CAPABILITIES else C.FORMAT_UNSUPPORTED_TYPE,
    )

    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        format = formats.firstOrNull()
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val format = format ?: return
        throw createRendererException(
            IllegalStateException("No decoder for ${format.sampleMimeType}"),
            format,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }

    override fun isReady(): Boolean = false
    override fun isEnded(): Boolean = false

    override fun onDisabled() {
        format = null
    }
}
