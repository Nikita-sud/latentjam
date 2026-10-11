/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

/**
 * Songs from the Music library have no file path, and the export picker never says where it saved
 * the copy, so there is nothing to write a relative path between.
 */
internal actual val relativePlaylistPathsAvailable: Boolean = false

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfcNormalized(text: String): String =
    (text as NSString).precomposedStringWithCanonicalMapping
