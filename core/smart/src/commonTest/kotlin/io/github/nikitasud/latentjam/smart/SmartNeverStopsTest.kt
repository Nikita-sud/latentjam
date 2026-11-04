/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Issue #1: a SMART queue must keep playing. These run the engine as the app configures it (judged scoring, artist
 * sets, queue classes and their hygiene, the queue theme, the kept hub) over libraries that 0.9.4's changes could
 * starve: mixed metadata the hygiene spreads, a pool "Don't recommend artist" shrinks, a partly analysed library, a
 * library heard almost entirely of late, and an engine rebuilt from its stores as after a kill or a reboot.
 */
internal class SmartNeverStopsTest {
    // The 3,000-track runs take seconds on the JVM but minutes in a Kotlin/Native debug build: hence the long timeouts.
    @Test
    fun `a mixed library keeps refilling through its last analysed track`() = runTest(timeout = 20.minutes) {
        val library = mixedLibrary(3_000)
        val engine = engine(library)
        val calls = playToTheEnd(engine, library, library)
        assertTrue(calls >= 200, "only $calls top-ups")
    }

    @Test
    fun `a pool shrunk by artist exclusions keeps refilling until it is spent`() = runTest(timeout = 20.minutes) {
        val library = mixedLibrary(3_000)
        val engine = engine(library)
        // Every artist but three, by their display credits: most of the library is hidden, credits with a feature too.
        val hidden = engine.artistExclusion((3 until ARTISTS).map { "Artist $it" })
        val pool = library.filterNot(hidden)
        assertTrue(pool.size in 100..1_000, "pool ${pool.size}")
        playToTheEnd(engine, library, pool)
    }

    @Test
    fun `a partly analysed library keeps refilling while analysed tracks remain`() = runTest(timeout = 20.minutes) {
        val library = mixedLibrary(3_000)
        val analysed = library.filterIndexed { i, _ -> i % 5 != 0 }
        val engine = engine(library, analysed = analysed)
        val calls = playToTheEnd(engine, library, library, analysed = analysed.mapTo(HashSet()) { it.id })
        assertTrue(calls >= 200, "only $calls top-ups")
    }

    @Test
    fun `a library heard almost entirely of late still plans the next tracks`() = runTest(timeout = 3.minutes) {
        val library = mixedLibrary(600)
        val engine = engine(library)
        val now = 1_800_000_000_000L
        val history = library.dropLast(8).mapIndexed { i, track ->
            SmartHistoryEvent(track.id, now - (library.size - i) * 200_000L, 1f, completed = true, skipped = false)
        }
        val seed = library[300]
        assertEquals(12, engine.continueSmartQueue(seed, library - seed, 12, history).size)
        // Only the unheard few are left, and some of them are of the kinds the hygiene spreads.
        val left = library.takeLast(8)
        val queue = engine.continueSmartQueue(seed, left, 12, history)
        assertEquals(left.map { it.id }.toSet(), queue.toSet())
    }

    @Test
    fun `an engine rebuilt from its stores continues the queue it left`() = runTest(timeout = 3.minutes) {
        val library = mixedLibrary(600)
        val audio = audioStore(library)
        val text = FakeIndexStore()
        val before = engine(library, audio = audio, text = text)
        val queue = before.smartQueue(library.first(), library.drop(1), 12)
        assertEquals(12, queue.size)

        // A kill or a reboot: a new engine over the same stores, asked to extend the queue it restored.
        val after = engine(library, audio = audio, text = text)
        val byId = library.associateBy { it.id }
        val played = (listOf(library.first().id) + queue).toSet()
        val next = after.continueSmartQueue(
            byId.getValue(queue.last()), library.filterNot { it.id in played }, 12,
            precedingTrackIds = (listOf(library.first().id) + queue.dropLast(1)),
        )
        assertEquals(12, next.size)
        assertTrue(next.none { it in played })
    }

    /**
     * Tops [pool] up as playback does, twelve at a time from the queue's last track, excluding every queued track, until
     * no analysed track is left; fails on any empty plan before that. Returns the number of top-ups.
     */
    private suspend fun playToTheEnd(
        engine: DefaultSimilarityEngine,
        library: List<TrackDescriptor>,
        pool: List<TrackDescriptor>,
        analysed: Set<TrackId>? = null,
    ): Int {
        val byId = library.associateBy { it.id }
        val queued = mutableListOf(pool.first { analysed == null || it.id in analysed }.id)
        val used = queued.toMutableSet()
        var calls = 0
        while (true) {
            val candidates = pool.filter { it.id !in used }
            val playable = candidates.count { analysed == null || it.id in analysed }
            if (playable == 0) break
            val next = engine.continueSmartQueue(
                byId.getValue(queued.last()), candidates, 12, precedingTrackIds = queued.takeLast(12).dropLast(1),
            )
            assertTrue(next.isNotEmpty(), "SMART stopped with $playable analysed candidates left after $calls top-ups")
            assertTrue(next.none { it in used })
            assertEquals(next.size, next.toSet().size)
            queued += next
            used += next
            calls++
        }
        return calls
    }

    private suspend fun engine(
        library: List<TrackDescriptor>,
        analysed: List<TrackDescriptor> = library,
        audio: FakeIndexStore = audioStore(analysed),
        text: FakeIndexStore = FakeIndexStore(),
    ): DefaultSimilarityEngine {
        val engine = DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(), index = InMemoryVectorIndex(960), store = audio,
            config = APP_LIKE, dispatcher = Dispatchers.Default,
            textEncoder = TestTextEncoder(), textIndex = InMemoryVectorIndex(384), textStore = text,
        )
        assertTrue(engine.initialize().isSuccess)
        engine.ensureMetadataVectors(library)
        return engine
    }

    private fun audioStore(analysed: List<TrackDescriptor>) = FakeIndexStore().apply {
        snapshots[MODEL] = analysed.associate { track -> track.id to vector(track.id.value.substringAfter('-').toInt()) }
    }

    private class TestTextEncoder : TextEncoder {
        override suspend fun load() = Result.success(Unit)
        override fun encode(metadata: String) = FloatArray(384).also {
            it[metadata.hashCode().mod(384)] = 1f
            it[0] += 0.5f
        }
        override fun close() = Unit
    }

    /**
     * Tracks whose metadata exercises 0.9.4's rules: credits with features, unknown artists, the kinds the hygiene spreads
     * (birthday songs, interviews, memes), clips under a minute and tracks over ten, several genres and languages.
     */
    private fun mixedLibrary(count: Int) = (0 until count).map { i ->
        val artist = when {
            i % 13 == 0 -> null
            i % 7 == 0 -> "Artist ${i % ARTISTS} feat. Artist ${(i + 1) % ARTISTS}"
            else -> "Artist ${i % ARTISTS}"
        }
        val title = when (i % 23) {
            0 -> "Happy Birthday $i"
            5 -> "Interview $i"
            9 -> "Meme $i"
            else -> "Track $i"
        }
        TrackDescriptor(
            TrackId("never-$i"), title = title, artist = artist, album = "Album ${i / 10}",
            genre = GENRES[i % GENRES.size], durationMs = DURATIONS[i % DURATIONS.size],
            audioUri = "test://never-$i", language = LANGUAGES[i % LANGUAGES.size],
        )
    }

    private fun vector(i: Int) = FloatArray(960).also {
        it[i % 960] = 1f
        it[(i * 31 + 17) % 960] += 0.3f
        it[(i / 40) % 960] += 0.5f
    }

    private companion object {
        const val MODEL = "never-stops-test"
        const val ARTISTS = 40
        val GENRES = listOf("Rock", "Pop; Dance", "Hip-Hop", "Jazz", "Classical", "Folk / Country", "Electronic")
        val LANGUAGES = listOf(null, "eng", "rus", "ron")
        val DURATIONS = listOf(210_000L, 45_000L, 185_000L, 900_000L, 240_000L, 8_000L, 300_000L)

        /** What AppGraph builds, less its assets: judged scoring, artist sets, queue classes, the theme, the kept hub. */
        val APP_LIKE = SmartEngineConfig(
            embeddingDim = 960,
            modelVersion = MODEL,
            continueAfterExhaustion = true,
            judgedScoring = true,
            artistRunPenalty = 0.5f,
            queueClassPenalty = 1f,
            artistSets = true,
            backgroundHub = true,
            themeWeights = listOf(0.6910f, 0.5599f, 1.6095f, 0f, 0.8016f, 0.0988f, 1.0009f, -3.2116f),
            themeSlots = 25,
            themeBonus = 1.5f,
            judgedCorrection = false,
        )
    }
}
