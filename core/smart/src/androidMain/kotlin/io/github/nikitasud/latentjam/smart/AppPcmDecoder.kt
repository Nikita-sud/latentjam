/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * A decoder the app brings for a codec the platform has none for (Apple Lossless on Android): one packet of the
 * container in, interleaved little-endian signed PCM out. The library analysis asks for one only after the platform
 * said it has no decoder for the stream, so a device that ships its own keeps using that.
 */
public interface AppPcmDecoder : AutoCloseable {
    public val channels: Int
    public val sampleRate: Int

    /** Bytes of one sample in [decode]'s output: 2, 3 or 4. */
    public val bytesPerSample: Int

    /** Room for the largest packet the stream can hold. */
    public val maxPacketBytes: Int

    /** Decodes [length] bytes of [packet]; null when the packet cannot be decoded. The array is reused by the next call. */
    public fun decode(packet: ByteArray, length: Int): AppPcm?
}

/** [length] bytes of PCM at the start of [bytes]. */
public class AppPcm(public val bytes: ByteArray, public val length: Int)

/** Opens an [AppPcmDecoder] for a stream by its MIME type and codec configuration, or null when it has none. */
public fun interface AppPcmDecoderFactory {
    public fun open(mime: String, configuration: ByteArray): AppPcmDecoder?
}

/**
 * The app's own decoders, set once at process start by the module that has them (playback owns the native ALAC
 * decoder, and depends on this module, not the other way round). Null means the platform's codecs are all there is.
 */
public object AppPcmDecoders {
    @Volatile
    public var factory: AppPcmDecoderFactory? = null
}

/**
 * Interleaved little-endian signed PCM of [bytesPerSample] bytes a sample, [length] bytes of [bytes], as mono floats in
 * [-1, 1], downmixed as the platform decoder's output is.
 */
internal fun monoFromPcmBytes(
    bytes: ByteArray,
    length: Int,
    channels: Int,
    bytesPerSample: Int,
    downmixMode: AudioDownmixMode,
): FloatArray {
    require(channels > 0 && bytesPerSample in 2..4) { "Unsupported PCM layout: channels=$channels bytes=$bytesPerSample" }
    val frameBytes = channels * bytesPerSample
    val frames = length / frameBytes
    val mono = FloatArray(frames)
    fun sample(at: Int): Float = when (bytesPerSample) {
        2 -> ((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)) / 32768f
        3 -> ((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or (bytes[at + 2].toInt() shl 16)) / 8388608f
        else -> ((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24)) / 2147483648f
    }
    for (frame in 0 until frames) {
        val start = frame * frameBytes
        mono[frame] = when (downmixMode) {
            AudioDownmixMode.AVERAGE -> {
                var sum = 0f
                for (channel in 0 until channels) sum += sample(start + channel * bytesPerSample)
                sum / channels
            }
            AudioDownmixMode.PRESERVE_CHANNEL_POWER -> channelPowerDownmix(channels) { channel ->
                sample(start + channel * bytesPerSample)
            }
        }
    }
    return mono
}
