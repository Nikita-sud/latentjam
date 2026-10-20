/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/**
 * The simulator reads the files from the host checkout: core/smart's build passes the two directories as
 * SIMCTL_CHILD_LJ_TEST_RESOURCES / SIMCTL_CHILD_LJ_ML_DIR, which simctl hands to the test process without the prefix.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object GoldenFiles {
    private fun dir(name: String): String = checkNotNull(getenv(name)?.toKString()) { "$name is not set (core/smart build.gradle.kts)" }

    private fun bytes(path: String): ByteArray {
        val f = checkNotNull(fopen(path, "rb")) { path }
        try {
            fseek(f, 0, SEEK_END)
            val size = ftell(f).toInt()
            fseek(f, 0, SEEK_SET)
            val out = ByteArray(size)
            if (size > 0) out.usePinned { check(fread(it.addressOf(0), 1u, size.toULong(), f).toInt() == size) { path } }
            return out
        } finally {
            fclose(f)
        }
    }

    actual fun gzipText(name: String): String {
        val input = bytes(dir("LJ_TEST_RESOURCES") + "/" + name)
        val parts = ArrayList<ByteArray>()
        memScoped {
            val stream = alloc<z_stream>()
            check(inflateInit2_(stream.ptr, 16 + MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
            val chunk = ByteArray(1 shl 16)
            input.usePinned { inPin ->
                stream.next_in = inPin.addressOf(0).reinterpret<UByteVar>()
                stream.avail_in = input.size.toUInt()
                chunk.usePinned { outPin ->
                    while (true) {
                        stream.next_out = outPin.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_out = chunk.size.toUInt()
                        val rc = inflate(stream.ptr, Z_NO_FLUSH)
                        check(rc == Z_OK || rc == Z_STREAM_END) { "inflate $rc" }
                        parts += chunk.copyOf(chunk.size - stream.avail_out.toInt())
                        if (rc == Z_STREAM_END) break
                    }
                }
            }
            inflateEnd(stream.ptr)
        }
        val all = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(all, at); at += p.size }
        return all.decodeToString()
    }

    actual fun ml(name: String): ByteArray = bytes(dir("LJ_ML_DIR") + "/" + name)
}
