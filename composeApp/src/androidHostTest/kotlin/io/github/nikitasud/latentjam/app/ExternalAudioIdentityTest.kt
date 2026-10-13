/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ExternalAudioIdentityTest {
    private val primary = TrackDescriptor(
        id = TrackId("42"),
        audioUri = "content://media/external/audio/media/42",
        title = "A song",
        fileName = "A+B song.mp3",
        folderPath = "Music/Album",
        storageVolume = "external_primary",
    )
    private val card = primary.copy(
        id = TrackId("81"),
        audioUri = "content://media/external/audio/media/81",
        storageVolume = "1a2b-3c4d",
    )
    private val tracks = listOf(primary, card)

    @Test
    fun audioAndFilesCollectionsKeepTheCanonicalMediaStoreIdentity() {
        for (uri in listOf(
            primary.audioUri!!,
            "content://media/external_primary/audio/media/42",
            "content://media/external/file/42",
            "content://media/external_primary/file/42",
            "content://com.android.providers.media.documents/document/audio%3A42",
        )) assertEquals(primary, match(uri))
    }

    @Test
    fun aMediaIdOnAnotherVolumeNeverSelectsTheSameNumber() {
        assertNull(match("content://media/internal/audio/media/42"))
        assertNull(match("content://media/1a2b-3c4d/audio/media/42"))
        assertNull(match("content://com.android.providers.media.documents/document/video%3A42"))
        assertNull(match("content://media/external/images/media/42"))
    }

    @Test
    fun storageDocumentPathsDistinguishCopiesOnDifferentVolumesAndPreservePlusSigns() {
        assertEquals(primary, match(
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2FAlbum%2FA+B%20song.mp3",
        ))
        assertEquals(card, match(
            "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMusic/" +
                "document/1A2B-3C4D%3AMusic%2FAlbum%2FA%2BB%20song.mp3",
        ))
        assertNull(match(
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2FOther%2FA+B%20song.mp3",
        ))
    }

    @Test
    fun unknownProvidersAndUnknownFilesNeverGuessFromTheirNameOrFinalId() {
        assertNull(match("content://some.file.provider/music/A+B%20song.mp3"))
        assertNull(match("content://some.file.provider/audio/media/42"))
        assertNull(match("content://com.android.providers.downloads.documents/document/42"))
        assertNull(match("content://media/external/audio/media/99"))
    }

    @Test
    fun anExactVisibleUriIsAcceptedWithoutInterpretingAnOpaqueProviderId() {
        val source = "content://custom.library/document/opaque"
        val custom = primary.copy(audioUri = source)
        assertEquals(custom, matchExternalAudio(checkNotNull(externalAudioTarget(source)), listOf(custom)))
    }

    @Test
    fun hiddenAndAmbiguousRowsDoNotResolve() {
        val target = checkNotNull(externalAudioTarget(primary.audioUri!!))
        assertNull(matchExternalAudio(target, listOf(card)))
        assertNull(matchExternalAudio(target, listOf(primary, primary.copy(id = TrackId("duplicate")))))
    }

    @Test
    fun fileUrisAndRawDownloadsRequireAnExactCanonicalLibraryPath() {
        val paths = mapOf(primary.id to "/storage/emulated/0/Music/Album/A+B song.mp3")
        assertEquals(primary, matchExternalAudio(checkNotNull(externalAudioTarget(
            "file:///storage/emulated/0/Music/Album/./A+B%20song.mp3",
        )), tracks, paths))
        assertEquals(primary, matchExternalAudio(checkNotNull(externalAudioTarget(
            "content://com.android.providers.downloads.documents/" +
                "document/raw%3A%2Fstorage%2Femulated%2F0%2FMusic%2FAlbum%2FA+B%20song.mp3",
        )), tracks, paths))
        // Another Android user's primary volume must not be confused with this user's file.
        assertNull(matchExternalAudio(checkNotNull(externalAudioTarget(
            "file:///storage/emulated/10/Music/Album/A+B%20song.mp3",
        )), tracks, paths))
    }

    @Test
    fun networkAndMalformedPathsAreNeverLocalAudioTargets() {
        for (uri in listOf(
            "https://example.com/song.mp3", "http://example.com/song.mp3",
            "file://remote/Music/song.mp3", "file:relative.mp3", "broken uri %",
        )) assertNull(externalAudioTarget(uri))
        assertNull(match(
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2F..%2FAlbum%2FA+B%20song.mp3",
        ))
    }

    private fun match(uri: String): TrackDescriptor? = matchExternalAudio(checkNotNull(externalAudioTarget(uri)), tracks)
}
