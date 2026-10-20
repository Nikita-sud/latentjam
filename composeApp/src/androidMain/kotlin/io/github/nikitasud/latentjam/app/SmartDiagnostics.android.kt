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
