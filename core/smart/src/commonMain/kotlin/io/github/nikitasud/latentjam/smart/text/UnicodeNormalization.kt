/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/** Unicode normalisation forms (UAX #15). */
public enum class UnicodeForm { NFC, NFD, NFKC, NFKD }

/** [value] in [form], by the platform's Unicode tables (java.text.Normalizer / Foundation). */
public expect fun normalizeUnicode(value: String, form: UnicodeForm): String
