/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class TransportEventThrottleTest {

    @Test
    fun aBurstOfFourPassesAndThenOnlyOneLinePerSecond() {
        val throttle = TransportEventThrottle()

        // A Bluetooth tap is a burst, not a stream: the connect line, the key line and the state
        // changes it caused all fit at once.
        assertEquals(4, (0 until 4).count { throttle.admit(HEADSET, "burst-$it", 0L) != null })
        assertNull(throttle.admit(HEADSET, "burst-4", 0L))

        // After the burst the same source earns one line a second, and no more.
        assertNull(throttle.admit(HEADSET, "at-900ms", 900L))
        assertNotNull(throttle.admit(HEADSET, "at-1s", 1_000L))
        assertNull(throttle.admit(HEADSET, "at-1.9s", 1_900L))
        assertNotNull(throttle.admit(HEADSET, "at-2s", 2_000L))
    }

    @Test
    fun suppressedLinesAreCountedAndTheNextAdmittedLineReportsThem() {
        val throttle = TransportEventThrottle()
        repeat(4) { assertNotNull(throttle.admit(FLOODER, "key=85", 0L)) }

        // A flood still reads as a flood: the lines a spent bucket swallowed are summarised by the
        // next one that gets through.
        repeat(3) { assertNull(throttle.admit(FLOODER, "key=85", 0L)) }
        assertEquals("key=85 (+3 suppressed)", throttle.admit(FLOODER, "key=85", 1_000L))

        // The count is spent with the line that carried it.
        assertNull(throttle.admit(FLOODER, "key=85", 1_000L))
        assertEquals("key=85 (+1 suppressed)", throttle.admit(FLOODER, "key=85", 2_000L))
    }

    @Test
    fun oneSourceCannotSpendAnotherSourcesBurst() {
        val throttle = TransportEventThrottle()
        repeat(4) { assertNotNull(throttle.admit(HEADSET, "key=85", 0L)) }
        assertNull(throttle.admit(HEADSET, "key=85", 0L))

        // A second app on the same transport surface has a bucket of its own at the same instant.
        repeat(4) { assertNotNull(throttle.admit(CAR, "connect", 0L)) }
        assertNull(throttle.admit(CAR, "connect", 0L))
        assertNull(throttle.admit(HEADSET, "key=85", 0L))

        // Each earns its own refill too: a second of silence buys one line for each of them.
        assertNotNull(throttle.admit(HEADSET, "key=85", 1_000L))
        assertNotNull(throttle.admit(CAR, "connect", 1_000L))
    }

    @Test
    fun walkingNamesPastTheTableNeverBuysTheFlooderAFreshBurst() {
        val throttle = TransportEventThrottle()
        repeat(4) { assertNotNull(throttle.admit(FLOODER, "key=85", 0L)) }
        assertNull(throttle.admit(FLOODER, "key=85", 0L))

        // More names than the bucket table holds, one per millisecond. The flooder asks for a line
        // after each of them, and an attempt stamps it as recently seen even while it is refused —
        // so the eviction every further name triggers takes an idle bucket, never the flooder's.
        var nowMs = 1L
        repeat(64) { index ->
            assertNotNull(throttle.admit("source-$index", "connect", nowMs))
            assertNull(throttle.admit(FLOODER, "key=85", nowMs))
            nowMs++
        }

        // Walking those names past the table bought the flooder nothing: it still waits out the
        // refill that began when its burst ran out, like a source nobody displaced.
        assertNull(throttle.admit(FLOODER, "key=85", nowMs))
        assertNotNull(throttle.admit(FLOODER, "key=85", nowMs + 1_000L))
    }

    @Test
    fun aFullTableEvictsTheNameThatHasBeenQuietLongest() {
        val throttle = TransportEventThrottle()
        // QUIET spends its burst and is never heard from again; BUSY spends its own a moment later
        // and keeps asking, which makes it the more recently seen of the two.
        repeat(4) { assertNotNull(throttle.admit(QUIET, "key=85", 0L)) }
        repeat(4) { assertNotNull(throttle.admit(BUSY, "key=85", 1L)) }
        assertNull(throttle.admit(BUSY, "key=85", 2L))

        // The bucket table holds thirty-two sources; the rest of it fills up here.
        repeat(30) { index ->
            assertNotNull(throttle.admit("source-$index", "connect", 100L + index))
        }

        // A further name has to make room, and the entry it drops is the one that has gone longest
        // without a line. BUSY, which asked a moment ago, is not it: it keeps its empty bucket...
        assertNotNull(throttle.admit("newcomer", "connect", 200L))
        assertNull(throttle.admit(BUSY, "key=85", 200L))

        // ...while QUIET, the stalest entry, was the one evicted, so it comes back with a burst.
        repeat(4) { assertNotNull(throttle.admit(QUIET, "key=85", 200L)) }
        assertNull(throttle.admit(QUIET, "key=85", 200L))
    }

    private companion object {
        /** A headset dispatcher, the source a Bluetooth tap reaches the session as. */
        const val HEADSET = "com.android.bluetooth"

        /** A second app on the session's transport surface. */
        const val CAR = "com.google.android.projection.gearhead"

        /** A source stuck in a loop, which the bucket exists to keep out of the log. */
        const val FLOODER = "com.example.stuck"

        /** A source that spent its burst once and then went silent. */
        const val QUIET = "com.example.quiet"

        /** A source that spent its burst and keeps asking for more. */
        const val BUSY = "com.example.busy"
    }
}
