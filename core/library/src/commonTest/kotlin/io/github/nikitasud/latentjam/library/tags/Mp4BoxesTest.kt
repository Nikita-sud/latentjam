/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Builds MP4 files box by box. Shares no code with the codec's serializer. */
internal object Mp4Fixtures {
    fun be32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    fun type(type: String): ByteArray = ByteArray(4) { type[it].code.toByte() }

    fun leaf(type: String, payload: ByteArray): ByteArray = be32(8 + payload.size) + type(type) + payload

    fun box(type: String, vararg children: ByteArray): ByteArray =
        leaf(type, children.fold(ByteArray(0)) { all, child -> all + child })

    fun data(code: Int, value: ByteArray): ByteArray = leaf("data", byteArrayOf(0, 0, 0, code.toByte()) + ByteArray(4) + value)

    fun text(type: String, value: String): ByteArray = box(type, data(1, value.encodeToByteArray()))

    fun pair(type: String, number: Int, total: Int, length: Int = 8): ByteArray {
        val value = ByteArray(length)
        value[2] = (number ushr 8).toByte()
        value[3] = number.toByte()
        value[4] = (total ushr 8).toByte()
        value[5] = total.toByte()
        return box(type, data(0, value))
    }

    fun freeform(name: String, vararg values: String): ByteArray = box(
        "----",
        leaf("mean", ByteArray(4) + "com.apple.iTunes".encodeToByteArray()),
        leaf("name", ByteArray(4) + name.encodeToByteArray()),
        *values.map { data(1, it.encodeToByteArray()) }.toTypedArray(),
    )

    fun covers(vararg images: Pair<Int, ByteArray>): ByteArray =
        box("covr", *images.map { (code, bytes) -> data(code, bytes) }.toTypedArray())

    fun free(size: Int): ByteArray = leaf("free", ByteArray(size - 8))

    fun hdlr(): ByteArray = leaf("hdlr", ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9))

    fun meta(items: List<ByteArray>, freeAfterIlst: Int = 0, iso: Boolean = true): ByteArray = leaf(
        "meta",
        (if (iso) ByteArray(4) else ByteArray(0)) + hdlr() + box("ilst", *items.toTypedArray()) +
            (if (freeAfterIlst > 0) free(freeAfterIlst) else ByteArray(0)),
    )

    fun ftyp(): ByteArray = leaf("ftyp", "M4A ".encodeToByteArray() + ByteArray(4) + "M4A mp42isom".encodeToByteArray())

    val samples = ByteArray(2000) { (it * 3 + 1).toByte() }

    fun trak(offsets: List<Int>, sampleEntry: String = "mp4a", extraStbl: ByteArray = ByteArray(0)): ByteArray = box(
        "trak",
        leaf("tkhd", ByteArray(84)),
        box(
            "mdia",
            leaf("mdhd", ByteArray(24)),
            box(
                "minf",
                box(
                    "stbl",
                    leaf("stsd", ByteArray(4) + be32(1) + leaf(sampleEntry, ByteArray(28))),
                    leaf("stts", ByteArray(8)),
                    leaf("stsz", ByteArray(12)),
                    leaf("stco", ByteArray(4) + be32(offsets.size) + offsets.fold(ByteArray(0)) { all, o -> all + be32(o) }),
                    extraStbl,
                ),
            ),
        ),
    )

    fun moov(offsets: List<Int>, items: List<ByteArray>?, freeAfterIlst: Int = 0, iso: Boolean = true, sampleEntry: String = "mp4a", extraStbl: ByteArray = ByteArray(0)): ByteArray =
        box(
            "moov",
            leaf("mvhd", ByteArray(100)),
            trak(offsets, sampleEntry, extraStbl),
            if (items == null) ByteArray(0) else box("udta", meta(items, freeAfterIlst, iso)),
        )

    /**
     * ftyp, then moov and one mdat in either order, and optionally a top-level free after moov.
     * The two chunk offsets point at samples[0] and samples[1000] inside the mdat.
     */
    fun file(
        items: List<ByteArray>?,
        moovFirst: Boolean = true,
        freeAfterIlst: Int = 0,
        freeAfterMoov: Int = 0,
        iso: Boolean = true,
        sampleEntry: String = "mp4a",
        extraStbl: ByteArray = ByteArray(0),
    ): ByteArray {
        val ftyp = ftyp()
        val mdat = leaf("mdat", samples)
        val free = if (freeAfterMoov > 0) free(freeAfterMoov) else ByteArray(0)
        fun moovAt(mdatStart: Int) =
            moov(listOf(mdatStart + 8, mdatStart + 8 + 1000), items, freeAfterIlst, iso, sampleEntry, extraStbl)
        val moovSize = moovAt(0).size
        return if (moovFirst) {
            ftyp + moovAt(ftyp.size + moovSize + free.size) + free + mdat
        } else {
            ftyp + mdat + moovAt(ftyp.size) + free
        }
    }

    /** The chunk offsets of the first stco in [file]. */
    fun chunkOffsets(file: ByteArray): List<Long> {
        val source = ByteArraySource(file)
        val atom = Mp4Boxes.topLevel(source)!!.first { it.type == "moov" }
        val moov = Mp4Boxes.parse(source.read(atom.offset, atom.size.toInt())!!)!!
        val stco = Mp4Boxes.walk(moov).first { it.type == "stco" }
        val count = Mp4Boxes.be32(stco.payload, 4).toInt()
        return (0 until count).map { Mp4Boxes.be32(stco.payload, 8 + 4 * it) }
    }
}

internal class Mp4BoxesTest {

    private val items = listOf(
        Mp4Fixtures.text("©nam", "Song"),
        Mp4Fixtures.pair("trkn", 3, 12),
        Mp4Fixtures.freeform("ARTISTS", "A", "B"),
    )

    @Test
    fun topLevelTilesTheFile() {
        val file = Mp4Fixtures.file(items)
        val atoms = assertNotNull(Mp4Boxes.topLevel(ByteArraySource(file)))
        assertEquals(listOf("ftyp", "moov", "mdat"), atoms.map { it.type })
        assertEquals(file.size.toLong(), atoms.last().end)
    }

    @Test
    fun topLevelRejectsSizesPastTheEnd() {
        val file = Mp4Fixtures.file(items)
        assertNull(Mp4Boxes.topLevel(ByteArraySource(file.copyOf(file.size - 1))))
    }

    @Test
    fun moovParsesAndSerializesByteForByte() {
        for (iso in listOf(true, false)) {
            val moov = Mp4Fixtures.moov(listOf(1, 2), items, freeAfterIlst = 64, iso = iso)
            val tree = assertNotNull(Mp4Boxes.parse(moov))
            assertContentEquals(moov, tree.serialize())
            val meta = assertNotNull(tree.child("udta")?.child("meta"))
            assertEquals(if (iso) 4 else 0, meta.prefix.size)
            assertEquals(listOf("hdlr", "ilst", "free"), meta.children!!.map { it.type })
        }
    }

    @Test
    fun ilstItemsAreContainersOfDataAtoms() {
        val tree = assertNotNull(Mp4Boxes.parse(Mp4Fixtures.moov(listOf(1), items)))
        val ilst = assertNotNull(tree.child("udta")?.child("meta")?.child("ilst"))
        assertEquals(listOf("©nam", "trkn", "----"), ilst.children!!.map { it.type })
        assertEquals(listOf("mean", "name", "data", "data"), ilst.children!![2].children!!.map { it.type })
    }

    @Test
    fun typesRoundTripThroughLatin1() {
        assertContentEquals(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), Mp4Boxes.typeBytes("©nam"))
        assertEquals("©nam", Mp4Boxes.typeOf(Mp4Boxes.typeBytes("©nam"), 0))
    }

    @Test
    fun parseRejectsAChildRunningPastItsParent() {
        val bad = Mp4Fixtures.leaf("moov", Mp4Fixtures.be32(100) + Mp4Fixtures.type("trak") + ByteArray(10))
        assertNull(Mp4Boxes.parse(bad))
    }

    @Test
    fun chunkOffsetsPointAtTheSamples() {
        for (moovFirst in listOf(true, false)) {
            val file = Mp4Fixtures.file(items, moovFirst = moovFirst)
            val offsets = Mp4Fixtures.chunkOffsets(file)
            assertEquals(Mp4Fixtures.samples[0], file[offsets[0].toInt()])
            assertEquals(Mp4Fixtures.samples[1000], file[offsets[1].toInt()])
        }
    }
}
