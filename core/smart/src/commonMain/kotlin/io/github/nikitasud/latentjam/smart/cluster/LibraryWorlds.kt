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
 *   by a shared decade), a supported two-style blend, an artist, a shared decade, or a neutral discovery label. No filename text is treated
 *   as a genre and no network model is involved.
 * @property tracks members, ordered by centrality with the nearest member supporting the name first.
 */
public data class LibraryWorld(
    public val name: String,
    public val tracks: List<TrackDescriptor>,
    public val nameSource: LibraryWorldNameSource = LibraryWorldNameSource.GENRE,
    public val content: LibraryWorldContent = LibraryWorldContent.UNKNOWN,
    public val semanticTitle: LibraryWorldSemanticTitle? = null,
    /** The two supported style families in a blend; other coherent members may still be present. */
    public val blendFamilies: Set<String> = emptySet(),
) {
    init {
        require(tracks.isNotEmpty()) { "A world with no tracks is not a world" }
    }

    /** The most central admitted track: what the card shows, and what SMART is seeded from. */
    public val representative: TrackDescriptor get() = tracks.first()

    /** Whether [track] can replace the medoid as a fresher cover without contradicting the name. */
    public fun supportsName(track: TrackDescriptor): Boolean = when (nameSource) {
        LibraryWorldNameSource.GENRE -> {
            val sameGenre = Genres.families(track.genre)
                .any { it in Genres.families(representative.genre) }
            val representativeDecade = representative.worldDecade()
            val nameClaimsDecade = representativeDecade != null && name.endsWith("${representativeDecade}s")
            sameGenre && (!nameClaimsDecade || track.worldDecade() == representativeDecade)
        }
        LibraryWorldNameSource.ARTIST ->
            track.worldArtistKey() != null && track.worldArtistKey() == representative.worldArtistKey()
        LibraryWorldNameSource.GENRE_BLEND ->
            Genres.families(track.genre).any { it in blendFamilies }
        LibraryWorldNameSource.DECADE ->
            track.worldDecade() != null && track.worldDecade() == representative.worldDecade()
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

    /** A shared, tagged first-release decade; edition year is used only when it is all we have. */
    DECADE,

    /** Two substantial styles jointly describe at least two thirds of the coherent region. */
    GENRE_BLEND,
}

private fun TrackDescriptor.worldDecade(): Int? =
    (originalYear?.takeIf { it in 1900..2099 } ?: year?.takeIf { it in 1900..2099 })
        ?.let { it / 10 * 10 }

private fun TrackDescriptor.worldArtistKey(): String? =
    artist?.trim()?.lowercase()?.takeUnless {
        it.isEmpty() || it == "unknown" || it == "<unknown>" || it == "various artists"
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

    /**
     * How much of a world must lie inside one named group before the group may name it.
     * Measured on the same real library: 0.6 named 2 of 17 worlds, 0.45 named 3, 0.35 named 5
     * with no further gain below. 0.45 keeps the name an honest near-majority claim.
     */
    public const val GROUP_NAME_CONTAINMENT: Double = 0.45

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
        data class Claim(
            val worldIndex: Int,
            val groupName: String,
            val normalizedName: String,
            val containment: Double,
            val specificity: Double,
            val inputOrder: Int,
        )

        val claims = mutableListOf<Claim>()
        var inputOrder = 0
        worlds.forEachIndexed { worldIndex, world ->
            for ((groupName, members) in groups) {
                val displayName = groupName.trim()
                val normalizedName = displayName.lowercase()
                if (displayName.isEmpty() || members.isEmpty()) continue
                val inside = world.tracks.count { it.id in members }
                val containment = inside.toDouble() / world.tracks.size
                if (containment >= minContainment) {
                    claims += Claim(
                        worldIndex = worldIndex,
                        groupName = displayName,
                        normalizedName = normalizedName,
                        containment = containment,
                        specificity = inside.toDouble() / members.size,
                        inputOrder = inputOrder,
                    )
                }
                inputOrder++
            }
        }
        val renamed = worlds.toMutableList()
        val takenWorlds = HashSet<Int>()
        val takenNames = HashSet<String>()
        // Containment first; at a tie, SPECIFICITY — the share of the group taken up by this
        // world. Nested curation makes exact ties routine (a JoJo playlist inside an Anime
        // playlist: a world of JoJo soundtracks is 100% inside both), and the tighter group is
        // strictly the more informative name. It also frees the broad name for a broader world.
        for (claim in claims.sortedWith(
            compareByDescending<Claim> { it.containment }
                .thenByDescending { it.specificity }
                .thenBy { it.inputOrder },
        )) {
            if (claim.worldIndex in takenWorlds || claim.normalizedName in takenNames) continue
            takenWorlds += claim.worldIndex
            takenNames += claim.normalizedName
            renamed[claim.worldIndex] = renamed[claim.worldIndex].copy(
                name = claim.groupName,
                nameSource = LibraryWorldNameSource.PLAYLIST,
                semanticTitle = null,
                blendFamilies = emptySet(),
            )
        }
        return renamed
    }

    /**
     * How much of a cluster a genre, subtype, decade, or artist must cover before the mix may claim
     * it in the title.
     *
     * A title is a promise about the whole playlist, not a description of its largest minority.
     * A three-fifths majority keeps that promise while naming real mixed-tag clusters: at 0.75,
     * a real 1062-track library produced 15 nameless "Discovery mix N" cards out of 17 worlds —
     * labels that tell the listener nothing — because OST-heavy libraries rarely tag three
     * quarters of an acoustic region identically. A 35% plurality still cannot name anything.
     */
    public const val MIN_SHARE: Float = 0.6f

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
     * @return named worlds, largest first, each with its nearest supported member at index 0.
     *   Tracks with no vector are absent rather than pooled. Coherent regions without enough
     *   shared metadata retain a neutral discovery name.
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
        k = recommendedK(library.asSequence().map { it.id }.distinct().count { id ->
            vectors[id]?.let { TrackClustering.isUsableVector(it, dim) } == true
        }),
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
        k = recommendedK(vectorSpace.size),
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
        val label = name(tracks, semantics, minSize)
        return LibraryWorld(
            name = label.text,
            tracks = label.tracks,
            nameSource = label.source,
            content = LibraryWorldContent.MUSIC,
            semanticTitle = label.semanticTitle,
            blendFamilies = label.blendFamilies,
        )
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
        val raw = listOf(track.title, track.artist, track.album, track.genre)
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

    /**
     * Keep the large-library tuning while giving each automatic cluster room for at least the
     * minimum useful membership. Asking sixteen tracks for eight clusters made every real island
     * split below the four-track admission floor and left a fully indexed library unnamed.
     * [trackCount] is the usable, unique vector population, not the number of library rows.
     */
    internal fun recommendedK(trackCount: Int): Int {
        val target = ((trackCount.coerceAtLeast(1) - 1) / TARGET_TRACKS_PER_MIX + 1)
            .coerceIn(TrackClustering.DEFAULT_K, MAX_MIXES)
        val viable = (trackCount / TrackClustering.MIN_CLUSTER_SIZE).coerceAtLeast(1)
        return minOf(target, viable)
    }

    /**
     * Pure genre, artist and decade names keep only members making that claim true. A blend
     * describes the two main styles of a coherent region and retains its smaller remainder.
     * All share gates use the ORIGINAL region size, including a genre/decade intersection: a
     * majority of a majority alone is not enough to name the whole region.
     *
     * Members arrive in centrality order. Filtering preserves that order, so the nearest member
     * supporting the final name becomes both cover and playback seed. One central track with an
     * absent or contradictory tag must not veto the evidence in the rest of the region.
     */
    private fun name(
        tracks: List<TrackDescriptor>,
        semantics: Map<TrackId, TrackSemantics>,
        minSize: Int,
    ): WorldName {
        // Parse joined genres and normalized metadata once per member. Previously every possible
        // subtype reparsed every track's tags and family membership was parsed again on admission.
        val facts = tracks.map { track ->
            val rawGenres = Genres.rawList(track.genre)
                .mapNotNull { raw ->
                    Genres.normalize(raw)?.let { family ->
                        raw.lowercase() to NamedGenre(display = raw, family = family)
                    }
                }
                .toMap()
            NameFacts(
                track = track,
                rawGenres = rawGenres,
                families = rawGenres.values.mapTo(LinkedHashSet()) { it.family },
                artist = track.worldArtistKey(),
                decade = track.worldDecade(),
                semanticGenre = dominantSemanticGenre(semantics[track.id]),
            )
        }
        fun supported(count: Int): Boolean = count >= minSize && count >= facts.size * MIN_SHARE
        fun <T : Any> strongest(select: (NameFacts) -> T?): T? =
            facts.mapNotNull(select).mostCommonEntry()?.takeIf { supported(it.second) }?.first
        fun named(text: String, source: LibraryWorldNameSource, members: List<NameFacts>): WorldName =
            WorldName(text, source, members.map { it.track })

        // A track votes once for every family in its tag. Linked insertion order makes ties prefer
        // the closest supporting member rather than a platform-specific HashMap iteration order.
        val familyVotes = LinkedHashMap<String, Int>()
        for (fact in facts) {
            for (family in fact.families) familyVotes[family] = (familyVotes[family] ?: 0) + 1
        }
        val family = familyVotes.entries
            .filter { supported(it.value) }
            .maxByOrNull { it.value }?.key
        if (family != null) {
            // A broad family can be supported even when none of its narrower subtypes is. A
            // subtype also has to leave a useful minimum-sized mix, otherwise retain the family.
            val rawVotes = LinkedHashMap<String, Int>()
            for (fact in facts) {
                for ((key, genre) in fact.rawGenres) {
                    if (genre.family == family) rawVotes[key] = (rawVotes[key] ?: 0) + 1
                }
            }
            val exactGenre = rawVotes.entries
                .filter { supported(it.value) }
                .maxByOrNull { it.value }?.key
            val genreFacts = facts.filter { fact ->
                if (exactGenre != null) exactGenre in fact.rawGenres else family in fact.families
            }
            val genre = exactGenre?.let { key -> genreFacts.first().rawGenres.getValue(key).display }
                ?: displayFamily(family)
            val decade = genreFacts.mapNotNull { it.decade }.mostCommonEntry()
                ?.takeIf { supported(it.second) }?.first
            return if (decade != null) {
                named(
                    "$genre • ${decade}s",
                    LibraryWorldNameSource.GENRE,
                    genreFacts.filter { it.decade == decade },
                )
            } else {
                named(genre, LibraryWorldNameSource.GENRE, genreFacts)
            }
        }

        strongest { it.artist }?.let { artist ->
            val members = facts.filter { it.artist == artist }
            return named(
                "${members.first().track.artist.orEmpty().trim()} • Mix",
                LibraryWorldNameSource.ARTIST,
                members,
            )
        }

        strongest { it.semanticGenre }?.let { genre ->
            return named(
                genre.displayName,
                LibraryWorldNameSource.SEMANTIC,
                facts.filter { it.semanticGenre == genre },
            )
        }

        strongest { it.decade }?.let { decade ->
            return named(
                "${decade}s",
                LibraryWorldNameSource.DECADE,
                facts.filter { it.decade == decade },
            )
        }

        // A coherent region can mix two well-supported styles without any one style meeting the
        // pure-genre gate. Do not throw that evidence away or remove tracks merely to get a title.
        // Each side needs a real, distinct following, and their UNION must cover two thirds of
        // the original region. Eight candidates bound pair evaluation even for long genre lists.
        val blendCandidates = familyVotes.entries
            .filter { it.value >= minSize && it.value >= facts.size * 0.15f }
            .sortedByDescending { it.value }
            .take(8)
        var bestBlend: Set<String>? = null
        var bestCoverage = 0
        var bestBalance = 0
        for (left in blendCandidates.indices) {
            for (right in left + 1 until blendCandidates.size) {
                val first = blendCandidates[left].key
                val second = blendCandidates[right].key
                var firstOnly = 0
                var secondOnly = 0
                var coverage = 0
                for (fact in facts) {
                    val inFirst = first in fact.families
                    val inSecond = second in fact.families
                    if (inFirst || inSecond) coverage++
                    if (inFirst && !inSecond) firstOnly++
                    if (inSecond && !inFirst) secondOnly++
                }
                val balance = minOf(firstOnly, secondOnly)
                if (coverage * 3 < facts.size * 2 || balance < minSize || balance * 10 < facts.size) continue
                if (coverage > bestCoverage || coverage == bestCoverage && balance > bestBalance) {
                    bestBlend = linkedSetOf(first, second)
                    bestCoverage = coverage
                    bestBalance = balance
                }
            }
        }
        bestBlend?.let { families ->
            val representative = facts.first { fact -> fact.families.any { it in families } }.track
            return WorldName(
                text = families.joinToString(" / ", transform = ::displayFamily),
                source = LibraryWorldNameSource.GENRE_BLEND,
                tracks = listOf(representative) + tracks.filter { it.id != representative.id },
                blendFamilies = families,
            )
        }

        return WorldName(
            text = "Discovery mix",
            source = LibraryWorldNameSource.GENERIC,
            tracks = tracks,
        )
    }

    private data class NameFacts(
        val track: TrackDescriptor,
        val rawGenres: Map<String, NamedGenre>,
        val families: Set<String>,
        val artist: String?,
        val decade: Int?,
        val semanticGenre: SemanticGenre?,
    )

    private data class NamedGenre(val display: String, val family: String)

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
        val tracks: List<TrackDescriptor>,
        val semanticTitle: LibraryWorldSemanticTitle? = null,
        val blendFamilies: Set<String> = emptySet(),
    )

    private data class SemanticGenre(
        val label: SemanticLabel,
        val displayName: String,
        val threshold: Float,
    )

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
