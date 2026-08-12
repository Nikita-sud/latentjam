/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextUnicodeCategoryTest {

    @Test
    fun `recognizes full Unicode categories used by the reference tokenizer`() {
        assertTrue(isSpaceSeparator(0x2007)) // FIGURE SPACE, Zs
        assertFalse(isSpaceSeparator(0x2028)) // LINE SEPARATOR, Zl

        assertTrue(isControlCategory(0x0001)) // Cc
        assertTrue(isControlCategory(0x061C)) // Cf
        assertTrue(isControlCategory(0xD800)) // Cs
        assertTrue(isControlCategory(0xE000)) // Co
        assertFalse(isControlCategory('A'.code))

        assertTrue(isPunctuationCategory(0x055A)) // ARMENIAN APOSTROPHE, Po
        assertTrue(isPunctuationCategory(0x10100)) // AEGEAN WORD SEPARATOR LINE, Po
        assertFalse(isPunctuationCategory(0x00A9)) // COPYRIGHT SIGN, So

        assertTrue(isNonSpacingMark(0x05BF)) // HEBREW POINT RAFE, Mn
        assertTrue(isNonSpacingMark(0x1D167)) // MUSICAL SYMBOL COMBINING TREMOLO-1, Mn
        assertFalse(isNonSpacingMark(0x0903)) // DEVANAGARI SIGN VISARGA, Mc
        assertFalse(isNonSpacingMark(0x20DD)) // COMBINING ENCLOSING CIRCLE, Me
    }

    @Test
    fun `normalization and category decisions affect tokens like the reference`() {
        assertEquals("e\u0301", nfdNormalize("\u00E9"))

        val tokens = listOf("[UNK]", "[CLS]", "[SEP]", "a", "\u055A", "b")
        val tokenizer = BertWordPieceTokenizer(
            vocab = tokens.withIndex().associate { (index, token) -> token to index },
        )

        assertEquals(
            listOf(1, 3, 4, 5, 2),
            tokenizer.encode("a\u055Ab").toList(),
            "Armenian punctuation must split into its own token",
        )
        assertEquals(
            listOf(1, 3, 2),
            tokenizer.encode("a\u05BF").toList(),
            "Hebrew Mn marks must be stripped",
        )
    }
}
