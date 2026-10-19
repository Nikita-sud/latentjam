/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.text.UnicodeForm
import io.github.nikitasud.latentjam.smart.text.normalizeUnicode

/**
 * The ranker's reproducible randomness (PLAN_FINAL §1.6 step 5, kept by §8 for the salted pick among near-best
 * candidates): seed = the first 8 bytes, big-endian, of SHA-256 over the UTF-8 of "salt|query|press|key_id"; a
 * SplitMix64 generator starts from state = seed and hop h (1 for the first pick) uses its h-th output.
 */
public object SaltedKey {
    /** Fixed across builds, so an upgrade keeps the press counters meaningful (§7.2b). */
    public const val SALT: String = "latentjam-smart-ranker-1"

    public fun seed(salt: String, query: String, press: Long, keyId: Int = 0): Long {
        val digest = Sha256.digest("$salt|$query|$press|$keyId".encodeToByteArray())
        var out = 0L
        for (i in 0 until 8) out = (out shl 8) or (digest[i].toLong() and 0xff)
        return out
    }

    /**
     * The §7.2b content key: "v1|" + volume + "|" + the path relative to that volume's root (NFC, "/" separators) +
     * "|" + size in bytes + "|" + duration in ms (0 if unknown). Without a file path (iOS media items) the persistent
     * media id takes the path's place. Never the audio-index key, so it survives indexing; a tag edit changes the size.
     */
    public fun contentKey(track: TrackDescriptor): String {
        val name = track.fileName?.takeIf(String::isNotBlank)
        val path = if (name != null) {
            listOfNotNull(track.folderPath?.trim('/')?.takeIf(String::isNotEmpty), name).joinToString("/")
        } else {
            track.id.value
        }
        val relative = normalizeUnicode(path.replace('\\', '/'), UnicodeForm.NFC)
        val size = track.sizeBytes?.takeIf { it >= 0 } ?: 0L
        val duration = track.durationMs?.takeIf { it > 0 } ?: 0L
        return "v1|${track.storageVolume.orEmpty()}|$relative|$size|$duration"
    }

    /** Unsigned order of the UTF-8 bytes of a track id: the tie-break order of §1.6. */
    public val idOrder: Comparator<String> = Comparator { a, b ->
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }
}

/** SplitMix64 (Steele, Lea & Flood 2014), the generator §1.6 names. */
public class SplitMix64(private var state: Long) {
    public fun next(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}

/** SHA-256 (FIPS 180-4) in common code: Kotlin/Native has no platform digest here. */
public object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    public fun digest(message: ByteArray): ByteArray = digest(message.size.toLong()) { message[it.toInt()] }

    /** The digest of [size] bytes read through [byteAt], 64 at a time: a mapped asset is hashed without a copy. */
    public fun digest(size: Long, byteAt: (Long) -> Byte): ByteArray {
        val h = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )
        val bitLength = size * 8
        val total = ((size + 9 + 63) / 64) * 64
        val block = ByteArray(64)
        val w = IntArray(64)
        var at = 0L
        while (at < total) {
            for (i in 0 until 64) {
                val p = at + i
                block[i] = when {
                    p < size -> byteAt(p)
                    p == size -> 0x80.toByte()
                    p >= total - 8 -> (bitLength ushr (8 * (total - 1 - p).toInt())).toByte()
                    else -> 0
                }
            }
            for (t in 0 until 16) {
                w[t] = ((block[t * 4].toInt() and 0xff) shl 24) or ((block[t * 4 + 1].toInt() and 0xff) shl 16) or
                    ((block[t * 4 + 2].toInt() and 0xff) shl 8) or (block[t * 4 + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
                val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            at += 64
        }
        return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }
}
