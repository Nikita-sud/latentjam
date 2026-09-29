/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** A top-level box as it sits in the file. */
internal class Mp4Atom(val type: String, val offset: Long, val headerSize: Int, val size: Long) {
    val end: Long get() = offset + size
}

/**
 * A box held in memory: a leaf keeps its payload, a container its children (plus any bytes that
 * precede them, such as an ISO `meta` box's version and flags). Serialization always writes 32-bit
 * sizes, which is why a tree is only edited after it re-serializes to exactly the bytes it came from.
 */
internal class Mp4Box(
    val type: String,
    var payload: ByteArray,
    val children: MutableList<Mp4Box>?,
    var prefix: ByteArray = ByteArray(0),
) {
    val size: Long
        get() = 8L + if (children == null) payload.size.toLong() else prefix.size + children.sumOf { it.size }

    fun child(type: String): Mp4Box? = children?.firstOrNull { it.type == type }

    fun serialize(): ByteArray = ByteArraySink().also { write(it) }.toByteArray()

    fun write(sink: ByteSink) {
        val total = size
        require(total <= 0xFFFFFFFFL) { "box $type too large for a 32-bit size" }
        val header = ByteArray(8)
        Mp4Boxes.putBe32(header, 0, total)
        Mp4Boxes.typeBytes(type).copyInto(header, 4)
        sink.write(header)
        if (children == null) {
            sink.write(payload)
        } else {
            sink.write(prefix)
            children.forEach { it.write(sink) }
        }
    }

    companion object {
        fun leaf(type: String, payload: ByteArray): Mp4Box = Mp4Box(type, payload, null)

        fun container(type: String, children: List<Mp4Box>, prefix: ByteArray = ByteArray(0)): Mp4Box =
            Mp4Box(type, ByteArray(0), children.toMutableList(), prefix)
    }
}

internal object Mp4Boxes {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf", "meta", "ilst")
    private const val MAX_TOP_LEVEL = 100_000
    private const val MAX_DEPTH = 32

    fun typeOf(b: ByteArray, at: Int): String = CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() }.concatToString()

    fun typeBytes(type: String): ByteArray = ByteArray(4) { type[it].code.toByte() }

    fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    fun be24(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)

    fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    fun be64(b: ByteArray, at: Int): Long = (be32(b, at) shl 32) or be32(b, at + 4)

    fun putBe32(b: ByteArray, at: Int, value: Long) {
        b[at] = (value ushr 24).toByte()
        b[at + 1] = (value ushr 16).toByte()
        b[at + 2] = (value ushr 8).toByte()
        b[at + 3] = value.toByte()
    }

    fun putBe64(b: ByteArray, at: Int, value: Long) {
        putBe32(b, at, value ushr 32)
        putBe32(b, at + 4, value)
    }

    /** The top-level boxes, which must tile the file exactly; null when they do not. */
    fun topLevel(source: RandomAccessSource): List<Mp4Atom>? {
        val atoms = ArrayList<Mp4Atom>()
        var offset = 0L
        while (offset < source.length) {
            val header = source.read(offset, 8) ?: return null
            val size32 = be32(header, 0)
            val (headerSize, size) = when (size32) {
                0L -> 8 to source.length - offset
                1L -> 16 to be64(source.read(offset + 8, 8) ?: return null, 0)
                else -> 8 to size32
            }
            // Compare without adding offset + size: a huge largesize would overflow a Long and wrap
            // negative, making the bogus box look small enough to accept.
            if (size < headerSize || size > source.length - offset) return null
            atoms += Mp4Atom(typeOf(header, 4), offset, headerSize, size)
            offset += size
            if (atoms.size > MAX_TOP_LEVEL) return null
        }
        return atoms
    }

    /** One complete box as a tree, or null when any size does not add up. */
    fun parse(bytes: ByteArray): Mp4Box? {
        val (box, end) = parseAt(bytes, 0, bytes.size, parentType = "") ?: return null
        return box.takeIf { end == bytes.size }
    }

    fun walk(box: Mp4Box): Sequence<Mp4Box> = sequence {
        yield(box)
        box.children?.forEach { yieldAll(walk(it)) }
    }

    private fun parseAt(bytes: ByteArray, offset: Int, end: Int, parentType: String, depth: Int = 0): Pair<Mp4Box, Int>? {
        if (depth > MAX_DEPTH) return null
        if (offset + 8 > end) return null
        val size32 = be32(bytes, offset)
        val type = typeOf(bytes, offset + 4)
        val (header, size) = when (size32) {
            0L -> 8 to (end - offset).toLong()
            1L -> {
                if (offset + 16 > end) return null
                16 to be64(bytes, offset + 8)
            }
            else -> 8 to size32
        }
        // Compare without adding offset + size: a huge largesize would overflow a Long and wrap
        // negative, making the bogus box look small enough to accept, then (offset + size).toInt()
        // below would hand copyOfRange garbage bounds.
        if (size < header || size > end - offset) return null
        val bodyStart = offset + header
        val boxEnd = (offset + size).toInt()
        if (type !in CONTAINERS && parentType != "ilst") {
            return Mp4Box.leaf(type, bytes.copyOfRange(bodyStart, boxEnd)) to boxEnd
        }
        var childStart = bodyStart
        var prefix = ByteArray(0)
        if (type == "meta" && isoMeta(bytes, bodyStart, boxEnd)) {
            prefix = bytes.copyOfRange(bodyStart, bodyStart + 4)
            childStart += 4
        }
        val children = ArrayList<Mp4Box>()
        var p = childStart
        while (p < boxEnd) {
            val (child, next) = parseAt(bytes, p, boxEnd, type, depth + 1) ?: return null
            children += child
            p = next
        }
        return Mp4Box(type, ByteArray(0), children, prefix) to boxEnd
    }

    /** ISO `meta` is a full box: 4 bytes of version and flags before its first child. QuickTime's is not. */
    private fun isoMeta(bytes: ByteArray, bodyStart: Int, end: Int): Boolean {
        if (bodyStart + 8 <= end && typeOf(bytes, bodyStart + 4) == "hdlr") return false
        return bodyStart + 4 <= end
    }
}
