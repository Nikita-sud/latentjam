/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import io.github.nikitasud.latentjam.library.FolderTree

/**
 * Where a chain of playback errors began: the row that failed first, the point in it playback had reached, and how
 * that play had begun ([cause], null when nobody could say). When the chain finds nothing that can play, the playhead
 * goes back here, so the listener's place is still theirs once the files can be read again.
 */
internal data class RecoveryOrigin(
    val mediaId: String,
    val index: Int,
    val positionMs: Long,
    val cause: StartCause?,
)

/**
 * The row to put the playhead back on when an error chain ended at row [failedIndex] of [mediaIds] with nothing
 * readable: the [origin]'s row, found again by its media id (the row nearest to where it was, should the queue have
 * changed under the chain). Null when the chain never left its first row, which then keeps the position the player
 * has for it, or when that row is gone.
 */
internal fun recoveryReturnRow(mediaIds: List<String>, failedIndex: Int, origin: RecoveryOrigin?): Int? {
    origin ?: return null
    val row = mediaIds.indices
        .filter { mediaIds[it] == origin.mediaId }
        .minByOrNull { kotlin.math.abs(it - origin.index) }
        ?: return null
    return row.takeIf { it != failedIndex }
}

/**
 * Which storage volumes the system has now, by the names [io.github.nikitasud.latentjam.smart.TrackDescriptor.storageVolume]
 * uses. [mounted] can be read; [unmounted] are listed but cannot (ejected, being checked, taken out badly). With
 * [mountedIsComplete] every volume a track can name is in [mounted] when it is there, so a name missing from it is a
 * volume that is gone; without it only a listed, unmounted volume counts, since a volume the system does not list may
 * just be one it names differently.
 */
internal class StoragePresence(
    private val mounted: Set<String>,
    private val unmounted: Set<String>,
    private val mountedIsComplete: Boolean,
) {
    /** True only when [volume] is known not to be readable now; an unnamed volume is never taken for gone. */
    fun isGone(volume: String?): Boolean {
        val name = volume?.lowercase() ?: return false
        return name in unmounted || (mountedIsComplete && name !in mounted)
    }
}

/**
 * The rows of [mediaIds] that cannot be read because their storage is not there, given that the row that just failed
 * ([failedId]) is on such storage: every row on a volume [presence] knows to be gone. An error chain leaves these out
 * instead of trying them one by one, so pulling out a card ends the chain at once instead of failing through every
 * remaining row of the card. Empty when the failed row's own storage is there (or cannot be told): the file itself is
 * at fault then, and the next row is tried as before.
 */
internal fun rowsOnMissingStorage(
    mediaIds: List<String>,
    failedId: String,
    volumeOf: (String) -> String?,
    presence: StoragePresence?,
): Set<String> {
    if (presence == null || !presence.isGone(volumeOf(failedId))) return emptySet()
    return mediaIds.filterTo(HashSet()) { presence.isGone(volumeOf(it)) }
}

/**
 * What the system says about its storage volumes now, or null when it cannot say. From Android 10 a track's volume is
 * MediaStore's own name for it, and MediaStore lists exactly the mounted ones. Before that the name comes from the
 * file's path, which need not be how the system names the volume: only a volume listed as not mounted is told apart,
 * and a card that is physically out is not listed at all (the chain then runs its course and returns to its origin).
 */
internal fun storagePresence(context: Context): StoragePresence? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        StoragePresence(
            mounted = MediaStore.getExternalVolumeNames(context).mapTo(HashSet()) { it.lowercase() },
            unmounted = emptySet(),
            mountedIsComplete = true,
        )
    } else {
        val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
        val (present, absent) = volumes.partition {
            it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY
        }
        fun names(of: List<android.os.storage.StorageVolume>) =
            of.mapNotNullTo(HashSet()) { if (it.isPrimary) FolderTree.PRIMARY_VOLUME else it.uuid?.lowercase() }
        StoragePresence(mounted = names(present), unmounted = names(absent) - names(present), mountedIsComplete = false)
    }
} catch (_: RuntimeException) {
    null
}
