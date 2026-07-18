/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

internal class TestFrame(
    val id: String,
    val body: ByteArray,
    val flags: ByteArray = byteArrayOf(0, 0),
)

/** How the fixture writes frame size fields, so wrong encodings can be tested. */
internal enum class FrameSizes {
    /** Syncsafe on 2.4, plain big-endian on 2.3 — what the spec says. */
    PER_SPEC,

    /** Always syncsafe, which is wrong for 2.3. */
    SYNCSAFE,

    /** Always plain big-endian, which is wrong for 2.4 and very common there. */
    PLAIN,
}

/**
 * Builds ID3v2 tags byte by byte, including malformed ones.
 *
 * Deliberately shares no code with the production writer: a fixture built from
 * the code under test only ever proves that the code agrees with itself.
 */
internal object Id3TestTags {

    fun latin1Body(text: String): ByteArray =
        byteArrayOf(0) + ByteArray(text.length) { text[it].code.toByte() }

    fun utf16Body(text: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add(1)
        out.add(0xFF.toByte())
        out.add(0xFE.toByte())
        for (c in text) {
            out.add((c.code and 0xFF).toByte())
            out.add(((c.code shr 8) and 0xFF).toByte())
        }
        return out.toByteArray()
    }

    fun utf16beBody(text: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add(2)
        for (c in text) {
            out.add(((c.code shr 8) and 0xFF).toByte())
            out.add((c.code and 0xFF).toByte())
        }
        return out.toByteArray()
    }

    fun utf8Body(text: String): ByteArray = byteArrayOf(3) + text.encodeToByteArray()

    /** An APIC frame shaped like a real one, with [size] bytes of "image". */
    fun artFrame(size: Int): TestFrame {
        val body = ArrayList<Byte>()
        body.add(0) // text encoding
        body.addAll("image/jpeg".encodeToByteArray().toList())
        body.add(0) // mime terminator
        body.add(3) // picture type: front cover
        body.add(0) // empty description
        // Not random: a fixed pattern makes a corrupted round trip obvious.
        for (i in 0 until size) body.add((i % 251).toByte())
        return TestFrame("APIC", body.toByteArray())
    }

    fun commentFrame(text: String): TestFrame {
        val body = ArrayList<Byte>()
        body.add(0)
        body.addAll("eng".encodeToByteArray().toList())
        body.add(0) // empty short description
        body.addAll(text.encodeToByteArray().toList())
        return TestFrame("COMM", body.toByteArray())
    }

    /** Fake MPEG audio: an MPEG sync word plus filler. */
    fun mp3Payload(size: Int = 1024): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) +
            ByteArray(size) { (it % 256).toByte() }

    fun build(
        major: Int,
        frames: List<TestFrame>,
        padding: Int = 0,
        headerFlags: Int = 0,
        sizes: FrameSizes = FrameSizes.PER_SPEC,
        extendedHeader: ByteArray? = null,
        withFooter: Boolean = false,
        /** Overrides the header's declared size, to build truncated tags. */
        declaredSize: Int? = null,
    ): ByteArray {
        val body = ArrayList<Byte>()
        extendedHeader?.let { body.addAll(it.toList()) }
        for (frame in frames) {
            for (c in frame.id) body.add(c.code.toByte())
            val sizeBytes = when (sizes) {
                FrameSizes.PER_SPEC -> if (major >= 4) syncsafe(frame.body.size) else plain(frame.body.size)
                FrameSizes.SYNCSAFE -> syncsafe(frame.body.size)
                FrameSizes.PLAIN -> plain(frame.body.size)
            }
            body.addAll(sizeBytes.toList())
            body.add(frame.flags[0])
            body.add(frame.flags[1])
            body.addAll(frame.body.toList())
        }
        repeat(padding) { body.add(0) }

        val flags = headerFlags or if (withFooter) 0x10 else 0
        val out = ArrayList<Byte>()
        out.addAll("ID3".map { it.code.toByte() })
        out.add(major.toByte())
        out.add(0)
        out.add(flags.toByte())
        out.addAll(syncsafe(declaredSize ?: body.size).toList())
        out.addAll(body)
        if (withFooter) {
            out.addAll("3DI".map { it.code.toByte() })
            out.add(major.toByte())
            out.add(0)
            out.add(flags.toByte())
            out.addAll(syncsafe(declaredSize ?: body.size).toList())
        }
        return out.toByteArray()
    }

    fun syncsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    fun plain(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** The raw frames of the tag at the head of [data], for assertions. */
    fun framesOf(data: ByteArray): List<Id3RawFrame> =
        (Id3Codec.parse(data) as Id3Parse.Parsed).tag.frames

    fun frameBody(data: ByteArray, id: String): ByteArray? =
        framesOf(data).firstOrNull { it.id == id }?.body

    /** The four raw size bytes of the first frame with [id] in a serialized tag. */
    fun rawSizeFieldOf(data: ByteArray, id: String): ByteArray {
        var pos = Id3Codec.HEADER_SIZE
        val end = Id3Codec.HEADER_SIZE + (Id3Codec.readSyncsafe(data, 6) ?: error("bad header"))
        while (pos + Id3Codec.FRAME_HEADER_SIZE <= end) {
            val frameId = buildString { for (i in 0 until 4) append((data[pos + i].toInt() and 0xFF).toChar()) }
            if (frameId == id) return data.copyOfRange(pos + 4, pos + 8)
            // Serialized output always uses the version's correct encoding.
            val major = data[3].toInt() and 0xFF
            val size = if (major >= 4) {
                Id3Codec.readSyncsafe(data, pos + 4) ?: error("bad size")
            } else {
                Id3Codec.readUInt32BE(data, pos + 4).toInt()
            }
            pos += Id3Codec.FRAME_HEADER_SIZE + size
        }
        error("frame $id not found")
    }
}
