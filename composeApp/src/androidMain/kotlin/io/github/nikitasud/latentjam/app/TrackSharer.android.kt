/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.nikitasud.latentjam.smart.TrackDescriptor

@Composable
internal actual fun rememberTrackSharer(): (List<TrackDescriptor>) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { tracks ->
            val uris = tracks.mapNotNull { it.audioUri?.let(Uri::parse) }.distinct()
            if (uris.isNotEmpty()) {
                val send = Intent(
                    if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
                ).apply {
                    type = "audio/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (uris.size == 1) {
                        putExtra(Intent.EXTRA_STREAM, uris.single())
                    } else {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                    clipData = ClipData.newUri(context.contentResolver, "audio", uris.first())
                        .also { clip ->
                            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                        }
                }
                context.startActivity(
                    Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
