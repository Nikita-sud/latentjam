/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.text.NumberFormat

internal actual fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value.toLong())

internal actual fun formatPercent(value: Int): String = NumberFormat.getPercentInstance().format(value / 100.0)
