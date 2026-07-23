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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a region of the library ends up being called.
 *
 * The rule that matters is not which name wins but that the name and the tracks beneath it come
 * from the same selection: a row that says one genre and shows another is the single failure this
 * surface has already been burned by.
 */
class LibraryWorldsTest {

    private val dim = 8
    private val random = Random(19)

    private fun vector(angle: Double, spread: Double = 0.04): FloatArray =
        FloatArray(dim) { d ->
            val base = when (d) {
                0 -> cos(angle)
                1 -> sin(angle)
                else -> 0.0
            }
            (base + if (spread > 0.0) random.nextDouble(-spread, spread) else 0.0).toFloat()
        }

    private class Corpus {
        val tracks = mutableListOf<TrackDescriptor>()
        val vectors = mutableMapOf<TrackId, FloatArray>()
    }

    private fun corpus(build: Corpus.() -> Unit): Corpus = Corpus().apply(build)

    private fun Corpus.add(
        id: String,
        angle: Double,
        title: String? = "T$id",
        artist: String? = null,
        genre: String? = null,
        spread: Double = 0.04,
    ) {
        val trackId = TrackId(id)
        tracks += TrackDescriptor(id = trackId, title = title, artist = artist, genre = genre)
        vectors[trackId] = vector(angle, spread)
    }

    private fun Corpus.discover(
        k: Int = 2,
        minSize: Int = 4,
        semantics: Map<TrackId, TrackSemantics> = emptyMap(),
    ) = LibraryWorlds.discover(
        library = tracks,
        vectors = vectors,
        dim = dim,
        k = k,
        minSize = minSize,
        semantics = semantics,
    )

    private fun semantics(vararg scores: Pair<SemanticLabel, Float>): TrackSemantics {
        val output = FloatArray(TrackSemantics.OUTPUT_SIZE)
        scores.forEach { (label, value) -> output[label.modelIndex] = value }
        return assertNotNull(TrackSemantics.fromModelOutput(output))
    }

    @Test
    fun `large libraries get more focused mixes without bloating the shelf`() {
        assertEquals(8, LibraryWorlds.recommendedK(209))
        assertEquals(15, LibraryWorlds.recommendedK(870))
        assertEquals(16, LibraryWorlds.recommendedK(5_000))
    }

    @Test
    fun `a world is named after the genre its members share`() {
        val library = corpus {
            repeat(10) { add("rap$it", angle = 0.3, genre = "Hip-Hop", artist = "Artist$it") }
            repeat(10) { add("rock$it", angle = 3.5, genre = "Hard Rock", artist = "Band$it") }
        }
        val worlds = library.discover()
        assertEquals(setOf("Hip-Hop", "Hard Rock"), worlds.map { it.name }.toSet())
    }

    @Test
    fun `spellings of one genre are counted as one without overclaiming the medoid subtype`() {
        val library = corpus {
            // Trap, Phonk and Hip-Hop are one family. Together they support Rap, but none of the
            // narrower tags is common enough to name the whole mix after the medoid alone.
            repeat(5) { add("a$it", angle = 0.3, genre = "Hip-Hop", artist = "Artist$it") }
            repeat(5) { add("b$it", angle = 0.3, genre = "Phonk", artist = "Other$it") }
            repeat(5) { add("c$it", angle = 0.3, genre = "Trap", artist = "Third$it") }
        }
        val world = library.discover(k = 1).single()
        assertEquals(15, world.tracks.size)
        assertEquals("Rap", world.name)
        assertTrue(world.tracks.all { Genres.normalize(it.genre) == "rap" })
    }

    @Test
    fun `broad rap support cannot turn a mixed cluster into a phonk mix`() {
        val library = corpus {
            // Keep a Phonk track first so it is the medoid and recreates the production failure:
            // a narrow label inherited from one central track despite a broader membership.
            add("phonk-medoid", angle = 0.3, spread = 0.0, genre = "Phonk", artist = "P0")
            repeat(4) { add("phonk$it", angle = 0.3, spread = 0.0, genre = "Phonk", artist = "P$it") }
            repeat(10) { add("trap$it", angle = 0.3, spread = 0.0, genre = "Trap", artist = "T$it") }
            repeat(10) { add("hiphop$it", angle = 0.3, spread = 0.0, genre = "Hip-Hop", artist = "H$it") }
            repeat(7) { add("other$it", angle = 0.3, spread = 0.0, genre = "Folk", artist = "F$it") }
        }

        val world = library.discover(k = 1).single()

        assertEquals("Rap", world.name)
        assertEquals(25, world.tracks.size)
        assertTrue(world.tracks.all { Genres.normalize(it.genre) == "rap" })
    }

    @Test
    fun `a supported subtype may name a mix and contradictory members are removed`() {
        val library = corpus {
            add("phonk-medoid", angle = 0.3, spread = 0.0, genre = "Phonk", artist = "P0")
            repeat(23) { add("phonk$it", angle = 0.3, spread = 0.0, genre = "Phonk", artist = "P$it") }
            repeat(6) { add("trap$it", angle = 0.3, spread = 0.0, genre = "Trap", artist = "T$it") }
            repeat(2) { add("other$it", angle = 0.3, spread = 0.0, genre = "Folk", artist = "F$it") }
        }

        val world = library.discover(k = 1).single()

        assertEquals("Phonk", world.name)
        assertEquals(24, world.tracks.size)
        assertTrue(world.tracks.all { it.genre == "Phonk" })
    }

    @Test
    fun `a genre the cover does not share is not claimed for the world`() {
        val library = corpus {
            // One track sits exactly at the centre and will be the medoid; it is tagged unlike the
            // nineteen around it.
            add("centre", angle = 0.3, spread = 0.0, genre = "Ambient", artist = "Alone", title = "Drift")
            repeat(19) { add("rock$it", angle = 0.3, genre = "Hard Rock", artist = "Band$it") }
            repeat(12) { add("other$it", angle = 3.5, genre = "Disco", artist = "Other$it") }
        }
        val world = library.discover().first { it.tracks.any { track -> track.id.value == "centre" } }
        // The fixture is only meaningful if the odd track really is the one on the cover.
        assertEquals("centre", world.representative.id.value)
        // Nineteen of twenty are Hard Rock, but the record on the cover is not, and the cover is
        // what the row actually says. Announcing a genre the art contradicts is the one failure
        // this surface has already paid for.
        assertTrue(world.name != "Hard Rock", "the label contradicted the cover")
        assertEquals("Discovery mix", world.name)
    }

    @Test
    fun `a genre only a minority shares is not claimed`() {
        val library = corpus {
            repeat(3) { add("tagged$it", angle = 0.3, genre = "Jazz", artist = "Artist$it") }
            repeat(12) { add("untagged$it", angle = 0.3, genre = null, artist = "Artist$it") }
        }
        val world = library.discover(k = 1).single()
        assertTrue(world.name != "Jazz", "three tags in fifteen tracks named the whole world")
    }

    @Test
    fun `with no shared genre a world falls back to its dominant artist`() {
        val library = corpus {
            repeat(12) { add("own$it", angle = 0.3, artist = "The Same Band", genre = null) }
            repeat(3) { add("guest$it", angle = 0.3, artist = "Guest$it", genre = null) }
        }
        val world = library.discover(k = 1).single()
        assertEquals("The Same Band • Mix", world.name)
    }

    @Test
    fun `with nothing shared a world gets a neutral discovery label`() {
        val library = corpus {
            repeat(12) { add("t$it", angle = 0.3, title = "Song $it", artist = "Artist$it", genre = null) }
        }
        val world = library.discover(k = 1).single()
        assertEquals("Discovery mix", world.name)
    }

    @Test
    fun `a supported decade sharpens a genre mix name`() {
        val tracks = (0 until 12).map { index ->
            TrackDescriptor(
                id = TrackId("disco$index"),
                title = "Song $index",
                artist = "Artist $index",
                genre = "Disco",
                year = 1970 + index % 8,
            )
        }
        val vectors = tracks.associate { it.id to vector(0.3) }

        val world = LibraryWorlds.discover(tracks, vectors, dim, k = 1).single()

        assertEquals("Disco • 1970s", world.name)
        assertTrue(world.tracks.all(world::supportsName))
    }

    @Test
    fun `the biggest world leads and every world keeps its medoid first`() {
        val library = corpus {
            repeat(20) { add("big$it", angle = 0.3, genre = "Disco", artist = "Artist$it") }
            repeat(6) { add("small$it", angle = 3.5, genre = "Techno", artist = "Other$it") }
        }
        val worlds = library.discover()
        assertEquals(listOf("Disco", "Techno"), worlds.map { it.name })
        // The representative is taken from position 0 rather than recomputed, so the cover on a
        // card and the track SMART is seeded from cannot come apart.
        assertTrue(worlds.all { it.representative == it.tracks.first() })
    }

    @Test
    fun `a metadata sparse embedding region can still be offered without a false claim`() {
        val library = corpus {
            repeat(12) { add("t$it", angle = 0.3, title = null, artist = null, genre = null) }
        }
        assertEquals("Discovery mix", library.discover(k = 1).single().name)
    }

    @Test
    fun `tracks the index has not reached yet are absent rather than pooled together`() {
        val library = corpus {
            repeat(10) { add("known$it", angle = 0.3, genre = "Disco", artist = "Artist$it") }
            repeat(10) { add("known2$it", angle = 3.4, genre = "Techno", artist = "Other$it") }
        }
        // Ten tracks the encoder has not gotten to. Pooled, they would look like a real region.
        val unindexed = (1..10).map { index ->
            TrackDescriptor(id = TrackId("cold$index"), title = "Cold $index", genre = "Ska")
        }
        val worlds = LibraryWorlds.discover(library.tracks + unindexed, library.vectors, dim, k = 2)
        val named = worlds.flatMap { it.tracks }.map { it.id }
        assertTrue(unindexed.none { it.id in named }, "an unindexed track was placed in a world")
        assertNull(worlds.firstOrNull { it.name == "Ska" })
    }

    @Test
    fun `corroborated novelty routing splits music and uses a truthful name`() {
        val noveltyTitles = listOf(
            "Prowler Goku Meme",
            "To Be Continued (Green Screen)",
            "[Unknown TikTok Audio]",
            "Old Town Road (AI Cover)",
            "Goofy Ahh Parody",
        )
        val library = corpus {
            repeat(5) { index ->
                add(
                    id = "music$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Ordinary song $index",
                    artist = "Band $index",
                    genre = "Rock",
                )
            }
            repeat(5) { index ->
                add(
                    id = "clip$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = noveltyTitles[index],
                    artist = "Creator $index",
                    genre = null,
                )
            }
        }
        val predictions = buildMap {
            library.tracks.forEach { track ->
                put(
                    track.id,
                    if (track.id.value.startsWith("clip")) {
                        semantics(
                            SemanticLabel.MUSIC to 0.70f,
                            SemanticLabel.NOVELTY_PROXY to 0.80f,
                            SemanticLabel.MOOD_FUNNY to 0.60f,
                        )
                    } else {
                        semantics(SemanticLabel.MUSIC to 0.95f)
                    },
                )
            }
        }

        val worlds = library.discover(k = 1, minSize = 4, semantics = predictions)
        val music = worlds.single { it.content == LibraryWorldContent.MUSIC }
        val novelty = worlds.single { it.content == LibraryWorldContent.NOVELTY }

        assertEquals("Rock", music.name)
        assertTrue(music.tracks.all { it.id.value.startsWith("music") })
        assertEquals("Meme & Viral Audio", novelty.name)
        assertEquals(LibraryWorldNameSource.SEMANTIC, novelty.nameSource)
        assertEquals(LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO, novelty.semanticTitle)
        assertTrue(novelty.tracks.all { it.id.value.startsWith("clip") })

        val admitted = worlds.flatMap { it.tracks }.map { it.id }
        assertEquals(admitted.size, admitted.toSet().size, "a track was routed into two worlds")
        assertEquals(library.tracks.map { it.id }.toSet(), admitted.toSet())
    }

    @Test
    fun `acoustic novelty proxy requires metadata corroboration`() {
        val library = corpus {
            repeat(8) { index ->
                // "Memento" also guards against raw substring matching on the marker "meme".
                add(
                    id = "track$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Memento theme $index",
                    artist = "Composer $index",
                    genre = "Rock",
                )
            }
        }
        val predictions = library.tracks.associate { track ->
            track.id to semantics(
                SemanticLabel.MUSIC to 0.70f,
                SemanticLabel.NOVELTY_PROXY to 0.99f,
                SemanticLabel.MOOD_FUNNY to 0.99f,
            )
        }

        val world = library.discover(k = 1, minSize = 4, semantics = predictions).single()

        assertEquals(LibraryWorldContent.MUSIC, world.content)
        assertEquals("Rock", world.name)
        assertEquals(library.tracks.map { it.id }.toSet(), world.tracks.map { it.id }.toSet())
    }

    @Test
    fun `dominant speech and effects scores route into separate special worlds`() {
        val library = corpus {
            repeat(4) { index ->
                add(
                    id = "music$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Ordinary song $index",
                    genre = "Rock",
                )
            }
            repeat(4) { index ->
                add(
                    id = "speech$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Spoken item $index",
                )
            }
            repeat(4) { index ->
                add(
                    id = "effect$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Transient item $index",
                )
            }
        }
        val predictions = library.tracks.associate { track ->
            track.id to when {
                track.id.value.startsWith("speech") -> semantics(
                    SemanticLabel.MUSIC to 0.08f,
                    SemanticLabel.SPEECH to 0.72f,
                    SemanticLabel.SOUND_EFFECTS to 0.60f,
                )
                track.id.value.startsWith("effect") -> semantics(
                    SemanticLabel.MUSIC to 0.08f,
                    SemanticLabel.SPEECH to 0.60f,
                    SemanticLabel.SOUND_EFFECTS to 0.72f,
                )
                else -> semantics(
                    SemanticLabel.MUSIC to 0.92f,
                    SemanticLabel.SPEECH to 0.12f,
                    SemanticLabel.SOUND_EFFECTS to 0.12f,
                )
            }
        }

        val worlds = library.discover(k = 1, minSize = 4, semantics = predictions)

        assertEquals(
            setOf(
                LibraryWorldContent.MUSIC,
                LibraryWorldContent.SPOKEN,
                LibraryWorldContent.SOUND_EFFECTS,
            ),
            worlds.map { it.content }.toSet(),
        )
        assertTrue(
            worlds.single { it.content == LibraryWorldContent.MUSIC }
                .tracks.all { it.id.value.startsWith("music") },
        )
        assertTrue(
            worlds.single { it.content == LibraryWorldContent.SPOKEN }
                .tracks.all { it.id.value.startsWith("speech") },
        )
        assertTrue(
            worlds.single { it.content == LibraryWorldContent.SOUND_EFFECTS }
                .tracks.all { it.id.value.startsWith("effect") },
        )
    }

    @Test
    fun `small corroborated novelty groups combine into one useful category`() {
        val library = corpus {
            repeat(4) { index ->
                add(
                    id = "rock$index",
                    angle = 0.0,
                    spread = 0.0,
                    title = "Rock song $index",
                    genre = "Rock",
                )
            }
            repeat(2) { index ->
                add(
                    id = "left-meme$index",
                    angle = 0.0,
                    spread = 0.0,
                    title = "Left Meme Clip $index",
                )
            }
            repeat(4) { index ->
                add(
                    id = "electronic$index",
                    angle = 3.14,
                    spread = 0.0,
                    title = "Electronic song $index",
                    genre = "Electronic",
                )
            }
            repeat(2) { index ->
                add(
                    id = "right-meme$index",
                    angle = 3.14,
                    spread = 0.0,
                    title = "Right Meme Clip $index",
                )
            }
        }

        val worlds = library.discover(k = 2, minSize = 4)
        val novelty = worlds.single { it.content == LibraryWorldContent.NOVELTY }

        assertEquals(4, novelty.tracks.size)
        assertTrue(novelty.tracks.all { "meme" in it.id.value })
        assertEquals(1, worlds.count { it.name == "Meme & Viral Audio" })
        assertEquals(2, worlds.count { it.content == LibraryWorldContent.MUSIC })
    }

    @Test
    fun `a special route below minimum size is omitted instead of leaking into music`() {
        val library = corpus {
            repeat(6) { index ->
                add(
                    id = "music$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Song $index",
                    genre = "Rock",
                )
            }
            repeat(2) { index ->
                add(
                    id = "meme$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Meme Clip $index",
                )
            }
        }

        val worlds = library.discover(k = 1, minSize = 4)

        assertNull(worlds.singleOrNull { it.content == LibraryWorldContent.NOVELTY })
        val music = worlds.single()
        assertEquals(LibraryWorldContent.MUSIC, music.content)
        assertTrue(music.tracks.all { it.id.value.startsWith("music") })
    }

    @Test
    fun `only held-out precise semantic genres can name metadata sparse music`() {
        val library = corpus {
            repeat(8) { index ->
                add(
                    id = "track$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Track $index",
                )
            }
        }
        val aboveThreshold = library.tracks.associate { track ->
            track.id to semantics(SemanticLabel.GENRE_HIP_HOP to 0.551f)
        }
        val belowThreshold = library.tracks.associate { track ->
            track.id to semantics(SemanticLabel.GENRE_HIP_HOP to 0.549f)
        }

        assertEquals(
            "Hip-Hop",
            library.discover(k = 1, minSize = 4, semantics = aboveThreshold).single().name,
        )
        assertEquals(
            "Discovery mix",
            library.discover(k = 1, minSize = 4, semantics = belowThreshold).single().name,
        )
    }

    @Test
    fun `uncalibrated semantic outputs cannot make a mix title claim`() {
        val library = corpus {
            repeat(8) { index ->
                add(
                    id = "track$index",
                    angle = 0.3,
                    spread = 0.0,
                    title = "Track $index",
                )
            }
        }
        val predictions = library.tracks.associate { track ->
            track.id to semantics(
                SemanticLabel.GENRE_POP to 0.99f,
                SemanticLabel.GENRE_METAL to 0.99f,
                SemanticLabel.INSTRUMENTAL to 0.99f,
            )
        }

        val world = library.discover(k = 1, minSize = 4, semantics = predictions).single()

        assertEquals("Discovery mix", world.name)
        assertEquals(LibraryWorldNameSource.GENERIC, world.nameSource)
        assertNull(world.semanticTitle)
    }

    @Test
    fun `a low confidence boundary track is trimmed rather than used to fill a mix`() {
        val tracks = mutableListOf<TrackDescriptor>()
        val vectors = mutableMapOf<TrackId, FloatArray>()
        fun add(id: String, x: Float, y: Float) {
            val trackId = TrackId(id)
            tracks += TrackDescriptor(
                id = trackId,
                title = id,
                artist = id,
                genre = "Disco",
            )
            vectors[trackId] = floatArrayOf(x, y, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        repeat(8) { add("left$it", x = 1f, y = 0f) }
        repeat(8) { add("right$it", x = -1f, y = 0f) }
        add("boundary", x = 0f, y = 1f)

        val clustered = TrackClustering.cluster(
            ids = tracks.map { it.id },
            vectors = vectors,
            dim = dim,
            k = 2,
            minSize = 4,
        )
        val boundaryEvidence = clustered
            .flatMap { it.memberships }
            .single { it.trackId.value == "boundary" }
        val coreEvidence = clustered
            .flatMap { it.memberships }
            .filterNot { it.trackId.value == "boundary" }
        assertTrue(
            boundaryEvidence.centroidSimilarity <
                coreEvidence.minOf { it.centroidSimilarity },
            "the fixture must put the extra track outside both compact cores: $boundaryEvidence",
        )

        val worlds = LibraryWorlds.discover(
            library = tracks,
            vectors = vectors,
            dim = dim,
            k = 2,
            minSize = 4,
        )
        val admittedIds = worlds.flatMap { it.tracks }.map { it.id.value }

        assertEquals(16, admittedIds.size)
        assertTrue("boundary" !in admittedIds, "the rejected edge was used to pad a mix")
    }

    @Test
    fun `an empty library has no worlds`() {
        assertTrue(LibraryWorlds.discover(emptyList(), emptyMap(), dim).isEmpty())
        val library = corpus { repeat(6) { add("t$it", angle = 0.3, genre = "Disco") } }
        assertTrue(LibraryWorlds.discover(library.tracks, emptyMap(), dim).isEmpty())
    }
}
