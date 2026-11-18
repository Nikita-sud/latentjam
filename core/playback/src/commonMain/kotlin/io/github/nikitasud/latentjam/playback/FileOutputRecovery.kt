/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/*
 * The rules behind getting the iOS file player going again after the system stopped its audio graph: a change of
 * route or of the output's format stops AVAudioEngine, and nothing but the app restarts it. Pure, so the host tests
 * hold every row; IosPlaybackController only gathers the facts and carries the answers out.
 */

/** One output of the audio route: its id, and whether it is the phone's own speaker or earpiece. */
internal data class OutputPort(val uid: String, val builtIn: Boolean)

/**
 * Whether an output the music was playing through, other than the phone's own, is gone from the route: headphones
 * unplugged, a Bluetooth speaker switched off, AirPlay ended. Music must not carry on from the phone's speaker then;
 * it pauses, as the route-change rule for a device that went away already says. [before] is the route playback was
 * last known to sound on (empty when unknown: nothing is known to be lost), [now] the route at this moment.
 */
internal fun privateOutputLost(before: Set<OutputPort>, now: Set<OutputPort>): Boolean {
    // No route at all is nothing to play through either.
    if (now.isEmpty()) return before.isNotEmpty()
    val present = now.mapTo(HashSet()) { it.uid }
    return before.any { !it.builtIn && it.uid !in present }
}

/** What the periodic look at the file player finds. */
internal enum class FileOutputCheck {
    /** As it should be, or a recovery already under way decides. */
    NOTHING,

    /** The system stopped the graph under a playing track: re-cue and start again. */
    RECOVER,

    /** Not playing and not to be restarted: say paused. */
    PAUSE,
}

/**
 * What to make of the file player while the app means to play ([playing]). [nodePlaying] is the player node's own
 * flag, [engineRunning] the engine's. A stopped engine is the system's doing (this app pauses the node first, and
 * then no longer means to play), so it is restarted rather than reported as a pause, unless [recoveryAllowed] says
 * that was tried too often just now. [recoveryPending]: a recovery is between its re-cue and its restart.
 */
internal fun fileOutputCheck(
    playing: Boolean,
    nodePlaying: Boolean,
    engineRunning: Boolean,
    recoveryPending: Boolean,
    recoveryAllowed: Boolean,
): FileOutputCheck = when {
    !playing || recoveryPending -> FileOutputCheck.NOTHING
    nodePlaying && engineRunning -> FileOutputCheck.NOTHING
    !engineRunning -> if (recoveryAllowed) FileOutputCheck.RECOVER else FileOutputCheck.PAUSE
    else -> FileOutputCheck.PAUSE
}

/** What a recovery does once the route has had its moment to settle. */
internal enum class OutputResume {
    /** Start the re-cued track again. */
    RESUME,

    /** Nothing: it was paused meanwhile, or another item was loaded and owns the output. */
    LEAVE,

    /** The app still means to play, and must not or cannot: pause, position kept. */
    PAUSE,
}

/**
 * Whether the track re-cued after a stop of the graph starts again. Never over a pause that came meanwhile (the
 * listener's, an interruption's, a device that went away), never for an item loaded since ([sameItem]), never into
 * another app's sound ([otherAudioPlaying]) or an output with no usable format ([outputReady]).
 */
internal fun resumeAfterOutputChange(
    playing: Boolean,
    sameItem: Boolean,
    fileBackend: Boolean,
    interrupted: Boolean,
    otherAudioPlaying: Boolean,
    outputReady: Boolean,
    privateOutputLost: Boolean,
): OutputResume = when {
    !playing || !sameItem || !fileBackend -> OutputResume.LEAVE
    interrupted || otherAudioPlaying || privateOutputLost || !outputReady -> OutputResume.PAUSE
    else -> OutputResume.RESUME
}

/**
 * Whether one more recovery may be tried at [nowMs], given when the last ones were ([recentMs], same clock): an output
 * that stops again as often as it is restarted is left paused instead of being restarted for ever.
 */
internal fun outputRecoveryAllowed(recentMs: List<Long>, nowMs: Long): Boolean =
    recentMs.count { nowMs - it in 0 until OUTPUT_RECOVERY_WINDOW_MS } < OUTPUT_RECOVERY_MAX

internal const val OUTPUT_RECOVERY_WINDOW_MS: Long = 10_000L
internal const val OUTPUT_RECOVERY_MAX: Int = 3

/** How long a re-cued track waits before it starts again: a pause for a device that went away, or an interruption, lands first. */
internal const val OUTPUT_RECOVERY_SETTLE_MS: Long = 150L
