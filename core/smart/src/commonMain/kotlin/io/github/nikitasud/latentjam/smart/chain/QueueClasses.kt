/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

/**
 * Kinds of track that jar in a queue started outside them: children's songs, memes, political songs, chanson, karaoke,
 * spoken word, and dance music after a calm seed ([ChainTuning.queueClasses]). A candidate of a kind its seed is not of
 * loses [lambda] standard deviations of the hop's candidate scores, like [ChainTuning.artistRunPenalty]: it still
 * plays when nothing else fits. The lexicons are the ranker's tag classes; memes stay welcome after sound- and
 * mood-led seeds (soundtracks, games, comedy), and chanson pays after every seed outside it.
 */
internal class QueueClasses(val lambda: Float) {
    private class Kind(
        val name: String,
        val genre: List<String>,
        val tokens: Set<String> = emptySet(),
        val text: List<String> = emptyList(),
        /** The kind pays only after a seed of one of these kinds; empty is any seed. */
        val seedIn: Set<String> = emptySet(),
        /** The kind is free after a seed of one of these kinds. */
        val seedNotIn: Set<String> = emptySet(),
        /** False for kinds that only describe the seed. */
        val pays: Boolean = true,
    ) {
        fun matches(meta: TrackMeta): Boolean {
            val g = meta.genre.orEmpty().lowercase()
            if (genre.any { it in g }) return true
            if (tokens.isNotEmpty() && g.split(';').any { it.trim() in tokens }) return true
            if (text.isEmpty()) return false
            val s = listOf(meta.title, meta.artist, meta.album).joinToString(" | ") { it.orEmpty() }.lowercase()
            return text.any { it in s }
        }
    }

    /** Whether each snapshot row pays after [seedRow]'s seed; computed once per plan. */
    fun penalised(snapshot: SmartSnapshot, seedRow: Int): BooleanArray {
        val seedKinds = KINDS.filter { it.matches(snapshot.tracks[seedRow].meta) }.mapTo(HashSet()) { it.name }
        val paying = KINDS.filter { kind ->
            kind.pays && kind.name !in seedKinds &&
                (kind.seedIn.isEmpty() || kind.seedIn.any { it in seedKinds }) &&
                kind.seedNotIn.none { it in seedKinds }
        }
        return BooleanArray(snapshot.size) { row ->
            row != seedRow && paying.any { it.matches(snapshot.tracks[row].meta) }
        }
    }

    private companion object {
        val KINDS = listOf(
            Kind(
                "kids",
                genre = listOf("children", "kids", "nursery", "lullab", "детск", "animation ost", "kid-friendly", "kid friendly"),
                text = listOf("смешарик", "smeshariki", "мультфильм", "birthday"),
            ),
            Kind(
                "meme",
                genre = listOf("meme", "sound effect", "tiktok", "voice recording", "ringtone", "game audio"),
                text = listOf("meme", "-мем", " мем", "sound effect"),
                seedNotIn = setOf("soundMood"),
            ),
            Kind(
                "political",
                genre = listOf("patriotic", "revolutionary song", "soviet song", "political", "military", "patriotic folk"),
                text = listOf("донбасс"),
            ),
            Kind("chanson", genre = listOf("chanson", "шансон")),
            Kind("karaoke", genre = listOf("karaoke"), text = listOf("karaoke", "originally performed by")),
            Kind(
                "spoken",
                genre = listOf(
                    "spoken word", "field recording", "audiobook", "podcast", "speech", "jingle", "comedy sketch", "interview",
                    "spoken weird", "sound poetry", "radio art", "audio collage", "sound collage", "musique concrete", "sound art",
                ),
                tokens = setOf("radio"),
                text = listOf("field recording", "audiobook", "interview", "christmas carol", "carol of the"),
            ),
            Kind(
                "energetic",
                genre = listOf(
                    "electro house", "big room", "dubstep", "drum and bass", "drum & bass", "dnb", "trance", "hardstyle", "techno",
                    "eurodance", "breakbeat", "edm", "future bass", "hardcore", "phonk", "dance-pop", "deep house", "tech house",
                    "progressive house", "future house", "bass house",
                ),
                tokens = setOf("electro", "house", "dance", "electronic dance music"),
                seedIn = setOf("calm"),
            ),
            Kind(
                "calm",
                genre = listOf(
                    "ambient", "downtempo", "chill", "lo-fi", "lofi", "new age", "drone", "dungeon synth", "trip hop", "trip-hop",
                    "meditation", "sleep", "minimalism",
                ),
                pays = false,
            ),
            // Sound- and mood-led seeds, the owner rounds' typing words: memes fit after them.
            Kind(
                "soundMood",
                genre = listOf(
                    "ost", "soundtrack", "score", "anime", "game", "ambient", "chill", "lo-fi", "new age", "dungeon", "downtempo",
                    "meme", "comedy", "sound effect", "tiktok", "instrumental", "classical", "orchestral", "epic",
                ),
                pays = false,
            ),
        )
    }
}
