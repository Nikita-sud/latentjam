/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import platform.Foundation.NSCharacterSet

// The BMP by Kotlin's own tables; supplementary planes (CJK extensions, historic scripts) by Foundation's sets, which
// group the categories slightly differently (a documented parity exception of the artist-set port).
internal actual fun isNonspacingMark(codePoint: Int): Boolean =
    if (codePoint <= 0xFFFF) codePoint.toChar().category == CharCategory.NON_SPACING_MARK
    else NSCharacterSet.nonBaseCharacterSet.longCharacterIsMember(codePoint.toUInt())

internal actual fun isLetterNumberOrMark(codePoint: Int): Boolean =
    if (codePoint <= 0xFFFF) {
        codePoint.toChar().category.code.let { it.startsWith("L") || it.startsWith("N") || it.startsWith("M") }
    } else {
        NSCharacterSet.alphanumericCharacterSet.longCharacterIsMember(codePoint.toUInt())
    }
