/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * True where common tests run as Kotlin/Native (the iOS simulator): debug Native code runs tens of times slower than the
 * JVM, and a CI macOS runner slower still, so a long workload proves its point on the JVM and a smaller one here.
 */
internal expect val NATIVE_TEST: Boolean
