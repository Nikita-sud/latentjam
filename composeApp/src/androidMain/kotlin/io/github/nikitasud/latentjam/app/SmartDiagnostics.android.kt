/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File

internal actual fun writeDebugFile(name: String, text: String): String? = runCatching {
    val dir = AndroidAppContext.value.getExternalFilesDir(null) ?: return null
    File(dir, name).also { it.writeText(text) }.absolutePath
}.getOrNull()

internal actual fun appendDebugLine(name: String, line: String): Boolean = runCatching {
    val dir = AndroidAppContext.value.getExternalFilesDir(null) ?: return false
    val file = File(dir, name)
    if (file.length() > 4L * 1024 * 1024) file.renameTo(File(dir, "$name.1"))
    file.appendText(line.trimEnd('\n') + "\n")
    true
}.getOrDefault(false)
