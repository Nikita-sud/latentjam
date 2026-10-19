/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/** A count with the current locale's digit grouping ("5,000", "5 000", "5.000"). */
internal expect fun formatCount(value: Int): String

/** A whole percentage as the current locale writes one ("85%", "85 %", "%85"). */
internal expect fun formatPercent(value: Int): String
