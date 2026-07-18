/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

/**
 * Unicode facts by explicit range.
 *
 * Foundation would answer these from its character sets, but `NSCharacterSet.characterIsMember`
 * works on UTF-16 units and the class properties are awkward to reach through cinterop, so the
 * ranges are spelled out instead. They cover the categories BERT tokenization actually
 * distinguishes across Latin, Cyrillic, Greek and CJK text.
 *
 * NOT verified against the reference tokenizer: SMART does not run on iOS yet, so nothing consumes
 * these. Before enabling it, run the tokenizer fixtures on an iOS test target — that suite is the
 * gate, and a mismatch here would silently shift text vectors rather than fail.
 */

internal actual fun nfdNormalize(text: String): String =
    NSString.create(string = text).decomposedStringWithCanonicalMapping

/** Unicode `Zs`. */
internal actual fun isSpaceSeparator(codePoint: Int): Boolean =
    codePoint == 0x00A0 || codePoint == 0x1680 || codePoint in 0x2000..0x200A ||
        codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000

/** Unicode `Cc`, `Cf`, `Cs`, `Co` — and the unassigned planes, which behave the same way here. */
internal actual fun isControlCategory(codePoint: Int): Boolean =
    codePoint in 0x0000..0x001F || codePoint in 0x007F..0x009F ||
        codePoint == 0x00AD || codePoint in 0x0600..0x0605 || codePoint == 0x061C ||
        codePoint in 0x200B..0x200F || codePoint in 0x2028..0x202E ||
        codePoint in 0x2060..0x2064 || codePoint in 0x2066..0x206F ||
        codePoint == 0xFEFF || codePoint in 0xFFF9..0xFFFB ||
        codePoint in 0xD800..0xDFFF || codePoint in 0xE000..0xF8FF ||
        codePoint in 0xF0000..0x10FFFF

/** Unicode `P*`. ASCII punctuation is handled by the caller. */
internal actual fun isPunctuationCategory(codePoint: Int): Boolean =
    codePoint == 0x00A1 || codePoint == 0x00A7 || codePoint == 0x00AB || codePoint == 0x00B6 ||
        codePoint == 0x00B7 || codePoint == 0x00BB || codePoint == 0x00BF ||
        codePoint in 0x2010..0x2027 || codePoint in 0x2030..0x205E ||
        codePoint in 0x2E00..0x2E7F || codePoint in 0x3001..0x3003 ||
        codePoint in 0x3008..0x3020 || codePoint == 0x3030 ||
        codePoint in 0xFE10..0xFE19 || codePoint in 0xFE30..0xFE6B ||
        codePoint in 0xFF01..0xFF03 || codePoint in 0xFF05..0xFF0A ||
        codePoint in 0xFF0C..0xFF0F || codePoint in 0xFF1A..0xFF1B ||
        codePoint in 0xFF1F..0xFF20 || codePoint in 0xFF3B..0xFF3D ||
        codePoint == 0xFF3F || codePoint == 0xFF5B || codePoint == 0xFF5D ||
        codePoint in 0xFF5F..0xFF65

/** Unicode `Mn` — the marks NFD peels off an accented letter. */
internal actual fun isNonSpacingMark(codePoint: Int): Boolean =
    codePoint in 0x0300..0x036F || codePoint in 0x0483..0x0489 ||
        codePoint in 0x0591..0x05BD || codePoint in 0x0610..0x061A ||
        codePoint in 0x064B..0x065F || codePoint in 0x0670..0x0670 ||
        codePoint in 0x0E31..0x0E3A || codePoint in 0x1AB0..0x1AFF ||
        codePoint in 0x1DC0..0x1DFF || codePoint in 0x20D0..0x20F0 ||
        codePoint in 0x302A..0x302D || codePoint in 0xFE20..0xFE2F
