/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform back gesture while [enabled] — Android's back
 * button/gesture via androidx.activity; a no-op on iOS (its navigation idiom
 * is the down-chevron close affordance, which the UI always shows).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
