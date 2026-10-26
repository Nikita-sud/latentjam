/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/** Whether this is a debuggable build (Android) or a debug binary (iOS); product-check tools show only there. */
internal expect fun isDebuggableBuild(): Boolean
