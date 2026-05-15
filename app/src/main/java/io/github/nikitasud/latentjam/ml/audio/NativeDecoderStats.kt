/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * NativeDecoderStats.kt is part of LatentJam.
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

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters for [NativeAudioDecoder] outcomes. Lets DiagnosticsFragment surface a
 * realistic picture of what fraction of the user's library is hitting the fast native path versus
 * silently falling through to the platform `MediaExtractor` — a question that's otherwise invisible
 * because [NativeAudioDecoder.tryDecode] just returns null on every fallback condition.
 *
 * Kept as a top-level `object` (not a Hilt `@Singleton`) because [NativeAudioDecoder] is itself an
 * `object` and we don't want to pull in DI just for a stat counter. The counters are atomic so
 * concurrent `tryDecode` calls from multiple worker threads stay coherent.
 */
internal object NativeDecoderStats {
    private val successCount = AtomicLong(0)
    private val fallbackNoLibCount = AtomicLong(0)
    private val fallbackAfdOpenFailedCount = AtomicLong(0)
    private val fallbackNonzeroAfdCount = AtomicLong(0)
    private val fallbackEmptyLengthCount = AtomicLong(0)
    private val fallbackFdFailedCount = AtomicLong(0)
    private val fallbackNativeThrewCount = AtomicLong(0)
    private val fallbackNativeReturnedNullCount = AtomicLong(0)

    fun recordSuccess() {
        successCount.incrementAndGet()
    }

    fun recordFallback(reason: FallbackReason) {
        when (reason) {
            FallbackReason.NO_LIB -> fallbackNoLibCount.incrementAndGet()
            FallbackReason.AFD_OPEN_FAILED -> fallbackAfdOpenFailedCount.incrementAndGet()
            FallbackReason.NONZERO_AFD_OFFSET -> fallbackNonzeroAfdCount.incrementAndGet()
            FallbackReason.EMPTY_LENGTH -> fallbackEmptyLengthCount.incrementAndGet()
            FallbackReason.FD_FETCH_FAILED -> fallbackFdFailedCount.incrementAndGet()
            FallbackReason.NATIVE_THREW -> fallbackNativeThrewCount.incrementAndGet()
            FallbackReason.NATIVE_RETURNED_NULL -> fallbackNativeReturnedNullCount.incrementAndGet()
        }
    }

    fun snapshot(): Snapshot =
        Snapshot(
            success = successCount.get(),
            fallbackNoLib = fallbackNoLibCount.get(),
            fallbackAfdOpenFailed = fallbackAfdOpenFailedCount.get(),
            fallbackNonzeroAfdOffset = fallbackNonzeroAfdCount.get(),
            fallbackEmptyLength = fallbackEmptyLengthCount.get(),
            fallbackFdFetchFailed = fallbackFdFailedCount.get(),
            fallbackNativeThrew = fallbackNativeThrewCount.get(),
            fallbackNativeReturnedNull = fallbackNativeReturnedNullCount.get(),
        )

    enum class FallbackReason {
        /** `System.loadLibrary` failed (ABI strip, unusual device, .so missing). Process-wide. */
        NO_LIB,
        /** `ContentResolver.openAssetFileDescriptor` threw — usually a SAF permission revoke. */
        AFD_OPEN_FAILED,
        /** AFD covers a sub-region of the underlying file (asset bundle / multi-file container). */
        NONZERO_AFD_OFFSET,
        /** AFD reported a non-positive length, which the native mmap path can't handle. */
        EMPTY_LENGTH,
        /** Couldn't unwrap a raw fd from the ParcelFileDescriptor. */
        FD_FETCH_FAILED,
        /** `nativeDecode` threw — typically an unsupported format or a corrupt stream. */
        NATIVE_THREW,
        /** `nativeDecode` returned null — the C++ side declined for a per-format reason. */
        NATIVE_RETURNED_NULL,
    }

    data class Snapshot(
        val success: Long,
        val fallbackNoLib: Long,
        val fallbackAfdOpenFailed: Long,
        val fallbackNonzeroAfdOffset: Long,
        val fallbackEmptyLength: Long,
        val fallbackFdFetchFailed: Long,
        val fallbackNativeThrew: Long,
        val fallbackNativeReturnedNull: Long,
    ) {
        val totalFallbacks: Long
            get() =
                fallbackNoLib +
                    fallbackAfdOpenFailed +
                    fallbackNonzeroAfdOffset +
                    fallbackEmptyLength +
                    fallbackFdFetchFailed +
                    fallbackNativeThrew +
                    fallbackNativeReturnedNull

        val total: Long
            get() = success + totalFallbacks
    }
}
