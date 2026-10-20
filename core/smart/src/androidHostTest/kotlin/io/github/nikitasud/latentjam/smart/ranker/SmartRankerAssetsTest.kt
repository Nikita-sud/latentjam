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
        assertTrue(ranker.modelName.startsWith("CORE final"), ranker.modelName)
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
}
