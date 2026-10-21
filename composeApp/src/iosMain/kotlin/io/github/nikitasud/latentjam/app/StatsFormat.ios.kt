/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterPercentStyle
import platform.Foundation.currentLocale

internal actual fun formatCount(value: Int): String = NSNumberFormatter().run {
    numberStyle = NSNumberFormatterDecimalStyle
    locale = NSLocale.currentLocale
    maximumFractionDigits = 0u
    stringFromNumber(NSNumber(int = value)) ?: value.toString()
}

internal actual fun formatPercent(value: Int): String = NSNumberFormatter().run {
    numberStyle = NSNumberFormatterPercentStyle
    locale = NSLocale.currentLocale
    maximumFractionDigits = 0u
    stringFromNumber(NSNumber(double = value / 100.0)) ?: "$value%"
}
