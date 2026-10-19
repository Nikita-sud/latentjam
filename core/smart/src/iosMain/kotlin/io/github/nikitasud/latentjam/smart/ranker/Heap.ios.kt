/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

// iOS has no managed-heap ceiling of the Android kind; the process memory limit is not exposed as a number.
public actual fun heapHeadroomBytes(): Long = Long.MAX_VALUE
