/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class CleanModelAssetsTest {
    @Test
    fun versionAndGraphsMatchAcrossPlatforms() {
        val android = File("../../androidApp/src/main/assets/ml")
        val ios = File("../../iosApp/iosApp/ml")
        assertEquals(AUDIO_MODEL_VERSION, File(android, "embedding_version.txt").readText().trim())
        val names = android.listFiles()!!.filter { it.isFile }.map { it.name }.sorted()
        assertFalse(names.any { it.startsWith("predictor_") })
        for (name in names) assertContentEquals(File(android, name).readBytes(), File(ios, name).readBytes(), name)
        val metadata = File(android, "semantic_version.txt").readLines()
        val hash = MessageDigest.getInstance("SHA-256").digest(File(android, "universal_semantic_head.onnx").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue("labels=27" in metadata)
        assertTrue("sha256=$hash" in metadata)
        assertEquals(104146L, File(android, "universal_semantic_head.onnx").length())
        assertEquals(3323667L, File(android, "mnv4_audio.onnx").length())
    }
}
