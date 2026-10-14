/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.Foundation.NSURL
import platform.Foundation.NSProcessInfo

/** Imported/local files only; protected Music.app playback has no app-owned gain stage. */
@OptIn(ExperimentalForeignApi::class)
public class IosIntegratedLoudnessMeter {
    public suspend fun measure(audioUri: String, durationMs: Long?): IntegratedLoudness? =
        withContext(Dispatchers.Default) {
            scanMutex.withLock {
                val coroutine = currentCoroutineContext()
                val started = NSProcessInfo.processInfo.systemUptime
                var meter: NativeLoudnessMeter? = null
                try {
                    if (durationMs != null && durationMs > LoudnessScanLimits.MAX_DURATION_MS) return@withLock null
                    val url = NSURL.URLWithString(audioUri)?.takeIf { it.isFileURL() } ?: return@withLock null
                    val file = AVAudioFile(forReading = url, commonFormat = AVAudioPCMFormatFloat32, interleaved = false, error = null)
                    val format = file.processingFormat
                    val rate = format.sampleRate.toInt()
                    val channels = format.channelCount.toInt()
                    if (rate !in 8000..192000 || channels !in 1..2 || file.length <= 0 ||
                        file.length > LoudnessScanLimits.MAX_DURATION_MS * rate / 1000) return@withLock null
                    val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = LoudnessScanLimits.CHUNK_FRAMES.toUInt())
                    val samples = FloatArray(LoudnessScanLimits.CHUNK_FRAMES * channels)
                    val native = NativeLoudnessMeter(rate, channels).also { meter = it }
                    var decodedFrames = 0L
                    while (decodedFrames < file.length) {
                        coroutine.ensureActive()
                        if ((NSProcessInfo.processInfo.systemUptime - started) * 1000 > LoudnessScanLimits.WALL_BUDGET_MS) return@withLock null
                        if (!file.readIntoBuffer(buffer, error = null)) return@withLock null
                        val frames = buffer.frameLength.toInt()
                        if (frames <= 0) return@withLock null // early EOF is a partial scan
                        val data = buffer.floatChannelData ?: return@withLock null
                        val stride = buffer.stride.toInt()
                        for (c in 0 until channels) {
                            val channel = data[c] ?: return@withLock null
                            for (f in 0 until frames) samples[f * channels + c] = channel[f * stride]
                        }
                        if (!native.add(samples, frames)) return@withLock null
                        decodedFrames += frames
                    }
                    coroutine.ensureActive()
                    if (decodedFrames != file.length || !LoudnessScanLimits.complete(decodedFrames, rate, durationMs)) return@withLock null
                    native.finish().also { coroutine.ensureActive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    null
                } finally { meter?.close() }
            }
        }
    private companion object { val scanMutex = Mutex() }
}
