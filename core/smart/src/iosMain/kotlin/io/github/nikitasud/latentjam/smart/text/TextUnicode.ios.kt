/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.nikitasud.latentjam.smart.text

import platform.Foundation.NSMakeRange
import platform.Foundation.NSRegularExpression
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.firstMatchInString

/**
 * Unicode facts from Foundation's ICU-backed regular-expression engine.
 *
 * `NSCharacterSet.nonBaseCharacterSet`, for example, contains `Mc` and `Me` in addition to `Mn`,
 * while BERT strips only `Mn`. ICU general-category properties preserve that distinction and work
 * for supplementary-plane scalars as well as BMP characters.
 */

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfdNormalize(text: String): String =
    (text as NSString).decomposedStringWithCanonicalMapping

internal actual fun isSpaceSeparator(codePoint: Int): Boolean = ZS.matches(codePoint)

internal actual fun isControlCategory(codePoint: Int): Boolean = CONTROL.matches(codePoint)

internal actual fun isPunctuationCategory(codePoint: Int): Boolean = PUNCTUATION.matches(codePoint)

internal actual fun isNonSpacingMark(codePoint: Int): Boolean = NON_SPACING_MARK.matches(codePoint)

private val ZS = unicodeCategory("Zs")
private val CONTROL = unicodeCategory("C")
private val PUNCTUATION = unicodeCategory("P")
private val NON_SPACING_MARK = unicodeCategory("Mn")

private fun unicodeCategory(category: String): NSRegularExpression =
    NSRegularExpression(pattern = "\\A\\p{$category}\\z", options = 0uL, error = null)

private fun NSRegularExpression.matches(codePoint: Int): Boolean {
    if (codePoint !in 0..0x10FFFF) return false
    val scalar = codePointToString(codePoint)
    return firstMatchInString(
        string = scalar,
        options = 0uL,
        range = NSMakeRange(0uL, scalar.length.toULong()),
    ) != null
}

private fun codePointToString(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val value = codePoint - 0x10000
        charArrayOf(
            (0xD800 + (value shr 10)).toChar(),
            (0xDC00 + (value and 0x3FF)).toChar(),
        ).concatToString()
    }
