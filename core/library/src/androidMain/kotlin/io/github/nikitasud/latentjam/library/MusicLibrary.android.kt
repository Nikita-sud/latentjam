/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * [MusicLibrary] backed by Android's [MediaStore].
 *
 * Uses the system media index (fast, no file walking, respects `.nomedia`).
 * Each track's [TrackDescriptor.audioUri] is a `content://` URI the future
 * embedding backend can open for decoding. Genre is left `null` for now:
 * MediaStore models genres as a separate join table and the per-track lookup
 * is costly — it lands together with the real indexing pipeline.
 *
 * Requires `READ_MEDIA_AUDIO` (API 33+) or `READ_EXTERNAL_STORAGE` (≤32);
 * without the grant the query yields no rows and this returns an empty list.
 */
internal class MediaStoreMusicLibrary(
    private val context: Context,
) : MusicLibrary {

    override suspend fun tracks(): List<TrackDescriptor> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<TrackDescriptor>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                tracks += TrackDescriptor(
                    id = TrackId(id.toString()),
                    title = cursor.getString(titleColumn).knownOrNull(),
                    artist = cursor.getString(artistColumn).knownOrNull(),
                    album = cursor.getString(albumColumn).knownOrNull(),
                    durationMs = cursor.getLong(durationColumn).takeIf { it > 0 },
                    audioUri = ContentUris
                        .withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        .toString(),
                )
            }
        }
        tracks
    }

    /** MediaStore reports missing tags as the literal string "<unknown>". */
    private fun String?.knownOrNull(): String? =
        this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
}

public actual fun musicLibraryModule(): Module = module {
    single<MusicLibrary> { MediaStoreMusicLibrary(context = get()) }
}
