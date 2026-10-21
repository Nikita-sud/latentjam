/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun writeDebugFile(name: String, text: String): String? {
    val dir = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return null
    val path = "$dir/$name"
    @Suppress("CAST_NEVER_SUCCEEDS")
    val ok = (NSString.create(string = text)).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    return if (ok) path else null
}

/** The per-press diagnostics log is an Android test-build tool. */
internal actual fun appendDebugLine(name: String, line: String): Boolean = false
