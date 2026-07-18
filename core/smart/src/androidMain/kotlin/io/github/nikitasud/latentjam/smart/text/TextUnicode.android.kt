/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.text.Normalizer

internal actual fun nfdNormalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)

internal actual fun isSpaceSeparator(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

internal actual fun isControlCategory(codePoint: Int): Boolean =
    when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        -> true
        else -> false
    }

internal actual fun isPunctuationCategory(codePoint: Int): Boolean =
    when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }

internal actual fun isNonSpacingMark(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()
