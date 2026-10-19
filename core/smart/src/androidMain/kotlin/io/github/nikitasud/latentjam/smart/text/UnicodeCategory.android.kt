/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

internal actual fun isNonspacingMark(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()

internal actual fun isLetterNumberOrMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
    Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(),
    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
    Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
    -> true
    else -> false
}
