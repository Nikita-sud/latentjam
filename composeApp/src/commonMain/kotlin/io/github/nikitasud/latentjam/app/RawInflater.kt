/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/**
 * A raw DEFLATE decoder (RFC 1951, no zlib or gzip wrapper): what a ZIP entry with method 8 holds.
 * java.util.zip.Inflater on Android, the system zlib on iOS.
 *
 * Push-based, like java.util.zip.Inflater: [setInput] hands over a slice that the decoder reads
 * from until [remaining] reaches zero, and the caller must not change that slice before then.
 */
internal expect class RawInflater() {
    /** Hands over [length] compressed bytes of [buffer] starting at [offset]. */
    fun setInput(buffer: ByteArray, offset: Int, length: Int)

    /** True once every byte handed to [setInput] has been consumed. */
    val needsInput: Boolean

    /** True once the end of the DEFLATE stream has been decoded. */
    val finished: Boolean

    /** Input bytes handed over but not consumed yet; after [finished] they follow the stream. */
    val remaining: Int

    /**
     * Decodes into [output] and returns how many bytes it wrote: zero when it needs input or has
     * finished. Throws [IllegalArgumentException] for corrupt data.
     */
    fun inflate(output: ByteArray, offset: Int, length: Int): Int

    /** Releases the decoder's native state. */
    fun close()
}
