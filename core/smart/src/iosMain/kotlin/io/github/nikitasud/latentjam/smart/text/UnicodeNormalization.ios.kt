/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
public actual fun normalizeUnicode(value: String, form: UnicodeForm): String {
    val s = value as NSString
    return when (form) {
        UnicodeForm.NFC -> s.precomposedStringWithCanonicalMapping
        UnicodeForm.NFD -> s.decomposedStringWithCanonicalMapping
        UnicodeForm.NFKC -> s.precomposedStringWithCompatibilityMapping
        UnicodeForm.NFKD -> s.decomposedStringWithCompatibilityMapping
    }
}
