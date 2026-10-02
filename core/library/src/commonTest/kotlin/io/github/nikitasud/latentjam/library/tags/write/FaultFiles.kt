/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.random.Random

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
 * - [powerLoss] then rebuilds what storage would hold: `powerLoss(keep)` keeps, per file, the first
 *   `keep` bytes of its unforced writes in order (a torn write at any byte); `powerLoss(select)` and
 *   `powerLossSubset(seed)` keep an arbitrary subset of the unforced changes, in or out of order.
 * - [processDeath] instead keeps everything visible and unforced, as a killed process leaves it.
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

    /** When set, the store reports this much free space whatever it holds (a store that lies). */
    var reportedFree: Long? = null

    /** 0 = never. Power is lost right after the Nth write to any track (that write done, unforced). */
    var crashAtTrackWrite: Int = 0
    private var trackWrites = 0

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

    /**
     * Rebuilds what storage holds after a power loss. [landed] gives, per file, the bytes that
     * survived; the store falls back to its last synced directory listing.
     */
    private fun lose(landed: (name: String, node: Node) -> ByteArray) {
        store = LinkedHashMap(syncedStore)
        val files = tracks.map { it.key to it.value } + store.map { it.key to it.value }
        for ((name, node) in files) {
            val data = landed(name, node)
            node.durable = data
            node.current = data.copyOf()
            node.pending.clear()
        }
        resetCounters()
    }

    private fun resetCounters() {
        crashAt = 0
        operations = 0
        crashAtTrackWrite = 0
        trackWrites = 0
    }

    /**
     * The process is killed but the machine keeps running: every write and directory change so far
     * stays visible (the OS still holds it) and stays unforced, so a later [powerLoss] can still
     * drop it. Only the crash counters reset.
     */
    fun processDeath() = resetCounters()

    /**
     * An independent copy of everything: both views of every file, the pending changes, the synced
     * listing and the counters. It lets a crash test replay one save once and then try many ways
     * its power loss can end. Byte arrays are never changed in place here, so the copies share them.
     */
    fun copy(): FaultFiles {
        val copies = HashMap<Node, Node>()
        fun Node.copied(): Node = copies.getOrPut(this) {
            Node(durable).also {
                it.current = current
                it.pending += pending
            }
        }
        return FaultFiles(storeCapacity).also { c ->
            tracks.forEach { (name, node) -> c.tracks[name] = node.copied() }
            store.forEach { (name, node) -> c.store[name] = node.copied() }
            c.syncedStore = syncedStore.mapValuesTo(LinkedHashMap()) { it.value.copied() }
            c.crashAt = crashAt
            c.operations = operations
            c.reportedFree = reportedFree
            c.crashAtTrackWrite = crashAtTrackWrite
            c.trackWrites = trackWrites
        }
    }

    /**
     * A power loss where each file keeps its durable bytes plus, in order, the unforced changes
     * that fit in the first [keep] bytes of its pending writes: a torn write at any byte.
     *
     * A length change lands when every write before it landed whole, so `keep >= pendingBytes()`
     * lands everything, a trailing or standalone `setLength` included (a file with no pending
     * writes has all its length changes land, whatever [keep] is). Nothing after a torn write lands.
     */
    fun powerLoss(keep: Long = 0) = lose { _, node ->
        var budget = keep
        var data = node.durable
        for (change in node.pending) {
            when (change) {
                is Change.Write -> {
                    val n = minOf(budget, change.bytes.size.toLong()).toInt()
                    if (n > 0) data = splice(data, change.offset, change.bytes.copyOf(n))
                    budget -= n
                    if (n < change.bytes.size) break
                }
                is Change.Length -> data = data.copyOf(change.length.toInt())
            }
        }
        data
    }

    /**
     * A power loss where storage keeps an arbitrary subset of each file's unforced changes, which
     * real storage may do: a later write persisted without an earlier one, a size extension
     * persisted without its data (the new bytes read as zeros: select the `setLength`, not the writes).
     *
     * [select] is asked once per pending change, with the file's name and the change's index among
     * that file's changes since its last force (in the order they were made).
     */
    fun powerLoss(select: (fileName: String, changeIndex: Int) -> Boolean) = lose { name, node ->
        var data = node.durable
        node.pending.forEachIndexed { index, change ->
            if (!select(name, index)) return@forEachIndexed
            data = when (change) {
                is Change.Write -> splice(data, change.offset, change.bytes)
                is Change.Length -> data.copyOf(change.length.toInt())
            }
        }
        data
    }

    /**
     * [powerLoss] with a [seed]-determined subset, for looping over many crash outcomes:
     * `for (seed in 0L until 200) { fresh files; run the scenario; files.powerLossSubset(seed); check }`.
     * Each pending change lands with probability 1/2, and a landed write is torn to a random prefix
     * one time in four. The same seed always gives the same outcome.
     */
    fun powerLossSubset(seed: Long) {
        val random = Random(seed)
        lose { _, node ->
            var data = node.durable
            for (change in node.pending) {
                if (random.nextBoolean()) continue
                data = when (change) {
                    is Change.Write -> {
                        val n = if (random.nextInt(4) == 0) random.nextInt(change.bytes.size + 1) else change.bytes.size
                        splice(data, change.offset, change.bytes.copyOf(n))
                    }
                    is Change.Length -> data.copyOf(change.length.toInt())
                }
            }
            data
        }
    }

    /** An [AtomicReplacer] renaming a store file over a track in one durable step, as iOS does. */
    fun replacer(): AtomicReplacer = AtomicReplacer { key, stagedName ->
        tick()
        val node = store.remove(stagedName) ?: throw IllegalStateException("no $stagedName")
        syncedStore = syncedStore - stagedName
        node.durable = node.current.copyOf()
        node.pending.clear()
        tracks[key] = node
        NodeFile(node, store = false)
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

        override fun freeBytes(): Long = reportedFree ?: (storeCapacity - storeSize())
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
            if (!store && ++trackWrites == crashAtTrackWrite) throw PowerLoss()
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
