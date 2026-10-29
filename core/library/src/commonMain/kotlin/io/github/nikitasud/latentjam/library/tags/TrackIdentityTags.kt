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
internal fun readTrackIdentityTags(source: RandomAccessSource): TrackIdentityTags? =
    boundedIdentityComments(source)?.let(TrackIdentityTags::from)

/**
 * What says two files hold one recording: the MusicBrainz recording id and the ISRCs. The duplicate
 * finder weighs it next to the audio, never instead of it — Picard can mis-tag a file and a
 * compilation can reuse an ISRC — so a missing, malformed or ambiguous value is simply absent.
 */
public data class TrackRecordingIds(
    public val recording: String? = null,
    /** Every valid ISRC the file carries, normalised (upper case, no hyphens); a recording may have several. */
    public val isrcs: Set<String> = emptySet(),
) {
    public fun isEmpty(): Boolean = recording == null && isrcs.isEmpty()
}

/**
 * The [TrackRecordingIds] in [source]'s tags, with the same bounded reads as relocation (at most
 * 8 MiB, never the audio); null when the metadata cannot be read completely, empty when it has none.
 */
public fun readTrackRecordingIds(source: RandomAccessSource): TrackRecordingIds? =
    boundedIdentityComments(source)?.let(::trackRecordingIdsOf)

internal fun trackRecordingIdsOf(comments: List<Pair<String, String>>): TrackRecordingIds {
    // The recording id alone: a malformed release-track id must not erase it here.
    val recording = TrackIdentityTags.from(comments.filter { identityKey(it.first) == "MUSICBRAINZTRACKID" }).recording
    val isrcs = comments.asSequence()
        .filter { identityKey(it.first) == "ISRC" }
        .flatMap { it.second.split(';', ',', '/', '\u0000').asSequence() }
        .map { value -> value.filterNot { it == '-' || it == ' ' || it == '\t' }.uppercase() }
        .filter(isrc::matches)
        .distinct()
        .take(MAX_ISRCS)
        .toSet()
    return TrackRecordingIds(recording, isrcs)
}

private fun identityKey(name: String): String = name.filterNot { it == ' ' || it == '_' }.uppercase()

private val isrc = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")

/** A file listing more ISRCs than this is not describing one recording any more. */
private const val MAX_ISRCS = 16

private fun boundedIdentityComments(source: RandomAccessSource): List<Pair<String, String>>? {
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
    return when (TagCodecs.forSource(bounded) ?: return emptyList()) {
        Mp4TagCodec -> Mp4TagCodec.identityComments(bounded)
        Id3TagCodec -> Id3TagCodec.identityComments(bounded)
        FlacTagCodec -> FlacTagCodec.identityComments(bounded)
        OggTagCodec -> OggTagCodec.identityComments(bounded)
        else -> null
    }
}
