/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.text.Normalizer

public actual fun normalizeUnicode(value: String, form: UnicodeForm): String = Normalizer.normalize(
    value,
    when (form) {
        UnicodeForm.NFC -> Normalizer.Form.NFC
        UnicodeForm.NFD -> Normalizer.Form.NFD
        UnicodeForm.NFKC -> Normalizer.Form.NFKC
        UnicodeForm.NFKD -> Normalizer.Form.NFKD
    },
)
