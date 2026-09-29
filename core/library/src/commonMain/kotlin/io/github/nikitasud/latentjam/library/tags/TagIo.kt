/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Random read access to a file. Platforms implement it over a file handle; tests over an array.
 * Codecs read only through this, so the same code runs on Android, iOS and the host JVM.
 */
public interface RandomAccessSource {
    public val length: Long

    /** Exactly [count] bytes at [offset], or null when the range is negative or runs past [length]. */
    public fun read(offset: Long, count: Int): ByteArray?
}

public class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val length: Long get() = bytes.size.toLong()

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset + count > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
    }
}

/** Sequential output of a streaming rewrite. */
public interface ByteSink {
    public fun write(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset)
}

/** An in-memory [ByteSink], for tests and small files. */
public class ByteArraySink : ByteSink {
    private var buffer = ByteArray(1024)

    public var size: Int = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(size + count, buffer.size * 2))
        bytes.copyInto(buffer, size, offset, offset + count)
        size += count
    }

    public fun toByteArray(): ByteArray = buffer.copyOf(size)

    /** Forgets everything written, keeping the buffer. */
    public fun reset() {
        size = 0
    }
}
