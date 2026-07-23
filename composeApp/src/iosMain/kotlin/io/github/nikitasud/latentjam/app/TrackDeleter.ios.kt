/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Deletes only files owned by LatentJam in its Documents directory.
 *
 * A descriptor's relative ID is used instead of trusting an arbitrary URL. Music.app descriptors
 * live in a separate `ios-media:` namespace and are read-only, while `..` and absolute paths are
 * rejected so this action cannot escape the app's sandbox even if a malformed descriptor appears.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberTrackDeleter(onDeleted: () -> Unit): (List<TrackDescriptor>) -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnDeleted = rememberUpdatedState(onDeleted)
    return { tracks ->
        val safeRelatives = tracks.mapNotNull { track ->
            track.id.value.takeIf { relative ->
                relative.isNotBlank() &&
                    !relative.startsWith("/") &&
                    !relative.startsWith(MEDIA_ID_PREFIX) &&
                    relative.split('/').none { segment -> segment == ".." }
            }
        }.distinct()
        if (safeRelatives.isNotEmpty()) {
            scope.launch {
                val deletedAny = withContext(Dispatchers.Default) {
                    val documents = NSSearchPathForDirectoriesInDomains(
                        NSDocumentDirectory,
                        NSUserDomainMask,
                        true,
                    ).firstOrNull() as? String ?: return@withContext false
                    var removed = false
                    safeRelatives.forEach { safeRelative ->
                        val path = if (documents.endsWith('/')) {
                            documents + safeRelative
                        } else {
                            "$documents/$safeRelative"
                        }
                        if (memScoped {
                            val error = alloc<ObjCObjectVar<NSError?>>()
                            NSFileManager.defaultManager.removeItemAtPath(path, error.ptr)
                        }) {
                            removed = true
                        }
                    }
                    removed
                }
                if (deletedAny) currentOnDeleted.value()
            }
        }
    }
}

private const val MEDIA_ID_PREFIX = "ios-media:"
