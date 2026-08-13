/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.Genres
import io.github.nikitasud.latentjam.smart.SemanticLabel
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.TrackSemantics

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
    public val content: LibraryWorldContent = LibraryWorldContent.UNKNOWN,
    public val semanticTitle: LibraryWorldSemanticTitle? = null,
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
        LibraryWorldNameSource.SEMANTIC -> true
        LibraryWorldNameSource.GENERIC -> true
        LibraryWorldNameSource.PLAYLIST -> true
    }
}

/** Which member fact produced [LibraryWorld.name], used to keep a fresh cover truthful. */
public enum class LibraryWorldNameSource {
    GENRE,
    ARTIST,
    SEMANTIC,
    GENERIC,

    /** Named after the listener's own playlist that contains most of this world. */
    PLAYLIST,
}

/** Mutually exclusive content route used to keep non-music out of ordinary mixes. */
public enum class LibraryWorldContent {
    MUSIC,
    SPOKEN,
    SOUND_EFFECTS,
    NOVELTY,
    UNKNOWN,
}

/** Stable presentation key for semantic names; the app may localize the fallback [LibraryWorld.name]. */
public enum class LibraryWorldSemanticTitle {
    MEME_VIRAL_AUDIO,
    SOUND_EFFECTS,
    SPOKEN_AUDIO,
    INSTRUMENTAL,
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

    /** How much of a world must lie inside one named group before the group may name it. */
    public const val GROUP_NAME_CONTAINMENT: Double = 0.6

    /**
     * Renames worlds after the listener's own vocabulary: a world whose tracks lie mostly
     * inside one named group (a playlist) takes that group's name. Containment rather than
     * Jaccard — a large playlist may legitimately name a small cluster carved out of it.
     * Applied AFTER discovery, so clustering itself stays blind to curation. Each group names
     * at most its best-contained world, and each world takes at most one name: two cards with
     * the same title would be indistinguishable.
     */
    public fun namedAfterGroups(
        worlds: List<LibraryWorld>,
        groups: List<Pair<String, Set<TrackId>>>,
        minContainment: Double = GROUP_NAME_CONTAINMENT,
    ): List<LibraryWorld> {
        if (worlds.isEmpty() || groups.isEmpty()) return worlds
        data class Claim(val worldIndex: Int, val groupName: String, val containment: Double)

        val claims = mutableListOf<Claim>()
        worlds.forEachIndexed { worldIndex, world ->
            for ((groupName, members) in groups) {
                if (groupName.isBlank() || members.isEmpty()) continue
                val inside = world.tracks.count { it.id in members }
                val containment = inside.toDouble() / world.tracks.size
                if (containment >= minContainment) {
                    claims += Claim(worldIndex, groupName, containment)
                }
            }
        }
        val renamed = worlds.toMutableList()
        val takenWorlds = HashSet<Int>()
        val takenNames = HashSet<String>()
        for (claim in claims.sortedByDescending { it.containment }) {
            if (claim.worldIndex in takenWorlds || claim.groupName in takenNames) continue
            takenWorlds += claim.worldIndex
            takenNames += claim.groupName
            renamed[claim.worldIndex] = renamed[claim.worldIndex].copy(
                name = claim.groupName,
                nameSource = LibraryWorldNameSource.PLAYLIST,
                semanticTitle = null,
            )
        }
        return renamed
    }

    /**
     * How much of a cluster a genre, subtype, decade, or artist must cover before the mix may claim
     * it in the title.
     *
     * A title is a promise about the whole playlist, not a description of its largest minority.
     * Keeping this at three quarters still tolerates imperfect tags while preventing a 35% rap
     * plurality from turning a mostly unrelated cluster into a narrowly named "Phonk" mix.
     */
    public const val MIN_SHARE: Float = 0.75f

    /**
     * A member this close to a rival centroid sits on an arbitrary k-means boundary rather than
     * inside a coherent region. It is omitted from the mix instead of being used to reach a target
     * playlist length.
     */
    public const val MIN_ASSIGNMENT_MARGIN: Float = 0.04f

    /**
     * A member pointing away from its own final centroid is not representative of that region even
     * when every rival centroid is farther away.
     */
    public const val MIN_CENTROID_SIMILARITY: Float = 0f

    /** Adapts the affinity floor to compact and diffuse clusters without retaining a weak tail. */
    public const val MIN_RELATIVE_CENTROID_SIMILARITY: Float = 0.20f

    /** A boundary margin must also clear a small share of its cluster's typical core margin. */
    public const val MIN_RELATIVE_ASSIGNMENT_MARGIN: Float = 0.15f

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
        semantics: Map<TrackId, TrackSemantics> = emptyMap(),
    ): List<LibraryWorld> = discover(
        library = library,
        vectors = vectors,
        dim = dim,
        semantics = semantics,
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
        semantics: Map<TrackId, TrackSemantics> = emptyMap(),
    ): List<LibraryWorld> {
        if (library.isEmpty() || vectors.isEmpty()) return emptyList()
        return worlds(
            library = library,
            clusters = TrackClustering.cluster(
                library.map { it.id },
                vectors,
                dim,
                k = k,
                minSize = minSize,
            ),
            minSize = minSize,
            semantics = semantics,
        )
    }

    /**
     * Memory-bounded production overload for the one-shot matrix selected by
     * [LibraryVectorFusion]. The matrix is consumed and centered in place.
     */
    public fun discover(
        library: List<TrackDescriptor>,
        vectorSpace: LibraryVectorSpace,
        semantics: Map<TrackId, TrackSemantics> = emptyMap(),
    ): List<LibraryWorld> = discover(
        library = library,
        vectorSpace = vectorSpace,
        semantics = semantics,
        k = recommendedK(library.size),
        minSize = TrackClustering.MIN_CLUSTER_SIZE,
    )

    /** Explicit-k overload for fused-space fixtures and experiments. */
    public fun discover(
        library: List<TrackDescriptor>,
        vectorSpace: LibraryVectorSpace,
        k: Int,
        minSize: Int = TrackClustering.MIN_CLUSTER_SIZE,
        semantics: Map<TrackId, TrackSemantics> = emptyMap(),
    ): List<LibraryWorld> {
        if (library.isEmpty() || vectorSpace.size == 0) return emptyList()
        return worlds(
            library = library,
            clusters = TrackClustering.cluster(vectorSpace, k = k, minSize = minSize),
            minSize = minSize,
            semantics = semantics,
        )
    }

    private fun worlds(
        library: List<TrackDescriptor>,
        clusters: List<TrackCluster>,
        minSize: Int,
        semantics: Map<TrackId, TrackSemantics>,
    ): List<LibraryWorld> {
        val byId = library.associateBy { it.id }
        val musicWorlds = mutableListOf<LibraryWorld>()
        val specialGroups = SPECIAL_CONTENT_ROUTES.associateWith {
            mutableListOf<List<TrackDescriptor>>()
        }
        for (cluster in clusters) {
            val tracks = confidentMembers(cluster).mapNotNull(byId::get)
            // Confidence trimming is intentionally not followed by backfilling from the rejected
            // edge. If too little coherent evidence remains, there is no world to offer.
            if (tracks.size < minSize) continue
            val routed = tracks.groupBy { track -> contentRoute(track, semantics[track.id]) }

            val music = routed[LibraryWorldContent.MUSIC].orEmpty()
            if (music.size >= minSize) {
                musicWorlds += namedMusicWorld(music, semantics, minSize)
            }
            for (content in SPECIAL_CONTENT_ROUTES) {
                val members = routed[content].orEmpty()
                if (members.isNotEmpty()) specialGroups.getValue(content) += members
            }
        }

        // A special route is a content category, not an embedding genre. Gather its confidently
        // assigned members once across the 960-d music regions so a large library does not receive
        // several identically named "Meme & Viral Audio" cards, and so two honest two-track groups
        // can form one useful four-track category. The largest coherent subgroup leads, preserving
        // a real cluster medoid as the representative; no member is added merely to reach minSize.
        val specialWorlds = SPECIAL_CONTENT_ROUTES.mapNotNull { content ->
            val groups = specialGroups.getValue(content)
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<List<TrackDescriptor>>> { it.value.size }
                        .thenBy { it.index },
                )
                .map { it.value }
            if (groups.sumOf { it.size } < minSize) return@mapNotNull null
            semanticWorld(content, groups.flatten())
        }
        return (musicWorlds + specialWorlds).sortedByDescending { it.tracks.size }
    }

    private fun semanticWorld(
        content: LibraryWorldContent,
        tracks: List<TrackDescriptor>,
    ): LibraryWorld? {
        val (name, title) = when (content) {
            LibraryWorldContent.NOVELTY ->
                "Meme & Viral Audio" to LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO
            LibraryWorldContent.SOUND_EFFECTS ->
                "Sound Effects" to LibraryWorldSemanticTitle.SOUND_EFFECTS
            LibraryWorldContent.SPOKEN ->
                "Spoken Audio" to LibraryWorldSemanticTitle.SPOKEN_AUDIO
            LibraryWorldContent.MUSIC,
            LibraryWorldContent.UNKNOWN,
            -> return null
        }
        return LibraryWorld(
            name = name,
            tracks = tracks,
            nameSource = LibraryWorldNameSource.SEMANTIC,
            content = content,
            semanticTitle = title,
        )
    }

    private fun namedMusicWorld(
        tracks: List<TrackDescriptor>,
        semantics: Map<TrackId, TrackSemantics>,
        minSize: Int,
    ): LibraryWorld {
        val label = name(tracks, semantics)
        val admitted = tracks.filter(label.accepts)
        // A precise title with too few truthful members is less useful than an honest discovery
        // region. Keep the coherent region but drop the unsupported claim.
        return if (label.source != LibraryWorldNameSource.GENERIC && admitted.size < minSize) {
            LibraryWorld(
                name = "Discovery mix",
                tracks = tracks,
                nameSource = LibraryWorldNameSource.GENERIC,
                content = LibraryWorldContent.MUSIC,
            )
        } else {
            LibraryWorld(
                name = label.text,
                tracks = admitted,
                nameSource = label.source,
                content = LibraryWorldContent.MUSIC,
                semanticTitle = label.semanticTitle,
            )
        }
    }

    private fun confidentMembers(cluster: TrackCluster): List<TrackId> {
        val evidence = cluster.memberships
        if (evidence.isEmpty()) return cluster.members
        // A one-cluster experiment has no rival and therefore no assignment boundary to trim.
        if (evidence.all { it.assignmentMargin == 2f }) return cluster.members
        val strongestSimilarity = evidence.maxOf { it.centroidSimilarity }
        val similarityFloor = maxOf(
            MIN_CENTROID_SIMILARITY,
            strongestSimilarity * MIN_RELATIVE_CENTROID_SIMILARITY,
        )
        val sortedMargins = evidence.map { it.assignmentMargin }.sorted()
        val medianMargin = sortedMargins[sortedMargins.size / 2]
        val marginFloor = maxOf(
            MIN_ASSIGNMENT_MARGIN,
            medianMargin * MIN_RELATIVE_ASSIGNMENT_MARGIN,
        )
        return evidence
            .filter { membership ->
                membership.centroidSimilarity >= similarityFloor &&
                    membership.assignmentMargin >= marginFloor
            }
            .map { it.trackId }
    }

    /**
     * Produces one exclusive route per track. Explicit novelty metadata wins because a meme clip
     * may also contain ordinary music or speech. The head's novelty value is deliberately only an
     * acoustic proxy, so it never creates a meme claim without that text corroboration. The other
     * AudioSet aggregates are uncalibrated routing scores; they must clear both a conservative
     * floor and the music score, and speech/effects settle conflicts by acoustic dominance.
     */
    private fun contentRoute(
        track: TrackDescriptor,
        semantics: TrackSemantics?,
    ): LibraryWorldContent {
        val metadata = normalizedMetadata(track)
        if (metadata.containsAnyPhrase(NOVELTY_MARKERS)) return LibraryWorldContent.NOVELTY
        if (metadata.containsAnyPhrase(EFFECT_MARKERS)) return LibraryWorldContent.SOUND_EFFECTS
        if (semantics == null) return LibraryWorldContent.MUSIC

        val music = semantics.probability(SemanticLabel.MUSIC)
        val speech = semantics.probability(SemanticLabel.SPEECH)
        val effects = semantics.probability(SemanticLabel.SOUND_EFFECTS)
        val speechDominatesMusic =
            speech >= MIN_NON_MUSIC_ROUTING_SCORE &&
                speech > music * MIN_NON_MUSIC_TO_MUSIC_RATIO
        val effectsDominateMusic =
            effects >= MIN_NON_MUSIC_ROUTING_SCORE &&
                effects > music * MIN_NON_MUSIC_TO_MUSIC_RATIO
        return when {
            speechDominatesMusic && speech > effects -> LibraryWorldContent.SPOKEN
            effectsDominateMusic && effects > speech -> LibraryWorldContent.SOUND_EFFECTS
            else -> LibraryWorldContent.MUSIC
        }
    }

    /**
     * Lowercases metadata and turns punctuation into token boundaries. Matching raw substrings
     * would classify "Memento" as a meme because it begins with the same four letters.
     */
    private fun normalizedMetadata(track: TrackDescriptor): String {
        val raw = listOf(track.title, track.artist, track.album)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
            .lowercase()
        return buildString(raw.length) {
            var afterSeparator = true
            for (character in raw) {
                if (character.isLetterOrDigit()) {
                    append(character)
                    afterSeparator = false
                } else if (!afterSeparator) {
                    append(' ')
                    afterSeparator = true
                }
            }
        }.trim()
    }

    private fun String.containsAnyPhrase(markers: List<String>): Boolean {
        val padded = " $this "
        return markers.any { marker -> padded.contains(" $marker ") }
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
    private fun name(
        tracks: List<TrackDescriptor>,
        semantics: Map<TrackId, TrackSemantics>,
    ): WorldName {
        val medoid = tracks.first()

        val family = Genres.normalize(medoid.genre)
        if (family != null && strongest(tracks) { Genres.normalize(it.genre) } == family) {
            val medoidGenre = medoid.genre?.trim()?.takeIf(String::isNotEmpty)
            val medoidGenreKey = medoidGenre?.lowercase()
            // A broad family can be truthful without proving its narrower subtype. For example,
            // Phonk, Trap, and Hip-Hop may together make this a Rap mix, but the medoid alone must
            // not upgrade that broad claim to Phonk. Use the raw subtype only when it independently
            // clears the same whole-cluster support gate.
            val exactGenre = medoidGenreKey?.takeIf { key ->
                tracks.count { it.genre?.trim()?.lowercase() == key } >= tracks.size * MIN_SHARE
            }
            val genre = if (exactGenre != null) medoidGenre else displayFamily(family)
            val acceptedGenreTracks = tracks.filter { track ->
                if (exactGenre != null) {
                    track.genre?.trim()?.lowercase() == exactGenre
                } else {
                    Genres.normalize(track.genre) == family
                }
            }
            val medoidDecade = medoid.year?.takeIf { it in 1900..2099 }?.let(::decade)
            val sharedDecade = strongest(acceptedGenreTracks) { track ->
                track.year?.takeIf { it in 1900..2099 }?.let(::decade)
            }
            val text = if (medoidDecade != null && sharedDecade == medoidDecade) {
                "$genre • ${medoidDecade}s"
            } else {
                genre
            }
            return WorldName(
                text = text,
                source = LibraryWorldNameSource.GENRE,
                accepts = { track ->
                    val genreMatches = if (exactGenre != null) {
                        track.genre?.trim()?.lowercase() == exactGenre
                    } else {
                        Genres.normalize(track.genre) == family
                    }
                    val decadeMatches = if (medoidDecade != null && sharedDecade == medoidDecade) {
                        track.year?.takeIf { it in 1900..2099 }?.let(::decade) == medoidDecade
                    } else {
                        true
                    }
                    genreMatches && decadeMatches
                },
            )
        }

        val artist = medoid.artist?.trim()?.takeIf(String::isNotEmpty)
        if (artist != null && strongest(tracks) { it.artist?.trim()?.takeIf(String::isNotEmpty) } == artist) {
            return WorldName(
                text = "$artist • Mix",
                source = LibraryWorldNameSource.ARTIST,
                accepts = { it.artist?.trim()?.takeIf(String::isNotEmpty) == artist },
            )
        }

        val semanticGenre = dominantSemanticGenre(semantics[medoid.id])
        if (
            semanticGenre != null &&
            strongest(tracks) { track -> dominantSemanticGenre(semantics[track.id]) } == semanticGenre
        ) {
            return WorldName(
                text = semanticGenre.displayName,
                source = LibraryWorldNameSource.SEMANTIC,
                accepts = { track -> dominantSemanticGenre(semantics[track.id]) == semanticGenre },
            )
        }

        return WorldName(
            text = "Discovery mix",
            source = LibraryWorldNameSource.GENERIC,
            accepts = { true },
        )
    }

    private fun decade(year: Int): Int = year / 10 * 10

    private fun displayFamily(family: String): String =
        family.replaceFirstChar { first -> if (first.isLowerCase()) first.titlecase() else first.toString() }

    private fun dominantSemanticGenre(semantics: TrackSemantics?): SemanticGenre? {
        if (semantics == null) return null
        return SEMANTIC_GENRES
            .maxByOrNull { semantics.probability(it.label) }
            ?.takeIf { semantics.probability(it.label) >= it.threshold }
    }

    private data class WorldName(
        val text: String,
        val source: LibraryWorldNameSource,
        val accepts: (TrackDescriptor) -> Boolean,
        val semanticTitle: LibraryWorldSemanticTitle? = null,
    )

    private data class SemanticGenre(
        val label: SemanticLabel,
        val displayName: String,
        val threshold: Float,
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

    private const val MIN_NON_MUSIC_ROUTING_SCORE = 0.16f
    private const val MIN_NON_MUSIC_TO_MUSIC_RATIO = 1.05f

    private val SPECIAL_CONTENT_ROUTES = listOf(
        LibraryWorldContent.NOVELTY,
        LibraryWorldContent.SOUND_EFFECTS,
        LibraryWorldContent.SPOKEN,
    )

    private val NOVELTY_MARKERS = listOf(
        "meme",
        "tiktok",
        "tik tok",
        "viral audio",
        "green screen",
        "goofy ahh",
        "parody",
        "ai cover",
    )

    private val EFFECT_MARKERS = listOf(
        "sound effect",
        "sound effects",
        "sfx",
        "ringtone",
        "notification sound",
    )

    /*
     * These are the only exported broad-genre decisions that retained the FMA head's 0.80 target
     * precision on the frozen held-out test split. The thresholds are copied from the exporter
     * metadata. Pop abstained; International, Rock, Folk, and Experimental missed that precision
     * target; AudioSet-only genres and Instrumental expose routing scores with no calibrated
     * decision threshold. They remain available to future ranking work but cannot name a mix yet.
     */
    private val SEMANTIC_GENRES = listOf(
        SemanticGenre(
            label = SemanticLabel.GENRE_ELECTRONIC,
            displayName = "Electronic",
            threshold = 0.5924116f,
        ),
        SemanticGenre(
            label = SemanticLabel.GENRE_HIP_HOP,
            displayName = "Hip-Hop",
            threshold = 0.5500264f,
        ),
    )
}
