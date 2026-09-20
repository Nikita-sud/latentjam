/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_output_route
import io.github.nikitasud.latentjam.app.generated.resources.output_bluetooth
import io.github.nikitasud.latentjam.app.generated.resources.output_headphones
import io.github.nikitasud.latentjam.app.generated.resources.output_phone_speaker
import org.jetbrains.compose.resources.stringResource

/** Where the sound is going right now. */
internal enum class AudioOutputKind { SPEAKER, WIRED, BLUETOOTH, OTHER }

/** [name] is the device's own name when known; named kinds also have a localized fallback. */
internal data class AudioOutputRoute(val kind: AudioOutputKind, val name: String?)

/** The current output, kept current as devices come and go; null where the platform cannot say. */
@Composable
internal expect fun rememberAudioOutputRoute(): AudioOutputRoute?

/** Opens the platform's own place for choosing an output, or null where there is none to open. */
@Composable
internal expect fun rememberAudioOutputChooser(): (() -> Unit)?

/**
 * The output as a status in the player's top bar: an icon and the device's name.
 *
 * A tap opens the platform's chooser where one exists; otherwise the status simply states the
 * fact, which is most of its value — knowing the sound will come out of the speaker before the
 * first note does.
 */
@Composable
internal fun AudioOutputStatus(route: AudioOutputRoute, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val label = route.name ?: stringResource(
        when (route.kind) {
            AudioOutputKind.SPEAKER -> Res.string.output_phone_speaker
            AudioOutputKind.WIRED -> Res.string.output_headphones
            AudioOutputKind.BLUETOOTH -> Res.string.output_bluetooth
            AudioOutputKind.OTHER -> return
        },
    )
    val description = if (onOpen != null) stringResource(Res.string.cd_output_route, label) else label
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (onOpen != null) {
                    Modifier.clickable(role = Role.Button) {
                        haptics.play(PlayerHaptic.TAP)
                        onOpen()
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description }
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = when (route.kind) {
                AudioOutputKind.SPEAKER -> Icons.Rounded.Speaker
                AudioOutputKind.WIRED -> Icons.Rounded.Headphones
                AudioOutputKind.BLUETOOTH -> Icons.Rounded.Bluetooth
                AudioOutputKind.OTHER -> Icons.Rounded.VolumeUp
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
    }
}
