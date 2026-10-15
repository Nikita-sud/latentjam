/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import android.content.ContentValues
import android.content.ContextWrapper
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.history.*
import io.github.nikitasud.latentjam.library.tags.Id3Tags
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real MediaStore IDs, files, playback locators and existing user stores; production app untouched. */
class TrackRelocationDeviceTest {
    @Test fun retaggedFileWithNewMediaStoreIdKeepsPlaylistFavoritesHistoryAndVisibility() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "identity-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val resolver = context.contentResolver
        val created = ArrayList<Uri>()
        val folder = "Music/LatentJamIdentityTest-${UUID.randomUUID()}/"
        val bytes = javaClass.getResourceAsStream("/identity-tone.mp3")!!.use { it.readBytes() }
        fun insert(name: String, artist: String, content: ByteArray): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.mp3")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$folder$name/")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.TITLE, "Identity fixture")
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.DURATION, 3000)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
            created += uri
            resolver.openOutputStream(uri)!!.use { it.write(content) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        try {
            var library = MediaStoreMusicLibrary(context)
            val beforeUri = insert("before", "Old artist", bytes)
            val before = library.allKnownTracks().single { it.audioUri?.endsWith("/${beforeUri.lastPathSegment}") == true }
            library.rememberTrackIdentities()
            val playlists = DefaultPlaylists(object : PlaylistStore {
                var saved = emptyList<String>()
                override suspend fun read() = saved
                override suspend fun write(lines: List<String>) { saved = lines }
            })
            val playlist = playlists.create("Keep me", listOf(before.id))
            val favorites = DefaultFavorites(object : FavoritesStore {
                var saved = emptyList<String>()
                override suspend fun read() = saved
                override suspend fun write(ids: List<String>) { saved = ids }
            })
            favorites.toggle(before.id)
            val history = DefaultListeningHistory(object : HistoryStore {
                val saved = ArrayList<String>()
                override suspend fun append(line: String) { saved += line }
                override suspend fun readAll() = saved.toList()
                override suspend fun clear() { saved.clear() }
            })
            history.record(ListenEvent(before.id, 1000, 3000, 3000, completed = true, skipped = false))
            library.hide(before.id)
            assertEquals(1, resolver.delete(beforeUri, null, null))
            created.remove(beforeUri)
            val retagged = Id3Tags.updateTag(bytes, TagEdits(artist = "Standardized artist"))!!
            val afterUri = insert("after", "Standardized artist", retagged)
            assertNotEquals(beforeUri.lastPathSegment, afterUri.lastPathSegment)

            // A new instance also verifies that no process-local alias is required.
            library = MediaStoreMusicLibrary(context)
            val after = library.allKnownTracks().single { it.audioUri?.endsWith("/${afterUri.lastPathSegment}") == true }
            assertEquals(before.id, after.id)
            assertNotEquals(before.audioUri, after.audioUri)
            assertNotEquals(before.sourceRevision, after.sourceRevision)
            assertTrue(library.scan().tracks.none { it.id == before.id })
            assertTrue(library.hiddenTracks().any { it.id == before.id && it.audioUri == after.audioUri })
            assertTrue(library.missingTrackIds(listOf(before.id)).isEmpty())
            assertTrue(library.filePaths(listOf(before.id)).getValue(before.id).endsWith("/after/after.mp3"))
            library.unhide(before.id)
            val visible = library.scan().tracks.associateBy { it.id }
            assertEquals(after.audioUri, visible[TrackId(playlists.all().single { it.id == playlist.id }.trackIds.single())]?.audioUri)
            assertTrue(favorites.contains(after.id))
            assertEquals(1, history.stats().getValue(after.id).plays)
            val stats = ListeningOverviews.summarize(history.allEvents(), { visible[it]?.artist }, null, 5000,
                timePointOf = { LocalTimePoint(0, 0) }, includeTrack = visible::containsKey)
            assertTrue(stats.topTracks.any { it.trackId == after.id && it.plays == 1 })
            assertTrue(stats.topArtists.isNotEmpty())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(after.audioUri), null)
                extractor.selectTrack(0)
                assertTrue(extractor.readSampleData(ByteBuffer.allocate(64 * 1024), 0) > 0)
            } finally { extractor.release() }
        } finally {
            created.forEach { resolver.delete(it, null, null) }
            directory.deleteRecursively()
        }
    }
}
