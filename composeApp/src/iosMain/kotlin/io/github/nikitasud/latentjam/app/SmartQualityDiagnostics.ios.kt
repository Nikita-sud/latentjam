/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import platform.Foundation.NSProcessInfo

internal actual fun smartQualityDiagnosticSeeds(): List<String> =
    (NSProcessInfo.processInfo.environment[SMART_QUALITY_ENV] as? String)
        ?.split('|')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()

private const val SMART_QUALITY_ENV = "LATENTJAM_SMART_QUALITY_SEEDS"
