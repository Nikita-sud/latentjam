/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The model bundle both apps ship: the same files on each platform, every model named in its licence. */
class ShippedModelBundleTest {

    private val android = File("../../androidApp/src/main/assets/ml")
    private val ios = File("../../iosApp/iosApp/ml")

    private fun names(dir: File): List<String> {
        assertTrue(dir.isDirectory, "bundle missing at ${dir.absolutePath}")
        return dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
    }

    @Test
    fun `the iOS bundle mirrors the Android one file for file`() {
        val shipped = names(android)
        assertEquals(shipped, names(ios))
        for (name in shipped) {
            assertContentEquals(File(android, name).readBytes(), File(ios, name).readBytes(), name)
        }
    }

    @Test
    fun `the bundle ships only the graphs the app loads`() {
        // Since 0.8.1 the chain plans without the learned state and scorer nets, so neither ships.
        assertEquals(
            listOf("mnv4_audio.onnx", "text_encoder.onnx", "universal_semantic_head.onnx"),
            names(android).filter { it.endsWith(".onnx") },
        )
    }

    @Test
    fun `the licence file names every shipped model file`() {
        val licence = File(android, "LICENSE-MODEL.txt").readText()
        val models = names(android).filter { it.endsWith(".onnx") || it.endsWith(".bin") || it == "text_vocab.txt" }
        assertTrue(models.isNotEmpty())
        for (name in models) assertTrue(name in licence, "LICENSE-MODEL.txt does not name $name")
    }
}
