/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * M4A: tags in `moov/udta/meta/ilst`, audio in `mdat`, chunk offsets (absolute file positions) in
 * `stco`/`co64`. Edits stay inside `moov` whenever free space allows; otherwise `moov` changes size
 * and exactly the chunk offsets that point past it move with the audio.
 */
internal object Mp4TagCodec : TagCodec {
    private const val MAX_MOOV = 64L shl 20
    private const val TEXT = 1
    private const val JPEG = 13
    private const val PNG = 14
    private const val BMP = 27
    private const val ARTISTS = "ARTISTS"
    private val MANAGED = setOf("©nam", "©ART", "©alb", "aART", "©gen", "gnre", "©day", "trkn", "disk", "©lyr", "covr")
    private val DRM_ENTRIES = setOf("drms", "drmi", "enca", "encv", "encs", "enct")
    /** Boxes that hold absolute file offsets the codec cannot see or move (`cmov` compresses a whole moov). */
    private val OFFSET_BOXES = setOf("saio", "iloc", "cmov")
    private val FREE = setOf("free", "skip")

    /**
     * Top-level atoms known to hold no offset into the rest of the file. Moving the audio (case C)
     * is refused when any other atom is present: it might address the bytes that move.
     */
    private val MOVABLE_TOP_LEVEL = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "pdin")
    private const val MAX_SAMPLE_ENTRIES = 64

    /** `hdlr` payload iTunes writes for a metadata `meta` box. */
    private val MDIR_HANDLER = ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9)

    private class Layout(val atoms: List<Mp4Atom>, val moovAtom: Mp4Atom, val moovBytes: ByteArray, val moov: Mp4Box)

    private sealed interface Parsed {
        class Ok(val layout: Layout) : Parsed
        class Bad(val reason: TagRefusal) : Parsed
    }

    private class Tags(val meta: Mp4Box, val ilst: Mp4Box)

    override fun recognizes(head: ByteArray): Boolean = head.size >= 8 && Mp4Boxes.typeOf(head, 4) == "ftyp"

    private fun parse(source: RandomAccessSource): Parsed {
        val atoms = Mp4Boxes.topLevel(source) ?: return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        if (atoms.any { it.type == "moof" || it.type == "mfra" || it.type == "sidx" }) {
            return Parsed.Bad(TagRefusal.MP4_FRAGMENTED)
        }
        // A top-level ISO meta may carry an iloc that addresses items anywhere in the file.
        if (atoms.any { it.type == "meta" }) return Parsed.Bad(TagRefusal.MP4_UNKNOWN_OFFSET_BOX)
        val moovs = atoms.filter { it.type == "moov" }
        if (moovs.size != 1) return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        val atom = moovs[0]
        if (atom.size > MAX_MOOV) return Parsed.Bad(TagRefusal.MP4_TAGS_TOO_LARGE)
        val bytes = source.read(atom.offset, atom.size.toInt()) ?: return Parsed.Bad(TagRefusal.TRUNCATED)
        val tree = Mp4Boxes.parse(bytes) ?: return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        // Only a tree that reproduces its own bytes exactly is safe to edit and write back.
        if (!tree.serialize().contentEquals(bytes)) return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
        val boxes = Mp4Boxes.walk(tree).toList()
        for (stsd in boxes.filter { it.type == "stsd" }) {
            // Entries that do not add up could hide the one that marks the file protected.
            val types = sampleEntryTypes(stsd) ?: return Parsed.Bad(TagRefusal.MP4_MALFORMED_ATOMS)
            if (types.any { it in DRM_ENTRIES }) return Parsed.Bad(TagRefusal.MP4_DRM_PROTECTED)
        }
        if (boxes.any { it.type in OFFSET_BOXES }) return Parsed.Bad(TagRefusal.MP4_UNKNOWN_OFFSET_BOX)
        return Parsed.Ok(Layout(atoms, atom, bytes, tree))
    }

    /** The sample entry types, or null unless exactly the declared entries tile the box. */
    private fun sampleEntryTypes(stsd: Mp4Box): List<String>? {
        val p = stsd.payload
        if (p.size < 8) return null
        val count = Mp4Boxes.be32(p, 4)
        if (count > MAX_SAMPLE_ENTRIES) return null
        val types = ArrayList<String>()
        var offset = 8
        repeat(count.toInt()) {
            if (offset + 8 > p.size) return null
            val size = Mp4Boxes.be32(p, offset)
            // Past the end also stops a 2 GiB size from wrapping the Int offset negative.
            if (size < 8 || size > p.size - offset) return null
            types += Mp4Boxes.typeOf(p, offset + 4)
            offset += size.toInt()
        }
        return types.takeIf { offset == p.size }
    }

    /**
     * The iTunes-style `meta` (handler `mdir`) — preferring one that holds an `ilst`. A QuickTime
     * `mdta` meta keys its `ilst` items by index, not by `©nam`-style types, and is left alone.
     */
    private fun tagsMeta(moov: Mp4Box): Mp4Box? {
        val candidates = listOfNotNull(moov.child("udta")?.child("meta"), moov.child("meta"))
            .filter { meta -> meta.child("hdlr")?.payload?.let { it.size >= 12 && Mp4Boxes.typeOf(it, 8) == "mdir" } == true }
        return candidates.firstOrNull { it.child("ilst") != null } ?: candidates.firstOrNull()
    }

    private fun findTags(moov: Mp4Box): Tags? {
        val meta = tagsMeta(moov) ?: return null
        val ilst = meta.child("ilst") ?: return null
        return Tags(meta, ilst)
    }

    /** Null when `udta` already holds some other kind of `meta`, next to which no tags `meta` can go. */
    private fun createTags(moov: Mp4Box): Tags? {
        val meta = tagsMeta(moov) ?: run {
            val udta = moov.child("udta") ?: Mp4Box.container("udta", emptyList()).also { moov.children!!.add(it) }
            if (udta.child("meta") != null) return null
            Mp4Box.container("meta", listOf(Mp4Box.leaf("hdlr", MDIR_HANDLER)), prefix = ByteArray(4))
                .also { udta.children!!.add(it) }
        }
        val ilst = Mp4Box.container("ilst", emptyList()).also { meta.children!!.add(it) }
        return Tags(meta, ilst)
    }

    // ------------------------------------------------------------------ items

    private fun dataBoxes(item: Mp4Box): List<Mp4Box> = item.children.orEmpty().filter { it.type == "data" }

    private fun value(data: Mp4Box): ByteArray? = data.payload.takeIf { it.size >= 8 }?.copyOfRange(8, data.payload.size)

    private fun code(data: Mp4Box): Int = if (data.payload.size >= 4) Mp4Boxes.be24(data.payload, 1) else -1

    private fun text(items: List<Mp4Box>, type: String): String? =
        items.firstOrNull { it.type == type }?.let(::dataBoxes)?.firstOrNull { code(it) == TEXT }
            ?.let(::value)?.decodeToString()?.takeIf { it.isNotEmpty() }

    private fun pair(items: List<Mp4Box>, type: String): Pair<Int?, Int?> {
        val bytes = items.firstOrNull { it.type == type }?.let(::dataBoxes)?.firstOrNull()?.let(::value)
        if (bytes == null || bytes.size < 6) return null to null
        return Mp4Boxes.be16(bytes, 2).takeIf { it > 0 } to Mp4Boxes.be16(bytes, 4).takeIf { it > 0 }
    }

    private fun freeformName(item: Mp4Box): String? =
        item.child("name")?.payload?.takeIf { it.size >= 4 }?.let { it.decodeToString(4, it.size) }

    private fun isArtists(item: Mp4Box): Boolean = item.type == "----" && freeformName(item)?.equals(ARTISTS, ignoreCase = true) == true

    private fun coverInfo(data: Mp4Box): CoverInfo? {
        val bytes = value(data) ?: return null
        val mime = when (code(data)) {
            JPEG -> ImageProbe.JPEG
            PNG -> ImageProbe.PNG
            BMP -> "image/bmp"
            else -> ImageProbe.probe(bytes)?.mime ?: "application/octet-stream"
        }
        return CoverInfo.of(bytes, mime)
    }

    private fun dataBox(code: Int, value: ByteArray): Mp4Box =
        Mp4Box.leaf("data", byteArrayOf(0, (code ushr 16).toByte(), (code ushr 8).toByte(), code.toByte()) + ByteArray(4) + value)

    // ------------------------------------------------------------------ read

    override fun read(source: RandomAccessSource): TagSnapshot {
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return TagSnapshot(TagFormat.MP4, "MP4", refusal = parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val items = findTags(layout.moov)?.ilst?.children.orEmpty()
        // Every image of every covr item, in order: the first is the cover, the rest are kept.
        val covers = items.filter { it.type == "covr" }.flatMap(::dataBoxes)
        val (trackNumber, trackTotal) = pair(items, "trkn")
        val (discNumber, discTotal) = pair(items, "disk")
        return TagSnapshot(
            format = TagFormat.MP4,
            version = "MP4",
            title = text(items, "©nam"),
            artist = text(items, "©ART"),
            album = text(items, "©alb"),
            albumArtist = text(items, "aART"),
            genre = text(items, "©gen") ?: numericGenre(items),
            year = text(items, "©day"),
            trackNumber = trackNumber,
            trackTotal = trackTotal,
            discNumber = discNumber,
            discTotal = discTotal,
            lyrics = text(items, "©lyr")?.trim()?.ifEmpty { null },
            cover = covers.firstOrNull()?.let(::coverInfo),
            otherPictures = maxOf(0, covers.size - 1),
            nextCover = covers.getOrNull(1)?.let(::coverInfo),
            pictures = covers.mapNotNull { value(it)?.let { bytes -> Crc32.of(bytes) } }.sorted(),
            artists = items.filter(::isArtists)
                .flatMap { dataBoxes(it) }
                .mapNotNull { value(it)?.decodeToString() }
                .flatMap { TagFacts.splitArtists(it) },
        )
    }

    /** iTunes' old `gnre`: an ID3v1 genre index plus one. */
    private fun numericGenre(items: List<Mp4Box>): String? {
        val bytes = items.firstOrNull { it.type == "gnre" }?.let(::dataBoxes)?.firstOrNull()?.let(::value)
        if (bytes == null || bytes.size < 2) return null
        val index = Mp4Boxes.be16(bytes, 0) - 1
        return GenreTags.split("($index)").firstOrNull()
    }

    // ------------------------------------------------------------------ plan

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val layout = when (val parsed = parse(source)) {
            is Parsed.Bad -> return WritePlan.Refused(parsed.reason)
            is Parsed.Ok -> parsed.layout
        }
        val moov = layout.moov
        val existing = findTags(moov)
        val tags = existing ?: createTags(moov) ?: return WritePlan.Refused(TagRefusal.MP4_MALFORMED_ATOMS)
        val oldItems = tags.ilst.children!!.toList()
        val oldIlstSize = tags.ilst.size
        val newItems = applyEdits(oldItems, normalized)
        if (oldItems.size == newItems.size &&
            oldItems.indices.all { oldItems[it].serialize().contentEquals(newItems[it].serialize()) }
        ) {
            return WritePlan.NoChange
        }
        tags.ilst.children.clear()
        tags.ilst.children.addAll(newItems)

        val siblings = tags.meta.children!!
        val at = siblings.indexOf(tags.ilst)
        var freeEnd = at + 1
        while (freeEnd < siblings.size && siblings[freeEnd].type == "free") freeEnd++
        val innerFree = (at + 1 until freeEnd).sumOf { siblings[it].size }
        val atom = layout.moovAtom
        val length = source.length

        // A: the new ilst fits where the old one and its free space were.
        if (existing != null) {
            val left = oldIlstSize + innerFree - tags.ilst.size
            if (left == 0L || left >= 8) {
                replaceRange(siblings, at + 1, freeEnd, if (left > 0) listOf(freeBox(left)) else emptyList())
                return ByteDiff.patchOrNoChange(atom.offset, layout.moovBytes, moov.serialize(), length, length)
            }
        }

        // The tags are laid out anew: fold the old free space in and leave spare room after ilst.
        replaceRange(siblings, at + 1, freeEnd, listOf(freeBox(TagSpace.SPARE_BYTES.toLong())))
        val withSpare = moov.serialize()
        replaceRange(siblings, at + 1, at + 2, emptyList())
        val withoutSpare = moov.serialize()

        // B1 / B2: moov keeps its offset and grows into — or shrinks away from — the free space after it.
        val index = layout.atoms.indexOf(atom)
        var topFreeEnd = index + 1
        while (topFreeEnd < layout.atoms.size && layout.atoms[topFreeEnd].type in FREE) topFreeEnd++
        // Free space too big to hold in memory (or to count in an Int) is left alone, and passed over.
        if (layout.atoms[topFreeEnd - 1].end - atom.offset > MAX_MOOV) topFreeEnd = index + 1
        val available = layout.atoms[topFreeEnd - 1].end - atom.offset
        // moov and the free space after it are overwritten: no chunk may live there.
        offsetsInside(moov, atom.offset, atom.offset + available)?.let { return WritePlan.Refused(it) }
        val region = source.read(atom.offset, available.toInt()) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
        for (candidate in listOf(withSpare, withoutSpare)) {
            val left = available - candidate.size
            if (left == 0L || left >= 8) {
                val filled = if (left > 0) candidate + freeBox(left).serialize() else candidate
                return ByteDiff.patchOrNoChange(atom.offset, region, filled, length, length)
            }
        }
        if (topFreeEnd == layout.atoms.size) {
            return ByteDiff.patchOrNoChange(atom.offset, region, withSpare, atom.offset + withSpare.size, length)
        }

        // C: moov changes size where it is; everything after it moves, and so must its chunk offsets.
        if (layout.atoms.any { it.type !in MOVABLE_TOP_LEVEL }) return WritePlan.Refused(TagRefusal.MP4_UNKNOWN_OFFSET_BOX)
        val shifted = Mp4Boxes.parse(withSpare) ?: return WritePlan.Refused(TagRefusal.MP4_MALFORMED_ATOMS)
        shiftOffsets(shifted, atom.offset, atom.end, withSpare.size - atom.size)?.let { return WritePlan.Refused(it) }
        return WritePlan.StreamingRewrite(
            listOf(
                OutputSegment.Copy(0, atom.offset),
                OutputSegment.Bytes(shifted.serialize()),
                OutputSegment.Copy(atom.end, length - atom.end),
            ),
        )
    }

    private fun freeBox(size: Long): Mp4Box = Mp4Box.leaf("free", ByteArray((size - 8).toInt()))

    private fun replaceRange(list: MutableList<Mp4Box>, from: Int, to: Int, with: List<Mp4Box>) {
        repeat(to - from) { list.removeAt(from) }
        list.addAll(from, with)
    }

    /** Every chunk offset of every `stco`/`co64` in [moov]; null when a table does not add up. */
    private fun chunkOffsets(moov: Mp4Box): List<LongArray>? {
        val tables = ArrayList<LongArray>()
        for (box in Mp4Boxes.walk(moov)) {
            val wide = when (box.type) {
                "stco" -> false
                "co64" -> true
                else -> continue
            }
            val p = box.payload
            if (p.size < 8) return null
            val count = Mp4Boxes.be32(p, 4)
            val width = if (wide) 8 else 4
            if (8 + count * width != p.size.toLong()) return null
            tables += LongArray(count.toInt()) { i -> if (wide) Mp4Boxes.be64(p, 8 + i * 8) else Mp4Boxes.be32(p, 8 + i * 4) }
        }
        return tables
    }

    /** A refusal when any chunk offset points into [[start], [end]), a region about to be overwritten. */
    private fun offsetsInside(moov: Mp4Box, start: Long, end: Long): TagRefusal? {
        val tables = chunkOffsets(moov) ?: return TagRefusal.MP4_MALFORMED_ATOMS
        return TagRefusal.MP4_OFFSET_INSIDE_REWRITE.takeIf { tables.any { offsets -> offsets.any { it in start until end } } }
    }

    /** Moves every chunk offset at or past [end] by [delta]; null when all of them could be moved. */
    private fun shiftOffsets(moov: Mp4Box, start: Long, end: Long, delta: Long): TagRefusal? {
        for (box in Mp4Boxes.walk(moov)) {
            val wide = when (box.type) {
                "stco" -> false
                "co64" -> true
                else -> continue
            }
            val p = box.payload
            if (p.size < 8) return TagRefusal.MP4_MALFORMED_ATOMS
            val count = Mp4Boxes.be32(p, 4)
            val width = if (wide) 8 else 4
            if (8 + count * width != p.size.toLong()) return TagRefusal.MP4_MALFORMED_ATOMS
            for (i in 0 until count.toInt()) {
                val at = 8 + i * width
                val offset = if (wide) Mp4Boxes.be64(p, at) else Mp4Boxes.be32(p, at)
                if (offset in start until end) return TagRefusal.MP4_OFFSET_INSIDE_REWRITE
                if (offset >= end) {
                    val moved = offset + delta
                    if (!wide && moved > 0xFFFFFFFFL) return TagRefusal.MP4_OFFSET_OVERFLOW
                    if (wide) Mp4Boxes.putBe64(p, at, moved) else Mp4Boxes.putBe32(p, at, moved)
                }
            }
        }
        return null
    }

    private fun applyEdits(items: List<Mp4Box>, edits: TagEdits): List<Mp4Box> {
        val out = items.toMutableList()
        setText(out, "©nam", edits.title)
        setText(out, "©ART", edits.artist)
        if (edits.artist != null) setArtists(out, edits.artist)
        setText(out, "©alb", edits.album)
        setText(out, "aART", edits.albumArtist)
        if (edits.genre != null) {
            setText(out, "©gen", edits.genre)
            out.removeAll { it.type == "gnre" }
        }
        setText(out, "©day", edits.year)
        setPair(out, "trkn", edits.trackNumber, edits.trackTotal, defaultLength = 8)
        setPair(out, "disk", edits.discNumber, edits.discTotal, defaultLength = 6)
        setText(out, "©lyr", edits.lyrics, trimmed = true)
        setCover(out, edits.cover)
        return out
    }

    /** Null keeps; "" removes every item of [type]; a value replaces the first and drops duplicates. */
    private fun setText(items: MutableList<Mp4Box>, type: String, value: String?, trimmed: Boolean = false) {
        if (value == null) return
        // An item that already says exactly this is kept byte for byte (lyrics: as they read, trimmed).
        val current = text(items, type)?.let { if (trimmed) it.trim() else it }
        if (value.isNotEmpty() && items.count { it.type == type } == 1 && current == value) return
        replaceItem(items, type, if (value.isEmpty()) null else Mp4Box.container(type, listOf(dataBox(TEXT, value.encodeToByteArray()))))
    }

    private fun replaceItem(items: MutableList<Mp4Box>, type: String, replacement: Mp4Box?) {
        val first = items.indexOfFirst { it.type == type }
        if (first < 0) {
            replacement?.let { items += it }
            return
        }
        if (replacement != null) items[first] = replacement
        val keep = if (replacement != null) first else -1
        val iterator = items.listIterator()
        var index = 0
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.type == type && index != keep) iterator.remove()
            index++
        }
    }

    private fun setPair(items: MutableList<Mp4Box>, type: String, number: String?, total: String?, defaultLength: Int) {
        if (number == null && total == null) return
        val (currentNumber, currentTotal) = pair(items, type)
        val n = if (number == null) currentNumber else TagNumbers.strict(number)
        val t = if (total == null) currentTotal else TagNumbers.strict(total)
        if (n == currentNumber && t == currentTotal && items.count { it.type == type } == 1) return
        if (n == null && t == null) {
            replaceItem(items, type, null)
            return
        }
        val existingLength = items.firstOrNull { it.type == type }?.let(::dataBoxes)?.firstOrNull()?.let(::value)?.size
        val value = ByteArray(maxOf(existingLength ?: defaultLength, 6))
        value[2] = ((n ?: 0) ushr 8).toByte()
        value[3] = (n ?: 0).toByte()
        value[4] = ((t ?: 0) ushr 8).toByte()
        value[5] = (t ?: 0).toByte()
        replaceItem(items, type, Mp4Box.container(type, listOf(dataBox(0, value))))
    }

    /** Rewrites the ARTISTS freeform item from a new display credit — only when the file has one. */
    private fun setArtists(items: MutableList<Mp4Box>, artist: String) {
        val first = items.indexOfFirst(::isArtists)
        if (first < 0) return
        val names = CreditedArtists.fromDisplay(artist)
        val original = items[first]
        val rebuilt = if (names.isEmpty()) {
            null
        } else {
            Mp4Box.container(
                "----",
                original.children.orEmpty().filter { it.type != "data" } + names.map { dataBox(TEXT, it.encodeToByteArray()) },
            )
        }
        var index = 0
        val iterator = items.listIterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (isArtists(item)) {
                if (index == first && rebuilt != null) iterator.set(rebuilt) else iterator.remove()
            }
            index++
        }
    }

    private fun setCover(items: MutableList<Mp4Box>, edit: CoverEdit) {
        if (edit == CoverEdit.Keep) return
        // The cover is the first image of the first covr item that holds one (see read()).
        val withImage = items.indexOfFirst { it.type == "covr" && dataBoxes(it).isNotEmpty() }
        val covrIndex = if (withImage >= 0) withImage else items.indexOfFirst { it.type == "covr" }
        val covr = items.getOrNull(covrIndex)
        // A new covr box, never the old one changed in place: plan() compares the new items with the old.
        val children = covr?.children.orEmpty().toMutableList()
        val first = children.indexOfFirst { it.type == "data" }
        when (edit) {
            CoverEdit.Keep -> Unit
            CoverEdit.Remove -> {
                if (covr == null || first < 0) return
                children.removeAt(first)
                if (children.none { it.type == "data" }) {
                    items.removeAt(covrIndex)
                } else {
                    items[covrIndex] = Mp4Box.container("covr", children)
                }
            }
            is CoverEdit.Replace -> {
                val data = dataBox(if (edit.mime == ImageProbe.PNG) PNG else JPEG, edit.bytes)
                if (first >= 0) children[first] = data else children.add(0, data)
                val rebuilt = Mp4Box.container("covr", children)
                if (covr == null) items += rebuilt else items[covrIndex] = rebuilt
            }
        }
    }

    // ------------------------------------------------------------------ verification

    override fun audioDigest(source: RandomAccessSource): Long? {
        val atoms = Mp4Boxes.topLevel(source) ?: return null
        val crc = Crc32()
        for (atom in atoms.filter { it.type == "mdat" }) {
            var position = atom.offset + atom.headerSize
            while (position < atom.end) {
                val count = minOf(WritePlans.COPY_CHUNK.toLong(), atom.end - position).toInt()
                crc.update(source.read(position, count) ?: return null)
                position += count
            }
        }
        return crc.value
    }

    override fun inventory(source: RandomAccessSource): List<String> {
        val layout = (parse(source) as? Parsed.Ok)?.layout ?: return emptyList()
        val out = ArrayList<String>()
        for (atom in layout.atoms) {
            if (atom.type == "moov" || atom.type == "mdat" || atom.type in FREE) continue
            val bytes = if (atom.size <= (1 shl 20)) source.read(atom.offset, atom.size.toInt()) else null
            out += "top:${atom.type}:${atom.size}:${bytes?.let { Crc32.of(it) } ?: "-"}"
        }
        val meta = tagsMeta(layout.moov)
        val targets = OffsetTargets(layout.atoms)
        fun visit(box: Mp4Box, path: String) {
            when {
                // moov/udta is listed item by item below; a udta anywhere deeper is pinned like the rest.
                (box.type == "udta" && path == "moov") || box === meta || box.type in FREE -> Unit
                box.children == null -> out += if (box.type == "stco" || box.type == "co64") {
                    "$path/${box.type}:${targets.identity(box)}"
                } else {
                    "$path/${box.type}:${Crc32.of(box.payload)}"
                }
                else -> box.children.forEach { visit(it, "$path/${box.type}") }
            }
        }
        layout.moov.children!!.forEach { visit(it, "moov") }
        layout.moov.child("udta")?.children
            ?.filter { it !== meta && it.type !in FREE }
            ?.forEach { out += "udta/${it.type}:${Crc32.of(it.serialize())}" }
        meta?.children
            ?.filter { it.type != "ilst" && it.type != "hdlr" && it.type !in FREE }
            ?.forEach { out += "meta/${it.type}:${Crc32.of(it.serialize())}" }
        // Pictures are pinned by TagSnapshot.pictures, which also knows which one an edit changes.
        meta?.child("ilst")?.children
            ?.filterNot { it.type in MANAGED || isArtists(it) }
            ?.forEach { out += "item:${Crc32.of(it.serialize())}" }
        return out
    }

    /**
     * Where chunk offsets point, in terms a correct edit never changes: the containing top-level
     * atom (counted without `free`/`skip`, which edits may merge or drop) and the distance into it.
     * An offset into free space or past every atom keeps its absolute value, so moving it shows.
     */
    private class OffsetTargets(private val atoms: List<Mp4Atom>) {
        private val ordinals = HashMap<Mp4Atom, Int>().also { map ->
            atoms.filter { it.type !in FREE }.forEachIndexed { i, atom -> map[atom] = i }
        }

        fun identity(table: Mp4Box): String {
            val p = table.payload
            val wide = table.type == "co64"
            val width = if (wide) 8 else 4
            val count = if (p.size >= 8) Mp4Boxes.be32(p, 4) else -1L
            if (count < 0 || 8 + count * width != p.size.toLong()) return "raw:${Crc32.of(p)}"
            val crc = Crc32()
            val pair = ByteArray(12)
            for (i in 0 until count.toInt()) {
                val offset = if (wide) Mp4Boxes.be64(p, 8 + i * 8) else Mp4Boxes.be32(p, 8 + i * 4)
                val atom = containing(offset)
                val ordinal = atom?.let { ordinals[it] }
                Mp4Boxes.putBe32(pair, 0, (ordinal ?: -1).toLong())
                Mp4Boxes.putBe64(pair, 4, if (ordinal != null) offset - atom.offset else offset)
                crc.update(pair)
            }
            return "$count:${crc.value}"
        }

        private fun containing(offset: Long): Mp4Atom? {
            var low = 0
            var high = atoms.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val atom = atoms[mid]
                when {
                    offset < atom.offset -> high = mid - 1
                    offset >= atom.end -> low = mid + 1
                    else -> return atom
                }
            }
            return null
        }
    }
}
