/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.nikitasud.latentjam.library.AndroidTrackArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The intent action a pinned playlist shortcut opens [MainActivity] with. */
internal const val ACTION_OPEN_PLAYLIST = "io.github.nikitasud.latentjam.action.OPEN_PLAYLIST"
internal const val EXTRA_PLAYLIST_ID = "io.github.nikitasud.latentjam.extra.PLAYLIST_ID"

/** 108 dp at xxxhdpi: an adaptive icon's full-bleed layer; the launcher masks the outer sixth. */
private const val ICON_SIZE_PX = 432

internal actual fun playlistShortcutsSupported(): Boolean =
    ShortcutManagerCompat.isRequestPinShortcutSupported(AndroidAppContext.value)

internal actual suspend fun pinPlaylistShortcut(playlistId: String, name: String, coverUri: String?): Boolean {
    val context = AndroidAppContext.value
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
    val cover = coverUri?.let { withContext(Dispatchers.IO) { readCover(context, it) } }
    val icon = cover?.let(IconCompat::createWithAdaptiveBitmap)
        ?: IconCompat.createWithResource(context, context.applicationInfo.icon)
    val shortcut = ShortcutInfoCompat.Builder(context, playlistShortcutId(playlistId))
        .setShortLabel(name)
        .setLongLabel(name)
        .setIcon(icon)
        .setIntent(openPlaylistIntent(context, playlistId))
        .build()
    // Pinned before (a new cover, a new name since): the launcher would offer the old copy again.
    val alreadyPinned = runCatching {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .any { it.id == shortcut.id }
    }.getOrDefault(false)
    if (alreadyPinned) {
        // Greyed out when its playlist was deleted, and the playlist is back (a restored backup keeps its id): the
        // launcher refuses, by throwing, a pin request for a shortcut that is disabled.
        runCatching { ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut)) }
        runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut)) }
    }
    // The launcher may refuse by throwing too (the app left the foreground meanwhile): that is a "no", not a crash.
    return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
}

internal actual fun renamePlaylistShortcut(playlistId: String, name: String) {
    val context = AndroidAppContext.value
    val id = playlistShortcutId(playlistId)
    runCatching {
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .firstOrNull { it.id == id } ?: return
        // The rebuilt shortcut keeps its icon: updateShortcuts only replaces the fields it is given,
        // but the compat builder requires an intent, so the same one is set again.
        val renamed = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(name)
            .setLongLabel(name)
            .setIntent(pinned.intent ?: openPlaylistIntent(context, playlistId))
            .build()
        ShortcutManagerCompat.updateShortcuts(context, listOf(renamed))
    }
}

internal actual fun disablePlaylistShortcut(playlistId: String, message: String) {
    val context = AndroidAppContext.value
    runCatching {
        ShortcutManagerCompat.disableShortcuts(context, listOf(playlistShortcutId(playlistId)), message)
    }
}

internal actual fun enablePlaylistShortcut(playlistId: String) {
    val context = AndroidAppContext.value
    val id = playlistShortcutId(playlistId)
    runCatching {
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .firstOrNull { it.id == id } ?: return
        ShortcutManagerCompat.enableShortcuts(context, listOf(pinned))
    }
}

private fun openPlaylistIntent(context: Context, playlistId: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_PLAYLIST)
        .putExtra(EXTRA_PLAYLIST_ID, playlistId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** The cover, square and full-bleed; null keeps the app icon (no cover, no access, unreadable). */
private fun readCover(context: Context, uri: String): Bitmap? = try {
    // The loader scales to cover the square but does not cut: a wide cover is cut here, or the launcher would
    // squash it into the icon's square.
    AndroidTrackArtwork.loadDisplayBitmap(context.contentResolver, Uri.parse(uri), ICON_SIZE_PX, ICON_SIZE_PX, crop = true)
        ?.let { cover ->
            val side = minOf(cover.width, cover.height)
            if (cover.width == cover.height) cover
            else Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
        }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
