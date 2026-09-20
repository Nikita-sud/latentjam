/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberLyricsSearchStorage(): LyricsSearchStorage {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        object : LyricsSearchStorage {
            private val file = AtomicFile(File(context.cacheDir, "lyrics-search-v1"))
            override suspend fun read(): String? = withContext(Dispatchers.IO) {
                if (file.baseFile.exists()) file.openRead().bufferedReader().use { it.readText() } else null
            }
            override suspend fun write(payload: String) = withContext(Dispatchers.IO) {
                val output = file.startWrite()
                try {
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (failure: Throwable) {
                    file.failWrite(output)
                    throw failure
                }
            }
        }
    }
}
