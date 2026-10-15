/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Uses the same controller/session boundary as the app, not a mocked position property. */
class RelocatedPlaybackPositionDeviceTest {
    @Test fun relocatingThePlayingItemKeepsItsPositionAndQueue() = withPlayback(playing = true) { fixture ->
        fixture.relocate(index = 1)
    }

    @Test fun relocatingThePausedItemKeepsItsPositionAndStaysPaused() = withPlayback(playing = false) { fixture ->
        fixture.relocate(index = 1)
    }

    @Test fun relocatingAnotherItemAndRefreshingTagsDoNotSeekTheCurrentItem() = withPlayback(playing = false) { fixture ->
        fixture.relocate(index = 2)
        fixture.refreshTags()
    }

    @Test fun actualSourceTakesPriorityOverAnAlreadyRefreshedLibraryDescriptor() = withPlayback(playing = false) { fixture ->
        fixture.relocate(index = 1, sourceDescriptorAlreadyRefreshed = true)
    }

    @Test fun duplicateTrackIdsPreserveTheCurrentOccurrence() =
        withPlayback(playing = false, ids = listOf("current", "current", "after")) { fixture ->
            fixture.relocate(index = 0)
            fixture.relocate(index = 1)
        }

    private fun withPlayback(playing: Boolean, ids: List<String> = listOf("before", "current", "after"), check: (Fixture) -> Unit) {
        val fixture = Fixture(playing, ids)
        try {
            fixture.start()
            check(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val playing: Boolean, private val ids: List<String>) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val directory = File(context.cacheDir, "relocated-playback-${System.nanoTime()}").apply { mkdirs() }
        private val original = File(directory, "original.wav").apply { writeSilence(this) }
        private val relocated = File(directory, "relocated.wav").apply { original.copyTo(this) }
        private val originalUri = original.toURI().toString()
        private val relocatedUri = relocated.toURI().toString()
        private var player: ExoPlayer? = null
        private var session: MediaSession? = null
        private var controller: MediaController? = null
        private var seeks = 0

        fun start() {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).setUri(originalUri).build() }, 1, 5_000L)
                    repeatMode = Player.REPEAT_MODE_ONE
                    shuffleModeEnabled = true
                    playWhenReady = playing
                    prepare()
                    addListener(object : Player.Listener {
                        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks++
                        }
                    })
                }
                session = MediaSession.Builder(context, player!!).setId("relocation-${System.nanoTime()}").build()
            }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync { future = MediaController.Builder(context, session!!.token).buildAsync() }
            controller = future.get(10, TimeUnit.SECONDS)
            await("initial playback") {
                player!!.playbackState == Player.STATE_READY && controller!!.playbackState == Player.STATE_READY &&
                    controller!!.currentMediaItemIndex == 1 && controller!!.currentPosition >= 4_900L
            }
        }

        fun relocate(index: Int, sourceDescriptorAlreadyRefreshed: Boolean = false) {
            var before = 0L
            var previousSeeks = 0
            instrumentation.runOnMainSync {
                before = controller!!.currentPosition
                previousSeeks = seeks
                // A direct player exposes the actual local configuration. A session's timeline
                // can omit it, so the other cases exercise the controller's descriptor fallback.
                val target: Player = if (sourceDescriptorAlreadyRefreshed) player!! else controller!!
                target.refreshTrackAt(index,
                    TrackDescriptor(TrackId(ids[index]), title = "Moved", audioUri = relocatedUri),
                    coverChanged = false, previousAudioUri = if (sourceDescriptorAlreadyRefreshed) relocatedUri else originalUri)
            }
            await("relocated source ready") {
                player!!.getMediaItemAt(index).localConfiguration!!.uri.toString() == relocatedUri &&
                    player!!.playbackState == Player.STATE_READY && controller!!.playbackState == Player.STATE_READY
            }
            instrumentation.runOnMainSync {
                assertPlayback(before)
                if (index != 1) assertEquals("Replacing another item must not seek", previousSeeks, seeks)
            }
        }

        fun refreshTags() {
            var before = 0L
            var previousSeeks = 0
            instrumentation.runOnMainSync {
                before = controller!!.currentPosition
                previousSeeks = seeks
                controller!!.refreshTrackAt(1,
                    TrackDescriptor(TrackId("current"), title = "Retagged", audioUri = originalUri),
                    coverChanged = false, previousAudioUri = originalUri)
            }
            await("updated tags") { player!!.currentMediaItem?.mediaMetadata?.title == "Retagged" }
            instrumentation.runOnMainSync {
                assertPlayback(before)
                assertEquals("Tag-only refresh must not seek", previousSeeks, seeks)
            }
        }

        private fun assertPlayback(before: Long) {
            val actual = player!!
            assertNull(actual.playerError)
            assertEquals("Current queue index", 1, actual.currentMediaItemIndex)
            assertEquals(ids, List(actual.mediaItemCount) { actual.getMediaItemAt(it).mediaId })
            assertEquals(playing, actual.playWhenReady)
            assertEquals(Player.REPEAT_MODE_ONE, actual.repeatMode)
            assertTrue(actual.shuffleModeEnabled)
            val tolerance = if (playing) 2_000L else 50L
            assertTrue("Position reset: before=$before after=${actual.currentPosition}",
                actual.currentPosition in (before - 100L)..(before + tolerance))
        }

        private fun await(description: String, ready: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            do {
                var done = false
                instrumentation.runOnMainSync {
                    assertNull("Playback failed while waiting for $description", player?.playerError)
                    done = ready()
                }
                if (done) return
                Thread.sleep(20)
            } while (System.nanoTime() < deadline)
            fail("Timed out waiting for $description")
        }

        fun close() {
            instrumentation.runOnMainSync {
                controller?.release()
                session?.release()
                player?.release()
            }
            directory.deleteRecursively()
        }
    }

    private companion object {
        fun writeSilence(file: File) {
            val sampleRate = 16_000
            val dataSize = sampleRate * 12 * 2
            val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            bytes.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            bytes.put("data".toByteArray()).putInt(dataSize)
            file.writeBytes(bytes.array())
        }
    }
}
