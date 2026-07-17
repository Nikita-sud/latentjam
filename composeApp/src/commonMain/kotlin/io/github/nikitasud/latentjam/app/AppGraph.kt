/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.di.smartEngineModule
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication

/**
 * The app's single composition root, shared verbatim by the Android and iOS
 * entry points.
 *
 * A SCOPED Koin application (not the `startKoin` global) — nothing else in
 * the process can collide with it, and `by lazy` gives thread-safe,
 * exactly-once initialization on both JVM and Kotlin/Native, whichever
 * platform entry touches it first.
 *
 * Resolving [engine] is allocation-only (the engine singleton defers all
 * heavy work to its suspend functions), so it is safe to call while building
 * the very first UI frame.
 */
object AppGraph {

    private val koinApp: KoinApplication by lazy {
        koinApplication {
            modules(smartEngineModule)
        }
    }

    /** The process-wide similarity engine. */
    val engine: SimilarityEngine
        get() = koinApp.koin.get()
}
