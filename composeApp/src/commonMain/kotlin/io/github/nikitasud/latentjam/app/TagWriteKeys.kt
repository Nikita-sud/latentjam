/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/**
 * A request's keys as bytes: a count, then each key's length and UTF-16 code units, all
 * big-endian — the format Android's `writeTagWriteKeys` streams — so a key survives exactly, lone
 * surrogates included.
 */
internal fun encodeTagWriteKeys(keys: List<String>): ByteArray {
    val out = ByteArray(Int.SIZE_BYTES * (1 + keys.size) + Char.SIZE_BYTES * keys.sumOf { it.length })
    var at = 0
    fun putInt(value: Int) {
        for (shift in 24 downTo 0 step 8) out[at++] = (value ushr shift).toByte()
    }
    putInt(keys.size)
    for (key in keys) {
        putInt(key.length)
        for (char in key) {
            out[at++] = (char.code ushr 8).toByte()
            out[at++] = char.code.toByte()
        }
    }
    return out
}

/** The keys [encodeTagWriteKeys] wrote; throws when the bytes are torn or damaged. */
internal fun decodeTagWriteKeys(bytes: ByteArray): List<String> {
    var at = 0
    fun int(): Int {
        check(bytes.size - at >= Int.SIZE_BYTES) { "torn at $at" }
        var value = 0
        repeat(Int.SIZE_BYTES) { value = (value shl 8) or (bytes[at++].toInt() and 0xFF) }
        return value
    }
    val count = int()
    // Bounded by the bytes, so a damaged count cannot ask for more memory than they could fill.
    check(count >= 0 && count <= bytes.size / Int.SIZE_BYTES) { "count $count" }
    val keys = List(count) {
        val length = int()
        check(length >= 0 && length <= (bytes.size - at) / Char.SIZE_BYTES) { "length $length" }
        CharArray(length) {
            val code = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
            at += Char.SIZE_BYTES
            code.toChar()
        }.concatToString()
    }
    check(at == bytes.size) { "${bytes.size - at} trailing bytes" }
    return keys
}
