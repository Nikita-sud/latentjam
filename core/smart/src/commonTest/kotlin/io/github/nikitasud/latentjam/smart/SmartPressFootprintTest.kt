/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.CentredRows
import io.github.nikitasud.latentjam.smart.chain.ScaledRows
import io.github.nikitasud.latentjam.smart.chain.SmartSnapshot
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledgePack
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import io.github.nikitasud.latentjam.smart.text.EntityIndexBytes
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What one SMART press holds in memory, and that holding less of it changes nothing it plans.
 *
 * The fixture is a library the size where the hub penalty samples its anchors, built the way the app
 * builds one: audio and text vectors in the engine's own indexes, descriptors from an artist pack (for
 * the artists it knows) and the adapter (for the rest), rows without any text, energies on some, a
 * restart whose history names tracks the library no longer lists, marked playlists and a top-up.
 * Every value is derived from integer arithmetic, so the floats are the same on every platform.
 */
internal class SmartPressFootprintTest {

    @Test
    fun `a SMART press builds the recorded snapshot and plans the recorded queues`() = runTest {
        val audioStore = FakeIndexStore()
        val textStore = FakeIndexStore()
        val first = engine(audioStore, textStore)
        first.initialize()
        assertEquals(library.size, first.indexLibrary(library).indexed)
        first.ensureMetadataVectors(library)
        first.persistPendingAnalysis()

        val planned = buildList {
            for (seed in listOf(3, 404, 777)) add("plain $seed: " + first.smartQueue(library[seed], library, 12).text())
        }
        val firstSnapshot = digest(requireNotNull(first.smartSnapshot(emptyList())))

        // A restart restores both indexes; the library it lists has lost its last hundred tracks, and the
        // history still names a few of them. Judged scoring, a run penalty, marked playlists and a top-up.
        val restarted = engine(audioStore, textStore, judged = true)
        restarted.initialize()
        val listed = library.dropLast(100)
        val groups = listOf(
            (200 until 230).map { library[it].id }.toSet(),
            (600 until 612).map { library[it].id }.toSet(),
        )
        val start = restarted.smartQueue(library[205], listed, 12, history, groups)
        val tail = listed.first { it.id == start.last() }
        val rest = listed.filter { it.id !in start && it.id != library[205].id }
        val topUp = restarted.continueSmartQueue(
            tail, rest, 12, history, groups, precedingTrackIds = listOf(library[205].id) + start.dropLast(1),
        )
        val restartedSnapshot = digest(requireNotNull(restarted.smartSnapshot(history)))

        val queues = planned + listOf("judged start: " + start.text(), "judged top-up: " + topUp.text())
        assertEquals(RECORDED_FIRST_SNAPSHOT, firstSnapshot, "first engine's snapshot")
        assertEquals(RECORDED_RESTARTED_SNAPSHOT, restartedSnapshot, "restarted engine's snapshot")
        assertEquals(RECORDED_QUEUES, queues)
    }

    /** A real index that counts the calls that copy a stored vector: [VectorIndex.vector] and [VectorIndex.entries]. */
    private class CopyCountingIndex(private val inner: InMemoryVectorIndex) : VectorIndex by inner {
        var copiedVectors = 0

        override fun vector(id: TrackId): FloatArray? = inner.vector(id).also { if (it != null) copiedVectors++ }

        override fun entries(): Map<TrackId, FloatArray> = inner.entries().also { copiedVectors += it.size }
    }

    @Test
    fun `a SMART press reads the stored vectors without copying them`() = runTest {
        val audio = CopyCountingIndex(InMemoryVectorIndex(SmartSnapshot.AUDIO_DIM))
        val text = CopyCountingIndex(InMemoryVectorIndex(TextEncoder.TEXT_DIM))
        val engine = engine(FakeIndexStore(), FakeIndexStore(), audio = audio, text = text)
        engine.initialize()
        engine.indexLibrary(library)
        engine.ensureMetadataVectors(library)
        audio.copiedVectors = 0
        text.copiedVectors = 0

        assertEquals(12, engine.smartQueue(library[3], library, 12, history).size)

        // Before, every row was copied out of the audio index and twice out of the text index (its
        // text vector, and the input of its descriptor): 1,101 and 2,112 copies for this library.
        assertEquals(0, audio.copiedVectors, "audio vectors copied by one press")
        assertEquals(0, text.copiedVectors, "text vectors copied by one press")
    }

    // ---------------------------------------------------------------- fixture

    private fun engine(
        audioStore: IndexStore,
        textStore: IndexStore,
        judged: Boolean = false,
        audio: VectorIndex = InMemoryVectorIndex(SmartSnapshot.AUDIO_DIM),
        text: VectorIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
    ) =
        DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(audioVectors),
            index = audio,
            store = audioStore,
            config = SmartEngineConfig(
                embeddingDim = SmartSnapshot.AUDIO_DIM, modelVersion = "footprint",
                continueAfterExhaustion = judged, judgedScoring = judged, artistRunPenalty = if (judged) 0.5f else 0f,
            ),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "footprint"),
            textEncoder = FixtureTextEncoder,
            textIndex = text,
            textStore = textStore,
            artistKnowledge = knowledge,
        )

    private fun List<TrackId>.text() = joinToString(" ") { it.value }

    /** xorshift64*; the top 24 bits give exact floats in [0, 1). */
    private class Stream(seed: Long) {
        private var state = seed or 1L
        fun nextLong(): Long {
            state = state xor (state ushr 12)
            state = state xor (state shl 25)
            state = state xor (state ushr 27)
            return state * 2685821657736338717L
        }
        fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()
        fun unit(): Float = (nextLong() ushr 40).toInt() / 16_777_216f
        fun centred(): Float = unit() + unit() + unit() - 1.5f
    }

    private val syllables = listOf("ka", "lo", "mi", "ra", "zu", "te", "no", "vi", "sa", "dre")
    private val artists = List(48) { a ->
        (syllables[a % 10] + syllables[(a / 10 + a) % 10] + syllables[(a * 7 + 3) % 10]).replaceFirstChar { it.uppercase() }
    }

    /** The pack knows the first sixteen; the adapter guesses the rest from their text vectors. */
    private val knownArtists = artists.take(16)
    private val genres = listOf("Rock", "Pop", "Jazz", "Electronic", "Folk", "Hip-Hop", "Classical", "Metal")

    private val library: List<TrackDescriptor> = run {
        val stream = Stream(0x5EED)
        List(1_100) { k ->
            val id = TrackId("t" + k.toString().padStart(4, '0'))
            val untagged = k % 25 == 7
            val artist = artists[stream.nextInt(artists.size)]
            val partner = if (k % 19 == 4) artists[stream.nextInt(artists.size)] else null
            TrackDescriptor(
                id = id,
                title = if (untagged) null else "Song $k",
                artist = when {
                    untagged -> null
                    partner != null -> "$artist & $partner"
                    else -> artist
                },
                artists = if (partner != null) listOf(artist, partner) else emptyList(),
                album = if (untagged) null else "Album ${k / 12}",
                genre = if (untagged) null else genres[artists.indexOf(artist) % genres.size],
                year = if (untagged || k % 5 == 0) null else 1960 + (k * 7) % 60,
                durationMs = 120_000L + (k * 977L) % 240_000L,
                energy = if (k % 3 == 0) (k % 10) / 10f else null,
                audioUri = "test://$k",
            )
        }
    }

    /** Sixteen sound clusters around a shared offset, as real embeddings share a mean direction. */
    private val audioVectors: MutableMap<TrackId, FloatArray> = run {
        val stream = Stream(0xA0D10)
        val offset = FloatArray(SmartSnapshot.AUDIO_DIM) { stream.centred() * 0.8f }
        val centres = List(16) { FloatArray(SmartSnapshot.AUDIO_DIM) { stream.centred() } }
        library.associateTo(mutableMapOf()) { track ->
            val centre = centres[stream.nextInt(centres.size)]
            track.id to FloatArray(SmartSnapshot.AUDIO_DIM) { d -> offset[d] + centre[d] + 0.7f * stream.centred() }
        }
    }

    private val history: List<SmartHistoryEvent> = listOf(1_010, 1_050, 12, 1_099, 330, 1_020).mapIndexed { i, k ->
        SmartHistoryEvent(library[k].id, startedAtMs = 1_000L * i, playedFraction = 0.5f + i / 20f, completed = i % 2 == 0, skipped = i == 3)
    }

    /** Genre and artist set the direction, the rest of the string a little noise: like the student, roughly. */
    private object FixtureTextEncoder : TextEncoder {
        override suspend fun load(): Result<Unit> = Result.success(Unit)
        override fun encode(metadata: String): FloatArray {
            val words = metadata.split("; ")
            val genre = Stream(hash(words.first()))
            val rest = Stream(hash(metadata))
            return FloatArray(TextEncoder.TEXT_DIM) { genre.centred() + 0.4f * rest.centred() }
        }
        override fun close(): Unit = Unit
    }

    private val knowledge = ArtistKnowledge(
        MusicEntityResolver {
            AssetBytes.of(
                EntityIndexBytes.of(
                    *knownArtists.mapIndexed { id, name -> name.lowercase() to intArrayOf(id) }.toTypedArray(),
                    entityCount = knownArtists.size,
                ),
            )
        },
        loadAdapter = { adapterBytes() },
        loadPack = { AssetBytes.of(packBytes()) },
    )

    /** Version 2: 384 wide, two sub-spaces of four centroids, every known artist confident. */
    private fun packBytes(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += value.toByte() }
        fun u16(value: Int) { u8(value and 0xff); u8(value shr 8) }
        fun u32(value: Int) { u16(value and 0xffff); u16(value ushr 16) }
        val stream = Stream(0xBAC4)
        "LJKNOW1\u0000".encodeToByteArray().forEach { out += it }
        u32(2); u16(TextEncoder.TEXT_DIM); u8(2); u16(4); u32(knownArtists.size)
        repeat(2 * 4 * TextEncoder.TEXT_DIM / 2) { u16(HALVES[stream.nextInt(HALVES.size)]) }
        knownArtists.forEachIndexed { id, name ->
            u8(id % 4); u8((id / 4) % 4); u16(ArtistKnowledgePack.fingerprint(name))
            u8(if (id % 3 == 0) ArtistKnowledgePack.LANGUAGES.indexOf("ro") + 1 else 0); u8(0)
            u8(if (id % 2 == 0) (1970 - 1000) / 10 + 1 + id % 4 else 0)
            u8(200)
        }
        return out.toByteArray()
    }

    /**
     * Version 1: input 384, hidden 8. The first layer is zero, so the guess is the track's text vector
     * plus a fixed offset: GELU's tanh may round differently on another platform, GELU(0) cannot.
     */
    private fun adapterBytes(): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(value: Int) { out += (value and 0xff).toByte(); out += (value shr 8).toByte() }
        val stream = Stream(0xADA97)
        val width = TextEncoder.TEXT_DIM
        val hidden = 8
        "LJADPT1\u0000".encodeToByteArray().forEach { out += it }
        u16(1); u16(0); u16(width); u16(hidden)
        repeat(hidden * width + hidden) { u16(0) }
        repeat(width * hidden + width) { u16(HALVES[stream.nextInt(HALVES.size)]) }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- digests

    /** One FNV-1a 64 per field, so a mismatch names what moved. */
    private fun digest(snapshot: SmartSnapshot): String = with(snapshot) {
        listOf(
            "size=$size",
            "ids=" + fnv { tracks.forEach { track -> track.id.value.encodeToByteArray().forEach(::byte) } },
            "energy=" + fnv { tracks.forEach { floatBits(it.energy) } },
            "rawAudio=" + rows(rawAudio),
            "centeredAudio=" + centred(centeredAudio),
            "hubPenalty=" + floats(hubPenalty),
            "typicality=" + floats(typicality),
            "centeredText=" + centred(centeredText),
            "hasText=" + booleans(hasText),
            "rawText=" + rows(rawText),
            "descriptorDim=$descriptorDim",
            "centeredDescriptor=" + centred(centeredDescriptor),
            "hasDescriptor=" + booleans(hasDescriptor),
        ).joinToString("\n")
    }

    private class Fnv {
        var hash = -0x340d631b7bdddcdbL
        fun byte(value: Byte) {
            hash = (hash xor (value.toLong() and 0xff)) * 0x100000001b3L
        }
        fun floatBits(value: Float) {
            val bits = value.toRawBits()
            for (shift in 0 until 32 step 8) byte((bits ushr shift).toByte())
        }
    }

    private fun fnv(fill: Fnv.() -> Unit): String = Fnv().apply(fill).hash.toULong().toString(16)

    private fun floats(values: FloatArray?): String =
        if (values == null) "null" else "${values.size}:" + fnv { values.forEach(::floatBits) }

    /**
     * As [floats] over the centred rows laid end to end: the digest the centred matrix had before the snapshot stopped
     * holding one, so the recorded digests below still pin every centred float.
     */
    private fun centred(values: CentredRows?): String =
        if (values == null) "null" else "${values.size * values.dim}:" + fnv {
            val row = FloatArray(values.dim)
            for (r in 0 until values.size) {
                values.copyRow(r, row)
                row.forEach(::floatBits)
            }
        }

    /** As [floats] over the rows laid end to end: the digest a matrix of them would have. */
    private fun rows(values: ScaledRows?): String =
        if (values == null) "null" else "${values.size * values.dim}:" + fnv {
            for (row in 0 until values.size) for (d in 0 until values.dim) floatBits(values[row, d])
        }

    private fun booleans(values: BooleanArray?): String =
        if (values == null) "null" else "${values.count { it }}/${values.size}:" + fnv { values.forEach { byte(if (it) 1 else 0) } }

    private companion object {
        // Recorded on fix/low-memory-launch (10d997b1), where the engine copied every row out of both
        // indexes and the snapshot kept those copies and the decoded descriptors for its lifetime.
        val RECORDED_FIRST_SNAPSHOT = """
            size=1100
            ids=20b983bccbb82217
            energy=53b4618bdf687be2
            rawAudio=1056000:aa0581cd6e317998
            centeredAudio=1056000:43af54e706928b34
            hubPenalty=1100:7dbc15cd62357426
            typicality=1100:1d61bfa0f90cb040
            centeredText=422400:680ecd119088f9ee
            hasText=1056/1100:50c93651befb3c45
            rawText=422400:b0c16aec78ea6922
            descriptorDim=384
            centeredDescriptor=422400:91f8af2ac45217f5
            hasDescriptor=1057/1100:b830b4143d899f10
        """.trimIndent()

        val RECORDED_RESTARTED_SNAPSHOT = """
            size=1004
            ids=1c2f678a39cbb857
            energy=86315682949ace46
            rawAudio=963840:c2b9a9ea55e54b1f
            centeredAudio=963840:28cf7f3f8d0252ea
            hubPenalty=1004:e3f3cbbdc6fc988e
            typicality=1004:db9512c85be2b51e
            centeredText=385536:7938ef89bc340844
            hasText=964/1004:923d8d45e328dc59
            rawText=385536:fd7d97554967899
            descriptorDim=384
            centeredDescriptor=385536:998ab39d5f8679cc
            hasDescriptor=961/1004:38536dbd15782020
        """.trimIndent()

        val RECORDED_QUEUES = listOf(
            "plain 3: t1000 t0210 t0199 t0304 t0610 t0965 t0665 t0984 t0057 t0080 t0781 t0016",
            "plain 404: t0296 t0548 t0932 t0351 t0678 t0647 t0677 t0970 t0393 t0457 t0624 t0632",
            "plain 777: t0142 t0906 t0973 t1091 t0857 t0246 t0444 t0134 t0582 t0122 t0921 t0837",
            "judged start: t0983 t0715 t0600 t0416 t0105 t0931 t0449 t0531 t0197 t0287 t0244 t0520",
            "judged top-up: t0844 t0050 t0631 t0081 t0488 t0985 t0306 t0979 t0233 t0131 t0429 t0551",
        )

        /** ±1/16, ±1/8 and 0 as FP16 bit patterns: exact, so the decoded floats are too. */
        val HALVES = intArrayOf(0x2C00, 0xAC00, 0x3000, 0xB000, 0x0000)

        fun hash(text: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (byte in text.encodeToByteArray()) hash = (hash xor (byte.toLong() and 0xff)) * 0x100000001b3L
            return hash
        }
    }
}
