/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A reboot drops queued work (no RECEIVE_BOOT_COMPLETED); the next process start must enqueue the rebuild again. */
class MapRebuildResumeTest {
    private suspend fun resume(layout: Pair<Int, Boolean>?, identityPending: Int, enabled: Boolean = true): Int {
        var scheduled = 0
        MapRebuild.resumePending(
            nowMs = 1_000L,
            enabled = enabled,
            readLayout = { layout },
            readIdentityPending = { identityPending },
            schedule = { scheduled++ },
        )
        return scheduled
    }

    @Test fun `a refreshed map owed a rebuild is enqueued again at start`() = runTest {
        assertEquals(1, resume(layout = 120 to false, identityPending = 0))
    }

    @Test fun `a stale map is enqueued again and keeps its marker`() = runTest {
        assertEquals(1, resume(layout = 0 to true, identityPending = 0))
        assertTrue(MapRebuild.stale.value)
        MapRebuild.builtInPage()
    }

    @Test fun `regions alone with pending tracks are enqueued again`() = runTest {
        assertEquals(1, resume(layout = null, identityPending = 7))
    }

    @Test fun `nothing owed or the flag off enqueues nothing`() = runTest {
        assertEquals(0, resume(layout = 0 to false, identityPending = 0))
        assertEquals(0, resume(layout = null, identityPending = 0))
        assertEquals(0, resume(layout = 120 to true, identityPending = 3, enabled = false))
    }

    @Test fun `an unreadable state is treated as nothing owed`() = runTest {
        var scheduled = 0
        val asked = MapRebuild.resumePending(
            nowMs = 1_000L,
            enabled = true,
            readLayout = { error("corrupt") },
            readIdentityPending = { error("corrupt") },
            schedule = { scheduled++ },
        )
        assertFalse(asked)
        assertEquals(0, scheduled)
    }
}
