/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockPlayerAccessTest {

    @Test
    fun `nothing is shown while the setting is off`() {
        for (sdk in listOf(24, 28, 29, 36)) {
            for (canDraw in listOf(false, true)) {
                for (go in listOf(false, true)) {
                    assertNull(lockPlayerAccess(enabled = false, sdkInt = sdk, canDrawOverlays = canDraw, lowRamGo = go))
                }
            }
        }
    }

    @Test
    fun `before Android 10 there is no background start limit and nothing is needed`() {
        for (sdk in 24..28) {
            assertEquals(
                LockPlayerAccess.Ready,
                lockPlayerAccess(enabled = true, sdkInt = sdk, canDrawOverlays = false, lowRamGo = false),
            )
        }
    }

    @Test
    fun `from Android 10 the overlay access is what makes it ready`() {
        for (sdk in listOf(29, 30, 36, 37)) {
            assertEquals(
                LockPlayerAccess.NeedsOverlay,
                lockPlayerAccess(enabled = true, sdkInt = sdk, canDrawOverlays = false, lowRamGo = false),
            )
            assertEquals(
                LockPlayerAccess.Ready,
                lockPlayerAccess(enabled = true, sdkInt = sdk, canDrawOverlays = true, lowRamGo = false),
            )
        }
    }

    @Test
    fun `a Go phone cannot grant it unless it already holds it`() {
        assertEquals(
            LockPlayerAccess.Unsupported,
            lockPlayerAccess(enabled = true, sdkInt = 30, canDrawOverlays = false, lowRamGo = true),
        )
        assertEquals(
            LockPlayerAccess.Ready,
            lockPlayerAccess(enabled = true, sdkInt = 30, canDrawOverlays = true, lowRamGo = true),
        )
    }

    @Test
    fun `vendor ROMs with their own pop-up and lock-screen gates are recognised by maker or brand`() {
        val gated = listOf(
            "Xiaomi" to "POCO", "Xiaomi" to "Redmi", "HUAWEI" to "HONOR", "HONOR" to "HONOR",
            "realme" to "realme", "vivo" to "iQOO", "OPPO" to "OPPO", "OnePlus" to "OnePlus",
            "TECNO MOBILE LIMITED" to "TECNO", "INFINIX MOBILITY LIMITED" to "Infinix", "itel" to "itel",
            "Meizu" to "meizu", "unknown" to "huawei",
        )
        for ((maker, brand) in gated) assertTrue(lockPlayerNeedsVendorNote(maker, brand), "$maker / $brand")
        val plain = listOf("Google" to "google", "samsung" to "samsung", "motorola" to "motorola", "" to "")
        for ((maker, brand) in plain) assertFalse(lockPlayerNeedsVendorNote(maker, brand), "$maker / $brand")
    }

    @Test
    fun `the vendor note waits until the overlay access is granted and can be hidden`() {
        assertTrue(lockPlayerShowsVendorNote(LockPlayerAccess.Ready, vendorGated = true, hidden = false))
        assertFalse(lockPlayerShowsVendorNote(LockPlayerAccess.Ready, vendorGated = true, hidden = true))
        assertFalse(lockPlayerShowsVendorNote(LockPlayerAccess.Ready, vendorGated = false, hidden = false))
        assertFalse(lockPlayerShowsVendorNote(LockPlayerAccess.NeedsOverlay, vendorGated = true, hidden = false))
        assertFalse(lockPlayerShowsVendorNote(LockPlayerAccess.Unsupported, vendorGated = true, hidden = false))
        assertFalse(lockPlayerShowsVendorNote(null, vendorGated = true, hidden = false))
    }

    @Test
    fun `the launch notice comes once and only when access is missing`() {
        assertTrue(lockPlayerShouldNudge(LockPlayerAccess.NeedsOverlay, noticeShown = false))
        assertFalse(lockPlayerShouldNudge(LockPlayerAccess.NeedsOverlay, noticeShown = true))
        for (access in listOf(LockPlayerAccess.Ready, LockPlayerAccess.Unsupported, null)) {
            assertFalse(lockPlayerShouldNudge(access, noticeShown = false), "$access")
        }
    }
}
