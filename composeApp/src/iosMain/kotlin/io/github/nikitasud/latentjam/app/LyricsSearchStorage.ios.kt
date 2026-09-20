/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberLyricsSearchStorage(): LyricsSearchStorage = remember {
    object : LyricsSearchStorage {
        private fun path(): String {
            val directory = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                .first() as String
            check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
            return "$directory/lyrics-search-v1"
        }
        override suspend fun read(): String? = withContext(Dispatchers.Default) {
            NSString.stringWithContentsOfFile(path(), NSUTF8StringEncoding, null)
        }
        override suspend fun write(payload: String): Unit = withContext(Dispatchers.Default) {
            @Suppress("CAST_NEVER_SUCCEEDS")
            check((payload as NSString).writeToFile(path(), true, NSUTF8StringEncoding, null))
        }
    }
}
