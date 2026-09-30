/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

/** A simulated power loss. An Error, so production `catch (e: Exception)` never swallows it. */
internal class PowerLoss : Error("simulated power loss")

/**
 * An in-memory file system for crash testing.
 *
 * Model:
 * - Every file has durable bytes (what storage holds) and current bytes (what reads see).
 * - `force()` makes current durable.
 * - Directory entries created or deleted since the last `sync()` are not durable.
 * - [crashAt] throws [PowerLoss] at the Nth mutating operation.
 * - [powerLoss] then rebuilds what storage would hold. Every file keeps its durable bytes plus the
 *   first `keep` bytes of its unforced writes, in order: a torn write at any byte.
 * - Track files (made with [put]) are always durable entries; store files live in [directory].
 */
internal class FaultFiles(var storeCapacity: Long = Long.MAX_VALUE) {
    private class Node(var durable: ByteArray) {
        var current: ByteArray = durable.copyOf()
        val pending = ArrayList<Change>()
    }

    private sealed interface Change {
        class Write(val offset: Long, val bytes: ByteArray) : Change
        class Length(val length: Long) : Change
    }

    private val tracks = LinkedHashMap<String, Node>()
    private var store = LinkedHashMap<String, Node>()
    private var syncedStore: Map<String, Node> = emptyMap()

    /** 0 = never. */
    var crashAt: Int = 0
    var operations: Int = 0
        private set

    fun put(name: String, bytes: ByteArray) {
        tracks[name] = Node(bytes.copyOf())
    }

    fun track(name: String): TargetFile = NodeFile(tracks.getValue(name), store = false)

    fun trackBytes(name: String): ByteArray = tracks.getValue(name).current.copyOf()

    fun storeNames(): Set<String> = store.keys.toSet()

    /** Bytes written since the last force, across all files: the range of useful `keep` values. */
    fun pendingBytes(): Long = (tracks.values + store.values).sumOf { node ->
        node.pending.sumOf { if (it is Change.Write) it.bytes.size.toLong() else 0L }
    }

    private fun tick() {
        operations++
        if (operations == crashAt) throw PowerLoss()
    }

    fun powerLoss(keep: Long = 0) {
        store = LinkedHashMap(syncedStore)
        for (node in tracks.values + store.values) {
            var budget = keep
            var data = node.durable
            for (change in node.pending) {
                if (budget <= 0) break
                data = when (change) {
                    is Change.Write -> {
                        val n = minOf(budget, change.bytes.size.toLong()).toInt()
                        budget -= n
                        splice(data, change.offset, change.bytes.copyOf(n))
                    }
                    is Change.Length -> data.copyOf(change.length.toInt())
                }
            }
            node.durable = data
            node.current = data.copyOf()
            node.pending.clear()
        }
        crashAt = 0
        operations = 0
    }

    private fun storeSize(): Long = store.values.sumOf { it.current.size.toLong() }

    val directory: RecoveryDirectory = object : RecoveryDirectory {
        override fun create(name: String): TargetFile {
            tick()
            val node = Node(ByteArray(0))
            store[name] = node
            return NodeFile(node, store = true)
        }

        override fun open(name: String): TargetFile? = store[name]?.let { NodeFile(it, store = true) }

        override fun delete(name: String) {
            tick()
            store.remove(name)
        }

        override fun names(): List<String> = store.keys.toList()

        override fun sync() {
            tick()
            syncedStore = LinkedHashMap(store)
        }

        override fun freeBytes(): Long = storeCapacity - storeSize()
    }

    private inner class NodeFile(private val node: Node, private val store: Boolean) : TargetFile {
        override val length: Long get() = node.current.size.toLong()

        override fun read(offset: Long, count: Int): ByteArray? {
            if (offset < 0 || count < 0 || offset > node.current.size || count > node.current.size - offset) return null
            return node.current.copyOfRange(offset.toInt(), offset.toInt() + count)
        }

        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            tick()
            val chunk = bytes.copyOfRange(from, from + count)
            val grows = maxOf(0L, offset + count - node.current.size)
            if (store && grows > 0 && storeSize() + grows > storeCapacity) throw StorageFullException("store full")
            node.current = splice(node.current, offset, chunk)
            node.pending += Change.Write(offset, chunk)
        }

        override fun setLength(length: Long) {
            tick()
            node.current = node.current.copyOf(length.toInt())
            node.pending += Change.Length(length)
        }

        override fun force() {
            tick()
            node.durable = node.current.copyOf()
            node.pending.clear()
        }

        override fun close() = Unit
    }

    private companion object {
        fun splice(data: ByteArray, offset: Long, chunk: ByteArray): ByteArray {
            val end = offset.toInt() + chunk.size
            val out = if (end > data.size) data.copyOf(end) else data.copyOf()
            chunk.copyInto(out, offset.toInt())
            return out
        }
    }
}
