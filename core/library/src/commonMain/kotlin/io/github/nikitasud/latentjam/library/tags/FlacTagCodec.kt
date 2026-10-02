/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * FLAC: `fLaC`, STREAMINFO, metadata blocks, audio frames.
 *
 * The whole metadata region is rebuilt to its current size whenever it fits — comments and the
 * cover rewritten, every other block byte for byte, the rest handed to PADDING — so the audio never
 * moves. Only a region that cannot hold the edit is laid out anew, with [TagSpace.SPARE_BYTES] spare.
 */
internal object FlacTagCodec : TagCodec {
    private val MAGIC = "fLaC".encodeToByteArray()
    private const val STREAMINFO = 0
    private const val PADDING = 1
    private const val VORBIS_COMMENT = 4
    private const val PICTURE = 6
    private const val INVALID = 127
    private const val STREAMINFO_LENGTH = 34
    private const val MAX_BLOCK = 0xFFFFFF
    private const val MAX_BLOCKS = 10_000
    private val NEW_VENDOR = "LatentJam".encodeToByteArray()

    private class Block(val type: Int, val body: ByteArray)

    private class Layout(val blocks: List<Block>, val audioStart: Long)

    private sealed interface Parsed {
        class Ok(val layout: Layout) : Parsed
        class Bad(val reason: TagRefusal) : Parsed
    }

    /** The decoded comments and cover; [targetBlock] indexes [Layout.blocks]. */
    private class Parts(
        val comments: VorbisComments?,
        val target: FlacPicture?,
        val targetBlock: Int?,
        /** The picture that would become the cover if [target] were removed. */
        val next: FlacPicture?,
    )

    override fun recognizes(head: ByteArray): Boolean =
        head.size >= 4 && MAGIC.indices.all { head[it] == MAGIC[it] }

    private fun parse(source: RandomAccessSource): Parsed {
        val magic = source.read(0, 4) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
        if (!recognizes(magic)) return Parsed.Bad(TagRefusal.UNSUPPORTED_FORMAT)
        val blocks = ArrayList<Block>()
        var position = 4L
        while (true) {
            val header = source.read(position, 4) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == INVALID) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
            if (blocks.isEmpty() && (type != STREAMINFO || length != STREAMINFO_LENGTH)) {
                return Parsed.Bad(TagRefusal.FLAC_STREAMINFO_NOT_FIRST)
            }
            if (blocks.isNotEmpty() && type == STREAMINFO) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
            val body = source.read(position + 4, length) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
            blocks += Block(type, body)
            position += 4 + length
            if (last) break
            if (blocks.size > MAX_BLOCKS) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
        }
        if (blocks.count { it.type == VORBIS_COMMENT } > 1) return Parsed.Bad(TagRefusal.FLAC_MALFORMED_METADATA)
        return Parsed.Ok(Layout(blocks, position))
    }

    /** Null when the comment block does not decode to exactly its own length. */
    private fun parts(layout: Layout): Parts? {
        val commentBlock = layout.blocks.firstOrNull { it.type == VORBIS_COMMENT }
        val comments = if (commentBlock == null) {
            null
        } else {
            val (decoded, end) = VorbisComments.decode(commentBlock.body, 0) ?: return null
            if (end != commentBlock.body.size) return null
            decoded
        }
        val pictures = layout.blocks.withIndex()
            .filter { it.value.type == PICTURE }
            .mapNotNull { (index, block) -> FlacPicture.decode(block.body)?.let { index to it } }
        val chosen = CoverTarget.index(pictures.map { it.second.type })?.let { pictures[it] }
        val rest = pictures.filter { it.first != chosen?.first }
        val next = if (chosen == null) null else CoverTarget.index(rest.map { it.second.type })?.let { rest[it].second }
        return Parts(comments, chosen?.second, chosen?.first, next)
    }

    override fun read(source: RandomAccessSource): TagSnapshot {
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return TagSnapshot(TagFormat.FLAC, "FLAC", refusal = parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val parts = parts(layout)
            ?: return TagSnapshot(TagFormat.FLAC, "FLAC", refusal = TagRefusal.FLAC_MALFORMED_METADATA)
        val pictureCount = layout.blocks.count { it.type == PICTURE }
        return VorbisFields.read(parts.comments?.entries.orEmpty()).toSnapshot(
            format = TagFormat.FLAC,
            version = "FLAC",
            cover = parts.target?.let { CoverInfo.of(it.data, it.mime) },
            otherPictures = pictureCount - (if (parts.target != null) 1 else 0),
            nextCover = parts.next?.let { CoverInfo.of(it.data, it.mime) },
            pictures = layout.blocks.filter { it.type == PICTURE }
                .map { block -> FlacPicture.decode(block.body)?.let { Crc32.of(it.data) } ?: Crc32.of(block.body) }
                .sorted(),
        )
    }

    override fun readCover(source: RandomAccessSource): CoverPicture? {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return null
        return parts(layout)?.target?.let { CoverPicture(it.data, it.mime) }
    }

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return WritePlan.Refused(parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val parts = parts(layout) ?: return WritePlan.Refused(TagRefusal.FLAC_MALFORMED_METADATA)
        val blocks = rebuild(layout, parts, normalized)
        if (blocks.any { it.body.size > MAX_BLOCK }) return WritePlan.Refused(TagRefusal.FLAC_BLOCK_TOO_LARGE)

        val unchanged = layout.blocks.filter { it.type != PADDING }
        if (unchanged.size == blocks.size && unchanged.indices.all { i ->
                unchanged[i].type == blocks[i].type && unchanged[i].body.contentEquals(blocks[i].body)
            }
        ) {
            return WritePlan.NoChange
        }

        val region = layout.audioStart - 4
        val remainder = region - blocks.sumOf { 4L + it.body.size }
        if (remainder == 0L || remainder >= 4) {
            val oldRegion = source.read(4, region.toInt()) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
            val newRegion = serialize(if (remainder == 0L) blocks else blocks + paddingBlocks(remainder))
            return ByteDiff.patchOrNoChange(4, oldRegion, newRegion, source.length, source.length)
        }
        val newRegion = serialize(blocks + paddingBlocks(4L + TagSpace.SPARE_BYTES))
        return WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Bytes(MAGIC + newRegion),
                OutputSegment.Copy(layout.audioStart, source.length - layout.audioStart),
            ),
        )
    }

    /** Every non-padding block in order, with comments and the cover replaced as [edits] say. */
    private fun rebuild(layout: Layout, parts: Parts, edits: TagEdits): List<Block> {
        val entries = VorbisFields.apply(parts.comments?.entries.orEmpty(), edits)
        val commentBody = if (parts.comments != null || entries.isNotEmpty()) {
            VorbisComments(parts.comments?.vendor ?: NEW_VENDOR, entries).encode()
        } else {
            null
        }
        val cover = edits.cover
        val newPicture = (cover as? CoverEdit.Replace)?.let { replace ->
            FlacPicture.frontCover(replace.bytes, replace.mime)?.let { picture ->
                FlacPicture(
                    FlacPicture.FRONT_COVER, picture.mime, parts.target?.description ?: "",
                    picture.width, picture.height, picture.depth, 0, picture.data,
                )
            }
        }
        val out = ArrayList<Block>(layout.blocks.size + 2)
        var commentPlaced = false
        var picturePlaced = false
        for ((index, block) in layout.blocks.withIndex()) {
            when {
                block.type == PADDING -> Unit
                block.type == VORBIS_COMMENT -> {
                    commentBody?.let { out += Block(VORBIS_COMMENT, it) }
                    commentPlaced = true
                }
                index == parts.targetBlock && cover != CoverEdit.Keep -> {
                    newPicture?.let { out += Block(PICTURE, it.encode()) }
                    picturePlaced = true
                }
                else -> out += block
            }
        }
        if (!commentPlaced && commentBody != null) out.add(1, Block(VORBIS_COMMENT, commentBody))
        if (!picturePlaced && newPicture != null) {
            val afterComments = out.indexOfFirst { it.type == VORBIS_COMMENT }.let { if (it < 0) 1 else it + 1 }
            out.add(afterComments, Block(PICTURE, newPicture.encode()))
        }
        return out
    }

    /** PADDING blocks occupying exactly [bytes] (headers included); [bytes] is 0 or at least 4. */
    private fun paddingBlocks(bytes: Long): List<Block> {
        val out = ArrayList<Block>()
        var left = bytes
        while (left > 0) {
            val body = if (left - 4 <= MAX_BLOCK) left - 4 else minOf(MAX_BLOCK.toLong(), left - 8)
            out += Block(PADDING, ByteArray(body.toInt()))
            left -= 4 + body
        }
        return out
    }

    private fun serialize(blocks: List<Block>): ByteArray {
        val out = ByteArray(blocks.sumOf { 4 + it.body.size })
        var p = 0
        for ((i, block) in blocks.withIndex()) {
            val last = if (i == blocks.lastIndex) 0x80 else 0
            out[p] = (last or block.type).toByte()
            out[p + 1] = (block.body.size ushr 16).toByte()
            out[p + 2] = (block.body.size ushr 8).toByte()
            out[p + 3] = block.body.size.toByte()
            block.body.copyInto(out, p + 4)
            p += 4 + block.body.size
        }
        return out
    }

    override fun audioDigest(source: RandomAccessSource): Long? {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return null
        return Digests.crc32(source, layout.audioStart, source.length - layout.audioStart)
    }

    override fun inventory(source: RandomAccessSource): List<String> {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return emptyList()
        val parts = parts(layout) ?: return emptyList()
        val out = ArrayList<String>()
        // The vendor string is checked by its own test: a file without comments gains one on its first edit.
        parts.comments?.entries?.filter { it.key !in VorbisFields.MANAGED }?.forEach { out += VorbisFields.inventoryEntry(it) }
        // Pictures are pinned by TagSnapshot.pictures, which also knows which one an edit changes.
        layout.blocks
            .filter { it.type != PADDING && it.type != VORBIS_COMMENT && it.type != PICTURE }
            .forEach { out += "block${it.type}:${Crc32.of(it.body)}" }
        return out
    }
}
