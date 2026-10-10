/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import android.content.res.AssetManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [name] mapped read-only from the APK: its pages live in the page cache, which the system can drop and
 * reread, instead of on a Java heap that is 48 MB in all on the smallest devices. The asset must be
 * stored uncompressed (androidApp's `noCompress`); a compressed one is read onto the heap instead, in
 * one copy. Null when the asset is missing or unreadable, so the pack stays optional.
 */
internal fun AssetManager.assetBytes(name: String): AssetBytes? =
    runCatching { mapped(name) }
        .recoverCatching { AssetBytes.of(open(name).use { it.readExactly() }) }
        .getOrNull()

private fun AssetManager.mapped(name: String): AssetBytes =
    openFd(name).use { descriptor ->
        descriptor.createInputStream().channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                .asAssetBytes()
        }
    }

/** Reads this buffer in place by absolute index; a mapping stays valid after its channel closes. */
internal fun ByteBuffer.asAssetBytes(): AssetBytes = BufferAssetBytes(this)

private class BufferAssetBytes(private val buffer: ByteBuffer) : AssetBytes {
    override val size: Int = buffer.limit()

    override fun get(index: Int): Byte = buffer.get(index)
}

/**
 * The whole stream in one array of the length [InputStream.available] reports — an asset's full length —
 * instead of `readBytes()`'s growing buffer and final copy, which hold the asset twice at the peak. A
 * stream that turns out longer is finished the ordinary way.
 */
internal fun InputStream.readExactly(): ByteArray {
    val bytes = ByteArray(available())
    var filled = 0
    while (filled < bytes.size) {
        val count = read(bytes, filled, bytes.size - filled)
        if (count < 0) return bytes.copyOf(filled)
        filled += count
    }
    val next = read()
    return if (next < 0) bytes else bytes + next.toByte() + readBytes()
}
