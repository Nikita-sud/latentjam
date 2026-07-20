/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.Genres
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * A region of the library, with something to call it.
 *
 * @property name generated locally from evidence in the members — a genre (optionally sharpened
 *   by a shared decade), an artist mix, or a neutral discovery label. No filename text is treated
 *   as a genre and no network model is involved.
 * @property tracks members, medoid first.
 */
public data class LibraryWorld(
    public val name: String,
    public val tracks: List<TrackDescriptor>,
    public val nameSource: LibraryWorldNameSource = LibraryWorldNameSource.GENRE,
) {
    init {
        require(tracks.isNotEmpty()) { "A world with no tracks is not a world" }
    }

    /** The most central track: what the card shows, and what SMART is seeded from. */
    public val representative: TrackDescriptor get() = tracks.first()

    /** Whether [track] can replace the medoid as a fresher cover without contradicting the name. */
    public fun supportsName(track: TrackDescriptor): Boolean = when (nameSource) {
        LibraryWorldNameSource.GENRE -> {
            val sameGenre = Genres.normalize(track.genre) == Genres.normalize(representative.genre)
            val representativeDecade = representative.year?.takeIf { it in 1900..2099 }?.let { it / 10 * 10 }
            val nameClaimsDecade = representativeDecade != null && name.endsWith("${representativeDecade}s")
            sameGenre && (!nameClaimsDecade || track.year?.let { it / 10 * 10 } == representativeDecade)
        }
        LibraryWorldNameSource.ARTIST ->
            track.artist?.trim()?.takeIf(String::isNotEmpty) ==
                representative.artist?.trim()?.takeIf(String::isNotEmpty)
        LibraryWorldNameSource.GENERIC -> true
    }
}

/** Which member fact produced [LibraryWorld.name], used to keep a fresh cover truthful. */
public enum class LibraryWorldNameSource {
    GENRE,
    ARTIST,
    GENERIC,
}

/**
 * Finds the regions a library falls into, from embeddings rather than from tags.
 *
 * This is the one recommendation that needs **no listening history at all** — and the only one that
 * cannot collapse under its own feedback, because nothing here is derived from what was already
 * played. Everything else on a For You page can only reflect existing habits back.
 *
 * Run it over the metadata-text index, not the audio one. On a fresh install the audio index is
 * empty until the listener goes looking for the button that fills it, while text vectors are
 * encoded for the whole library at first launch — clustering the wrong space produces a section
 * that is missing for almost everybody.
 */
public object LibraryWorlds {

    /** Large libraries need more focused mixes; small ones keep enough cards to offer variety. */
    public const val TARGET_TRACKS_PER_MIX: Int = 60
    public const val MAX_MIXES: Int = 16

    /**
     * How much of a cluster a genre or artist must cover before the cluster is named after it.
     *
     * Held down by a second condition rather than by being strict: a claim also has to agree with
     * the medoid (see [name]). One third of a region, when it is also the largest group in it and
     * the thing on the cover, is a claim the contents support.
     */
    public const val MIN_SHARE: Float = 0.35f

    /**
     * @param library the tracks to consider; ordering is the tie-break, so keep it stable
     * @param vectors embeddings by id — the metadata-text index
     * @param dim their dimension
     * @return named worlds, largest first, each with its medoid at index 0. Tracks with no vector
     *   are absent rather than pooled, and a cluster nothing can be named after is dropped: a card
     *   with a cover and no words is not worth a slot.
     */
    public fun discover(
        library: List<TrackDescriptor>,
        vectors: Map<TrackId, FloatArray>,
        dim: Int,
    ): List<LibraryWorld> = discover(
        library = library,
        vectors = vectors,
        dim = dim,
        k = recommendedK(library.size),
        minSize = TrackClustering.MIN_CLUSTER_SIZE,
    )

    /** Explicit-k overload for experiments and deterministic fixtures. */
    public fun discover(
        library: List<TrackDescriptor>,
        vectors: Map<TrackId, FloatArray>,
        dim: Int,
        k: Int,
        minSize: Int = TrackClustering.MIN_CLUSTER_SIZE,
    ): List<LibraryWorld> {
        if (library.isEmpty() || vectors.isEmpty()) return emptyList()
        val byId = library.associateBy { it.id }
        return TrackClustering
            .cluster(library.map { it.id }, vectors, dim, k = k, minSize = minSize)
            .mapNotNull { cluster ->
                val tracks = cluster.members.mapNotNull(byId::get)
                if (tracks.isEmpty()) return@mapNotNull null
                name(tracks)?.let { label -> LibraryWorld(label.text, tracks, label.source) }
            }
    }

    internal fun recommendedK(trackCount: Int): Int =
        ((trackCount + TARGET_TRACKS_PER_MIX - 1) / TARGET_TRACKS_PER_MIX)
            .coerceIn(TrackClustering.DEFAULT_K, MAX_MIXES)

    /**
     * Names a cluster after the strongest claim its contents support, in descending order of how
     * much that claim says: a shared genre, then a shared artist, then a neutral discovery mix.
     *
     * **Every claim must also describe the medoid.** The leftmost cover of a row is looked at some
     * four times as often as its words are read, so the cover is what the row actually says — and a
     * name that disagrees with it is not a label, it is a contradiction. This surface has already
     * been burned once by a row that announced one genre and showed another; the guard is that the
     * plurality genre and the genre of the track on the cover have to be the same thing.
     *
     * Falling all the way through is not a failure. A neutral discovery label is honest about an
     * embedding region that lacks enough shared metadata. Naming it after its medoid is not: that
     * makes one ordinary song title look like the theme of the whole mix.
     */
    private fun name(tracks: List<TrackDescriptor>): WorldName? {
        val medoid = tracks.first()

        val family = Genres.normalize(medoid.genre)
        if (family != null && strongest(tracks) { Genres.normalize(it.genre) } == family) {
            // Counted by family, so that Trap, Phonk and Hip-Hop weigh as one thing rather than
            // splitting a rap region three ways and losing to nothing. Spelled by the medoid,
            // because the medoid is the cover: the words a listener reads are then literally the
            // genre of the record they are looking at, and the two cannot drift apart.
            val genre = medoid.genre?.trim()?.takeIf(String::isNotEmpty) ?: family
            val medoidDecade = medoid.year?.takeIf { it in 1900..2099 }?.let(::decade)
            val sharedDecade = strongest(tracks) { track ->
                track.year?.takeIf { it in 1900..2099 }?.let(::decade)
            }
            val text = if (medoidDecade != null && sharedDecade == medoidDecade) {
                "$genre • ${medoidDecade}s"
            } else {
                genre
            }
            return WorldName(text, LibraryWorldNameSource.GENRE)
        }

        val artist = medoid.artist?.trim()?.takeIf(String::isNotEmpty)
        if (artist != null && strongest(tracks) { it.artist?.trim()?.takeIf(String::isNotEmpty) } == artist) {
            return WorldName("$artist • Mix", LibraryWorldNameSource.ARTIST)
        }

        return WorldName("Discovery mix", LibraryWorldNameSource.GENERIC)
    }

    private fun decade(year: Int): Int = year / 10 * 10

    private data class WorldName(
        val text: String,
        val source: LibraryWorldNameSource,
    )

    /**
     * The value [select] returns for the largest share of [tracks], provided that share reaches
     * [MIN_SHARE]; null otherwise.
     *
     * The share is measured against ALL members, not only the ones that answered: three jazz tags
     * in a cluster of forty untagged tracks is not a jazz cluster, and a rule that ignored the
     * silent majority would call it one.
     */
    private fun <T : Any> strongest(tracks: List<TrackDescriptor>, select: (TrackDescriptor) -> T?): T? {
        val best = tracks.mapNotNull(select).mostCommonEntry() ?: return null
        return best.first.takeIf { best.second >= tracks.size * MIN_SHARE }
    }

    /**
     * The most frequent value and its count, ties going to whichever appeared first — which, on a
     * medoid-first list, means the one closest to the centre of the cluster.
     */
    private fun <T : Any> List<T>.mostCommonEntry(): Pair<T, Int>? {
        if (isEmpty()) return null
        val counts = LinkedHashMap<T, Int>()
        for (value in this) counts[value] = (counts[value] ?: 0) + 1
        var best: T? = null
        var bestCount = 0
        for ((value, count) in counts) {
            if (count > bestCount) {
                best = value
                bestCount = count
            }
        }
        return best?.let { it to bestCount }
    }
}
