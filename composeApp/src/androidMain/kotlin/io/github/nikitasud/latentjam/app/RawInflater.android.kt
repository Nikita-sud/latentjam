/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal actual class RawInflater actual constructor() {
    private val inflater = Inflater(true)

    actual fun setInput(buffer: ByteArray, offset: Int, length: Int) {
        inflater.setInput(buffer, offset, length)
    }

    actual val needsInput: Boolean get() = inflater.needsInput()

    actual val finished: Boolean get() = inflater.finished()

    actual val remaining: Int get() = inflater.remaining

    actual fun inflate(output: ByteArray, offset: Int, length: Int): Int = try {
        if (inflater.needsDictionary()) throw IllegalArgumentException("A DEFLATE stream asked for a preset dictionary")
        inflater.inflate(output, offset, length)
    } catch (corrupt: DataFormatException) {
        throw IllegalArgumentException(corrupt.message ?: "Corrupt DEFLATE data", corrupt)
    }

    actual fun close() {
        inflater.end()
    }
}
