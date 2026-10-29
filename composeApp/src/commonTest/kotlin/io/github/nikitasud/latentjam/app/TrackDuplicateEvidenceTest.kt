/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TrackRecordingIds
import io.github.nikitasud.latentjam.playback.SoundSpan
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrackDuplicateEvidenceTest {

    /** A unit vector whose cosine with (1, 0) is [similarity]. */
    private fun at(similarity: Double): FloatArray {
        val angle = acos(similarity)
        return floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())
    }

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")
    private val recording = "f1de9675-0170-46e5-a05c-d4e27640aec7"
    private val otherRecording = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
    private val sameIds = TrackRecordingIds(recording, setOf("QMTH31100021"))

    private fun groups(
        vectors: Map<TrackId, FloatArray>,
        durations: Map<TrackId, Long?>,
        evidence: Map<TrackId, DuplicateEvidence> = emptyMap(),
        dismissed: DuplicateExclusions = DuplicateExclusions(emptyList()),
    ) = audioDuplicateGroups(vectors = vectors, durationsMs = durations, evidence = evidence, dismissed = dismissed)

    @Test
    fun aCopyPaddedWithSilenceMatchesOnceItsSoundLengthIsKnown() {
        val vectors = mapOf(a to at(1.0), b to at(0.999))
        val durations = mapOf(a to 100_000L, b to 104_000L)
        assertTrue(groups(vectors, durations).isEmpty())
        val sound = mapOf(a to DuplicateEvidence(soundMs = 99_400L), b to DuplicateEvidence(soundMs = 99_450L))
        assertEquals(listOf(listOf(a, b)), groups(vectors, durations, sound))
    }

    @Test
    fun aDifferentSongOfTheSameLengthNeverMatches() {
        val vectors = mapOf(a to at(1.0), b to at(0.9))
        val durations = mapOf(a to 200_000L, b to 200_000L)
        val evidence = mapOf(
            a to DuplicateEvidence(soundMs = 199_000L, ids = sameIds),
            b to DuplicateEvidence(soundMs = 199_000L, ids = sameIds),
        )
        assertTrue(groups(vectors, durations, evidence).isEmpty())
    }

    @Test
    fun silenceTrimmingDoesNotJoinADifferentEdit() {
        // An edit that is six seconds of music shorter stays apart, whatever its silence.
        val vectors = mapOf(a to at(1.0), b to at(0.999))
        val durations = mapOf(a to 200_000L, b to 207_000L)
        val evidence = mapOf(a to DuplicateEvidence(soundMs = 199_000L), b to DuplicateEvidence(soundMs = 205_000L))
        assertTrue(groups(vectors, durations, evidence).isEmpty())
    }

    @Test
    fun theSameRecordingIdAllowsAFiveSecondGapAndASlightlyLowerSimilarity() {
        val durations = mapOf(a to 200_000L, b to 205_000L)
        val ids = mapOf(a to DuplicateEvidence(ids = sameIds), b to DuplicateEvidence(ids = sameIds))
        val close = mapOf(a to at(1.0), b to at(0.999))
        assertTrue(groups(close, durations).isEmpty())
        assertEquals(listOf(listOf(a, b)), groups(close, durations, ids))
        val lowerBitrate = mapOf(a to at(1.0), b to at(0.985))
        assertEquals(listOf(listOf(a, b)), groups(lowerBitrate, durations, ids))
        assertEquals(listOf(listOf(a, b)), groups(lowerBitrate, mapOf(a to 200_000L, b to 200_500L), ids))
    }

    @Test
    fun theSameRecordingIdWithALowSimilarityNeverMatches() {
        val durations = mapOf(a to 200_000L, b to 200_000L)
        val ids = mapOf(a to DuplicateEvidence(ids = sameIds), b to DuplicateEvidence(ids = sameIds))
        assertTrue(groups(mapOf(a to at(1.0), b to at(0.95)), durations, ids).isEmpty())
        assertTrue(groups(mapOf(a to at(1.0), b to at(0.975)), durations, ids).isEmpty())
    }

    @Test
    fun theSameRecordingIdNeverBridgesMoreThanItsLengthTolerance() {
        val close = mapOf(a to at(1.0), b to at(0.999))
        val ids = mapOf(a to DuplicateEvidence(ids = sameIds), b to DuplicateEvidence(ids = sameIds))
        assertTrue(groups(close, mapOf(a to 200_000L, b to 211_000L), ids).isEmpty())
        // Past the widest gap nothing joins two files, not even equal sound lengths and ids.
        val everything = mapOf(
            a to DuplicateEvidence(soundMs = 190_000L, ids = sameIds),
            b to DuplicateEvidence(soundMs = 190_000L, ids = sameIds),
        )
        assertTrue(groups(close, mapOf(a to 200_000L, b to 216_000L), everything).isEmpty())
        // Within it, equal sound lengths join a pair the plain similarity accepts.
        assertEquals(listOf(listOf(a, b)), groups(close, mapOf(a to 200_000L, b to 214_000L), everything))
        // But the id rule's lower similarity never reaches past its own declared tolerance.
        val lower = mapOf(a to at(1.0), b to at(0.985))
        val nearSound = mapOf(
            a to DuplicateEvidence(soundMs = 190_000L, ids = sameIds),
            b to DuplicateEvidence(soundMs = 191_000L, ids = sameIds),
        )
        assertTrue(groups(lower, mapOf(a to 200_000L, b to 212_000L), nearSound).isEmpty())
        assertTrue(!isDuplicatePair(0.985f, DUPLICATE_SIMILARITY, 200_000L, 212_000L, nearSound[a], nearSound[b]))
    }

    @Test
    fun conflictingRecordingIdsWithholdTheTrustButNeverVetoTheAudio() {
        val conflicting = mapOf(
            a to DuplicateEvidence(ids = TrackRecordingIds(recording, setOf("QMTH31100021"))),
            b to DuplicateEvidence(ids = TrackRecordingIds(otherRecording, setOf("QMTH31100021"))),
        )
        val close = mapOf(a to at(1.0), b to at(0.999))
        // The plain rule still holds: MusicBrainz keeps unmerged duplicate recordings.
        assertEquals(listOf(listOf(a, b)), groups(close, mapOf(a to 200_000L, b to 201_000L), conflicting))
        // A shared ISRC does not relax the lengths when the recording ids disagree.
        assertTrue(groups(close, mapOf(a to 200_000L, b to 205_000L), conflicting).isEmpty())
        // An equal recording id does, whatever the ISRCs (a reissue gets a new one).
        val reissue = mapOf(
            a to DuplicateEvidence(ids = TrackRecordingIds(recording, setOf("QMTH31100021"))),
            b to DuplicateEvidence(ids = TrackRecordingIds(recording, setOf("USRC17607839"))),
        )
        assertEquals(listOf(listOf(a, b)), groups(close, mapOf(a to 200_000L, b to 205_000L), reissue))
        // A shared ISRC alone does, when no recording id contradicts it.
        val isrcOnly = mapOf(
            a to DuplicateEvidence(ids = TrackRecordingIds(null, setOf("QMTH31100021", "GBAYE0601498"))),
            b to DuplicateEvidence(ids = TrackRecordingIds(recording, setOf("QMTH31100021"))),
        )
        assertEquals(listOf(listOf(a, b)), groups(close, mapOf(a to 200_000L, b to 205_000L), isrcOnly))
    }

    @Test
    fun unknownDurationsKeepThePlainRule() {
        val unknown = mapOf<TrackId, Long?>(a to null, b to null)
        assertEquals(listOf(listOf(a, b)), groups(mapOf(a to at(1.0), b to at(0.995)), unknown))
        val slightlyLower = mapOf(a to at(1.0), b to at(0.985))
        assertTrue(groups(slightlyLower, unknown).isEmpty())
        val ids = mapOf(a to DuplicateEvidence(ids = sameIds), b to DuplicateEvidence(ids = sameIds))
        assertEquals(listOf(listOf(a, b)), groups(slightlyLower, unknown, ids))
        // One duration known: still no length to compare.
        assertEquals(listOf(listOf(a, b)), groups(mapOf(a to at(1.0), b to at(0.995)), mapOf(a to 1_000L, b to null)))
    }

    @Test
    fun aDismissedPairStaysApartWhateverTheEvidence() {
        val vectors = mapOf(a to at(1.0), b to at(1.0))
        val evidence = mapOf(
            a to DuplicateEvidence(soundMs = 100_000L, ids = sameIds),
            b to DuplicateEvidence(soundMs = 100_000L, ids = sameIds),
        )
        val dismissed = DuplicateExclusions(listOf(listOf(a, b)))
        assertTrue(groups(vectors, mapOf(a to 100_000L, b to 104_000L), evidence, dismissed).isEmpty())
    }

    @Test
    fun groupsStayCompleteLinkWhenSilenceJoinsNeighbours() {
        // a≈b and b≈c by sound length, but a and c are three seconds of sound apart.
        val vectors = mapOf(a to at(1.0), b to at(1.0), c to at(1.0))
        val durations = mapOf(a to 100_000L, b to 105_000L, c to 110_000L)
        val evidence = mapOf(
            a to DuplicateEvidence(soundMs = 100_000L),
            b to DuplicateEvidence(soundMs = 101_500L),
            c to DuplicateEvidence(soundMs = 103_000L),
        )
        val found = groups(vectors, durations, evidence)
        assertEquals(listOf(listOf(a, b)), found)
        found.forEach { group -> assertTrue(!(a in group && c in group)) }
    }

    @Test
    fun soundLengthsComeOnlyFromSpansThatFitTheFile() {
        assertEquals(234_900L, soundLengthMs(SoundSpan(50L, 234_950L), 235_636L))
        assertEquals(234_900L, soundLengthMs(SoundSpan(50L, 234_950L), null))
        assertNull(soundLengthMs(null, 235_636L))
        assertNull(soundLengthMs(SoundSpan(5_000L, 5_000L), 235_636L))
        assertNull(soundLengthMs(SoundSpan(0L, 240_000L), 235_636L))
    }

    // --- The Dragonborn report, with synthetic numbers taken from the two files. ---

    private val cd = TrackId("/sdcard/Music/Skyrim/1-01 - Dragonborn.flac")
    private val digital = TrackId("/sdcard/Music/01 - Dragonborn.flac")
    private val other = TrackId("/sdcard/Music/Other.flac")

    /** The same audio until 234.97 s; the digital release holds 2.66 s of silence after it, the CD 0.66 s. */
    private val dragonVectors = mapOf(cd to at(1.0), digital to at(0.9995), other to at(0.5))
    private val dragonDurations = mapOf(cd to 235_636L, digital to 237_640L, other to 237_000L)
    private val dragonSpans = mapOf(cd to SoundSpan(0L, 234_950L), digital to SoundSpan(0L, 235_000L))

    @Test
    fun dragonbornIsMissedByThePlainRuleBecauseOfFourMilliseconds() {
        assertTrue(audioDuplicateGroups(dragonVectors, durationsMs = dragonDurations).isEmpty())
    }

    @Test
    fun dragonbornIsFoundFromItsSoundEdgesAlone() = runTest {
        val measured = mutableListOf<TrackId>()
        val found = findAudioDuplicates(
            vectors = dragonVectors,
            durationsMs = dragonDurations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { TrackRecordingIds() },
            measureSoundMs = { id ->
                measured += id
                soundLengthMs(dragonSpans[id], dragonDurations[id])
            },
        )
        assertEquals(listOf(listOf(digital, cd)), found)
        // Only the undecided pair is decoded; the unrelated track never is.
        assertEquals(setOf(cd, digital), measured.toSet())
    }

    @Test
    fun dragonbornIsFoundFromItsTagsWithoutDecodingAnything() = runTest {
        var decodes = 0
        val found = findAudioDuplicates(
            vectors = dragonVectors,
            durationsMs = dragonDurations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { id -> if (id == other) TrackRecordingIds() else sameIds },
            measureSoundMs = { decodes++; null },
        )
        assertEquals(listOf(listOf(digital, cd)), found)
        assertEquals(0, decodes)
    }

    @Test
    fun dragonbornUsesSoundEdgesPlaybackAlreadyMeasured() = runTest {
        val found = findAudioDuplicates(
            vectors = dragonVectors,
            durationsMs = dragonDurations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { null },
            measureSoundMs = { error("no decode needed") },
            knownSoundMs = { id -> soundLengthMs(dragonSpans[id], dragonDurations[id]) },
        )
        assertEquals(listOf(listOf(digital, cd)), found)
    }

    @Test
    fun failedReadsAndDecodesLeaveThePlainRule() = runTest {
        val found = findAudioDuplicates(
            vectors = dragonVectors,
            durationsMs = dragonDurations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { error("unreadable") },
            measureSoundMs = { throw IllegalStateException("decoder") },
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun aSlowDecodeTimesOutAndStaysUnknown() = runTest {
        val found = findAudioDuplicates(
            vectors = dragonVectors,
            durationsMs = dragonDurations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { null },
            measureSoundMs = { id ->
                if (id == cd) delay(DuplicateEvidenceLimits.SOUND_MEASUREMENT_TIMEOUT_MS + 1)
                soundLengthMs(dragonSpans[id], dragonDurations[id])
            },
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun aScanWithoutUndecidedPairsGathersNothing() = runTest {
        val found = findAudioDuplicates(
            vectors = mapOf(a to at(1.0), b to at(0.999), c to at(0.5)),
            durationsMs = mapOf(a to 100_000L, b to 101_000L, c to 100_000L),
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { error("not needed") },
            measureSoundMs = { error("not needed") },
        )
        assertEquals(listOf(listOf(a, b)), found)
    }

    @Test
    fun decodesAreCappedPerScan() = runTest {
        // Forty separate padded pairs: only the first measurements are taken, never a crash.
        val vectors = LinkedHashMap<TrackId, FloatArray>()
        val durations = HashMap<TrackId, Long?>()
        repeat(40) { index ->
            val angle = index * 0.5
            val row = floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat(), index.toFloat())
            vectors[TrackId("p$index-x")] = row
            vectors[TrackId("p$index-y")] = row
            durations[TrackId("p$index-x")] = 100_000L + index * 60_000L
            durations[TrackId("p$index-y")] = 104_000L + index * 60_000L
        }
        var decodes = 0
        val found = findAudioDuplicates(
            vectors = vectors,
            durationsMs = durations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { null },
            measureSoundMs = { decodes++; 99_000L },
        )
        assertEquals(DuplicateEvidenceLimits.MAX_SOUND_MEASUREMENTS, decodes)
        assertEquals(DuplicateEvidenceLimits.MAX_SOUND_MEASUREMENTS / 2, found.size)
    }

    @Test
    fun aReaderCancellingItselfIsAnUnreadFileNotACancelledScan() = runTest {
        val found = findAudioDuplicates(
            vectors = dragonVectors,
            durationsMs = dragonDurations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { throw kotlinx.coroutines.CancellationException("provider gave up") },
            measureSoundMs = { id -> soundLengthMs(dragonSpans[id], dragonDurations[id]) },
        )
        assertEquals(listOf(listOf(digital, cd)), found)
    }

    @Test
    fun anExhaustedDecodeBudgetStillUsesStoredSoundLengths() = runTest {
        val clock = kotlin.time.TestTimeSource()
        val p = TrackId("p"); val q = TrackId("q"); val r = TrackId("r"); val s = TrackId("s")
        val t = TrackId("t"); val u = TrackId("u")
        val vectors = mapOf(
            p to floatArrayOf(1f, 0f, 0f), q to floatArrayOf(1f, 0f, 0f),
            r to floatArrayOf(0f, 1f, 0f), s to floatArrayOf(0f, 1f, 0f),
            t to floatArrayOf(0f, 0f, 1f), u to floatArrayOf(0f, 0f, 1f),
        )
        val durations = mapOf(
            p to 100_000L, q to 104_000L, r to 200_000L, s to 204_000L, t to 300_000L, u to 304_000L,
        )
        val decoded = mutableListOf<TrackId>()
        val found = findAudioDuplicates(
            vectors = vectors,
            durationsMs = durations,
            dismissed = DuplicateExclusions(emptyList()),
            readIds = { null },
            measureSoundMs = { id ->
                decoded += id
                clock += kotlin.time.Duration.parse("60s") // two decodes spend the whole budget
                99_000L
            },
            // Playback measured r and s already; t and u would need decodes.
            knownSoundMs = { id -> if (id == r || id == s) 150_000L else null },
            timeSource = clock,
        )
        assertEquals(listOf(p, q), decoded)
        assertEquals(setOf(setOf(p, q), setOf(r, s)), found.map { it.toSet() }.toSet())
    }

    @Test
    fun withoutEvidenceTheScanEqualsThePlainRuleOnHighDimensionalVectors() {
        val random = kotlin.random.Random(42)
        for (dimensions in listOf(512, 513, 7)) {
            val centres = List(6) { FloatArray(dimensions) { random.nextFloat() - 0.5f } }
            val vectors = LinkedHashMap<TrackId, FloatArray>()
            val durations = HashMap<TrackId, Long?>()
            repeat(300) { index ->
                val centre = centres[index % centres.size]
                // Tight clusters: many pairs sit right around the 0.99 threshold.
                val spread = 0.02f + random.nextFloat() * 0.13f
                val row = FloatArray(dimensions) { centre[it] + spread * (random.nextFloat() - 0.5f) }
                val id = TrackId("t" + index.toString().padStart(4, '0'))
                vectors[id] = row
                durations[id] = when (index % 7) {
                    0 -> null
                    else -> 180_000L + random.nextLong(0L, 8_000L)
                }
            }
            val dismissed = DuplicateExclusions(listOf(listOf(TrackId("t0000"), TrackId("t0006"))))
            val expected = plainRuleGroups(vectors, durations, dismissed)
            assertTrue(expected.size > 3, "the fixture must hold several groups")
            assertEquals(
                expected,
                audioDuplicateGroups(vectors, durationsMs = durations, dismissed = dismissed),
                "dimensions $dimensions",
            )
        }
    }

    /** The finder as it was before evidence: cosine via squared distance, 2 s duration buckets. */
    private fun plainRuleGroups(
        vectors: Map<TrackId, FloatArray>,
        durationsMs: Map<TrackId, Long?>,
        dismissed: DuplicateExclusions,
        threshold: Float = DUPLICATE_SIMILARITY,
    ): List<List<TrackId>> {
        val ids = vectors.keys.sortedBy(TrackId::value)
        val rows = ids.map { id ->
            val row = vectors.getValue(id)
            var norm = 0.0
            for (x in row) norm += x.toDouble() * x
            val scale = 1.0 / kotlin.math.sqrt(norm)
            if (kotlin.math.abs(scale - 1.0) < 1e-4) row else FloatArray(row.size) { (row[it] * scale).toFloat() }
        }
        val maximumSquaredDistance = 2f * (1f - threshold)
        val durations = ids.map { durationsMs[it]?.takeIf { d -> d > 0 } }
        fun areDuplicates(x: Int, y: Int): Boolean {
            if (dismissed.excludes(ids[x], ids[y])) return false
            val a = rows[x]
            val b = rows[y]
            var squared = 0f
            for (d in a.indices) {
                val difference = a[d] - b[d]
                squared += difference * difference
                if (squared > maximumSquaredDistance) return false
            }
            return true
        }
        val groups = mutableListOf<MutableList<Int>>()
        val groupOf = IntArray(ids.size) { -1 }
        for (candidate in ids.indices) {
            val duration = durations[candidate]
            val priors = (0 until candidate).filter { prior ->
                val other = durations[prior]
                duration == null || other == null || kotlin.math.abs(duration - other) <= 2_000L
            }
            val duplicatePrior = priors.filter { areDuplicates(candidate, it) }.toHashSet()
            val compatible = duplicatePrior.map { groupOf[it] }.filter { it >= 0 }.distinct().sorted()
                .firstOrNull { group -> groups[group].all { it in duplicatePrior } }
            val index = if (compatible == null) {
                groups += mutableListOf(candidate)
                groups.lastIndex
            } else {
                groups[compatible] += candidate
                compatible
            }
            groupOf[candidate] = index
        }
        return groups.filter { it.size > 1 }.map { group -> group.map { ids[it] } }
            .sortedWith(compareByDescending<List<TrackId>> { it.size }.thenBy { it.first().value })
    }
}
