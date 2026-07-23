/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSBundle

@Composable
internal actual fun rememberAppVersion(): String? = remember {
    runCatching {
        NSBundle.mainBundle
            .objectForInfoDictionaryKey("CFBundleShortVersionString")
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
