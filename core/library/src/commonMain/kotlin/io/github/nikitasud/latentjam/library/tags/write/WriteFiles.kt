/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteSink
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import io.github.nikitasud.latentjam.library.tags.WritePlans

/**
 * A file the durable writer changes: the track being edited, or a file in the recovery store.
 *
 * Platforms implement it over a file descriptor and do not buffer: [write] hands bytes to the OS,
 * and [force] returns only once every earlier write and length change is on storage (`fsync`,
 * `F_FULLFSYNC` on Apple platforms, where `fsync` alone does not flush the drive's cache).
 */
public interface TargetFile : RandomAccessSource, AutoCloseable {
    /** Writes [count] bytes of [bytes] from [from] at [offset], extending the file when past its end. */
    public fun write(offset: Long, bytes: ByteArray, from: Int = 0, count: Int = bytes.size - from)

    /** Truncates, or extends with zeros, to exactly [length] bytes. */
    public fun setLength(length: Long)

    public fun force()
}

/**
 * App-private storage for the journal and the bytes a recovery needs. Never a cache directory: the
 * system may clear those at any moment, and with them the only way back for a half-written file.
 */
public interface RecoveryDirectory {
    /** A new, empty file named [name], replacing any existing one. */
    public fun create(name: String): TargetFile

    /** The file named [name], or null when there is none. */
    public fun open(name: String): TargetFile?

    public fun delete(name: String)

    /**
     * Every file in the store. Throws when the store cannot be listed; empty only when there truly is
     * nothing (or no store yet). A failed listing must never read as empty: every open save would
     * then look finished.
     */
    public fun names(): List<String>

    /** Makes every create and delete so far durable (an fsync of the directory itself). */
    public fun sync()

    public fun freeBytes(): Long
}

/**
 * Replaces a track file with a staged one in one atomic step — iOS, where the track and the store
 * share a volume and a rename cannot leave a half-written file behind.
 */
public fun interface AtomicReplacer {
    /** Moves store file [stagedName] over the track [key], durably, and opens the result. */
    public fun replace(key: String, stagedName: String): TargetFile
}

/** A write ran out of space. Every step that can meet it leaves the track whole. */
public class StorageFullException(message: String) : Exception(message)

internal object FileOps {
    fun crc(source: RandomAccessSource): Long? {
        val crc = Crc32()
        var position = 0L
        while (position < source.length) {
            val count = minOf(WritePlans.COPY_CHUNK.toLong(), source.length - position).toInt()
            crc.update(source.read(position, count) ?: return null)
            position += count
        }
        return crc.value
    }

    fun readAll(source: RandomAccessSource): ByteArray? =
        if (source.length > Int.MAX_VALUE) null else source.read(0, source.length.toInt())

    /** Writes all of [from] over [to] from offset 0 and sets the length; the CRC of what was written. */
    fun copyOver(from: RandomAccessSource, to: TargetFile): Long {
        val crc = Crc32()
        var position = 0L
        while (position < from.length) {
            val count = minOf(WritePlans.COPY_CHUNK.toLong(), from.length - position).toInt()
            val chunk = from.read(position, count) ?: throw IllegalStateException("short read at $position")
            to.write(position, chunk)
            crc.update(chunk)
            position += count
        }
        to.setLength(from.length)
        return crc.value
    }
}

/** A [ByteSink] that appends to a [TargetFile] and keeps the CRC of what it wrote. */
internal class AppendingSink(private val file: TargetFile) : ByteSink {
    var position = 0L
        private set
    private val crc = Crc32()
    val crcValue: Long get() = crc.value

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        file.write(position, bytes, offset, count)
        crc.update(bytes, offset, count)
        position += count
    }
}
