/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate as zlibInflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/**
 * The system zlib with a negative window size, which is raw DEFLATE. The input slice is pinned only
 * for the duration of each [inflate] call, so zlib's next_in pointer never outlives the pin: every
 * call points it at the unconsumed part of the slice again.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class RawInflater actual constructor() {
    private val stream = nativeHeap.alloc<z_stream>()
    private var input: ByteArray = ByteArray(0)
    private var inputOffset = 0
    private var inputRemaining = 0
    private var done = false
    private var open = true

    init {
        val rc = inflateInit2_(stream.ptr, -MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().toInt())
        if (rc != Z_OK) {
            nativeHeap.free(stream)
            open = false
            error("zlib could not start ($rc)")
        }
    }

    actual fun setInput(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        input = buffer
        inputOffset = offset
        inputRemaining = length
    }

    actual val needsInput: Boolean get() = inputRemaining == 0

    actual val finished: Boolean get() = done

    actual val remaining: Int get() = inputRemaining

    actual fun inflate(output: ByteArray, offset: Int, length: Int): Int {
        check(open) { "The inflater is closed" }
        if (done || length == 0) return 0
        require(offset >= 0 && offset + length <= output.size)
        // Called with no input too: zlib may still hold decoded bytes that did not fit last time.
        return if (inputRemaining == 0) {
            run(output, offset, length, null)
        } else {
            input.usePinned { inPin -> run(output, offset, length, inPin) }
        }
    }

    private fun run(output: ByteArray, offset: Int, length: Int, inPin: Pinned<ByteArray>?): Int =
        output.usePinned { outPin ->
            stream.next_in = inPin?.addressOf(inputOffset)?.reinterpret<UByteVar>()
            stream.avail_in = inputRemaining.toUInt()
            stream.next_out = outPin.addressOf(offset).reinterpret<UByteVar>()
            stream.avail_out = length.toUInt()
            val rc = zlibInflate(stream.ptr, Z_NO_FLUSH)
            val consumed = inputRemaining - stream.avail_in.toInt()
            inputOffset += consumed
            inputRemaining -= consumed
            val written = length - stream.avail_out.toInt()
            // Never leave pointers into memory that is about to be unpinned.
            stream.next_in = null
            stream.next_out = null
            when (rc) {
                Z_OK, Z_BUF_ERROR -> Unit
                Z_STREAM_END -> done = true
                else -> throw IllegalArgumentException("Corrupt DEFLATE data ($rc)")
            }
            written
        }

    actual fun close() {
        if (!open) return
        open = false
        inflateEnd(stream.ptr)
        nativeHeap.free(stream)
    }
}
