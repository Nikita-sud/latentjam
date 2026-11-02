/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the crossfade's gain functions and [seamAnchors] on a fixed grid to
 * `build/render-check-table.tsv`, for research renders to be checked against the app's own numbers.
 */
class RenderCheckTableTest {
    @Test
    fun writesTheCheckTable() {
        val rows = buildList {
            for (i in 0..20) {
                val p = i / 20f
                add("out\t$p\t${overlapOutGain(p)}")
                add("in\t$p\t${overlapInGain(p)}")
                add("knee\t$p\t${overlapKnee(p)}")
            }
            for (i in -12..12) {
                val x = i / 10f
                add("softclip0.8\t$x\t${softClip(x, 0.8f)}")
                add("softclip0.9\t$x\t${softClip(x, 0.9f)}")
            }
            for (lufs in listOf(-30f, -20f, -14f, -10f, -6f)) for (peak in listOf(-6f, -2f, -1f, 0f, 1f)) {
                add("normvol\t$lufs,$peak\t${IntegratedLoudness(lufs, peak).normalizationVolume()}")
            }
            val spans = listOf(null, SoundSpan(0, 199_800), SoundSpan(1_500, 180_000), SoundSpan(350, 190_000), SoundSpan(45_000, 150_000))
            for (out in spans) for (inc in spans) {
                val a = seamAnchors(200_000L, out, inc)
                add("anchors\t$out|$inc\t${a.outgoingEndMs},${a.incomingStartMs}")
            }
        }
        val file = File("build/render-check-table.tsv")
        file.writeText(rows.joinToString("\n", postfix = "\n"))
        assertTrue(file.length() > 0)
    }
}
