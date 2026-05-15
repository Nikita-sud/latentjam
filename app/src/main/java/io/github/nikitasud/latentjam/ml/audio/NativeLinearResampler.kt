/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * NativeLinearResampler.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.audio

import timber.log.Timber

/**
 * JNI bridge to the C++ NEON-optimized linear resampler in `app/src/main/cpp/linear_resampler.cpp`.
 *
 * Same algorithm as [KotlinLinearResampler] (cross-chunk-aware linear interpolation, O(1) memory
 * per instance). Same numerical output to within float32 precision. ~5-15× faster on Android
 * arm64-v8a because the native build is -O3, auto-vectorizes the inner loop with NEON, and skips
 * JIT bounds-check overhead.
 *
 * Lifecycle: each instance owns an opaque native pointer (`handle`). Call [close] exactly once when
 * done — typically the AudioDecoder does this in `finally` for the resampler it created. We
 * deliberately don't tie freeing to GC (Cleaner / phantom refs) because (a) finalization is
 * unreliable on Android and (b) the explicit-close pattern is already how AudioDecoder treats its
 * other resources.
 */
internal class NativeLinearResampler private constructor(private var handle: Long) :
    LinearResampler, AutoCloseable {

    override fun feed(
        src: FloatArray,
        srcOff: Int,
        srcLen: Int,
        dst: FloatArray,
        dstOff: Int,
        dstCap: Int,
    ): Pair<Int, Int> {
        if (handle == 0L) return 0 to 0
        val packed = nativeFeed(handle, src, srcOff, srcLen, dst, dstOff, dstCap)
        val consumed = (packed shr 32).toInt()
        val produced = (packed and 0xFFFF_FFFFL).toInt()
        return consumed to produced
    }

    override fun flush(dst: FloatArray): Int {
        if (handle == 0L) return 0
        return nativeFlush(handle, dst, dst.size)
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeDestroy(h)
        }
    }

    companion object {
        @Volatile private var libraryLoaded: Boolean? = null

        /**
         * Attempt to load the native .so and instantiate a resampler. Returns `null` on any failure
         * (missing library, wrong ABI, init error) so the caller can fall back to the pure-Kotlin
         * implementation. Library load is attempted exactly once per process; subsequent failures
         * return `null` immediately without retrying.
         */
        fun tryCreate(fromSr: Int, toSr: Int): NativeLinearResampler? {
            if (libraryLoaded == false) return null
            if (libraryLoaded == null) {
                synchronized(this) {
                    if (libraryLoaded == null) {
                        libraryLoaded =
                            try {
                                System.loadLibrary("linear_resampler")
                                Timber.d("native linear_resampler loaded")
                                true
                            } catch (e: UnsatisfiedLinkError) {
                                Timber.w(
                                    e,
                                    "native linear_resampler failed to load, falling back to Kotlin",
                                )
                                false
                            } catch (e: SecurityException) {
                                Timber.w(
                                    e,
                                    "native linear_resampler load denied, falling back to Kotlin",
                                )
                                false
                            }
                    }
                }
            }
            if (libraryLoaded != true) return null
            val handle =
                try {
                    nativeCreate(fromSr, toSr)
                } catch (e: Throwable) {
                    Timber.w(e, "nativeCreate failed for $fromSr → $toSr")
                    return null
                }
            if (handle == 0L) return null
            return NativeLinearResampler(handle)
        }

        @JvmStatic external fun nativeCreate(fromSr: Int, toSr: Int): Long

        @JvmStatic external fun nativeDestroy(handle: Long)

        @JvmStatic
        external fun nativeFeed(
            handle: Long,
            src: FloatArray,
            srcOff: Int,
            srcLen: Int,
            dst: FloatArray,
            dstOff: Int,
            dstCap: Int,
        ): Long

        @JvmStatic external fun nativeFlush(handle: Long, dst: FloatArray, dstCap: Int): Int
    }
}
