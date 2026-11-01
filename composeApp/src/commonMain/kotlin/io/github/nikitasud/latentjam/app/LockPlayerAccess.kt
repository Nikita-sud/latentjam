/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * Whether the lock-screen player can open when the screen turns off while another app is in front.
 * From Android 10 an app may start an activity from the background only with an exemption; the
 * one the listener can grant is "display over other apps". Without it the player still opens when
 * LatentJam itself is on screen at screen-off.
 */
internal enum class LockPlayerAccess {
    /** Nothing more to ask for (vendor ROMs may still gate it, see [lockPlayerNeedsVendorNote]). */
    Ready,

    /** Works only from inside LatentJam until the listener allows display over other apps. */
    NeedsOverlay,

    /** A Go phone: the access cannot be granted at all. */
    Unsupported,
}

/** Null while the setting is off: then nothing about access is shown. */
internal fun lockPlayerAccess(
    enabled: Boolean,
    sdkInt: Int,
    canDrawOverlays: Boolean,
    lowRamGo: Boolean,
): LockPlayerAccess? = when {
    !enabled -> null
    // The background-start limit arrived with Android 10.
    sdkInt < 29 -> LockPlayerAccess.Ready
    // Before the Go check: a Go phone that held the access before an upgrade keeps it.
    canDrawOverlays -> LockPlayerAccess.Ready
    lowRamGo -> LockPlayerAccess.Unsupported
    else -> LockPlayerAccess.NeedsOverlay
}

/** Makers whose ROMs add their own pop-up / lock-screen permissions that no public API reports. */
private val VENDOR_GATED = setOf(
    "xiaomi", "redmi", "poco", "honor", "huawei", "vivo", "iqoo", "oppo", "realme", "oneplus",
    "tecno", "infinix", "itel", "meizu",
)

internal fun lockPlayerNeedsVendorNote(manufacturer: String, brand: String): Boolean =
    listOf(manufacturer, brand).any { name ->
        val lower = name.lowercase()
        VENDOR_GATED.any { lower == it || lower.startsWith("$it ") }
    }

/**
 * The vendor line appears once the standard access is granted, so the listener is never shown
 * two notes at once, and it stays hidden after they said they are done.
 */
internal fun lockPlayerShowsVendorNote(access: LockPlayerAccess?, vendorGated: Boolean, hidden: Boolean): Boolean =
    access == LockPlayerAccess.Ready && vendorGated && !hidden

/** The one-time notice at launch, for listeners who turned the setting on before it asked. */
internal fun lockPlayerShouldNudge(access: LockPlayerAccess?, noticeShown: Boolean): Boolean =
    access == LockPlayerAccess.NeedsOverlay && !noticeShown

/** What this device says about [LockPlayerAccess], and the ways into its settings. */
internal class LockPlayerDevice(
    val access: LockPlayerAccess?,
    val vendorGated: Boolean,
    val openOverlaySettings: () -> Unit,
    val openAppDetails: () -> Unit,
)

/** Kept current while composed: the access is read again each time the app comes back on screen. */
@Composable
internal expect fun rememberLockPlayerDevice(enabled: Boolean): LockPlayerDevice
