/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.AppPcm
import io.github.nikitasud.latentjam.smart.AppPcmDecoder
import io.github.nikitasud.latentjam.smart.AppPcmDecoderFactory

/**
 * Apple Lossless for the library analysis, which knows only the platform's codecs: the decoder playback and the
 * loudness scan already use (issue #34). Without it an ALAC file plays but is never analysed, so SMART never picks it
 * and the Map does not show it. Any channel count is fine here: the analysis mixes down to mono, so the channel order
 * that keeps playback to stereo does not matter.
 */
public object AlacAnalysisDecoders : AppPcmDecoderFactory {
    private const val ALAC = "audio/alac"

    /** Bytes an ALAC packet may take beyond its frame stored uncompressed: the element header and the end tag. */
    private const val PACKET_HEADROOM = 1024

    override fun open(mime: String, configuration: ByteArray): AppPcmDecoder? {
        if (mime != ALAC) return null
        val decoder = AlacPacketDecoder.open(configuration) ?: return null
        return object : AppPcmDecoder {
            override val channels: Int = decoder.channels
            override val sampleRate: Int = decoder.sampleRate
            override val bytesPerSample: Int = decoder.maxOutputBytes / (decoder.frameLength * decoder.channels)
            override val maxPacketBytes: Int = decoder.maxOutputBytes + PACKET_HEADROOM
            override fun decode(packet: ByteArray, length: Int): AppPcm? =
                decoder.decode(packet, 0, length)?.let { AppPcm(it.bytes, it.length) }
            override fun close() = decoder.close()
        }
    }
}
