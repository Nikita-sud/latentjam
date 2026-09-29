/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** What a picture's header says about it. [bitsPerPixel] is FLAC's "colour depth". */
public data class ImageInfo(val mime: String, val width: Int, val height: Int, val bitsPerPixel: Int)

/** Reads just enough of a JPEG or PNG header to name and size it. */
public object ImageProbe {
    public const val JPEG: String = "image/jpeg"
    public const val PNG: String = "image/png"

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The format and dimensions of a JPEG or PNG, or null for anything else or a damaged header. */
    public fun probe(bytes: ByteArray): ImageInfo? = png(bytes) ?: jpeg(bytes)

    private fun png(b: ByteArray): ImageInfo? {
        if (b.size < 26) return null
        for (i in PNG_SIGNATURE.indices) if (b[i] != PNG_SIGNATURE[i]) return null
        if (b.decodeToString(12, 16) != "IHDR") return null
        val width = be32(b, 16)
        val height = be32(b, 20)
        val bitDepth = b[24].toInt() and 0xFF
        val channels = when (b[25].toInt() and 0xFF) {
            0 -> 1
            2 -> 3
            3 -> 1
            4 -> 2
            6 -> 4
            else -> return null
        }
        if (width <= 0 || height <= 0) return null
        return ImageInfo(PNG, width, height, bitDepth * channels)
    }

    private fun jpeg(b: ByteArray): ImageInfo? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                i += 1
                continue
            }
            if (marker == 0x01 || marker in 0xD0..0xD9) {
                i += 2
                continue
            }
            val length = be16(b, i + 2)
            if (length < 2) return null
            val isFrameHeader = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrameHeader) {
                if (i + 10 > b.size) return null
                val precision = b[i + 4].toInt() and 0xFF
                val height = be16(b, i + 5)
                val width = be16(b, i + 7)
                val components = b[i + 9].toInt() and 0xFF
                if (width <= 0 || height <= 0) return null
                return ImageInfo(JPEG, width, height, precision * components)
            }
            i += 2 + length
        }
        return null
    }

    private fun be16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}

/**
 * The FLAC `METADATA_BLOCK_PICTURE` structure — a FLAC PICTURE block's body, and (base64-encoded)
 * the Ogg `METADATA_BLOCK_PICTURE` comment. All integers big-endian.
 */
internal class FlacPicture(
    val type: Int,
    val mime: String,
    val description: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val colors: Int,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val mimeBytes = mime.encodeToByteArray()
        val descriptionBytes = description.encodeToByteArray()
        val out = ByteArray(32 + mimeBytes.size + descriptionBytes.size + data.size)
        var p = 0
        fun put32(value: Int) {
            out[p] = (value ushr 24).toByte()
            out[p + 1] = (value ushr 16).toByte()
            out[p + 2] = (value ushr 8).toByte()
            out[p + 3] = value.toByte()
            p += 4
        }
        fun put(bytes: ByteArray) {
            bytes.copyInto(out, p)
            p += bytes.size
        }
        put32(type)
        put32(mimeBytes.size)
        put(mimeBytes)
        put32(descriptionBytes.size)
        put(descriptionBytes)
        put32(width)
        put32(height)
        put32(depth)
        put32(colors)
        put32(data.size)
        put(data)
        return out
    }

    companion object {
        const val FRONT_COVER = 3
        const val OTHER = 0

        fun decode(body: ByteArray): FlacPicture? {
            var p = 0
            fun get32(): Long? {
                if (p + 4 > body.size) return null
                val value = ((body[p].toLong() and 0xFF) shl 24) or ((body[p + 1].toLong() and 0xFF) shl 16) or
                    ((body[p + 2].toLong() and 0xFF) shl 8) or (body[p + 3].toLong() and 0xFF)
                p += 4
                return value
            }
            fun take(length: Long): ByteArray? {
                if (length < 0 || length > body.size - p) return null
                val out = body.copyOfRange(p, p + length.toInt())
                p += length.toInt()
                return out
            }
            val type = get32() ?: return null
            val mime = take(get32() ?: return null)?.decodeToString() ?: return null
            val description = take(get32() ?: return null)?.decodeToString() ?: return null
            val width = get32() ?: return null
            val height = get32() ?: return null
            val depth = get32() ?: return null
            val colors = get32() ?: return null
            val data = take(get32() ?: return null) ?: return null
            if (p != body.size) return null
            return FlacPicture(type.toInt(), mime, description, width.toInt(), height.toInt(), depth.toInt(), colors.toInt(), data)
        }

        /** A front-cover picture for [bytes], or null unless they probe as a [mime] image. */
        fun frontCover(bytes: ByteArray, mime: String): FlacPicture? {
            val info = ImageProbe.probe(bytes)?.takeIf { it.mime == mime } ?: return null
            return FlacPicture(FRONT_COVER, mime, "", info.width, info.height, info.bitsPerPixel, 0, bytes)
        }
    }
}

/** Which picture is "the cover", in every format that types its pictures. */
internal object CoverTarget {
    /** Index of the first front cover, else of the first "other" picture, else null. */
    fun index(types: List<Int>): Int? =
        types.indexOf(FlacPicture.FRONT_COVER).takeIf { it >= 0 }
            ?: types.indexOf(FlacPicture.OTHER).takeIf { it >= 0 }

    /** The new cover's header facts, or null when its bytes are not the image its mime claims. */
    fun replacement(edit: CoverEdit.Replace): ImageInfo? =
        ImageProbe.probe(edit.bytes)?.takeIf { it.mime == edit.mime }
}
