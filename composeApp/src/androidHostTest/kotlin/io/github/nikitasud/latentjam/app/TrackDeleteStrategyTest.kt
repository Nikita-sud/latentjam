/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The version boundaries, pinned. Each one is a different Android deletion mechanism, and getting
 * a boundary wrong reintroduces exactly the bug this replaced: a delete that does nothing at all.
 */
class TrackDeleteStrategyTest {

    @Test
    fun `Android 11 and newer hand the whole batch to the system`() {
        assertEquals(
            TrackDeleteStrategy.SYSTEM_DELETE_REQUEST,
            trackDeleteStrategy(Build.VERSION_CODES.R),
        )
        assertEquals(TrackDeleteStrategy.SYSTEM_DELETE_REQUEST, trackDeleteStrategy(36))
    }

    @Test
    fun `Android 10 asks per file through a recoverable refusal`() {
        assertEquals(
            TrackDeleteStrategy.RECOVERABLE_CONSENT,
            trackDeleteStrategy(Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun `Android 9 and older have only the storage permission`() {
        assertEquals(
            TrackDeleteStrategy.WRITE_PERMISSION,
            trackDeleteStrategy(Build.VERSION_CODES.P),
        )
        // minSdk: the oldest system that can install LatentJam at all.
        assertEquals(TrackDeleteStrategy.WRITE_PERMISSION, trackDeleteStrategy(24))
    }
}
