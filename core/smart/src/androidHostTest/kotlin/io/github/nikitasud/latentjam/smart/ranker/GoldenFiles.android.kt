/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import java.io.File
import java.util.zip.GZIPInputStream

internal actual object GoldenFiles {
    actual fun gzipText(name: String): String =
        GZIPInputStream(checkNotNull(GoldenFiles::class.java.getResourceAsStream("/$name")) { name }).reader().readText()

    actual fun ml(name: String): ByteArray = File("../../androidApp/src/main/assets/ml", name).readBytes()
}
