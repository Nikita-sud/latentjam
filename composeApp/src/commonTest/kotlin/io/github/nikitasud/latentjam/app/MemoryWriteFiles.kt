/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.write.RecoveryDirectory
import io.github.nikitasud.latentjam.library.tags.write.TargetFile

/** Files in memory for coordinator tests; crash behaviour is core/library's FaultFiles' job. */
internal class MemoryWriteFiles {
    private val tracks = HashMap<String, Box>()
    private val store = LinkedHashMap<String, Box>()

    /** Sees every store file the moment it is deleted. */
    var onDelete: (String) -> Unit = {}

    class Box(var bytes: ByteArray)

    fun put(key: String, bytes: ByteArray) {
        tracks[key] = Box(bytes.copyOf())
    }

    fun has(key: String) = key in tracks
    fun remove(key: String) {
        tracks.remove(key)
    }
    fun bytes(key: String): ByteArray = tracks.getValue(key).bytes.copyOf()
    fun track(key: String): TargetFile = BoxFile(tracks.getValue(key))

    val directory: RecoveryDirectory = object : RecoveryDirectory {
        override fun create(name: String): TargetFile = BoxFile(Box(ByteArray(0)).also { store[name] = it })
        override fun open(name: String): TargetFile? = store[name]?.let(::BoxFile)
        override fun delete(name: String) {
            onDelete(name)
            store.remove(name)
        }
        override fun names(): List<String> = store.keys.toList()
        override fun sync() = Unit
        override fun freeBytes(): Long = Long.MAX_VALUE / 4
    }

    private class BoxFile(private val box: Box) : TargetFile {
        override val length: Long get() = box.bytes.size.toLong()
        override fun read(offset: Long, count: Int): ByteArray? =
            if (offset < 0 || count < 0 || offset > length || count > length - offset) null
            else box.bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            val end = offset.toInt() + count
            if (end > box.bytes.size) box.bytes = box.bytes.copyOf(end)
            bytes.copyInto(box.bytes, offset.toInt(), from, from + count)
        }
        override fun setLength(length: Long) {
            box.bytes = box.bytes.copyOf(length.toInt())
        }
        override fun force() = Unit
        override fun close() = Unit
    }
}
