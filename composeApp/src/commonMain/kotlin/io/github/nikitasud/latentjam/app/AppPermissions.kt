/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module

/** User-visible state of an operating-system permission LatentJam may need. */
enum class AppPermissionStatus {
    /** This platform or app configuration does not need the permission. */
    NOT_REQUIRED,

    /** The operating system has not yet been asked. */
    NOT_DETERMINED,

    /** Access is currently available. */
    GRANTED,

    /** Access is unavailable; system settings are the durable recovery path. */
    DENIED,
}

/** Pure status projection shared by platform stores and covered independently of system APIs. */
internal fun permissionStatus(
    required: Boolean,
    granted: Boolean,
    requestResolved: Boolean,
): AppPermissionStatus = when {
    !required -> AppPermissionStatus.NOT_REQUIRED
    granted -> AppPermissionStatus.GRANTED
    requestResolved -> AppPermissionStatus.DENIED
    else -> AppPermissionStatus.NOT_DETERMINED
}

/**
 * Whether a library scan may be treated as the complete device library.
 *
 * An empty (or partial, on platforms that also expose app-owned files) result while access is
 * unavailable is not evidence that previously indexed tracks were deleted. Callers bind this
 * value to the result of each completed scan rather than consulting the live permission flow
 * later: a grant can arrive before the post-grant rescan and must not retroactively make the old
 * empty result authoritative.
 */
internal fun AppPermissionStatus.authorizesCompleteLibraryScan(): Boolean =
    this == AppPermissionStatus.GRANTED || this == AppPermissionStatus.NOT_REQUIRED

/** Both the operating-system grant and the concrete source query must support deletion pruning. */
internal fun authoritativeLibrarySnapshot(
    scanCompleted: Boolean,
    permissionStatus: AppPermissionStatus,
): Boolean = scanCompleted && permissionStatus.authorizesCompleteLibraryScan()

/**
 * Cross-platform permission state and recovery actions.
 *
 * Request launchers remain in the platform UI because Android requires an Activity. Keeping status
 * and settings navigation here lets shared Settings explain and recover from a denial without
 * knowing about Intents or UIKit.
 */
interface AppPermissions {
    val audioLibraryStatus: StateFlow<AppPermissionStatus>
    val notificationStatus: StateFlow<AppPermissionStatus>

    /** True only while a running SMART analysis has a relevant rationale to show. */
    val notificationPromptPending: StateFlow<Boolean>

    /** Re-read operating-system state after a permission dialog or return from system Settings. */
    fun refresh()

    /** Persist that Android has actually launched its audio permission dialog at least once. */
    fun markAudioPermissionRequested()

    /** Signals that current background analysis can benefit from progress notifications. */
    fun backgroundAnalysisNeedsNotifications()

    /** Records the user's response to our rationale so future scans do not nag them. */
    fun resolveNotificationPrompt()

    /** Clears a transient request when the analysis that caused it has stopped. */
    fun cancelNotificationPrompt()

    /** Opens this app's operating-system settings page. */
    fun openAppSettings()

    /** Opens the most specific notification settings page this platform provides. */
    fun openNotificationSettings()
}

/** Koin binding for the platform permission/status implementation. */
expect fun appPermissionsModule(): Module
