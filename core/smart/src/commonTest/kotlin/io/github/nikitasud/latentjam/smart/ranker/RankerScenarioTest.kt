/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PLAN_FINAL §7.1's scenario matrix on the ranker, with §7.3's invariants checked on every plan: no crash; never an
 * empty plan while an eligible track exists; distinct picks; no track that is a version of the one before it unless
 * every eligible track is; non-music never picked; deterministic for a fixed press; library order changes nothing.
 * Models: the shipped CORE+LFM shape (no assets here, so knowledge-based features read "unknown") and RULE_SET.
 */
class RankerScenarioTest {
    private val coreModel = RankerModel.parse(
        """{"name": "test-core", "features": ["cos", "same_family", "same_lang", "same_artist", "colisten", "lfm_cos",
           "dur_short", "desc_known"], "mean": [0,0,0,0,0,0,0,0], "sd": [1,1,1,1,1,1,1,1],
           "weights": [-1.7, 0.3, 0.4, 2.7, 2.0, 1.5, 0, 0.5], "runPenalty": 2.129, "anchor": "mix", "epsilon": 0.01}""",
    )!!
    private val fullModel = RankerModel.parse(
        """{"name": "test-full", "features": ["same_family", "same_artist", "heard", "log_freq", "actr", "trans_track"],
           "mean": [0,0,0,0,0,0], "sd": [1,1,1,1,1,1], "weights": [0.3, 2.0, 0.5, 0.4, 0.2, 1.0],
           "runPenalty": 1.9, "anchor": "mix", "epsilon": 0.01}""",
    )!!

    private fun ranker(audio: AudioSource = AudioSource.NONE) = SmartRanker(coreModel, null, null, audio = audio, fullModel = fullModel)

    private fun track(
        id: String,
        artist: String? = "Artist",
        title: String? = id,
        genre: String? = "Rock",
        album: String? = null,
        durationMs: Long? = 200_000,
        folder: String? = "Music",
    ) = TrackDescriptor(TrackId(id), title = title, artist = artist, album = album, genre = genre, durationMs = durationMs, folderPath = folder, fileName = "$id.mp3")

    private fun library(n: Int, artists: Int = 7, genres: List<String?> = listOf("Rock", "Pop", "Hip Hop", "Classical")) =
        (0 until n).map { track("t$it", artist = "Artist ${it % artists}", genre = genres[it % genres.size]) }

    /** Plans and checks every invariant; returns the plan. */
    private suspend fun check(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int = 20,
        candidates: List<TrackDescriptor> = library,
        request: (PlanRequest) -> PlanRequest = { it },
        ranker: SmartRanker = ranker(),
        expectFull: Boolean = true,
    ): List<TrackId> {
        val base = PlanRequest(seed, library, candidates, length, press = 3)
        val plan = ranker.plan(request(base)).ids
        val eligible = candidates.filter { it.id != seed.id && !SmartRanker.isNonMusic(it) }.distinctBy { it.id }
        if (eligible.isNotEmpty() && length > 0) assertTrue(plan.isNotEmpty(), "empty plan with ${eligible.size} eligible")
        if (expectFull) assertEquals(minOf(length, eligible.size), plan.size, "plan size")
        assertEquals(plan.size, plan.toSet().size, "distinct picks")
        val byId = (library + candidates + seed).associateBy { it.id }
        assertTrue(plan.all { it in eligible.map(TrackDescriptor::id) }, "only eligible tracks")
        assertFalse(plan.any { SmartRanker.isNonMusic(byId.getValue(it)) }, "non-music picked")
        val sequence = listOf(seed.id) + plan
        for (i in 1 until sequence.size) {
            val a = SmartRanker.versionKey(byId.getValue(sequence[i - 1]))
            val b = SmartRanker.versionKey(byId.getValue(sequence[i]))
            if (a == b) {
                val othersExist = eligible.any { it.id !in sequence.take(i + 1) && SmartRanker.versionKey(it) != a }
                assertFalse(othersExist, "same-song adjacency at $i while other songs remain")
            }
        }
        assertEquals(plan, ranker.plan(request(base)).ids, "deterministic")
        val shuffled = library.shuffled(Random(7))
        val shuffledCandidates = candidates.shuffled(Random(11))
        assertEquals(plan, ranker().plan(request(PlanRequest(seed, shuffled, shuffledCandidates, length, press = 3))).ids.takeIf {
            request(base).history === PersonalHistory.EMPTY && !request(base).ruleOnly && !request(base).personal
        } ?: plan, "library order")
        return plan
    }

    @Test fun emptyLibrary() = runTest {
        assertTrue(ranker().plan(PlanRequest(track("seed"), emptyList(), emptyList(), 20)).ids.isEmpty())
    }

    @Test fun oneTrackLibraryHasNothingElseToPlay() = runTest {
        val seed = track("seed")
        assertTrue(ranker().plan(PlanRequest(seed, listOf(seed), listOf(seed), 20)).ids.isEmpty())
    }

    @Test fun fiveAndTwentyTrackLibraries() = runTest {
        for (n in listOf(2, 3, 5, 20)) {
            val lib = library(n)
            check(lib[0], lib)
        }
    }

    @Test fun largeLibraryFillsWithinItsBudget() = runTest {
        val lib = library(10_000, artists = 900)
        val plan = check(lib[0], lib)
        assertEquals(20, plan.size)
    }

    @Test fun oneArtistOwnsMostOfTheLibrary() = runTest {
        val lib = (0 until 60).map { track("t$it", artist = if (it < 45) "Dominant" else "Other $it") }
        val plan = check(lib[0], lib)
        var run = 1
        var longest = 1
        val artist = plan.map { id -> lib.first { it.id == id }.artist }
        for (i in 1 until artist.size) { run = if (artist[i] == artist[i - 1]) run + 1 else 1; longest = maxOf(longest, run) }
        assertTrue(longest < 20, "the run penalty breaks the dominant artist's run")
    }

    @Test fun singleArtistAndSingleGenreLibraries() = runTest {
        check(library(30, artists = 1)[0], library(30, artists = 1))
        val oneGenre = library(30, genres = listOf("Pop"))
        check(oneGenre[0], oneGenre)
    }

    @Test fun noTagsAtAll() = runTest {
        val lib = (0 until 25).map { TrackDescriptor(TrackId("u$it")) }
        check(lib[0], lib)
    }

    @Test fun mojibakeMixedScriptsAndRtl() = runTest {
        val names = listOf("Ã©dith PiÃ¡f", "米津玄師", "فيروز", "Кино", "İzel", "ΣΟΦΟΣ", "Beyoncé feat. JAY-Z", "\u0000�", "", "   ")
        val lib = names.mapIndexed { i, a -> track("m$i", artist = a, title = a.reversed(), genre = listOf("J-Pop", "Arabic", null)[i % 3]) } +
            (0 until 10).map { track("x$it", artist = "X $it") }
        check(lib[0], lib)
        check(lib[2], lib)
    }

    @Test fun classicalLongAndShortTracksAndAbsurdDurations() = runTest {
        val lib = listOf(
            track("bach1", artist = "Glenn Gould", genre = "Classical", durationMs = 3_600_000),
            track("book", artist = "Narrator", genre = "Audiobook", durationMs = 40_000_000),
            track("skit", artist = "Rapper", genre = "Hip Hop", durationMs = 12_000),
            track("zero", durationMs = 0), track("negative", durationMs = -5), track("huge", durationMs = Long.MAX_VALUE),
            track("null", durationMs = null),
        ) + library(20)
        check(lib[0], lib)
        check(lib[1], lib)
    }

    @Test fun versionsAndDuplicatesNeverFollowEachOther() = runTest {
        val versions = listOf("Song", "Song (Live)", "Song - Remastered 2011", "Song (Radio Edit)", "Song [Acoustic]")
            .mapIndexed { i, t -> track("v$i", artist = "Band", title = t) }
        val duplicates = listOf(track("d1", artist = "Band", title = "Song", folder = "Music/A"), track("d2", artist = "Band", title = "Song", folder = "Download"))
        val lib = versions + duplicates + library(10)
        val plan = check(lib[0], lib)
        assertTrue(plan.take(1).none { it.value.startsWith("v") || it.value.startsWith("d") })
    }

    @Test fun onlyVersionsOfTheSeedStillPlay() = runTest {
        val lib = listOf("Song", "Song (Live)", "Song - Remix", "Song [Demo]").mapIndexed { i, t -> track("v$i", artist = "Band", title = t) }
        val plan = check(lib[0], lib)
        assertEquals(3, plan.size)
    }

    @Test fun nonMusicFoldersAreNeverPicked() = runTest {
        val lib = library(10) + listOf(
            track("ring", folder = "Ringtones"), track("note", folder = "Notifications/Sounds"),
            track("rec", folder = "Recordings"), track("alarm", folder = "Media/Alarms"),
        )
        val plan = check(lib[0], lib)
        assertTrue(plan.none { it.value in setOf("ring", "note", "rec", "alarm") })
    }

    @Test fun seedOutsideTheLibraryAndUnresolvedSeeds() = runTest {
        val lib = library(15)
        check(track("external", artist = null, title = null, genre = null, folder = null), lib)
        check(TrackDescriptor(TrackId("bare")), lib)
    }

    @Test fun aTrackDeletedOrAVolumeRemovedMidQueueIsNeverPlanned() = runTest {
        val lib = library(30)
        val remaining = lib.filterNot { it.id.value in setOf("t3", "t4", "t5") }
        val plan = check(lib[0], lib, candidates = remaining)
        assertTrue(plan.none { it.value in setOf("t3", "t4", "t5") })
    }

    @Test fun recentlyHeardTracksAreLeftOutUntilNothingElseRemains() = runTest {
        val lib = library(12)
        val plan = check(lib[0], lib, request = { r -> PlanRequest(r.seed, r.library, r.candidates, r.length, exclude = lib.drop(1).take(5).map { it.id }.toSet(), press = r.press) })
        assertTrue(plan.take(6).none { it.value in setOf("t1", "t2", "t3", "t4", "t5") }, "excluded tracks only after the rest")
        // Everything heard recently: the queue still plays.
        check(lib[0], lib, request = { r -> PlanRequest(r.seed, r.library, r.candidates, r.length, exclude = lib.map { it.id }.toSet(), press = r.press) })
    }

    @Test fun freshInstallHeavyHistoryHabitsOnAndOffAndAClockChange() = runTest {
        val lib = library(80)
        val ids = lib.map { it.id }
        val random = Random(3)
        val heavy = (0 until 10_000).map { i -> HabitListen(ids[random.nextInt(ids.size)], 1_000_000L + i * 200_000L, countsAsNext = random.nextBoolean()) }
        val future = heavy.takeLast(50).map { HabitListen(it.trackId, it.startedAtMs + 10_000_000_000L, true) }
        for (listens in listOf(emptyList(), heavy, heavy + future)) {
            val history = PersonalHistory(listens, 1_000_000L + 10_000 * 200_000L) { id -> lib.firstOrNull { it.id == id }?.artist.orEmpty() }
            for (personal in listOf(false, true)) {
                check(lib[0], lib, request = { r -> PlanRequest(r.seed, r.library, r.candidates, r.length, press = r.press, history = history, personal = personal) })
            }
        }
    }

    @Test fun pressesVaryTheQueueAndRepeatItExactly() = runTest {
        val lib = library(200, artists = 50)
        val r = ranker()
        val plans = (0L until 6L).map { p -> r.plan(PlanRequest(lib[0], lib, lib, 20, press = p)).ids }
        assertEquals(plans, (0L until 6L).map { p -> r.plan(PlanRequest(lib[0], lib, lib, 20, press = p)).ids })
        assertTrue(plans.distinct().size > 1, "a second press varies the queue")
    }

    @Test fun audioPartlyIndexedAndAudioFailures() = runTest {
        val lib = library(40)
        val half = lib.take(20).associate { it.id to FloatArray(8) { i -> ((it.id.value.hashCode() + i) % 7).toFloat() } }
        val partial = object : AudioSource {
            override suspend fun vector(id: TrackId) = half[id]
            override suspend fun neighbours(id: TrackId, k: Int) = if (id in half) half.keys.filter { it != id }.take(k) else emptyList()
        }
        val failing = object : AudioSource {
            override suspend fun vector(id: TrackId): FloatArray? = error("index closed")
            override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> = error("index closed")
        }
        check(lib[0], lib, ranker = ranker(partial))
        check(lib[25], lib, ranker = ranker(failing))
    }

    @Test fun theRuleOnlyPathAndALargeLibraryOverTheRetrievalThreshold() = runTest {
        val lib = library(3_000, artists = 300)
        check(lib[0], lib, request = { r -> PlanRequest(r.seed, r.library, r.candidates, r.length, press = r.press, ruleOnly = true) })
        val report = ranker().plan(PlanRequest(lib[0], lib, lib, 20, press = 1))
        assertTrue(report.poolSize < lib.size, "retrieval bounds the pool on a large library")
    }

    @Test fun heapRuleRefusesThePressBelowItsThreshold() {
        assertTrue(rankerFitsInHeap(600, headroom = 64L * 1024 * 1024))
        assertFalse(rankerFitsInHeap(600, headroom = 8L * 1024 * 1024))
    }
}
