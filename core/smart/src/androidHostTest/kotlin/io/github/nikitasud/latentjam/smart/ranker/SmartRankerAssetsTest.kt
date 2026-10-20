/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shipped ranker assets pass their manifest; a damaged or missing one leaves a working rule-only ranker. */
class SmartRankerAssetsTest {
    private val ml = File("../../androidApp/src/main/assets/ml")
    private fun read(name: String) = File(ml, name).takeIf { it.isFile }?.readBytes()?.let(AssetBytes::of)

    private val library = (0 until 30).map { TrackDescriptor(TrackId("t$it"), title = "Song $it", artist = "Artist ${it % 7}", genre = "Rock") }

    @Test
    fun shippedAssetsMatchTheirManifest() = runTest {
        val assets = SmartRankerAssets(null, ::read)
        val ranker = assets.ranker
        assertNull(assets.problem, assets.problem)
        assertTrue(assets.healthy)
        assertTrue(ranker.hasFullModel)
        assertTrue(ranker.modelName.startsWith("CORE L2"), ranker.modelName)
        assertEquals(20, ranker.plan(PlanRequest(library[0], library, library, length = 20)).ids.size)
    }

    @Test
    fun aDamagedOrMissingAssetFailsTheManifestButNotTheQueue() = runTest {
        for (broken in listOf("artist_colisten.bin", "smart_ranker.json", "smart_manifest.json")) {
            val assets = SmartRankerAssets(null, { name ->
                when {
                    name == broken && name.endsWith(".bin") -> read(name)?.let { b -> AssetBytes.of(ByteArray(b.size) { if (it == 100) (b[it] + 1).toByte() else b[it] }) }
                    name == broken -> null
                    else -> read(name)
                }
            })
            val ranker = assets.ranker
            assertFalse(assets.healthy, broken)
            val plan = ranker.plan(PlanRequest(library[0], library, library, length = 10, ruleOnly = !assets.healthy))
            assertEquals(10, plan.ids.size, broken)
            assertEquals("rule-only", plan.model, broken)
        }
    }

    @Test
    fun anotherAudioEncoderIsRefusedVisibly() = runTest {
        val assets = SmartRankerAssets(null, ::read, audioEncoder = "mnv4-960-retrieval-distill-v1-p50q4aq")
        assets.ranker
        assertFalse(assets.healthy)
        assertTrue(assets.problem!!.contains("embedding_version.txt"), assets.problem)
        val head = SmartRankerAssets(null, { name ->
            if (name == "semantic_version.txt") AssetBytes.of("x\nencoder=other\n".encodeToByteArray()) else read(name)
        })
        head.ranker
        assertTrue(head.problem!!.contains("semantic_version.txt"), head.problem)
    }

    @Test
    fun aModelWithAKeyTheAppDoesNotImplementIsRefused() = runTest {
        val core = File(ml, "smart_ranker.json").readText()
        assertTrue(RankerModel.parse(core) != null)
        val unknown = core.replaceFirst("{", "{\"mystery\": 0.5, ")
        assertNull(RankerModel.parse(unknown))
        assertEquals("unknown model key 'mystery'", RankerModel.parseOrProblem(unknown).second)
        val nested = core.replaceFirst("\"serving\": {", "\"serving\": {\"maxPool\": 400, ")
        assertEquals("unknown model key 'serving.maxPool'", RankerModel.parseOrProblem(nested).second)
        val declared = core.replaceFirst("\"inputs\": {", "\"inputs\": {\"audioEncoder\": \"other\", ")
        assertEquals("other", RankerModel.parse(declared)?.audioEncoder)
    }

    @Test
    fun l2TrackFieldsComeFromTheFileTags() = runTest {
        val tracks = (0 until 12).map {
            TrackDescriptor(
                TrackId("c$it"), title = "Song $it", artist = "Singer ${it % 4}", album = if (it < 6) "Slow Jams" else "Other LP",
                albumArtist = if (it < 6) null else "Singer 1", genre = if (it % 2 == 0) "R&B" else "Unknown",
                year = 2001, originalYear = if (it == 0) 1994 else null, durationMs = 200_000L + it,
            )
        }
        val ranker = SmartRankerAssets(null, ::read).ranker
        val trace = PlanTrace()
        ranker.tracer = trace
        ranker.plan(PlanRequest(tracks[0], tracks, tracks, length = 5))
        val col = { name: String -> RankerFeatures.NAMES.indexOf(name) }
        val row = { id: String -> trace.seedFeatures[trace.pool.indexOf(TrackId(id))] }
        assertEquals(1.0, row("c2")[col("family_is_track")], "an R&B tag maps on the twelve families")
        assertEquals(0.0, row("c1")[col("family_is_track")])
        assertEquals(0.7, row("c2")[col("year_dist")], 1e-12, "the seed's original year, the candidate's year")
        // "Slow Jams" without an album artist keys on each track artist: c4 shares the seed's (Singer 0), c2 does not.
        assertEquals(1.0, row("c4")[col("same_album")])
        assertEquals(0.0, row("c2")[col("same_album")])
        assertEquals(0.0, row("c4")[col("same_compilation")], "one artist on that key: not a compilation")
    }

    @Test
    fun theArtistSetsKeySelectsAs2AndAnUnknownValueIsRefused() = runTest {
        val core = File(ml, "smart_ranker.json").readText()
        assertFalse(RankerModel.parse(core)!!.artistSetsAs2)
        assertTrue(RankerModel.parse(core.replaceFirst("{", "{\"artistSets\": \"as2\", "))!!.artistSetsAs2)
        assertFalse(RankerModel.parse(core.replaceFirst("{", "{\"artistSets\": \"as1\", "))!!.artistSetsAs2)
        assertNull(RankerModel.parse(core.replaceFirst("{", "{\"artistSets\": \"as3\", ")))
    }
    @Test
    fun theTaxonomyAndVersionKeysAreParsedAndAWrongTaxonomyFallsBackToRules() = runTest {
        val core = File(ml, "smart_ranker.json").readText()
        assertNull(RankerModel.parse(core)!!.taxonomy)
        assertFalse(RankerModel.parse(core)!!.versionKeyV2)
        assertEquals("2.2", RankerModel.parse(core.replaceFirst("{", "{\"taxonomy\": \"2.2\", "))!!.taxonomy)
        assertNull(RankerModel.parse(core.replaceFirst("{", "{\"taxonomy\": \"2.3\", ")))
        assertTrue(RankerModel.parse(core.replaceFirst("{", "{\"versionKey\": \"v2\", "))!!.versionKeyV2)
        assertNull(RankerModel.parse(core.replaceFirst("{", "{\"versionKey\": \"v3\", ")))
        // The shipped genre_families.json has the twelve v2.1 families: a v2.2 model reads codes it does not have.
        for ((taxonomy, fits) in listOf("2.1" to true, "2.2" to false)) {
            val ranker = SmartRankerAssets(null, { name ->
                if (name == "smart_ranker.json" || name == "smart_ranker_full.json") {
                    AssetBytes.of(File(ml, name).readText().replaceFirst("{", "{\"taxonomy\": \"$taxonomy\", ").encodeToByteArray())
                } else read(name)
            }).ranker
            assertEquals(fits, ranker.modelName != RankerModel.RULE_SET.name, taxonomy)
            assertEquals(fits, ranker.hasFullModel, taxonomy)
        }
    }

    @Test
    fun as2GivesAPlaceholderCreditNoEngineArtistAndDropsSpokenWordFolders() = runTest {
        val tracks = (0 until 12).map {
            TrackDescriptor(
                TrackId("p$it"), title = "Song $it", artist = if (it < 6) "Unknown Artist" else "Singer ${it % 3}", genre = "Rock",
                folderPath = if (it == 11) "Music/Podcasts/Show" else "Music", durationMs = 200_000L,
            )
        }
        val col = RankerFeatures.NAMES.indexOf("same_artist")
        for (as2 in listOf(false, true)) {
            val ranker = SmartRankerAssets(null, ::read, artistSetsAs2 = as2).ranker
            val trace = PlanTrace()
            ranker.tracer = trace
            ranker.plan(PlanRequest(tracks[0], tracks, tracks, length = 5))
            val row = { id: String -> trace.seedFeatures[trace.pool.indexOf(TrackId(id))] }
            assertEquals(if (as2) 0.0 else 1.0, row("p1")[col], "as2=$as2: two Unknown Artist tracks")
            assertEquals(as2, TrackId("p11") !in trace.pool, "as2=$as2: a podcasts folder")
        }
        assertFalse(SmartRanker.isNonMusic(tracks[11]))
        assertTrue(SmartRanker.isNonMusic(tracks[11].copy(folderPath = "WhatsApp/Media/WhatsApp Voice Notes/2026"), as2 = true))
        assertTrue(SmartRanker.isNonMusic(tracks[11].copy(folderPath = "Call_rec"), as2 = true))
        assertFalse(SmartRanker.isNonMusic(tracks[11].copy(folderPath = "Music/Podcast Remixes"), as2 = true))
    }
}
