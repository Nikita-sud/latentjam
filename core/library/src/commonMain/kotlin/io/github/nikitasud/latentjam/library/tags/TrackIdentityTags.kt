/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** Evidence for relocating a file, never a replacement for the app's own track ID. */
internal data class TrackIdentityTags(val releaseTrack: String? = null, val recording: String? = null) {
    companion object {
        private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun from(comments: List<Pair<String, String>>): TrackIdentityTags {
            // Repeated identical tags are fine; conflicting, malformed or multi-valued IDs are not.
            var malformed = false
            fun unique(key: String): String? {
                val values = comments.filter { (name, _) ->
                    name.filterNot { it == ' ' || it == '_' }.uppercase() == key
                }.map { it.second.trim().lowercase() }
                val result = values.distinct().singleOrNull()?.takeIf {
                    uuid.matches(it) && it != "00000000-0000-0000-0000-000000000000"
                }
                if (values.isNotEmpty() && result == null) malformed = true
                return result
            }
            val release = unique("MUSICBRAINZRELEASETRACKID")
            val recording = unique("MUSICBRAINZTRACKID")
            return if (malformed) TrackIdentityTags() else TrackIdentityTags(release, recording)
        }
    }
}

/** Bounded metadata reads only. A recording/ISRC/AcoustID alone cannot distinguish releases. */
internal fun readTrackIdentityTags(source: RandomAccessSource): TrackIdentityTags? {
    val bounded = object : RandomAccessSource {
        override val length = source.length
        var remaining = 8 * 1024 * 1024
        override fun read(offset: Long, count: Int): ByteArray? {
            if (count < 0 || count > remaining) return null
            remaining -= count
            return source.read(offset, count)
        }
    }
    // Identity cannot use the enrichment parser's best-effort prefix salvage: a conflicting
    // duplicate tag may sit after the truncated prefix. Incomplete/oversized metadata is unknown.
    val comments = when (TagCodecs.forSource(bounded) ?: return TrackIdentityTags()) {
        Mp4TagCodec -> Mp4TagCodec.identityComments(bounded)
        Id3TagCodec -> Id3TagCodec.identityComments(bounded)
        FlacTagCodec -> FlacTagCodec.identityComments(bounded)
        OggTagCodec -> OggTagCodec.identityComments(bounded)
        else -> null
    }
    return comments?.let(TrackIdentityTags::from)
}
