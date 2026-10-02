/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Opus and Vorbis in Ogg: an identification page, then the comment packet (and, for Vorbis, the
 * setup packet), then audio pages.
 *
 * The comment packet is rebuilt and laced into the same pages with the same byte total whenever its
 * discardable padding allows — only those pages are rewritten and every audio page keeps its
 * sequence number. Otherwise the header is laid out anew and every later page is renumbered.
 *
 * Accepted deviation from spec §4.3: a chained stream (another logical stream after this one ends)
 * is not refused by [read] or [plan]. An in-place edit changes only the first link's comments; a
 * rewrite stops mid-stream on the next link (OGG_MULTIPLE_STREAMS) before anything is replaced.
 */
internal object OggTagCodec : TagCodec {
    private val MAGIC = "OggS".encodeToByteArray()
    private val OPUS_HEAD = "OpusHead".encodeToByteArray()
    private val VORBIS_IDENTIFICATION = byteArrayOf(1) + "vorbis".encodeToByteArray()
    private const val PICTURE_KEY = "METADATA_BLOCK_PICTURE"
    private const val MAX_HEADER_PAGES = 4096
    private const val MAX_HEADER_BYTES = 64L shl 20

    /** Separates the digest of bytes that are no page from the pages before them. */
    private val NOT_A_PAGE = "not a page".encodeToByteArray()

    private enum class Kind(val format: TagFormat, val label: String, val prefix: ByteArray, val headerPackets: Int) {
        OPUS(TagFormat.OPUS, "Opus", "OpusTags".encodeToByteArray(), 1),
        VORBIS(TagFormat.VORBIS, "Vorbis", byteArrayOf(3) + "vorbis".encodeToByteArray(), 2),
    }

    private class Layout(
        val kind: Kind,
        val first: OggPage,
        val headerPages: List<OggPage>,
        val comment: ByteArray,
        val setup: ByteArray?,
        val audioStart: Long,
    )

    private sealed interface Parsed {
        class Ok(val layout: Layout) : Parsed
        class Bad(val reason: TagRefusal) : Parsed
    }

    /** The decoded comment packet: the block, and what follows it. */
    private class Comment(val block: VorbisComments, val tail: ByteArray, val tailIsPadding: Boolean)

    override fun recognizes(head: ByteArray): Boolean =
        head.size >= 4 && MAGIC.indices.all { head[it] == MAGIC[it] }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean =
        data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    private fun parse(source: RandomAccessSource): Parsed {
        val first = OggPages.readAt(source, 0) ?: return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        if (!first.crcValid) return Parsed.Bad(TagRefusal.OGG_BAD_PAGE_CRC)
        if (!first.isBeginning || first.continues) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        val (identification, closed) = OggPages.packets(listOf(first)) ?: return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        if (!closed || identification.size != 1) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
        val kind = when {
            startsWith(identification[0], OPUS_HEAD) -> Kind.OPUS
            startsWith(identification[0], VORBIS_IDENTIFICATION) -> Kind.VORBIS
            else -> return Parsed.Bad(TagRefusal.OGG_UNKNOWN_CODEC)
        }
        val pages = ArrayList<OggPage>()
        var offset = first.size.toLong()
        while (true) {
            val page = OggPages.readAt(source, offset)
            if (page == null) {
                val magic = source.read(offset, 4)
                val cut = offset + OggPages.HEADER_SIZE > source.length ||
                    (magic != null && MAGIC.indices.all { magic[it] == MAGIC[it] })
                return Parsed.Bad(if (cut) TagRefusal.TRUNCATED else TagRefusal.OGG_MALFORMED_PAGES)
            }
            if (page.serial != first.serial || page.isBeginning) return Parsed.Bad(TagRefusal.OGG_MULTIPLE_STREAMS)
            if (!page.crcValid) return Parsed.Bad(TagRefusal.OGG_BAD_PAGE_CRC)
            pages += page
            offset += page.size
            val (packets, pagesClosed) = OggPages.packets(pages) ?: return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
            if (packets.size > kind.headerPackets) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
            if (packets.size == kind.headerPackets) {
                // Audio must start on a fresh page; a header page that runs into audio is malformed.
                if (!pagesClosed) return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
                // A rewrite numbers header pages on from the first one; a gap among them would move.
                if (pages.zipWithNext().any { (a, b) -> b.sequence != a.sequence + 1 }) {
                    return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
                }
                return Parsed.Ok(Layout(kind, first, pages, packets[0], packets.getOrNull(1), offset))
            }
            if (pages.size > MAX_HEADER_PAGES || offset > MAX_HEADER_BYTES) {
                return Parsed.Bad(TagRefusal.OGG_MALFORMED_PAGES)
            }
        }
    }

    private fun comment(layout: Layout): Comment? {
        val packet = layout.comment
        if (!startsWith(packet, layout.kind.prefix)) return null
        val (block, end) = VorbisComments.decode(packet, layout.kind.prefix.size) ?: return null
        return when (layout.kind) {
            // RFC 7845 §5.2: data after the comments whose first byte has its low bit set must be kept.
            Kind.OPUS -> {
                val tail = packet.copyOfRange(end, packet.size)
                Comment(block, tail, tailIsPadding = tail.isEmpty() || (tail[0].toInt() and 1) == 0)
            }
            // Vorbis ends with a framing bit; anything after it is undefined, so only zeros are padding.
            Kind.VORBIS -> {
                if (end >= packet.size || (packet[end].toInt() and 1) == 0) return null
                val tail = packet.copyOfRange(end + 1, packet.size)
                Comment(block, tail, tailIsPadding = tail.all { it == 0.toByte() })
            }
        }
    }

    /** (entry index, picture) of the cover among the METADATA_BLOCK_PICTURE entries. */
    private fun coverEntry(entries: List<VorbisEntry>): Pair<Int, FlacPicture>? {
        val pictures = entries.withIndex()
            .filter { it.value.key == PICTURE_KEY }
            .mapNotNull { (index, entry) ->
                Base64Codec.decode(entry.value)?.let { FlacPicture.decode(it) }?.let { index to it }
            }
        return CoverTarget.index(pictures.map { it.second.type })?.let { pictures[it] }
    }

    private fun refused(source: RandomAccessSource, reason: TagRefusal): TagSnapshot {
        val opus = OggPages.readAt(source, 0)?.payload?.let { startsWith(it, OPUS_HEAD) } ?: true
        return if (opus) {
            TagSnapshot(TagFormat.OPUS, "Opus", refusal = reason)
        } else {
            TagSnapshot(TagFormat.VORBIS, "Vorbis", refusal = reason)
        }
    }

    override fun read(source: RandomAccessSource): TagSnapshot {
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return refused(source, parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val comment = comment(layout) ?: return refused(source, TagRefusal.OGG_MALFORMED_PAGES)
        val entries = comment.block.entries
        val cover = coverEntry(entries)
        val next = cover?.let { chosen -> coverEntry(entries.filterIndexed { index, _ -> index != chosen.first }) }
        return VorbisFields.read(entries).toSnapshot(
            format = layout.kind.format,
            version = layout.kind.label,
            cover = cover?.second?.let { CoverInfo.of(it.data, it.mime) },
            otherPictures = entries.count { it.key == PICTURE_KEY } - (if (cover != null) 1 else 0),
            nextCover = next?.second?.let { CoverInfo.of(it.data, it.mime) },
            pictures = entries.filter { it.key == PICTURE_KEY }
                .map { entry ->
                    Base64Codec.decode(entry.value)?.let { FlacPicture.decode(it) }?.let { Crc32.of(it.data) }
                        ?: Crc32.of(entry.raw)
                }
                .sorted(),
        )
    }

    override fun readCover(source: RandomAccessSource): CoverPicture? {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return null
        val comment = comment(layout) ?: return null
        return coverEntry(comment.block.entries)?.second?.let { CoverPicture(it.data, it.mime) }
    }

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return WritePlan.Refused(parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val comment = comment(layout) ?: return WritePlan.Refused(TagRefusal.OGG_MALFORMED_PAGES)
        val before = comment.block.entries
        val after = applyCover(VorbisFields.apply(before, normalized), normalized.cover)
        if (before.size == after.size && before.indices.all { before[it].raw.contentEquals(after[it].raw) }) {
            return WritePlan.NoChange
        }

        val framing = if (layout.kind == Kind.VORBIS) byteArrayOf(1) else ByteArray(0)
        val body = layout.kind.prefix + VorbisComments(comment.block.vendor, after).encode() + framing
        val preserved = if (comment.tailIsPadding) null else comment.tail
        val minimum = body.size + (preserved?.size ?: 0)
        val pageCount = layout.headerPages.size
        val headerBytes = layout.headerPages.sumOf { it.size.toLong() }
        val setupCost = layout.setup?.let { cost(it.size) } ?: 0L
        val target = headerBytes - OggPages.HEADER_SIZE.toLong() * pageCount - setupCost

        val inPlaceLength = if (preserved == null) packetLengthFor(target, minimum) else minimum.takeIf { cost(it) == target }
        if (inPlaceLength != null) {
            val packet = if (preserved != null) body + preserved else body + ByteArray(inPlaceLength - body.size)
            val pages = OggPages.layout(listOfNotNull(packet, layout.setup), pageCount)
            if (pages != null) {
                val old = source.read(layout.first.size.toLong(), headerBytes.toInt())
                    ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
                return ByteDiff.patchOrNoChange(layout.first.size.toLong(), old, headerPages(layout, pages), source.length, source.length)
            }
        }

        val packet = if (preserved != null) body + preserved else body + ByteArray(TagSpace.SPARE_BYTES)
        val packets = listOfNotNull(packet, layout.setup)
        val count = OggPages.minimumPages(packets)
        val pages = OggPages.layout(packets, count) ?: return WritePlan.Refused(TagRefusal.OGG_MALFORMED_PAGES)
        val delta = count - pageCount
        val rest = source.length - layout.audioStart
        val audio = if (delta == 0) {
            OutputSegment.Copy(layout.audioStart, rest)
        } else {
            OutputSegment.Transformed(layout.audioStart, rest, OggRenumberer(layout.first.serial, delta))
        }
        return WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Copy(0, layout.first.size.toLong()),
                OutputSegment.Bytes(headerPages(layout, pages)),
                audio,
            ),
        )
    }

    /** Bytes a packet of [length] occupies in pages: its payload plus its lacing values. */
    private fun cost(length: Int): Long = length.toLong() + length / 255 + 1

    /** The packet length at least [minimum] whose [cost] is exactly [target], or null. */
    private fun packetLengthFor(target: Long, minimum: Int): Int? {
        if (target < cost(minimum)) return null
        val estimate = (target * 255 / 256).toInt()
        for (length in maxOf(minimum, estimate - 3)..estimate + 3) {
            if (cost(length) == target) return length
        }
        return null
    }

    private fun headerPages(layout: Layout, pages: List<OggPages.PageContent>): ByteArray {
        val sink = ByteArraySink()
        val firstSequence = layout.headerPages.first().sequence
        for ((i, content) in pages.withIndex()) {
            sink.write(
                OggPages.serialize(
                    headerType = if (content.continues) 1 else 0,
                    granule = if (content.completesPacket) 0L else -1L,
                    serial = layout.first.serial,
                    sequence = firstSequence + i,
                    lacing = content.lacing,
                    payload = content.payload,
                ),
            )
        }
        return sink.toByteArray()
    }

    private fun applyCover(entries: List<VorbisEntry>, cover: CoverEdit): List<VorbisEntry> {
        if (cover == CoverEdit.Keep) return entries
        val target = coverEntry(entries)
        return when (cover) {
            CoverEdit.Keep -> entries
            CoverEdit.Remove -> if (target == null) entries else entries.filterIndexed { i, _ -> i != target.first }
            is CoverEdit.Replace -> {
                val base = checkNotNull(FlacPicture.frontCover(cover.bytes, cover.mime)) { "validated by EditChecks" }
                val picture = FlacPicture(
                    FlacPicture.FRONT_COVER, base.mime, target?.second?.description ?: "",
                    base.width, base.height, base.depth, 0, base.data,
                )
                val entry = VorbisEntry.of(PICTURE_KEY, Base64Codec.encode(picture.encode()))
                if (target == null) entries + entry else entries.toMutableList().also { it[target.first] = entry }
            }
        }
    }

    /**
     * Every audio page as a renumbering rewrite must leave it: its fields and data, whether its
     * checksum holds, and its step from the previous page's sequence number (not the number itself,
     * which the rewrite shifts). Bytes after the last whole page (a cut-off page, an appended
     * trailer) are digested as they are, so an editable stream always has a digest.
     */
    override fun audioDigest(source: RandomAccessSource): Long? {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return null
        val crc = Crc32()
        val fields = ByteArray(18)
        var previous = layout.headerPages.last().sequence
        var offset = layout.audioStart
        while (offset < source.length) {
            val page = OggPages.readAt(source, offset)
            if (page == null) {
                crc.update(NOT_A_PAGE)
                crc.update(Digests.crc32(source, offset, source.length - offset)?.let { longBytes(it) } ?: return null)
                break
            }
            fields[0] = page.headerType.toByte()
            OggPages.putLe64(fields, 1, page.granule)
            OggPages.putLe32(fields, 9, page.serial)
            OggPages.putLe32(fields, 13, page.sequence - previous)
            fields[17] = if (page.crcValid) 1 else 0
            previous = page.sequence
            crc.update(fields)
            crc.update(ByteArray(page.lacing.size) { page.lacing[it].toByte() })
            crc.update(page.payload)
            offset += page.size
        }
        return crc.value
    }

    private fun longBytes(value: Long): ByteArray = ByteArray(8) { (value ushr (8 * it)).toByte() }

    override fun inventory(source: RandomAccessSource): List<String> {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return emptyList()
        val comment = comment(layout) ?: return emptyList()
        val out = ArrayList<String>()
        out += "identification:${Crc32.of(layout.first.payload)}"
        layout.setup?.let { out += "setup:${Crc32.of(it)}" }
        out += "vendor:${Crc32.of(comment.block.vendor)}"
        // Pictures are pinned by TagSnapshot.pictures, which also knows which one an edit changes.
        comment.block.entries
            .filter { it.key !in VorbisFields.MANAGED && it.key != PICTURE_KEY }
            .forEach { out += VorbisFields.inventoryEntry(it) }
        if (!comment.tailIsPadding) out += "tail:${Crc32.of(comment.tail)}"
        return out
    }
}

/** RFC 4648 base64 (standard alphabet), for METADATA_BLOCK_PICTURE. Padding optional on input. */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = (b(i) shl 16) or (b(i + 1) shl 8) or b(i + 2)
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = b(i) shl 16
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append("==")
            }
            2 -> {
                val n = (b(i) shl 16) or (b(i + 1) shl 8)
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                    .append(ALPHABET[n ushr 6 and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** The decoded bytes, or null for any character outside the alphabet or an impossible length. */
    fun decode(text: String): ByteArray? {
        val clean = text.trimEnd('=')
        if (clean.length % 4 == 1) return null
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var p = 0
        for (c in clean) {
            val value = if (c.code < 128) DECODE[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[p++] = (buffer ushr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out
    }
}
