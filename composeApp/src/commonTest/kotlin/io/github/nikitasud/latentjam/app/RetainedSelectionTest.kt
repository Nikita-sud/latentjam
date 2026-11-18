/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RetainedSelectionTest {

    @Test fun `a new screen starts without the selection an older screen left behind`() {
        assertEquals(RetainedStart.CLEAR, retainedSelectionAtStart(heldWhenSaved = false, holdsNow = true))
        assertEquals(RetainedStart.CLEAR, retainedSelectionAtStart(heldWhenSaved = false, holdsNow = false))
    }

    @Test fun `a recreated screen takes its selection back while the process lives`() {
        assertEquals(RetainedStart.KEEP, retainedSelectionAtStart(heldWhenSaved = true, holdsNow = true))
    }

    @Test fun `after a restarted process the selection is lost and its dialog is dropped`() {
        assertEquals(RetainedStart.LOST, retainedSelectionAtStart(heldWhenSaved = true, holdsNow = false))
    }
}
