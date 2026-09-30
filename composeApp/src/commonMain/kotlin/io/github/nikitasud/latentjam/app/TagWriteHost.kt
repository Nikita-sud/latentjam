/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * The one place tag saves meet the user: it launches consent prompts, hands finished saves to
 * whichever editor asked for them, and offers to finish saves a crash interrupted. Placed once, in
 * [App] — two hosts would each launch the same prompt.
 */
@Composable
expect fun TagWriteHost()

/** Offers to finish the saves a crash interrupted. */
@Composable
internal fun <C> TagRecoveryPrompt(coordinator: TagWriteCoordinator<C>) = Unit
