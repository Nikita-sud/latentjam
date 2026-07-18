/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * The five Unicode facts BERT tokenization needs, as a platform seam.
 *
 * Kotlin common has no character-category API, and tokenization has to agree with the reference
 * byte for byte — a single misclassified codepoint changes the token ids, which changes the text
 * vector, which changes retrieval. Keeping the seam this small means the tokenizer itself stays
 * common and testable.
 */

/** Canonical decomposition (NFD), so combining marks become separate codepoints. */
internal expect fun nfdNormalize(text: String): String

/** True for the Unicode `Zs` category. ASCII whitespace is handled by the caller. */
internal expect fun isSpaceSeparator(codePoint: Int): Boolean

/** True for `Cc`, `Cf`, `Cs`, `Co` and `Cn` — what transformers drops as control characters. */
internal expect fun isControlCategory(codePoint: Int): Boolean

/** True for any `P*` category. ASCII punctuation is handled by the caller. */
internal expect fun isPunctuationCategory(codePoint: Int): Boolean

/** True for the `Mn` category — the marks NFD peels off an accented letter. */
internal expect fun isNonSpacingMark(codePoint: Int): Boolean
