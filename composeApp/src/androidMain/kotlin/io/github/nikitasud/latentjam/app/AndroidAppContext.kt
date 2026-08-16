/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context

/**
 * The application context for androidMain code with no composition or framework entry point
 * (expect/actual suspend readers). Set once in [LatentJamApplication.onCreate], before any
 * caller can run.
 */
internal object AndroidAppContext {
    lateinit var value: Context
}
