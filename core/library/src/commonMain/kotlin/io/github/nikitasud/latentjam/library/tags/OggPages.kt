/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** One Ogg page as found in a file. [crcValid] says whether its stored checksum matches its bytes. */
internal class OggPage(
    val offset: Long,
    val headerType: Int,
    val granule: Long,
    val serial: Int,
    val sequence: Int,
    val lacing: IntArray,
    val payload: ByteArray,
    val crcValid: Boolean,
) {
    val size: Int get() = OggPages.HEADER_SIZE + lacing.size + payload.size
    val isBeginning: Boolean get() = headerType and 0x02 != 0
    val continues: Boolean get() = headerType and 0x01 != 0
}

/** The Ogg page layer (RFC 3533): what the tag codec needs and nothing else. */
internal object OggPages {
    const val HEADER_SIZE = 27
    const val MAX_SEGMENTS = 255
    private val MAGIC = "OggS".encodeToByteArray()

    /** The page at [offset], or null when the bytes there are not a whole version-0 Ogg page. */
    fun readAt(source: RandomAccessSource, offset: Long): OggPage? {
        val header = source.read(offset, HEADER_SIZE) ?: return null
        if (!MAGIC.indices.all { header[it] == MAGIC[it] } || header[4] != 0.toByte()) return null
        val segments = header[26].toInt() and 0xFF
        val lacingBytes = source.read(offset + HEADER_SIZE, segments) ?: return null
        val lacing = IntArray(segments) { lacingBytes[it].toInt() and 0xFF }
        val payload = source.read(offset + HEADER_SIZE + segments, lacing.sum()) ?: return null
        val whole = header + lacingBytes + payload
        val stored = le32(whole, 22)
        putLe32(whole, 22, 0)
        return OggPage(
            offset = offset,
            headerType = header[5].toInt() and 0xFF,
            granule = le64(header, 6),
            serial = le32(header, 14),
            sequence = le32(header, 18),
            lacing = lacing,
            payload = payload,
            crcValid = OggCrc.compute(whole) == stored,
        )
    }

    fun serialize(headerType: Int, granule: Long, serial: Int, sequence: Int, lacing: IntArray, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + lacing.size + payload.size)
        MAGIC.copyInto(out)
        out[4] = 0
        out[5] = headerType.toByte()
        putLe64(out, 6, granule)
        putLe32(out, 14, serial)
        putLe32(out, 18, sequence)
        out[26] = lacing.size.toByte()
        for (i in lacing.indices) out[HEADER_SIZE + i] = lacing[i].toByte()
        payload.copyInto(out, HEADER_SIZE + lacing.size)
        putLe32(out, 22, OggCrc.compute(out))
        return out
    }

    /** Lacing for one packet: a 255 per full segment, then the remainder (0 for an exact multiple). */
    fun lacingOf(length: Int): IntArray {
        val full = length / 255
        return IntArray(full + 1) { if (it < full) 255 else length % 255 }
    }

    /**
     * The complete packets in [pages] (consecutive pages of one stream, the first starting on a
     * packet boundary) and whether the last page closes its last packet. Null when a page's
     * continuation flag disagrees with the packet state.
     */
    fun packets(pages: List<OggPage>): Pair<List<ByteArray>, Boolean>? {
        val packets = ArrayList<ByteArray>()
        val current = ByteArraySink()
        var open = false
        for (page in pages) {
            if (page.continues != open) return null
            var position = 0
            for (value in page.lacing) {
                current.write(page.payload, position, value)
                position += value
                open = true
                if (value < 255) {
                    packets += current.toByteArray()
                    current.reset()
                    open = false
                }
            }
        }
        return packets to !open
    }

    /** One laid-out page. */
    class PageContent(
        val lacing: IntArray,
        val payload: ByteArray,
        /** The page begins in the middle of a packet. */
        val continues: Boolean,
        /** At least one packet ends on this page. */
        val completesPacket: Boolean,
    )

    /**
     * [packets] laced into exactly [pageCount] pages of 1–255 segments each, earlier pages filled
     * first; null when that many pages cannot hold them.
     */
    fun layout(packets: List<ByteArray>, pageCount: Int): List<PageContent>? {
        // Each segment: which packet, where in it, how long, and whether it closes the packet.
        val packetOf = ArrayList<Int>()
        val offsetOf = ArrayList<Int>()
        val lengthOf = ArrayList<Int>()
        val closes = ArrayList<Boolean>()
        for ((index, packet) in packets.withIndex()) {
            val lacing = lacingOf(packet.size)
            var offset = 0
            for ((i, value) in lacing.withIndex()) {
                packetOf += index
                offsetOf += offset
                lengthOf += value
                closes += i == lacing.lastIndex
                offset += value
            }
        }
        val total = lengthOf.size
        if (pageCount <= 0 || total < pageCount || total > pageCount.toLong() * MAX_SEGMENTS) return null
        // Balanced, front-loaded: the first (total % pageCount) pages carry one extra segment.
        val base = total / pageCount
        val remainder = total % pageCount
        val pages = ArrayList<PageContent>(pageCount)
        var index = 0
        for (page in 0 until pageCount) {
            val take = base + if (page < remainder) 1 else 0
            val payload = ByteArray((index until index + take).sumOf { lengthOf[it] })
            var p = 0
            for (s in index until index + take) {
                packets[packetOf[s]].copyInto(payload, p, offsetOf[s], offsetOf[s] + lengthOf[s])
                p += lengthOf[s]
            }
            pages += PageContent(
                lacing = IntArray(take) { lengthOf[index + it] },
                payload = payload,
                continues = index > 0 && !closes[index - 1],
                completesPacket = (index until index + take).any { closes[it] },
            )
            index += take
        }
        return pages
    }

    fun minimumPages(packets: List<ByteArray>): Int {
        val segments = packets.sumOf { it.size / 255 + 1 }
        return (segments + MAX_SEGMENTS - 1) / MAX_SEGMENTS
    }

    fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    fun putLe32(b: ByteArray, at: Int, value: Int) {
        b[at] = value.toByte()
        b[at + 1] = (value ushr 8).toByte()
        b[at + 2] = (value ushr 16).toByte()
        b[at + 3] = (value ushr 24).toByte()
    }

    fun le64(b: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    fun putLe64(b: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) b[at + i] = (value ushr (8 * i)).toByte()
    }
}

/**
 * Streams pages through with sequence numbers shifted by [sequenceDelta] and checksums recomputed:
 * what every page after the header needs when the header gains or loses pages.
 */
internal class OggRenumberer(private val serial: Int, private val sequenceDelta: Int) : StreamTransform {

    override fun start(): StreamTransform.Pass = object : StreamTransform.Pass {
        private var buffer = ByteArray(1 shl 16)
        private var size = 0

        override fun process(chunk: ByteArray, sink: ByteSink) {
            if (size + chunk.size > buffer.size) buffer = buffer.copyOf(maxOf(size + chunk.size, buffer.size * 2))
            chunk.copyInto(buffer, size)
            size += chunk.size
            drain(sink)
        }

        override fun finish(sink: ByteSink) {
            drain(sink)
            if (size != 0) throw StreamRefusedException(TagRefusal.OGG_MALFORMED_PAGES)
        }

        private fun drain(sink: ByteSink) {
            var start = 0
            while (size - start >= OggPages.HEADER_SIZE) {
                if (buffer[start] != 'O'.code.toByte() || buffer[start + 1] != 'g'.code.toByte() ||
                    buffer[start + 2] != 'g'.code.toByte() || buffer[start + 3] != 'S'.code.toByte()
                ) {
                    throw StreamRefusedException(TagRefusal.OGG_MALFORMED_PAGES)
                }
                val segments = buffer[start + 26].toInt() and 0xFF
                if (size - start < OggPages.HEADER_SIZE + segments) break
                var payload = 0
                for (i in 0 until segments) payload += buffer[start + OggPages.HEADER_SIZE + i].toInt() and 0xFF
                val pageSize = OggPages.HEADER_SIZE + segments + payload
                if (size - start < pageSize) break
                val page = buffer.copyOfRange(start, start + pageSize)
                val stored = OggPages.le32(page, 22)
                OggPages.putLe32(page, 22, 0)
                if (OggCrc.compute(page) != stored) throw StreamRefusedException(TagRefusal.OGG_BAD_PAGE_CRC)
                if (OggPages.le32(page, 14) != serial) throw StreamRefusedException(TagRefusal.OGG_MULTIPLE_STREAMS)
                OggPages.putLe32(page, 18, OggPages.le32(page, 18) + sequenceDelta)
                OggPages.putLe32(page, 22, OggCrc.compute(page))
                sink.write(page)
                start += pageSize
            }
            if (start > 0) {
                buffer.copyInto(buffer, 0, start, size)
                size -= start
            }
        }
    }
}
