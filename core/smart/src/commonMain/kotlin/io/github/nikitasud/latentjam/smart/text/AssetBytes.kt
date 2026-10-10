/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * Read-only random access to a packed asset: the [MusicEntityIndex] and the [ArtistKnowledgePack].
 *
 * Both are read in place, a few bytes per lookup, so nothing obliges them to sit on a managed heap.
 * Android maps them straight from the APK — twelve megabytes that a 48 MB Java heap would otherwise
 * have to hold beside the library — while [of] wraps bytes already in memory.
 */
public interface AssetBytes {
    /** The asset's length in bytes. */
    public val size: Int

    /** The byte at [index], in `0 until size`. */
    public operator fun get(index: Int): Byte

    public companion object {
        /** Wraps [bytes] without copying them. */
        public fun of(bytes: ByteArray): AssetBytes = ByteArrayAssetBytes(bytes)
    }
}

private class ByteArrayAssetBytes(private val bytes: ByteArray) : AssetBytes {
    override val size: Int get() = bytes.size

    override fun get(index: Int): Byte = bytes[index]
}
